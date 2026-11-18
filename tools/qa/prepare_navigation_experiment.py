"""Stage a navigation-feasibility spec for MaaRegionStateExperimentTest (private frames, ignored storage).

Measures whether the courtyard-to-daily-gift path can be anchored by text OCR or by an icon template.
Day 2 frames come from back-key/, day 1 counterparts from read-only-sampling/. Nothing is committed.
"""
from pathlib import Path
import hashlib
import json
import shutil

ROOT = Path(__file__).resolve().parents[2]
G = ROOT / ".local/game-validation"
OUT = ROOT / ".local/vision-poc/region-experiment-assets/navigation"
FULL = [0, 0, 2400, 1080]
# name -> (source, screen)
FRAMES = {
    "courtyard-day2.png": ("back-key/02-after-enter-private.png", "courtyard"),
    "courtyard-day1.png": ("read-only-sampling/03-loaded-private.png", "courtyard"),
    "menu-day2.png": ("back-key/03-menu-private.png", "menu"),
    "menu-day1.png": ("read-only-sampling/06-menu-private.png", "menu"),
    "download-day2.png": ("back-key/04-shop-private.png", "download"),
    "download-day1.png": ("read-only-sampling/07-shop-private.png", "download"),
    "shop-day2.png": ("back-key/07-download-cancelled-private.png", "shop"),
    "shop-day1.png": ("read-only-sampling/08-shop-download-cancelled-private.png", "shop"),
    "giftshop-day2.png": ("back-key/08-gift-shop-private.png", "giftshop"),
    "giftshop-day1.png": ("read-only-sampling/14-gift-shop-private.png", "giftshop"),
    "daily-day2.png": ("back-key/09-daily-list-private.png", "daily"),
    "daily-day1.png": ("read-only-sampling/15-daily-gift-list-private.png", "daily"),
}
TEXT = {"menu": ["商店"], "download": ["取消", "下载"], "shop": ["礼包屋"], "giftshop": ["日常", "礼包屋"], "daily": ["每日礼包", "日常"],
        "courtyard": ["商店"]}
TEMPLATES = {
    "scroll.png": {"frame": "courtyard-day2.png", "rect": [2100, 930, 90, 120]},
    "daily-lantern.png": {"frame": "giftshop-day2.png", "rect": [2120, 660, 95, 130]},
}
EXPECT = {"scroll.png": {"courtyard"}, "daily-lantern.png": {"giftshop"}}


def main():
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)
    frames = {}
    for name, (source, screen) in FRAMES.items():
        data = (G / source).read_bytes()
        (OUT / name).write_bytes(data)
        frames[name] = {"sha256": hashlib.sha256(data).hexdigest(), "state": screen, "session": name.split("-")[-1][:-4]}
    cases = []
    for name, (_, screen) in FRAMES.items():
        for text in TEXT.get(screen, []):
            cases.append({"id": f"{name}:ocr:{text}", "frame": name, "type": "OCR", "roi": FULL, "expectedText": text})
        for template, screens in EXPECT.items():
            circular = TEMPLATES[template]["frame"] == name
            cases.append({"id": f"{name}:{template}", "frame": name, "type": "TemplateMatch", "roi": FULL, "template": template,
                          "expected": screen in screens, "circular": circular})
    spec = {"frames": frames, "templates": TEMPLATES, "cases": cases, "anchors": [],
            "red": {"method": 4, "lower": [150, 0, 0], "upper": [255, 90, 90], "count": 1, "connected": False}}
    (OUT / "spec.json").write_text(json.dumps(spec, ensure_ascii=False, indent=2))
    print(f"Staged {len(frames)} frames, {len(cases)} cases into {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
