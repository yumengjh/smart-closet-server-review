#!/usr/bin/env bash
# 一键把图片从服务器磁盘迁移到 S3 兼容存储桶（腾讯云 COS 等），在你自己的电脑上执行，通过 SSH 操作服务器。
# 设计：docs/design/2026-10-11-对象存储（S3兼容·腾讯云COS）设计.md
#
#   scripts/storage/migrate-to-s3.sh --host ubuntu@1.2.3.4 [--port 22] [--key ~/.ssh/id_ed25519] [--env-file .env.storage] [--yes]
#       完整迁移：检查 → 统计 → 复制（服务不停）→ 停服 → 补复制 → 数据库核对 → 切换配置 → 启动 → 健康检查
#   scripts/storage/migrate-to-s3.sh --host ... --step scan|copy|verify
#       只执行一步（verify 需要后端已停止）
#   scripts/storage/migrate-to-s3.sh --host ... --rollback
#       切回服务器磁盘：停服 → 把桶里新增的图片拉回磁盘 → 改回 local → 启动
#   scripts/storage/migrate-to-s3.sh --local-dir ./data --step scan|copy|verify|pull [--jar target/app.jar | --image 镜像]
#       对本机上的一份存储目录执行（用 docker 镜像或 jar）
#
# .env.storage（不要提交到仓库）：
#   CLOSET_STORAGE_S3_ENDPOINT=https://cos.ap-guangzhou.myqcloud.com
#   CLOSET_STORAGE_S3_REGION=ap-guangzhou
#   CLOSET_STORAGE_S3_BUCKET=closet-1250000000
#   CLOSET_STORAGE_S3_ACCESS_KEY=…   CLOSET_STORAGE_S3_SECRET_KEY=…   CLOSET_STORAGE_S3_PREFIX=（可选）
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
HOST="" PORT=22 KEY="" ENV_FILE=.env.storage STEP="" YES=false ROLLBACK=false LOCAL_DIR="" JAR="" IMAGE=ghcr.io/yumengjh/smart-closet-server:latest
while [ $# -gt 0 ]; do
  case "$1" in
    --host) HOST=$2; shift 2 ;; --port) PORT=$2; shift 2 ;; --key) KEY=$2; shift 2 ;;
    --env-file) ENV_FILE=$2; shift 2 ;; --step) STEP=$2; shift 2 ;; --yes|-y) YES=true; shift ;;
    --rollback) ROLLBACK=true; shift ;; --local-dir) LOCAL_DIR=$2; shift 2 ;; --jar) JAR=$2; shift 2 ;; --image) IMAGE=$2; shift 2 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "未知参数：$1（--help 查看用法）" >&2; exit 2 ;;
  esac
done
say() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
die() { printf '错误：%s\n' "$*" >&2; exit 1; }
confirm() { $YES && return 0; read -r -p "$1 [y/N] " a; [ "$a" = y ] || [ "$a" = Y ] || die "已取消"; }

# ---- 配置检查（不打印密钥）----
if ! $ROLLBACK || [ -n "$LOCAL_DIR" ]; then
  [ -f "$ENV_FILE" ] || die "没有找到 $ENV_FILE，请参考 scripts/storage/env.storage.example 创建"
  for k in ENDPOINT REGION BUCKET ACCESS_KEY SECRET_KEY; do
    grep -qE "^CLOSET_STORAGE_S3_${k}=.+" "$ENV_FILE" || die "$ENV_FILE 缺少 CLOSET_STORAGE_S3_${k}"
  done
  grep -vE '^\s*(#|$)' "$ENV_FILE" | grep -vqE '^CLOSET_STORAGE_S3_[A-Z_]+=' && die "$ENV_FILE 只能包含 CLOSET_STORAGE_S3_* 变量"
  printf '存储桶：%s（%s，%s）\n' "$(sed -n 's/^CLOSET_STORAGE_S3_BUCKET=//p' "$ENV_FILE")" \
    "$(sed -n 's/^CLOSET_STORAGE_S3_REGION=//p' "$ENV_FILE")" "$(sed -n 's/^CLOSET_STORAGE_S3_ENDPOINT=//p' "$ENV_FILE")"
fi

