const { WebSocketServer, WebSocket } = require('ws');
const http = require('http');
const mongoose = require('mongoose');

const PORT        = process.env.PORT || 8080;
const MONGO_URI   = process.env.MONGODB_URI || '';
const REDIS_URL   = process.env.UPSTASH_REDIS_REST_URL || '';
const REDIS_TOK   = process.env.UPSTASH_REDIS_REST_TOKEN || '';
const GEMINI_KEY  = process.env.GEMINI_API_KEY || '';
const FCM_KEY     = process.env.FIREBASE_SERVER_KEY || '';
const VERSION     = '3.0';

// ── Redis helpers ──────────────────────────────────────────────────────────
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
      headers: { Authorization: `Bearer ${REDIS_TOK}` } });
    const j = await r.json(); return j.result ?? null;
  } catch (_) { return null; }
}

// ── Gemini AI caller ───────────────────────────────────────────────────────
async function callGemini(prompt) {
  if (!GEMINI_KEY) return null;
  try {
    const resp = await fetch(
      `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-lite:generateContent?key=${GEMINI_KEY}`,
      { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ contents: [{ parts: [{ text: prompt }] }] }),
        signal: AbortSignal.timeout(30000) }
    );
    const data = await resp.json();
    const raw  = data.candidates?.[0]?.content?.parts?.[0]?.text || '';
    return raw.replace(/```json\n?|```\n?/g, '').trim();
  } catch (e) { console.error('[Gemini]', e.message); return null; }
}

async function callGeminiJson(prompt) {
  const raw = await callGemini(prompt);
  if (!raw) return null;
  try { return JSON.parse(raw); } catch { return null; }
}

// ── FCM sender ────────────────────────────────────────────────────────────
async function sendFcm(token, title, body) {
  if (!FCM_KEY || !token) return;
  try {
    await fetch('https://fcm.googleapis.com/fcm/send', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `key=${FCM_KEY}` },
      body: JSON.stringify({ to: token, notification: { title, body }, priority: 'high' })
    });
  } catch (e) { console.error('[FCM]', e.message); }
}

// ── MongoDB Schemas ────────────────────────────────────────────────────────
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
  appUsageHistory:{ type: [Object], default: [] },
  keywordAlerts:  { type: Number,  default: 0 },
  screenTime:     { type: Number,  default: 0 },
  updatedAt:      { type: Date,    default: Date.now }
});
const Settings = mongoose.model('Settings', settingsSchema);

const messageSchema = new mongoose.Schema({
  pairCode:        { type: String, index: true },
  sender:          { type: String, enum: ['child', 'parent'] },
  text:            { type: String, maxlength: 500 },
  isPreset:        { type: Boolean, default: false },
  isDistress:      { type: Boolean, default: false },
  urgency:         { type: String, default: 'low' },
  suggestedReply:  { type: String, default: '' },
  ts:              { type: Date, default: Date.now }
});
const Message = mongoose.model('Message', messageSchema);

const aiResultSchema = new mongoose.Schema({
  pairCode: { type: String, index: true },
  type:     { type: String },   // behavior | mood | danger
  result:   { type: Object },
  ts:       { type: Date, default: Date.now }
});
const AiResult = mongoose.model('AiResult', aiResultSchema);

// ── DB connect ─────────────────────────────────────────────────────────────
let mongoStatus = 'disconnected';
if (MONGO_URI) {
  mongoose.connect(MONGO_URI)
    .then(() => { mongoStatus = 'connected'; console.log('[DB] MongoDB connected'); })
    .catch(e => { mongoStatus = 'error: ' + e.message; });
} else { mongoStatus = 'no_uri'; }

async function loadSettings(pc) { try { return await Settings.findOne({ pairCode: pc }); } catch { return null; } }
async function saveSettings(pc, patch) {
  try { await Settings.findOneAndUpdate({ pairCode: pc }, { ...patch, pairCode: pc, updatedAt: new Date() }, { upsert: true }); }
  catch(e) { console.error('[DB]', e.message); }
}

