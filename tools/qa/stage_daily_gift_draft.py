"""Stage the draft daily-gift task bundle for the debug-only LocalTaskTrigger.

Writes task-bundle.json plus its resources into ignored .local/debug-task/daily-gift/. The jade icon
is cropped from a verified private capture, so the output is private and must not be committed or
published. Requires Pillow (for the crop only). Also writes a wait-only fixture for plumbing checks.
"""
from pathlib import Path
import hashlib
import io
import json
import shutil
import sys

ROOT = Path(__file__).resolve().parents[2]
MODELS = ROOT / ".local/maa/prepared/assets/maa-model"
FRAME = ROOT / ".local/game-validation/free-daily-claim/15-daily-after-private.png"
FRAME_SHA = "23bbe561f6f1c378ca8ac3db06f00f2c9a80294aa86fd7da4bbd5e8fb46149c3"
OUT = ROOT / ".local/debug-task"
GAME = "com.netease.onmyoji"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def resource(rid, name, data):
    return {"id": rid, "assetName": name, "bytes": len(data), "sha256": sha(data)}


def out(action, field):
    return {"type": "output", "actionId": action, "field": field}


def act(aid, capability, version, inputs, bindings=None):
    node = {"id": aid, "capability": capability, "contractVersion": version, "inputs": inputs}
    if bindings:
        node["bindings"] = bindings
    return {"type": "action", "action": node}


def outside(aid, anchor, dx, dy):
    return act(aid, "screen.deriveRegion", 1, {"dx": dx, "dy": dy, "width": 120, "height": 120,
                                               "referenceWidth": 2400, "referenceHeight": 1080}, {"target": out(anchor, "target")})


def close(aid, region):
    return act(aid, "screen.tapRegion", 1, {"durationMs": 80, "requireNoText": True}, {"region": out(region, "region")})


def dismiss():
    """Close the reward, then the details, by tapping blank space left of each panel (same as DailyGiftDraft.kt)."""
    return [
        act("rewardAnchor", "screen.locateText", 1, {"text": "获得奖励", "minimumConfidence": 0.8}, {"observation": out("after", "observation")}),
        outside("rewardOutside", "rewardAnchor", -842, 65), close("closeReward", "rewardOutside"),
        act("afterReward", "control.wait", 1, {"durationMs": 1500}),
        act("detailsRead", "screen.readText", 2, {"profile": "onmyoji"}),
        act("detailsAnchor", "screen.locateText", 1, {"text": "御行达摩的赠礼", "minimumConfidence": 0.8}, {"observation": out("detailsRead", "observation")}),
        outside("detailsOutside", "detailsAnchor", -392, 145), close("closeDetails", "detailsOutside"),
        act("afterDetails", "control.wait", 1, {"durationMs": 1500}),
        act("listRead", "screen.readText", 2, {"profile": "onmyoji"}),
        act("backOnList", "screen.expectText", 1, {"text": "新手符咒包", "minimumConfidence": 0.8}, {"observation": out("listRead", "observation")}),
    ]


def navigate():
    """Courtyard -> menu -> shop (optional download dialog) -> gift shop -> daily list. Precondition: courtyard."""
    def wait(aid, ms):
        return act(aid, "control.wait", 1, {"durationMs": ms})

    def read(aid):
        return act(aid, "screen.readText", 2, {"profile": "onmyoji"})

    def locate(aid, text, source):
        return act(aid, "screen.locateText", 1, {"text": text, "minimumConfidence": 0.8}, {"observation": out(source, "observation")})

    def tap(aid, target):
        return act(aid, "screen.tap", 1, {"durationMs": 80}, {"target": out(target, "target")})

    def image(aid, template, score, source):
        return act(aid, "screen.locateImage", 1, {"template": template, "minimumScore": score}, {"observation": out(source, "observation")})

    return [
        read("courtyard"), image("scroll", "scroll-icon", 0.9, "courtyard"), tap("openMenu", "scroll"), wait("menuOpening", 1500),
        read("menu"), locate("guild", "阴阳寮", "menu"),
        # The shop label is partly covered by its icon; press the icon right of the stable "阴阳寮" label.
        act("shopIcon", "screen.deriveRegion", 1, {"dx": 163, "dy": -116, "width": 60, "height": 60,
                                                   "referenceWidth": 2400, "referenceHeight": 1080}, {"target": out("guild", "target")}),
        act("openShop", "screen.tapRegion", 1, {"durationMs": 80, "requireNoText": False}, {"region": out("shopIcon", "region")}),
        wait("shopLoading", 4000), read("shop"),
        act("shopState", "screen.classifyText", 1, {"states": [
            {"id": "dialog", "text": "下载", "minimumConfidence": 0.8},
            {"id": "shop", "text": "礼包屋", "minimumConfidence": 0.8, "absent": [{"text": "下载", "minimumConfidence": 0.8}]},
        ]}, {"observation": out("shop", "observation")}),
        {"type": "branch", "value": out("shopState", "state"), "cases": [
            {"equals": "dialog", "program": [locate("cancel", "取消", "shop"), tap("closeDialog", "cancel"), wait("dialogClosing", 2000)]},
            {"equals": "shop", "program": []},
        ]},
        read("shopReady"), locate("giftTab", "礼包屋", "shopReady"), tap("openGiftShop", "giftTab"), wait("giftLoading", 3000),
        read("giftShop"), image("lantern", "daily-lantern", 0.85, "giftShop"), tap("openDaily", "lantern"), wait("dailyLoading", 2500),
        read("daily"),
        act("onDaily", "screen.expectText", 1, {"text": "每日礼包", "minimumConfidence": 0.8}, {"observation": out("daily", "observation")}),
    ]


