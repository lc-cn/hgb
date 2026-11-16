"""Fetch fixed official PoC models into ignored storage; never modifies the production APK."""
from pathlib import Path
import hashlib
import io
import json
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
BASE = "https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/"
MODELS = {
    "det": ("PP-OCRv6_tiny_det_onnx_infer.tar", 1792000, "ff6ab415b0a6e0c488550f2fb5d5046f1719848df220b2dc21b56402a65bc05d"),
    "rec": ("PP-OCRv6_tiny_rec_onnx_infer.tar", 4526080, "1e13b22717b1edd89d4cde4fda272b6c17d5b505c97c2baea99da1a3a2d54b29"),
}

def prepare():
    folder = ROOT / ".local/vision-poc/assets"
    records = []
    for kind, (name, size, digest) in MODELS.items():
        existing = ROOT / f".local/vision-research/paddle-v6-{kind}.tar"
        cache = ROOT / ".local/vision-poc/downloads" / name
        if existing.exists():
            data = existing.read_bytes()
        elif cache.exists():
            data = cache.read_bytes()
        else:
            with urllib.request.urlopen(BASE + name, timeout=60) as response:
                data = response.read(size + 1)
        if len(data) != size or hashlib.sha256(data).hexdigest() != digest:
            raise ValueError(f"Model archive does not match the pinned local baseline: {name}")
        cache.parent.mkdir(parents=True, exist_ok=True)
        cache.write_bytes(data)
        with tarfile.open(fileobj=io.BytesIO(data)) as archive:
            for file in (["inference.onnx"] if kind == "det" else ["inference.onnx", "inference.yml"]):
                member = archive.getmember(name.removesuffix(".tar") + "/" + file)
                if not member.isfile() or member.size > size:
                    raise ValueError("Unexpected model member")
                content = archive.extractfile(member).read()
                dest = folder / "models" / kind / file
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_bytes(content)
                records.append({"path": str(dest.relative_to(folder)), "bytes": len(content), "sha256": hashlib.sha256(content).hexdigest()})
    (folder / "models-manifest.json").write_text(json.dumps({"source": BASE, "archives": MODELS, "files": records,
        "scope": "Downloaded official URLs; SHA256 pins the locally reviewed baseline, not an independently signed upstream checksum."}, indent=2) + "\n")
    print("Prepared fixed tiny ONNX models for visionprobe only.")

if __name__ == "__main__":
    prepare()
