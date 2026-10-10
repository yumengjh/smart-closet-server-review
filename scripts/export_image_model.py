"""Exports the local image-text model (Chinese-CLIP ViT-B/16) to ONNX for the server.

Run through scripts/export-image-model.sh (it pins the Python packages). Steps:
 1. official PyTorch weights (ModelScope by default, or Hugging Face) via cn_clip's own loader;
 2. image tower and text tower to fp32 ONNX with the official contract
    (`image` float [1,3,224,224] -> `unnorm_image_features`; `text` int64 [1,52] -> `unnorm_text_features`);
 3. int8 versions (onnxruntime dynamic quantization);
 4. checks every ONNX file against PyTorch on a fixed picture and fixed texts, and writes image-model-check.json /
    image-model-check.png so the Java side can prove it prepares inputs the same way (ClipRealModelParityTest);
 5. SHA-256 of every file into image-model-manifest.json.
Files are written to a temporary folder and moved into place only after every check passed.
"""
import argparse, hashlib, json, os, shutil, sys, tempfile, time

import numpy as np
import torch

# torch >= 2.6 loads checkpoints with weights_only=True. The official checkpoint is tried that way first; only if it
# holds other Python objects is it loaded fully (it comes from the official publisher or a file you chose).
_torch_load = torch.load
def _load(f, *a, **kw):
    if "weights_only" in kw:
        return _torch_load(f, *a, **kw)
    try:
        return _torch_load(f, *a, weights_only=True, **kw)
    except Exception as e:  # noqa: BLE001
        if not hasattr(f, "seek"):
            raise
        print(f"note: checkpoint needs a full load ({type(e).__name__}); loading it as a trusted official file")
        f.seek(0)
        return _torch_load(f, *a, weights_only=False, **kw)
torch.load = _load
from PIL import Image, ImageDraw

import cn_clip.clip as clip
from cn_clip.clip.utils import _MODEL_INFO, create_model, image_transform, load_from_name

ARCH = "ViT-B-16"
TEXTS = ["白色T恤", "带小熊图案的白T", "红色连衣裙", "藏蓝色牛仔裤", "格纹百褶裙", "黑色皮鞋", "灰色针织开衫", "碎花长裙"]
FILES = {"image_fp32": "image-model-fp32.onnx", "image_int8": "image-model-int8.onnx",
         "text_fp32": "image-text-model-fp32.onnx", "text_int8": "image-text-model-int8.onnx", "vocab": "image-text-vocab.txt"}


