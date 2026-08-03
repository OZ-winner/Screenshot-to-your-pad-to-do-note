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

## Remote Selection Screenshot

The tablet can drive a rectangular selection using normalized coordinates. Windows captures the primary screen once, then shows a small click-through outline window matching the current selection. `confirm` hides the outline, crops the pending raw capture, and broadcasts it as a normal `screenshot` message.

Client:

```json
{
  "type": "remote_selection",
  "token": "random-token",
  "phase": "update",
  "x_ratio": 0.12,
  "y_ratio": 0.18,
  "width_ratio": 0.5,
  "height_ratio": 0.35
}
```

Phases:

- `begin`: capture the current primary screen and prepare the remote outline.
- `update`: move or resize the visible selection rectangle.
- `confirm`: crop the selected rectangle and send it to paired tablets.
- `cancel`: hide the Windows outline and discard the pending capture.

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

After SHA-256 verification and gallery persistence, the tablet acknowledges the same screenshot ID:

```json
{
  "type": "screenshot_result",
  "token": "random-token",
  "id": "uuid",
  "success": true,
  "message": null
}
```

Windows reports success only after receiving a matching result. A missing result times out after 15 seconds.
