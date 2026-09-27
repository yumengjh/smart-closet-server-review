#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p models
model=models/u2net.onnx
expected=8d10d2f3bb75ae3b6d527c77944fc5e7dcd94b29809d47a739a7a728a912b491
checksum() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}';
  else shasum -a 256 "$1" | awk '{print $1}'; fi
}
if [[ -f "$model" ]] && [[ "$(checksum "$model")" == "$expected" ]]; then
  echo "U2Net already downloaded and verified."
  exit 0
fi
work=$(mktemp -d models/.download-XXXXXX)
pids=()
cleanup() {
  for pid in $(jobs -pr); do kill "$pid" 2>/dev/null || true; done
  wait 2>/dev/null || true
  rm -rf "$work"
}
trap cleanup EXIT
trap 'exit 130' INT TERM
# The pinned upstream file is 175,997,641 bytes. Range downloads avoid a slow
# single connection restarting the entire file; every part and the full SHA are checked.
size=175997641
parts=16
chunk=$(( (size + parts - 1) / parts ))
url='https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2net.onnx'
for ((i=0; i<parts; i++)); do
  start=$((i * chunk)); end=$(((i + 1) * chunk - 1))
  if ((end >= size)); then end=$((size - 1)); fi
  curl --fail --silent --show-error --location --retry 2 --connect-timeout 20 --max-time 300 \
    --max-filesize "$chunk" --range "$start-$end" "$url" --output "$work/$i" &
  pids+=("$!")
done
for pid in "${pids[@]}"; do wait "$pid"; done
pids=()
for ((i=0; i<parts; i++)); do
  wanted=$chunk
  if ((i == parts - 1)); then wanted=$((size - i * chunk)); fi
  if [[ $(wc -c < "$work/$i" | tr -d ' ') -ne $wanted ]]; then
    echo "Unexpected length in model part $i" >&2; exit 1
  fi
  cat "$work/$i" >> "$work/complete"
done
if [[ "$(checksum "$work/complete")" != "$expected" ]]; then
  echo "Model checksum mismatch; leaving existing model untouched." >&2
  exit 1
fi
mv "$work/complete" "$model"
echo "Saved verified U2Net model to $model"