def daily_gift(jade):
    jade_condition = {"template": "jade-icon", "minimumScore": 0.45}
    read = act("read", "screen.readText", 2, {"profile": "onmyoji"})
    program = [
        read,
        act("anchor", "screen.locateText", 1, {"text": "每日礼包", "minimumConfidence": 0.8}, {"observation": out("read", "observation")}),
        act("button", "screen.deriveRegion", 1, {"dx": -120, "dy": 195, "width": 240, "height": 55,
                                                 "referenceWidth": 2400, "referenceHeight": 1080}, {"target": out("anchor", "target")}),
        act("state", "screen.classifyRegion", 1, {"states": [
            {"id": "claimable", "text": [{"text": "领取", "minimumConfidence": 0.8}], "absent": [jade_condition]},
            {"id": "paid", "present": [jade_condition]},
        ]}, {"region": out("button", "region")}),
        {"type": "branch", "value": out("state", "state"), "cases": [
            {"equals": "claimable", "program": [
                act("claim", "screen.locateText", 1, {"text": "领取", "minimumConfidence": 0.8}, {"observation": out("read", "observation")}),
                act("tap", "screen.tap", 1, {"durationMs": 80}, {"target": out("claim", "target")}),
                act("settle", "control.wait", 1, {"durationMs": 2500}),
                act("after", "screen.readText", 2, {"profile": "onmyoji"}),
                act("reward", "screen.expectText", 1, {"text": "获得奖励", "minimumConfidence": 0.8}, {"observation": out("after", "observation")}),
            ] + dismiss()},
            {"equals": "paid", "program": [
                act("listed", "screen.expectText", 1, {"text": "每日礼包", "minimumConfidence": 0.8}, {"observation": out("read", "observation")}),
            ]},
        ]},
    ]
    models = {name: (MODELS / name).read_bytes() for name in ("det.onnx", "rec.onnx", "keys.txt")}
    icons = {name: crop(source, rect) for name, (source, rect) in ICONS.items()}
    resources = [resource(n, n, d) for n, d in models.items()] + [resource("jade-icon", "jade-icon.png", jade)] + \
        [resource(name.removesuffix(".png"), name, data) for name, data in icons.items()]
    bundle = {
        "formatVersion": 1, "version": "debug-daily-gift-3", "updatedAt": "2026-11-18T00:00:00Z",
        "changelog": "Local debug draft; not published.",
        "tasks": [{"id": "daily-gift", "targetId": "onmyoji", "name": "领取每日礼包", "category": "daily", "priority": 10,
                   "steps": [{"id": "claim", "name": "领取免费每日礼包", "program": program}]},
                  {"id": "open-daily-gift", "targetId": "onmyoji", "name": "前往每日礼包", "category": "daily", "priority": 10,
                   "steps": [{"id": "navigate", "name": "从庭院进入礼包屋日常", "program": navigate()}]}],
        "targets": [{"id": "onmyoji", "name": "阴阳师", "androidPackages": [GAME], "identityPolicy": "NONE",
                     "categories": [{"id": "daily", "name": "日常"}]}],
        "resources": resources,
        "visionProfiles": [{"id": "onmyoji", "targetId": "onmyoji", "packageName": GAME, "recognizer": "maa.ppocr.v1",
                            "models": {"detector": "det.onnx", "recognizer": "rec.onnx", "dictionary": "keys.txt"}}],
    }
    return bundle, {**models, "jade-icon.png": jade, **icons}


def wait_fixture():
    return {
        "formatVersion": 1, "version": "debug-wait-1", "updatedAt": "2026-11-18T00:00:00Z", "changelog": "Plumbing fixture.",
        "tasks": [{"id": "wait", "targetId": "fixture", "name": "等待", "category": "tools", "priority": 1,
                   "steps": [{"id": "step", "name": "等待", "actions": [
                       {"id": "wait", "capability": "control.wait", "contractVersion": 1, "inputs": {"durationMs": 200}}]}]}],
        "targets": [{"id": "fixture", "name": "夹具", "androidPackages": ["test.fixture"], "identityPolicy": "NONE",
                     "categories": [{"id": "tools", "name": "工具"}]}],
    }


