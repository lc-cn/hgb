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


REFERENCE = {"referenceWidth": 2400, "referenceHeight": 1080}
# Where each thing appears on the 2400x1080 reference frame. The courtyard scene pans, so its lantern window is wide.
LANTERN = [800, 100, 700, 320]
MENU_BAR = [1000, 965, 1200, 90]
GUILD_LABEL = [1000, 965, 220, 90]
SCROLL = [2040, 880, 220, 200]
HOME = [220, 0, 160, 120]
DIALOG_BUTTONS = [800, 650, 800, 120]
TITLE_BUTTON = [1000, 840, 400, 110]
GIFT_TAB = [1860, 960, 300, 100]
DAILY_LANTERN = [2060, 600, 220, 260]
DAILY_LABEL = [440, 180, 320, 100]
CLAIM_BUTTON = [470, 425, 280, 95]
NEXT_LABEL = [440, 560, 320, 100]
# The banner rises as the panel under it grows (a pile of mail fills three rows).
REWARD_TITLE = [800, 150, 800, 320]
DETAILS_TITLE = [500, 230, 500, 130]
# Notification dot of the shop entry on the opened courtyard menu.
SHOP_BADGE = [1307, 885, 44, 44]
FLOWER_BADGE = [1501, 885, 44, 44]
FLOWER_TITLE = [240, 20, 220, 90]
TODAY_TAB = [960, 250, 200, 90]
CHOICE_BUTTONS = [[520, 640, 240, 110], [1080, 640, 240, 110], [1650, 640, 240, 110]]
CHOICE_LABELS = [[516, 550, 280, 80], [1053, 550, 280, 80], [1606, 550, 280, 80]]
FLOWER_CHOICE = {"id": "flowerChoice", "label": "花札三选一礼盒", "widget": "SINGLE_CHOICE", "required": True, "section": "花合战",
                 "description": "领取花合战奖励遇到三选一礼盒时选哪一项", "recommended": "none",
                 "options": [{"value": "none", "label": "不选，停下来由我处理"}, {"value": "soul", "label": "御魂养成"},
                             {"value": "currency", "label": "货币"}, {"value": "stamina", "label": "玩法体力"}]}
CLAIM_ALL = [1500, 820, 320, 240]
PET_TITLE = [1050, 30, 300, 80]
KENNEL = [1200, 540, 800, 220]
BOWLS = [300, 380, 1800, 420]
DETAIL_LABEL = [1760, 840, 240, 90]
FEED_BUTTON = [1840, 822, 260, 96]
FEED_COST = [1930, 500, 120, 110]


def word(text, within):
    return {"text": text, "minimumConfidence": 0.8, "within": within}


def picture(template, score, within):
    return {"template": template, "minimumScore": score, "within": within}


COURTYARD_CLOSED = {"id": "courtyard", "present": [picture("scroll-icon", 0.9, SCROLL)], "text": [word("探索", LANTERN)]}
COURTYARD_OPEN = {"id": "courtyard", "text": [word("阴阳寮", MENU_BAR), word("式神录", MENU_BAR), word("探索", LANTERN)]}
# A dimmed popup leaves icons matchable, so the shop also requires that no dialog buttons show.
SHOP = {"id": "shop", "present": [picture("home-icon", 0.8, HOME)], "absentText": [word("取消", DIALOG_BUTTONS), word("确定", DIALOG_BUTTONS)]}
DOWNLOAD = {"id": "download", "text": [word("下载", DIALOG_BUTTONS), word("取消", DIALOG_BUTTONS)]}
# Left alone a while, the game hides the courtyard's interface; its round back button at the top left brings it back.
IDLE_BACK = [140, 20, 160, 130]
IDLE = {"id": "idle", "present": [picture("idle-back", 0.85, IDLE_BACK)], "absentText": [word("探索", LANTERN)]}
TITLE = {"id": "title", "text": [word("进入游戏", TITLE_BUTTON)]}
DAILY_LIST = {"id": "list", "text": [word("每日礼包", DAILY_LABEL)], "absentText": [word("获得奖励", REWARD_TITLE), word("御行达摩的赠礼", DETAILS_TITLE)]}


def said(aid, text):
    """A line for the run's record, on the branch that was taken (task.result)."""
    return act(aid, "task.result", 1, {"text": text})


