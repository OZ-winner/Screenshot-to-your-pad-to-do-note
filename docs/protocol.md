# Bridge Protocol

Default port: `46731`

The server listens on `0.0.0.0` and accepts WebSocket clients on any path. Pairing is protected by a short-lived pairing code. Device commands require a persisted token.

## Pair

Client:

```json
{
  "type": "pair",
  "code": "ABC123",
  "device_name": "小米平板"
}
```

Server:

```json
{
  "type": "paired",
  "token": "random-token",
  "device_id": "uuid"
}
```

After a successful pair, the server rotates the pairing code.

## Commands

Client:

```json
{
  "type": "command",
  "command": "play_pause",
  "token": "random-token"
}
```

Commands:

- `screenshot`
- `play_pause`
- `seek_back_5`
- `seek_forward_5`

## Screenshot Delivery

Server:

```json
{
  "type": "screenshot",
  "id": "uuid",
  "filename": "PC_20260730_120000.png",
  "created_at": "2026-07-30T12:00:00+08:00",
  "width": 1920,
  "height": 1080,
  "sha256": "hex",
  "png_base64": "base64"
}
```
