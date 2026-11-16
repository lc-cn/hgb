"""Fetch locked official PoC models into ignored storage; never modifies the production APK."""
from pathlib import Path
import argparse
import hashlib
import io
import json
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
LOCK = ROOT / "visionprobe/models.lock.json"

def prepare(variant="v6-tiny"):
    lock = json.loads(LOCK.read_text())
    if variant not in lock["variants"]:
        raise ValueError("Unknown locked model variant")
    spec = lock["variants"][variant]
    folder = ROOT / (".local/vision-poc/assets" if variant == "v6-tiny" else f".local/vision-poc/assets-{variant}")
    records = []
    for kind, item in spec["archives"].items():
        name, size, digest = item["name"], item["bytes"], item["sha256"]
        cache = ROOT / ".local/vision-poc/downloads" / name
        if cache.exists():
            data = cache.read_bytes()
        else:
            with urllib.request.urlopen(lock["baseUrl"] + name, timeout=45) as response:
                data = response.read(size + 1)
        if len(data) != size or hashlib.sha256(data).hexdigest() != digest:
            raise ValueError(f"Model archive does not match the pinned local baseline: {name}")
        cache.parent.mkdir(parents=True, exist_ok=True)
        cache.write_bytes(data)
        with tarfile.open(fileobj=io.BytesIO(data)) as archive:
            for path, expected in spec["files"].items():
                if not path.startswith(f"models/{kind}/"):
                    continue
                member = archive.getmember(name.removesuffix(".tar") + "/" + path.rsplit("/", 1)[1])
                if not member.isfile() or member.size != expected["bytes"]:
                    raise ValueError("Unexpected model member")
                content = archive.extractfile(member).read()
                if hashlib.sha256(content).hexdigest() != expected["sha256"]:
                    raise ValueError(f"Model member does not match its lock: {path}")
                dest = folder / path
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_bytes(content)
                records.append({"path": path, **expected})
    (folder / "models-manifest.json").write_text(json.dumps({"variant": variant, "source": lock["baseUrl"],
        "sourceDocument": lock["sourceDocument"], "archives": spec["archives"], "files": records,
        "scope": "Downloaded official URLs; SHA256 pins the locally reviewed baseline, not an independently signed upstream checksum."}, indent=2) + "\n")
    print(f"Prepared locked {variant} ONNX models for visionprobe only.")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--variant", choices=json.loads(LOCK.read_text())["variants"], default="v6-tiny")
    prepare(parser.parse_args().variant)
