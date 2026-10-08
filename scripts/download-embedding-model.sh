#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p models
# ONNX export (community conversion) and the official tokenizer files, both pinned to fixed commits.
onnx='https://huggingface.co/Xenova/bge-small-zh-v1.5/resolve/75c43b069aac4d136ba6bc1122f995fedcfd2781'
official='https://huggingface.co/BAAI/bge-small-zh-v1.5/resolve/7999e1d3359715c523056ef9478215996d62a620'
checksum() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}';
  else shasum -a 256 "$1" | awk '{print $1}'; fi
}
work=$(mktemp -d models/.download-XXXXXX)
trap 'rm -rf "$work"' EXIT
trap 'exit 130' INT TERM
fetch() {
  local url=$1 name=$2 expected=$3 size=$4
  if [[ -f "models/$name" ]] && [[ "$(checksum "models/$name")" == "$expected" ]]; then
    echo "Already verified: models/$name"
    return
  fi
  curl --fail --silent --show-error --location --retry 2 --connect-timeout 20 --max-time 600 \
    --max-filesize "$size" "$url" --output "$work/$name"
  if [[ $(wc -c < "$work/$name" | tr -d ' ') -ne $size ]] || [[ "$(checksum "$work/$name")" != "$expected" ]]; then
    echo "Size or SHA-256 mismatch: $name; existing files remain untouched." >&2
    exit 1
  fi
}
# Verify every staged file before replacing any existing file; renames stay on the same filesystem.
fetch "$onnx/onnx/model.onnx" embedding-fp32.onnx 69a0b846f4f116b5e6aabf9546ea6754d02264f3211a13a1bd69b31b8040749a 94851877
fetch "$onnx/onnx/model_quantized.onnx" embedding-int8.onnx 15b717c382bcb518ba457b93ea6850ede7f4f1cd8937454aa06972366cd19bcc 24010842
fetch "$official/vocab.txt" embedding-vocab.txt 45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c 109540
fetch "$official/tokenizer_config.json" embedding-tokenizer-config.json e6f3b96db926a37d4039995fbf5ad17de158dfb8f6343d607e4dbaad18d75f5a 367
fetch "$official/tokenizer.json" embedding-tokenizer.json 48cea5d44424912a6fd1ea647bf4fe50b55ab8b1e5879c3275f80e339e8fae26 439125
for name in embedding-fp32.onnx embedding-int8.onnx embedding-vocab.txt embedding-tokenizer-config.json embedding-tokenizer.json; do
  if [[ -f "$work/$name" ]]; then mv -f "$work/$name" "models/$name"; fi
done
echo 'Local text embedding files verified and installed.'
