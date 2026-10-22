#!/usr/bin/env bash
#
# Claim incoming Lagos Life money over the API. No browser, no login page.
#
#   ./claim.sh <username>                claim once
#   ./claim.sh <username> --watch 30     keep claiming every 30 seconds
#   ./claim.sh <username> --background   run detached, log to ~/.eko_claim_<user>.log
#   ./claim.sh <username> --stop         stop the background loop
#   ./claim.sh --all                     claim every account in ~/.eko_accounts
#
# Passwords: EKO_PASSWORD, or ~/.eko_accounts with one "user:password" per line.
#
# A claim only credits the balance on the server, it never sends a balance
# back, so it cannot lose money the way a browser session that saves an old
# balance does.
set -uo pipefail

BASE="${LAGOS_BASE:-https://lagoslife.app}"
ACCOUNTS_FILE="${EKO_ACCOUNTS:-$HOME/.eko_accounts}"
JAR="$(mktemp)"
CLEANED=0
cleanup() { [ "$CLEANED" = 0 ] && rm -f "$JAR"; CLEANED=1; }
trap cleanup EXIT

die() { echo "$*" >&2; exit 1; }

# Passwords come from, in order: --pass, EKO_PASSWORD, ~/.eko_accounts.
# If there is no terminal to type into, it says how to set it instead of hanging.
password_for() {
  local user="$1"
  if [ -n "${EKO_PASSWORD:-}" ]; then echo "$EKO_PASSWORD"; return 0; fi
  if [ -f "$ACCOUNTS_FILE" ]; then
    local line
    line="$(grep -E "^${user}:" "$ACCOUNTS_FILE" | head -1)"
    if [ -n "$line" ]; then echo "${line#*:}"; return 0; fi
  fi
  if [ -t 0 ]; then
    local pw
    read -r -s -p "password for @$user: " pw
    echo
    echo "$pw"
    return 0
  fi
  echo "" # no password, login will fail with a clear message
}

save_password() {
  local user="$1" pass="$2"
  if [ -z "$user" ] || [ -z "$pass" ]; then
    echo "usage: $(basename "$0") --set <username> <password>" >&2
    exit 1
  fi
  touch "$ACCOUNTS_FILE"
  chmod 600 "$ACCOUNTS_FILE"
  local tmp
  tmp="$(mktemp)"
  grep -v "^${user}:" "$ACCOUNTS_FILE" > "$tmp" 2>/dev/null || true
  echo "${user}:${pass}" >> "$tmp"
  mv "$tmp" "$ACCOUNTS_FILE"
  chmod 600 "$ACCOUNTS_FILE"
  echo "saved @$user in $ACCOUNTS_FILE"
}

login() {
  local user="$1" pass="$2"
  rm -f "$JAR"
  local out
  out="$(curl -sS --max-time 20 -c "$JAR" -X POST "$BASE/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$user\",\"password\":\"$pass\"}")"
  case "$out" in
    *'"ok":true'*) return 0 ;;
    *) echo "login failed for @$user: ${out:0:120}" >&2; return 1 ;;
  esac
}

api_post() {
  curl -sS --max-time 25 -b "$JAR" -X POST "$BASE$1" \
    -H 'Content-Type: application/json' -H 'Accept: application/json' -d "$2"
}

api_get() {
  curl -sS --max-time 25 -b "$JAR" "$BASE$1" -H 'Accept: application/json'
}

balance() {
  api_get "/api/save" | python3 -c '
import json,sys
try:
    d=json.load(sys.stdin); g=d.get("game") or {}
    print(g.get("money"))
except Exception:
    print("?")
'
}