// ── AI Feature 1: Behavior Score (runs at 11 PM daily) ────────────────────
async function runBehaviorScore(pairCode) {
  const s = await loadSettings(pairCode);
  if (!s) return;
  const data = {
    appUsageMinutes:  s.screenTime || 0,
    keywordAlerts:    s.keywordAlerts || 0,
    nightUsage:       false,
    appList:          (s.appUsageHistory || []).slice(-10),
    lastLocation:     s.lastLocation
  };
  const prompt = `You are a child safety AI. Analyze this child's digital behavior and return ONLY valid JSON with:
{"score":0-100,"grade":"A/B/C/D/F","highlights":["3 positives"],"concerns":["3 concerns"],"suggestions":["3 suggestions"],"mood_estimate":"happy/normal/anxious/sad"}
Data: ${JSON.stringify(data)}`;
  const result = await callGeminiJson(prompt);
  if (!result) return;
  await AiResult.create({ pairCode, type: 'behavior', result });
  console.log(`[AI] Behavior score for ${pairCode}: ${result.score}`);
}

// ── AI Feature 2: Mood Detection (every 2h) ───────────────────────────────
async function runMoodDetection(pairCode) {
  const s = await loadSettings(pairCode);
  if (!s) return;
  const signals = {
    appSwitchFrequency: Math.floor(Math.random() * 30),
    socialMediaMinutes: 0,
    gamingMinutes:      0,
    nightUsage:         new Date().getHours() >= 22,
    keywordAlerts:      s.keywordAlerts || 0
  };
  const prompt = `Based on behavioral signals from a child's phone, estimate emotional state. Return ONLY valid JSON:
{"mood":"happy/normal/anxious/sad/stressed","confidence":0-100,"reason":"string","alert_parent":true/false}
Signals: ${JSON.stringify(signals)}`;
  const result = await callGeminiJson(prompt);
  if (!result) return;
  await AiResult.create({ pairCode, type: 'mood', result });
  if (result.alert_parent && s.fcmToken) {
    await sendFcm(s.fcmToken, '⚠️ GuardianEye AI Alert',
      `Your child seems ${result.mood} right now. ${result.reason}`);
  }
  return result;
}

// ── AI Feature 3: Danger Zone (triggered on location update) ─────────────
async function checkDangerZone(pairCode, lat, lng) {
  const s = await loadSettings(pairCode);
  if (!s) return;
  const hour = new Date().getHours();
  const timeStr = `${hour}:00`;
  const isOutsideGeofence = s.geofence
    ? getDistance(lat, lng, s.geofence.lat, s.geofence.lng) > s.geofence.radius
    : false;
  if (!isOutsideGeofence) return;
  const prompt = `A child aged ~13 is at coordinates ${lat},${lng} at ${timeStr}.
They may be outside their safe zone. Assess safety risk. Return ONLY valid JSON:
{"risk_level":"low/medium/high","reason":"string","alert_parent":true/false,"suggested_action":"string"}`;
  const result = await callGeminiJson(prompt);
  if (!result) return;
  await AiResult.create({ pairCode, type: 'danger', result });
  if (result.risk_level === 'high' && s.fcmToken) {
    await sendFcm(s.fcmToken, '🚨 Danger Zone Alert',
      `Risk: ${result.reason}. ${result.suggested_action}`);
    const room = rooms.get(pairCode);
    if (room?.parent?.readyState === WebSocket.OPEN)
      room.parent.send(JSON.stringify({ type: 'danger_zone_alert', ...result, lat, lng }));
  }
}

function getDistance(lat1, lng1, lat2, lng2) {
  const R = 6371000;
  const dLat = (lat2 - lat1) * Math.PI / 180;
  const dLng = (lng2 - lng1) * Math.PI / 180;
  const a = Math.sin(dLat/2)**2 + Math.cos(lat1*Math.PI/180) * Math.cos(lat2*Math.PI/180) * Math.sin(dLng/2)**2;
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
}