def await_screen(aid, states, timeout=8000, stable=0):
    """Continue the moment a declared page holds; a page that never shows stops the step as unknown."""
    return act(aid, "screen.awaitScreen", 1, {"profile": "onmyoji", "states": states, "timeoutMs": timeout, "intervalMs": 350, "stableMs": stable, **REFERENCE})


def find_image(aid, template, score, within, source):
    return act(aid, "screen.locateImageIn", 1, {"template": template, "minimumScore": score, "within": within, **REFERENCE}, {"observation": out(source, "observation")})


def find_text(aid, text, within, source):
    return act(aid, "screen.locateTextIn", 1, {"text": text, "minimumConfidence": 0.8, "within": within, **REFERENCE}, {"observation": out(source, "observation")})


def press(aid, target):
    return act(aid, "screen.tap", 1, {"durationMs": 80}, {"target": out(target, "target")})


def dismiss(source):
    """Close the reward, then the details, by tapping blank space left of each panel (same as DailyGiftDraft.kt)."""
    return [
        find_text("rewardAnchor", "获得奖励", REWARD_TITLE, source),
        outside("rewardOutside", "rewardAnchor", -842, 65), close("closeReward", "rewardOutside"),
        await_screen("details", [{"id": "details", "text": [word("御行达摩的赠礼", DETAILS_TITLE)], "absentText": [word("获得奖励", REWARD_TITLE)]}]),
        find_text("detailsAnchor", "御行达摩的赠礼", DETAILS_TITLE, "details"),
        outside("detailsOutside", "detailsAnchor", -392, 145), close("closeDetails", "detailsOutside"),
        await_screen("backOnList", [{"id": "list", "text": [word("新手符咒包", NEXT_LABEL)], "absentText": [word("御行达摩的赠礼", DETAILS_TITLE)]}]),
    ]


def navigate():
    """Open courtyard menu -> shop (optional download dialog) -> gift shop -> daily list."""
    shop_ready = {"id": "shop", "text": [word("礼包屋", GIFT_TAB)], "absentText": [word("下载", DIALOG_BUTTONS)]}
    dialog = {"id": "dialog", "text": [word("下载", DIALOG_BUTTONS), word("取消", DIALOG_BUTTONS)]}
    return [
        await_screen("menu", [{"id": "open", "text": [word("阴阳寮", GUILD_LABEL)]}]),
        find_text("guild", "阴阳寮", GUILD_LABEL, "menu"),
        # The shop label is partly covered by its icon; press the icon right of the stable "阴阳寮" label.
        act("shopIcon", "screen.deriveRegion", 1, {"dx": 163, "dy": -116, "width": 60, "height": 60, **REFERENCE}, {"target": out("guild", "target")}),
        act("openShop", "screen.tapRegion", 1, {"durationMs": 80, "requireNoText": False}, {"region": out("shopIcon", "region")}),
        await_screen("shopState", [dialog, shop_ready], 12000),
        {"type": "branch", "value": out("shopState", "state"), "cases": [
            {"equals": "dialog", "program": [find_text("cancel", "取消", DIALOG_BUTTONS, "shopState"), press("closeDialog", "cancel")]},
            {"equals": "shop", "program": []},
        ]},
        await_screen("shopReady", [shop_ready]),
        find_text("giftTab", "礼包屋", GIFT_TAB, "shopReady"), press("openGiftShop", "giftTab"),
        await_screen("giftShop", [{"id": "gifts", "present": [picture("daily-lantern", 0.85, DAILY_LANTERN)]}], 10000),
        find_image("lantern", "daily-lantern", 0.85, DAILY_LANTERN, "giftShop"), press("openDaily", "lantern"),
        await_screen("onDaily", [DAILY_LIST], 10000),
    ]


