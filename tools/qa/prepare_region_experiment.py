"""Stage private frames and a geometry-only spec for the Maa region/graphic-state experiment.

Copies five locally captured frames (verified by SHA-256) into ignored storage with neutral names
and writes spec.json. Templates are cropped on device from the named frame, so no derived image is
written here. Nothing produced by this script may be committed or published.
"""
from pathlib import Path
import hashlib
import json
import shutil

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / ".local/game-validation"
OUT = ROOT / ".local/vision-poc/region-experiment-assets/region-experiment"

FRAMES = {
    # name: (source, sha256, captured state as observed by a human, session)
    "list-before.png": ("free-daily-claim/09-daily-before-private.png",
                        "1aa8e0795b72089fb7a731dc7755c020ab62a2cfe93fb634748f80f8630da574", "daily card claimable", "claim"),
    "list-after.png": ("free-daily-claim/15-daily-after-private.png",
                       "23bbe561f6f1c378ca8ac3db06f00f2c9a80294aa86fd7da4bbd5e8fb46149c3", "daily card replaced by 1 jade offer", "claim"),
    "list-heldout.png": ("read-only-sampling/15-daily-gift-list-private.png",
                         "fc3fdc77c8488917c34e8cef4818f0cd838e7fd8a0e4ccec3d20a494c78eb8fc", "daily card claimable", "read-only"),
    "details-claimed.png": ("free-daily-claim/14-reward-outside-private.png",
                            "ab63f7a028daa99809e19885d5223b0f6a2706b5219dbab0c7a8da9f785869ce", "first column stamped claimed", "claim"),
    "reward.png": ("free-daily-claim/12-after-claim-settled-private.png",
                   "8e27cf6adc5ecaa367ca8a736c9dd5e6705bdd2db4ad6e3cbde63fbaf15512e2", "reward popup over details", "claim"),
}

# Templates are crops of a single frame. Any case on that same frame region is marked circular.
TEMPLATES = {
    "jade.png": {"frame": "list-after.png", "rect": [576, 449, 36, 43]},
    "stamp.png": {"frame": "details-claimed.png", "rect": [660, 481, 181, 112]},
}
RED = {"method": 4, "lower": [150, 0, 0], "upper": [255, 90, 90], "count": 1, "connected": False}

def card(i):
    """Shop list card i (0..7): two rows of four. Geometry measured on 2400x1080 frames."""
    row, col = divmod(i, 4)
    left = 488 + 334 * col
    return {"button": [left, 445 + 370 * row, 240, 55], "art": [left, 250 + 370 * row, 240, 190]}

def column(i):
    left = 610 + 301 * i
    return {"center": [left, 408, 280, 192], "price": [left, 710, 280, 75]}

def cases():
    result = []
    list_frames = {"list-before.png": [False] + [True] * 7, "list-after.png": [True] * 8, "list-heldout.png": [False] + [True] * 7}
    for frame, jade in list_frames.items():
        for i in range(8):
            geometry = card(i)
            circular = frame == "list-after.png" and i == 0
            result.append({"id": f"{frame}:card{i}:jade", "frame": frame, "type": "TemplateMatch", "roi": geometry["button"],
                           "template": "jade.png", "expected": jade[i], "circular": circular})
            result.append({"id": f"{frame}:card{i}:button-ocr", "frame": frame, "type": "OCR", "roi": geometry["button"],
                           "expectedText": "领取" if not jade[i] else None})
            result.append({"id": f"{frame}:card{i}:art-red", "frame": frame, "type": "ColorMatch", "roi": geometry["art"],
                           "color": RED, "expected": False})
            result.append({"id": f"{frame}:card{i}:art-stamp", "frame": frame, "type": "TemplateMatch", "roi": geometry["art"],
                           "template": "stamp.png", "expected": False})
    prices = ["免费", "1", "18", "30"]
    for i in range(4):
        geometry = column(i)
        result.append({"id": f"details:col{i}:jade", "frame": "details-claimed.png", "type": "TemplateMatch", "roi": geometry["price"],
                       "template": "jade.png", "expected": i != 0})
        result.append({"id": f"details:col{i}:price-ocr", "frame": "details-claimed.png", "type": "OCR", "roi": geometry["price"],
                       "expectedText": prices[i]})
        result.append({"id": f"details:col{i}:red", "frame": "details-claimed.png", "type": "ColorMatch", "roi": geometry["center"],
                       "color": RED, "expected": i == 0})
        result.append({"id": f"details:col{i}:stamp", "frame": "details-claimed.png", "type": "TemplateMatch", "roi": geometry["center"],
                       "template": "stamp.png", "expected": i == 0, "circular": i == 0})
        result.append({"id": f"reward:col{i}:stamp", "frame": "reward.png", "type": "TemplateMatch", "roi": geometry["center"],
                       "template": "stamp.png", "expected": None})
    return result

# Anchor association: full-frame OCR finds the anchor; ROIs are offsets from the anchor box
# (x from its horizontal centre, y from its bottom). Expected boxes are the same human labels used before.
ANCHORS = [
    {"frame": f, "text": "每日礼包", "expectedBox": [540, 208, 675, 250],
     "derived": [{"name": "button", "dx": -120, "dy": 195, "w": 240, "h": 55, "handRoi": card(0)["button"], "checks": ["jade", "ocr"]}]}
    for f in ("list-before.png", "list-after.png", "list-heldout.png")
] + [
    {"frame": "details-claimed.png", "text": "御行达摩的赠礼", "expectedBox": [620, 290, 885, 335],
     "derived": [{"name": "center", "dx": -142, "dy": 73, "w": 280, "h": 192, "handRoi": column(0)["center"], "checks": ["red", "stamp"]},
                 {"name": "price", "dx": -142, "dy": 375, "w": 280, "h": 75, "handRoi": column(0)["price"], "checks": ["jade", "ocr"]}]},
]

def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for stale in OUT.iterdir():
        stale.unlink()
    for name, (source, digest, _, _) in FRAMES.items():
        data = (SOURCE / source).read_bytes()
        if hashlib.sha256(data).hexdigest() != digest:
            raise ValueError(f"Private frame changed: {source}")
        shutil.copyfile(SOURCE / source, OUT / name)
    spec = {
        "frames": {name: {"sha256": digest, "state": state, "session": session} for name, (_, digest, state, session) in FRAMES.items()},
        "templates": TEMPLATES, "red": RED, "cases": cases(), "anchors": ANCHORS,
    }
    (OUT / "spec.json").write_text(json.dumps(spec, ensure_ascii=False, indent=2))
    print(f"Staged {len(FRAMES)} frames and {len(spec['cases'])} cases into ignored {OUT.relative_to(ROOT)}")

if __name__ == "__main__":
    main()
