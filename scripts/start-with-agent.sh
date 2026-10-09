#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
mkdir -p .local
secret_file="${CLOSET_AGENT_SECRET_FILE:-.local/agent-secret}"
if [[ -z "${CLOSET_AGENT_SECRET:-}" ]]; then
  if [[ ! -f "$secret_file" ]]; then openssl rand -hex 32 > "$secret_file"; fi
  chmod 600 "$secret_file"
  export CLOSET_AGENT_SECRET="$(cat "$secret_file")"
fi
export CLOSET_AGENT_PORT="${CLOSET_AGENT_PORT:-8091}"
export CLOSET_AGENT_RUNTIME_URL="${CLOSET_AGENT_RUNTIME_URL:-http://127.0.0.1:${CLOSET_AGENT_PORT}}"
export CLOSET_AGENT_CALLBACK_URL="${CLOSET_AGENT_CALLBACK_URL:-http://127.0.0.1:${PORT:-8080}}"
node -e 'if (Number(process.versions.node.split(".")[0]) < 22) process.exit(1)' || { echo 'Node.js 22 or later is required.' >&2; exit 1; }
if [[ ! -d agent-runtime/node_modules ]]; then npm --prefix agent-runtime ci; fi
npm --prefix agent-runtime run build
node agent-runtime/dist/server.js &
agent_pid=$!
cleanup() { kill "$agent_pid" 2>/dev/null || true; }
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
ready=false
for attempt in {1..30}; do
  kill -0 "$agent_pid" 2>/dev/null || { echo 'Agent process exited; check its port and configuration.' >&2; exit 1; }
  if curl --noproxy '*' --silent --fail --connect-timeout 1 --max-time 2 "$CLOSET_AGENT_RUNTIME_URL/health" >/dev/null; then ready=true; break; fi
  sleep 0.2
done
[[ "$ready" == true ]] || { echo 'Agent health check did not become ready.' >&2; exit 1; }
kill -0 "$agent_pid" 2>/dev/null || { echo 'Agent process exited before Spring startup.' >&2; exit 1; }
if [[ -n "${CLOSET_SERVER_JAR:-}" ]]; then java -jar "$CLOSET_SERVER_JAR"; else mvn spring-boot:run; fi
