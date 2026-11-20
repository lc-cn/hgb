"""寄养 (式神育成 in the guild's 结界), sampled on 2026-11-20 on account 归·雨 (.local/game-validation/foster/).

The flow: courtyard → 阴阳寮 → 结界 → 式神育成. If our own foster slot is busy (a timer shows instead of the "+"), it says
so and leaves. Otherwise it opens the friend list, taps the first rows one by one and reads the card name in the side
panel, in the order the player's setting asks for (勾玉 → 太鼓, 体力 → 斗鱼, 经验 → 伞室闭). On a match it enters that friend's
结界 and drags the first shikigami in the list that is neither a 达摩 nor at 满级 onto the free slot, then confirms.

Coordinates are on the 2400x1080 reference frame. Nothing here spends anything: fostering is free; the only
confirmation pressed is 确定 on 确认寄养所选式神. 取消寄养 is never pressed.
"""

SAMPLES_DIR = "../foster"
ICONS = {
    "foster-plus.png": (f"{SAMPLES_DIR}/15-private.png", (1975, 105, 85, 75)),
    "realm-training.png": (f"{SAMPLES_DIR}/25-private.png", (1134, 438, 54, 150)),
    "realm-back.png": (f"{SAMPLES_DIR}/25-private.png", (156, 24, 72, 72)),
    # An empty foster lantern on a friend's page: the 友 diamond.
    "foster-free.png": (f"{SAMPLES_DIR}/20-private.png", (1734, 102, 60, 60)),
    "daruma-white.png": (f"{SAMPLES_DIR}/26-private.png", (390, 768, 96, 72)),
    "daruma-blue.png": (f"{SAMPLES_DIR}/26-private.png", (594, 768, 96, 72)),
    "daruma-red.png": (f"{SAMPLES_DIR}/26-private.png", (792, 768, 96, 72)),
}

HALL_INFO = [2040, 990, 280, 90]
RECRUIT_TAB = [430, 110, 180, 90]
TRAINING_TITLE = [180, 20, 340, 110]
TRAINING_BANNER = [1000, 330, 320, 400]
BACK_CORNER = [100, 0, 220, 130]
MY_SLOT = [1900, 40, 240, 200]
LIST_HEADER = [1300, 140, 520, 90]
CARD_NAME = [1420, 515, 280, 80]
ENTER_REALM = [1400, 800, 320, 100]
PLACE_HINT = [1760, 10, 320, 80]
# The dialog's title ends in a question mark the recognizer reads as part of it, so its two buttons stand for it.
CONFIRM_OK = [1220, 770, 260, 100]
CONFIRM_CANCEL = [940, 770, 240, 100]
# The shikigami list under the lanterns: eight cards 202 apart, the first centred at x 438, y 840.
CARDS = 8
FIRST_CARD_X, CARD_STEP, CARD_Y = 438, 202, 840
# A friend's two foster lanterns. The 可放置式神寄养 hint does not always show, so a free one is told by its 友 diamond.
# The left window starts right of where our own page has a 友 lantern, so our page never passes for a friend's.
FREE_LEFT, FREE_RIGHT = [1710, 50, 140, 170], [1940, 50, 150, 170]
DROPS = {"right": (2016, 132), "left": (1764, 132)}
# 式神育成's title: its centre x and its bottom, which offsets below are measured from.
TITLE_X, TITLE_BOTTOM = 342, 98
# The friend list rows, measured from the centre / bottom of 进入好友结界寄养式神.
HEADER_X, HEADER_BOTTOM = 1551, 204
ROW_X, ROW_YS = 864, [330, 492, 654, 816]

FOSTER_CARD = {"id": "fosterCard", "label": "寄养挑卡", "widget": "SINGLE_CHOICE", "required": True, "section": "寄养",
               "description": "在好友的结界卡里先挑哪一种；同一种按好友列表的顺序", "recommended": "jade",
               "options": [{"value": "jade", "label": "勾玉优先（太鼓）"}, {"value": "stamina", "label": "体力优先（斗鱼）"},
                           {"value": "exp", "label": "经验优先（伞室闭）"}]}
CARD_ORDERS = {"jade": ["太鼓", "斗鱼", "伞室闭"], "stamina": ["斗鱼", "太鼓", "伞室闭"], "exp": ["伞室闭", "斗鱼", "太鼓"]}


