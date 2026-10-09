#!/usr/bin/env bash
# One command for a fresh deployment: downloads both model sets into ./models (the folder compose mounts at /models).
# The running server notices new files by itself (no restart) and then indexes every garment in the background.
set -euo pipefail
cd "$(dirname "$0")"
bash download-model.sh
bash download-embedding-model.sh
echo "Done. No restart needed: segmentation works on the next upload; smart search/recommendation indexing starts within ~30 seconds."