# claims once, prints only when something was actually credited
claim_once() {
  local user="$1" quiet="${2:-0}"
  local res total
  res="$(api_post "/api/family" '{"action":"claim"}')"
  total="$(printf '%s' "$res" | python3 -c '
import json,sys
try:
    d=json.load(sys.stdin)
    got=d.get("received",[])
    print(sum(g.get("amount",0) for g in got))
except Exception:
    print(0)
')"
  if [ "$total" != "0" ] && [ -n "$total" ]; then
    echo "$(date '+%d %b %H:%M:%S')  claimed $total for @$user   balance now $(balance)"
    printf '%s' "$res" | python3 -c '
import json,sys
d=json.load(sys.stdin)
for g in d.get("received",[]):
    print("    +%s from %s" % (g.get("amount"), g.get("from")))
'
  elif [ "$quiet" = "0" ]; then
    echo "$(date '+%d %b %H:%M:%S')  nothing waiting for @$user   balance $(balance)"
  fi
}

watch_loop() {
  local user="$1" every="$2"
  local pass
  pass="$(password_for "$user")"
  login "$user" "$pass" || exit 1
  echo "watching @$user every ${every}s, ctrl-c to stop"
  while true; do
    claim_once "$user" 1
    sleep "$every"
    # the session can expire, log in again if it does
    if ! api_post "/api/family" '{"action":"history"}' | grep -q '"items"'; then
      login "$user" "$pass" || true
    fi
  done
}

background_loop() {
  local user="$1" every="$2"
  local log="$HOME/.eko_claim_${user}.log"
  local pidf="$HOME/.eko_claim_${user}.pid"
  if [ -f "$pidf" ] && kill -0 "$(cat "$pidf")" 2>/dev/null; then
    echo "already running (pid $(cat "$pidf"))"; exit 0
  fi
  nohup "$0" "$user" --watch "$every" >>"$log" 2>&1 &
  echo $! > "$pidf"
  echo "started, pid $(cat "$pidf"), log $log"
}

stop_loop() {
  local user="$1"
  local pidf="$HOME/.eko_claim_${user}.pid"
  if [ -f "$pidf" ]; then
    kill "$(cat "$pidf")" 2>/dev/null && echo "stopped" || echo "not running"
    rm -f "$pidf"
  else
    echo "not running"
  fi
}

case "${1:-}" in
  --all)
    [ -f "$ACCOUNTS_FILE" ] || die "no $ACCOUNTS_FILE"
    while IFS=: read -r u p; do
      [ -z "$u" ] && continue
      export EKO_PASSWORD="$p"
      login "$u" "$p" || continue
      claim_once "$u" 0
    done < "$ACCOUNTS_FILE"
    ;;
  --set)
    save_password "${2:-}" "${3:-}"
    ;;
  --list)
    if [ -f "$ACCOUNTS_FILE" ]; then
      cut -d: -f1 "$ACCOUNTS_FILE" | sed 's/^/@/'
    else
      echo "no accounts saved yet"
    fi
    ;;
  "")
    die "usage: $(basename "$0") <username> [--watch [secs] | --background | --stop | --pass <password>]
       $(basename "$0") --set <username> <password>
       $(basename "$0") --list"
    ;;
  *)
    USER="$1"; shift
    case "${1:-}" in
      --watch)      watch_loop "$USER" "${2:-30}" ;;
      --background) background_loop "$USER" "${2:-30}" ;;
      --stop)       stop_loop "$USER" ;;
      --pass)
        export EKO_PASSWORD="${2:-}"
        PASS="$EKO_PASSWORD"
        login "$USER" "$PASS" || exit 1
        claim_once "$USER" 0
        ;;
      *)
        PASS="$(password_for "$USER")"
        if [ -z "$PASS" ]; then
          echo "No password for @$USER." >&2
          echo "Save it once:  $(basename "$0") --set $USER 'yourpassword'" >&2
          echo "or run once with:  $(basename "$0") $USER --pass 'yourpassword'" >&2
          exit 1
        fi
        login "$USER" "$PASS" || exit 1
        claim_once "$USER" 0
        ;;
    esac
    ;;
esac
