# Lagos Transfer

Black & white, minimalist transfer-only Android app for Lagos Life.

**Scope:** transfer only. No balance tamper. Read balance, send money.

- Multi-account (encrypted, Keystore)
- Toggle transfers per-account + global
- Bulk parallel sends to different people
- Balance check per account

Endpoints: `POST /api/auth/login`, `GET /api/save`, `GET /api/messages`, `POST /api/send`, `GET /api/send-pin`

Design: monochrome, sharp, no inflate.
