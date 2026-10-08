#!/usr/bin/env python3
"""Generate reference fixtures with the upstream tokenizer implementation.

Run from the repository root after bash scripts/download-embedding-model.sh:
  uv run --with tokenizers==0.22.1 python scripts/gen-tokenizer-fixtures.py
The official tokenizer.json downloaded next to the model is the reference (normalizer,
pre-tokenizer, WordPiece, special tokens and [CLS]/[SEP] template), truncated to 512 tokens
like the model's own configuration. No model inference or network access is used here.
The fixture embeds the small MIT-licensed vocabulary so the Java parity test also runs in CI
without any model files.
"""
import hashlib
import json
from pathlib import Path

from tokenizers import Tokenizer

ROOT = Path(__file__).resolve().parent.parent
VOCAB = ROOT / "models/embedding-vocab.txt"
CONFIG = ROOT / "models/embedding-tokenizer-config.json"
TOKENIZER = ROOT / "models/embedding-tokenizer.json"
OUTPUT = ROOT / "src/test/resources/semantic/tokenizer-fixtures.json"

SAMPLES = [
    "白色T恤", "白色短袖上衣", "黑色皮鞋", "藏蓝色圆领长袖针织衫；胸前小熊刺绣",
    "海边的浅色亚麻衬衫", "秋冬通勤羊毛大衣", "红色连衣裙，适合约会！", "春秋休闲牛仔裤",
    "Nike AIR 白色运动鞋", "UNIQLO HEATTECH 保暖内衣", "tshirt TSHIRT T-shirt",
    "尺码 XL，价格￥199.00，2026款", "100%棉 / 80%羊毛+20%涤纶", "白色👕 黑色👟 ✨",
    "【新品】（宽松）上衣：春／夏；", "Café naïve résumé ÉCOLE", "cafe\u0301 déjà vu",
    "", " \t\n\r ", "白\u0000色\u200b上\ufffd\ue000\u0378衣", "白\u00a0色\u3000衬\u2028衫\u2029", "𠀀𪛖白色上衣",
    "ＡＢＣ１２３ Ｔ恤", "[CLS]白色[MASK]上衣[SEP]", "上衣[UNK][PAD]", "https://example.com/a?q=白色",
    "2026-10-08 3.5cm #OOTD", "a" * 101, "白色短袖上衣" * 120,
    "为这个句子生成表示以用于检索相关文章：适合海边的衣服",
]


def main():
    config = json.loads(CONFIG.read_text())
    tokenizer = Tokenizer.from_file(str(TOKENIZER))
    tokenizer.enable_truncation(max_length=config["model_max_length"])
    fixture = {
        "reference_library": "tokenizers==0.22.1",
        "vocab_sha256": hashlib.sha256(VOCAB.read_bytes()).hexdigest(),
        "config_sha256": hashlib.sha256(CONFIG.read_bytes()).hexdigest(),
        "tokenizer_sha256": hashlib.sha256(TOKENIZER.read_bytes()).hexdigest(),
        "lower_case": config["do_lower_case"],
        "max_length": config["model_max_length"],
        "vocabulary": VOCAB.read_text(encoding="utf-8").removesuffix("\n").split("\n"),
        "samples": [],
    }
    for text in SAMPLES:
        encoded = tokenizer.encode(text)
        fixture["samples"].append({"text": text, "input_ids": encoded.ids,
                                   "attention_mask": encoded.attention_mask, "token_type_ids": encoded.type_ids})
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    temporary = OUTPUT.with_suffix(".json.part")
    try:
        temporary.write_text(json.dumps(fixture, ensure_ascii=False, indent=2) + "\n")
        temporary.replace(OUTPUT)
    finally:
        temporary.unlink(missing_ok=True)
    print(f"Wrote {len(SAMPLES)} reference samples to {OUTPUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
