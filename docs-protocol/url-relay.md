# URL Relay for Laptops

This bridge lets Android peers exchange the existing binary MeshLink packets through a laptop-hosted WebSocket URL. The laptop relay does not decrypt, parse, or store messages; it only forwards binary frames between connected clients.

## Run the relay

```powershell
cd tools/url-relay
npm install
npm start
```

Open the printed `http://<laptop-ip>:8787` page from the Android device and tap **Add relay to MeshLink**, or open:

```text
MeshLink://relay?url=ws://<laptop-ip>:8787
```

Every Android device that opens the same relay URL can use the laptop as an internet/LAN bridge in addition to BLE.

## Protocol

Clients send raw `MeshLinkPacket.toBinaryData()` bytes as WebSocket binary frames. The relay forwards binary frames to every other connected socket and ignores text frames.

