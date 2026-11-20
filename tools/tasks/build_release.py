"""Build a public task release for lc-cn/hgb-tasks: task-bundle.json plus the resources it uses.

Takes the live-tested daily-gift flow from tools/qa/stage_daily_gift_draft.py and keeps only the tasks named in
TASKS, then only the resources those tasks reference. The icon templates are cropped from captures under .local/,
so this runs only where those captures are; the crops themselves are plain game interface icons (no account data),
published with the user's approval on 2026-11-20 (art belongs to NetEase, removed on request).

Usage: python tools/tasks/build_release.py v1.1.0 "本次更新说明" [task-id ...]
Without task ids it builds TASKS.
Writes .local/task-release/<version>/ and prints each file's size and SHA-256. Publishing is a separate, manual step.
Needs Pillow (for the crops).
"""
from pathlib import Path
import datetime
import hashlib
import importlib.util
import json
import re
import shutil
import sys

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / ".local/task-release"
# What a public version holds unless the command names tasks. v1.0.0 was ["daily-gift"].
# 喂猫喂狗 and 每日抽签 are left out: 庭院事务一键完成 does both (and more) in one press.
TASKS = ["daily-tour", "daily-gift", "flower-chore", "courtyard-affairs", "mail-claim", "friendship-points", "guild-funds", "shikigami-foster"]


def draft_module():
    spec = importlib.util.spec_from_file_location("draft", ROOT / "tools/qa/stage_daily_gift_draft.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def used_templates(node, found):
    """Every template name the programs mention, wherever it sits."""
    if isinstance(node, dict):
        for key, value in node.items():
            if key == "template" and isinstance(value, str):
                found.add(value)
            else:
                used_templates(value, found)
    elif isinstance(node, list):
        for item in node:
            used_templates(item, found)
    return found


def used_routines(node, found):
    if isinstance(node, dict):
        if isinstance(node.get("use"), str):
            found.add(node["use"])
        for value in node.values():
            used_routines(value, found)
    elif isinstance(node, list):
        for item in node:
            used_routines(item, found)
    return found


def build(version, changelog, wanted=TASKS):
    if not re.fullmatch(r"v\d+\.\d+\.\d+", version):
        sys.exit("版本号要像 v1.2.3")
    draft = draft_module()
    from PIL import Image
    import io
    frame = draft.FRAME.read_bytes()
    if draft.sha(frame) != draft.FRAME_SHA:
        sys.exit("source capture changed")
    jade = io.BytesIO()
    Image.open(io.BytesIO(frame)).convert("RGBA").crop((576, 449, 576 + 36, 449 + 43)).save(jade, format="PNG")
    bundle, files = draft.daily_gift(jade.getvalue())
    # The free daily chores sampled on 2026-11-20, with their icons.
    import importlib.util as util
    spec = util.spec_from_file_location("chores", Path(__file__).with_name("chores_1120.py"))
    chores = util.module_from_spec(spec); spec.loader.exec_module(chores)
    extra, chore_steps = chores.build(draft)
    bundle["tasks"] += extra
    # 日常巡游: the game's own 一键完成 (庭院事务) now covers feeding the pets and the lottery, so the tour runs it instead
    # of the separate pets step, then the chores it does not cover.
    tour = next(task for task in bundle["tasks"] if task["id"] == "daily-tour")
    tour["steps"] = [step for step in tour["steps"] if step["id"] != "pets"] + \
        [chore_steps[key] for key in ("oneclick", "mail", "friendship", "guild")]
    for name, data in chores.icons(draft.crop).items():
        bundle["resources"].append(draft.resource(name.removesuffix(".png"), name, data)); files[name] = data
    # 寄养, sampled the same evening on another account.
    spec = util.spec_from_file_location("foster", Path(__file__).with_name("foster_1120.py"))
    foster = util.module_from_spec(spec); spec.loader.exec_module(foster)
    foster_tasks, _ = foster.build(draft)
    bundle["tasks"] += foster_tasks
    for name, (source, rect) in foster.ICONS.items():
        data = draft.crop(chores.SAMPLES / source, rect)
        bundle["resources"].append(draft.resource(name.removesuffix(".png"), name, data)); files[name] = data

    tasks = [task for task in bundle["tasks"] if task["id"] in wanted]
    if sorted(task["id"] for task in tasks) != sorted(wanted):
        sys.exit(f"tasks missing from the draft: {sorted(set(wanted) - {task['id'] for task in tasks})}")
    # A task sent on its own must not assume the game already sits in the courtyard: it starts by getting there,
    # with the same entry step 领取每日礼包 uses.
    enter = next(step for task in bundle["tasks"] if task["id"] == "daily-gift" for step in task["steps"] if step["id"] == "enter")
    for task in tasks:
        if task["steps"][0].get("use") != enter["use"]:
            task["steps"] = [enter] + task["steps"]
    # Snippets used by kept snippets are kept too, until nothing new turns up.
    routines = used_routines(tasks, set())
    while True:
        kept_routines = [routine for routine in bundle["snippets"] if routine["id"] in routines]
        grown = used_routines(kept_routines, set(routines))
        if grown == routines:
            break
        routines = grown
    templates = used_templates([tasks, kept_routines], set())
    models = set(bundle["visionProfiles"][0]["models"].values())
    resources = [r for r in bundle["resources"] if r["id"] in templates or r["assetName"] in models]
    missing = templates - {r["id"] for r in resources}
    if missing:
        sys.exit(f"templates without a resource: {sorted(missing)}")

    bundle.update({
        "version": version,
        "updatedAt": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "changelog": changelog,
        "tasks": tasks,
        "snippets": kept_routines,
        "resources": resources,
    })
    keep = {r["assetName"] for r in resources}
    files = {name: data for name, data in files.items() if name in keep}

    folder = OUT / version
    if folder.exists():
        shutil.rmtree(folder)
    folder.mkdir(parents=True)
    (folder / "task-bundle.json").write_text(json.dumps(bundle, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    for name, data in files.items():
        (folder / name).write_bytes(data)
    for path in sorted(folder.iterdir()):
        print(f"{path.name}\t{path.stat().st_size}\t{hashlib.sha256(path.read_bytes()).hexdigest()}")
    return folder


if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    build(sys.argv[1], sys.argv[2], sys.argv[3:] or TASKS)
