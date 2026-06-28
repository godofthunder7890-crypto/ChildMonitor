const { WebSocketServer, WebSocket } = require('ws');
const http = require('http');
const mongoose = require('mongoose');

const PORT = process.env.PORT || 8080;
const MONGO_URI = process.env.MONGODB_URI || '';
const REDIS_URL  = process.env.UPSTASH_REDIS_REST_URL || '';
const REDIS_TOK  = process.env.UPSTASH_REDIS_REST_TOKEN || '';
const VERSION    = '2.0';

// ── Upstash Redis helpers (fetch — no extra package) ─────────────────────────
async function redisSet(key, value, exSeconds) {
  if (!REDIS_URL || !REDIS_TOK) return;
  try {
    const url = exSeconds
      ? `${REDIS_URL}/set/${encodeURIComponent(key)}/${encodeURIComponent(value)}?EX=${exSeconds}`
      : `${REDIS_URL}/set/${encodeURIComponent(key)}/${encodeURIComponent(value)}`;
    await fetch(url, { headers: { Authorization: `Bearer ${REDIS_TOK}` } });
  } catch (_) {}
}

async function redisGet(key) {
  if (!REDIS_URL || !REDIS_TOK) return null;
  try {
    const r = await fetch(`${REDIS_URL}/get/${encodeURIComponent(key)}`, {
      headers: { Authorization: `Bearer ${REDIS_TOK}` }
    });
    const j = await r.json();
    return j.result ?? null;
  } catch (_) { return null; }
}

// ── MongoDB Schema ─────────────────────────────────────────────────────────
const settingsSchema = new mongoose.Schema({
  pairCode:       { type: String, index: true, unique: true },
  fcmToken:       { type: String, default: '' },
  blockedApps:    { type: [String], default: [] },
  keywords:       { type: [String], default: [] },
  blockedDomains: { type: [String], default: [] },
  allowedDomains: { type: [String], default: [] },
  geofence:       { type: Object,  default: null },
  offSchedule:    { type: Object,  default: null },
  lastLocation:   { type: Object,  default: null },
  lastSeen:       { type: Date,    default: null },
  updatedAt:      { type: Date,    default: Date.now }
});
const Settings = mongoose.model('Settings', settingsSchema);

// ── MongoDB connect ─────────────────────────────────────────────────────────
let mongoStatus = 'disconnected';
if (MONGO_URI) {
  mongoose.connect(MONGO_URI)
    .then(() => { mongoStatus = 'connected'; console.log('[DB] MongoDB connected'); })
    .catch(e => { mongoStatus = 'error: ' + e.message; console.error('[DB]', e.message); });
} else {
  mongoStatus = 'no_uri';
  console.warn('[DB] MONGODB_URI not set — running without persistence');
}

async function loadSettings(pairCode) {
  if (!MONGO_URI) return null;
  try { return await Settings.findOne({ pairCode }); } catch { return null; }
}

async function saveSettings(pairCode, patch) {
  if (!MONGO_URI) return;
  try {
    await Settings.findOneAndUpdate(
      { pairCode },
      { ...patch, pairCode, updatedAt: new Date() },
      { upsert: true, new: true }
    );
  } catch(e) { console.error('[DB] save error:', e.message); }
}

// ── HTTP server ────────────────────────────────────────────────────────────
const startTime = Date.now();
const server = http.createServer(async (req, res) => {
  if (req.url === '/health') {
    let redisOk = false;
    if (REDIS_URL) {
      try { await redisSet('__ping', '1', 10); redisOk = true; } catch (_) {}
    }
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      status: 'ok',
      version: VERSION,
      uptime: Math.floor((Date.now() - startTime) / 1000),
      rooms: rooms.size,
      mongodb: mongoStatus,
      redis: REDIS_URL ? (redisOk ? 'connected' : 'error') : 'no_url'
    }));
    return;
  }
  if (req.url === '/api/config') {
    const domain = process.env.RAILWAY_STATIC_URL || process.env.RAILWAY_PUBLIC_DOMAIN || '';
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      ws_url: domain ? `wss://${domain}/api/ws` : null,
      version: VERSION
    }));
    return;
  }
  res.writeHead(200, { 'Content-Type': 'text/plain' });
  res.end('GuardianEye WS Relay v' + VERSION + '\n');
});

// ── WebSocket relay ────────────────────────────────────────────────────────
const wss = new WebSocketServer({ server, path: '/api/ws' });
const rooms = new Map();

function getOrCreate(pairCode) {
  if (!rooms.has(pairCode)) rooms.set(pairCode, { parent: null, child: null });
  return rooms.get(pairCode);
}

