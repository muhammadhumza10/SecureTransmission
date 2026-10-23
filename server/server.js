// Secure Transmission relay.
//   Phone     --> wss://HOST/publish?key=SECRET   (sends binary JPEG frames)
//   Dashboard --> wss://HOST/view?key=SECRET      (receives binary JPEG frames)
// Frames are forwarded straight from memory to viewers and then discarded.
// Nothing is written to disk or kept after it has been forwarded.

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { WebSocketServer } = require('ws');

const PORT = process.env.PORT || 8080;
const STREAM_KEY = process.env.STREAM_KEY;
if (!STREAM_KEY || STREAM_KEY.length < 8) {
  console.error('Set the STREAM_KEY environment variable (at least 8 characters).');
  process.exit(1);
}

const MAX_FRAME_BYTES = 2 * 1024 * 1024;  // reject anything bigger than 2 MB
const MAX_VIEWERS = 5;
const SLOW_VIEWER_BYTES = 1024 * 1024;    // skip frames for viewers that are falling behind

function keyOk(candidate) {
  const a = Buffer.from(String(candidate || ''));
  const b = Buffer.from(STREAM_KEY);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

const indexHtml = path.join(__dirname, 'public', 'index.html');
const server = http.createServer((req, res) => {
  const url = req.url.split('?')[0];
  if (url === '/healthz') { res.writeHead(200); return res.end('ok'); }
  if (url === '/' || url === '/index.html') {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
    return fs.createReadStream(indexHtml).pipe(res);
  }
  res.writeHead(404); res.end('Not found');
});

const wss = new WebSocketServer({ noServer: true, maxPayload: MAX_FRAME_BYTES });
let publisher = null;
const viewers = new Set();

function broadcastStatus() {
  const msg = JSON.stringify({ type: 'status', publisher: !!publisher, viewers: viewers.size });
  for (const v of viewers) if (v.readyState === 1) v.send(msg);
}

server.on('upgrade', (req, socket, head) => {
  const u = new URL(req.url, 'http://x');
  if (!keyOk(u.searchParams.get('key'))) {
    socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n'); return socket.destroy();
  }
  if (u.pathname !== '/publish' && u.pathname !== '/view') {
    socket.write('HTTP/1.1 404 Not Found\r\n\r\n'); return socket.destroy();
  }
  if (u.pathname === '/view' && viewers.size >= MAX_VIEWERS) {
    socket.write('HTTP/1.1 503 Service Unavailable\r\n\r\n'); return socket.destroy();
  }
  wss.handleUpgrade(req, socket, head, (ws) => {
    ws.isAlive = true;
    ws.on('pong', () => { ws.isAlive = true; });
    ws.on('error', () => {});
    if (u.pathname === '/publish') onPublisher(ws); else onViewer(ws);
  });
});

function onPublisher(ws) {
  if (publisher && publisher.readyState === 1) publisher.close(4000, 'replaced');
  publisher = ws;
  console.log('publisher connected');
  broadcastStatus();
  ws.on('message', (data, isBinary) => {
    if (!isBinary) return;
    for (const v of viewers) {
      if (v.readyState === 1 && v.bufferedAmount < SLOW_VIEWER_BYTES) v.send(data, { binary: true });
    }
  });
  ws.on('close', () => {
    if (publisher === ws) { publisher = null; console.log('publisher left'); broadcastStatus(); }
  });
}

function onViewer(ws) {
  viewers.add(ws);
  console.log('viewer connected (' + viewers.size + ')');
  ws.send(JSON.stringify({ type: 'status', publisher: !!publisher, viewers: viewers.size }));
  broadcastStatus();
  ws.on('close', () => { viewers.delete(ws); broadcastStatus(); });
}

// Keep connections alive through proxies and drop dead ones.
setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) { ws.terminate(); continue; }
    ws.isAlive = false;
    ws.ping();
  }
}, 20000);

server.listen(PORT, () => console.log('Relay listening on :' + PORT));
