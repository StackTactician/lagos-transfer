#!/usr/bin/env bash
#
# Mint Eko Aza activation codes.
#
#   ./mint-codes.sh                      10 codes, redeemable for 2 days
#   ./mint-codes.sh 25                   25 codes
#   ./mint-codes.sh 25 "adewale"         25 codes, note "adewale"
#   ./mint-codes.sh 25 30 "adewale"      25 codes, 30 days to redeem, note "adewale"
#   ./mint-codes.sh -n "adewale" -o sold.txt
#
# The admin token comes from LICENCE_ADMIN_TOKEN, or from ~/.eko_licence_token
set -euo pipefail

SERVER="${LICENCE_SERVER:-https://eko-licence.mmmoyosore09.workers.dev}"
TOKEN="${LICENCE_ADMIN_TOKEN:-}"
if [ -z "$TOKEN" ] && [ -f "$HOME/.eko_licence_token" ]; then
  TOKEN="$(cat "$HOME/.eko_licence_token")"
fi
if [ -z "$TOKEN" ]; then
  echo "No admin token. Put it in ~/.eko_licence_token or export LICENCE_ADMIN_TOKEN." >&2
  exit 1
fi

usage() {
  sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
}

COUNT=10
DAYS=2
NOTE=""
SAVE=""
POSITIONAL=0

while [ $# -gt 0 ]; do
  case "$1" in
    -n|--note)  NOTE="${2:-}"; shift 2 ;;
    -d|--days)  DAYS="${2:-2}"; shift 2 ;;
    -c|--count) COUNT="${2:-10}"; shift 2 ;;
    -o|--out)   SAVE="${2:-}"; shift 2 ;;
    -h|--help)  usage; exit 0 ;;
    -*)         echo "Unknown option: $1" >&2; usage; exit 1 ;;
    *)
      case "$POSITIONAL" in
        0) COUNT="$1"; POSITIONAL=1 ;;
        1) if [ "$1" -eq "$1" ] 2>/dev/null; then DAYS="$1"; POSITIONAL=2; else NOTE="$1"; POSITIONAL=3; fi ;;
        *) NOTE="$1"; POSITIONAL=3 ;;
      esac
      shift ;;
  esac
done

case "$COUNT" in (''|*[!0-9]*) echo "count must be a number" >&2; exit 1 ;; esac
case "$DAYS"  in (''|*[!0-9]*) echo "days must be a number"  >&2; exit 1 ;; esac

BODY=$(printf '{"count":%s,"days":%s,"note":"%s"}' "$COUNT" "$DAYS" "$NOTE")

RESPONSE=$(curl -sS --max-time 30 -X POST "$SERVER/api/admin/mint" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "$BODY")

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is needed to read the answer" >&2
  echo "$RESPONSE" >&2
  exit 1
fi

CODES=$(printf '%s' "$RESPONSE" | python3 -c '
import json, sys
raw = sys.stdin.read()
try:
    d = json.loads(raw)
except Exception:
    print(""); sys.exit(1)
if "codes" not in d:
    print(""); sys.stderr.write(d.get("error", raw)[:200] + "\n"); sys.exit(1)
print("\n".join(d["codes"]))
') || { echo "minting failed" >&2; exit 1; }

if [ -z "$CODES" ]; then
  echo "minting failed: $RESPONSE" >&2
  exit 1
fi

echo "$CODES"
echo "---" >&2
echo "minted $(printf '%s\n' "$CODES" | wc -l | tr -d ' ') code(s), redeemable for $DAYS day(s)" >&2

if [ -n "$SAVE" ]; then
  printf '%s\n' "$CODES" >> "$SAVE"
  echo "saved to $SAVE" >&2
fi
