#!/usr/bin/env bash
# Runs on the deployment host over SSH, from a bundle directory uploaded by .github/workflows/deploy.yml.
#
#   remote-deploy.sh plan     find the running deployment (compose project, directory, files, /models mount) and list
#                             the image-model files the host is missing or has in another version ("UPLOAD <name>")
#   remote-deploy.sh apply    install/verify every model, sync compose.yaml (with backup), pull the images, recreate
#                             server and agent-runtime, wait for health, check the models; roll back on failure
#
# Environment (all optional): IMAGE_TAG (default latest), SYNC_COMPOSE (default true), DEPLOY_DIR / DEPLOY_ENV_FILE
# (only needed when no container is running yet, or to override what is detected), HEALTH_TIMEOUT (seconds, default
# 300), GHCR_USER (with a token on stdin: log in to ghcr.io for the pull, log out afterwards).
set -euo pipefail
cmd=${1:?usage: remote-deploy.sh plan|apply}
BUNDLE=$(cd "$(dirname "$0")" && pwd)
STATE="$BUNDLE/state.env"
SERVER_REPO=ghcr.io/yumengjh/smart-closet-server
AGENT_REPO=ghcr.io/yumengjh/smart-closet-server-agent
IMAGE_TAG=${IMAGE_TAG:-latest}
log() { printf '[deploy] %s\n' "$*" >&2; }
die() { printf '[deploy] 错误：%s\n' "$*" >&2; exit 1; }

# docker directly, or through password-less sudo.
PRE=()
if ! docker info >/dev/null 2>&1; then
  if sudo -n docker info >/dev/null 2>&1; then PRE=(sudo -n); else die "当前用户无法使用 docker，也不能免密 sudo"; fi
fi
dk() { "${PRE[@]}" env IMAGE_TAG="$IMAGE_TAG" docker "$@"; }
docker compose version >/dev/null 2>&1 || "${PRE[@]}" docker compose version >/dev/null 2>&1 || die "没有 docker compose v2 插件"
for tool in curl sha256sum; do command -v "$tool" >/dev/null || die "服务器缺少 $tool"; done

