#!/bin/sh
# 鱼搭便携版（macOS）：双击打开菜单。第一次若提示“无法验证开发者”，请右键本文件 → 打开。
DIR="$(cd "$(dirname "$0")" && pwd)"
# 浏览器下载的压缩包会给每个文件加隔离标记，包内 Java 与 Node 会因此被拦截；这里一次性解除。
xattr -dr com.apple.quarantine "$DIR" 2>/dev/null || true
cd "$DIR" || exit 1
"$DIR/runtime/node/bin/node" "$DIR/app/launcher/yuda.mjs" "$@"
