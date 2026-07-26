"""
Export all-MiniLM-L6-v2 to ONNX plus its WordPiece vocab for on-device use.

Produces the two assets the Android OnnxMiniLmEmbedder expects:
    <out>/minilm.onnx      self-contained graph (no external .data sidecar)
    <out>/vocab.txt        30522-token WordPiece vocabulary

Precision selection is measured, not assumed. Candidates are tried smallest
first and the first one that clears the 0.99 parity gate against
sentence-transformers wins. int8 is attempted because PROJECT_CONTEXT.md targets
it, but it is only shipped if it actually holds parity -- the retrieval
confidence thresholds are calibrated on exact embeddings, so a model that drifts
would silently shift every score.

Usage:
    python export_onnx.py --out /out
"""
import argparse
import os
import shutil
import sys

import numpy as np
import torch
from sentence_transformers import SentenceTransformer
from transformers import AutoModel, AutoTokenizer

MODEL_ID = "sentence-transformers/all-MiniLM-L6-v2"
MAX_SEQ_LEN = 128
PARITY_THRESHOLD = 0.99

PROBES = [
    "For adult CPR push hard and fast in the center of the chest",
    "my friend is losing a lot of blood from his leg",
    "the ground is shaking violently what do i do",
    "her face is drooping and speech is slurred",
    "water is rising fast in the street",
    "he swallowed something poisonous",
]


def mean_pool_l2(token_embeddings: np.ndarray, attention_mask: np.ndarray) -> np.ndarray:
    """Replicates the pooling in OnnxMiniLmEmbedder.kt."""
    mask = attention_mask[0].astype(bool)
    valid = token_embeddings[0][mask]
    pooled = valid.mean(axis=0)
    norm = np.linalg.norm(pooled)
    return pooled / norm if norm > 1e-12 else pooled


