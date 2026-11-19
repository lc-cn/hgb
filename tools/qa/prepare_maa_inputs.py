"""Prepare pinned Maa code inputs, optionally with debug probe models, in ignored storage."""
from pathlib import Path
import argparse
import hashlib
import json
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def checked(data, spec, label):
    if len(data) != spec["bytes"] or hashlib.sha256(data).hexdigest() != spec["sha256"]:
        raise ValueError(f"Pinned Maa input mismatch: {label}")
    return data


def fetch_or_cached(path, url, spec):
    if path.exists():
        return checked(path.read_bytes(), spec, str(path.name))
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "yys-assist-android-probe"}), timeout=45) as response:
        data = checked(response.read(spec["bytes"] + 1), spec, str(path.name))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return data


def prepare(native_only=False):
    lock = json.loads((ROOT / "client/android/vision/inputs.lock.json").read_text())
    folder = ROOT / ".local/maa-probe"
    prepared = ROOT / ".local/maa/prepared"
    spec = lock["distribution"]
    archive_path = folder / spec["name"]
    fetch_or_cached(archive_path, spec["url"], spec)
    records = []

    def write(path, data):
        destination = prepared / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(data)
        records.append({"path": path, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()})

    with zipfile.ZipFile(archive_path) as archive:
        for source, entry in lock["distributionFiles"].items():
            write(entry["destination"], checked(archive.read(source), entry, source))
    spec = lock["license"]
    license_data = fetch_or_cached(folder / "source/LICENSE.md", spec["url"], spec)
    write("native-assets/maa-license/LICENSE.md", license_data)
    if not native_only:
        for entry in lock["models"]["files"]:
            data = fetch_or_cached(folder / "models" / entry["path"], entry["url"], entry)
            write("assets/maa-model/" + entry["path"], data)
        write("assets/maa-license/LICENSE.md", license_data)
        write("assets/model-manifest.json", (json.dumps(lock["models"], indent=2) + "\n").encode())
    (prepared / "inputs-manifest.json").write_text(json.dumps({"version": lock["version"], "files": records}, indent=2) + "\n")
    print("Prepared pinned Maa native inputs" + (" without models." if native_only else " and debug probe models."))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--native-only", action="store_true", help="Prepare APK code/notices without downloading models")
    prepare(parser.parse_args().native_only)