def write(folder, bundle, files):
    if folder.exists():
        shutil.rmtree(folder)
    folder.mkdir(parents=True)
    (folder / "task-bundle.json").write_text(json.dumps(bundle, ensure_ascii=False, separators=(",", ":")))
    for name, data in files.items():
        (folder / name).write_bytes(data)


ICONS = {
    # Lower body of the courtyard menu scroll (avoids its changing red badge); unselected "日常" lantern.
    "scroll-icon.png": (ROOT / ".local/game-validation/back-key/02-after-enter-private.png", (2100, 930, 90, 120)),
    "daily-lantern.png": (ROOT / ".local/game-validation/back-key/08-gift-shop-private.png", (2120, 660, 95, 130)),
}


def crop(source, rect):
    from PIL import Image
    x, y, w, h = rect
    out_bytes = io.BytesIO()
    Image.open(source).convert("RGB").crop((x, y, x + w, y + h)).save(out_bytes, format="PNG")
    return out_bytes.getvalue()


def main():
    from PIL import Image
    frame = FRAME.read_bytes()
    if sha(frame) != FRAME_SHA:
        sys.exit("private capture changed")
    crop = io.BytesIO()
    Image.open(io.BytesIO(frame)).convert("RGBA").crop((576, 449, 576 + 36, 449 + 43)).save(crop, format="PNG")
    write(OUT / "daily-gift", *daily_gift(crop.getvalue()))
    write(OUT / "wait", wait_fixture(), {})
    bundle, files = daily_gift(crop.getvalue())
    claim = [488, 445, 728, 500]; outside_rect = [360, 480, 480, 600]
    days = {
        "replay-day1": ROOT / ".local/game-validation/free-daily-claim", "replay-day2": ROOT / ".local/game-validation/back-key"}
    frames = {
        "replay-day1": {"list-before.png": "09-daily-before-private.png", "reward.png": "12-after-claim-settled-private.png",
                        "details-claimed.png": "14-reward-outside-private.png", "list-after.png": "15-daily-after-private.png"},
        "replay-day2": {"list-before.png": "14-daily-list-private.png", "reward.png": "15-after-trigger-private.png",
                        "details-claimed.png": "16-reward-dismissed-private.png", "list-after.png": "17-list-after-private.png"},
    }
    for day, source in days.items():
        staged = dict(files)
        for name, original in frames[day].items():
            staged[name] = (source / original).read_bytes()
        for start in ("list-before.png", "list-after.png"):
            script = {"package": GAME, "start": start, "transitions": [
                {"from": "list-before.png", "rect": claim, "to": "reward.png"},
                {"from": "reward.png", "rect": outside_rect, "to": "details-claimed.png"},
                {"from": "details-claimed.png", "rect": outside_rect, "to": "list-after.png"}]}
            write(OUT / f"{day}-{start.removesuffix('.png')}", bundle, {**staged, "replay.json": json.dumps(script).encode()})
    nav = {
        "nav-day2": {"courtyard.png": "back-key/02-after-enter-private.png", "menu.png": "back-key/03-menu-private.png",
                     "download.png": "back-key/04-shop-private.png", "shop.png": "back-key/07-download-cancelled-private.png",
                     "giftshop.png": "back-key/08-gift-shop-private.png", "daily.png": "back-key/09-daily-list-private.png"},
        "nav-day1": {"courtyard.png": "read-only-sampling/03-loaded-private.png", "menu.png": "read-only-sampling/06-menu-private.png",
                     "download.png": "read-only-sampling/07-shop-private.png", "shop.png": "read-only-sampling/08-shop-download-cancelled-private.png",
                     "giftshop.png": "read-only-sampling/14-gift-shop-private.png", "daily.png": "read-only-sampling/15-daily-gift-list-private.png"},
    }
    for day, mapping in nav.items():
        staged = dict(files)
        for name, original in mapping.items():
            staged[name] = (ROOT / ".local/game-validation" / original).read_bytes()
        script = {"package": GAME, "start": "courtyard.png", "transitions": [
            {"from": "courtyard.png", "rect": [2100, 930, 2190, 1050], "to": "menu.png"},
            {"from": "menu.png", "rect": [1250, 905, 1320, 975], "to": "download.png"},
            {"from": "download.png", "rect": [864, 660, 1074, 750], "to": "shop.png"},
            {"from": "shop.png", "rect": [1900, 940, 2080, 1060], "to": "giftshop.png"},
            {"from": "giftshop.png", "rect": [2120, 660, 2215, 790], "to": "daily.png"}]}
        write(OUT / day, bundle, {**staged, "replay.json": json.dumps(script).encode()})
    print(f"Staged private daily-gift draft and wait fixture under {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