def walk_to_courtyard(maximum, wait_ms, states, cases):
    """Until the courtyard shows, handle the current page. Never the system back key: in the courtyard it
    opens the quit dialog. An undeclared page gets no input and stops the step when the wait runs out."""
    known = [COURTYARD_CLOSED, COURTYARD_OPEN, SHOP, DOWNLOAD, IDLE] + states
    return [
        {"type": "until", "value": out("place", "state"), "equals": "courtyard", "maximum": maximum, "program": [
            await_screen("place", known, wait_ms),
            {"type": "branch", "value": out("place", "state"), "cases": [
                {"equals": "courtyard", "program": []},
                {"equals": "shop", "program": [
                    find_image("homeIcon", "home-icon", 0.8, HOME, "place"), press("pressHome", "homeIcon"),
                    await_screen("leftShop", [{"id": "left", "absent": [picture("home-icon", 0.8, HOME)]}], 10000, 0),
                ]},
                {"equals": "download", "program": [
                    find_text("cancelTarget", "取消", DIALOG_BUTTONS, "place"), press("cancelPress", "cancelTarget"),
                    await_screen("leftDialog", [{"id": "left", "absentText": [word("下载", DIALOG_BUTTONS)]}], 5000, 0),
                ]},
                {"equals": "idle", "program": [
                    find_image("idleBack", "idle-back", 0.85, IDLE_BACK, "place"), press("pressIdleBack", "idleBack"),
                    await_screen("leftIdle", [{"id": "left", "absent": [picture("idle-back", 0.85, IDLE_BACK)]}], 5000, 0),
                ]},
            ] + cases},
        ]},
        await_screen("arrived", [COURTYARD_CLOSED, COURTYARD_OPEN], 3000, 0),
    ]


def menu_nodes():
    return [
        await_screen("menuState", [{**COURTYARD_CLOSED, "id": "closed"}, {**COURTYARD_OPEN, "id": "open"}]),
        {"type": "branch", "value": out("menuState", "state"), "cases": [
            {"equals": "open", "program": []},
            {"equals": "closed", "program": [find_image("scroll", "scroll-icon", 0.9, SCROLL, "menuState"), press("openMenu", "scroll")]},
        ]},
        await_screen("menuReady", [{"id": "open", "text": [word("阴阳寮", GUILD_LABEL)]}], 5000),
    ]


def only_when_badged(badge, chore, done=None, nothing=None):
    """Do a chore only if its courtyard entry shows a notification dot, and end back in the courtyard. done / nothing:
    the result line for each case."""
    return menu_nodes() + [
        await_screen("badgeFrame", [COURTYARD_OPEN], 5000),
        act("badge", "screen.matchColorIn", 1, {"within": badge, "lower": [215, 0, 0], "upper": [255, 60, 60], "minimumRatio": 0.05, **REFERENCE},
            {"observation": out("badgeFrame", "observation")}),
        {"type": "branch", "value": out("badge", "matched"), "cases": [
            {"equals": True, "program": chore + walk_to_courtyard(5, 8000, [], []) + ([said("badgedDone", done)] if done else [])},
            {"equals": False, "program": [said("badgedNothing", nothing)] if nothing else []},
        ]},
        await_screen("choreDone", [COURTYARD_CLOSED, COURTYARD_OPEN], 8000),
    ]


def press_offset(prefix, anchor, dx, dy):
    """Press a 60x60 spot placed relative to a label that was just located on the current page."""
    return [
        act(f"{prefix}Spot", "screen.deriveRegion", 1, {"dx": dx, "dy": dy, "width": 60, "height": 60, **REFERENCE}, {"target": out(anchor, "target")}),
        act(f"{prefix}Press", "screen.tapRegion", 1, {"durationMs": 80, "requireNoText": False}, {"region": out(f"{prefix}Spot", "region")}),
    ]


