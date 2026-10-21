# Eko Aza licence server

Small server with one job: validate one time activation codes and remember
which phone redeemed which code.

It does **not** store game logins, does not see transfers and does not talk to
lagoslife.app. All of that stays in the Android app.

## Run it

```bash
cd server
ADMIN_TOKEN=$(openssl rand -hex 16) node server.js
```

| env | default | meaning |
|---|---|---|
| `PORT` | `8787` | port |
| `DATA` | `./codes.json` | where the codes are kept |
| `ADMIN_TOKEN` | empty | needed for minting codes, minting is disabled without it |

## Endpoints

```
POST /api/redeem          {code, device_id}
  200 {ok:true, license_id, redeemed_at}   code was unused
  404 {error:"Unknown code"}
  409 {error:"This code was already used on another phone"}
  410 {error:"This code expired - ask for a new one"}   (2 days to redeem)

GET  /api/licence?device_id=...
  200 {licensed:true, license_id, redeemed_at}   this phone redeemed a code
  200 {licensed:false}

POST /api/admin/mint      Authorization: Bearer <ADMIN_TOKEN>
  body {count: 10, days: 2, note: "buyer name"}
  200 {codes:["EKO-XXXX-XXXX-XXXXC", ...]}

GET  /api/health          200 {ok:true}
```

Codes are stored hashed (sha256), the server never keeps the plaintext.

## Point the app at it

The app reads the address from the `LICENCE_SERVER` environment variable at
build time, or from `licenceServer=` in `gradle.properties`:

```bash
LICENCE_SERVER=https://your.domain node ../gradlew assembleRelease
```

In GitHub Actions add it as a repository secret named `LICENCE_SERVER` and the
workflow picks it up.

## Behind a reverse proxy

Terminate TLS in front of it (nginx, Caddy, Cloudflare Tunnel). The app only
talks HTTPS.

```nginx
location /api/ {
    proxy_pass http://127.0.0.1:8787;
    proxy_set_header Host $host;
}
```