def check_picture():
    """A fixed synthetic picture: a red tee-like shape with a white band on a light background (no external files)."""
    img = Image.new("RGB", (320, 280), (238, 236, 230))
    d = ImageDraw.Draw(img)
    d.polygon([(90, 40), (230, 40), (300, 100), (260, 130), (240, 110), (240, 260), (80, 260), (80, 110), (60, 130), (20, 100)], fill=(196, 32, 44))
    d.rectangle([80, 140, 240, 160], fill=(250, 250, 248))
    return img


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def cos(a, b):
    a = np.asarray(a, dtype=np.float64); b = np.asarray(b, dtype=np.float64)
    return float(a @ b / (np.linalg.norm(a) * np.linalg.norm(b)))


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--out", default="models")
    p.add_argument("--source", choices=["modelscope", "huggingface"], default="modelscope")
    p.add_argument("--cache", default=os.path.expanduser("~/.cache/smart-closet/cn-clip"))
    p.add_argument("--checkpoint", help="local clip_cn_vit-b-16.pt instead of downloading")
    p.add_argument("--random-weights", action="store_true", help="development only: random weights to test the pipeline offline")
    args = p.parse_args()
    torch.manual_seed(0)

    if args.random_weights:
        model = create_model(_MODEL_INFO[ARCH]["struct"]).float().eval()
        print("!! random weights: only for testing the export pipeline, never deploy these files")
    elif args.checkpoint:
        model, _ = load_from_name(args.checkpoint, device="cpu", vision_model_name="ViT-B-16", text_model_name="RoBERTa-wwm-ext-base-chinese", input_resolution=224)
    else:
        model, _ = load_from_name(ARCH, device="cpu", download_root=args.cache, use_modelscope=args.source == "modelscope")
    model = model.float().eval()
    preprocess = image_transform(_MODEL_INFO[ARCH]["input_resolution"])

    os.makedirs(args.out, exist_ok=True)
    work = tempfile.mkdtemp(prefix=".image-model-", dir=args.out)   # same file system as the target, so moving is atomic
    out = lambda key: os.path.join(work, FILES[key])
    started = time.time()
    with torch.no_grad():
        image = preprocess(Image.new("RGB", (224, 224))).unsqueeze(0)
        text = clip.tokenize([""], context_length=52)
        torch.onnx.export(model, (image, None), out("image_fp32"), input_names=["image"], output_names=["unnorm_image_features"],
                          export_params=True, do_constant_folding=True, opset_version=14, dynamo=False)
        torch.onnx.export(model, (None, text), out("text_fp32"), input_names=["text"], output_names=["unnorm_text_features"],
                          export_params=True, opset_version=14, dynamo=False)
    print(f"exported fp32 in {time.time() - started:.0f}s")

    from onnxruntime.quantization import QuantType, quantize_dynamic
    quantize_dynamic(out("image_fp32"), out("image_int8"), weight_type=QuantType.QInt8)
    # The text tower loses too much with per-tensor scales (min cosine 0.82 against fp32 on the check texts, issue #15);
    # per-channel scales keep it at 0.97 with the same file size. The image tower is fine per-tensor (0.97) and stays
    # as it was, so image vectors already in the database keep their model version.
    quantize_dynamic(out("text_fp32"), out("text_int8"), weight_type=QuantType.QInt8, per_channel=True)
    shutil.copyfile(os.path.join(os.path.dirname(clip.__file__), "vocab.txt"), out("vocab"))

    # Reference outputs from PyTorch, then every ONNX file against them.
    import onnxruntime as ort
    picture = check_picture()
    with torch.no_grad():
        pixel = preprocess(picture).unsqueeze(0)
        tokens = clip.tokenize(TEXTS, context_length=52)
        ref_image = model(pixel, None)[0].numpy()
        ref_texts = [model(None, tokens[i:i + 1])[0].numpy() for i in range(len(TEXTS))]
    agreement = {}
    for key in ("image_fp32", "image_int8"):
        s = ort.InferenceSession(out(key), providers=["CPUExecutionProvider"])
        agreement[key] = round(cos(s.run(None, {"image": pixel.numpy()})[0][0], ref_image), 5)
    for key in ("text_fp32", "text_int8"):
        s = ort.InferenceSession(out(key), providers=["CPUExecutionProvider"])
        agreement[key] = round(min(cos(s.run(None, {"text": tokens[i:i + 1].numpy()})[0][0], ref_texts[i]) for i in range(len(TEXTS))), 5)
    print("agreement with PyTorch (cosine):", agreement)
    if agreement["image_fp32"] < 0.999 or agreement["text_fp32"] < 0.999:
        sys.exit("fp32 ONNX does not match PyTorch; files were not installed")
    if agreement["image_int8"] < 0.95 or agreement["text_int8"] < 0.95:
        print("!! int8 agrees less than 0.95 with fp32; prefer the fp32 files")

    # Zero-shot sanity on the check picture (real weights only): the red tee should match "红色" texts best.
    ref_image_n = ref_image / np.linalg.norm(ref_image)
    ranking = sorted(((cos(ref_image_n, t), TEXTS[i]) for i, t in enumerate(ref_texts)), reverse=True)
    print("check picture vs texts:", [(t, round(c, 3)) for c, t in ranking[:4]])

    picture.save(os.path.join(work, "image-model-check.png"))
    check = {"arch": ARCH, "randomWeights": bool(args.random_weights), "picture": "image-model-check.png", "texts": TEXTS,
             "tokens": tokens.tolist(), "image": ref_image.tolist(), "textFeatures": [t.tolist() for t in ref_texts],
             "agreement": agreement, "exportedAt": time.strftime("%Y-%m-%dT%H:%M:%S")}
    with open(os.path.join(work, "image-model-check.json"), "w", encoding="utf-8") as f:
        json.dump(check, f, ensure_ascii=False)
    manifest = {name: {"bytes": os.path.getsize(os.path.join(work, name)), "sha256": sha256(os.path.join(work, name))}
                for name in sorted(os.listdir(work))}
    manifest["source"] = "random weights (development)" if args.random_weights else f"OFA-Sys Chinese-CLIP {ARCH} official checkpoint via {args.checkpoint or args.source}"
    with open(os.path.join(work, "image-model-manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)

    for name in os.listdir(work):
        os.replace(os.path.join(work, name), os.path.join(args.out, name))   # same file system: each file appears whole
    os.rmdir(work)
    for name, info in manifest.items():
        if isinstance(info, dict):
            print(f"{name:32s} {info['bytes']:>12,d}  {info['sha256']}")
    print(f"done in {time.time() - started:.0f}s -> {os.path.abspath(args.out)}")


if __name__ == "__main__":
    main()
