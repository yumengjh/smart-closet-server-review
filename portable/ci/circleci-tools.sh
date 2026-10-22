#!/usr/bin/env bash
# Installs the toolchain one portable package is built with on CircleCI (.circleci/config.yml), for the package's
# platform rather than the machine's: Node 24 (bundled into the package by assemble.mjs), Temurin JDK 21 (its jlink
# builds the trimmed runtime) and Maven. macos-x64 runs the x64 tools under Rosetta on Apple Silicon.
#   bash portable/ci/circleci-tools.sh <linux-x64|linux-arm64|macos-arm64|macos-x64|windows-x64>
# Writes PATH and JAVA_HOME to ~/portable-tools/env.sh; later steps source it.
set -euo pipefail
platform=${1:?usage: circleci-tools.sh <platform>}
NODE_MAJOR=24 JAVA_MAJOR=21 MAVEN_VERSION=3.9.9
case "$platform" in
  linux-x64)   node_os=linux-x64;   jdk_os=linux;   jdk_arch=x64 ;;
  linux-arm64) node_os=linux-arm64; jdk_os=linux;   jdk_arch=aarch64 ;;
  macos-arm64) node_os=darwin-arm64; jdk_os=mac;    jdk_arch=aarch64 ;;
  macos-x64)   node_os=darwin-x64;  jdk_os=mac;     jdk_arch=x64 ;;
  windows-x64) node_os=win-x64;     jdk_os=windows; jdk_arch=x64 ;;
  *) echo "未知平台：$platform"; exit 1 ;;
esac
tools="$HOME/portable-tools"
rm -rf "$tools" && mkdir -p "$tools" && cd "$tools"

if [ "$platform" = macos-x64 ] && ! arch -x86_64 /usr/bin/true 2>/dev/null; then
  sudo softwareupdate --install-rosetta --agree-to-license
fi

unpack() {
  case "$1" in
    *.zip) if command -v unzip >/dev/null; then unzip -q "$1"; else powershell.exe -NoProfile -Command "Expand-Archive -Path '$1' -DestinationPath ."; fi ;;
    *) tar -xzf "$1" ;;
  esac
  rm -f "$1"
}

# Node: the newest release of the major line.
node_file=$(curl -fsSL "https://nodejs.org/dist/latest-v${NODE_MAJOR}.x/SHASUMS256.txt" | awk -v s="-$node_os." 'index($2, s) && $2 ~ /(tar\.gz|zip)$/ {print $2}' | head -1)
[ -n "$node_file" ] || { echo "找不到 Node $NODE_MAJOR 的 $node_os 包"; exit 1; }
curl -fsSL -o "$node_file" "https://nodejs.org/dist/latest-v${NODE_MAJOR}.x/$node_file"
unpack "$node_file"
node_dir="$tools/${node_file%.tar.gz}"; node_dir="${node_dir%.zip}"
[ "$jdk_os" = windows ] && node_bin="$node_dir" || node_bin="$node_dir/bin"

# Temurin JDK (with jmods, which jlink needs).
jdk_ext=tar.gz; [ "$jdk_os" = windows ] && jdk_ext=zip
curl -fsSL -o "jdk.$jdk_ext" "https://api.adoptium.net/v3/binary/latest/${JAVA_MAJOR}/ga/${jdk_os}/${jdk_arch}/jdk/hotspot/normal/eclipse"
unpack "jdk.$jdk_ext"
jdk_dir=$(ls -d "$tools"/jdk-* | head -1)
[ "$jdk_os" = mac ] && jdk_dir="$jdk_dir/Contents/Home"

curl -fsSL -o maven.tar.gz "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz"
unpack maven.tar.gz

java_home="$jdk_dir"
# Node and Java on Windows need a native path, not Git Bash's /c/...
[ "$jdk_os" = windows ] && java_home=$(cygpath -m "$jdk_dir")
{
  echo "export JAVA_HOME='$java_home'"
  echo "export PATH='$node_bin':'$jdk_dir/bin':'$tools/apache-maven-${MAVEN_VERSION}/bin':\"\$PATH\""
} > "$tools/env.sh"
# shellcheck disable=SC1091
source "$tools/env.sh"
echo "平台 ${platform}：node $(node --version) ($(node -p process.arch))，$(java -version 2>&1 | head -1)，$(mvn -v | head -1)"
