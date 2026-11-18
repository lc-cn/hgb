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
    resources = [resource(n, n, d) for n, d in models.items()] + [resource("jade-icon", "jade-icon.png", jade)]
    bundle = {
        "formatVersion": 1, "version": "debug-daily-gift-2", "updatedAt": "2026-11-18T00:00:00Z",
        "changelog": "Local debug draft; not published.",
        "tasks": [{"id": "daily-gift", "targetId": "onmyoji", "name": "领取每日礼包", "category": "daily", "priority": 10,
                   "steps": [{"id": "claim", "name": "领取免费每日礼包", "program": program}]}],
        "targets": [{"id": "onmyoji", "name": "阴阳师", "androidPackages": [GAME], "identityPolicy": "NONE",
                     "categories": [{"id": "daily", "name": "日常"}]}],
        "resources": resources,
        "visionProfiles": [{"id": "onmyoji", "targetId": "onmyoji", "packageName": GAME, "recognizer": "maa.ppocr.v1",
                            "models": {"detector": "det.onnx", "recognizer": "rec.onnx", "dictionary": "keys.txt"}}],
    }
    return bundle, {**models, "jade-icon.png": jade}


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
    print(f"Staged private daily-gift draft and wait fixture under {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