# ---- 本机目录模式 ----
if [ -n "$LOCAL_DIR" ]; then
  [ -n "$STEP" ] || die "本机目录模式需要 --step scan|copy|verify|pull"
  DIR=$(cd "$LOCAL_DIR" && pwd)
  if [ -n "$JAR" ]; then
    ( set -a; . "$(cd "$(dirname "$ENV_FILE")" && pwd)/$(basename "$ENV_FILE")"; set +a
      CLOSET_STORAGE_DIR=$DIR exec java -jar "$JAR" --closet-command=storage-migrate --step="$STEP" )
  else
    mode=ro; [ "$STEP" = pull ] && mode=rw
    docker run --rm --env-file "$ENV_FILE" -e CLOSET_STORAGE_DIR=/data -v "$DIR:/data:$mode" "$IMAGE" --closet-command=storage-migrate --step="$STEP"
  fi
  exit $?
fi

# ---- 服务器模式 ----
[ -n "$HOST" ] || die "需要 --host 用户@服务器（或 --local-dir）"
SSH=(ssh -p "$PORT" -o ControlMaster=auto -o ControlPath="$HOME/.ssh/closet-storage-%r@%h:%p" -o ControlPersist=15m)
[ -n "$KEY" ] && SSH+=(-i "$KEY")
remote() { "${SSH[@]}" "$HOST" "$@"; }
say "连接 $HOST"
RT=$(remote 'umask 077; mktemp -d /tmp/closet-storage.XXXXXX') || die "SSH 连接失败"
cleanup() { remote "rm -rf '$RT'" >/dev/null 2>&1 || true; }
trap cleanup EXIT
remote "cat > '$RT/remote-storage.sh' && chmod 700 '$RT/remote-storage.sh'" < "$HERE/remote-storage.sh"
[ -f "$ENV_FILE" ] && remote "cat > '$RT/storage.env'" < "$ENV_FILE"
R() { remote "bash '$RT/remote-storage.sh' $*"; }

R check

if $ROLLBACK; then
  confirm "将停止后端，把存储桶里的图片拉回服务器磁盘并切回本机存储，继续？"
  say "停止后端"; R stop
  say "从存储桶拉回本机缺少的图片"; [ -f "$ENV_FILE" ] || die "回滚需要 $ENV_FILE 来读取存储桶"
  if ! R run pull "'$RT/storage.env'"; then R start || true; die "拉回失败，后端已按原配置重新启动"; fi
  say "切回本机存储"; R use-local
  say "启动后端"; R start || die "后端未能启动，请登录服务器查看 docker compose logs server"
  say "已切回服务器磁盘存储；存储桶里的对象没有删除。"
  exit 0
fi

if [ -n "$STEP" ]; then
  case "$STEP" in scan|copy|verify) R run "$STEP" "'$RT/storage.env'"; exit $? ;; *) die "--step 只能是 scan、copy、verify" ;; esac
fi

say "1/6 统计要迁移的文件"; R run scan "'$RT/storage.env'"
confirm "开始复制到存储桶（后端照常运行）？"
say "2/6 复制（服务不停，可随时中断后重跑，已传的会跳过）"
R run copy "'$RT/storage.env'" || { echo "有文件复制失败，可直接重新运行本脚本。"; exit 1; }
confirm "接下来停止后端（约 1～3 分钟），补齐期间新增的图片、核对数据库并切换到存储桶，继续？"
say "3/6 停止后端"; R stop
say "4/6 补齐复制并核对数据库"
if ! R run copy "'$RT/storage.env'" || ! R run verify "'$RT/storage.env'"; then
  echo "核对没有通过，保持本机存储并重新启动后端。"; R start || true; exit 1
fi
say "5/6 切换配置到存储桶"; R use-bucket "'$RT/storage.env'"
say "6/6 启动后端"
if ! R start; then
  echo "后端用存储桶配置没有启动成功，自动切回本机存储。"
  R use-local; R start || true; exit 1
fi
cat <<'DONE'

迁移完成：新图片写入存储桶，浏览器直接从存储桶加载图片。
- 服务器磁盘上的旧图片没有删除，确认一段时间无误后再自行清理 /data 下的 workflows、batches、private-person/*.png、ootd-samples 等目录。
- 回滚：scripts/storage/migrate-to-s3.sh --host <同上> --rollback
DONE