label() { dk inspect -f "{{ index .Config.Labels \"$2\" }}" "$1" 2>/dev/null || true; }
container_env() { dk inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$1" | sed -n "s/^$2=//p" | head -1; }

detect() {
  local id cid=""
  for id in $(dk ps -aq --filter label=com.docker.compose.service=server); do
    if [ -n "$(dk inspect -f '{{range .Mounts}}{{if eq .Destination "/models"}}{{.Source}}{{end}}{{end}}' "$id")" ]; then cid=$id; break; fi
  done
  if [ -n "$cid" ]; then
    PROJECT=$(label "$cid" com.docker.compose.project)
    WORKDIR=$(label "$cid" com.docker.compose.project.working_dir)
    CONFIG=$(label "$cid" com.docker.compose.project.config_files)
    ENV_FILE=$(label "$cid" com.docker.compose.project.environment_file)
    MODELS=$(dk inspect -f '{{range .Mounts}}{{if eq .Destination "/models"}}{{.Source}}{{end}}{{end}}' "$cid")
    DATA=$(dk inspect -f '{{range .Mounts}}{{if eq .Destination "/data"}}{{if .Name}}卷 {{.Name}}{{else}}{{.Source}}{{end}}{{end}}{{end}}' "$cid")
    OLD_SERVER=$(dk inspect -f '{{.Image}}' "$cid")
    local agent; agent=$(dk ps -aq --filter "label=com.docker.compose.project=$PROJECT" --filter label=com.docker.compose.service=agent-runtime | head -1)
    OLD_AGENT=$([ -n "$agent" ] && dk inspect -f '{{.Image}}' "$agent" || true)
    IMAGE_MODEL=$(basename "$(container_env "$cid" CLOSET_IMAGE_MODEL_PATH)")
    TEXT_MODEL=$(basename "$(container_env "$cid" CLOSET_IMAGE_TEXT_MODEL_PATH)")
    EMBEDDING_MODEL=$(basename "$(container_env "$cid" CLOSET_EMBEDDING_MODEL_PATH)")
    log "找到运行中的部署：容器 ${cid:0:12}，项目 $PROJECT，目录 $WORKDIR"
  else
    [ -n "${DEPLOY_DIR:-}" ] || die "没有找到挂载了 /models 的 server 容器；首次部署请在仓库变量里设置 DEPLOY_DIR"
    WORKDIR=$DEPLOY_DIR; PROJECT=$(basename "$DEPLOY_DIR" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9_-')
    CONFIG="$DEPLOY_DIR/compose.yaml"; ENV_FILE=""; MODELS="$DEPLOY_DIR/models"; DATA="卷 ${PROJECT}_closet-data"
    OLD_SERVER=""; OLD_AGENT=""; IMAGE_MODEL=""; TEXT_MODEL=""; EMBEDDING_MODEL=""
    log "没有运行中的容器，按 DEPLOY_DIR 首次部署：$DEPLOY_DIR"
  fi
  # Explicit settings win over what the running container says.
  if [ -n "${DEPLOY_DIR:-}" ] && [ "$DEPLOY_DIR" != "$WORKDIR" ]; then die "DEPLOY_DIR=$DEPLOY_DIR 与运行中容器的目录 $WORKDIR 不一致，请改正或删除该变量"; fi
  [ -n "${DEPLOY_ENV_FILE:-}" ] && ENV_FILE=$DEPLOY_ENV_FILE
  if [ -z "$ENV_FILE" ]; then for f in "$WORKDIR/.env" /etc/smart-closet/server.env "$HOME/smart-closet-server.env"; do [ -f "$f" ] && { ENV_FILE=$f; break; }; done; fi
  [ -n "$IMAGE_MODEL" ] || IMAGE_MODEL=image-model-int8.onnx
  [ -n "$TEXT_MODEL" ] || TEXT_MODEL=image-text-model-int8.onnx
  [ -n "$EMBEDDING_MODEL" ] || EMBEDDING_MODEL=embedding-fp32.onnx
  [ -n "$PROJECT" ] && [ -n "$WORKDIR" ] && [ -n "$CONFIG" ] && [ -n "$MODELS" ] || die "部署信息不完整（项目/目录/compose 文件/模型目录）"
  log "compose 文件：$CONFIG；环境文件：${ENV_FILE:-（无）}"
  log "模型目录（挂载到 /models）：$MODELS；数据：${DATA:-未知}"
  log "使用中的模型：$EMBEDDING_MODEL、$IMAGE_MODEL、$TEXT_MODEL"
}

save_state() {
  : > "$STATE"
  for v in PROJECT WORKDIR CONFIG ENV_FILE MODELS OLD_SERVER OLD_AGENT IMAGE_MODEL TEXT_MODEL EMBEDDING_MODEL; do printf '%s=%q\n' "$v" "${!v}" >> "$STATE"; done
}

# Image-model files this host needs: the configured towers, the vocabulary and the manifest.
image_files() { printf '%s\n' "$IMAGE_MODEL" "$TEXT_MODEL" image-text-vocab.txt image-model-manifest.json; }

plan() {
  detect; save_state
  local name want have
  for name in $(image_files); do
    want=$(awk -v n="$name" '$2==n{print $1}' "$BUNDLE/image-models.sha256")
    [ -n "$want" ] || die "构建产物里没有 $name"
    have=$([ -f "$MODELS/$name" ] && sha256sum "$MODELS/$name" | awk '{print $1}' || true)
    if [ "$have" != "$want" ]; then echo "UPLOAD $name"; log "需要上传：$name（服务器上${have:+版本不同}${have:-没有}）"; fi
  done
}

compose() {
  local args=(-p "$PROJECT" --project-directory "$WORKDIR") f
  IFS=',' read -ra files <<< "$CONFIG"; for f in "${files[@]}"; do args+=(-f "$f"); done
  [ -n "$ENV_FILE" ] && args+=(--env-file "$ENV_FILE")
  dk compose "${args[@]}" "$@"
}

install_models() {
  local SUDO=()
  "${PRE[@]}" mkdir -p "$MODELS"
  [ -w "$MODELS" ] || SUDO=(sudo -n)
  # The download scripts work on ../models next to themselves: point that at the mounted directory.
  mkdir -p "$BUNDLE/run/scripts"
  cp "$BUNDLE/download-model.sh" "$BUNDLE/download-embedding-model.sh" "$BUNDLE/run/scripts/"
  ln -sfn "$MODELS" "$BUNDLE/run/models"
  log "抠图模型（U²-Net）：下载或校验"
  "${SUDO[@]}" bash "$BUNDLE/run/scripts/download-model.sh"
  log "文字向量模型（bge-small-zh）：下载或校验"
  "${SUDO[@]}" bash "$BUNDLE/run/scripts/download-embedding-model.sh"
  # Image-text model: exported and checked against PyTorch on the GitHub runner; uploaded only when different.
  if [ -d "$BUNDLE/image" ] && [ -n "$(ls -A "$BUNDLE/image")" ]; then
    (cd "$BUNDLE/image" && for f in *; do awk -v n="$f" '$2==n' "$BUNDLE/image-models.sha256"; done | sha256sum -c --quiet) || die "上传的图文模型校验失败，服务器文件未改动"
    local stage; stage=$("${SUDO[@]}" mktemp -d "$MODELS/.incoming-XXXXXX")
    "${SUDO[@]}" cp "$BUNDLE"/image/* "$stage/"
    "${SUDO[@]}" chmod 0644 "$stage"/*
    for f in "$stage"/*; do "${SUDO[@]}" mv -f "$f" "$MODELS/$(basename "$f")"; done
    "${SUDO[@]}" rmdir "$stage"
    log "图文模型已更新：$(ls "$BUNDLE/image" | tr '\n' ' ')"
  fi
  local missing=()
  for f in u2net.onnx "$EMBEDDING_MODEL" embedding-vocab.txt embedding-tokenizer.json embedding-tokenizer-config.json $(image_files); do
    [ -s "$MODELS/$f" ] || missing+=("$f")
  done
  [ ${#missing[@]} -eq 0 ] || die "模型目录仍缺少：${missing[*]}"
  (cd "$MODELS" && for f in $(image_files); do awk -v n="$f" '$2==n' "$BUNDLE/image-models.sha256"; done | sha256sum -c --quiet) || die "图文模型与本次构建不一致"
  log "模型齐全并已校验：u2net.onnx、$EMBEDDING_MODEL、$IMAGE_MODEL、$TEXT_MODEL 及词表"
}

COMPOSE_BACKUP=""
sync_compose() {
  [ "${SYNC_COMPOSE:-true}" = "true" ] || { log "按设置不更新 compose 文件"; return; }
  [[ "$CONFIG" != *,* ]] || { log "使用了多个 compose 文件，不自动更新：$CONFIG"; return; }
  if [ -f "$CONFIG" ] && cmp -s "$BUNDLE/compose.yaml" "$CONFIG"; then log "compose 文件已是最新"; return; fi
  if [ -f "$CONFIG" ]; then COMPOSE_BACKUP="$CONFIG.bak-$(date +%Y%m%d%H%M%S)"; "${PRE[@]}" cp -p "$CONFIG" "$COMPOSE_BACKUP"; log "已备份 compose 文件：$COMPOSE_BACKUP"; fi
  "${PRE[@]}" mkdir -p "$(dirname "$CONFIG")"
  "${PRE[@]}" cp "$BUNDLE/compose.yaml" "$CONFIG"
  if ! compose config --quiet; then
    [ -n "$COMPOSE_BACKUP" ] && "${PRE[@]}" cp -p "$COMPOSE_BACKUP" "$CONFIG"
    die "新 compose 文件校验失败（多半是环境文件缺少必填项），已恢复原文件"
  fi
  log "compose 文件已更新为仓库版本"
}

wait_healthy() {
  local id status deadline=$((SECONDS + ${HEALTH_TIMEOUT:-300}))
  id=$(compose ps -q server)
  [ -n "$id" ] || return 1
  while [ $SECONDS -lt $deadline ]; do
    status=$(dk inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id" 2>/dev/null || echo missing)
    case "$status" in
      healthy) return 0 ;;
      unhealthy|exited|dead|missing) log "server 状态：$status"; return 1 ;;
    esac
    sleep 5
  done
  log "等待健康检查超时"; return 1
}

rollback() {
  log "部署失败，开始回滚"
  compose logs --tail=80 server >&2 || true
  [ -n "$COMPOSE_BACKUP" ] && "${PRE[@]}" cp -p "$COMPOSE_BACKUP" "$CONFIG" && log "已恢复 compose 文件"
  if [ -n "$OLD_SERVER" ]; then
    dk tag "$OLD_SERVER" "$SERVER_REPO:rollback"
    [ -n "$OLD_AGENT" ] && dk tag "$OLD_AGENT" "$AGENT_REPO:rollback"
    IMAGE_TAG=rollback
    compose up -d --no-build server agent-runtime && wait_healthy && log "已回滚到部署前的镜像" || log "回滚后仍不健康，请登录服务器检查"
  fi
  exit 1
}

check_models_running() {
  local id; id=$(compose ps -q server)
  compose exec -T server sh -c "for f in u2net.onnx $EMBEDDING_MODEL embedding-vocab.txt $IMAGE_MODEL $TEXT_MODEL image-text-vocab.txt; do test -r /models/\$f || { echo \"容器内读不到 /models/\$f\"; exit 1; }; done" \
    || die "容器内的模型文件不可读（挂载目录或权限有问题）"
  # The text embedding model loads in the background after start; the image-text towers load on first use.
  # Logs since this container started: a redeploy of the running tag keeps the container, started long ago.
  local deadline=$((SECONDS + 180)) logs="" started
  started=$(dk inspect -f '{{.State.StartedAt}}' "$id")
  while [ $SECONDS -lt $deadline ]; do
    logs=$(dk logs --since "$started" "$id" 2>&1 || true)
    grep -q "本地向量模型已加载" <<< "$logs" && break
    sleep 10
  done
  if grep -q "本地向量模型已加载" <<< "$logs"; then log "文字向量模型：已加载"
  elif [ "$(date -d "$started" +%s 2>/dev/null || echo 0)" -lt "$APPLY_STARTED" ]; then
    # Not restarted by this deployment and the start-up lines already rotated out: nothing new to check.
    log "server 容器未重建（镜像未变），启动日志已轮转，跳过向量模型加载检查"
  else log "文字向量模型 3 分钟内没有加载，最近日志："; grep -E "向量|模型|onnx" <<< "$logs" | tail -20 >&2; exit 1; fi
  local bad loaded
  bad=$(grep -E "无法加载|降级为字段与关键词|回退为颜色轮廓比对" <<< "$logs" | tail -5 || true)
  [ -z "$bad" ] || { printf '%s\n' "$bad" >&2; die "有模型加载失败，见上面的日志"; }
  loaded=$(grep "已加载（无需重启）" <<< "$logs" | grep -v "本地向量" | tail -2 || true)
  if [ -n "$loaded" ]; then printf '%s\n' "$loaded" >&2; else log "图文模型：文件就绪，首次以图搜图或建图像索引时加载"; fi
}

apply() {
  APPLY_STARTED=$(date +%s)
  # shellcheck disable=SC1090
  source "$STATE"
  local token=""; [ -n "${GHCR_USER:-}" ] && token=$(cat)
  install_models
  if [ -n "$token" ]; then
    printf '%s' "$token" | dk login ghcr.io -u "$GHCR_USER" --password-stdin >/dev/null
    trap 'dk logout ghcr.io >/dev/null 2>&1 || true' EXIT
  fi
  # Pull first: a tag that was never published stops the deployment before anything changes.
  log "拉取镜像 $IMAGE_TAG"
  dk pull -q "$SERVER_REPO:$IMAGE_TAG" >/dev/null || die "拉取 $SERVER_REPO:$IMAGE_TAG 失败（标签不存在或没有权限）"
  dk pull -q "$AGENT_REPO:$IMAGE_TAG" >/dev/null || die "拉取 $AGENT_REPO:$IMAGE_TAG 失败"
  "${PRE[@]}" mkdir -p "$WORKDIR/.local"
  sync_compose
  log "重建 server 与 agent-runtime"
  compose up -d --no-build server agent-runtime || rollback
  wait_healthy || rollback
  # An old compose file with a fixed image tag would quietly keep the previous version running.
  local want got
  want=$(dk image inspect -f '{{.Id}}' "$SERVER_REPO:$IMAGE_TAG"); got=$(dk inspect -f '{{.Image}}' "$(compose ps -q server)")
  [ "$want" = "$got" ] || { log "server 运行的镜像不是 $IMAGE_TAG（compose 文件的 image 没有使用 \${IMAGE_TAG}？）"; rollback; }
  log "server 健康检查通过，运行镜像 $IMAGE_TAG"
  check_models_running
  dk image prune -f >/dev/null || true
  log "部署完成：$SERVER_REPO:$IMAGE_TAG"
}

case "$cmd" in
  plan) plan ;;
  apply) apply ;;
  *) die "未知命令：$cmd" ;;
esac
