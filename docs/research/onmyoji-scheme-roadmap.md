# 阴阳师自动化方案调研与最终版路线图

调研日期：2026-11-18。证据标注：**【源码】** 在固定提交中读到；**【issue】** 来自 GitHub issue；**【推测】** 由源码推断、未运行验证。本文的“方案”对应 [CONTEXT.md](../../CONTEXT.md) 里的“任务”：单人、队长、队员等差异放进任务配置，不拆成多个任务。

## 摘要

1. **最终版做 65 个方案**：首发（P0）10 个，第二批（P1）23 个，后续（P2）32 个（含用户追加的 5 个，见 5.3.1、5.3.2）；另有 15 类玩法明确不做。
2. MaaYYs（提交 `5df99bf`）有 44 个任务入口，界面实际导入 35 个；OAS（提交 `ac712ac`）有 52 个玩法任务，另有 2 个系统任务。
3. assttyys 有 66 个方案，三个项目合并去重后得到上述 60 个方案；其中 29 个在三个项目里都有对应实现。
4. 首发清单：启动并进入庭院、归位庭院、每日礼包、庭院事务、邮件、签到、永久勾玉卡、宠物投喂、御魂单人、个人结界突破。
5. 最大的动作缺口是**跨皮肤识别**（阻塞全部 60 个方案），其次是全局弹窗守卫和数值比较分支（各阻塞 31 个）。
6. 三个项目反复出现的暗病：找不到就当完成、子项失败仍记成功、计次不等于业务结果、超时参数散落；OAS 还有未受配置约束的勾玉购买分支（见 3.2 节）。
7. OAS 是 GPL-3.0，代码和资源都不能复制；MaaYYs 是 MIT，assttyys 也是 MIT，但游戏截图模板的权利不随许可证转移，所以三者都只借思路。
8. 消耗勾玉或魂玉、御魂强化与处置等规则列为待确认决策（第 8 节），本文不设默认值。

## 1. MaaYYs