def flower_chore():
    """Claim everything on the tasks tab first (its experience can unlock rewards), then on the rewards tab."""
    page = {"id": "page", "text": [word("花合战", FLOWER_TITLE)], "absentText": [word("获得奖励", REWARD_TITLE)]}

    def pick(prefix, value, label, column):
        """Press the box the player configured, after checking its label is where the layout says."""
        name = f"{prefix}{value.capitalize()}"
        return {"equals": value, "program": [
            await_screen(f"{name}Box", [{"id": "box", "text": [word(label, CHOICE_LABELS[column]), word("选择", CHOICE_BUTTONS[column])]}], 3000),
            find_text(f"{name}Target", "选择", CHOICE_BUTTONS[column], f"{name}Box"), press(f"{name}Press", f"{name}Target"),
        ]}

    def claim_all(prefix):
        """The button only shows while something can be claimed; without it the tab is already done."""
        button = picture("claim-all", 0.8, CLAIM_ALL)
        result = f"{prefix}Result"
        return [
            await_screen(f"{prefix}Button", [{"id": "ready", "present": [button]}, {"id": "done", "absent": [button]}], 6000, 700),
            {"type": "branch", "value": out(f"{prefix}Button", "state"), "cases": [
                {"equals": "done", "program": []},
                {"equals": "ready", "program": [
                    find_image(f"{prefix}All", "claim-all", 0.8, CLAIM_ALL, f"{prefix}Button"), press(f"{prefix}Claim", f"{prefix}All"),
                    # Handle what the claim brings up until the page stays plain long enough that no popup is coming.
                    {"type": "until", "value": out(result, "state"), "equals": "page", "maximum": 6, "program": [
                        await_screen(result, [{"id": "reward", "text": [word("获得奖励", REWARD_TITLE)]},
                                              {"id": "choice", "text": [word("选择", CHOICE_BUTTONS[1])]},
                                              {**page, "absentText": page["absentText"] + [word("选择", CHOICE_BUTTONS[1])]}], 10000, 3000),
                        {"type": "branch", "value": out(result, "state"), "cases": [
                            {"equals": "page", "program": []},
                            {"equals": "reward", "program": [
                                find_text(f"{prefix}Banner", "获得奖励", REWARD_TITLE, result),
                                # A spot below the modal popup, clear of this page's buttons. The press re-finds the banner on a fresh frame,
                                # so it only lands while the popup is up; reading the dimmed backdrop for text only produces noise here.
                                outside(f"{prefix}Outside", f"{prefix}Banner", 120, 516),
                                act(f"{prefix}Close", "screen.tapRegion", 1, {"durationMs": 80, "requireNoText": False}, {"region": out(f"{prefix}Outside", "region")}),
                            ]},
                            # "none" has no case on purpose: the step stops at the box and leaves the pick to the player.
                            {"equals": "choice", "program": [{"type": "branch", "value": {"type": "configuration", "fieldId": "flowerChoice"}, "cases": [
                                pick(prefix, "soul", "御魂养成", 0), pick(prefix, "currency", "货币", 1), pick(prefix, "stamina", "玩法体力", 2),
                            ]}]},
                        ]},
                    ]},
                ]},
            ]},
        ]

    return [
        find_text("flowerLabel", "花合战", MENU_BAR, "badgeFrame"),
        *press_offset("flowerEntry", "flowerLabel", -30, -100),
        await_screen("flowerPage", [page], 10000),
        find_text("tasksAnchor", "花合战", FLOWER_TITLE, "flowerPage"),
        *press_offset("tasksTab", "tasksAnchor", 1694, 422),
        await_screen("tasksPage", [{"id": "tasks", "text": [word("今日", TODAY_TAB)]}], 5000),
        *claim_all("task"),
        await_screen("afterTasks", [page], 6000),
        find_text("rewardsAnchor", "花合战", FLOWER_TITLE, "afterTasks"),
        *press_offset("rewardsTab", "rewardsAnchor", 1690, 242),
        await_screen("rewardsPage", [{**page, "absentText": page["absentText"] + [word("今日", TODAY_TAB)]}], 5000),
        *claim_all("gift"),
        await_screen("afterRewards", [page], 6000),
        find_text("leaveAnchor", "花合战", FLOWER_TITLE, "afterRewards"),
        *press_offset("leaveFlower", "leaveAnchor", -182, -58),
    ]