def measure_parity(model_path, tokenizer, st_model, verbose=False):
    """Load like the Android embedder does (raw bytes) and score against reference."""
    import onnxruntime as ort

    with open(model_path, "rb") as f:
        model_bytes = f.read()
    sess = ort.InferenceSession(model_bytes, providers=["CPUExecutionProvider"])

    worst = 1.0
    for text in PROBES:
        enc = tokenizer(
            text,
            return_tensors="np",
            padding="max_length",
            truncation=True,
            max_length=MAX_SEQ_LEN,
        )
        out = sess.run(
            ["token_embeddings"],
            {
                "input_ids": enc["input_ids"].astype(np.int64),
                "attention_mask": enc["attention_mask"].astype(np.int64),
                "token_type_ids": enc["token_type_ids"].astype(np.int64),
            },
        )[0]
        onnx_vec = mean_pool_l2(out, enc["attention_mask"])
        ref = st_model.encode(text, normalize_embeddings=True)
        sim = float(np.dot(onnx_vec, ref))
        worst = min(worst, sim)
        if verbose:
            flag = "OK  " if sim > PARITY_THRESHOLD else "FAIL"
            print(f"    {flag} cos={sim:.6f}  {text[:50]}")
    return worst, len(model_bytes)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="/out")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    final_path = os.path.join(args.out, "minilm.onnx")

    print(f"Loading {MODEL_ID} ...")
    tokenizer = AutoTokenizer.from_pretrained(MODEL_ID)
    model = AutoModel.from_pretrained(MODEL_ID)
    model.eval()
    st_model = SentenceTransformer(MODEL_ID)

    raw_path = os.path.join(args.out, "minilm.raw.onnx")
    sample = tokenizer(
        "akasha emergency safety knowledge retrieval",
        return_tensors="pt",
        padding="max_length",
        truncation=True,
        max_length=MAX_SEQ_LEN,
    )

    print(f"Exporting ONNX graph -> {raw_path}")
    torch.onnx.export(
        model,
        (sample["input_ids"], sample["attention_mask"], sample["token_type_ids"]),
        raw_path,
        input_names=["input_ids", "attention_mask", "token_type_ids"],
        output_names=["token_embeddings"],
        dynamic_axes={
            "input_ids": {0: "batch", 1: "seq"},
            "attention_mask": {0: "batch", 1: "seq"},
            "token_type_ids": {0: "batch", 1: "seq"},
            "token_embeddings": {0: "batch", 1: "seq"},
        },
        opset_version=18,
        do_constant_folding=True,
    )

    # The exporter spills weights into a sidecar .onnx.data file. The Android
    # embedder loads the model as a single asset byte array, which cannot follow
    # external data references, so collapse it into one self-contained file.
    import onnx

    print("Consolidating external weights into a single file ...")
    raw_model = onnx.load(raw_path, load_external_data=True)
    fp32_path = os.path.join(args.out, "minilm.fp32.onnx")
    onnx.save_model(raw_model, fp32_path, save_as_external_data=False)
    print(f"  fp32: {os.path.getsize(fp32_path) / 1e6:.1f} MB")

    candidates = []

    # -- int8 dynamic quantization (smallest) -------------------------------
    int8_path = os.path.join(args.out, "minilm.int8.onnx")
    try:
        from onnxruntime.quantization import QuantType, quantize_dynamic

        quantize_dynamic(
            model_input=fp32_path, model_output=int8_path, weight_type=QuantType.QInt8
        )
        candidates.append(("int8", int8_path))
        print(f"  int8: {os.path.getsize(int8_path) / 1e6:.1f} MB")
    except Exception as e:
        print(f"  int8 unavailable: {e}")

    # -- fp16 (middle ground) ----------------------------------------------
    fp16_path = os.path.join(args.out, "minilm.fp16.onnx")
    try:
        from onnxconverter_common import float16

        fp16_model = float16.convert_float_to_float16(
            onnx.load(fp32_path), keep_io_types=True
        )
        onnx.save_model(fp16_model, fp16_path, save_as_external_data=False)
        candidates.append(("fp16", fp16_path))
        print(f"  fp16: {os.path.getsize(fp16_path) / 1e6:.1f} MB")
    except Exception as e:
        print(f"  fp16 unavailable: {e}")

    candidates.append(("fp32", fp32_path))

    # -- pick the smallest candidate that actually holds parity -------------
    print("-" * 72)
    print(f"PARITY SELECTION (gate: cosine > {PARITY_THRESHOLD})")
    print("-" * 72)
    chosen = None
    for name, path in candidates:
        try:
            worst, size = measure_parity(path, tokenizer, st_model)
        except Exception as e:
            reason = str(e).splitlines()[0][:90]
            print(f"  {name:<5} {'':>6}     unusable: {reason}")
            continue
        verdict = "PASS" if worst > PARITY_THRESHOLD else "REJECT"
        print(f"  {name:<5} {size / 1e6:>6.1f} MB  worst cos={worst:.6f}  {verdict}")
        if worst > PARITY_THRESHOLD and chosen is None:
            chosen = (name, path, worst, size)

    if chosen is None:
        print("no candidate cleared the parity gate; refusing to write assets")
        return 1

    name, path, worst, size = chosen
    shutil.copyfile(path, final_path)
    print("-" * 72)
    print(f"SELECTED: {name} ({size / 1e6:.1f} MB, worst cosine {worst:.6f})")
    print("-" * 72)
    measure_parity(final_path, tokenizer, st_model, verbose=True)

    # vocab.txt for the Kotlin WordPieceTokenizer
    saved = tokenizer.save_vocabulary(args.out)
    vocab_path = os.path.join(args.out, "vocab.txt")
    if saved and os.path.abspath(saved[0]) != os.path.abspath(vocab_path):
        shutil.move(saved[0], vocab_path)
    if not os.path.exists(vocab_path):
        raise SystemExit("failed to write vocab.txt")
    with open(vocab_path, encoding="utf-8") as f:
        vocab_lines = sum(1 for _ in f)
    print(f"Wrote {vocab_path} ({vocab_lines} tokens)")

    # keep only the shipping artifacts
    for leftover in (raw_path, raw_path + ".data", fp32_path, fp16_path, int8_path):
        if os.path.exists(leftover):
            os.remove(leftover)

    print(f"ONNX PARITY PASS. minilm.onnx = {os.path.getsize(final_path) / 1e6:.1f} MB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
