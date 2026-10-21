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

## Licence and trial

- Trial: 100,000,000,000 naira cumulative over every account of the app, counted on the phone
- The counter is kept in four places (preferences, external files, a hidden file in Downloads, a
  system setting), every copy sealed with a key derived from a secret in the app and the device
  id, so clearing the data or uninstalling does not hand out a fresh trial
- Activation codes are one time, valid 2 days to redeem, bound to the phone that redeemed them
- The licence is checked with the licence server, so it survives a reinstall and cannot be
  patched out of the app
- Server: `server/` (Cloudflare Worker + D1), deployed from CI
