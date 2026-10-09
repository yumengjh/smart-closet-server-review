#!/usr/bin/env bash
# Exports the local image-text model (Chinese-CLIP ViT-B/16, official weights) to ./models as ONNX.
# Run once on any machine with ~6 GB free disk (a MacBook is fine), then copy the files to the server's ./models
# (or run it on the server). The running server picks the files up by itself, no restart needed.
#
#   bash scripts/export-image-model.sh              # weights from ModelScope (fast in mainland China)
#   bash scripts/export-image-model.sh huggingface  # weights from Hugging Face
#   bash scripts/export-image-model.sh /path/clip_cn_vit-b-16.pt   # a checkpoint you already downloaded
#
# Needs uv (https://docs.astral.sh/uv/). Python packages go into a separate cache folder, not the project.
# Pinned versions below are the ones the export was tested with (2026-10-09).
set -euo pipefail
cd "$(dirname "$0")/.."
command -v uv >/dev/null || { echo "需要先安装 uv：curl -LsSf https://astral.sh/uv/install.sh | sh"; exit 1; }
SOURCE="${1:-modelscope}"
VENV="${CLOSET_EXPORT_VENV:-$HOME/.cache/smart-closet/image-model-export}"
uv venv -q --allow-existing --python 3.12 "$VENV"
PY="$VENV/bin/python"
TORCH=(torch==2.6.0 torchvision==0.21.0)
if [ "$(uname -s)" = "Linux" ]; then
  # CPU wheels: the default Linux wheels pull ~3 GB of CUDA libraries the export does not need.
  uv pip install -q --python "$PY" --default-index https://download.pytorch.org/whl/cpu "${TORCH[@]}"
else
  uv pip install -q --python "$PY" "${TORCH[@]}"
fi
uv pip install -q --python "$PY" onnx==1.23.2 onnxruntime==1.31.0 "numpy>=2,<3" pillow==12.3.0 six==1.17.0 tqdm
# cn_clip 1.6.0 pins lmdb==1.3.0 (training only, often fails to build); the export needs just the model code.
uv pip install -q --python "$PY" --no-deps cn_clip==1.6.0
case "$SOURCE" in
  modelscope)  uv pip install -q --python "$PY" modelscope; ARGS=(--source modelscope) ;;
  huggingface) uv pip install -q --python "$PY" huggingface_hub; ARGS=(--source huggingface) ;;
  *)           [ -f "$SOURCE" ] || { echo "找不到权重文件：$SOURCE"; exit 1; }; ARGS=(--checkpoint "$SOURCE") ;;
esac
"$PY" scripts/export_image_model.py --out models "${ARGS[@]}"
echo
echo "完成。默认使用 image-model-int8.onnx（图像）与 image-text-model-int8.onnx（文字）；服务会在约 30 秒内自动加载并开始为衣物计算图像向量。"
echo "核对：mvn -o -Dtest=ClipRealModelParityTest test （Java 预处理与 PyTorch 对拍）"