def build(d):
    word, picture, out, act, said = d.word, d.picture, d.out, d.act, d.said
    ref = d.REFERENCE

    def pick(aid, template, score, source):
        return act(aid, "screen.selectImage", 1, {"template": template, "minimumScore": score, "order": "top", "index": 0},
                   {"observation": out(source, "observation")})

    def offset_region(aid, anchor, x, y):
        """A 60x60 region centred on (x, y), placed from the 式神育成 title found on the current page."""
        return act(aid, "screen.deriveRegion", 1, {"dx": x - 30 - TITLE_X, "dy": y - 30 - TITLE_BOTTOM, "width": 60, "height": 60, **ref},
                   {"target": out(anchor, "target")})

    title = word("式神育成", TRAINING_TITLE)
    hall = {"id": "hall", "text": [word("寮信息", HALL_INFO)]}
    # A 结界 seen from outside: its corner back arrow, which the hall shares, so not the hall's 寮信息 either. The training
    # banner would say more but koi streamers sway across it.
    realm = {"id": "realm", "present": [picture("realm-back", 0.85, BACK_CORNER)], "absentText": [title, word("寮信息", HALL_INFO)]}
    # The friend list leaves the slot's "+" visible above it, so our own page also requires the list to be gone.
    own_free = {"id": "free", "text": [title], "present": [picture("foster-plus", 0.85, MY_SLOT)], "absentText": [word("进入好友结界寄养式神", LIST_HEADER)]}
    own_busy = {"id": "busy", "text": [title], "absent": [picture("foster-plus", 0.85, MY_SLOT)],
                "absentText": [word("进入好友结界寄养式神", LIST_HEADER)]}
    friends = {"id": "list", "text": [word("进入好友结界寄养式神", LIST_HEADER)]}
    plus = picture("foster-plus", 0.85, MY_SLOT)
    left, right = picture("foster-free", 0.85, FREE_LEFT), picture("foster-free", 0.85, FREE_RIGHT)
    friend_page = {"text": [title], "absentText": [word("确定", CONFIRM_OK), word("进入好友结界寄养式神", LIST_HEADER)]}
    # Each excludes the others: a page that fits two states confirms neither.
    friend_right = {"id": "right", **friend_page, "present": [right], "absent": [plus]}
    friend_left = {"id": "left", **friend_page, "present": [left], "absent": [plus, right]}
    friend_full = {"id": "full", **friend_page, "absent": [plus, left, right]}

    def card_search(key, names):
        """Tap the rows in turn and read the card's name; the first match enters that friend's 结界."""
        pairs = [(name, row) for name in names for row in range(len(ROW_YS))]

        def attempt(k, source):
            # Every press invalidates what was located before it, so the header is found again on the latest frame.
            head = f"{key}Head{k}"
            header = d.find_text(head, "进入好友结界寄养式神", LIST_HEADER, source)
            if k == len(pairs):
                return [said(f"{key}NoCard", "好友里没有可寄养的结界卡"), header,
                        act(f"{key}CloseSpot", "screen.deriveRegion", 1, {"dx": 2130 - HEADER_X, "dy": 510 - HEADER_BOTTOM, "width": 60, "height": 60, **ref},
                            {"target": out(head, "target")}),
                        act(f"{key}CloseList", "screen.tapRegion", 1, {"durationMs": 80, "requireNoText": False}, {"region": out(f"{key}CloseSpot", "region")})]
            name, row = pairs[k]
            return [
                header,
                act(f"{key}Row{k}Spot", "screen.deriveRegion", 1, {"dx": ROW_X - 30 - HEADER_X, "dy": ROW_YS[row] - 30 - HEADER_BOTTOM, "width": 60, "height": 60, **ref},
                    {"target": out(head, "target")}),
                act(f"{key}Row{k}Press", "screen.tapRegion", 1, {"durationMs": 80, "requireNoText": False}, {"region": out(f"{key}Row{k}Spot", "region")}),
                # The two states must exclude each other: a page that fits both confirms neither.
                d.await_screen(f"{key}Card{k}", [{"id": "hit", "text": [word(name, CARD_NAME), word("进入结界", ENTER_REALM)]},
                                                 {**friends, "absentText": [word(name, CARD_NAME)]}], 6000, 600),
                {"type": "branch", "value": out(f"{key}Card{k}", "state"), "cases": [
                    {"equals": "hit", "program": [said(f"{key}Said{k}", f"挑了好友的{name}卡"),
                                                  d.find_text(f"{key}Enter{k}", "进入结界", ENTER_REALM, f"{key}Card{k}"), d.press(f"{key}EnterPress{k}", f"{key}Enter{k}"),
                                                  # The friend's page must open; a press that went nowhere stops here as unknown.
                                                  d.await_screen(f"{key}Entered{k}", [friend_right, friend_left, friend_full], 15000, 800)]},
                    {"equals": "list", "program": attempt(k + 1, f"{key}Card{k}")},
                ]},
            ]
        return attempt(0, "friendList")

    def place():
        """On the friend's page: the first card that is neither at 满级 nor a 达摩, dragged onto a free lantern. Each card
        is looked at only while the page still shows a free lantern, so the checks stay a straight line."""
        def check(i, side):
            x = FIRST_CARD_X + CARD_STEP * i
            face = [x - 75, 740, 150, 140]
            tag = f"{side}{i}"
            drop = DROPS[side]
            drag = [
                d.find_text(f"slotTitle{tag}", "式神育成", TRAINING_TITLE, f"look{tag}"),
                offset_region(f"cardSpot{tag}", f"slotTitle{tag}", x, CARD_Y),
                act(f"drag{tag}", "screen.dragRegion", 1, {"durationMs": 1500, "dx": drop[0] - x, "dy": drop[1] - CARD_Y, **ref},
                    {"region": out(f"cardSpot{tag}", "region")}),
                d.await_screen(f"confirm{tag}", [{"id": "confirm", "text": [word("确定", CONFIRM_OK), word("取消", CONFIRM_CANCEL)]}], 6000, 400),
                d.find_text(f"ok{tag}", "确定", CONFIRM_OK, f"confirm{tag}"), d.press(f"okPress{tag}", f"ok{tag}"),
                d.await_screen(f"placed{tag}", [friend_right, friend_left, friend_full], 8000, 600),
                # The lantern dropped on must now be taken: right stays free, or (dropping left) any lantern stays free,
                # means the game did not take it.
                {"type": "branch", "value": out(f"placed{tag}", "state"), "cases": [
                    {"equals": "full", "program": [said(f"placedSaid{tag}", "已寄养式神")]},
                    {"equals": "left", "program": [said(f"placedLeft{tag}", "已寄养式神" if side == "right" else "寄养没有成功")]},
                    {"equals": "right", "program": [said(f"placedRight{tag}", "寄养没有成功")]},
                ]},
                # Leave at once, so the looks at the next cards find the page gone and do nothing.
                d.find_text(f"doneTitle{tag}", "式神育成", TRAINING_TITLE, f"placed{tag}"),
                offset_region(f"doneBack{tag}", f"doneTitle{tag}", 180, 74),
                act(f"doneLeave{tag}", "screen.tapRegion", 1, {"durationMs": 80, "requireNoText": False}, {"region": out(f"doneBack{tag}", "region")}),
                d.await_screen(f"doneRealm{tag}", [realm], 15000, 800),
            ]
            # One look decides the card: 满级, one of the 达摩, or free to foster. Each state rules out the ones before it, so
            # exactly one holds. A skipped card needs nothing more: the next card is looked at by the loop around.
            mark = word("满", [x - 115, 700, 90, 75])
            faces = [picture(f"daruma-{color}", 0.85, face) for color in ("white", "blue", "red")]
            states = [{"id": "max", "text": [title, mark]}] + \
                [{"id": f"daruma{n}", "text": [title], "absentText": [mark], "present": [faces[n]], "absent": faces[:n]} for n in range(3)] + \
                [{"id": "ok", "text": [title], "absentText": [mark], "absent": faces}]
            program = [d.await_screen(f"look{tag}", states, 4000, 0),
                       {"type": "branch", "value": out(f"look{tag}", "state"), "cases": [
                           {"equals": "ok", "program": drag}] + [{"equals": s_id, "program": []} for s_id in ("max", "daruma0", "daruma1", "daruma2")]}]
            return program

        nodes = []
        for i in range(CARDS):
            nodes += [d.await_screen(f"pending{i}", [friend_right, friend_left, friend_full, realm], 8000, 0),
                      {"type": "branch", "value": out(f"pending{i}", "state"), "cases": [
                          {"equals": "right", "program": check(i, "right")}, {"equals": "left", "program": check(i, "left")},
                          {"equals": "full", "program": []}, {"equals": "realm", "program": []}]}]
        # Still on the page with a lantern free: no card fitted. Full from the start was already said.
        return nodes + [
            d.await_screen("placeEnd", [friend_right, friend_left, friend_full, realm], 8000, 0),
            {"type": "branch", "value": out("placeEnd", "state"), "cases": [
                {"equals": "right", "program": [said("noShikigamiR", "前 8 只式神都是达摩或满级，没有寄养")]},
                {"equals": "left", "program": [said("noShikigamiL", "前 8 只式神都是达摩或满级，没有寄养")]},
                {"equals": "full", "program": []}, {"equals": "realm", "program": []}]},
        ]

    foster = d.menu_nodes() + [
        d.await_screen("guildMenu", [d.COURTYARD_OPEN], 5000),
        d.find_text("guildLabel", "阴阳寮", d.GUILD_LABEL, "guildMenu"),
        *d.press_offset("guildEntry", "guildLabel", -30, -100),
        d.await_screen("guildState", [hall, d.DOWNLOAD, {"id": "search", "text": [word("招募中", RECRUIT_TAB)]}], 15000),
        {"type": "branch", "value": out("guildState", "state"), "cases": [
            {"equals": "download", "program": [d.find_text("skipDownload", "取消", d.DIALOG_BUTTONS, "guildState"), d.press("pressSkip", "skipDownload"),
                                               said("fosterNoPack", "没有下载阴阳寮扩展包，寄养跳过")]},
            {"equals": "search", "program": [
                said("fosterNoGuild", "还没有加入阴阳寮，寄养跳过"),
                d.find_text("recruitTab", "招募中", RECRUIT_TAB, "guildState"),
                *d.press_offset("leaveSearch", "recruitTab", -356, -162),
            ]},
            {"equals": "hall", "program": [
                d.find_text("hallInfo", "寮信息", HALL_INFO, "guildState"),
                *d.press_offset("openRealm", "hallInfo", -184, -110),
                d.await_screen("realmView", [{**realm, "present": realm["present"] + [picture("realm-training", 0.8, TRAINING_BANNER)]}], 15000, 800),
                pick("trainingBanner", "realm-training", 0.8, "realmView"), d.press("openTraining", "trainingBanner"),
                d.await_screen("training", [own_free, own_busy], 10000, 800),
                {"type": "branch", "value": out("training", "state"), "cases": [
                    {"equals": "busy", "program": [said("fosterBusy", "式神还在寄养中")]},
                    {"equals": "free", "program": [
                        pick("plus", "foster-plus", 0.85, "training"), d.press("openList", "plus"),
                        d.await_screen("friendList", [friends], 8000, 800),
                        {"type": "branch", "value": {"type": "configuration", "fieldId": "fosterCard"}, "cases": [
                            {"equals": key, "program": card_search(key, names)} for key, names in CARD_ORDERS.items()]},
                        # After a match the friend's page opens; with none, the list was closed back to our own page.
                        d.await_screen("afterSearch", [{**own_free, "id": "own"}, friend_right, friend_left, friend_full], 15000, 800),
                        {"type": "branch", "value": out("afterSearch", "state"), "cases": [
                            {"equals": "own", "program": []},
                            {"equals": "right", "program": []},
                            {"equals": "left", "program": []},
                            {"equals": "full", "program": [said("friendFull", "好友结界的寄养位已满，没有寄养")]},
                        ]},
                        d.await_screen("onFriend", [{**own_free, "id": "own"}, {"id": "friend", **friend_page, "absent": [plus]}], 8000, 600),
                        {"type": "branch", "value": out("onFriend", "state"), "cases": [
                            {"equals": "own", "program": []},
                            {"equals": "friend", "program": place()},
                        ]},
                    ]},
                ]},
                # Out of 式神育成 by its back arrow, then out of each 结界 (the friend's, then ours) by the corner arrow.
                d.await_screen("trainingLeaving", [{"id": "training", "text": [title], "absentText": [word("进入好友结界寄养式神", LIST_HEADER)]}, realm], 6000),
                {"type": "branch", "value": out("trainingLeaving", "state"), "cases": [
                    {"equals": "realm", "program": []},
                    {"equals": "training", "program": [
                        d.find_text("leaveTitle", "式神育成", TRAINING_TITLE, "trainingLeaving"),
                        offset_region("leaveSpot", "leaveTitle", 180, 74),
                        act("leaveTraining", "screen.tapRegion", 1, {"durationMs": 80, "requireNoText": False}, {"region": out("leaveSpot", "region")}),
                    ]},
                ]},
                {"type": "until", "value": out("realmBack", "state"), "equals": "hall", "maximum": 4, "program": [
                    d.await_screen("realmBack", [hall, realm], 15000, 800),
                    {"type": "branch", "value": out("realmBack", "state"), "cases": [
                        {"equals": "hall", "program": []},
                        {"equals": "realm", "program": [pick("backArrow", "realm-back", 0.85, "realmBack"), d.press("pressBack", "backArrow")]},
                    ]},
                ]},
                d.await_screen("hallLeaving", [hall], 8000),
                d.find_text("hallInfoOut", "寮信息", HALL_INFO, "hallLeaving"),
                *d.press_offset("leaveHall", "hallInfoOut", -2000, -1022),
            ]},
        ]},
        d.await_screen("fosterBack", [d.COURTYARD_CLOSED, d.COURTYARD_OPEN], 20000, 1000),
    ]
    step = {"id": "foster", "name": "寄养式神", "program": foster}
    task = {"id": "shikigami-foster", "targetId": "onmyoji", "name": "寄养", "category": "daily", "priority": 10,
            "fields": [FOSTER_CARD], "steps": [{"id": "enter", "name": "启动并进入庭院", "use": "enter-courtyard"}, step]}
    return [task], step
