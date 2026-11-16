"""Prepare pinned recognition-only Maa probe inputs in ignored storage. No production dependency."""
from pathlib import Path
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


def prepare():
    lock = json.loads((ROOT / "maaprobe/inputs.lock.json").read_text())
    folder = ROOT / ".local/maa-probe"
    prepared = folder / "prepared"
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
    for entry in lock["models"]["files"]:
        data = fetch_or_cached(folder / "models" / entry["path"], entry["url"], entry)
        write("assets/maa-model/" + entry["path"], data)
    spec = lock["license"]
    write("assets/maa-license/LICENSE.md", fetch_or_cached(folder / "source/LICENSE.md", spec["url"], spec))
    write("assets/model-manifest.json", (json.dumps(lock["models"], indent=2) + "\n").encode())
    (prepared / "inputs-manifest.json").write_text(json.dumps({"version": lock["version"], "files": records}, indent=2) + "\n")
    print("Prepared pinned Maa probe inputs; controller, agent, toolkit and plugin libraries excluded.")


if __name__ == "__main__":
    prepare()
