#!/usr/bin/env bash
#
# Claim incoming Lagos Life money without logging into the web client.
#
#   ./claim.sh <username>            password from EKO_PASSWORD or a prompt
#   EKO_PASSWORD=secret ./claim.sh <username>
#
# It logs in over the API, shows what is waiting, claims it, then prints the
# new balance and the money log (what the server took back, and what it gave
# back). No browser, and the app's rule of never saving game state is kept:
# a claim only ever credits the balance on the server.
set -euo pipefail

BASE="${LAGOS_BASE:-https://lagoslife.app}"
USER="${1:-}"
if [ -z "$USER" ]; then
  echo "usage: $(basename "$0") <username>" >&2
  exit 1
fi

if [ -z "${EKO_PASSWORD:-}" ]; then
  read -r -s -p "password for @$USER: " EKO_PASSWORD
  echo
fi

JAR="$(mktemp)"
trap 'rm -f "$JAR"' EXIT

login() {
  curl -sS --max-time 20 -c "$JAR" -X POST "$BASE/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$USER\",\"password\":\"$EKO_PASSWORD\"}" >/dev/null
}

api_post() {
  curl -sS --max-time 25 -b "$JAR" -X POST "$BASE$1" \
    -H 'Content-Type: application/json' -H 'Accept: application/json' \
    -d "$2"
}

api_get() {
  curl -sS --max-time 25 -b "$JAR" "$BASE$1" -H 'Accept: application/json'
}

login

echo "== before =="
api_get "/api/save" | python3 -c '
import json,sys
d=json.load(sys.stdin); g=d.get("game") or {}
print("balance:", g.get("money"))
'

echo
echo "== waiting to be claimed =="
api_post "/api/family" '{"action":"history"}' | python3 -c '
import json,sys
d=json.load(sys.stdin)
items=d.get("items",[])
wait=[i for i in items if not i.get("sent") and not i.get("received") and i.get("status")=="waiting"]
if not wait:
    print("nothing waiting")
for i in wait:
    print("  %s -> %s  %s" % (i.get("id"), i.get("who"), i.get("amount")))
print("total waiting:", sum(i.get("amount",0) for i in wait))
'

echo
echo "== claiming =="
api_post "/api/family" '{"action":"claim"}' | python3 -c '
import json,sys
d=json.load(sys.stdin)
got=d.get("received",[])
if not got:
    print("nothing was credited:", d.get("error",""))
for g in got:
    print("  +%s from %s" % (g.get("amount"), g.get("from")))
print("claimed total:", sum(g.get("amount",0) for g in got))
'

echo
echo "== after =="
api_get "/api/save" | python3 -c '
import json,sys
d=json.load(sys.stdin); g=d.get("game") or {}
print("balance:", g.get("money"))
'

echo
echo "== money log (what the server took back and returned) =="
api_post "/api/family" '{"action":"history"}' | python3 -c '
import json,sys,datetime
d=json.load(sys.stdin)
names={"money":"the server could not confirm it, took it back",
       "moneyBack":"the server took it by mistake, returned it",
       "refused":"transfer refused, it went back to the sender",
       "addedTransfer":"sent to you, your phone had not collected it",
       "addedCredit":"top up, refund or payout you had not seen",
       "tookSend":"a payment you made came off a save that did not show it"}
log=d.get("log",[])
if not log:
    print("empty")
for e in log[:25]:
    t=datetime.datetime.fromtimestamp(e.get("at",0)/1000).strftime("%d %b %H:%M")
    k=e.get("k")
    print("  %s  %12s  %s" % (t, e.get("a"), names.get(k,k)))
'
