#!/usr/bin/env bash
# Signed call to the closet management API without any host platform.
#   CLOSET_MANAGEMENT_SECRET=... scripts/manage-curl.sh GET '/api/manage/v1/users?size=5'
#   scripts/manage-curl.sh PATCH /api/manage/v1/users/alice/status '{"enabled":false}'
# Optional env: CLOSET_MANAGEMENT_URL (default http://127.0.0.1:8080), CLOSET_MANAGEMENT_KEY_ID (default primary),
#               CLOSET_OPERATOR (default $USER). Requires openssl and python3.
set -euo pipefail
method="${1:?usage: manage-curl.sh METHOD PATH [JSON_BODY]}"
target="${2:?usage: manage-curl.sh METHOD PATH [JSON_BODY]}"
body="${3-}"
secret="${CLOSET_MANAGEMENT_SECRET:?Set CLOSET_MANAGEMENT_SECRET}"
base="${CLOSET_MANAGEMENT_URL:-http://127.0.0.1:8080}"
key_id="${CLOSET_MANAGEMENT_KEY_ID:-primary}"
operator="${CLOSET_OPERATOR:-${USER:-cli}}"
method="$(printf '%s' "$method" | tr '[:lower:]' '[:upper:]')"

path="${target%%\?*}"
query=""
[[ "$target" == *\?* ]] && query="${target#*\?}"

canonical_query="$(QUERY="$query" python3 - <<'PY'
import os, urllib.parse
raw = os.environ["QUERY"]
pairs = []
for part in filter(None, raw.split("&")):
    name, _, value = part.partition("=")
    dec = lambda s: urllib.parse.unquote_plus(s)
    enc = lambda s: urllib.parse.quote(s, safe="-._~")
    pairs.append((enc(dec(name)), enc(dec(value))))
print("&".join(f"{n}={v}" for n, v in sorted(pairs)), end="")
PY
)"

timestamp="$(python3 -c 'import time; print(int(time.time()*1000))')"
nonce="$(openssl rand -hex 16)"
request_id="cli-$nonce"
body_hash="$(printf '%s' "$body" | openssl dgst -sha256 -r | cut -d' ' -f1)"
canonical="$(printf 'CLOSET-HMAC-SHA256\n%s\n%s\n%s\n%s\n%s\n%s\n%s\n%s' \
  "$method" "$path" "$canonical_query" "$timestamp" "$nonce" "$operator" "$request_id" "$body_hash")"
signature="$(printf '%s' "$canonical" | openssl dgst -sha256 -hmac "$secret" -binary | openssl base64 -A | tr '+/' '-_' | tr -d '=')"

args=(-sS -X "$method" "$base$target"
  -H "X-Closet-Key-Id: $key_id" -H "X-Closet-Timestamp: $timestamp" -H "X-Closet-Nonce: $nonce"
  -H "X-Closet-Signature: $signature" -H "X-Operator: $operator" -H "X-Request-Id: $request_id")
if [[ -n "$body" ]]; then args+=(-H "Content-Type: application/json" --data-binary "$body"); fi
curl "${args[@]}"
echo
