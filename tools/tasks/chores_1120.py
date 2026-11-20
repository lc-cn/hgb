"""Four free daily chores sampled on 2026-11-20 (.local/game-validation/tasks-1120/), each starting and ending in the
courtyard: 邮件领取, 每日抽签, 好友友情点, 阴阳寮资金. Built on the helpers of tools/qa/stage_daily_gift_draft.py.

Coordinates are on the 2400x1080 reference frame. Every press lands on text or an icon found on the current frame, or on a
spot placed relative to one; a page that does not show up stops the step instead of pressing blindly. Nothing here
spends anything: the only confirmations pressed are 全部领取 → 确定 (mail attachments) and 领取 (guild funds).
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SAMPLES = ROOT / ".local/game-validation/tasks-1120"

# Icons found by image: (capture, (x, y, width, height)). Interior crops avoid badges and changing backgrounds.
ICONS = {
    "mail-icon.png": ("15-mail-closed-private.png", (2068, 40, 52, 40)),
    "lottery-sign.png": ("04-entered-private.png", (605, 733, 66, 80)),
    "lottery-bag.png": ("22-lottery-closed-private.png", (606, 735, 66, 70)),
    # The checklist the courtyard paper man floats above its head: it opens 庭院事务.
    "affairs-icon.png": ("oneclick/30-courtyard-after-private.png", (1417, 572, 44, 40)),
}

MAIL_CORNER = [2000, 0, 200, 110]
MAIL_TITLE = [1080, 70, 240, 100]
MAIL_CLAIM = [300, 840, 240, 150]
CONFIRM_TITLE = [980, 80, 420, 110]
CONFIRM_BUTTONS = [880, 800, 640, 130]
COURTYARD_GROUND = [0, 480, 1700, 600]
LOTTERY_BEST = [980, 630, 440, 90]
LOTTERY_TOTAL = [900, 720, 280, 90]
FRIENDS_TITLE = [1080, 60, 240, 90]
FRIENDS_TAB = [430, 130, 200, 90]
FRIENDS_COLLECT = [300, 800, 320, 100]
FRIENDS_LIST = [440, 280, 560, 580]
HALL_INFO = [2040, 990, 280, 90]
RECRUIT_TAB = [430, 110, 180, 90]
INFO_TAB = [430, 110, 180, 90]
FUNDS_BUTTON = [540, 890, 300, 100]
FUNDS_BADGE = [780, 886, 44, 44]
FUNDS_DIALOG = [960, 280, 480, 90]
FUNDS_CLAIM = [1060, 640, 280, 100]
AFFAIRS_TITLE = [620, 50, 400, 110]
DAILY_TAB = [1940, 200, 160, 110]
ONECLICK_RIM = [1884, 930, 14, 60]
CLAIMED_TITLE = [680, 40, 340, 110]
CLOSE_HINT = [1040, 1010, 340, 60]
LOT_TOTAL = [920, 730, 200, 70]


def build(d):
    """d is the draft module (word, picture, await_screen, find_text, find_image, press, press_offset, outside, close,
    menu_nodes, walk_to_courtyard, out, act, REFERENCE, REWARD_TITLE, GUILD_LABEL, MENU_BAR, DOWNLOAD)."""
    word, picture, out, act = d.word, d.picture, d.out, d.act
    said = d.said

    def pick(aid, template, score, order, source):
        """One match of an icon on the whole frame, by order. Unlike a located target it re-finds the nearest match
        before pressing, so a flat icon that scores on neighbouring pixels too is still pressed once."""
        return act(aid, "screen.selectImage", 1, {"template": template, "minimumScore": score, "order": order, "index": 0},
                   {"observation": out(source, "observation")})
    reward = {"id": "reward", "text": [word("获得奖励", d.REWARD_TITLE)]}

    def close_reward(prefix, source):
        """The reward banner is modal: a tap below its panel closes it and reaches nothing behind. The panel grows with
        the rewards (a pile of mail can fill three rows), so the spot sits under the tallest one yet still on screen for
        the shortest; it is refused if it holds any text."""
        return [d.find_text(f"{prefix}Banner", "获得奖励", d.REWARD_TITLE, source),
                act(f"{prefix}Below", "screen.deriveRegion", 1, {"dx": -30, "dy": 595, "width": 60, "height": 60, **d.REFERENCE},
                    {"target": out(f"{prefix}Banner", "target")}),
                d.close(f"{prefix}Close", f"{prefix}Below")]

    # ---- 邮件领取 ----
    mailbox = {"id": "mailbox", "text": [word("邮箱", MAIL_TITLE)], "absentText": [word("获得奖励", d.REWARD_TITLE), word("全部领取", CONFIRM_TITLE)]}
    mail = [
        d.await_screen("mailStart", [d.COURTYARD_CLOSED, d.COURTYARD_OPEN], 8000),
        pick("mailIcon", "mail-icon", 0.9, "top", "mailStart"), d.press("openMail", "mailIcon"),
        d.await_screen("mailOpen", [mailbox], 8000),
        d.find_text("mailTitle", "邮箱", MAIL_TITLE, "mailOpen"),
        # The system-mail tab, right of the paper: it may be left on 特别邮件 (letters, nothing to claim).
        *d.press_offset("systemTab", "mailTitle", 828, 236),
        # The sign reads 一键 over 领取; recognition may return either line or both as one.
        d.await_screen("mailList", [{**mailbox, "id": "split", "text": mailbox["text"] + [word("领取", MAIL_CLAIM)]},
                                    {**mailbox, "id": "joined", "text": mailbox["text"] + [word("一键领取", MAIL_CLAIM)]},
                                    {**mailbox, "id": "none", "absentText": mailbox["absentText"] + [word("领取", MAIL_CLAIM), word("一键领取", MAIL_CLAIM)]}], 6000, 700),
        {"type": "branch", "value": out("mailList", "state"), "cases": [
            {"equals": "none", "program": [said("mailNone", "没有可领的邮件")]},
        ] + [{"equals": state, "program": [
                d.find_text(f"claimAll{tag}", label, MAIL_CLAIM, "mailList"), d.press(f"pressClaimAll{tag}", f"claimAll{tag}"),
                d.await_screen(f"mailConfirm{tag}", [{"id": "confirm", "text": [word("全部领取", CONFIRM_TITLE), word("确定", CONFIRM_BUTTONS)]}], 6000),
                d.find_text(f"confirmOk{tag}", "确定", CONFIRM_BUTTONS, f"mailConfirm{tag}"), d.press(f"pressConfirm{tag}", f"confirmOk{tag}"),
                d.await_screen(f"mailReward{tag}", [reward], 8000),
                *close_reward(f"mail{tag}", f"mailReward{tag}"),
                d.await_screen(f"mailAfter{tag}", [mailbox], 6000), said(f"mailGot{tag}", "已领取邮件附件"),
            ]} for state, label, tag in (("split", "领取", "A"), ("joined", "一键领取", "B"))]},
        d.await_screen("mailLeaving", [mailbox], 6000),
        d.find_text("mailTitleAgain", "邮箱", MAIL_TITLE, "mailLeaving"),
        *d.press_offset("closeMail", "mailTitleAgain", 772, 6),
    ] + d.walk_to_courtyard(5, 8000, [], [])

    # ---- 每日抽签 ----
    sign = picture("lottery-sign", 0.8, COURTYARD_GROUND)
    bag = picture("lottery-bag", 0.8, COURTYARD_GROUND)
    # The courtyard with its menu open or closed: the 探索 lantern shows either way.
    plain = {"text": [word("探索", d.LANTERN)], "absentText": [word("获得奖励", d.REWARD_TITLE), word("今日最佳奖励", LOTTERY_BEST)]}
    lottery = d.walk_to_courtyard(5, 8000, [], []) + [
        {"type": "until", "value": out("lotState", "state"), "equals": "done", "maximum": 4, "program": [
            d.await_screen("lotState", [{"id": "sign", "present": [sign], **plain}, {"id": "bag", "present": [bag], **plain},
                                        {"id": "done", "absent": [sign, bag], **plain}], 8000, 1200),
            {"type": "branch", "value": out("lotState", "state"), "cases": [
                {"equals": "done", "program": []},
                {"equals": "sign", "program": [
                    pick("signTarget", "lottery-sign", 0.8, "top", "lotState"), d.press("pressSign", "signTarget"),
                    d.await_screen("lotBox", [{"id": "box", "text": [word("今日最佳奖励", LOTTERY_BEST)]}], 6000),
                    d.find_text("bestLabel", "今日最佳奖励", LOTTERY_BEST, "lotBox"),
                    *d.press_offset("drawBox", "bestLabel", -30, -400),
                    d.await_screen("lotPaper", [{"id": "paper", "text": [word("累计奖励", LOTTERY_TOTAL)]}], 8000),
                    d.find_text("totalLabel", "累计奖励", LOTTERY_TOTAL, "lotPaper"),
                    # The X right of the paper; never 解签.
                    *d.press_offset("closePaper", "totalLabel", 498, -648),
                ]},
                {"equals": "bag", "program": [
                    pick("bagTarget", "lottery-bag", 0.8, "top", "lotState"), d.press("pressBag", "bagTarget"),
                    d.await_screen("lotReward", [reward], 8000),
                    *close_reward("lot", "lotReward"),
                ]},
            ]},
        ]},
        d.await_screen("lotDone", [{"id": "courtyard", **plain}], 6000),
    ]

    # ---- 好友友情点 ----
    friends = {"id": "friends", "text": [word("好友", FRIENDS_TITLE)], "absentText": [word("获得奖励", d.REWARD_TITLE)]}
    friendship = d.menu_nodes() + [
        d.await_screen("friendMenu", [d.COURTYARD_OPEN], 5000),
        # 好友's own label is too stylised to read reliably; the menu's slots are fixed, so it is placed from 花合战's.
        d.find_text("friendLabel", "花合战", d.MENU_BAR, "friendMenu"),
        *d.press_offset("friendEntry", "friendLabel", 164, -112),
        d.await_screen("friendPage", [friends], 8000),
        # The page always opens on the plain list; 友情点 is the grey round button right of 好友 at the bottom left.
        # Their labels are too stylised to read, so it is placed from the 好友 tab at the top of the list.
        d.find_text("friendTab", "好友", FRIENDS_TAB, "friendPage"),
        # On the "情点" peeking out from behind the round 好友 button, below the 群组 row.
        *d.press_offset("pointsMode", "friendTab", -40, 790),
        d.await_screen("collectState", [{**friends, "id": "collect", "text": friends["text"] + [word("一键收取", FRIENDS_COLLECT)]},
                                        {**friends, "id": "none", "absentText": friends["absentText"] + [word("一键收取", FRIENDS_COLLECT)]}], 4000, 700),
        {"type": "branch", "value": out("collectState", "state"), "cases": [
            {"equals": "none", "program": [said("pointsNone", "没有可收的友情点")]},
            {"equals": "collect", "program": [
                d.find_text("collectAll", "一键收取", FRIENDS_COLLECT, "collectState"), d.press("pressCollect", "collectAll"),
                d.await_screen("collectReward", [reward], 8000),
                *close_reward("collect", "collectReward"),
                d.await_screen("collectDone", [friends], 6000), said("pointsGot", "已收取友情点"),
            ]},
        ]},
        # Sending one by one is left out: 一键收取 already sends back to everyone who gave, and once today's sending is
        # used up a heart stays orange whatever is pressed, which cannot be told apart from a press that failed.
        d.await_screen("friendLeaving", [friends], 6000),
        d.find_text("friendTitle", "好友", FRIENDS_TITLE, "friendLeaving"),
        *d.press_offset("closeFriends", "friendTitle", 782, 17),
    ] + d.walk_to_courtyard(5, 8000, [], [])

    # ---- 阴阳寮资金 ----
    hall = {"id": "hall", "text": [word("寮信息", HALL_INFO)], "absentText": [word("获得奖励", d.REWARD_TITLE)]}
    info = {"id": "info", "text": [word("资金领取", FUNDS_BUTTON), word("信息", INFO_TAB)], "absentText": [word("获得奖励", d.REWARD_TITLE)]}
    guild = d.menu_nodes() + [
        d.await_screen("guildMenu", [d.COURTYARD_OPEN], 5000),
        d.find_text("guildLabel", "阴阳寮", d.GUILD_LABEL, "guildMenu"),
        *d.press_offset("guildEntry", "guildLabel", -30, -100),
        # Without the guild's extra download, the game asks for it first: that is left to the player.
        # Not in a guild yet, the entry opens the guild search instead; nothing there is pressed but the way back.
        d.await_screen("guildState", [hall, d.DOWNLOAD, {"id": "search", "text": [word("招募中", RECRUIT_TAB)]}], 15000),
        {"type": "branch", "value": out("guildState", "state"), "cases": [
            {"equals": "download", "program": [d.find_text("skipDownload", "取消", d.DIALOG_BUTTONS, "guildState"), d.press("pressSkip", "skipDownload"),
                                               said("guildNoPack", "没有下载阴阳寮扩展包，寮资金跳过")]},
            {"equals": "search", "program": [
                said("guildNone", "还没有加入阴阳寮，寮资金跳过"),
                d.find_text("recruitTab", "招募中", RECRUIT_TAB, "guildState"),
                *d.press_offset("leaveSearch", "recruitTab", -356, -162),
            ]},
            {"equals": "hall", "program": [
                d.find_text("infoLabel", "寮信息", HALL_INFO, "guildState"),
                *d.press_offset("openInfo", "infoLabel", -30, -110),
                d.await_screen("infoPage", [info], 8000),
                act("fundsBadge", "screen.matchColorIn", 1, {"within": FUNDS_BADGE, "lower": [215, 0, 0], "upper": [255, 60, 60], "minimumRatio": 0.05, **d.REFERENCE},
                    {"observation": out("infoPage", "observation")}),
                {"type": "branch", "value": out("fundsBadge", "matched"), "cases": [
                    {"equals": False, "program": [said("fundsAlready", "没有可领的阴阳寮资金")]},
                    {"equals": True, "program": [
                        d.find_text("fundsButton", "资金领取", FUNDS_BUTTON, "infoPage"), d.press("pressFunds", "fundsButton"),
                        d.await_screen("fundsDialog", [{"id": "dialog", "text": [word("每日领取阴阳寮资金", FUNDS_DIALOG), word("领取", FUNDS_CLAIM)]}], 6000),
                        d.find_text("fundsClaim", "领取", FUNDS_CLAIM, "fundsDialog"), d.press("pressFundsClaim", "fundsClaim"),
                        d.await_screen("fundsReward", [reward], 8000),
                        *close_reward("funds", "fundsReward"),
                        d.await_screen("fundsDone", [info], 6000), said("fundsGot", "已领取阴阳寮资金"),
                    ]},
                ]},
                d.await_screen("infoLeaving", [info], 6000),
                d.find_text("infoTab", "信息", INFO_TAB, "infoLeaving"),
                *d.press_offset("leaveInfo", "infoTab", -354, -165),
                d.await_screen("hallLeaving", [hall], 8000),
                d.find_text("hallInfo", "寮信息", HALL_INFO, "hallLeaving"),
                *d.press_offset("leaveHall", "hallInfo", -2000, -1022),
            ]},
        ]},
        # The hall's house icon looks like the shop's home button, so the walk back home is not used here: the back
        # arrow leads straight to the courtyard, after a loading screen.
        d.await_screen("guildBack", [d.COURTYARD_CLOSED, d.COURTYARD_OPEN], 20000, 1000),
    ]

    # ---- 庭院事务一键完成 ----
    affairs = {"id": "affairs", "text": [word("庭院事务", AFFAIRS_TITLE)], "absentText": [word("领取成功", CLAIMED_TITLE), word("累计奖励", LOT_TOTAL)]}
    # The paper man floats a checklist while 庭院事务 has something to do; with everything done it is gone.
    checklist = picture("affairs-icon", 0.85, COURTYARD_GROUND)
    oneclick = d.walk_to_courtyard(5, 8000, [], []) + [
        d.await_screen("affairsStart", [{"id": "open", "present": [checklist], **plain}, {"id": "none", "absent": [checklist], **plain}], 8000, 1200),
        {"type": "branch", "value": out("affairsStart", "state"), "cases": [
            {"equals": "none", "program": [d.await_screen("affairsNone", [{"id": "courtyard", **plain}], 4000, 0), said("affairsNoneSaid", "没有待完成的庭院事务")]},
            {"equals": "open", "program": [
        pick("affairsIcon", "affairs-icon", 0.85, "top", "affairsStart"), d.press("openAffairs", "affairsIcon"),
        # The first visit shows a one-time tip over the page; a tap anywhere dismisses it.
        d.await_screen("affairsOpen", [{"id": "tip", "text": [word("点击任意位置继续", [900, 960, 600, 90])]}, affairs], 8000),
        {"type": "branch", "value": out("affairsOpen", "state"), "cases": [
            {"equals": "affairs", "program": []},
            {"equals": "tip", "program": [d.find_text("tipHint", "点击任意位置继续", [900, 960, 600, 90], "affairsOpen"),
                                          d.press("dismissTip", "tipHint")]},
        ]},
        d.await_screen("affairsPage", [affairs], 6000),
        # The daily tab, where 一键完成 lives.
        d.find_text("dailyTab", "日常", DAILY_TAB, "affairsPage"), d.press("openDailyTab", "dailyTab"),
        d.await_screen("dailyPage", [affairs], 6000, 800),
        # A lit 一键完成 has a white rim; once everything is done it turns grey.
        act("oneclickLit", "screen.matchColorIn", 1, {"within": ONECLICK_RIM, "lower": [230, 230, 230], "upper": [255, 255, 255], "minimumRatio": 0.25, **d.REFERENCE},
            {"observation": out("dailyPage", "observation")}),
        {"type": "branch", "value": out("oneclickLit", "matched"), "cases": [
            {"equals": False, "program": [said("oneclickAlready", "没有待完成的庭院事务")]},
            {"equals": True, "program": [
                d.find_text("dailyAgain", "日常", DAILY_TAB, "dailyPage"),
                *d.press_offset("pressOneclick", "dailyAgain", -48, 644),
                # The results page, with the lottery paper laid over it when the draw was among them.
                {"type": "until", "value": out("claimed", "state"), "equals": "list", "maximum": 4, "program": [
                    d.await_screen("claimed", [{"id": "paper", "text": [word("累计奖励", LOT_TOTAL)]},
                                               {"id": "list", "text": [word("领取成功", CLAIMED_TITLE)], "absentText": [word("累计奖励", LOT_TOTAL)]}], 10000, 800),
                    {"type": "branch", "value": out("claimed", "state"), "cases": [
                        {"equals": "list", "program": []},
                        # Close the paper by its X; never 补签 (it may cost) or 解签.
                        {"equals": "paper", "program": [d.find_text("paperTotal", "累计奖励", LOT_TOTAL, "claimed"),
                                                        *d.press_offset("closeLotPaper", "paperTotal", 529, -650)]},
                    ]},
                ]},
                d.await_screen("claimedList", [{"id": "list", "text": [word("领取成功", CLAIMED_TITLE), word("点击空白处关闭", CLOSE_HINT)],
                                                "absentText": [word("累计奖励", LOT_TOTAL)]}], 6000),
                d.find_text("closeHint", "点击空白处关闭", CLOSE_HINT, "claimedList"), d.press("closeClaimed", "closeHint"),
                d.await_screen("affairsDone", [affairs], 8000, 800), said("oneclickDone", "已一键完成庭院事务"),
            ]},
        ]},
        d.await_screen("affairsLeaving", [affairs], 6000),
        d.find_text("affairsTitle", "庭院事务", AFFAIRS_TITLE, "affairsLeaving"),
        *d.press_offset("leaveAffairs", "affairsTitle", -650, -110),
        d.await_screen("affairsBack", [d.COURTYARD_CLOSED, d.COURTYARD_OPEN], 10000, 800),
            ]},
        ]},
    ]

    enter = {"id": "enter", "name": "启动并进入庭院", "use": "enter-courtyard"}
    steps = {
        "mail": {"id": "mail", "name": "领取邮件附件", "program": mail},
        "lottery": {"id": "lottery", "name": "每日抽签", "program": lottery},
        "friendship": {"id": "friendship", "name": "收送友情点", "program": friendship},
        "guild": {"id": "guild", "name": "领取阴阳寮资金", "program": guild},
        "oneclick": {"id": "oneclick", "name": "庭院事务一键完成", "program": oneclick},
    }
    tasks = [
        {"id": "mail-claim", "targetId": "onmyoji", "name": "邮件领取", "category": "daily", "priority": 10, "fields": [], "steps": [enter, steps["mail"]]},
        {"id": "daily-lottery", "targetId": "onmyoji", "name": "每日抽签", "category": "daily", "priority": 10, "fields": [], "steps": [enter, steps["lottery"]]},
        {"id": "friendship-points", "targetId": "onmyoji", "name": "好友友情点", "category": "daily", "priority": 10, "fields": [], "steps": [enter, steps["friendship"]]},
        {"id": "guild-funds", "targetId": "onmyoji", "name": "阴阳寮资金", "category": "daily", "priority": 10, "fields": [], "steps": [enter, steps["guild"]]},
        {"id": "courtyard-affairs", "targetId": "onmyoji", "name": "庭院事务一键完成", "category": "daily", "priority": 10, "fields": [],
         "steps": [enter, steps["oneclick"]]},
    ]
    return tasks, steps


def icons(crop):
    """Each icon as PNG bytes; crop(path, rect) is the draft's cropper."""
    return {name: crop(SAMPLES / source, rect) for name, (source, rect) in ICONS.items()}