def pets_chore():
    """Feed while a pet shows a bowl. Any dialog after feeding has never been seen, so it stops the step."""
    title = word("宠物小屋", PET_TITLE)
    kennel = picture("kennel", 0.75, KENNEL)
    bowl = picture("pet-bowl", 0.75, BOWLS)
    detail = {"id": "detail", "text": [word("配饰", DETAIL_LABEL)]}
    # Feeding is priced in sushi (stamina). The cost icon must be the sushi icon before the button is pressed.
    panel = {"id": "panel", "text": [word("喂食", FEED_BUTTON)], "present": [picture("sushi-cost", 0.85, FEED_COST)]}
    return [
        await_screen("kennelState", [{"id": "kennel", "present": [kennel]}, {"id": "none", "absent": [kennel], "text": [word("探索", LANTERN)]}], 8000, 700),
        {"type": "branch", "value": out("kennelState", "state"), "cases": [
            {"equals": "none", "program": []},
            {"equals": "kennel", "program": [
                find_image("kennelTarget", "kennel", 0.75, KENNEL, "kennelState"), press("openKennel", "kennelTarget"),
                await_screen("petRoom", [{"id": "room", "text": [title]}], 10000),
                # One feeding per run: a pet stays hungry after a single portion, and each portion costs stamina.
                await_screen("bowlState", [{"id": "hungry", "present": [bowl], "text": [title]}, {"id": "full", "absent": [bowl], "text": [title]}], 6000, 1200),
                {"type": "branch", "value": out("bowlState", "state"), "cases": [
                    {"equals": "full", "program": []},
                    {"equals": "hungry", "program": [
                        find_image("bowlTarget", "pet-bowl", 0.75, BOWLS, "bowlState"), press("openPet", "bowlTarget"),
                        await_screen("petDetail", [detail], 6000),
                        find_text("detailAnchor", "配饰", DETAIL_LABEL, "petDetail"),
                        *press_offset("satiety", "detailAnchor", 78, -359),
                        await_screen("feedPanel", [panel], 6000),
                        find_text("feedTarget", "喂食", FEED_BUTTON, "feedPanel"), press("feed", "feedTarget"),
                        await_screen("fed", [{**panel, "absentText": [word("取消", DIALOG_BUTTONS), word("确定", DIALOG_BUTTONS)]}], 6000, 1500),
                        find_text("leaveAnchor", "喂食", FEED_BUTTON, "fed"),
                        *press_offset("leavePet", "leaveAnchor", -1550, 10),
                    ]},
                ]},
                await_screen("leavingRoom", [{"id": "room", "text": [title]}], 8000),
                find_text("roomAnchor", "宠物小屋", PET_TITLE, "leavingRoom"),
                *press_offset("leaveRoom", "roomAnchor", -1032, -60),
            ] + walk_to_courtyard(5, 8000, [], [])},
        ]},
        await_screen("petsDone", [COURTYARD_CLOSED, COURTYARD_OPEN], 8000),
    ]


def routines():
    enter = [
        act("launch", "app.launch", 1, {"profile": "onmyoji"}),
        act("foreground", "app.awaitForeground", 1, {"profile": "onmyoji", "timeoutMs": 30000}),
    ] + walk_to_courtyard(12, 60000, [TITLE], [
        {"equals": "title", "program": [
            find_text("enterTarget", "进入游戏", TITLE_BUTTON, "place"), press("enterPress", "enterTarget"),
            await_screen("leftTitle", [{"id": "left", "absentText": [word("进入游戏", TITLE_BUTTON)]}], 20000, 0),
        ]},
    ])
    return [
        {"id": "enter-courtyard", "program": enter},
        {"id": "open-courtyard-menu", "program": menu_nodes()},
        {"id": "return-to-courtyard", "program": walk_to_courtyard(5, 8000, [], [])},
    ]


