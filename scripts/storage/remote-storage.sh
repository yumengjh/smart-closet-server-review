#!/usr/bin/env bash
# Runs on the deployment host, uploaded by migrate-to-s3.sh. Finds the running deployment the same way
# scripts/deploy/remote-deploy.sh does and runs one step of the storage migration against its /data.
#
#   remote-storage.sh check                    deployment found, image has the migration, compose passes CLOSET_STORAGE_*
#   remote-storage.sh run <step> <env-file>    scan | copy | verify (data mounted read-only) | pull (read-write)
#   remote-storage.sh stop | start             stop the server / start it and wait until healthy
#   remote-storage.sh use-bucket <env-file>    write CLOSET_STORAGE_* into the deployment's env file (backup first)
#   remote-storage.sh use-local                set CLOSET_STORAGE_TYPE=local in the env file
set -euo pipefail
cmd=${1:?usage: remote-storage.sh check|run|stop|start|use-bucket|use-local}
log() { printf '[storage] %s\n' "$*" >&2; }
die() { printf '[storage] 错误：%s\n' "$*" >&2; exit 1; }

PRE=()
if ! docker info >/dev/null 2>&1; then
  if sudo -n docker info >/dev/null 2>&1; then PRE=(sudo -n); else die "当前用户无法使用 docker，也不能免密 sudo"; fi
fi
dk() { "${PRE[@]}" docker "$@"; }
label() { dk inspect -f "{{ index .Config.Labels \"$2\" }}" "$1" 2>/dev/null || true; }
container_env() { dk inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$1" | sed -n "s/^$2=//p" | head -1; }

CID=""
for id in $(dk ps -aq --filter label=com.docker.compose.service=server); do
  if [ -n "$(dk inspect -f '{{range .Mounts}}{{if eq .Destination "/data"}}x{{end}}{{end}}' "$id")" ]; then CID=$id; break; fi
done
[ -n "$CID" ] || die "没有找到挂载了 /data 的 server 容器（请先用部署流程部署一次）"
PROJECT=$(label "$CID" com.docker.compose.project)
WORKDIR=$(label "$CID" com.docker.compose.project.working_dir)
CONFIG=$(label "$CID" com.docker.compose.project.config_files)
ENV_FILE=${DEPLOY_ENV_FILE:-$(label "$CID" com.docker.compose.project.environment_file)}
if [ -z "$ENV_FILE" ]; then for f in "$WORKDIR/.env" /etc/smart-closet/server.env "$HOME/smart-closet-server.env"; do [ -f "$f" ] && { ENV_FILE=$f; break; }; done; fi
[ -n "$ENV_FILE" ] || ENV_FILE="$WORKDIR/.env"
IMAGE=$(dk inspect -f '{{.Config.Image}}' "$CID")
IMAGE_TAG=${IMAGE##*:}
DATA=$(dk inspect -f '{{range .Mounts}}{{if eq .Destination "/data"}}{{if .Name}}{{.Name}}{{else}}{{.Source}}{{end}}{{end}}{{end}}' "$CID")

compose() {
  local files=() f
  IFS=',' read -ra cfg <<< "$CONFIG"; for f in "${cfg[@]}"; do files+=(-f "$f"); done
  local envs=(); [ -f "$ENV_FILE" ] && envs=(--env-file "$ENV_FILE")
  # Keep the image tag that is running now; compose would otherwise fall back to :latest.
  "${PRE[@]}" env IMAGE_TAG="$IMAGE_TAG" docker compose -p "$PROJECT" --project-directory "$WORKDIR" "${envs[@]}" "${files[@]}" "$@"
}

wait_healthy() {
  local i status
  for _ in $(seq 1 100); do
    status=$(dk inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$(compose ps -q server)" 2>/dev/null || true)
    [ "$status" = healthy ] && { log "后端已就绪"; return 0; }
    [ "$status" = unhealthy ] && break
    sleep 3
  done
  log "后端未就绪（状态：${status:-未知}），最近日志："; compose logs --tail 40 server >&2 || true
  return 1
}

case "$cmd" in
  check)
    log "部署：项目 $PROJECT，目录 $WORKDIR，镜像 $IMAGE，数据 $DATA，环境文件 $ENV_FILE"
    dk run --rm --entrypoint sh "$IMAGE" -c 'grep -aq "com/smartcloset/storage/StorageMigration" /app/app.jar' \
      || die "运行中的镜像 $IMAGE 还不支持存储迁移，请先部署包含存储桶功能的新版本"
    IFS=',' read -ra cfg <<< "$CONFIG"
    grep -q CLOSET_STORAGE_TYPE "${cfg[@]}" || die "compose 文件没有传递 CLOSET_STORAGE_* 变量，请先用部署流程同步新的 compose.yaml"
    current=$(container_env "$CID" CLOSET_STORAGE_TYPE); log "当前存储方式：${current:-local}"
    ;;
  run)
    step=${2:?step}; envfile=${3:?env file}
    mode=ro; [ "$step" = pull ] && mode=rw
    combined=$(mktemp); chmod 600 "$combined"; trap 'rm -f "$combined"' EXIT
    cat "$envfile" > "$combined"
    # Read the same database the server uses.
    for v in CLOSET_DB_URL CLOSET_DB_USER CLOSET_DB_PASSWORD; do val=$(container_env "$CID" "$v"); [ -n "$val" ] && printf '%s=%s\n' "$v" "$val" >> "$combined"; done
    printf 'CLOSET_STORAGE_DIR=/data\n' >> "$combined"
    dk run --rm --env-file "$combined" -v "$DATA:/data:$mode" "$IMAGE" --closet-command=storage-migrate --step="$step" --threads="${THREADS:-8}"
    ;;
  stop) log "停止后端"; compose stop server ;;
  start) log "启动后端（镜像标签 $IMAGE_TAG）"; compose up -d --no-deps server; wait_healthy ;;
  use-bucket)
    envfile=${2:?env file}
    [ -f "$ENV_FILE" ] && cp -p "$ENV_FILE" "$ENV_FILE.bak-storage-$(date +%Y%m%d%H%M%S)" || { touch "$ENV_FILE"; chmod 600 "$ENV_FILE"; }
    tmp=$(mktemp); chmod 600 "$tmp"
    grep -v -E '^(CLOSET_STORAGE_TYPE|CLOSET_STORAGE_S3_[A-Z_]+)=' "$ENV_FILE" > "$tmp" || true
    grep -E '^CLOSET_STORAGE_S3_[A-Z_]+=' "$envfile" >> "$tmp"
    printf 'CLOSET_STORAGE_TYPE=s3\n' >> "$tmp"
    cat "$tmp" > "$ENV_FILE"; rm -f "$tmp"
    log "已写入 $ENV_FILE（原文件已备份）"
    ;;
  use-local)
    [ -f "$ENV_FILE" ] || die "没有环境文件 $ENV_FILE"
    cp -p "$ENV_FILE" "$ENV_FILE.bak-storage-$(date +%Y%m%d%H%M%S)"
    if grep -q '^CLOSET_STORAGE_TYPE=' "$ENV_FILE"; then sed -i 's/^CLOSET_STORAGE_TYPE=.*/CLOSET_STORAGE_TYPE=local/' "$ENV_FILE"; else printf 'CLOSET_STORAGE_TYPE=local\n' >> "$ENV_FILE"; fi
    log "已切回本机存储（$ENV_FILE）"
    ;;
  *) die "未知命令 $cmd" ;;
esac