// ── AI Feature 4: Voice of Child (message distress check) ─────────────────
async function analyzeChildMessage(pairCode, text) {
  const prompt = `A child sent this message to their parent: '${text}'
Detect if this indicates distress or emergency. Return ONLY valid JSON:
{"is_distress":true/false,"urgency":"low/medium/high","suggested_parent_response":"string"}`;
  const result = await callGeminiJson(prompt);
  return result || { is_distress: false, urgency: 'low', suggested_parent_response: 'OK noted.' };
}

// ── Cron scheduler (no external deps) ────────────────────────────────────
let lastMoodRun = 0;
let lastBehaviorDate = '';

async function runScheduledJobs() {
  const now  = new Date();
  const hour = now.getHours();
  const dateStr = now.toDateString();

  // Behavior score at 11 PM daily
  if (hour === 23 && dateStr !== lastBehaviorDate) {
    lastBehaviorDate = dateStr;
    console.log('[Cron] Running nightly behavior scores...');
    for (const pc of rooms.keys()) await runBehaviorScore(pc);
  }

  // Mood detection every 2h
  if (Date.now() - lastMoodRun > 2 * 60 * 60 * 1000) {
    lastMoodRun = Date.now();
    console.log('[Cron] Running mood detection...');
    for (const pc of rooms.keys()) await runMoodDetection(pc).catch(() => {});
  }
}
setInterval(runScheduledJobs, 5 * 60 * 1000);

// ── HTTP routing helper ────────────────────────────────────────────────────
function parseUrl(url) {
  const [path, qs] = url.split('?');
  const parts = path.split('/').filter(Boolean);
  return { parts, path };
}
function readBody(req) {
  return new Promise(res => {
    let d = ''; req.on('data', c => d += c); req.on('end', () => {
      try { res(JSON.parse(d)); } catch { res({}); }
    });
  });
}
function json(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json',
    'Access-Control-Allow-Origin': '*' });
  res.end(body);
}

// ── HTTP server ────────────────────────────────────────────────────────────
const startTime = Date.now();
const server = http.createServer(async (req, res) => {
  if (req.method === 'OPTIONS') {
    res.writeHead(204, { 'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type' });
    return res.end();
  }

  const { parts } = parseUrl(req.url);
  // GET /health
  if (parts[0] === 'health') {
    let redisOk = false;
    if (REDIS_URL) { try { await redisSet('__ping','1',10); redisOk=true; } catch {} }
    return json(res, 200, { status:'ok', version:VERSION,
      uptime: Math.floor((Date.now()-startTime)/1000),
      rooms: rooms.size, mongodb: mongoStatus,
      redis: REDIS_URL?(redisOk?'connected':'error'):'no_url',
      gemini: GEMINI_KEY?'configured':'not_configured' });
  }
  // GET /api/config
  if (parts[0]==='api' && parts[1]==='config') {
    const domain = process.env.RAILWAY_STATIC_URL || process.env.RAILWAY_PUBLIC_DOMAIN || '';
    return json(res, 200, { ws_url: domain?`wss://${domain}/api/ws`:null, version:VERSION });
  }
  // GET /api/online/:code
  if (parts[0]==='api' && parts[1]==='online' && parts[2]) {
    const code = parts[2];
    const cached = await redisGet(`status:${code}`);
    const online = cached === 'online' || rooms.get(code)?.child?.readyState === WebSocket.OPEN;
    return json(res, 200, { online, code });
  }
  // GET /api/messages/:code
  if (parts[0]==='api' && parts[1]==='messages' && parts[2] && req.method==='GET') {
    const code = parts[2];
    try {
      const msgs = await Message.find({ pairCode:code }).sort({ ts:-1 }).limit(50).lean();
      return json(res, 200, { messages: msgs.reverse() });
    } catch { return json(res, 200, { messages:[] }); }
  }
  // POST /api/messages/:code  (parent sends message via HTTP)
  if (parts[0]==='api' && parts[1]==='messages' && parts[2] && req.method==='POST') {
    const code = parts[2];
    const body = await readBody(req);
    const text = String(body.text || '').slice(0,500);
    if (!text) return json(res, 400, { error:'empty message' });
    const msg = await Message.create({ pairCode:code, sender:'parent', text });
    const room = rooms.get(code);
    if (room?.child?.readyState === WebSocket.OPEN)
      room.child.send(JSON.stringify({ type:'parent_message', text, ts: Date.now() }));
    return json(res, 200, { ok:true, id: msg._id });
  }
  // GET /api/ai/insights/:code
  if (parts[0]==='api' && parts[1]==='ai' && parts[2]==='insights' && parts[3]) {
    const code = parts[3];
    try {
      const behavior = await AiResult.findOne({ pairCode:code, type:'behavior' }).sort({ts:-1}).lean();
      const mood     = await AiResult.findOne({ pairCode:code, type:'mood'    }).sort({ts:-1}).lean();
      const danger   = await AiResult.findOne({ pairCode:code, type:'danger'  }).sort({ts:-1}).lean();
      return json(res, 200, {
        behavior: behavior?.result || null,
        mood:     mood?.result     || null,
        danger:   danger?.result   || null,
        updatedAt: behavior?.ts    || null
      });
    } catch { return json(res, 200, { behavior:null, mood:null, danger:null }); }
  }
  // POST /api/ai/analyze/:code  (trigger on-demand analysis)
  if (parts[0]==='api' && parts[1]==='ai' && parts[2]==='analyze' && parts[3] && req.method==='POST') {
    const code = parts[3];
    runBehaviorScore(code).catch(() => {});
    return json(res, 202, { ok:true, message:'Analysis started' });
  }

  res.writeHead(200,{'Content-Type':'text/plain'});
  res.end('GuardianEye Relay v'+VERSION+'\n');
});

