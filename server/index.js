const { WebSocketServer, WebSocket } = require('ws');
const http = require('http');
const mongoose = require('mongoose');

const PORT = process.env.PORT || 8080;
const MONGO_URI = process.env.MONGODB_URI || '';

// ── MongoDB Schemas ────────────────────────────────────────────────────────
const settingsSchema = new mongoose.Schema({
  pairCode:       { type: String, index: true, unique: true },
  blockedApps:    { type: [String], default: [] },
  keywords:       { type: [String], default: [] },
  geofence:       { type: Object,  default: null },
  schedule:       { type: Object,  default: null },
  lastLocation:   { type: Object,  default: null },
  updatedAt:      { type: Date,    default: Date.now }
});
const Settings = mongoose.model('Settings', settingsSchema);

// ── Connect MongoDB ────────────────────────────────────────────────────────
if (MONGO_URI) {
  mongoose.connect(MONGO_URI)
    .then(() => console.log('[DB] MongoDB connected'))
    .catch(e => console.error('[DB] MongoDB error:', e.message));
} else {
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

// ── HTTP server + health endpoint ─────────────────────────────────────────
const startTime = Date.now();
const server = http.createServer((req, res) => {
  if (req.url === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ status: 'ok', uptime: Math.floor((Date.now() - startTime) / 1000) }));
    return;
  }
  res.writeHead(200, { 'Content-Type': 'text/plain' });
  res.end('GuardianEye WS Relay OK\n');
});

// ── WebSocket relay ────────────────────────────────────────────────────────
const wss = new WebSocketServer({ server, path: '/api/ws' });

// Map: pairCode -> { parent: ws, child: ws }
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
}

// Server-side ping every 30s — keeps Railway idle connections alive
setInterval(() => {
  wss.clients.forEach(ws => {
    if (ws.readyState === WebSocket.OPEN) {
      try { ws.ping(); } catch {}
    }
  });
}, 30000);

wss.on('connection', (ws) => {
  let pairCode = null;
  let role = null;

  const registrationTimeout = setTimeout(() => {
    if (!pairCode) {
      try { ws.close(1008, 'registration_timeout'); } catch {}
    }
  }, 30000);

  ws.on('message', async (raw) => {
    let msg;
    try { msg = JSON.parse(raw.toString()); } catch { return; }

    if (msg.type === 'register') {
      clearTimeout(registrationTimeout);
      pairCode = String(msg.pair_code || '').trim();
      role = msg.role === 'child' ? 'child' : 'parent';

      if (!pairCode) {
        ws.send(JSON.stringify({ type: 'error', reason: 'missing_pair_code' }));
        return;
      }

      const room = getOrCreate(pairCode);
      if (room[role] && room[role] !== ws) {
        try { room[role].close(1000, 'replaced'); } catch {}
      }
      room[role] = ws;

      ws.send(JSON.stringify({ type: 'auth_ok', role }));

      const peer = role === 'parent' ? room.child : room.parent;
      if (peer && peer.readyState === WebSocket.OPEN) {
        peer.send(JSON.stringify({ type: 'peer_connected' }));
        ws.send(JSON.stringify({ type: 'peer_connected' }));
      }

      // Send saved settings to child on reconnect
      if (role === 'child') {
        const saved = await loadSettings(pairCode);
        if (saved) {
          const restore = {};
          if (saved.blockedApps?.length) restore.blocked_apps = saved.blockedApps;
          if (saved.keywords?.length)    restore.keywords     = saved.keywords;
          if (saved.geofence)            restore.geofence     = saved.geofence;
          if (saved.schedule)            restore.schedule     = saved.schedule;
          if (Object.keys(restore).length) {
            ws.send(JSON.stringify({ type: 'restore_settings', ...restore }));
          }
        }
      }
      return;
    }

    if (!pairCode || !role) return;

    if (msg.type === 'ping') {
      ws.send(JSON.stringify({ type: 'pong' }));
      return;
    }

    // Persist settings sent by parent
    if (role === 'parent') {
      const patch = {};
      if (msg.type === 'set_blocked_apps' && msg.apps)    patch.blockedApps = msg.apps;
      if (msg.type === 'set_keywords'     && msg.keywords) patch.keywords    = msg.keywords;
      if (msg.type === 'set_geofence')     patch.geofence = { lat: msg.lat, lng: msg.lng, radius: msg.radius };
      if (msg.type === 'set_schedule')     patch.schedule = msg.schedule;
      if (Object.keys(patch).length) await saveSettings(pairCode, patch);
    }

    // Persist last known location sent by child
    if (role === 'child' && msg.type === 'location') {
      await saveSettings(pairCode, { lastLocation: { lat: msg.lat, lng: msg.lng, accuracy: msg.accuracy, ts: Date.now() } });
    }

    // Relay to peer
    const room = rooms.get(pairCode);
    if (!room) return;
    const peer = role === 'parent' ? room.child : room.parent;
    if (peer && peer.readyState === WebSocket.OPEN) {
      peer.send(raw.toString());
    }
  });

  ws.on('close', () => {
    clearTimeout(registrationTimeout);
    if (pairCode && role) cleanup(pairCode, role);
  });

  ws.on('error', () => {
    clearTimeout(registrationTimeout);
    if (pairCode && role) cleanup(pairCode, role);
  });
});

server.listen(PORT, () => {
  console.log(`[GuardianEye] WS Relay running on port ${PORT}`);
});
