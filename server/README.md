# Eko Aza licence server

Small server with one job: validate one time activation codes and remember
which phone redeemed which code.

It does **not** store game logins, does not see transfers and does not talk to
lagoslife.app. All of that stays in the Android app.

## What is live right now

| | |
|---|---|
| worker | `eko-licence` on Cloudflare Workers |
| url | `https://eko-licence.mmmoyosore09.workers.dev` |
| database | D1 `eko-licence` (id `d754d431-5006-4a34-b76b-11c4da326ce3`) |
| code | `server/worker.js` |
| deploy | `.github/workflows/deploy-licence.yml` (push to `server/**` or run it by hand) |

wrangler does not run on an Android phone, so the deploy happens from GitHub
Actions. It needs two repository secrets:

- `CF_API_TOKEN` - Cloudflare API token (Workers Scripts: Edit, D1: Edit)
- `CF_ACCOUNT_ID` - the Cloudflare account id

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

POST /api/admin/mint      Authorization: Bearer <LICENCE_ADMIN_TOKEN>
  body {count: 10, days: 2, note: "buyer name"}
  200 {codes:["EKO-XXXX-XXXX-XXXXC", ...]}

GET  /api/health          200 {ok:true}
```

Codes are stored hashed (sha256), the server never keeps the plaintext.

## Minting codes

```bash
./mint-codes.sh                       # 10 codes, redeemable for 2 days
./mint-codes.sh 25                    # 25 codes
./mint-codes.sh 25 "adewale"          # 25 codes with a note
./mint-codes.sh 25 30 "adewale"       # 25 codes, 30 days to redeem
./mint-codes.sh -n "adewale" -o sold.txt
```

The script prints the codes one per line and can append them to a file. The
admin token comes from `LICENCE_ADMIN_TOKEN` or from `~/.eko_licence_token`
(keep that file out of the repo).

Raw call:

```bash
curl -X POST https://eko-licence.mmmoyosore09.workers.dev/api/admin/mint \
  -H "Authorization: Bearer $LICENCE_ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"count":10,"days":2,"note":"buyer name"}'
```

`days` is how long the code can be redeemed. The licence it gives is for life.

## Point the app at it

The app reads the address at build time from the `licenceServer` gradle
property or the `LICENCE_SERVER` environment variable. The android workflow
passes the `LICENCE_SERVER` repository secret, so nothing has to be edited in
the code.

## Running it somewhere else instead

`server/server.js` is the same service as a plain Node process with a JSON
file instead of D1:

```bash
ADMIN_TOKEN=secret node server.js
```

Env: `PORT` (8787), `DATA` (./codes.json), `ADMIN_TOKEN`.