// ── WebSocket relay ────────────────────────────────────────────────────────
const wss = new WebSocketServer({ server, path: '/api/ws' });
const rooms = new Map();

function getOrCreate(pc) {
  if (!rooms.has(pc)) rooms.set(pc, { parent:null, child:null });
  return rooms.get(pc);
}
function cleanup(pc, role) {
  const room = rooms.get(pc);
  if (!room) return;
  room[role] = null;
  if (!room.parent && !room.child) rooms.delete(pc);
  const peer = role==='parent'?room.child:room.parent;
  if (peer?.readyState===WebSocket.OPEN)
    peer.send(JSON.stringify({type:'peer_disconnected'}));
  if (role==='child') {
    redisSet(`status:${pc}`, 'offline', 300).catch(()=>{});
    saveSettings(pc, { lastSeen: new Date() }).catch(()=>{});
  }
}

setInterval(() => {
  wss.clients.forEach(ws => { if(ws.readyState===WebSocket.OPEN) try{ws.ping();}catch{} });
}, 30000);

wss.on('connection', (ws) => {
  let pairCode=null, role=null;
  const regTimeout = setTimeout(() => {
    if (!pairCode) try{ws.close(1008,'registration_timeout');}catch{}
  }, 30000);

  ws.on('message', async (raw) => {
    let msg;
    try { msg = JSON.parse(raw.toString()); } catch { return; }

    if (msg.type==='register') {
      clearTimeout(regTimeout);
      pairCode = String(msg.pair_code||'').trim();
      role     = msg.role==='child'?'child':'parent';
      if (!pairCode) { ws.send(JSON.stringify({type:'error',reason:'missing_pair_code'})); return; }
      const room = getOrCreate(pairCode);
      if (room[role]&&room[role]!==ws) try{room[role].close(1000,'replaced');}catch{}
      room[role]=ws;
      ws.send(JSON.stringify({type:'auth_ok',role}));
      const peer = role==='parent'?room.child:room.parent;
      if (peer?.readyState===WebSocket.OPEN) {
        peer.send(JSON.stringify({type:'peer_connected'}));
        ws.send(JSON.stringify({type:'peer_connected'}));
      }
      if (role==='child') {
        redisSet(`status:${pairCode}`,'online',300).catch(()=>{});
        const saved = await loadSettings(pairCode);
        if (saved) {
          const restore={};
          if(saved.blockedApps?.length)    restore.blocked_apps    = saved.blockedApps;
          if(saved.keywords?.length)       restore.keywords        = saved.keywords;
          if(saved.blockedDomains?.length) restore.blocked_domains = saved.blockedDomains;
          if(saved.geofence)               restore.geofence        = saved.geofence;
          if(saved.offSchedule)            restore.schedule        = saved.offSchedule;
          if(Object.keys(restore).length)  ws.send(JSON.stringify({type:'restore_settings',...restore}));
        }
      }
      return;
    }

    if (!pairCode||!role) return;
    if (msg.type==='ping') { ws.send(JSON.stringify({type:'pong'})); return; }

    // ── Parent-only settings ──────────────────────────────────────────────
    if (role==='parent') {
      if (msg.type==='set_blocked_apps' && msg.apps)
        saveSettings(pairCode,{blockedApps:msg.apps}).catch(()=>{});
      if (msg.type==='set_keywords' && msg.keywords)
        saveSettings(pairCode,{keywords:msg.keywords}).catch(()=>{});
      if (msg.type==='set_blocked_domains' && msg.domains)
        saveSettings(pairCode,{blockedDomains:msg.domains}).catch(()=>{});
      if (msg.type==='set_geofence')
        saveSettings(pairCode,{geofence:{lat:msg.lat,lng:msg.lng,radius:msg.radius}}).catch(()=>{});
      if (msg.type==='set_schedule')
        saveSettings(pairCode,{offSchedule:{off_hour_start:msg.off_hour_start,off_hour_end:msg.off_hour_end}}).catch(()=>{});
    }

    // ── Child-only events ─────────────────────────────────────────────────
    if (role==='child') {
      if (msg.type==='fcm_token' && msg.token)
        saveSettings(pairCode,{fcmToken:msg.token}).catch(()=>{});

      if (msg.type==='location') {
        const locObj={lat:msg.lat,lng:msg.lng,accuracy:msg.accuracy,ts:Date.now()};
        redisSet(`location:${pairCode}`,JSON.stringify(locObj),600).catch(()=>{});
        saveSettings(pairCode,{lastLocation:locObj}).catch(()=>{});
        // Trigger danger zone AI check (async, non-blocking)
        checkDangerZone(pairCode, msg.lat, msg.lng).catch(()=>{});
      }

      if (msg.type==='keyword_alert')
        Settings.findOneAndUpdate({pairCode},{$inc:{keywordAlerts:1}}).catch(()=>{});

      // ── Feature 4: Voice of Child message ────────────────────────────────
      if (msg.type==='child_message' && msg.text) {
        const text = String(msg.text).slice(0,500);
        // Run Gemini distress analysis async
        analyzeChildMessage(pairCode, text).then(async (analysis) => {
          const savedMsg = await Message.create({
            pairCode, sender:'child', text,
            isPreset: !!msg.isPreset,
            isDistress: analysis.is_distress,
            urgency: analysis.urgency,
            suggestedReply: analysis.suggested_parent_response
          });
          const room = rooms.get(pairCode);
          if (room?.parent?.readyState===WebSocket.OPEN) {
            room.parent.send(JSON.stringify({
              type:'child_message',
              text, ts:Date.now(),
              isDistress: analysis.is_distress,
              urgency: analysis.urgency,
              suggestedReply: analysis.suggested_parent_response,
              id: savedMsg._id.toString()
            }));
          }
          if (analysis.is_distress) {
            const s = await loadSettings(pairCode);
            if (s?.fcmToken)
              await sendFcm(s.fcmToken,'⚠️ Child Message Alert',
                `Urgency: ${analysis.urgency} — "${text.slice(0,60)}"`);
          }
        }).catch(()=>{});
        return;
      }
    }

    // ── Relay to peer ─────────────────────────────────────────────────────
    const room = rooms.get(pairCode);
    if (!room) return;
    const peer = role==='parent'?room.child:room.parent;
    if (peer?.readyState===WebSocket.OPEN) peer.send(raw.toString());
  });

  ws.on('close', () => { clearTimeout(regTimeout); if(pairCode&&role) cleanup(pairCode,role); });
  ws.on('error', () => { clearTimeout(regTimeout); if(pairCode&&role) cleanup(pairCode,role); });
});

server.listen(PORT, () => console.log(`[GuardianEye] v${VERSION} on port ${PORT}`));
