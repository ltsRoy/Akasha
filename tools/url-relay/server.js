import http from "node:http";
import os from "node:os";
import { WebSocket, WebSocketServer } from "ws";

const port = Number(process.env.PORT || 8787);
const host = process.env.HOST || "0.0.0.0";

const server = http.createServer((req, res) => {
  const publicUrl = relayUrl(req);
  res.writeHead(200, { "content-type": "text/html; charset=utf-8" });
  res.end(`<!doctype html>
<html>
<head>
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>MeshLink URL Relay</title>
  <style>
    body { font-family: system-ui, sans-serif; max-width: 760px; margin: 48px auto; padding: 0 20px; line-height: 1.5; }
    code, a.button { background: #f1f3f5; border-radius: 6px; padding: 4px 7px; }
    a.button { display: inline-block; margin-top: 12px; color: #111; text-decoration: none; border: 1px solid #d0d7de; }
  </style>
</head>
<body>
  <h1>MeshLink URL Relay</h1>
  <p>Relay WebSocket:</p>
  <p><code>${escapeHtml(publicUrl)}</code></p>
  <p>Open this link on Android to add the relay:</p>
  <p><a class="button" href="MeshLink://relay?url=${encodeURIComponent(publicUrl)}">Add relay to MeshLink</a></p>
</body>
</html>`);
});

const wss = new WebSocketServer({ server });
const clients = new Set();

wss.on("connection", (socket) => {
  clients.add(socket);
  socket.on("message", (data, isBinary) => {
    if (!isBinary) return;
    for (const peer of clients) {
      if (peer !== socket && peer.readyState === WebSocket.OPEN) {
        peer.send(data, { binary: true });
      }
    }
  });
  socket.on("close", () => clients.delete(socket));
  socket.on("error", () => clients.delete(socket));
});

server.listen(port, host, () => {
  console.log(`MeshLink URL relay listening on ${host}:${port}`);
  for (const address of localAddresses()) {
    console.log(`  http://${address}:${port}`);
    console.log(`  ws://${address}:${port}`);
  }
});

function relayUrl(req) {
  const forwardedHost = req.headers["x-forwarded-host"];
  const forwardedProto = req.headers["x-forwarded-proto"];
  const hostHeader = Array.isArray(forwardedHost) ? forwardedHost[0] : forwardedHost || req.headers.host || `localhost:${port}`;
  const proto = forwardedProto === "https" ? "wss" : "ws";
  return `${proto}://${hostHeader}`;
}

function localAddresses() {
  return Object.values(os.networkInterfaces())
    .flat()
    .filter((item) => item && item.family === "IPv4" && !item.internal)
    .map((item) => item.address);
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}
