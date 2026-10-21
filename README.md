# Lagos Transfer

Black & white, minimalist transfer-only Android app for Lagos Life.

**Scope:** transfer only. No balance tamper. Read balance, send money.

- Multi-account (encrypted, Keystore)
- Toggle transfers per-account + global
- Bulk parallel sends to different people
- Balance check per account

Endpoints: `POST /api/auth/login`, `GET /api/save`, `GET /api/players`, `POST /api/send`,
`POST /api/family` (history / claim), `GET /api/send-pin`

Server rules the app obeys:
- one send per account every 30s (app waits 32s), sends are not idempotent, so a
  dropped connection is checked against the server ledger before any retry
- fee on top of the amount: 10 (<=5k), 25 (<=50k), 50 above
- received money waits in escrow until the receiver claims it, then it is
  returned to the sender after 72 hours
- part of a balance can be held by the server and cannot be sent

Design: monochrome, sharp, no inflate.