function cleanup(pairCode, role) {
  const room = rooms.get(pairCode);
  if (!room) return;
  room[role] = null;
  if (!room.parent && !room.child) rooms.delete(pairCode);
  const peer = role === 'parent' ? room.child : room.parent;
  if (peer && peer.readyState === WebSocket.OPEN) {
    peer.send(JSON.stringify({ type: 'peer_disconnected' }));
  }
  if (role === 'child') {
    redisSet(`status:${pairCode}`, 'offline', 300).catch(() => {});
    saveSettings(pairCode, { lastSeen: new Date() }).catch(() => {});
  }
}

// Server-side ping every 30s — keeps Railway idle connections alive
setInterval(() => {
  wss.clients.forEach(ws => {
    if (ws.readyState === WebSocket.OPEN) try { ws.ping(); } catch {}
  });
}, 30000);

wss.on('connection', (ws) => {
  let pairCode = null;
  let role = null;

  const registrationTimeout = setTimeout(() => {
    if (!pairCode) try { ws.close(1008, 'registration_timeout'); } catch {}
  }, 30000);

  ws.on('message', async (raw) => {
    let msg;
    try { msg = JSON.parse(raw.toString()); } catch { return; }

    if (msg.type === 'register') {
      clearTimeout(registrationTimeout);
      pairCode = String(msg.pair_code || '').trim();
      role     = msg.role === 'child' ? 'child' : 'parent';
      if (!pairCode) { ws.send(JSON.stringify({ type: 'error', reason: 'missing_pair_code' })); return; }

      const room = getOrCreate(pairCode);
      if (room[role] && room[role] !== ws) try { room[role].close(1000, 'replaced'); } catch {}
      room[role] = ws;
      ws.send(JSON.stringify({ type: 'auth_ok', role }));

      const peer = role === 'parent' ? room.child : room.parent;
      if (peer && peer.readyState === WebSocket.OPEN) {
        peer.send(JSON.stringify({ type: 'peer_connected' }));
        ws.send(JSON.stringify({ type: 'peer_connected' }));
      }

      if (role === 'child') {
        redisSet(`status:${pairCode}`, 'online', 300).catch(() => {});
        const saved = await loadSettings(pairCode);
        if (saved) {
          const restore = {};
          if (saved.blockedApps?.length)    restore.blocked_apps    = saved.blockedApps;
          if (saved.keywords?.length)       restore.keywords        = saved.keywords;
          if (saved.blockedDomains?.length) restore.blocked_domains = saved.blockedDomains;
          if (saved.geofence)               restore.geofence        = saved.geofence;
          if (saved.offSchedule)            restore.schedule        = saved.offSchedule;
          if (Object.keys(restore).length)
            ws.send(JSON.stringify({ type: 'restore_settings', ...restore }));
        }
      }
      return;
    }

    if (!pairCode || !role) return;
    if (msg.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }

    if (role === 'parent') {
      if (msg.type === 'set_blocked_apps'    && msg.apps)
        saveSettings(pairCode, { blockedApps: msg.apps }).catch(() => {});
      if (msg.type === 'set_keywords'        && msg.keywords)
        saveSettings(pairCode, { keywords: msg.keywords }).catch(() => {});
      if (msg.type === 'set_blocked_domains' && msg.domains)
        saveSettings(pairCode, { blockedDomains: msg.domains }).catch(() => {});
      if (msg.type === 'set_geofence')
        saveSettings(pairCode, { geofence: { lat: msg.lat, lng: msg.lng, radius: msg.radius } }).catch(() => {});
      if (msg.type === 'set_schedule')
        saveSettings(pairCode, { offSchedule: { off_hour_start: msg.off_hour_start, off_hour_end: msg.off_hour_end } }).catch(() => {});
    }

    if (role === 'child' && msg.type === 'fcm_token' && msg.token)
      saveSettings(pairCode, { fcmToken: msg.token }).catch(() => {});

    if (role === 'child' && msg.type === 'location') {
      const locObj = { lat: msg.lat, lng: msg.lng, accuracy: msg.accuracy, ts: Date.now() };
      redisSet(`location:${pairCode}`, JSON.stringify(locObj), 600).catch(() => {});
      saveSettings(pairCode, { lastLocation: locObj }).catch(() => {});
    }

    const room = rooms.get(pairCode);
    if (!room) return;
    const peer = role === 'parent' ? room.child : room.parent;
    if (peer && peer.readyState === WebSocket.OPEN) peer.send(raw.toString());
  });

  ws.on('close', () => { clearTimeout(registrationTimeout); if (pairCode && role) cleanup(pairCode, role); });
  ws.on('error', () => { clearTimeout(registrationTimeout); if (pairCode && role) cleanup(pairCode, role); });
});

server.listen(PORT, () => console.log(`[GuardianEye] WS Relay v${VERSION} on port ${PORT}`));