def daily_gift(jade):
    jade_condition = {"template": "jade-icon", "minimumScore": 0.45}
    program = [
        await_screen("listed", [DAILY_LIST], 5000),
        find_text("anchor", "每日礼包", DAILY_LABEL, "listed"),
        act("button", "screen.deriveRegion", 1, {"dx": -120, "dy": 195, "width": 240, "height": 55, **REFERENCE}, {"target": out("anchor", "target")}),
        act("state", "screen.classifyRegion", 1, {"states": [
            {"id": "claimable", "text": [{"text": "领取", "minimumConfidence": 0.8}], "absent": [jade_condition]},
            {"id": "paid", "present": [jade_condition]},
        ]}, {"region": out("button", "region")}),
        {"type": "branch", "value": out("state", "state"), "cases": [
            {"equals": "claimable", "program": [
                find_text("claim", "领取", CLAIM_BUTTON, "listed"), press("tap", "claim"),
                await_screen("after", [{"id": "reward", "text": [word("获得奖励", REWARD_TITLE)]}]),
            ] + dismiss("after") + [said("claimedGift", "已领取免费每日礼包")]},
            {"equals": "paid", "program": [await_screen("paidList", [DAILY_LIST], 3000, 0), said("giftAlready", "今天的免费每日礼包已领过")]},
        ]},
    ]
    models = {name: (MODELS / name).read_bytes() for name in ("det.onnx", "rec.onnx", "keys.txt")}
    icons = {name: crop(source, rect) for name, (source, rect) in ICONS.items()}
    resources = [resource(n, n, d) for n, d in models.items()] + [resource("jade-icon", "jade-icon.png", jade)] + \
        [resource(name.removesuffix(".png"), name, data) for name, data in icons.items()]
    ENTER = {"id": "enter", "name": "启动并进入庭院", "use": "enter-courtyard"}
    MENU = {"id": "menu", "name": "打开庭院菜单", "use": "open-courtyard-menu"}
    RETURN = {"id": "return", "name": "返回庭院", "use": "return-to-courtyard"}
    GO_DAILY = {"id": "navigate", "name": "前往礼包屋日常", "program": navigate()}
    CLAIM = {"id": "claim", "name": "领取免费每日礼包", "program": program}
    FLOWER = {"id": "flower", "name": "花合战（有红点才去）", "program": only_when_badged(FLOWER_BADGE, flower_chore(), "已领取花合战奖励", "花合战没有可领的奖励")}
    PETS = {"id": "pets", "name": "喂猫喂狗", "program": pets_chore()}

    def task(task_id, name, steps):
        fields = [FLOWER_CHOICE] if FLOWER in steps else []
        return {"id": task_id, "targetId": "onmyoji", "name": name, "category": "daily", "priority": 10, "fields": fields, "steps": steps}

    bundle = {
        "formatVersion": 1, "version": "debug-daily-tour-7", "updatedAt": "2026-11-18T00:00:00Z",
        "changelog": "Local debug draft; not published.",
        "tasks": [task("daily-tour", "日常巡游", [ENTER, {"id": "daily-gift", "name": "每日礼包（商店有红点才去）", "program": only_when_badged(SHOP_BADGE, navigate() + program, None, "商店没有红点，每日礼包跳过")}, FLOWER, PETS]),
                  task("flower-chore", "花合战领取", [FLOWER]),
                  task("pets-chore", "喂猫喂狗", [PETS]),
                  task("daily-gift", "领取每日礼包", [ENTER, MENU, GO_DAILY, CLAIM, RETURN]),
                  task("claim-daily-gift", "在礼包页领取", [CLAIM, RETURN]),
                  task("open-daily-gift", "前往每日礼包", [MENU, GO_DAILY]),
                  task("enter-courtyard", "启动并进入庭院", [ENTER]),
                  task("return-courtyard", "返回庭院", [RETURN])],
        "routines": routines(),
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
    # Only the silhouette inside the quick-home icon; the frame around it shifts between shop pages.
    "home-icon.png": (ROOT / ".local/game-validation/back-key/17-list-after-private.png", (256, 14, 58, 58)),
    "claim-all.png": (ROOT / ".local/game-validation/badge-sampling/10-flower-battle-private.png", (1600, 900, 100, 90)),
    "kennel.png": (ROOT / ".local/game-validation/badge-sampling/31-back-from-activity-private.png", (1762, 650, 90, 60)),
    "pet-bowl.png": (ROOT / ".local/game-validation/badge-sampling/40-pet-private.png", (1392, 578, 62, 52)),
    "sushi-cost.png": (ROOT / ".local/game-validation/live-tour-1119-d/16-feed-panel-private.png", (1968, 528, 54, 48)),
    "idle-back.png": (ROOT / ".local/game-validation/tasks-1120/idle/00-idle-private.png", (180, 58, 50, 50)),
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
    claim = [488, 445, 728, 500]; outside_rect = [340, 465, 490, 610]; home_rect = [240, 0, 335, 110]
    days = {
        "replay-day1": ROOT / ".local/game-validation/free-daily-claim", "replay-day2": ROOT / ".local/game-validation/back-key"}
    frames = {
        "replay-day1": {"list-before.png": "09-daily-before-private.png", "reward.png": "12-after-claim-settled-private.png",
                        "details-claimed.png": "14-reward-outside-private.png", "list-after.png": "15-daily-after-private.png",
                        "giftshop.png": "../read-only-sampling/14-gift-shop-private.png", "shop.png": "../read-only-sampling/08-shop-download-cancelled-private.png",
                        "courtyard.png": "../read-only-sampling/03-loaded-private.png"},
        "replay-day2": {"list-before.png": "14-daily-list-private.png", "reward.png": "15-after-trigger-private.png",
                        "details-claimed.png": "16-reward-dismissed-private.png", "list-after.png": "17-list-after-private.png",
                        "giftshop.png": "08-gift-shop-private.png", "shop.png": "07-download-cancelled-private.png",
                        "courtyard.png": "02-after-enter-private.png"},
    }
    for day, source in days.items():
        staged = dict(files)
        for name, original in frames[day].items():
            staged[name] = (source / original).read_bytes()
        for start in ("list-before.png", "list-after.png"):
            script = {"package": GAME, "start": start, "transitions": [
                {"from": "list-before.png", "rect": claim, "to": "reward.png"},
                {"from": "reward.png", "rect": outside_rect, "to": "details-claimed.png"},
                {"from": "details-claimed.png", "rect": outside_rect, "to": "list-after.png"},
                {"from": "list-after.png", "rect": home_rect, "to": "courtyard.png"}]}
            write(OUT / f"{day}-{start.removesuffix('.png')}", bundle, {**staged, "replay.json": json.dumps(script).encode()})
        # Back never leaves the list: the loop must give up as unknown instead of pressing forever.
        stuck = {"package": GAME, "start": "list-after.png", "transitions": []}
        write(OUT / f"{day}-stuck", bundle, {**staged, "replay.json": json.dumps(stuck).encode()})
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
        # The whole flow: courtyard to the gift, claim it, and come back.
        claimed = "free-daily-claim" if day == "nav-day1" else "back-key"
        names = frames["replay-day1" if day == "nav-day1" else "replay-day2"]
        full = {**staged, **{n: (ROOT / ".local/game-validation" / claimed / names[n]).read_bytes()
                             for n in ("list-before.png", "reward.png", "details-claimed.png", "list-after.png")}}
        steps = [t if t["to"] != "daily.png" else {**t, "to": "list-before.png"} for t in script["transitions"]] + [
            {"from": "list-before.png", "rect": claim, "to": "reward.png"},
            {"from": "reward.png", "rect": outside_rect, "to": "details-claimed.png"},
            {"from": "details-claimed.png", "rect": outside_rect, "to": "list-after.png"},
            {"from": "list-after.png", "rect": home_rect, "to": "courtyard.png"}]
        write(OUT / day.replace("nav-", "full-"), bundle, {**full, "replay.json": json.dumps({"package": GAME, "start": "courtyard.png", "transitions": steps}).encode()})
    samples = ROOT / ".local/game-validation"
    pages = {"title.png": "back-key/01-launch-private.png", "courtyard.png": "back-key/02-after-enter-private.png", "menu.png": "back-key/03-menu-private.png",
             "download.png": "back-key/04-shop-private.png", "shop.png": "back-key/07-download-cancelled-private.png",
             "giftshop.png": "back-key/08-gift-shop-private.png", "menu-clear.png": "live-1119-return/03-after-run-private.png", "unknown.png": "read-only-sampling/04-profile-private.png"}
    staged = {**files, **{name: (samples / original).read_bytes() for name, original in pages.items()}}
    routes = {
        "route-title": ("title.png", [{"from": "title.png", "rect": [1040, 840, 1360, 950], "to": "courtyard.png"}]),
        "route-download": ("download.png", [{"from": "download.png", "rect": [864, 660, 1074, 750], "to": "shop.png"},
                                            {"from": "shop.png", "rect": home_rect, "to": "courtyard.png"}]),
        "route-gift-shop": ("giftshop.png", [{"from": "giftshop.png", "rect": home_rect, "to": "courtyard.png"}]),
        "route-no-badge": ("courtyard.png", [{"from": "courtyard.png", "rect": [2100, 930, 2190, 1050], "to": "menu-clear.png"}]),
        "route-open-menu": ("menu.png", []),
        "route-closed": ("courtyard.png", []),
        "route-unknown": ("unknown.png", []),
    }
    sampled = samples / "badge-sampling"
    fed = io.BytesIO()
    room = Image.open(sampled / "40-pet-private.png").convert("RGB")
    room.paste(room.crop((1255, 555, 1365, 655)), (1365, 555))  # The same room with the bowl bubble covered.
    room.save(fed, format="PNG")
    tour_pages = {"tour-menu.png": "02-after-return-private.png", "flower.png": "10-flower-battle-private.png", "flower-tasks.png": "11-flower-tasks-private.png",
                  "tour-courtyard.png": "12-back-from-flower-private.png", "pets.png": "40-pet-private.png"}
    tour_files = {**staged, "pets-fed.png": fed.getvalue(), **{name: (sampled / original).read_bytes() for name, original in tour_pages.items()},
                  "reward.png": (samples / "back-key/15-after-trigger-private.png").read_bytes(),
                  "flower-clear.png": (samples / "read-only-sampling/06-menu-private.png").read_bytes()}
    claim_rect = [1590, 890, 1710, 995]
    kennel_rect = [1440, 600, 1720, 730]
    popup_blank = [1310, 915, 1450, 1065]
    tour_files["flower-reward.png"] = (ROOT / ".local/game-validation/live-tour-1119-c/99-final-private.png").read_bytes()
    flower_route = [
        {"from": "tour-menu.png", "rect": [1450, 925, 1515, 995], "to": "flower.png"},
        {"from": "flower.png", "rect": [2020, 500, 2105, 585], "to": "flower-tasks.png"},
        {"from": "flower-tasks.png", "rect": claim_rect, "to": "flower-reward.png"},
        {"from": "flower-reward.png", "rect": popup_blank, "to": "flower-tasks.png"},
        {"from": "flower-tasks.png", "rect": [2020, 320, 2105, 405], "to": "flower.png"},
        {"from": "flower.png", "rect": [150, 20, 225, 95], "to": "tour-courtyard.png"}]
    live = ROOT / ".local/game-validation/live-tour-1119-d"
    tour_files.update({"pet-detail.png": (live / "15-after-invites-private.png").read_bytes(), "feed-panel.png": (live / "16-feed-panel-private.png").read_bytes(),
                       "feed-done.png": (live / "18-after-feed-b-private.png").read_bytes(), "pets-after.png": (live / "19-after-blank-private.png").read_bytes()})
    pets_route = [
        {"from": "tour-courtyard.png", "rect": kennel_rect, "to": "pets.png"},
        {"from": "pets.png", "rect": [1340, 540, 1500, 670], "to": "pet-detail.png"},
        {"from": "pet-detail.png", "rect": [1925, 530, 2010, 620], "to": "feed-panel.png"},
        {"from": "feed-panel.png", "rect": [1840, 822, 2100, 918], "to": "feed-done.png"},
        {"from": "feed-done.png", "rect": [380, 860, 520, 1000], "to": "pets-after.png"},
        {"from": "pets-after.png", "rect": [160, 30, 235, 105], "to": "tour-courtyard.png"}]
    picked = io.BytesIO()
    plain = Image.open(sampled / "10-flower-battle-private.png").convert("RGB")
    plain.paste(plain.crop((1590, 700, 1710, 805)), (1590, 890))  # The rewards tab with the claim button gone.
    plain.save(picked, format="PNG")
    tour_files["flower-picked.png"] = picked.getvalue()
    tour_files["choice.png"] = (ROOT / ".local/game-validation/live-tour-1119-b/run-009-private.png").read_bytes()
    tour_files["flower-tasks-done.png"] = (ROOT / ".local/game-validation/live-tour-1119-b/00-before-private.png").read_bytes()
    done_route = [{"from": "tour-menu.png", "rect": [1450, 925, 1515, 995], "to": "flower.png"},
                  {"from": "flower.png", "rect": [2020, 500, 2105, 585], "to": "flower-tasks-done.png"},
                  {"from": "flower-tasks-done.png", "rect": [2020, 320, 2105, 405], "to": "flower.png"},
                  {"from": "flower.png", "rect": [150, 20, 225, 95], "to": "tour-courtyard.png"}]
    for name, start, transitions in (("tour-all", "tour-menu.png", flower_route + pets_route), ("tour-flower-clear", "flower-clear.png", []),
                                     ("tour-tasks-done", "tour-menu.png", done_route),
                                     ("tour-choice", "tour-menu.png", done_route[:3] + [
                                         {"from": "flower.png", "rect": claim_rect, "to": "choice.png"},
                                         {"from": "choice.png", "rect": [1080, 640, 1320, 750], "to": "flower-reward.png"},
                                         {"from": "flower-reward.png", "rect": popup_blank, "to": "flower-picked.png"},
                                         {"from": "flower-picked.png", "rect": [150, 20, 225, 95], "to": "tour-courtyard.png"}]),
                                     ("tour-pets", "tour-courtyard.png", pets_route), ("tour-pets-full", "tour-courtyard.png", [
                                         {"from": "tour-courtyard.png", "rect": kennel_rect, "to": "pets-fed.png"},
                                         {"from": "pets-fed.png", "rect": [160, 30, 235, 105], "to": "tour-courtyard.png"}])):
        write(OUT / name, bundle, {**tour_files, "replay.json": json.dumps({"package": GAME, "start": start, "transitions": transitions}).encode()})
    for name, (start, transitions) in routes.items():
        write(OUT / name, bundle, {**staged, "replay.json": json.dumps({"package": GAME, "start": start, "transitions": transitions}).encode()})
    print(f"Staged private daily-gift draft and wait fixture under {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
