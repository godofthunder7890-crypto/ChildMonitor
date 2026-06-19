const { WebSocketServer, WebSocket } = require('ws');
const http = require('http');

const PORT = process.env.PORT || 8080;

const server = http.createServer((req, res) => {
  res.writeHead(200, { 'Content-Type': 'text/plain' });
  res.end('WS Relay OK\n');
});

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

wss.on('connection', (ws) => {
  let pairCode = null;
  let role = null;

  // BUG FIX: Jo client connect karta tha lekin kabhi 'register' nahi bhejta tha woh
  // memory mein hamesha ke liye rehta tha (ghost connection / memory leak).
  // 30s timeout — agar register nahi aaya toh connection force-close karo.
  const registrationTimeout = setTimeout(() => {
    if (!pairCode) {
      try { ws.close(1008, 'registration_timeout'); } catch {}
    }
  }, 30000);

  ws.on('message', (raw) => {
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
      return;
    }

    if (!pairCode || !role) return;

    if (msg.type === 'ping') {
      ws.send(JSON.stringify({ type: 'pong' }));
      return;
    }

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
  console.log(`WS Relay running on port ${PORT}`);
});