仓库 [TanyaShue/MaaYYs](https://github.com/TanyaShue/MaaYYs)，分支 `main`，提交 `5df99bf5588919d9d1e8514fed5934da5a63f70c`（2026-11-17，版本 v4.1.0-beta.1），[MIT 许可](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/LICENSE)。

| 项目 | 情况 | 证据 |
| --- | --- | --- |
| 技术栈 | MaaFramework 流水线 JSON、MXU 图形界面、Go 自定义 agent | [README](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/README.md#L25)、[agent 注册表](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/agent/register.go#L42-L86)（25 个动作、9 个识别器）【源码】 |
| 平台 | 安卓模拟器走 ADB，macOS 走 PlayCover；要求 16:9，推荐 1280×720 | [controller](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/interface.json#L24-L32)、[使用须知](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/README.md#L87)【源码】 |
| 识别 | 1796 个流水线节点：OCR 945、模板匹配 368、无识别直通 324、特征匹配 95、自定义 31、神经网络检测 25、颜色匹配 8 | 脚本统计 `resource_pack/base/pipeline` 全部 JSON，含调试文件【源码】 |
| 控制 | 点击 818、滑动 40、自定义动作 119、启停应用 5 | 同上【源码】 |
| 调度 | 仓库内没有定时调度器。任务顺序由界面和[预设](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/preset/%E4%B8%80%E9%94%AE%E6%97%A5%E5%B8%B8.json)决定；任务内用[时间窗检查](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/agent/actions/time_check/time_check.go#L24)和[周状态记录](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/agent/register.go#L79-L80)判断是否执行 | 【源码】；定时能力是否由 MXU 提供，未在本仓库核实【推测】 |
| 配置 | 每个任务一个 JSON，选项通过 `pipeline_override` 改写节点参数；按渠道放置[资源包覆盖](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/resource_pack/official/pipeline/%E5%90%AF%E5%8A%A8%E6%B8%B8%E6%88%8F.json) | [示例：购物](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E8%B4%AD%E7%89%A9.json)【源码】 |

### 1.1 任务数量与清单

**数法**：解析 `tasks/*.json` 的 `task` 数组，共 44 个文件、44 个入口；再与 [`interface.json` 的 import 列表](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/interface.json#L124)比对，导入 35 个、未导入 9 个。未导入的任务在界面上看不到。README 把“自动业原火”列为任务入口，但它[不在导入列表](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/README.md#L109)，两处不一致【源码】。

| # | 任务 | 分组 | 导入界面 | 入口节点 |
| --- | --- | --- | --- | --- |
| 1 | [关闭游戏](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E5%85%B3%E9%97%AD%E6%B8%B8%E6%88%8F.json) | 日常 | 是 | `关闭阴阳师` |
| 2 | [召唤鬼王](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E5%8F%AC%E5%94%A4%E9%AC%BC%E7%8E%8B.json) | 限时 | 是 | `召唤鬼王` |
| 3 | [困28](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E5%9B%B028.json) | 独立副本 | 是 | `困28` |
| 4 | [自动地鬼](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E5%9C%B0%E9%AC%BC.json) | 独立副本 | 是 | `开始地鬼-检查时间` |
| 5 | [契灵探查](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E5%A5%91%E7%81%B5%E6%8E%A2%E6%9F%A5.json) | 独立副本 | 是 | `契灵探查` |
| 6 | [寮三十捐材料](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E5%AF%AE%E4%B8%89%E5%8D%81%E6%8D%90%E6%9D%90%E6%96%99.json) | 日常 | 是 | `寮三十-开始任务` |
| 7 | [寮商店购物](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E5%AF%AE%E5%95%86%E5%BA%97%E8%B4%AD%E7%89%A9.json) | 日常 | 是 | `阴阳寮商店购物` |
| 8 | [自动寮突破](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E5%AF%AE%E7%AA%81%E7%A0%B4.json) | 独立副本 | 是 | `寮突` |
| 9 | [式神图鉴分享(仅限mumu模拟器可用)](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E5%BC%8F%E7%A5%9E%E5%9B%BE%E9%89%B4%E5%88%86%E4%BA%AB.json) | 独立副本 | 是 | `式神图鉴分享` |
| 10 | [式神委派](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E5%BC%8F%E7%A5%9E%E5%A7%94%E6%B4%BE.json) | 日常 | 是 | `式神委派` |
| 11 | [自动御灵](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E5%BE%A1%E7%81%B5.json) | 独立副本 | 是 | `自动御灵1` |
| 12 | [自动御魂](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E5%BE%A1%E9%AD%82.json) | 独立副本 | 是 | `自动御魂` |
| 13 | [自动悬赏](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E6%82%AC%E8%B5%8F.json) | 独立副本 | 是 | `自动悬赏封印` |
| 14 | [打开游戏](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E6%89%93%E5%BC%80%E6%B8%B8%E6%88%8F.json) | 日常 | 是 | `启动游戏` |
| 15 | [投喂宠物](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E6%8A%95%E5%96%82%E5%AE%A0%E7%89%A9.json) | 日常 | 是 | `投喂宠物` |
| 16 | [自动斗技](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E6%96%97%E6%8A%80.json) | 独立副本 | 是 | `自动斗技-开始时间检查` |
| 17 | [日常奖励领取](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E6%97%A5%E5%B8%B8%E5%A5%96%E5%8A%B1%E9%A2%86%E5%8F%96.json) | 日常 | 是 | `日常-开始执行日常任务` |
| 18 | [珍旅居商店购物](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E7%8F%8D%E6%97%85%E5%B1%85%E5%95%86%E5%BA%97%E8%B4%AD%E7%89%A9.json) | 日常 | 是 | `珍旅居商店购买` |
| 19 | [真蛇](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E7%9C%9F%E8%9B%87.json) | 独立副本 | 是 | `真蛇` |
| 20 | [自动每周秘闻](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E7%A7%98%E9%97%BB.json) | 独立副本 | 是 | `自动秘闻` |
| 21 | [组队副本](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E7%BB%84%E9%98%9F%E5%89%AF%E6%9C%AC.json) | 独立副本 | 是 | `组队副本` |
| 22 | [组队御魂-房主](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E7%BB%84%E9%98%9F%E5%BE%A1%E9%AD%82-%E6%88%BF%E4%B8%BB.json) | 独立副本 | 是 | `组队御魂-房主` |
| 23 | [组队御魂-队友](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E7%BB%84%E9%98%9F%E5%BE%A1%E9%AD%82-%E9%98%9F%E5%8F%8B.json) | 独立副本 | 是 | `组队御魂-队友` |
| 24 | [结界奖励领取](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E7%BB%93%E7%95%8C%E5%A5%96%E5%8A%B1%E9%A2%86%E5%8F%96.json) | 日常 | 是 | `阴阳寮奖励领取` |
| 25 | [结界突破](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E7%BB%93%E7%95%8C%E7%AA%81%E7%A0%B4.json) | 独立副本 | 是 | `自动结界` |
| 26 | [网易大神签到](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E7%BD%91%E6%98%93%E5%A4%A7%E7%A5%9E%E7%AD%BE%E5%88%B0.json) | 日常 | 是 | `启动大神` |
| 27 | [肝绘卷](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E8%82%9D%E7%BB%98%E5%8D%B7.json) | 独立副本 | 是 | `肝绘卷` |
| 28 | [英杰试炼](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E8%8B%B1%E6%9D%B0%E8%AF%95%E7%82%BC.json) | 独立副本 | 是 | `英杰试炼` |
| 29 | [自动购物](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E8%B4%AD%E7%89%A9.json) | 日常 | 是 | `商店购买` |
| 30 | [超鬼王低星清票](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E8%B6%85%E9%AC%BC%E7%8E%8B.json) | 限时 | 是 | `超鬼王` |
| 31 | [通用爬塔副本](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E9%80%9A%E7%94%A8%E7%88%AC%E5%A1%94%E5%89%AF%E6%9C%AC.json) | 独立副本 | 是 | `通用爬塔副本-开始` |
| 32 | [自动逢魔](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E9%80%A2%E9%AD%94.json) | 独立副本 | 是 | `开始逢魔-检查时间` |
| 33 | [自动道馆突破](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E9%81%93%E9%A6%86%E7%AA%81%E7%A0%B4.json) | 独立副本 | 是 | `自动道馆` |
| 34 | [自动阴界之门](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E9%98%B4%E7%95%8C%E4%B9%8B%E9%97%A8.json) | 独立副本 | 是 | `阴界之门-时间检查` |
| 35 | [自动麒麟](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E9%BA%92%E9%BA%9F.json) | 独立副本 | 是 | `麒麟-开始时间检查` |
| 36 | [自动999](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/999.json) | 限时 | 否 | `999-开始` |
| 37 | [自动业原火](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E4%B8%9A%E5%8E%9F%E7%81%AB.json) | 独立副本 | 否 | `业原火-开始业原火` |
| 38 | [活动爬塔-武道大会](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E6%B4%BB%E5%8A%A8-%E6%AD%A6%E9%81%93%E5%A4%A7%E4%BC%9A.json) | 限时 | 否 | `活动爬塔-武道大会` |
| 39 | [活动爬塔-云华依言歌-活动大富翁](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E6%B4%BB%E5%8A%A8%E5%A4%A7%E5%AF%8C%E7%BF%81.json) | 限时 | 否 | `活动爬塔-云华依言歌-开始活动大富翁` |
| 40 | [活动爬塔-拾此一瞬](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E6%B4%BB%E5%8A%A8%E7%88%AC%E5%A1%94.json) | 限时 | 否 | `活动爬塔-拾此一瞬-开始活动爬塔任务` |
| 41 | [对弈竞猜](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E7%AB%9E%E7%8C%9C.json) | 限时 | 否 | `竞猜` |
| 42 | [花车里程领取](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E8%8A%B1%E8%BD%A6.json) | 限时 | 否 | `花车` |
| 43 | [超鬼王简易版](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E8%B6%85%E9%AC%BC%E7%8E%8B%E5%A5%BD%E5%8F%8B%E5%88%86%E4%BA%AB.json) | 限时 | 否 | `超鬼王好友分享` |
| 44 | [限时活动](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E9%99%90%E6%97%B6%E6%B4%BB%E5%8A%A8.json) | 限时 | 否 | `限时活动-泛舟争流` |

### 1.2 已知问题

| 问题 | 证据 | 类型 |
| --- | --- | --- |
| 阴界之门连续多日进不去。报告者认为 `expected` 里的竖线分隔不生效，并且 5 秒超时过短。全部流水线有 205 个节点用同样写法，报告自己的日志也显示该节点识别成功，所以超时更可能是根因 | [#132](https://github.com/TanyaShue/MaaYYs/issues/132)、[节点定义](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/resource_pack/base/pipeline/%E6%88%98%E6%96%97/%E9%98%B4%E9%97%B4%E4%B9%8B%E9%97%A8.json#L342-L343) | issue + 源码；根因为推测 |
| 结界和寮突破打不过时反复重打，没有“换下一个” | [#128](https://github.com/TanyaShue/MaaYYs/issues/128)、[#100](https://github.com/TanyaShue/MaaYYs/issues/100) | issue |
| 活动爬塔和寮突破卡在准备页；维护者建议手动锁定阵容 | [#127](https://github.com/TanyaShue/MaaYYs/issues/127) | issue |
| 游戏重启次数放在对象字段里，累计 5 次后直接停止任务，之后不再重置 | [restartgame.go](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/agent/actions/restartgame/restartgame.go#L22) | 源码 |
| 逢魔答题是占位实现：答案为固定坐标，直接点第一个。维护者说明该功能已取消，但节点仍在流水线里 | [question_matcher.go](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/agent/actions/question_matcher/question_matcher.go#L83-L88)、[节点](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/resource_pack/base/pipeline/%E6%88%98%E6%96%97/%E8%87%AA%E5%8A%A8%E9%80%A2%E9%AD%94.json#L759)、[#100](https://github.com/TanyaShue/MaaYYs/issues/100) | 源码 + issue |
| 突破只在读到 `0/30` 时结束，读数失败没有单独结果 | [结束突破](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/resource_pack/base/pipeline/%E6%88%98%E6%96%97/%E8%87%AA%E5%8A%A8%E7%BB%93%E7%95%8C.json#L343) | 源码；后果为推测 |
| 任务计数只存在内存里，进程重启后丢失 | [task_counter.go](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/agent/actions/task_counter/task_counter.go#L15) | 源码 |
| 配置里启用了 Sentry 遥测和链路追踪 | [interface.json](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/interface.json#L14-L17) | 源码 |
| 竞猜代码连接公共 MQTT 测试服务器和第三方预测接口，外部数据会影响下注（该任务未导入界面） | [guess.go](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/agent/actions/guess/guess.go#L31-L44) | 源码 |
| 长时间运行后系统卡顿；商店黑蛋、斗技设置页、困 28 都报告过卡住 | [#92](https://github.com/TanyaShue/MaaYYs/issues/92)、[#125](https://github.com/TanyaShue/MaaYYs/issues/125)、[#90](https://github.com/TanyaShue/MaaYYs/issues/90)、[#88](https://github.com/TanyaShue/MaaYYs/issues/88) | issue |

issue 统计：共 96 个，其中 60 个是发布机器人的上传失败记录，人工 issue 36 个；开放 10 个。

## 2. OnmyojiAutoScript（OAS）

仓库 [nearaura/OnmyojiAutoScript](https://github.com/nearaura/OnmyojiAutoScript)，分支 `dev`，提交 `ac712accdc30397ac45104f3ef16b721500d8fef`（2026-11-16），[GPL-3.0 许可](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/LICENSE)。

**仓库迁移依据**：[README 的“临时仓库”一节](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/README.md#L35-L37)写明 2026-11-08 原 `runhey` 账号被禁，暂用当前备份仓库。本次核实 `runhey/OnmyojiAutoScript` 返回 404，所以这是项目方自述的迁移，原仓库的 issue 历史无法复核。早期调查引用的 #1665、#1562、#1708 现在都打不开，本文不再当作当前证据。

| 项目 | 情况 | 证据 |
| --- | --- | --- |
| 技术栈 | Python 3.10 调度后端，独立图形界面（`gui.py`、`fluentui/`），服务端用 FastAPI；框架沿用 AzurLaneAutoScript 的结构 | [README](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/README.md)、[遗留的碧蓝航线任务配置](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/module/config/argument/task.yaml#L50)【源码】 |
| 截图 | ADB、uiautomator2、DroidCast、scrcpy、窗口后台、MuMu IPC | [ScreenshotMethod](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Script/config_device.py#L18-L27)【源码】 |
| 控制 | adb、uiautomator2、minitouch、窗口消息 | [ControlMethod](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Script/config_device.py#L29-L33)【源码】 |
| 识别 | 模板匹配（含掩码、多尺度）、SIFT 特征匹配、PaddleOCR，百鬼夜行用目标检测模型 | [RuleImage](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/module/atom/image.py#L109-L133)、[OCR](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/module/ocr/base_ocr.py#L48)、[百鬼配置](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Hyakkiyakou/script_task.py#L57-L68)【源码】 |
| 调度 | 每个任务自带启用状态、下次运行、优先级、成功／失败间隔、刷新时刻和随机浮动；支持过滤、先来后到、优先级三种规则 | [Scheduler](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Component/config_scheduler.py#L8-L17)、[规则](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Script/config_optimization.py#L14-L17)、[更新队列](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/module/config/config.py#L177-L216)【源码】 |
| 配置 | 每个任务一组 pydantic 模型，自动生成界面并保存为 JSON | [ConfigModel](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/module/config/config_model.py#L86-L156)【源码】 |
| 异常 | 60 秒无进展或 300 秒长等待判定卡死，同一按钮点击过多也报错，随后重启游戏；连续失败 3 次退出调度器 | [卡死检测](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/module/device/device.py#L34-L36)、[点击检测](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/module/device/device.py#L220-L238)、[失败退出](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/script.py#L721-L735)【源码】 |
| 导航 | 页面图：45 处页面定义，带识别条件、跳转动作和失败边惩罚 | [default_pages](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/GameUi/default_pages.py)、[边惩罚](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/GameUi/session.py#L149-L163)【源码】 |

### 2.1 任务数量与清单

**数法**：以 [`config_menu.py`](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/module/config/config_menu.py#L16-L44) 的菜单为准，去掉 Script 分组（脚本设置、Restart、GlobalGame）后剩 52 个玩法任务，每个都有 `tasks/<名称>/script_task.py`。`tasks/` 下共 54 个带 `script_task.py` 的目录，多出的 2 个是系统任务 Restart 和 GotoMain。`OrochiMoans` 只有配置，没有脚本，不计入。

| 菜单分组 | 数量 | 任务 |
| --- | --- | --- |
| 御魂副本 | 6 | [八岐大蛇](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Orochi)、[业原火](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Sougenbi)、[日轮之城](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/FallenSun)、[永生之海](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/EternitySea)、[六道之门](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/SixRealms)、[彼世逢魔](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/OtherWorldTwilight) |
| 日常 | 13 | [每日琐事](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/DailyTrifles)、[地域鬼王](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/AreaBoss)、[金币妖怪](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/GoldYoukai)、[经验妖怪](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/ExperienceYoukai)、[年兽](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Nian)、[花合战](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/TalismanPass)、[封魔之时](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/DemonEncounter)、[小猫咪](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Pets)、[御魂整理](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/SoulsTidy)、[式神委派](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Delegation)、[悬赏封印](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/WantedQuests)、[石距](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Tako)、[大神签到](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/AutoCheckinBigGod) |
| 肝度玩法 | 8 | [契灵之境](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/BondlingFairyland)、[觉醒副本](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/EvoZone)、[御灵之境](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/GoryouRealm)、[探索](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Exploration)、[百鬼夜行](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Hyakkiyakou)、[英杰试炼](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/HeroTest)、[FindJade（小号协作）](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/FindJade)、[绘卷](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/MemoryScrolls) |
| 阴阳寮 | 11 | [结界蹭卡](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/KekkaiUtilize)、[结界挂卡](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/KekkaiActivation)、[个人突破](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/RealmRaid)、[寮突破](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/RyouToppa)、[道馆](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Dokan)、[集体任务](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/CollectiveMissions)、[狩猎战](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Hunt)、[狭间暗域](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/AbyssShadows)、[宴会](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/GuildBanquet)、[首领退治](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/DemonRetreat)、[寮活动监控](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/GuildActivityMonitor) |
| 每周 | 6 | [真八岐大蛇](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/TrueOrochi)、[大富翁（商店合集）](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/RichMan)、[秘闻之境](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Secret)、[每周琐事](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/WeeklyTrifles)、[神秘商店](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/MysteryShop)、[斗技](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Duel) |
| 限时活动 | 8 | [当期式神爬塔](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/ActivityShikigami)、[超鬼王](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/MetaDemon)、[对弈竞猜](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/FrogBoss)、[花车巡游](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/FloatParade)、[智力答题](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Quiz)、[KittyShop（限时活动）](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/KittyShop)、[DyeTrials（限时活动）](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/DyeTrials)、[武道大会](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/BudokaiTournament) |

### 2.2 已知问题

| 问题 | 证据 | 类型 |
| --- | --- | --- |
| **逢魔宝箱默认花 50 勾玉买蓝票**：看到蓝票商品就点购买，没有配置开关；同一段里的买体力才有开关 | [_box](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/DemonEncounter/script_task.py#L396-L405) | 源码；实际扣费未运行验证 |
| 悬赏前置失败时，只记一句“可能已完成”，然后按完成安排下次运行 | [WantedQuests](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/WantedQuests/script_task.py#L65-L70) | 源码 |
| 蹭卡剩余时间 OCR 失败后只打日志，下一步仍拿结果做时间加法，可能抛异常 | [KekkaiUtilize](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/KekkaiUtilize/script_task.py#L86-L92) | 源码；后果为推测 |
| 子项是否成功都不影响结果：每日琐事、大富翁都固定记成功 | [DailyTrifles](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/DailyTrifles/script_task.py#L42)、[RichMan](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/RichMan/script_task.py#L32) | 源码 |
| 配置项没有接上：寮祈愿分支是空的；登录收获的勾玉、签到、御魂开关在代码里没有读取 | [guild_wish](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/DailyTrifles/script_task.py#L31-L32)、[开关定义](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Restart/config.py#L18-L26)、[收获循环](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Restart/login.py#L263-L296) | 源码 |
| 自定义刷新时刻后，成功间隔和指定时间都会被覆盖成第二天 | [task_delay](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/module/config/config.py#L352-L362) | 源码；后果为推测 |
| `tasks/` 里有 548 处无条件循环，分布在 95 个文件中，主要靠全局 60 秒卡死检测兜底 | 脚本统计；[卡死检测](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/module/device/device.py#L34-L36) | 源码 |
| 突破券 OCR 多读一位（如 128/30）；同一区域在不同渲染模式下位置不同 | [#7](https://github.com/nearaura/OnmyojiAutoScript/issues/7) | issue |
| 优先级列表引用了不存在的任务 OrochiMoans、OrochiJudgement | [SCHEDULER_PRIORITY](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/module/config/config_manual.py#L10-L23) | 源码 |

[#7](https://github.com/nearaura/OnmyojiAutoScript/issues/7) 的第一个问题（集体任务缺少“领取奖励”和超时）在当前提交里[已有对应代码](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/CollectiveMissions/script_task.py#L265-L283)，issue 仍开放。备份仓库只有 6 个 issue，其中 3 个是自动工作流失败记录，不能代表问题全貌。

## 3. 三项目覆盖矩阵

assttyys 依据提交 `7d26c420`，详见[能力调研](assttyys-capability-survey.md)。**数法**：三个项目的全部条目（66 个方案、44 个任务、54 个带脚本的目录）都由脚本核对过，每条都能在下表、第 6 节或公共组件里找到归属，没有遗漏。“功能 N”表示该玩法只存在于 assttyys 的功能里，没有独立方案。

| 编号 | 玩法 | assttyys | MaaYYs | OAS |
| --- | --- | --- | --- | --- |
| B01 | 启动并进入庭院 | [功能690](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/690_%E5%90%AF%E5%8A%A8%E6%B8%B8%E6%88%8F.ts) | [打开游戏](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E6%89%93%E5%BC%80%E6%B8%B8%E6%88%8F.json) | [登录与收获](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Restart) |
| B02 | 归位庭院 | [返回庭院](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L21) | — | [回庭院](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/GotoMain) |
| B03 | 加成开关 | [关闭BUFF](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L16) | [自动御魂](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E5%BE%A1%E9%AD%82.json) | [八岐大蛇](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Orochi) |
| D01 | 每日礼包领取 | [功能518](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/518_%E9%A2%86%E5%8F%96%E6%97%A5%E5%B8%B8%E5%A5%96%E5%8A%B1.ts) | [日常奖励领取](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E6%97%A5%E5%B8%B8%E5%A5%96%E5%8A%B1%E9%A2%86%E5%8F%96.json) | [每日琐事](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/DailyTrifles) |
| D02 | 庭院事务一键领取 | [功能518](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/518_%E9%A2%86%E5%8F%96%E6%97%A5%E5%B8%B8%E5%A5%96%E5%8A%B1.ts) | [日常奖励领取](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E6%97%A5%E5%B8%B8%E5%A5%96%E5%8A%B1%E9%A2%86%E5%8F%96.json) | [登录与收获](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Restart) |
| D03 | 邮件领取 | [每日签到与收取邮件](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L171) | [日常奖励领取](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E6%97%A5%E5%B8%B8%E5%A5%96%E5%8A%B1%E9%A2%86%E5%8F%96.json) | [登录与收获](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Restart) |
| D04 | 每日签到 | [每日签到与收取邮件](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L171) | — | [登录与收获](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Restart) |
| D05 | 永久勾玉卡领取 | — | — | [登录与收获](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Restart) |
| D06 | 宠物投喂 | [喂猫喂狗](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L185) | [投喂宠物](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E6%8A%95%E5%96%82%E5%AE%A0%E7%89%A9.json) | [小猫咪](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Pets) |
| D07 | 花合战奖励领取 | [功能518](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/518_%E9%A2%86%E5%8F%96%E6%97%A5%E5%B8%B8%E5%A5%96%E5%8A%B1.ts) | [日常奖励领取](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E6%97%A5%E5%B8%B8%E5%A5%96%E5%8A%B1%E9%A2%86%E5%8F%96.json) | [花合战](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/TalismanPass) |
| D08 | 友情点与吉闻 | [功能518](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/518_%E9%A2%86%E5%8F%96%E6%97%A5%E5%B8%B8%E5%A5%96%E5%8A%B1.ts) | — | [每日琐事](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/DailyTrifles) |
| D09 | 式神委派 | — | [式神委派](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E5%BC%8F%E7%A5%9E%E5%A7%94%E6%B4%BE.json) | [式神委派](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Delegation) |
| D10 | 悬赏封印 | [悬赏](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L140) | [自动悬赏](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E6%82%AC%E8%B5%8F.json) | [悬赏封印](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/WantedQuests) |
| D11 | 每周资源领取与分享 | [每周资源领取](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L438) | [式神图鉴分享(仅限mumu模拟器可用)](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E5%BC%8F%E7%A5%9E%E5%9B%BE%E9%89%B4%E5%88%86%E4%BA%AB.json) | [每周琐事](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/WeeklyTrifles) |
| X01 | 常规商店周购 | [每周资源领取](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L438) | [自动购物](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E8%B4%AD%E7%89%A9.json) | [大富翁（商店合集）](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/RichMan) |
| X02 | 寮商店周购 | [每周资源领取](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L438) | [寮商店购物](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E5%AF%AE%E5%95%86%E5%BA%97%E8%B4%AD%E7%89%A9.json) | [大富翁（商店合集）](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/RichMan) |
| X03 | 神秘商人 | [周三神秘商人](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L416) | — | [神秘商店](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/MysteryShop) |
| X04 | 珍旅居兑换 | [每周资源领取](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L438) | [珍旅居商店购物](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E7%8F%8D%E6%97%85%E5%B1%85%E5%95%86%E5%BA%97%E8%B4%AD%E7%89%A9.json) | — |
| X05 | 契灵商店兑换 | [每周资源领取](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L438) | — | [大富翁（商店合集）](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/RichMan) |
| X06 | 神龛兑换 | [每周资源领取](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L438) | — | [大富翁（商店合集）](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/RichMan) |
| F01 | 御魂副本单人 | [个人御魂](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L106) | [自动御魂](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E5%BE%A1%E9%AD%82.json) | [八岐大蛇](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Orochi) |
| F02 | 御灵副本 | [功能6](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/006_%E5%BE%A1%E9%AD%82%E5%BE%A1%E7%81%B5%E6%8C%91%E6%88%98.ts) | [自动御灵](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E5%BE%A1%E7%81%B5.json) | [御灵之境](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/GoryouRealm) |
| F03 | 觉醒副本 | — | — | [觉醒副本](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/EvoZone) |
| F04 | 业原火 | — | [自动业原火](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E4%B8%9A%E5%8E%9F%E7%81%AB.json)（未导入） | [业原火](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Sougenbi) |
| F05 | 探索单人 | [个人探索](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L113)、[循环_探索单人](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L606) | [困28](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E5%9B%B028.json) | [探索](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Exploration) |
| F06 | 地域鬼王 | [地鬼日常](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L147) | [自动地鬼](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E5%9C%B0%E9%AC%BC.json) | [地域鬼王](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/AreaBoss) |
| F07 | 秘闻之境 | [秘闻前五层](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L297)、[秘闻后五层](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L320) | [自动每周秘闻](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E7%A7%98%E9%97%BB.json) | [秘闻之境](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Secret) |
| F08 | 逢魔之时 | [逢魔日常](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L159) | [自动逢魔](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E9%80%A2%E9%AD%94.json) | [封魔之时](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/DemonEncounter) |
| F09 | 日轮之城 | — | — | [日轮之城](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/FallenSun) |
| F10 | 永生之海 | [魂海_队员](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L449)、[魂海_队长](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L466) | — | [永生之海](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/EternitySea) |
| F11 | 御魂组队 | [组队司机](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L46)、[组队乘客](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L53)、[循环_魂十队长](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L527)、[循环_御魂队员](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L544) | [组队御魂-房主](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E7%BB%84%E9%98%9F%E5%BE%A1%E9%AD%82-%E6%88%BF%E4%B8%BB.json)、[组队御魂-队友](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E7%BB%84%E9%98%9F%E5%BE%A1%E9%AD%82-%E9%98%9F%E5%8F%8B.json) | [八岐大蛇](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Orochi) |
| F12 | 探索组队 | [循环_探索队长](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L566)、[循环_探索队员](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L583) | — | [探索](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Exploration) |
| F13 | 妖怪副本 | [金币妖怪](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L211)、[经验妖怪](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L196)、[妖气封印](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L120) | [组队副本](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E7%BB%84%E9%98%9F%E5%89%AF%E6%9C%AC.json) | [金币妖怪](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/GoldYoukai)、[经验妖怪](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/ExperienceYoukai) |
| F14 | 石距 | [师徒_徒弟石距](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L769) | — | [石距](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Tako) |
| F15 | 年兽 | — | — | [年兽](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Nian) |
| F16 | 真蛇 | [每周真蛇_队长](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L357)、[每周真蛇_队员](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L363) | [真蛇](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E7%9C%9F%E8%9B%87.json) | [真八岐大蛇](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/TrueOrochi) |
| F17 | 英杰试炼 | — | [英杰试炼](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E8%8B%B1%E6%9D%B0%E8%AF%95%E7%82%BC.json) | [英杰试炼](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/HeroTest) |
| F18 | 契灵之境 | [契灵_单人](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L375)、[契灵_队长](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L387)、[契灵_队员](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L402) | [契灵探查](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E5%A5%91%E7%81%B5%E6%8E%A2%E6%9F%A5.json) | [契灵之境](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/BondlingFairyland) |
| F19 | 六道之门 | [六道椒图](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L342) | — | [六道之门](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/SixRealms) |
| F20 | 彼世逢魔 | — | — | [彼世逢魔](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/OtherWorldTwilight) |
| F21 | 百鬼夜行随机撒豆 | [小功能合集](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L10) | — | [百鬼夜行](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Hyakkiyakou) |
| F22 | 百鬼棋局 | [百鬼棋局](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L427) | — | — |
| S01 | 御魂盘点 | — | — | — |
| S02 | 御魂奉纳整理 | [小功能合集](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L10) | — | [御魂整理](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/SoulsTidy) |
| S03 | 御魂强化 | [小功能合集](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L10) | — | — |
| K01 | 个人结界突破 | [个人突破_打9退4](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L60)、[突破打9退4](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L629) | [结界突破](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E7%BB%93%E7%95%8C%E7%AA%81%E7%A0%B4.json) | [个人突破](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/RealmRaid) |
| K02 | 结界与寮收取 | [功能700](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/700_%E8%BF%9B%E5%85%A5%E7%BB%93%E7%95%8C%E5%B9%B6%E9%A2%86%E5%8F%96%E9%A3%9F%E7%9B%92.ts) | [结界奖励领取](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E7%BB%93%E7%95%8C%E5%A5%96%E5%8A%B1%E9%A2%86%E5%8F%96.json) | [结界蹭卡](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/KekkaiUtilize) |
| K03 | 结界挂卡 | [结界卡](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L133) | [结界奖励领取](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E7%BB%93%E7%95%8C%E5%A5%96%E5%8A%B1%E9%A2%86%E5%8F%96.json) | [结界挂卡](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/KekkaiActivation) |
| K04 | 式神寄养 | [寄养](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L127) | [结界奖励领取](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E7%BB%93%E7%95%8C%E5%A5%96%E5%8A%B1%E9%A2%86%E5%8F%96.json) | [结界蹭卡](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/KekkaiUtilize) |
| K05 | 寮突破 | [寮突破](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L94) | [自动寮突破](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E5%AF%AE%E7%AA%81%E7%A0%B4.json) | [寮突破](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/RyouToppa) |
| K06 | 集体任务 | — | [寮三十捐材料](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E5%AF%AE%E4%B8%89%E5%8D%81%E6%8D%90%E6%9D%90%E6%96%99.json) | [集体任务](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/CollectiveMissions) |
| K07 | 寮周期首领 | [狩猎战](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L233)、[阴门挑战](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L265)、[寮活动启动器](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L227) | [自动麒麟](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E9%BA%92%E9%BA%9F.json)、[自动阴界之门](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E9%98%B4%E7%95%8C%E4%B9%8B%E9%97%A8.json) | [狩猎战](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Hunt) |
| K08 | 狭间暗域 | [狭间暗域](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L246) | — | [狭间暗域](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/AbyssShadows) |
| K09 | 首领退治 | [首领退治](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L259) | — | [首领退治](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/DemonRetreat) |
| K10 | 寮宴会 | [宴会](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L252) | — | [宴会](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/GuildBanquet) |
| E01 | 活动爬塔模板 | [通用活动](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L39) | [自动999](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/999.json)（未导入）、[活动爬塔-武道大会](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E6%B4%BB%E5%8A%A8-%E6%AD%A6%E9%81%93%E5%A4%A7%E4%BC%9A.json)（未导入）、[活动爬塔-云华依言歌-活动大富翁](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E6%B4%BB%E5%8A%A8%E5%A4%A7%E5%AF%8C%E7%BF%81.json)（未导入）、[活动爬塔-拾此一瞬](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E6%B4%BB%E5%8A%A8%E7%88%AC%E5%A1%94.json)（未导入）、[通用爬塔副本](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E9%80%9A%E7%94%A8%E7%88%AC%E5%A1%94%E5%89%AF%E6%9C%AC.json)、[限时活动](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E9%99%90%E6%97%B6%E6%B4%BB%E5%8A%A8.json)（未导入） | [当期式神爬塔](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/ActivityShikigami)、[武道大会](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/BudokaiTournament)、[KittyShop（限时活动）](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/KittyShop)、[DyeTrials（限时活动）](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/DyeTrials) |
| E02 | 超鬼王模板 | [清自己1-4星鬼王](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L520) | [召唤鬼王](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E5%8F%AC%E5%94%A4%E9%AC%BC%E7%8E%8B.json)、[超鬼王低星清票](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E8%B6%85%E9%AC%BC%E7%8E%8B.json)、[超鬼王简易版](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E8%B6%85%E9%AC%BC%E7%8E%8B%E5%A5%BD%E5%8F%8B%E5%88%86%E4%BA%AB.json)（未导入） | [超鬼王](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/MetaDemon) |
| E03 | 花车里程领取 | — | [花车里程领取](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E8%8A%B1%E8%BD%A6.json)（未导入） | [花车巡游](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/FloatParade) |
| E04 | 绘卷进度提醒 | [绘卷进度_检测并提醒](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L490) | — | [绘卷](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/MemoryScrolls) |
| E05 | 智力答题 | — | — | [智力答题](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Quiz) |

公共组件不计入方案数：通用战斗循环（准备、战斗等待、胜负结算、奖励弹窗）；弹窗库（升级提示、协作邀请、下载框等干扰项）；页面导航（庭院到各玩法入口）；阵容预设切换（按分组和队伍名切换预设）。assttyys 的“通用准备退出”归入通用战斗循环。

### 3.1 各项目做得好的设计

| 项目 | 设计 | 证据 |
| --- | --- | --- |
| assttyys | 每个方案带一份已知干扰弹窗清单，先清弹窗再推进主流程 | [能力调研 5.2](assttyys-capability-survey.md#52-可借鉴的思路只借思路不复制代码) |
| assttyys | 命中后隔一段时间重新截图再确认（防抖） | [能力调研第 2 节](assttyys-capability-survey.md#2-底层基础能力怎么实现) |
| MaaYYs | 用 `[JumpBack]` 处理打断：处理完弹窗回到原节点，全部流水线共 1001 处 | [通用流水线](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/resource_pack/base/pipeline/%E9%80%9A%E7%94%A8/%E9%80%9A%E7%94%A8.json) |
| MaaYYs | 庭院入口用神经网络检测，流水线另有 95 个特征匹配节点，减少对单一模板的依赖 | [导航节点](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/resource_pack/base/pipeline/%E9%80%9A%E7%94%A8/%E5%AF%BC%E8%88%AA.json#L168) |
| MaaYYs | 寄养时跨分页比较收益，超过阈值才直接选择 | [AutoFoster](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/agent/actions/auto_foster/auto_foster.go#L383-L421) |
| MaaYYs | 设置挑战次数前先读当前值，相同就不再输入 | [RepeatChallengeNTimes](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/agent/actions/repeat_challenge_n_times/repeat_challenge_n_times.go#L48-L62) |
| OAS | 任务按游戏倒计时安排下一次运行 | [蹭卡](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/KekkaiUtilize/script_task.py#L92-L96) |
| OAS | 18 套庭院皮肤有独立配置，启动时切换对应素材 | [Costume](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Component/Costume/config.py#L7-L27)、[check_costume](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/base_task.py#L58) |
| OAS | 异常后先诊断画面，御魂溢出时自动安排整理任务 | [PostDiagnotor](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Utils/post_diagnotor.py#L17-L25)、[入口](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/script.py#L528-L538) |
| OAS | 突破失败策略可配置：继续、刷新或退出 | [RealmRaid](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/RealmRaid/script_task.py#L214-L232) |
| OAS | 悬赏可配置排除的首领和层数上限 | [WantedQuests 配置](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/WantedQuests/config.py#L111-L113) |

### 3.2 共同暗病与我们的对策

| 暗病 | assttyys | MaaYYs | OAS | 我们的对策 |
| --- | --- | --- | --- | --- |
| 找不到就当完成 | [商店连续 10 次未找到即跳过](assttyys-capability-survey.md#4-暗病与历史包袱) | [答题占位](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/agent/actions/question_matcher/question_matcher.go#L83-L88) | [悬赏前置失败](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/WantedQuests/script_task.py#L65-L70) | “无法确认”是独立结果，不能算完成 |
| 子项失败仍记成功 | [找不到就关闭子项开关](assttyys-capability-survey.md#31-领取日常奖励518916-行49-个-operator) | — | [每日琐事](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/DailyTrifles/script_task.py#L42) | 每个子项单独核对，整体结果可以是部分完成 |
| 计次不等于业务结果 | [按命中次数计](assttyys-capability-survey.md#4-暗病与历史包袱) | [内存计数](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/agent/actions/task_counter/task_counter.go#L15) | — | 次数只按结算核对通过计算 |
| 未授权消费 | — | — | [50 勾玉买蓝票](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/DemonEncounter/script_task.py#L396-L399) | 消耗动作必须通过资源规则门 |
| 超时参数散落 | [硬编码重试和等待](assttyys-capability-survey.md#4-暗病与历史包袱) | [5 秒节点超时](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/resource_pack/base/pipeline/%E6%88%98%E6%96%97/%E9%98%B4%E9%97%B4%E4%B9%8B%E9%97%A8.json#L342) | [全局 60 秒兜底](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/module/device/device.py#L34-L36) | 等待和上限都写成显式参数，并保留记录 |
| 皮肤依赖 | [95 处写明只支持默认皮肤](assttyys-capability-survey.md#4-暗病与历史包袱) | [README 提示](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/README.md#L87) | [按皮肤维护素材](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Component/Costume/config.py#L7-L27) | 文字锚点优先，图像用多模板，并做留出主题验证 |

## 4. 可复用参考

### 4.1 许可证边界

| 项目 | 许可证 | 可以做 | 不能做 |
| --- | --- | --- | --- |
| OAS | [GPL-3.0](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/LICENSE) | 阅读后用自己的语言总结流程思路 | **不能复制或改写代码、配置、图片模板、OCR 规则和模型**，也不能逐段翻译成 Kotlin 或任务 JSON；否则我们的分发物可能要按 GPL 开源 |
| MaaYYs | [MIT](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/LICENSE) | 借鉴思路；确需引用代码时保留版权声明 | 不复制图片模板和模型：截图素材的权利不因 MIT 而归我们，模型来源也未逐个核实 |
| assttyys | MIT | 同上 | 比色数据来自游戏画面取样，不复制 |

本项目的结论：三个项目**都只借思路**，全部模板、阈值和流程由我们从自己的截图重新制作和验证。这是工程判断，不是法律意见。

### 4.2 按玩法整理

| 玩法 | 流程设计 | 判定思路 | 异常处理 | 调度策略 | 来源 |
| --- | --- | --- | --- | --- | --- |
| 归位与导航 | 页面图加失败边惩罚，走不通就换路 | 页面由多个条件组合识别 | 未知页面只走已知关闭规则，否则输出未知 | 作为所有任务的前置步骤 | [OAS 导航](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/GameUi/navigator.py#L775) |
| 每日领取 | 子项拆开，各自独立结果 | 领取后读回红点或已领状态 | “没看到奖励”不能当作已领（OAS 的 5 秒空闲退出是反例） | 每日刷新后运行一次 | [OAS 收获循环](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Restart/login.py#L203-L322) |
| 悬赏封印 | 先领宝箱，再逐项执行；列表滑动查找 | 用面板完成数判断 | 排除指定首领和高层数 | 分早晚两个时段安排 | [OAS](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/WantedQuests/script_task.py#L72-L134) |
| 个人突破 | 先读券数，再挑目标，三胜领奖后按配置刷新 | 券数差值加结算结果 | 失败策略做成配置，避免无限重打 | 券数达到下限才运行 | [OAS](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/RealmRaid/script_task.py#L142-L237)、[#128](https://github.com/TanyaShue/MaaYYs/issues/128) |
| 结界寄养 | 跨分页收集收益，排序后选最优 | 寄养状态和剩余时长读回 | 没有合适目标时稍后重试，不算失败 | 按剩余时长安排下一次 | [MaaYYs](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/agent/actions/auto_foster/auto_foster.go#L62)、[OAS](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/KekkaiUtilize/script_task.py#L72-L96) |
| 结界挂卡 | 设收益下限，低于下限不挂 | 挂卡状态读回 | 找不到卡记录原因 | 按卡片到期时间安排 | [OAS](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/KekkaiActivation/config.py#L24) |
| 御魂副本 | 次数和时间双上限 | 结算核对后才计次 | 御魂溢出时转去整理或停下提示 | 体力足够时运行 | [OAS](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Utils/post_diagnotor.py#L17-L25) |
| 寮活动 | 先判断星期和时段，再进入 | 活动结算读回 | 入口过渡时间要足够长 | 按开放时段入队 | [MaaYYs 时间窗](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/agent/actions/time_check/time_check.go#L24)、[#132](https://github.com/TanyaShue/MaaYYs/issues/132) |
| 商店 | 白名单加限购读回 | 购买前后比较货币和限购 | 结果不明不重试 | 周三、周六或每周一次 | [MaaYYs 周状态](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/agent/register.go#L79-L80) |
| 活动爬塔 | 入口和资源可配置，流程共用模板 | 活动进度或门票差值 | 活动结束单独结束 | 活动期内每日运行 | [MaaYYs](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E9%80%9A%E7%94%A8%E7%88%AC%E5%A1%94%E5%89%AF%E6%9C%AC.json) |
| 长期运行 | — | — | 区分卡死、未知页面和人工接管 | 可参考作息窗和每日活跃上限（是否采用见 Q10） | [OAS AntiBanGuard](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/module/config/anti_ban.py#L6-L54) |

## 5. 最终方案清单

**数法**：把三个项目的玩法按游戏目标合并；单人、队长、队员等差异放进配置；每期活动的实例不单列，归入活动模板。结果是 60 个方案。

| 分期 | 优先级 | 数量 | 目标 |
| --- | --- | --- | --- |
| 首发 | P0 | 10 | 打通启动、归位、领取和两个战斗闭环 |
| 第二批 | P1 | 20 | 覆盖高频日常、结界、主要副本和首批商店 |
| 后续 | P2 | 30 | 补齐周期玩法、组队、寮活动、御魂管理和活动模板 |

按类别：运行基础 3 个、日常与领取 11 个、商店兑换 6 个、副本与挑战 22 个、御魂管理 3 个、结界与阴阳寮 10 个、限时活动 5 个。

“依赖动作”一列：前半是已有动作，“缺”后面是第 7 节的缺口编号。“确认”一列指第 8 节的决策，有编号的方案在用户确认前不能上线。来源：A＝assttyys，M＝MaaYYs，O＝OAS，PRD＝产品要求。

### 5.1 首发（P0，10 个）

| 编号 | 名称 | 类别 | 目标 | 优先级 | 依赖动作 | 账号资格与风险 | 完成判定 | 来源 | 确认 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| B01 | 启动并进入庭院 | 运行基础 | 从桌面启动游戏，处理公告与登录页后停在庭院 | P0 | 启动应用、读文字、定位、点击、分支/循环；缺 G01 | 无资格要求；风险低 | 前台包名正确，且庭院锚点在新观察中成立；账号身份另行核对 | A M O | — |
| B02 | 归位庭院 | 运行基础 | 从已知界面逐级回到庭院，未知界面不盲点 | P0 | 读文字、定位、点击、分支/循环；缺 G01 | 无资格要求；风险低 | 庭院锚点成立；遇到未知界面输出结果未知 | A O | — |
| D01 | 每日礼包领取 | 日常与领取 | 领取商店免费每日礼包，付费项绝不点击 | P0 | 读文字、定位、点击、分支/循环；缺 G01 | 无资格要求；风险中（同屏有付费项） | 出现“获得奖励”并回到列表；付费状态不输入 | A M O | — |
| D02 | 庭院事务一键领取 | 日常与领取 | 领取庭院事务中的每日奖励 | P0 | 读文字、定位、点击、分支/循环；缺 G01 | 无资格要求；风险低 | 奖励弹窗出现后，入口红点或可领状态消失 | A M O | — |
| D03 | 邮件领取 | 日常与领取 | 全部领取邮件附件 | P0 | 读文字、定位、点击、分支/循环；缺 G01 | 无资格要求；风险低 | 邮件列表无可领标记；未读到列表时结果未知 | A M O | — |
| D04 | 每日签到 | 日常与领取 | 完成当日签到并领取签到奖励 | P0 | 读文字、定位、点击、分支/循环；缺 G01 | 无资格要求；风险低 | 签到状态读回为已签到 | A O | — |
| D05 | 永久勾玉卡领取 | 日常与领取 | 持卡账号领取当日勾玉产出 | P0 | 读文字、定位、点击、分支/循环；缺 G01 | 需持有永久勾玉卡；风险低 | 领取后读回已领状态；无卡判定为不适用，不算完成 | O PRD | Q4 |
| D06 | 宠物投喂 | 日常与领取 | 完成当日喂猫喂狗并领取奖励 | P0 | 读文字、定位、点击、分支/循环；缺 G01 | 需已解锁宠物小屋；风险低 | 投喂入口读回已完成状态 | A M O | — |
| F01 | 御魂副本单人 | 副本与挑战 | 按配置层数挑战八岐大蛇指定次数 | P0 | 读文字、定位、点击、分支/循环、读数；缺 G01、G02、G03 | 需解锁对应层；风险中（消耗体力） | 次数按结算核对通过计；体力不足单独结束 | A M O | Q5 |
| K01 | 个人结界突破 | 结界与阴阳寮 | 用完配置的突破券，失败按配置处理 | P0 | 读文字、定位、点击、分支/循环、读数、区域状态、选择目标、滑动、画面对比；缺 G01、G02、G03、G05、G06 | 需已解锁；风险中（异步对抗） | 券数差值与结算核对一致；到券下限后结束 | A M O | Q1 |

### 5.2 第二批（P1，20 个）

| 编号 | 名称 | 类别 | 目标 | 优先级 | 依赖动作 | 账号资格与风险 | 完成判定 | 来源 | 确认 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| B03 | 加成开关 | 运行基础 | 按配置开启或关闭御魂、觉醒、金币、经验加成 | P1 | 读文字、定位、点击、分支/循环；缺 G01 | 无资格要求；风险中（加成开启后持续计时） | 每个目标加成的开关状态由区域状态读回，与配置一致 | A M O | Q5 |
| D07 | 花合战奖励领取 | 日常与领取 | 领取花合战任务与等级奖励 | P1 | 读文字、定位、点击、分支/循环；缺 G01 | 无资格要求；风险低 | 可领标记消失；付费档奖励不点击 | A M O | — |
| D08 | 友情点与吉闻 | 日常与领取 | 收取友情点并处理好友吉闻 | P1 | 读文字、定位、点击、分支/循环；缺 G01 | 需有好友；风险低（向好友发送吉闻属社交动作） | 收取入口读回无可领状态 | A O | — |
| D09 | 式神委派 | 日常与领取 | 按配置领取委派奖励并派出新委派 | P1 | 读文字、定位、点击、分支/循环、读数、区域状态、选择目标、滑动、画面对比；缺 G01、G05、G06、G09 | 需已解锁委派；风险低 | 进行中委派数与倒计时读回一致 | M O | — |
| D10 | 悬赏封印 | 日常与领取 | 追踪并完成当日悬赏，领取宝箱 | P1 | 读文字、定位、点击、分支/循环、区域状态、选择目标、滑动、画面对比；缺 G01、G02、G04、G05、G06、G07 | 需已解锁探索；风险中（消耗体力） | 悬赏面板读回完成数等于总数，宝箱已领 | A M O | Q5 |
| X01 | 常规商店周购 | 商店兑换 | 按白名单购买友情、勋章、荣誉、魅力商店商品 | P1 | 读文字、定位、点击、分支/循环、读数、区域状态、选择目标、滑动、画面对比；缺 G01、G03、G04、G05、G06、G08 | 需满足商店解锁；风险高（消耗货币） | 购买前后货币与限购数都读回，差值与数量一致；不一致记结果未知 | A M O | Q2 |
| X02 | 寮商店周购 | 商店兑换 | 按白名单用功勋购买寮商店商品 | P1 | 读文字、定位、点击、分支/循环、读数、区域状态、选择目标、滑动、画面对比；缺 G01、G03、G04、G05、G06、G08 | 需满足商店解锁；风险高（消耗货币） | 购买前后货币与限购数都读回，差值与数量一致；不一致记结果未知 | A M O | Q2 |
| X03 | 神秘商人 | 商店兑换 | 在开放时段按白名单购买周三、周六商店商品 | P1 | 读文字、定位、点击、分支/循环、读数、区域状态、选择目标、滑动、画面对比；缺 G01、G03、G04、G05、G06、G07、G08 | 需在开放时段；风险高（可能出现勾玉定价） | 商品售罄状态与货币差值都读回；未开放记为等待 | A O | Q2 |
| F02 | 御灵副本 | 副本与挑战 | 在开放日挑战指定御灵 | P1 | 读文字、定位、点击、分支/循环、读数；缺 G01、G02、G03、G07 | 需已解锁；风险中（消耗御灵之钥） | 结算核对通过次数达到目标，或门票读回为零 | A M O | Q5 |
| F03 | 觉醒副本 | 副本与挑战 | 挑战指定觉醒麒麟 | P1 | 读文字、定位、点击、分支/循环、读数；缺 G01、G02、G03 | 需已解锁；风险中（消耗体力） | 结算核对通过次数达到目标 | O | Q5 |
| F04 | 业原火 | 副本与挑战 | 挑战业原火指定层数 | P1 | 读文字、定位、点击、分支/循环、读数；缺 G01、G02、G03 | 需已解锁；风险中（消耗业原火票） | 结算核对通过次数达到目标，或门票读回为零 | M O | Q5 |
| F05 | 探索单人 | 副本与挑战 | 清指定章节小怪与首领并领取宝箱 | P1 | 读文字、定位、点击、分支/循环、读数、区域状态、选择目标、滑动、画面对比；缺 G01、G02、G03、G05、G06、G11 | 需解锁章节；风险中（消耗体力） | 章节结算核对通过；滑屏到底仍未找到目标记未知，不算打完 | A M O | Q5 |
| F06 | 地域鬼王 | 副本与挑战 | 完成当日地域鬼王挑战 | P1 | 读文字、定位、点击、分支/循环、读数、区域状态、选择目标、滑动、画面对比；缺 G01、G02、G03、G04、G05、G06 | 需已解锁；风险低 | 当日挑战次数读回用尽或达到配置 | A M O | — |
| F07 | 秘闻之境 | 副本与挑战 | 挑战本周秘闻指定层数 | P1 | 读文字、定位、点击、分支/循环、读数；缺 G01、G02、G03、G04、G07 | 需已解锁；风险低 | 层数进度读回达到目标 | A M O | — |
| F08 | 逢魔之时 | 副本与挑战 | 在开放时段完成逢魔事件、答题与首领 | P1 | 读文字、定位、点击、分支/循环；缺 G01、G02、G04、G07、G12 | 需已解锁；风险低 | 事件进度读回完成；题目未命中题库时不作答并记未知 | A M O | — |
| K02 | 结界与寮收取 | 结界与阴阳寮 | 收取结界体力盒、经验盒与寮资金 | P1 | 读文字、定位、点击、分支/循环；缺 G01、G10 | 需加入阴阳寮；风险低 | 入口读回无可领状态 | A M O | Q6 |
| K03 | 结界挂卡 | 结界与阴阳寮 | 按收益下限挂上指定结界卡 | P1 | 读文字、定位、点击、分支/循环、读数、区域状态、选择目标、滑动、画面对比；缺 G01、G05、G06、G09、G10 | 需加入阴阳寮并持卡；风险中（消耗结界卡） | 挂卡状态与剩余时长读回 | A M O | Q6 |
| K04 | 式神寄养 | 结界与阴阳寮 | 比较可寄养结界收益并寄养 | P1 | 读文字、定位、点击、分支/循环、读数、区域状态、选择目标、滑动、画面对比；缺 G01、G05、G06、G09、G10、G11 | 需加入阴阳寮；风险低 | 寄养状态与剩余时长读回 | A M O | Q6 |
| K05 | 寮突破 | 结界与阴阳寮 | 在可用次数内挑战寮突破目标 | P1 | 读文字、定位、点击、分支/循环、读数、区域状态、选择目标、滑动、画面对比；缺 G01、G02、G03、G05、G06、G09 | 需加入阴阳寮；风险中（异步对抗） | 次数差值与结算核对一致；冷却中记为等待 | A M O | Q1 |
| E01 | 活动爬塔模板 | 限时活动 | 用可配置模板完成爬塔类活动 | P1 | 读文字、定位、点击、分支/循环、读数、区域状态、选择目标；缺 G01、G02、G03、G08 | 需在活动期；风险中（消耗活动票或体力） | 活动进度或门票差值读回；每期资源单独发布 | A M O | Q7 |

### 5.3 后续（P2，30 个）

| 编号 | 名称 | 类别 | 目标 | 优先级 | 依赖动作 | 账号资格与风险 | 完成判定 | 来源 | 确认 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| D11 | 每周资源领取与分享 | 日常与领取 | 领取每周免费资源，完成分享类周常 | P2 | 读文字、定位、点击、分支/循环；缺 G01、G04 | 部分需特定解锁；风险中（分享对外可见） | 各子项读回已领或已分享；未读到的子项单独记为未知 | A M O | Q9 |
| X04 | 珍旅居兑换 | 商店兑换 | 按白名单兑换珍旅居商品 | P2 | 读文字、定位、点击、分支/循环、读数、区域状态、选择目标、滑动、画面对比；缺 G01、G03、G04、G05、G06、G08 | 需已解锁；风险高（消耗货币） | 限购数与货币差值读回一致 | A M | Q2 |
| X05 | 契灵商店兑换 | 商店兑换 | 按白名单兑换契灵商店商品 | P2 | 读文字、定位、点击、分支/循环、读数、区域状态、选择目标、滑动、画面对比；缺 G01、G03、G04、G05、G06、G08 | 需已解锁契灵；风险高（消耗货币） | 限购数与货币差值读回一致 | A O | Q2 |
| X06 | 神龛兑换 | 商店兑换 | 按白名单兑换神龛商品 | P2 | 读文字、定位、点击、分支/循环、读数、区域状态、选择目标、滑动、画面对比；缺 G01、G03、G04、G05、G06、G08 | 需已解锁；风险高（消耗货币） | 限购数与货币差值读回一致 | A O | Q2 |
| F09 | 日轮之城 | 副本与挑战 | 挑战日轮之城指定层数 | P2 | 读文字、定位、点击、分支/循环、读数；缺 G01、G02、G03 | 需已解锁；风险中（消耗体力） | 结算核对通过次数达到目标 | O | Q5 |
| F10 | 永生之海 | 副本与挑战 | 以单人或队伍身份挑战永生之海 | P2 | 读文字、定位、点击、分支/循环、读数；缺 G01、G02、G03 | 需已解锁；风险中（消耗体力） | 结算核对通过次数达到目标 | A O | Q5 |
| F11 | 御魂组队 | 副本与挑战 | 以队长或队员身份循环组队御魂 | P2 | 读文字、定位、点击、分支/循环、读数；缺 G01、G02、G03 | 需固定队友；风险中（影响队友） | 结算核对通过次数达到目标；队伍解散单独结束 | A M O | Q8 |
| F12 | 探索组队 | 副本与挑战 | 以队长或队员身份循环组队探索 | P2 | 读文字、定位、点击、分支/循环、读数、区域状态、选择目标、滑动、画面对比；缺 G01、G02、G03、G05、G06 | 需固定队友；风险中（影响队友） | 章节结算核对通过；队友离开单独结束 | A O | Q8 |
| F13 | 妖怪副本 | 副本与挑战 | 挑战金币妖怪、经验妖怪或妖气封印 | P2 | 读文字、定位、点击、分支/循环、读数；缺 G01、G02、G03、G04 | 需已解锁；风险低 | 当日次数读回用尽或达到配置 | A M O | — |
| F14 | 石距 | 副本与挑战 | 挑战石距指定次数 | P2 | 读文字、定位、点击、分支/循环、读数；缺 G01、G02、G03、G04 | 需已解锁；风险低 | 结算核对通过次数达到目标 | A O | — |
| F15 | 年兽 | 副本与挑战 | 在开放期挑战年兽 | P2 | 读文字、定位、点击、分支/循环；缺 G01、G02、G04、G07 | 需在开放期；风险低 | 当日次数读回用尽；冷却中记为等待 | O | — |
| F16 | 真蛇 | 副本与挑战 | 完成每周真蛇挑战 | P2 | 读文字、定位、点击、分支/循环；缺 G01、G02、G04、G07 | 需高练度阵容；风险中 | 周次数读回用尽或结算核对通过 | A M O | — |
| F17 | 英杰试炼 | 副本与挑战 | 挑战指定英杰与层数 | P2 | 读文字、定位、点击、分支/循环、读数；缺 G01、G02、G03 | 需已解锁；风险低 | 层数进度读回达到目标 | M O | — |
| F18 | 契灵之境 | 副本与挑战 | 探查并结契指定契灵 | P2 | 读文字、定位、点击、分支/循环、读数、区域状态、选择目标、滑动、画面对比；缺 G01、G02、G03、G05、G06、G08 | 需已解锁；风险高（消耗契灵盘） | 结契结果与盘数差值读回一致 | A M O | Q5 |
| F19 | 六道之门 | 副本与挑战 | 完成六道之门一轮选择与战斗 | P2 | 读文字、定位、点击、分支/循环、读数、区域状态、选择目标、滑动、画面对比；缺 G01、G02、G03、G05、G06 | 需已解锁；风险中（选择不可逆） | 通关结算读回；未知选项停止并记未知 | A O | — |
| F20 | 彼世逢魔 | 副本与挑战 | 在开放时段挑战彼世逢魔 | P2 | 读文字、定位、点击、分支/循环、读数；缺 G01、G02、G03、G07 | 需已解锁；风险低 | 结算核对通过次数达到目标 | O | — |
| F21 | 百鬼夜行随机撒豆 | 副本与挑战 | 不用模型完成百鬼夜行随机撒豆 | P2 | 读文字、定位、点击、分支/循环、读数；缺 G01、G02、G03、G04、G13 | 需百鬼券；风险中（消耗门票） | 门票差值与结算界面读回一致 | A O | Q5 |
| F22 | 百鬼棋局 | 副本与挑战 | 按预设阵容完成百鬼棋局挂机 | P2 | 读文字、定位、点击、分支/循环；缺 G01、G02、G04、G07 | 需在开放期；风险低 | 周进度读回达到目标 | A | — |
| S01 | 御魂盘点 | 御魂管理 | 只读统计御魂数量与溢出风险，不做任何处置 | P2 | 读文字、定位、点击、分支/循环、区域状态、选择目标、滑动、画面对比；缺 G01、G05、G06 | 无资格要求；风险低 | 读到的总数与上限一致并写入结果 | PRD | — |
| S02 | 御魂奉纳整理 | 御魂管理 | 按用户确认的规则奉纳已弃置御魂 | P2 | 读文字、定位、点击、分支/循环、区域状态、选择目标、滑动、画面对比；缺 G01、G05、G06、G08 | 风险高（不可逆） | 处置前列出命中项，处置后数量差值一致 | A O | Q3 |
| S03 | 御魂强化 | 御魂管理 | 按用户确认的对象与预算强化御魂 | P2 | 读文字、定位、点击、分支/循环、读数、区域状态、选择目标、滑动、画面对比；缺 G01、G03、G05、G06、G08 | 风险高（不可逆） | 强化后等级、素材差值逐项读回；结果不明时停止 | A | Q3 |
| K06 | 集体任务 | 结界与阴阳寮 | 按白名单提交材料并领取奖励 | P2 | 读文字、定位、点击、分支/循环、区域状态、选择目标、滑动、画面对比；缺 G01、G04、G05、G06、G08 | 需加入阴阳寮；风险高（消耗材料） | 提交数、材料差值与奖励状态读回一致 | M O | Q2 |
| K07 | 寮周期首领 | 结界与阴阳寮 | 按星期进入麒麟或阴界之门 | P2 | 读文字、定位、点击、分支/循环；缺 G01、G02、G04、G07 | 需加入阴阳寮；风险中（影响寮成员） | 活动结算读回；未开放记为等待 | A M O | — |
| K08 | 狭间暗域 | 结界与阴阳寮 | 在开放时段挑战狭间暗域 | P2 | 读文字、定位、点击、分支/循环、区域状态、选择目标、滑动、画面对比；缺 G01、G02、G04、G05、G06、G07 | 需加入阴阳寮；风险中（影响寮成员） | 挑战次数读回用尽或活动结束 | A O | — |
| K09 | 首领退治 | 结界与阴阳寮 | 在开放日完成首领退治 | P2 | 读文字、定位、点击、分支/循环；缺 G01、G02、G04、G07 | 需加入阴阳寮；风险低 | 结算读回完成 | A O | — |
| K10 | 寮宴会 | 结界与阴阳寮 | 在开放时段参加寮宴会 | P2 | 读文字、定位、点击、分支/循环；缺 G01、G02、G04、G07 | 需加入阴阳寮；风险低 | 宴会结束状态读回 | A O | — |
| E02 | 超鬼王模板 | 限时活动 | 按配置召唤或挑战超鬼王 | P2 | 读文字、定位、点击、分支/循环、读数、区域状态、选择目标、滑动、画面对比；缺 G01、G02、G03、G05、G06、G08 | 需在活动期；风险高（消耗活动票） | 鬼王状态与门票差值读回 | A M O | Q7 |
| E03 | 花车里程领取 | 限时活动 | 领取花车巡游里程奖励 | P2 | 读文字、定位、点击、分支/循环；缺 G01、G04 | 需在活动期；风险低 | 可领标记消失 | M O | — |
| E04 | 绘卷进度提醒 | 限时活动 | 只读绘卷进度并发送本机提醒 | P2 | 读文字、定位、点击、分支/循环、读数；缺 G01、G03 | 需在活动期；风险低 | 读到进度数值并投递提醒；不投分 | A O | — |
| E05 | 智力答题 | 限时活动 | 用版本化题库完成活动答题 | P2 | 读文字、定位、点击、分支/循环、读数；缺 G01、G03、G04、G12 | 需在活动期；风险低 | 作答结果读回；题库未命中不作答 | O | — |

补充说明：D05 的主要依据是 [PRD F04](../PRD.md)。OAS 登录收获里有[勾玉入口](https://github.com/nearaura/OnmyojiAutoScript/blob/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Restart/login.py#L263-L266)，但是否就是永久勾玉卡未核实。S01 没有现成实现，是为 Q3 决策提供只读依据而新增的方案。

### 5.3.1 用户追加（2026-11-18，3 个）

用户提出的候选，加入后最终清单为 **63 个方案**。游戏规则尚未实测，实现前都要先在真机核对入口和时间。

| 编号 | 名称 | 类别 | 目标 | 优先级 | 账号资格与风险 | 完成判定 | 确认 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| B04 | 周三维护更新处理 | 运行基础 | 每周三 9 点维护前后暂停长时任务，维护结束后处理更新提示并回到庭院 | P1 | 风险中（更新下载占用流量和时间；提示内容每次可能不同） | 进入庭院且庭院锚点成立；未见过的提示停止并通知用户 | 维护时段和更新确认方式待实测 |
| D12 | 每日免费抽卡 | 日常与领取 | 只使用每日免费次数抽一次 | P1 | 风险高（同屏有消耗召唤券和勾玉的入口） | 读到免费标识才点击；结果页出现后退出；没有免费标识时直接结束，绝不消耗召唤资源 | 免费入口位置待实测 |
| D13 | 每周分享得蓝票 | 日常与领取 | 完成每周分享并领取蓝票奖励 | P2 | 风险中（分享对外可见） | 奖励领取状态读回已领；分享渠道由任务配置，不向用户未选择的渠道发送 | Q9：用户已提出要做，分享渠道待定 |

D12 是第 6 节 N12“抽卡与普通召唤”的唯一例外：只做免费次数，其余抽卡仍然不做。

### 5.3.2 被动触发（2026-11-18，2 个）

用户提出要支持被动任务。机制和待确认项见[被动触发机制提案](../design/passive-triggers.md)。加入后最终清单为 **65 个方案**。

| 编号 | 名称 | 类别 | 目标 | 优先级 | 账号资格与风险 | 完成判定 | 确认 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| R01 | 协作邀请处理 | 日常与领取 | 只接受用户勾选的悬赏类协作（默认都不选），其余忽略；接受后追加悬赏任务 | P1 | 风险中（误接会打断当前任务） | 弹窗消失且决定写入运行记录；无法判断类型时不点击 | 已确认 |
| R02 | 组队邀请跟车 | 副本与挑战 | 在同一个协作多选项里按玩法勾选组队类型，按下级配置接受选定好友的邀请并完成下本，队长退出或到达上限后离队 | P2 | 风险高（消耗体力，影响队友） | 次数按结算核对；到达上限后已离队 | 已确认 |

### 5.4 分期前置条件

- **首发**需要先补：G01 跨皮肤识别（10 个方案）、G02 全局弹窗守卫（2 个方案）、G03 数值比较分支（2 个方案）、G05 文字宽松匹配（1 个方案）、G06 候选枚举与遍历（1 个方案）。
- **第二批**另需：G04 周期账本（7 个方案）、G07 时间窗判断（5 个方案）、G09 时长读取（4 个方案）、G08 资源规则门（4 个方案）、G10 动态下次运行（3 个方案）、G11 拖拽到动态目标（2 个方案）、G12 题库匹配（1 个方案）；商店类还需要决策 Q2。
- **后续**另需其余缺口；御魂管理必须等 Q3 确认。

### 5.5 公共组件

下列能力被多个方案共用，以任务包内的公共步骤交付，不单独计入方案数：

- **通用战斗循环**：准备、战斗等待、胜负结算、奖励弹窗。
- **弹窗库**：升级提示、协作邀请、下载框等干扰项。
- **页面导航**：庭院到各玩法入口。
- **阵容预设切换**：按分组和队伍名切换预设。

## 6. 不做的玩法

下表玩法的优先级都是“暂不做”，不计入 60 个方案。

| 编号 | 玩法 | 原因 | 类别 | assttyys | MaaYYs | OAS |
| --- | --- | --- | --- | --- | --- | --- |
| N01 | 斗技 | 实时 PVP，直接影响其他玩家 | PVP | [斗技](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L291) | [自动斗技](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E6%96%97%E6%8A%80.json) | [斗技](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Duel) |
| N02 | 道馆 | 寮间对抗，失误影响全寮，还需要指挥决策 | PVP | [道馆](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L239)、[僵尸寮自动攻打道馆](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L271) | [自动道馆突破](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E9%81%93%E9%A6%86%E7%AA%81%E7%A0%B4.json) | [道馆](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Dokan) |
| N03 | 打9退4 与主动降级 | 故意退出以操纵匹配等级，属于对其他玩家有影响的行为；[MaaYYs #100](https://github.com/TanyaShue/MaaYYs/issues/100) 还报告过循环重打 | PVP | [个人突破_降级](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L79)、[个人突破_打9退4](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L60)、[突破打9退4](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L629) | [结界突破](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E7%BB%93%E7%95%8C%E7%AA%81%E7%A0%B4.json) | [个人突破](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/RealmRaid) |
| N04 | 对弈竞猜 | 下注玩法；MaaYYs 还依赖第三方预测接口和公共 MQTT | 博彩与外部依赖 | [对弈竞猜](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L509) | [对弈竞猜](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E7%AB%9E%E7%8C%9C.json)（未导入） | [对弈竞猜](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/FrogBoss) |
| N05 | 师徒、小号与协战 | 批量操作多账号，账号风险高，也超出单账号产品范围 | 多账号 | [师徒_师傅战斗](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L690)、[师徒_徒弟登录](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L701)、[师徒_徒弟升级](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L719)、[师徒_徒弟领体力](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L733)、[师徒_徒弟金币](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L746)、[师徒_徒弟经验](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L758)、[师徒_徒弟协战](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L780)、[师徒_徒弟守护](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L797)、[僵尸寮日常任务](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L656)、[协战十五](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L675) | — | [FindJade（小号协作）](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/FindJade) |
| N06 | 购买体力、用勾玉买票 | 消耗付费货币；PRD 要求保存计划不隐含消费授权 | 付费货币 | [功能319](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/319_%E7%BC%98%E7%BB%93%E8%B6%A3%E6%B8%B8.ts) | — | [每日琐事](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/DailyTrifles)、[封魔之时](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/DemonEncounter) |
| N07 | 肝绘卷循环与绘卷投分 | 长时间连续消耗，MaaYYs 自己标注“有风险”；投分不可逆 | 高风险 | — | [肝绘卷](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E8%82%9D%E7%BB%98%E5%8D%B7.json) | [绘卷](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/MemoryScrolls) |
| N08 | 百鬼夜行 AI 撒豆 | 需要训练并分发目标检测模型，超出当前动作与资源范围 | 模型依赖 | — | — | [百鬼夜行](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/Hyakkiyakou) |
| N09 | 网易大神签到 | 属于另一个 App，多目标切换规则尚未确定，还涉及社区账号 | 跨应用 | [功能518](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/518_%E9%A2%86%E5%8F%96%E6%97%A5%E5%B8%B8%E5%A5%96%E5%8A%B1.ts) | [网易大神签到](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E7%BD%91%E6%98%93%E5%A4%A7%E7%A5%9E%E7%AD%BE%E5%88%B0.json) | [大神签到](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/AutoCheckinBigGod) |
| N10 | 关闭游戏、重启模拟器 | 普通 Android 权限无法结束其他应用；Root 路线尚无证据 | 平台限制 | [功能991](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/991_%E6%A8%A1%E6%8B%9F%E5%99%A8%E9%87%8D%E5%90%AF.ts) | [关闭游戏](https://github.com/TanyaShue/MaaYYs/blob/5df99bf5588919d9d1e8514fed5934da5a63f70c/tasks/%E5%85%B3%E9%97%AD%E6%B8%B8%E6%88%8F.json) | — |
| N11 | 寮管理与寮活动监控 | 需要管理权限，或依赖后台读取通知 | 权限 | [功能522](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/522_%E6%8B%9B%E5%8B%9F.ts) | — | [寮活动监控](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/GuildActivityMonitor) |
| N12 | 抽卡与普通召唤 | 消耗召唤资源，结果随机 | 资源消耗 | [功能22](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/022_%E6%8A%BD%E5%8E%95%E7%BA%B8%28%E6%99%AE%E9%80%9A%E5%8F%AC%E5%94%A4%29.ts) | — | [每日琐事](https://github.com/nearaura/OnmyojiAutoScript/tree/ac712accdc30397ac45104f3ef16b721500d8fef/tasks/DailyTrifles) |
| N13 | 式神育成、暴食鬼、结界卡合成 | 消耗养成资源，且不可逆 | 资源消耗 | [功能28](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/028_%E7%BB%A7%E7%BB%AD%E8%82%B2%E6%88%90.ts)、[功能31](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/031_%E7%BB%93%E7%95%8C%E5%8D%A1_%E7%BB%A7%E7%BB%AD%E5%90%88%E6%88%90.ts)、[功能310](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/310_%E6%9A%B4%E9%A3%9F%E9%AC%BC.ts) | — | — |
| N14 | 已下线或罕见活动实例 | 不单列；后续同类活动走活动模板 | 时效 | [夜行荒河](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L497)、[伊吹之擂](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L503) | — | — |
| N15 | 行为仿真类功能 | 目的是规避检测，不是完成游戏目标 | 风控规避 | [功能33](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/033_%E8%A1%8C%E4%B8%BA%E4%BB%BF%E7%9C%9F.ts) | — | — |

另外，“依靠云端模型自主理解新活动”属于 [PRD F11/F14](../PRD.md) 的独立能力，不计入这 60 个方案，全部方案都必须在关闭云端模型时可用。

## 7. 动作缺口

**数法**：每个方案按玩法特征标注需要的能力，再由脚本统计。“阻塞”表示没有这项能力时，方案无法达到第 5 节的完成判定。已有动作依据 [region-actions.md](../development/region-actions.md)、[step-program.md](../development/step-program.md)。

### 7.1 基础动作（按阻塞方案数排序）

| 编号 | 缺口 | 现状与需求 | 建议形态 | 阻塞方案数 | 其中 P0 | 其中 P1 |
| --- | --- | --- | --- | --- | --- | --- |
| G01 | 跨皮肤识别 | 多模板任一匹配、掩码模板、特征匹配 | `screen.matchAny（暂名）` | 60 | 10 | 20 |
| G02 | 全局弹窗守卫 | 任务运行期间统一处理协作邀请、升级提示等打断 | `步骤级中断规则` | 31 | 2 | 10 |
| G03 | 数值比较分支 | 大于、小于、区间，以及用读数决定循环次数；当前 branch 只支持相等 | `branch 比较运算` | 31 | 2 | 11 |
| G04 | 周期账本 | 跨运行记录本日、本周完成状态和次数 | `ledger.read / ledger.write` | 24 | 0 | 7 |
| G05 | 文字宽松匹配 | 包含、多候选、正则、繁简归一；当前只支持完全相同 | `matchMode 参数` | 23 | 1 | 10 |
| G06 | 候选枚举与遍历 | 列出全部候选，逐行读取后按规则挑选 | `screen.listTargets + forEach` | 23 | 1 | 10 |
| G07 | 时间窗判断 | 按星期、时段、游戏刷新时刻判断是否开放 | `clock.inWindow` | 13 | 0 | 5 |
| G08 | 资源规则门 | 消耗前核对白名单、预算和用户授权 | `resource.gate` | 12 | 0 | 4 |
| G09 | 时长读取 | 解析 12:34:56 这类倒计时 | `screen.readDuration` | 4 | 0 | 4 |
| G10 | 动态下次运行 | 任务结束时按游戏倒计时安排下一次；当前计划只有每日和指定星期 | `schedule.requestNextRun` | 3 | 0 | 3 |
| G11 | 拖拽到动态目标 | 从一个目标拖到另一个目标 | `screen.dragTo` | 2 | 0 | 2 |
| G12 | 题库匹配 | 用版本化题库匹配 OCR 题目 | `data.lookupAnswer` | 2 | 0 | 1 |
| G13 | 高频连点 | 低延迟连续点击 | `screen.tapBurst` | 1 | 0 | 0 |

`screen.inputText` 这次不列为缺口：清单内没有方案必须输入文字（多账号登录已列入不做）。

### 7.2 平台能力

| 能力 | 现状 | 影响范围 |
| --- | --- | --- |
| 账号身份核对 | PRD 要求核对后才放行自动任务，尚无实际游戏证据 | 全部 60 个 |
| 公共步骤复用 | 现有文档未见跨任务共享步骤的机制，弹窗处理和归位只能复制到每个任务 | 全部 60 个（可绕过，但维护成本高） |
| 游戏卡死恢复 | 普通权限不能结束其他应用，目前只能报告无法确认 | 全部 60 个，长循环受影响最大 |
| 执行证据持久化 | 动作 trace 目前没有持久化 | 全部 60 个的问题排查 |
| 模板匹配运行证据 | Apple Silicon 模拟器上图像请求被 CPU 保护拒绝，只能在真机验证 | 所有用图像的方案 |
| Windows 模拟器验证 | 尚无证据 | PRD 目标环境之一 |

## 8. 需用户确认的决策

### 8.1 用户已确认（2026-11-18）

| 编号 | 决定 | 落实要求 |
| --- | --- | --- |
| Q1 | 个人突破（K01）和寮突破（K05）都做 | 按原分期保留，不减少方案 |
| Q2 | 花费上限放在对应任务里配置，并提供推荐默认值 | 涉及花费的任务都要有上限配置项；推荐值在实现各任务时结合实测确定 |
| Q3 | 御魂强化和处置规则放在任务里配置 | 强化对象、目标等级、预算、处置范围都作为任务配置项 |
| Q5 | 体力和加成放在任务里配置 | 体力或门票消耗、是否使用加成都作为任务配置项 |

### 8.2 仍待确认

Q4、Q6、Q7、Q9、Q10 尚未决定，本文不设默认值。Q1、Q2、Q3、Q5 的原始问题保留在下表供追溯，以 8.1 为准。

| 编号 | 决策 | 影响方案 | 需要回答的问题 |
| --- | --- | --- | --- |
| Q1 | 异步对抗的边界 | K01、K05 | 个人突破和寮突破对手不在线，本文暂按可做列入。是否接受？不接受时首发减少 1 个、第二批减少 1 个 |
| Q2 | 商店与材料消耗规则 | X01、X02、X03、X04、X05、X06、K06 | 允许使用哪些货币？是否允许勾玉或魂玉？商品白名单、数量和预算由谁设定？ |
| Q3 | 御魂强化与处置规则 | S02、S03 | 强化哪些对象、到几级、预算多少？哪些御魂可以奉纳？能否无人值守，还是每次预览确认？ |
| Q4 | 永久勾玉卡资格判断 | D05 | 未持卡时，结果显示“不适用”还是隐藏该任务？ |
| Q5 | 体力、门票与加成消耗 | B03、D10、F01、F02、F03、F04、F05、F09、F10、F18、F21 | 是否要求用户先设次数或消耗上限才允许运行？加成是否允许自动开启？ |
| Q6 | 结界间隔调度 | K02、K03、K04 | PRD 的计划只有每日和指定星期。是否扩展为“按游戏倒计时安排下一次”？ |
| Q7 | 活动门票 | E01、E02 | 活动票是否只用免费获得的部分？是否禁止购买门票？ |
| Q8 | 组队协作 | F11、F12 | 已确认支持组队邀请，只接受用户选定的好友，见[被动触发机制](../design/passive-triggers.md)第 6 节 |
| Q9 | 对外分享 | D11 | 是否允许自动执行分享类周常？ |
| Q10 | 作息窗 | 全部长时运行方案 | 是否提供休息时段和每日运行上限？ |

## 9. 方法与局限

- **源码**：用 `git clone --depth 1` 克隆 MaaYYs（`5df99bf5588919d9d1e8514fed5934da5a63f70c`）和 OAS（`ac712accdc30397ac45104f3ef16b721500d8fef`）到独立临时目录。完整克隆太慢，改用 `--filter=blob:limit=300k` 加稀疏检出，**跳过了图片、模型等二进制文件**，所以没有检查模板内容。提交哈希与 GitHub API 返回的分支最新提交一致。
- **安全**：只读源码，没有安装依赖，没有运行其中任何脚本或构建，也没有连接游戏。统计脚本放在克隆目录之外，用 Python `-I` 运行。
- **issue**：通过 GitHub API 读取全部 issue 列表，只细读了本文引用的部分。OAS 原仓库不可访问，历史 issue 缺失；两个项目的高频问题可能主要在 QQ 群等渠道，issue 不能代表全貌。
- **计数**：MaaYYs 和 OAS 的数量由脚本解析得到；覆盖矩阵的归属是人工判断，由脚本核对没有遗漏条目。
- **游戏规则未实测**：开放时段、资格、消耗类型来自源码和名称，当前版本可能已变化，实现每个方案前要在实际游戏里重新确认。
- **风险等级和优先级**是本报告的工程判断，需要用户评审。源码里写着的行为（例如 50 勾玉购买）没有运行验证实际扣费。
- **缺口统计**按方案特征标注，实现时可能发现新的缺口，也可能用现有动作组合绕过部分缺口。
