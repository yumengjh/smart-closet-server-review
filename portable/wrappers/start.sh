#!/bin/sh
# 鱼搭便携版（Linux）：在终端运行 ./start.sh 打开菜单；./start.sh help 查看命令。
DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$DIR/runtime/node/bin/node" "$DIR/app/launcher/yuda.mjs" "$@"
