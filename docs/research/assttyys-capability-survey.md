# assttyys_autojs 能力调研：方案、功能、底层能力与暗病

调研日期：2026-11-18。只读源码与 GitHub issue／提交记录，没有安装依赖、没有运行该项目的任何脚本或构建，也没有连接游戏。证据标注：**【源码】** 在固定提交中读到；**【issue】** 来自 GitHub issue 正文或评论；**【提交】** 来自提交说明；**【推测】** 由源码推断但未运行验证。

## 摘要

- 依据提交 `7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc`（2026-11-12，今天仍是 master HEAD，与 `step-control-flow-proposal.md` 引用的是同一提交，所以无需对比）。
- **方案：内置 66 个**，分 9 组，其中 28 个标记常用（`star`）。数自 `src/common/schemeList.ts` 中未注释的 `schemeName`。
- **功能：生效 115 个**，每个 `src/common/funcList/*.ts` 文件一个功能，id 不重复；另有 87 个停用文件放在 `funcList.bak/`。115 个中有 32 个没有被任何内置方案引用。
- 内置方案引用了 5 个不存在的功能 id（10、24、220、504、521），涉及 18 个方案。加载时这些 id 被静默跳过，不报错。
- 底层只有三件事：**多点比色／多点找色**（第三方 ScriptLib 的锚点算法，以 1280×720 为基准缩放）、**OCR**（ML Kit／云犀插件，29 处调用）、**点击／滑动**。没有找图：工程配置 `removeOpenCv: true`，YOLO 只在停用代码里用过。
- 执行模型是“每轮截一次图，按方案顺序找第一个比色成功的功能去点”。点击后不核对效果；计次的依据是“7.5 秒内同一功能只算一次”。
- 最重要的暗病：找不到就当作已完成或跳过；点击后不核对；按命中次数计数；颜色阈值全局共享，只支持默认皮肤（源码 95 处写明）；方案切换用子串匹配，失败时静默；调度有硬编码的空窗，免打扰模式把跳过当成完成。
- issue 很少（36 个，3 个开放，主要讨论在 QQ 群），但 2026-03 以来 237 个非合并提交中有 49 个是重新取色、适配或修识别，说明维护成本集中在“游戏一更新就重新取色”。
- 建议优先做：归位与通用弹窗、每日领取、个人结界突破、御魂／御灵单人计次、悬赏封印。我们最缺的基础动作：多目标定位、颜色／亮度状态、数字读取、带超时的条件等待、滚动查找、画面是否变化、应用启停与前台检测、通知。

## 1. 方案与功能清单

### 1.1 怎么数的

- **提交**：浅克隆 master，HEAD 为 `7d26c420`，GitHub API 的 commits 列表首项也是它。
- **方案**：[`schemeList.ts`][schemeList] 第 6–824 行的数组。数法是：以 `\t\tschemeName:` 开头、没被 `//` 注释的行，每行一个方案，共 66 个（文件末尾另有一个注释掉的示例，不计）。一开始按 `{\n\t\tid:` 切分只数出 63 个，原因是有 3 处写成了 `}, {` 同行，后来改为按 `schemeName` 行回溯。
- 方案的 `id` 字段大量重复（如 `id: 79` 出现 10 次，`111` 出现 6 次），而合并逻辑让方案自带的 `id` 覆盖默认值（[schemeList.ts L849–866][schemeList-merge]）。实际起标识作用的是方案名：持久化、切换、合并都按名称（[tool.ts setCurrentScheme][tool-set]、[toolAuto.ts mergeSchemeList][mergeSchemeList]），所以按名称计数。
- **功能**：[`funcListIndex.ts`][funcListIndex] 用 `require.context('./funcList', false, /\.[jt]s$/)` 加载该目录（不递归）。目录下 115 个文件，每个导出一个带唯一 `id` 的功能。`funcList.bak/` 不被加载。
- “功能”是方案的组成单位：方案 = 功能 id 的有序列表 + 每个功能的参数。没有单独的“动作”层；功能内部的 `operator[]`（比色描述 + 点击区域）就是最小步骤。另有两张共享特征表：[`multiFindColors.ts`][mfc] 140 个多点找色键、[`multiDetectColors.ts`][mdc] 68 个多点比色键，按名称引用。

### 1.2 方案清单（66 个）

“常用”即源码里的 `star: true`。功能 id 列表照抄源码，其中 **10／24／220／504／521 在生效目录里不存在**。

| # | 方案 | 分组 | 常用 | 功能 id 列表（按优先级） |
| --- | --- | --- | --- | --- |
| 1 | [小功能合集](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L10) | 未分组 | 是 | 0, 1, 2, 3, 17, 22, 28, 31, 302, 309, 310, 319, 24 |
| 2 | [关闭BUFF](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L16) | 未分组 |  | 0, 1, 2, 3, 501, 29, 40, 503 |
| 3 | [返回庭院](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L21) | 未分组 |  | 0, 1, 2, 3, 24, 503 |
| 4 | [通用准备退出](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L32) | 未分组 | 是 | 0, 1, 2, 3, 24 |
| 5 | [通用活动](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L39) | 战斗 | 是 | 0, 1, 2, 3, 24, 312 |
| 6 | [组队司机](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L46) | 战斗 | 是 | 0, 1, 2, 3, 5 |
| 7 | [组队乘客](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L53) | 战斗 | 是 | 0, 1, 2, 3, 4 |
| 8 | [个人突破_打9退4](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L60) | 战斗 | 是 | 690, 509, 510, 51, 0, 1, 2, 3, 7, 8, 9, 29, 503 |
| 9 | [个人突破_降级](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L79) | 战斗 |  | 0, 1, 2, 3, 8, 9, 11 |
| 10 | [寮突破](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L94) | 战斗 | 是 | 0, 1, 2, 3, 8, 9, 12, 29 |
| 11 | [个人御魂](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L106) | 战斗 |  | 0, 1, 2, 3, 6 |
| 12 | [个人探索](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L113) | 战斗 | 是 | 0, 1, 2, 3, 14, 29 |
| 13 | [妖气封印](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L120) | 战斗 |  | 0, 1, 2, 3, 5, 27 |
| 14 | [寄养](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L127) | 日常 | 是 | 690, 0, 1, 2, 3, 700, 702, 503 |
| 15 | [结界卡](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L133) | 日常 | 是 | 690, 0, 1, 2, 3, 700, 701, 503 |
| 16 | [悬赏](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L140) | 日常 | 是 | 690, 509, 510, 1, 2, 3, 18, 29, 503 |
| 17 | [地鬼日常](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L147) | 日常 | 是 | 690, 509, 510, 0, 1, 2, 3, 16, 29, 503 |
| 18 | [逢魔日常](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L159) | 日常 | 是 | 1, 2, 3, 23, 24, 26, 508, 8, 504 |
| 19 | [每日签到与收取邮件](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L171) | 日常 |  | 0, 1, 2, 3, 518, 521 |
| 20 | [喂猫喂狗](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L185) | 日常 |  | 0, 1, 2, 3, 517 |
| 21 | [经验妖怪](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L196) | 日常 |  | 0, 50, 1, 2, 3, 5, 27 |
| 22 | [金币妖怪](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L211) | 日常 |  | 0, 50, 1, 2, 3, 5, 27 |
| 23 | [寮活动启动器](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L227) | 寮活动 |  | 690, 0, 1, 2, 3, 505, 600, 503 |
| 24 | [狩猎战](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L233) | 寮活动 |  | 509, 510, 0, 1, 2, 3, 601 |
| 25 | [道馆](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L239) | 寮活动 | 是 | 509, 510, 0, 1, 2, 3, 602 |
| 26 | [狭间暗域](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L246) | 寮活动 |  | 318, 311, 315, 510, 0, 1, 2, 3, 24, 603 |
| 27 | [宴会](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L252) | 寮活动 | 是 | 0, 1, 2, 3, 605, 503 |
| 28 | [首领退治](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L259) | 寮活动 |  | 509, 510, 0, 1, 2, 3, 604 |
| 29 | [阴门挑战](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L265) | 寮活动 |  | 509, 510, 0, 1, 2, 3, 5, 606 |
| 30 | [僵尸寮自动攻打道馆](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L271) | 寮活动 |  | 509, 510, 311, 519, 505, 51, 0, 1, 2, 3, 602 |
| 31 | [斗技](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L291) | 每周活动 |  | 690, 509, 510, 0, 1, 2, 3, 30, 503 |
| 32 | [秘闻前五层](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L297) | 每周活动 | 是 | 690, 509, 510, 315, 0, 50, 1, 2, 3, 34, 29, 503 |
| 33 | [秘闻后五层](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L320) | 每周活动 | 是 | 509, 510, 315, 0, 40, 1, 2, 3, 34 |
| 34 | [六道椒图](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L342) | 每周活动 | 是 | 690, 509, 510, 0, 1, 316, 2, 3, 24, 29, 503 |
| 35 | [每周真蛇_队长](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L357) | 每周活动 |  | 690, 509, 510, 1106, 0, 1, 2, 3, 306, 5, 1106, 6, 53, 29, 503 |
| 36 | [每周真蛇_队员](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L363) | 每周活动 |  | 690, 509, 510, 0, 1, 2, 3, 4, 24, 1106, 503 |
| 37 | [契灵_单人](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L375) | 每周活动 | 是 | 690, 509, 510, 0, 1, 2, 3, 313, 29, 503 |
| 38 | [契灵_队长](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L387) | 每周活动 |  | 690, 509, 510, 0, 1, 2, 3, 306, 5, 313, 29, 503 |
| 39 | [契灵_队员](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L402) | 每周活动 |  | 690, 509, 510, 0, 1, 2, 3, 4, 313, 29, 503 |
| 40 | [周三神秘商人](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L416) | 每周活动 |  | 690, 0, 1, 2, 3, 24, 1110, 503 |
| 41 | [百鬼棋局](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L427) | 每周活动 |  | 0, 1, 2, 3, 24, 320 |
| 42 | [每周资源领取](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L438) | 每周活动 |  | 690, 0, 1, 2, 3, 24, 1100, 1101, 1102, 1103, 1104, 1105, 1107, 1108, 1109, 1111, 503 |
| 43 | [魂海_队员](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L449) | 每周活动 | 是 | 690, 509, 510, 0, 1, 2, 3, 4, 503 |
| 44 | [魂海_队长](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L466) | 每周活动 |  | 690, 509, 510, 0, 1, 2, 3, 306, 5, 5, 27, 503, 306 |
| 45 | [绘卷进度_检测并提醒](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L490) | 罕见活动 | 是 | 0, 2, 3, 304 |
| 46 | [夜行荒河](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L497) | 罕见活动 |  | 2, 3, 220, 221 |
| 47 | [伊吹之擂](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L503) | 罕见活动 |  | 0, 3, 24, 99 |
| 48 | [对弈竞猜](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L509) | 罕见活动 |  | 0, 2, 3, 401 |
| 49 | [清自己1-4星鬼王](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L520) | 罕见活动 |  | 0, 1, 2, 3, 24, 317 |
| 50 | [循环_魂十队长](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L527) | 循环任务 | 是 | 690, 509, 510, 0, 1, 2, 3, 5, 27, 306, 503 |
| 51 | [循环_御魂队员](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L544) | 循环任务 | 是 | 690, 509, 510, 50, 0, 1, 2, 3, 4, 503 |
| 52 | [循环_探索队长](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L566) | 循环任务 | 是 | 690, 509, 510, 0, 1, 2, 3, 5, 27, 306, 25, 14, 503 |
| 53 | [循环_探索队员](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L583) | 循环任务 | 是 | 690, 509, 510, 50, 0, 1, 2, 3, 4, 25, 503 |
| 54 | [循环_探索单人](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L606) | 循环任务 | 是 | 690, 509, 510, 50, 0, 1, 2, 3, 14, 29, 503 |
| 55 | [突破打9退4](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L629) | 循环任务 | 是 | 690, 509, 510, 501, 40, 51, 0, 1, 2, 3, 8, 9, 10, 11, 29, 503 |
| 56 | [僵尸寮日常任务](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L656) | 小号部分 | 是 | 0, 1, 2, 3, 24, 609, 503 |
| 57 | [协战十五](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L675) | 小号部分 |  | 690, 694, 0, 1, 2, 3, 6, 53, 29, 503 |
| 58 | [师徒_师傅战斗](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L690) | 师徒部分 |  | 690, 510, 0, 1, 2, 3, 693, 503 |
| 59 | [师徒_徒弟登录](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L701) | 师徒部分 |  | 690, 0, 1, 2, 3, 5, 24, 691, 503 |
| 60 | [师徒_徒弟升级](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L719) | 师徒部分 |  | 694, 0, 1, 2, 3, 29, 14 |
| 61 | [师徒_徒弟领体力](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L733) | 师徒部分 |  | 690, 0, 1, 2, 3, 518, 503 |
| 62 | [师徒_徒弟金币](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L746) | 师徒部分 |  | 0, 1, 2, 3, 27, 306, 5 |
| 63 | [师徒_徒弟经验](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L758) | 师徒部分 |  | 0, 1, 2, 3, 27, 306, 5 |
| 64 | [师徒_徒弟石距](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L769) | 师徒部分 |  | 0, 1, 2, 3, 27, 306, 5 |
| 65 | [师徒_徒弟协战](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L780) | 师徒部分 |  | 690, 694, 0, 1, 2, 3, 53, 6, 29, 503 |
| 66 | [师徒_徒弟守护](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L797) | 师徒部分 |  | 0, 1, 2, 3, 306, 692, 29 |

分组统计：每周活动 14、战斗 9、日常 9、师徒部分 9、寮活动 8、循环任务 6、罕见活动 5、未分组 4、小号部分 2。

**引用了不存在功能的 18 个方案**【源码】：15 个引用 24（获得奖励确认，已移入 [`funcList.bak/024`][f024bak]；提交 [`8dddd89`][c-002-024] 的说明是“002 内置 024”），如小功能合集、返回庭院、通用活动、六道椒图、每周资源领取等。其余几个：“逢魔日常”引用 504（庭院进入现世逢魔，在 bak 里）；“每日签到与收取邮件”引用 521（**任何目录都没有**）；“夜行荒河”引用 220（夜行荒河_杂项，在 bak 里）；“突破打9退4”引用 10（地图进入突破界面，在 bak 里）。[`getFuncList`][getFuncList] 遇到不存在的 id 直接 `continue`。所以这些方案能照常启动，但少了某一步。例如“逢魔日常”少了从庭院进入现世逢魔这一步，可能停在庭院，直到“长时间未执行”超时才停止【推测】。

### 1.3 功能清单（115 个）

“类别”是本报告按 id 段、所属方案和名称做的归类，源码中没有类别字段。“作用”优先取源码 `desc`，没有 desc 的按名称与代码概括，并加注说明。“被引用”是引用该功能的内置方案数，0 表示只能由用户自建方案使用。

| id | 名称 | 类别 | 作用 | 被引用 |
| --- | --- | --- | --- | --- |
| [0](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/000_%E7%BB%93%E6%9D%9F%E5%88%A4%E6%96%AD.ts) | 结束判断 | 通用战斗框架 | 配置停止或切换方案的条件 | 63 |
| [1](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/001_%E5%87%86%E5%A4%87.ts) | 准备 | 通用战斗框架 | 在准备界面执行准备或退出操作 | 62 |
| [2](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/002_%E9%80%80%E5%87%BA%E7%BB%93%E7%AE%97.ts) | 退出结算 | 通用战斗框架 | 战斗结算、奖励弹窗、体力不足、失败重打等通用收尾与杂弹窗处理（源码无 desc，据名称与代码概括） | 65 |
| [3](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/003.%E6%82%AC%E8%B5%8F%E5%8D%8F%E4%BD%9C.ts) | 悬赏协作 | 通用战斗框架 | 处理悬赏/邀约协作邀请弹窗（接受/拒绝/关闭），并清理部分杂弹窗、被顶号时停止（源码无 desc，据名称与代码概括） | 66 |
| [4](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/004_%E6%8E%A5%E5%8F%97%E9%82%80%E8%AF%B7.ts) | 接受邀请 | 通用战斗框架 | 左侧弹出邀请提示时，自动接受邀请 | 6 |
| [5](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/005_%E7%BB%84%E9%98%9F%E6%8C%91%E6%88%98.ts) | 组队挑战 | 通用战斗框架 | 在组队界面时，点击挑战按钮，可配置三人开始或有人就开 | 14 |
| [6](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/006_%E5%BE%A1%E9%AD%82%E5%BE%A1%E7%81%B5%E6%8C%91%E6%88%98.ts) | 御魂/御灵/秘闻挑战 | 副本/玩法 | 点击挑战按钮，连续点击3次后未开始将自动停止脚本 | 4 |
| [7](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/007_%E7%BB%93%E7%95%8C_%E4%B8%89%E6%AC%A1%E5%88%B7%E6%96%B0.ts) | 结界_三次刷新 | 副本/玩法 | 启用后会在打了三个结界后进行刷新，若CD中则等待CD后刷新 | 1 |
| [8](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/008_%E7%BB%93%E7%95%8C_%E8%BF%9B%E6%94%BB.ts) | 结界_进攻 | 副本/玩法 | 出现进攻按钮后点击进攻按钮，若进攻按钮点击不生效可选择退出到上一级（寮突破用）或停止脚本（个人突破用）或切换方案；若在寮突破时进攻为灰色，会自动等待CD或切换方案 | 5 |
| [9](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/009_%E7%BB%93%E7%95%8C_%E5%8B%8B%E7%AB%A0%E7%82%B9%E5%87%BB.ts) | 结界_勋章点击 | 副本/玩法 | 在突破界面点击勋章，可配置点击勋章的优先级 | 4 |
| [11](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/011_%E7%BB%93%E7%95%8C_%E5%88%B7%E6%96%B0%E6%8C%89%E9%92%AE.ts) | 结界_刷新按钮 | 副本/玩法 | 个人突破时点击刷新按钮，应排在[9勋章点击]后 | 2 |
| [12](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/012_%E5%AF%AE%E7%AA%81%E7%A0%B4_%E7%BF%BB%E9%A1%B5.ts) | 寮突破_翻页 | 副本/玩法 | 寮突破时执行翻页操作，应排在[9勋章点击]后 | 1 |
| [14](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/014_%E6%8E%A2%E7%B4%A2_%E7%82%B9%E5%87%BB%E6%8C%91%E6%88%98%E5%9B%BE%E6%A0%87.ts) | 探索_点击挑战图标 | 副本/玩法 | 在探索界面时，选择小怪或boss进攻，优先打boss，可配置无差别挑战或只打经验怪或只打掉落怪 | 4 |
| [16](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/016_%E5%9C%B0%E9%AC%BC_%E7%83%AD%E9%97%A8%E6%94%B6%E8%97%8F%E6%8C%91%E6%88%98.ts) | 地鬼_热门收藏挑战 | 副本/玩法 | 在地域鬼王界面时，自动选热门或收藏进行挑战 | 1 |
| [17](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/017_%E7%99%BE%E9%AC%BC%E5%A4%9C%E8%A1%8C.ts) | 百鬼夜行 | 副本/玩法 | 在百鬼夜行的界面，随机自由散豆 | 1 |
| [18](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/018_%E6%AF%8F%E6%97%A5%E6%82%AC%E8%B5%8F.ts) | 每日悬赏 | 日常 | 庭院打开悬赏→追踪→前往挑战/秘闻副本→领宝箱→协作（源码无 desc，据名称与代码概括） | 1 |
| [19](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/019_%E5%8D%8F%E5%90%8C%E5%AF%B9%E5%BC%88.ts) | 协同对弈 | 副本/玩法 | 进行协同对弈开始或结束等操作 | 0 |
| [22](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/022_%E6%8A%BD%E5%8E%95%E7%BA%B8%28%E6%99%AE%E9%80%9A%E5%8F%AC%E5%94%A4%29.ts) | 抽厕纸(普通召唤) | 副本/玩法 | 普通召唤抽卡（源码无 desc，据名称与代码概括） | 1 |
| [23](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/023_%E9%80%A2%E9%AD%94%E7%AD%94%E9%A2%98.ts) | 逢魔答题 | 副本/玩法 | 逢魔密信自动答题，需要安装OCR扩展 | 1 |
| [25](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/025_%E6%8E%A2%E7%B4%A2_%E5%8D%95%E4%BA%BA%E6%97%B6%E9%80%80%E5%87%BA.ts) | 探索_单人时退出 | 通用战斗框架 | 探索小怪界面时，若只有自己一个人在里面，则退出 | 2 |
| [26](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/026_%E7%8E%B0%E4%B8%96%E9%80%A2%E9%AD%94.ts) | 现世逢魔 | 副本/玩法 | 逢魔界面点击右下角的图标后点击右侧相关事件图标 | 1 |
| [27](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/027_%E7%BB%84%E9%98%9F_%E5%88%9B%E5%BB%BA%E6%88%96%E5%8C%B9%E9%85%8D.ts) | 组队_创建或匹配 | 通用战斗框架 | 创建指定副本组队或直接匹配 | 9 |
| [28](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/028_%E7%BB%A7%E7%BB%AD%E8%82%B2%E6%88%90.ts) | 继续育成 | 式神/御魂养成 | 式神升级界面点击确定后再点击继续育成 | 1 |
| [29](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/029_%E5%BA%AD%E9%99%A2%E8%BF%9B%E5%85%A5%E6%8E%A2%E7%B4%A2%E5%9C%B0%E5%9B%BE.ts) | 庭院进入探索地图 | 导航/BUFF | 请使用默认庭院皮肤，启用该功能后在庭院下会自动进入探索地图界面 | 18 |
| [30](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/030_%E6%96%97%E6%8A%80_%E6%9D%82%E9%A1%B9.ts) | 斗技_杂项 | 副本/玩法 | 斗技界面点击“战”按钮，自动跳过段位提升等 | 1 |
| [31](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/031_%E7%BB%93%E7%95%8C%E5%8D%A1_%E7%BB%A7%E7%BB%AD%E5%90%88%E6%88%90.ts) | 结界卡_继续合成 | 式神/御魂养成 | 结界卡合成界面继续合成（源码无 desc，据名称与代码概括） | 1 |
| [32](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/032_%E9%81%93%E9%A6%86_%E6%8C%91%E6%88%98.ts) | 道馆_挑战 | 副本/玩法 | 道馆内挑战流程（源码无 desc，据名称与代码概括） | 0 |
| [33](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/033_%E8%A1%8C%E4%B8%BA%E4%BB%BF%E7%9C%9F.ts) | 行为仿真 | 通用战斗框架 | 启用后战斗中一定概率打开聊天框，若将概率都设置0，则不会打开任何聊天框，并且会自动关闭聊天框 | 0 |
| [34](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/034_%E7%A7%98%E9%97%BB_%E6%8C%91%E6%88%98.ts) | 秘闻_挑战 | 副本/玩法 | 每周竞速或百战挑战 | 2 |
| [35](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/035_%E9%87%91%E5%B8%81%E5%A6%96%E6%80%AA_%E5%BA%AD%E9%99%A2%E8%BF%9B%E5%85%A5%E7%BB%84%E9%98%9F.ts) | 金币妖怪_庭院进入组队 | 金币妖怪(未被方案引用) | 从庭院进入组队界面（请提前手动打开buff） | 0 |
| [37](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/037_%E9%87%91%E5%B8%81%E5%A6%96%E6%80%AA_%E8%BF%9B%E5%85%A5%E7%BB%84%E9%98%9F.ts) | 金币妖怪_进入组队 | 金币妖怪(未被方案引用) | 从庭院进入金币妖怪组队界面 | 0 |
| [38](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/038_%E9%87%91%E5%B8%81%E5%A6%96%E6%80%AA_%E8%BF%9B%E5%85%A5%E5%89%AF%E6%9C%AC.ts) | 金币妖怪_组队界面 | 金币妖怪(未被方案引用) | 金币妖怪组队界面操作（源码无 desc，据名称与代码概括） | 0 |
| [40](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/040_%E5%85%B3%E9%97%ADBUFF.ts) | 关闭BUFF | 导航/BUFF | 在 BUFF 界面关闭已开启的加成（源码无 desc，据名称与代码概括） | 3 |
| [50](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/050_%E5%BC%80%E5%90%AFBUFF.ts) | 开启BUFF | 导航/BUFF | 在 BUFF 界面开启指定加成（源码无 desc，据名称与代码概括） | 6 |
| [51](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/051_%E7%BB%BF%E6%A0%87.ts) | 绿标 | 通用战斗框架 | 战斗界面标记我方式神 | 3 |
| [53](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/053_%E6%8E%A2%E7%B4%A2%E5%9C%B0%E5%9B%BE%E8%BF%9B%E5%85%A5%E6%8C%91%E6%88%98.ts) | 探索地图进入挑战 | 副本/玩法 | 从探索地图进入御魂、御灵等个人挑战界面，注意，仅进入挑战界面，挑战层数请自行 | 3 |
| [99](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/099_%E4%BC%8A%E5%90%B9%E4%B9%8B%E6%93%82_%E5%8C%B9%E9%85%8D.ts) | 伊吹之擂_匹配 | 副本/玩法 | 连续执行5次后未开始，脚本将自动停止 | 1 |
| [221](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/221_%E5%A4%9C%E8%A1%8C%E8%8D%92%E6%B2%B3_%E6%8C%91%E6%88%98.ts) | 夜行荒河_挑战 | 副本/玩法 | 夜行荒河活动挑战（源码无 desc，据名称与代码概括） | 1 |
| [302](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/302_%E5%A5%89%E7%BA%B3%E5%BE%A1%E9%AD%82.ts) | 奉纳御魂 | 式神/御魂养成 | 奉纳弃置御魂，结束后返回式神录 | 1 |
| [304](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/304_%E6%A3%80%E6%B5%8B%E7%BB%98%E5%8D%B7%E8%BF%9B%E5%BA%A6.ts) | 检测绘卷进度 | 副本/玩法 | 先自行投分至期望排名,只想检测进度也需投入一次分数触发界面 | 1 |
| [306](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/306_%E7%BB%84%E9%98%9F%E5%86%85%E9%82%80%E8%AF%B7%E5%A5%BD%E5%8F%8B.ts) | 组队内邀请好友 | 通用战斗框架 | 组队界面邀请好友(排在‘005挑战’前面) | 9 |
| [309](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/309_%E5%BC%BA%E5%8C%96%E5%BE%A1%E9%AD%82.ts) | 强化御魂 | 式神/御魂养成 | 在批量强化界面强化御魂,逻辑在群公告的手册里 | 1 |
| [310](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/310_%E6%9A%B4%E9%A3%9F%E9%AC%BC.ts) | 暴食鬼吃经验 | 式神/御魂养成 | 暴食鬼吃一二三星经验书 | 1 |
| [311](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/311_%E7%BA%A2%E6%A0%87.ts) | 红标 | 通用战斗框架 | 战斗界面标记敌方式神 | 2 |
| [312](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/312_%E9%80%9A%E7%94%A8%E6%B4%BB%E5%8A%A8.ts) | 通用活动 | 副本/玩法 | 通用活动界面点击挑战（源码无 desc，据名称与代码概括） | 1 |
| [313](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/313_%E5%A5%91%E7%81%B5.ts) | 契灵 | 副本/玩法 | 需自行设置好“结契设置” | 3 |
| [315](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/315_%E4%B8%8A%E9%A2%84%E8%AE%BE%E9%98%B5%E5%AE%B9.ts) | 上预设阵容 | 通用战斗框架 | 准备前上固定预设 | 3 |
| [316](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/316_%E5%85%AD%E9%81%93%E6%A4%92%E5%9B%BE.ts) | 六道椒图 | 副本/玩法 | 六道之门·椒图：选 buff、选事件、战斗（源码无 desc，据名称与代码概括） | 1 |
| [317](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/317_%E6%B8%85%E8%87%AA%E5%B7%B1%E9%AC%BC%E7%8E%8B.ts) | 清自己鬼王 | 副本/玩法 | 召唤并清理自己发现的鬼王。星级由游戏内「裂变之眼」决定，请先在召唤队列里选好「即将召唤」的星级 | 1 |
| [318](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/318_%E6%88%98%E6%96%97%E5%86%85%E6%93%8D%E4%BD%9C%E9%80%89%E9%A1%B9.ts) | 战斗内操作选项 | 通用战斗框架 | 战斗内操作选项（如手动/自动、标记切换）（源码无 desc，据名称与代码概括） | 1 |
| [319](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/319_%E7%BC%98%E7%BB%93%E8%B6%A3%E6%B8%B8.ts) | 缘结趣游 | 副本/玩法 | 确保勾玉足够买票 | 1 |
| [320](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/320_%E7%99%BE%E9%AC%BC%E6%A3%8B%E5%B1%80%E6%8C%82%E6%9C%BA.ts) | 百鬼棋局挂机 | 副本/玩法 | 自行配置一套阵容置顶 | 1 |
| [321](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/321_%E5%AF%BB%E9%A6%99%E8%A1%8C%E5%88%87%E6%8D%A2%E6%8A%80%E8%83%BD.ts) | 寻香行五层后切二技能(待重做) | 副本/玩法 | （源码无 desc） | 0 |
| [401](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/401_%E5%AF%B9%E5%BC%88%E7%AB%9E%E7%8C%9C.ts) | 对弈竞猜 | 副本/玩法 | 对弈竞猜选边 | 1 |
| [500](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/500_%E8%BF%9B%E5%85%A5%E5%AF%AE%E6%B4%BB%E5%8A%A8.ts) | 进入寮活动 | 阴阳寮 | 进入已开启寮活动，如道馆、宴会、狩猎战、首领退治 | 0 |
| [501](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/501_%E6%89%93%E5%BC%80buff%E7%95%8C%E9%9D%A2.ts) | 打开BUFF界面 | 导航/BUFF | 支持从探索地图、庭院、组队打开buff界面 | 2 |
| [503](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/503_%E8%BF%94%E5%9B%9E%E5%BA%AD%E9%99%A2%E7%95%8C%E9%9D%A2.ts) | 返回庭院界面 | 导航/BUFF | 从各个地方返回到庭院界面 | 33 |
| [505](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/505_%E5%BA%AD%E9%99%A2%E8%BF%9B%E5%85%A5%E5%AF%AE%E4%BC%9A%E7%A5%9E%E7%A4%BE.ts) | 庭院进入寮会神社 | 导航/BUFF | 庭院进入寮会神社_判断 | 2 |
| [506](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/506_%E8%BF%9B%E5%85%A5%E5%AF%AE%E6%B4%BB%E5%8A%A8.ts) | 进入寮活动 | 阴阳寮 | 进入已开启寮活动，如道馆、宴会、狩猎战、首领退治 | 0 |
| [507](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/507_%E7%8B%A9%E7%8C%8E%E6%88%98_%E6%8C%91%E6%88%98.ts) | 狩猎战_挑战 | 阴阳寮 | 狩猎战挑战（源码无 desc，据名称与代码概括） | 0 |
| [508](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/508_%E9%80%A2%E9%AD%94%E9%A6%96%E9%A2%86.ts) | 逢魔首领 | 副本/玩法 | 逢魔首领挑战（源码无 desc，据名称与代码概括） | 1 |
| [509](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/509_%E8%BF%9B%E5%85%A5%E5%BC%8F%E7%A5%9E%E5%BD%95.ts) | 进入式神录 | 式神/御魂养成 | 从不同页面进入式神录 | 25 |
| [510](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/510_%E5%BC%8F%E7%A5%9E%E5%BD%95%E9%A2%84%E8%AE%BE%E8%BF%87%E6%BB%A4.ts) | 更改式神录御魂 | 式神/御魂养成 | 进入式神录更改式神御魂 | 27 |
| [511](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/511_%E5%BC%8F%E7%A5%9E%E5%BD%95%E9%A2%84%E8%AE%BE%E7%BF%BB%E9%A1%B5.ts) | 式神录预设_翻页 | 式神/御魂养成 | 式神录预设列表操作，应排在[510]后 | 0 |
| [512](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/512_%E9%A6%96%E9%A2%86%E9%80%80%E6%B2%BB.ts) | 首领退治_挑战 | 阴阳寮 | 首领退治挑战（源码无 desc，据名称与代码概括） | 0 |
| [513](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/513_%E9%98%B4%E9%97%A8%E6%8C%91%E6%88%98.ts) | 阴门_挑战 | 阴阳寮 | 阴门挑战（无 operator，全部写在 operatorFunc）（源码无 desc，据名称与代码概括） | 0 |
| [514](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/514_%E7%8B%AD%E9%97%B4%E6%9A%97%E5%9F%9F.ts) | 狭间暗域 | 阴阳寮 | 狭间暗域流程（源码无 desc，据名称与代码概括） | 0 |
| [515](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/515_%E7%8B%AD%E9%97%B4%E6%9A%97%E5%9F%9F%E6%8C%91%E6%88%98.ts) | 狭间暗域_挑战 | 阴阳寮 | 狭间暗域挑战（无 operator）（源码无 desc，据名称与代码概括） | 0 |
| [516](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/516_%E5%AF%AE%E6%B4%BB%E5%8A%A8%E7%BF%BB%E9%A1%B5.ts) | 寮活动翻页 | 阴阳寮 | 寮活动列表翻页操作，翻页后返回上一层，应排在[506]后 | 0 |
| [517](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/517_%E5%AE%A0%E7%89%A9%E5%BA%AD%E9%99%A2.ts) | 宠物庭院 | 日常 | 宠物庭院喂猫喂狗 | 1 |
| [518](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/518_%E9%A2%86%E5%8F%96%E6%97%A5%E5%B8%B8%E5%A5%96%E5%8A%B1.ts) | 领取日常奖励 | 日常 | 一键代办,商店黑蛋,发送吉闻,花合战,大神每日(需安装),注:不会领每日六星御魂 | 2 |
| [519](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/519_%E5%AF%AE%E7%AE%A1%E7%90%86%E6%94%BB%E6%89%93%E9%81%93%E9%A6%86.ts) | 寮管理攻打道馆 | 阴阳寮 | 星期1234进入道馆 | 1 |
| [522](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/522_%E6%8B%9B%E5%8B%9F.ts) | 管理招募 | 阴阳寮 | 管理自动招募功能 | 0 |
| [600](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/600_%E8%BF%9B%E5%85%A5%E5%AF%AE%E6%B4%BB%E5%8A%A8.ts) | 进入寮活动 | 阴阳寮 | 进入已开启寮活动(狩猎战、道馆、狭间暗域、首领退治、宴会、阴界之门) | 1 |
| [601](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/601_%E7%8B%A9%E7%8C%8E%E6%88%98.ts) | 狩猎战 | 阴阳寮 | 狩猎战（寮活动启动器子流程）（源码无 desc，据名称与代码概括） | 1 |
| [602](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/602_%E9%81%93%E9%A6%86.ts) | 道馆 | 阴阳寮 | 道馆（寮活动启动器子流程）（源码无 desc，据名称与代码概括） | 2 |
| [603](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/603_%E7%8B%AD%E9%97%B4%E6%9A%97%E5%9F%9F.ts) | 狭间暗域 | 阴阳寮 | 狭间暗域（寮活动启动器子流程）（源码无 desc，据名称与代码概括） | 1 |
| [604](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/604_%E9%A6%96%E9%A2%86%E9%80%80%E6%B2%BB.ts) | 首领退治 | 阴阳寮 | 首领退治（寮活动启动器子流程）（源码无 desc，据名称与代码概括） | 1 |
| [605](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/605_%E5%AF%AE%E5%AE%B4%E4%BC%9A.ts) | 寮宴会 | 阴阳寮 | 寮宴会（源码无 desc，据名称与代码概括） | 1 |
| [606](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/606_%E9%98%B4%E7%95%8C%E4%B9%8B%E9%97%A8.ts) | 阴门之门 | 阴阳寮 | 阴界之门（源码无 desc，据名称与代码概括） | 1 |
| [609](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/609_%E5%83%B5%E5%B0%B8%E5%AF%AE%E5%B0%8F%E5%8F%B7%E5%8A%9F%E8%83%BD.ts) | 僵尸寮小号功能 | 阴阳寮 | 用于切换小号进行每日寮任务 | 1 |
| [690](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/690_%E5%90%AF%E5%8A%A8%E6%B8%B8%E6%88%8F.ts) | 启动游戏 | 启动/账号/师徒 | 启动游戏 | 29 |
| [691](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/691_%E6%96%B0%E5%8F%B7%E6%B5%81%E7%A8%8B.ts) | 新号流程 | 启动/账号/师徒 | 新号剧情/引导流程（源码无 desc，据名称与代码概括） | 1 |
| [692](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/692_%E5%BE%92%E5%BC%9F%E6%B5%81%E7%A8%8B.ts) | 徒弟流程 | 启动/账号/师徒 | 徒弟流程（源码无 desc，据名称与代码概括） | 1 |
| [693](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/693_%E5%B8%88%E5%82%85%E6%B5%81%E7%A8%8B.ts) | 师傅流程 | 启动/账号/师徒 | 师傅流程（源码无 desc，据名称与代码概括） | 1 |
| [694](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/694_%E4%B8%8A%E9%98%B5%E5%8D%8F%E6%88%98%E5%BC%8F%E7%A5%9E.ts) | 上阵协战式神 | 通用战斗框架 | 上阵第一个式神进行协战,用于协战十五次(排在准备前面,不要锁定阵容) | 3 |
| [695](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/695_%E7%A7%98%E9%97%BB%E5%89%8D%E5%9B%9B%E5%B1%82.ts) | 秘闻前四层 | 副本/玩法 | 选择式神后清理前四层秘闻三十次 | 0 |
| [700](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/700_%E8%BF%9B%E5%85%A5%E7%BB%93%E7%95%8C%E5%B9%B6%E9%A2%86%E5%8F%96%E9%A3%9F%E7%9B%92.ts) | 进入结界并领取食盒 | 结界/寄养 | 进入结界并领取食盒/经验（源码无 desc，据名称与代码概括） | 2 |
| [701](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/701_%E7%BB%93%E7%95%8C%E5%8D%A1%E6%9B%B4%E6%8D%A2.ts) | 结界卡更换 | 结界/寄养 | 更换结界卡（源码无 desc，据名称与代码概括） | 1 |
| [702](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/702_%E5%BC%8F%E7%A5%9E%E5%AF%84%E5%85%BB.ts) | 式神寄养 | 结界/寄养 | 阴阳寮结界内式神寄养（源码无 desc，据名称与代码概括） | 1 |
| [703](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/703_%E6%AD%A6%E9%81%93%E5%A4%A7%E4%BC%9A_%E6%8E%A2%E7%B4%A2.ts) | 武道大会-探索 | 副本/玩法 | 武道大会探索界面（配合通用活动使用）：已探索出御灵则点击左侧御灵图标进入挑战界面，未探索则点击右侧探索按钮，探索后自动进入御灵挑战界面 | 0 |
| [990](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/990_%E7%83%A7%E6%9D%A1.ts) | 烧条 | 通用战斗框架 | 只针对斗技浪川阵容,放在最前面,悬赏后面(参照排序3,990,0,1,2) | 0 |
| [991](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/991_%E6%A8%A1%E6%8B%9F%E5%99%A8%E9%87%8D%E5%90%AF.ts) | 重启模拟器 | 启动/账号/师徒 | 重启模拟器，减少模拟器崩溃的几率(官方说的: )) | 0 |
| [992](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/992_%E7%BA%A2%E5%8C%85.ts) | 御祝都是我的 | 日常 | 御祝，懂的都懂 | 0 |
| [993](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/993_%E5%BA%94%E7%94%A8%E5%90%AF%E5%8A%A8%E7%99%BB%E5%BD%95.ts) | 启动游戏 | 启动/账号/师徒 | 启动游戏 | 0 |
| [994](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/994_%E5%AF%84%E5%85%BB%E5%A5%BD%E5%8F%8B%E5%88%97%E8%A1%A8%E7%BF%BB%E9%A1%B5.ts) | 寄养好友列表_翻页 | 结界/寄养 | 寄养好友列表操作，应排在[996,997]后 | 0 |
| [995](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/995_%E9%80%89%E6%8B%A9%E5%BC%8F%E7%A5%9E%E5%AF%84%E5%85%BB.ts) | 寄养式神操作 | 结界/寄养 | 在好友结界中寄养式神 | 0 |
| [996](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/996_%E5%BC%8F%E7%A5%9E%E5%AF%84%E5%85%BB%E8%BF%87%E6%BB%A4.ts) | 式神寄养过滤 | 结界/寄养 | 式神寄养过滤 | 0 |
| [997](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/997_%E5%88%B7%E6%96%B0%E5%BC%8F%E7%A5%9E%E5%AF%84%E5%85%BB%E5%88%97%E8%A1%A8.ts) | 刷新式神寄养列表 | 结界/寄养 | 刷新式神寄养列表_让其重新排序 | 0 |
| [998](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/998_%E5%BC%8F%E7%A5%9E%E5%AF%84%E5%85%BB.ts) | 式神寄养 | 结界/寄养 | 阴阳寮式神寄养页面 | 0 |
| [999](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/999_%E5%BC%8F%E7%A5%9E%E8%82%B2%E6%88%90.ts) | 式神育成 | 结界/寄养 | 打开阴阳寮式神育成页面 | 0 |
| [1000](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/1000_%E5%AE%B4%E4%BC%9A_%E5%90%83%E7%99%BD%E9%A5%AD.ts) | 宴会 | 阴阳寮 | 宴会_摆烂 | 0 |
| [1001](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/1001_%E5%AE%B4%E4%BC%9A_%E8%AE%BE%E7%BD%AE%E8%BD%AE%E6%8D%A2%E5%BC%8F%E7%A5%9E.ts) | 宴会_设置轮换式神 | 阴阳寮 | 宴会中设置轮换式神（源码无 desc，据名称与代码概括） | 0 |
| [1100](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/1100_%E6%AF%8F%E5%91%A8%E5%88%86%E4%BA%AB%E8%93%9D%E7%A5%A8.ts) | 每周分享蓝票 | 每周资源/商店 | 每周分享得蓝票（源码无 desc，据名称与代码概括） | 1 |
| [1101](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/1101_%E5%AF%AE%E5%95%86%E5%BA%97.ts) | 寮商店购买 | 每周资源/商店 | 寮商店购买（源码无 desc，据名称与代码概括） | 1 |
| [1102](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/1102_%E6%AF%8F%E5%91%A8%E5%95%86%E5%BA%97%E8%B4%AD%E4%B9%B0.ts) | 每周商店购买 | 每周资源/商店 | 每周商店购买（源码无 desc，据名称与代码概括） | 1 |
| [1103](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/1103_%E9%A3%8E%E5%A7%BF%E7%9A%AE%E8%82%A4%E5%88%B8.ts) | 风姿皮肤券 | 每周资源/商店 | 领取风姿皮肤券（源码无 desc，据名称与代码概括） | 1 |
| [1104](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/1104_%E7%8F%8D%E6%97%85%E5%B1%85%E5%85%91%E6%8D%A2.ts) | 珍旅居兑换 | 每周资源/商店 | 珍旅居兑换（源码无 desc，据名称与代码概括） | 1 |
| [1105](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/1105_%E6%AF%8F%E4%B8%A4%E5%91%A8%E7%A5%9E%E9%BE%9B%E5%85%91%E6%8D%A2%E9%BB%91%E8%9B%8B.ts) | 每两周神龛兑换黑蛋 | 每周资源/商店 | 每两周神龛兑换黑蛋（源码无 desc，据名称与代码概括） | 1 |
| [1106](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/1106_%E6%AF%8F%E5%91%A8%E4%B8%A4%E6%AC%A1%E7%9C%9F%E8%9B%87.ts) | 每周两次真蛇 | 副本/玩法 | 使用刷出真蛇功能需排序在"006挑战"之前 | 2 |
| [1107](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/1107.%E6%AF%8F%E5%91%A8%E5%A5%91%E7%81%B5%E5%95%86%E5%BA%97.ts) | 每周契灵商店 | 每周资源/商店 | 每周契灵商店购买（源码无 desc，据名称与代码概括） | 1 |
| [1108](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/1108.%E6%A1%A3%E6%9C%9F%E5%BC%8F%E7%A5%9E%E9%A2%86%E5%8F%96%E8%93%9D%E7%A5%A8.ts) | 档期式神领取蓝票 | 每周资源/商店 | 集结时领取六张蓝票,式神上线后购买祈愿福礼(推荐星期二运行) | 1 |
| [1109](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/1109_%E7%A7%98%E9%97%BB%E5%9C%B0%E9%AC%BC%E7%9A%84%E5%88%86%E4%BA%AB.ts) | 每周秘闻地鬼的分享 | 每周资源/商店 | 请保证已经攻打过秘闻和地鬼(非mumu版本时依赖大神分享,并且无地鬼分享) | 1 |
| [1110](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/1110_%E7%A5%9E%E7%A7%98%E5%95%86%E5%BA%97.ts) | 神秘商店购买蓝票黑碎 | 每周资源/商店 | 神秘商店购买蓝票/黑碎（源码无 desc，据名称与代码概括） | 1 |
| [1111](https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/1111_%E7%99%BE%E9%AC%BC%E6%A3%8B%E5%B1%80%E5%95%86%E5%BA%97.ts) | 百鬼棋局商店 | 每周资源/商店 | 百鬼棋局商店购买（源码无 desc，据名称与代码概括） | 1 |

**同名或同职责的重复功能**【源码】：690／993 都叫“启动游戏”；500／506／600 都是“进入寮活动”；514／603 狭间暗域；507／601 狩猎战；512／604 首领退治；513／606 阴门；702／998 式神寄养。其中一份往往是旧实现，没有被任何方案引用。

## 2. 底层基础能力怎么实现

| 能力 | 实现 | 源码位置 | 评价 |
| --- | --- | --- | --- |
| 截图 | 调用 ScriptLib 的 `KeepScreen(mode)` 缓存一帧；`mode=true` 时额外建立红色通道索引，用来加速找色。主循环每轮开头截一次，同一轮内所有功能共用这一帧 | [script.ts L218][keepScreen]、[主循环 L596–612][runLoop] | 帧被共享，点击之后的判断必须等下一轮或显式 `keepScreen` |
| 多点比色（判定画面） | `CompareColorEx(desc, colorSimilar)`。`desc` 是 `[锚点模式, x, y, 颜色]` 的数组，锚点模式分 left／center／right，先以 1280×720 为基准换算到当前分辨率；全局阈值 93% | [oper L785][oper]、[getFuncList 坐标换算 L260–300][getFuncList]、[helperBridge devWidth L12][devWidth]、[commonConfig][commonConfig] | 快、轻、无模型，但对皮肤、字体、抗锯齿、亮度很敏感 |
| 多点找色（定位目标） | `FindMultiColor(region, desc, 97%, 错位匹配)` 取第一个命中点；`findMultiColorEx` 取全部命中，并合并距离小于 30px 的点 | [findMultiColor L375][findMultiColor]、[multiFindColors.ts][mfc] | 支持“找所有再挑一个”（如最近、最上），这是我们暂时没有的 |
| 轮询等待 | `findMultiColorLoop`／`compareColorLoop` 按 `loopDelay` 轮询到超时；单个 operator 的 `retest`：命中后先 sleep，重截图再比一次（防抖） | [L498–534][loops]、[retest L821][retest] | 防抖思路可取；但大量等待是功能内硬编码的 `sleep` |
| 找图 | **没有**。工程配置 `removeOpenCv: true`；ncnn YOLO（百鬼夜行模型）只被停用的 `funcList.bak/020` 使用 | [project.json L23][removeOpenCv]、[ncnnBgyx.ts][ncnn] | 图标类目标全部靠多点找色 |
| OCR | 三种后端（ML Kit、ML Kit 2、云犀），需要单独安装插件。`findText` 支持正则、包含、汉字相似度几种匹配；`findNum` 把 S／O／Z 替换成 5／0／2；`findTextWithCompareColor` 先比色确认场景再识别。生效功能中共 29 处调用 | [script.ts L128–216][ocr]、[MlkitOcr2.ts L98][mlkit2] | 只用在答题、昵称、数量、商店等少数地方，主体仍是比色 |
| 点击随机化 | 区域内二维正态分布取点，**均值由 AndroidId 哈希决定**，所以同一设备每次都偏向同一位置；按压 10–60ms；点击后延时同样是“设备固定均值的正态分布”；`operStepRandom` 按 AndroidId 哈希选候选，**同一设备永远选同一个** | [regionClick L107–137][regionClick]、[stepRandom L160][stepRandom]、[getRegionBiasRnd2／strHashToNum][bias]、[issue #28][i28] | 用意是“每台设备有自己的习惯”，但同一设备上的随机性很弱 |
| 输入后端 | 无障碍、RootAutomator、Shell `input swipe`、Root，四选一 | [MyAutomator.ts L26][automator] | — |
| 滑动 | `regionSwipe` 起止点在区域内随机、时长随机；`regionBezierSwipe` 走贝塞尔曲线；`swipePath` **只取首尾两点**（源码 TODO 说明 root 下要补点） | [regionSwipe L164][regionSwipe]、[swipePath L181][swipePath] | — |
| 场景识别与自动选方案 | 每个功能的 `operator.desc` 就是场景判定。`autoRun` 对常用方案逐个比色，只有一个方案命中就运行，多个方案命中就弹窗让用户选 | [autoRun L635][autoRun] | 方案级的“从当前画面启动” |
| 计次 | `runTimes[funcId]`：任一 operator 命中且点击后加 1；**7.5 秒内同一功能重复命中不计**；`notForCnt` 可以排除确认框 | [L828][count] | 计的是“命中次数”，不是业务结果 |
| 结束条件 | 功能 0 做统一判断：长时间未执行任何功能（默认 10 分钟）、总运行时长、准备次数、结算次数、循环次数、定时暂停；结束后可以停止、切换方案或随机等待 | [000_结束判断][f000] | 用超时兜底未知画面 |
| 方案切换 | `rerun(name)` 支持特殊值 `__停止脚本__`、`__返回上个方案__`、`__关闭应用__`、`__不做动作__`、`__暂停__`；`schemeHistory` 记录历史；`superGlobal` 跨方案保留 | [rerun L712–772][rerun] | 链式任务靠各功能里的 `next_scheme` 参数拼接 |
| 暂停／恢复 | 暂停是先设 `isPause` 再停线程；恢复时保留 `global`，从方案第一个功能重新比色 | [_run L549–571][runLoop]、[pause L774][rerun] | 与我们“恢复时从入口重新观察”相同 |
| 定时 | 每 5 秒的计时器，按 `level` 优先级抢占；重复模式四种：一次、从开始算间隔、从结束算间隔、cron；`nextOffset` 加随机偏移；有免打扰模式；`sharedData.runTime` 允许功能回写下次运行时间 | [Schedule/index.ts L365–444][schedule]、[默认定时 15 个][scheduleList] | 有优先级抢占；问题见第 4 节 |
| 通知 | PushPlus、Server 酱、Gotify、OneBot；推送内容是文字 **加当前整帧截图**；没配置推送时只播放系统提示音 | [doPush L339–379][doPush]、[PushClient][pushclient] | 截图外发有隐私风险 |
| 应用与设备控制 | `app.launchPackage` 启动游戏；`am force-stop`（需 root）或先回桌面再 `killBackgroundProcesses`；功能 991 直接 `reboot` | [launch／stop L909–969][appctl]、[991][f991] | — |
| 非游戏 App | 用无障碍控件选择器（`text()`／`id()`）操作网易大神签到 | [518 L826–904][f518-godlike] | — |
| 调试 | `drawFloaty` 把比色点和点击点画在悬浮层上 | [drawFloaty.ts][drawFloaty] | 适合人工取点 |

## 3. 典型功能剖析

### 3.1 领取日常奖励（518，916 行，49 个 operator）

- **识别**：49 个 operator 覆盖庭院、一键代办、邮件、商店、好友吉闻、花合战等界面和弹窗，几乎都是多点比色；庭院判定明确写了“只支持默认庭院皮肤与默认装饰”（[L14][f518]）。
- **流程**：用 `global.function_Switch` 的六个开关（代办、商店、好友、花合战、邮件、大神）表示子任务进度（[L638–647][f518-switch]）。每轮按优先级尝试各子任务的入口和步骤，子任务结束就关掉对应开关。
- **成功／失败／已完成**【源码】：
  - 一键代办连续命中 2 次即判定完成（[L703–715][f518-agency]）；
  - 商店每日领取连续 10 次找不到目标，就提示“跳过领取”、推送消息、关掉开关（[L735–764][f518-shop]）；
  - 邮件领满 8 次就关掉开关（[L663][f518-switch]）。
  - 全程没有“领到了什么”的核对，“找不到”和“已完成”是同一个状态。
- **弹窗**：获得奖励、经验已满、签到解读、神秘商人首次弹窗、下载提示等各对应一个 operator，统一放在“杂项”或“退出”组里优先点掉。
- **进度不持久**：开关放在 `global`，每次全新启动都会重置。中断后重跑会把所有子任务重新尝试一遍【源码】，靠游戏界面自身的状态避免重复领取【推测】。

### 3.2 御魂／御灵单人（方案“个人御魂”= 0, 1, 2, 3, 6）

- 功能 6 只负责“点挑战”：同一帧里命中挑战按钮就点，然后重新截图；**连续 3 次点了还在挑战界面**，就切换到 `next_scheme`（desc 写的是“自动停止”，代码实际是 `rerun(next_scheme)`）（[006 L62–84][f006]）。
- 战斗中的准备由功能 1 处理，结算由功能 2 处理。功能 2 有 41 个 operator：胜利／失败太鼓、达摩、奖励弹窗、御魂溢出、体力不足、断线、技能升级提示……还专门有 MuMu 截图版本的特征（[002 L38–60][f002]）。体力不足时按配置切换方案或停止，失败时可选择重新挑战或切换方案（[L499–540][f002-logic]）。
- **完成判定**：靠功能 0 统计功能 2 的命中次数（[000 L170][f000-cnt]），也就是“看到结算画面并点击”的次数，7.5 秒去重。

### 3.3 结界突破（7 三次刷新、8 进攻、9 勋章选择、11 刷新、12 寮突破翻页）

- 功能 9 按勋章优先级选结界，功能 8 用多点找色定位“进攻”按钮，命中即点，然后 `count--`（[008 L214–260][f008]）。寮突破先找“进攻_灰”，判断 CD 后等待或切换方案（[L192][f008-cd]）。
- “打 9 退 4”（个人突破卡级）由多个方案靠 `next_scheme` 和 `designated_scheme` 拼接而成。源码注释直接要求用户在特定方案里配置特定选项，否则“会跳出循环或卡住”（[008 L220–238][f008]）。issue [#45][i45] 报告“完成退四后脚本自动停止”，issue [#25][i25] 中维护者也承认“需要多个方案配合才能实现流程化”。
- **完成判定**：次数等于“进攻按钮被点击的次数”，不核对是否真的进入了战斗。代码里留有“历史版本遗留参数的适配”分支（[L220][f008]）。

### 3.4 探索（14 点击挑战图标、25 单人时退出、29 庭院进入探索地图、53 地图进入挑战）

- 在探索界面优先找首领；否则按配置无差别地打，或者用 `findMultiColorEx` 找到所有经验／掉落标识和所有挑战图标，挑距离最近、且位于标识上方的那一个（[014 L172–270][f014]）。
- 找不到就贝塞尔滑屏，**剩余滑屏次数用完就点退出**（[L247][f014-exit]），不区分“打完了”和“没识别到”。
- 小缺陷【源码】：滑动速度的兜底写成 `String(thisconf.swipeSpeed) || '慢'`，`String(undefined)` 是非空字符串，兜底永远不会生效（[L266][f014-exit]）。

### 3.5 悬赏封印（18 每日悬赏、3 悬赏协作）

- 功能 18：庭院 → 悬赏 → 一键追踪 → 在“发现地点”弹窗里找“挑战”或“秘闻”字样，点它右侧 +440px 的按钮，再点一个**未经分辨率换算的固定坐标** `[1082, 586, 1127, 625]`（[L289／L302][f018-raw]）→ 在探索界面领宝箱、点已追踪任务。在探索界面连续 5 次没找到可做的项目，就切换方案（[L318–371][f018-loop]）。
- 协作分支对金币、狗粮、体力、勾玉四种协作各复制一段几乎相同的代码，源码写着“这一坨协作的多点找色需要重新取点”（[L381][f018-todo]）。
- 功能 3 出现在全部 66 个方案里：按配置接受、拒绝或关闭悬赏协作邀请，顺便清理“阴阳寮成就页”“呱太”等杂弹窗；检测到“其他设备登录”就推送并停止（[003 L139–205][f003]）。注释原话是“这些乱七八糟的弹窗统一找个地方放吧，烦死了”（[L66][f003-msg]）。
- issue [#136][i136] 报告新探索界面下 018 与 503 有部分未适配；提交 [`2d04d9a`][c-xs] 修复过“悬赏直接结束”的问题。

## 4. 暗病与历史包袱

| # | 问题 | 证据 | 类型 |
| --- | --- | --- | --- |
| 1 | **找不到即当完成或跳过**：商店连续 10 次未找到就关掉开关；代办 2 次即完成；功能 6 连续 3 次就切方案；悬赏 5 次就切方案；探索滑屏次数用完就退出；调度器免打扰模式直接 `doDone()`，跳过等同完成 | [518 L757][f518-shop]、[518 L697／711][f518-agency]、[006][f006]、[018][f018-loop]、[014 L247][f014-exit]、[Schedule L431][lazy] | 源码 |
| 2 | **点击后不核对**：`oper` 点完就返回 true，靠下一轮重新比色推进；界面反应慢时会重复点击。#12 的“关 BUFF 时连点两次导致又被打开”，维护者回复的原因正是固定 300ms 延时内状态未变，处理方式是把延时改成 1500ms | [oper][oper]、[issue #12][i12] | 源码 + issue |
| 3 | **计次等于命中次数**：7.5 秒去重；突破每点一次进攻就减 1；结束判断以结算命中次数为准。重复弹窗或点击未生效会导致多计或少计 | [count L828][count]、[008 L214][f008]、[000 L170][f000-cnt] | 源码（偏差程度为推测） |
| 4 | **分辨率与硬编码坐标**：以 1280×720 为基准缩放，README 承认异形屏几乎都不支持；018 有未换算的绝对坐标；33 个文件中共 149 处“找到的点 ± 固定像素”偏移没有随分辨率缩放；屏幕尺寸在模块加载时取一次 | [README][readme]、[devWidth][devWidth]、[018 L289][f018-raw] | 源码（非 720p 下点偏为推测） |
| 5 | **颜色阈值与皮肤／画质**：所有比色共享 93%／97% 两个全局阈值；功能代码中 95 处写“只支持默认（庭院皮肤／装饰）”；有 MuMu 专用特征。#72 寄养不点击，最后归因于游戏的“高清字体／抗锯齿”设置；#169 结算的 37 号特征把突破界面误识别为奖励弹窗 | [commonConfig][commonConfig]、[002 L49–50][f002]、[issue #72][i72]、[issue #169][i169]、[修复 1103ea4][c-169] | 源码 + issue |
| 6 | **游戏一更新就要重新取色**：2026-03-08 至 2026-11-12 的 237 个非合并提交中，49 个说明含“取色／取点／比色／识别／适配”；#65／#66（时空秘境挤占探索入口）、#136（新探索）、#115（地鬼不点挑战）、#58（奉纳）都属于这类 | [提交列表][commits]、[#66][i66]、[#136][i136]、[#115][i115] | 提交 + issue |
| 7 | **全局可变状态**：功能代码中 `thisScript.global／superGlobal` 共 998 处；`sharedData.runTime` 让功能回写调度时间；`getFuncList` 就地修改共享的功能对象（`transed`）。全新启动会清空 `global`，进度不持久 | [script.ts L60–61][globals]、[L1001][shared]、[getFuncList][getFuncList] | 源码 |
| 8 | **方案死引用与配置漂移**：18 个方案引用了不存在的功能，被静默跳过；`mergeSchemeList` 只补用户没有的内置方案，**用户已保存的同名内置方案永远不会更新**，修过的内置方案到不了老用户；#22 反映内置方案里 994 的配置有误 | [getFuncList L268][getFuncList]、[mergeSchemeList][mergeSchemeList]、[issue #22][i22] | 源码 + issue |
| 9 | **方案切换静默失败**：存在性检查用 `includes` 子串匹配，`setCurrentScheme` 却要求名称完全一致。传入部分名称时检查能通过，实际不会切换，但仍提示“切换方案为 X”并重跑旧方案 | [rerun L752][rerun]、[tool.ts L41][tool-set] | 源码（后果为推测） |
| 10 | **超时与重试散落各处**：上限是各功能硬编码的 3、5、10 次，等待是硬编码的 sleep，唯一的全局兜底是“10 分钟没动作就停止”；OCR 忙等烧 CPU 到 2026-08 才修，修复还在一次合并中整体丢失，之后补回 | [8b1aa86][c-fix]、[3ec74cd][c-restore] | 提交 |
| 11 | **异常路径缺陷与无测试**：运行线程的 catch 里误用 `this.job`（应为 `self.job`），异常终止时定时任务永久卡在运行中，2026-08 才修；`test/` 只有手工脚本，`package.json` 没有 test 命令 | [8b1aa86][c-fix]、[package.json][pkg] | 提交 + 源码 |
| 12 | **调度器硬编码**：每周三 15:00–15:05 一律不调度（注释还写着“6:30／9:00”）；计时器只把过期不到 180 秒的任务入队，启动时只为 cron 任务重算过期时间，间隔类任务一旦错过超过 3 分钟就不会再触发；991 在脚本线程里睡 610 秒后直接 reboot | [Schedule L375–393][schedule]、[webviewEvents/schedule.ts L43][sched-load]、[991][f991] | 源码（间隔任务卡住为推测） |
| 13 | **随机化弱且可关联**：点击均值、延时均值、步骤候选都由 AndroidId 决定，同一设备上的分布固定 | [stepRandom][stepRandom]、[issue #28][i28] | 源码 |
| 14 | **代码重复与遗留**：7 组同职责功能；87 个停用文件；`templateV3.deprecated`；2260 行的 609、1573 行的 691；悬赏的四种协作各复制一段 | 第 1.3 节、[018 L381][f018-todo] | 源码 |
| 15 | **平台包袱**：依赖闭源的 Auto.js Pro（#32 有人问“是不是跑路了”）、二进制 `scriptlib.dex`、自建的热更新壳；#14 反映打包后闪退或黑屏 | [issue #32][i32]、[issue #14][i14] | issue |
| 16 | **推送外发截图**：每次推送都附带当前整帧截图，可能包含昵称、聊天、好友 | [doPush L357–363][doPush] | 源码 |

## 5. 对我们的建议

### 5.1 任务候选（按优先级）

1. **归位与通用弹窗**（对应 503 + 002 + 003）：把“从任意已知界面回到庭院”做成任务，作为其他任务的公共前置。只认已知界面，未知界面输出“无法确认”，不盲点。前置依赖：多目标定位、颜色状态、条件等待。返回键在游戏弹窗上会弹出退出确认，已有实测，所以关弹窗要靠 `tapRegion` 或关闭按钮。
2. **每日领取**（518 中适合我们的部分）：在现有每日礼包草稿的基础上，依次加入邮件“全部领取”、一键代办、好友吉闻。每个子项都要核对“获得奖励”或“已领取”状态，“找不到”保持未知，不当成已领。
3. **个人结界突破**（7／8／9／11）：每天消耗、价值高、界面相对稳定。用数字读取拿到剩余突破券，按勋章挑选目标；“进攻”点击后要核对进入战斗，再核对结算结果；次数以“结算核对通过的次数”为准。“9 退 4”放到后续迭代。
4. **御魂／御灵单人计次**（6 + 1 + 2）：挑战 → 战斗 → 结算的单一循环，体力不足和失败作为明确的终止结果。我们已有分支和计次重复，正好验证“输入后结果未知”在长循环中的表现。
5. **悬赏封印**（18）：每天都有，但需要多目标定位、相对位移点击和滚动查找，适合作为基础动作补齐之后的验收任务。

其后可做：结界寄养与结界卡（700／701／702，默认每 6 小时一次的高频定时）、地鬼（16）、探索（14，需要滑屏查找）、组队司机／乘客（4／5／27，涉及多账号协作，放到后面）。

### 5.2 可借鉴的思路（只借思路，不复制代码）

项目本身是 MIT 许可（Copyright 2019 Chaos），但依赖闭源的 Auto.js Pro 和二进制 ScriptLib，比色表数据来自游戏画面取样。我们**只借鉴设计思路，不复制代码或特征数据**；如果将来确需引用其中的数据，须保留 MIT 声明，并另行评估游戏素材的权利。

- **锚点 + 参考分辨率**：left／center／right 三种锚点换算，和我们 `deriveRegion` 的“参考宽高 + 锚点偏移”一致，可以补上左／中／右对齐的选项。
- **防抖重测 `retest`**：命中后隔一段时间重新截图再确认一次，可作为观察动作的可选“二次确认”。
- **找全部再按规则挑选**：如“距标识最近且在标识上方”“取最上面一个”。
- **场景优先级表**：每个任务都带一份“已知干扰弹窗”清单，先清弹窗再推进主流程。我们可以把它做成任务包共享的“弹窗库”，单独版本化，而不是复制到每个任务里。
- **从当前画面推荐任务**（`autoRun`）：只用已有观察给出候选，不自动开跑。
- **结束条件的种类**：总时长、长时间无进展、结果计数，可以作为执行器级别的通用守护条件。
- **调度**：优先级抢占、cron、随机偏移、免打扰，都是用户可感知的需求；但跳过必须记为“跳过”，不能记为“完成”。
- **调试悬浮层**：把识别框和点击点画出来，对任务作者非常有用。

### 5.3 应该避免的做法，以及我们的模型怎么解决

| 它的做法 | 我们的对应 |
| --- | --- |
| 点完即成功，靠下一轮比色推进 | 输入前在新截图中重新定位，写入输入意图与回执，输入后必须重新观察核对；没核对到就是结果未知 |
| 找不到、次数用完就当完成 | “无法确认”是独立结果，不能补出核对证据；跳过和完成分开记录 |
| 全局阈值、默认皮肤 | 阈值写在每个动作的参数里，由任务作者明确给出；要求“恰好一个”匹配；界面状态由文字、图像存在与缺失组合判定 |
| 命中次数计数 | 计次重复以核对通过为准 |
| 全局可变 `global`／`superGlobal` | 句柄只能经前序输出传递，任何输入之后失效；不提供全局变量 |
| 方案间用 `next_scheme` 字符串拼接流程 | 一个任务内用分支和重复表达，任务间由唯一执行器排队；引用在装载时严格校验，不静默跳过 |
| 内置方案与用户副本漂移 | 任务包版本化，资源附摘要 |
| 硬编码 sleep 与散落的重试上限 | 等待与上限作为显式参数并留痕，执行器统一处理超时 |
| 推送带整帧截图 | 通知默认只发文字和结构化结果，截图需用户明确开启 |

### 5.4 我们还缺的基础动作

对照现有的 `screen.readText／readRegionText／expectText／locateText／classifyText／deriveRegion／matchImage／locateImage／classifyRegion／tap／longPress／swipe／back／tapRegion` 和 `control.wait`：

1. **多目标定位与选择**（如 `screen.locateAllImages`／`locateAllText` + 选择规则：最上、最左、离锚点最近、第 N 个）。现在的 `locateImage`／`locateText` 要求恰好一处匹配，悬赏、探索、突破的结界列表、勋章都需要从多个候选中挑一个。
2. **颜色／亮度状态**（区域平均色或多点颜色比较，带参考分辨率）：用于判断按钮灰／亮、“已完成”勾、红点等。成本比 OCR 低得多，可以作为 `classifyRegion` 的一种条件。
3. **数字读取**（`screen.readNumber`：区域 OCR + 解析 + 范围校验，可识别“12/30”这类格式）：突破券、体力、次数、CD 倒计时都要用，用于决定重复次数和停止条件。
4. **条件等待**（`waitUntil`：反复观察直到某状态成立，或超时后返回未知）：现在只有固定时长的 `control.wait` 加手工重新观察，战斗结束、加载这类等待时长不定的场景写起来很绕。
5. **有界“直到”循环**：在计次 `repeat` 之外增加“直到某状态成立，最多 N 轮”。打到券用完、滑到列表尽头都需要。
6. **滚动查找**（滑动 → 重新观察 → 找到目标或画面不再变化即停）。
7. **画面变化检测**（两帧或区域差异）：用来判断“点击未生效”“列表已到底”，是“结果未知”之外的另一类证据。
8. **应用生命周期**：启动游戏、检测前台包名、超时无响应时重启游戏，用于掉线和闪退恢复。`screen.back` 已有前台检查，但还没有独立的启动／重启动作。
9. **通知**（本地通知或可选推送，默认不带截图）：结果未知、需要人工处理时提醒用户。
10. 低优先级：文本输入（多账号切换）、在两个目标之间拖拽（式神上阵）、多段手势路径。

## 6. 方法与局限

- **源码**：`git clone --depth 1` 到 scratchpad 的 `assttyys-src/`，只读；没有执行 `npm install`、没有运行任何脚本或构建、没有连接游戏。用自己写的 Python 脚本（放在克隆目录之外，以 `-I` 运行）解析 `schemeList.ts` 与 `funcList/*.ts` 来计数，关键数字都人工抽查过。
- **issue 与提交**：通过 GitHub REST API 不登录读取。仓库共 174 个 issue／PR 条目，其中 issue 36 个（开放 3 个）、PR 138 个；提交读取了最近 300 个（2026-03-08 至 2026-11-12）。项目主要靠 QQ 群反馈，**GitHub issue 不能代表高频问题的全貌**；部分 issue 的截图上传失败，只能看到文字。
- **功能类别**是本报告的归类，不是源码字段；没有 desc 的功能，“作用”一栏是按名称和代码概括的。
- 所有“后果”类结论（例如非 720p 下偏移出错、间隔任务卡住、方案切换静默失败）都只是读代码得出的，**没有运行验证**，表中已标为推测。
- 前端仓库 `assttyys_ui` 不在本次范围内，悬浮层的研究见 [assttyys-floating-layers.md](assttyys-floating-layers.md)。

<!-- 链接：均为 7d26c420 永久链接、提交或 issue -->
[appctl]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/script.ts#L909-L969
[autoRun]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/script.ts#L635
[automator]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/MyAutomator.ts#L26
[bias]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/toolAuto.ts#L223-L278
[c-002-024]: https://github.com/zzliux/assttyys_autojs/commit/8dddd897c8a8d2bd9dc7360858f3a4f1f6b430fd
[c-169]: https://github.com/zzliux/assttyys_autojs/commit/1103ea4036cf813a80a705752b177c4540f7b07d
[c-fix]: https://github.com/zzliux/assttyys_autojs/commit/8b1aa865dccf2df0e6987d022cd276e94bc45cc3
[c-restore]: https://github.com/zzliux/assttyys_autojs/commit/3ec74cddcbec0c786267e5c9a0f7bf65b43827b9
[c-xs]: https://github.com/zzliux/assttyys_autojs/commit/2d04d9aa5f89c0ad647da893ddfdaaab6e037a17
[commits]: https://github.com/zzliux/assttyys_autojs/commits/master
[commonConfig]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/commonConfig.ts
[count]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/script.ts#L827-L838
[devWidth]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/helperBridge.ts#L12-L21
[doPush]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/toolAuto.ts#L339-L379
[drawFloaty]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/drawFloaty.ts
[f000]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/000_%E7%BB%93%E6%9D%9F%E5%88%A4%E6%96%AD.ts
[f000-cnt]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/000_%E7%BB%93%E6%9D%9F%E5%88%A4%E6%96%AD.ts#L170
[f002]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/002_%E9%80%80%E5%87%BA%E7%BB%93%E7%AE%97.ts#L38-L60
[f002-logic]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/002_%E9%80%80%E5%87%BA%E7%BB%93%E7%AE%97.ts#L499-L540
[f003]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/003.%E6%82%AC%E8%B5%8F%E5%8D%8F%E4%BD%9C.ts#L139-L205
[f003-msg]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/003.%E6%82%AC%E8%B5%8F%E5%8D%8F%E4%BD%9C.ts#L66
[f006]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/006_%E5%BE%A1%E9%AD%82%E5%BE%A1%E7%81%B5%E6%8C%91%E6%88%98.ts#L62-L84
[f008]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/008_%E7%BB%93%E7%95%8C_%E8%BF%9B%E6%94%BB.ts#L214-L260
[f008-cd]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/008_%E7%BB%93%E7%95%8C_%E8%BF%9B%E6%94%BB.ts#L192
[f014]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/014_%E6%8E%A2%E7%B4%A2_%E7%82%B9%E5%87%BB%E6%8C%91%E6%88%98%E5%9B%BE%E6%A0%87.ts#L172-L270
[f014-exit]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/014_%E6%8E%A2%E7%B4%A2_%E7%82%B9%E5%87%BB%E6%8C%91%E6%88%98%E5%9B%BE%E6%A0%87.ts#L247-L266
[f018-loop]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/018_%E6%AF%8F%E6%97%A5%E6%82%AC%E8%B5%8F.ts#L318-L371
[f018-raw]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/018_%E6%AF%8F%E6%97%A5%E6%82%AC%E8%B5%8F.ts#L283-L303
[f018-todo]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/018_%E6%AF%8F%E6%97%A5%E6%82%AC%E8%B5%8F.ts#L381
[f024bak]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList.bak/024_%E8%8E%B7%E5%BE%97%E5%A5%96%E5%8A%B1%E7%A1%AE%E8%AE%A4.ts
[f518]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/518_%E9%A2%86%E5%8F%96%E6%97%A5%E5%B8%B8%E5%A5%96%E5%8A%B1.ts#L14
[f518-agency]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/518_%E9%A2%86%E5%8F%96%E6%97%A5%E5%B8%B8%E5%A5%96%E5%8A%B1.ts#L691-L723
[f518-godlike]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/518_%E9%A2%86%E5%8F%96%E6%97%A5%E5%B8%B8%E5%A5%96%E5%8A%B1.ts#L826-L904
[f518-shop]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/518_%E9%A2%86%E5%8F%96%E6%97%A5%E5%B8%B8%E5%A5%96%E5%8A%B1.ts#L735-L764
[f518-switch]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/518_%E9%A2%86%E5%8F%96%E6%97%A5%E5%B8%B8%E5%A5%96%E5%8A%B1.ts#L638-L683
[f991]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcList/991_%E6%A8%A1%E6%8B%9F%E5%99%A8%E9%87%8D%E5%90%AF.ts
[findMultiColor]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/script.ts#L375-L435
[funcListIndex]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/funcListIndex.ts
[getFuncList]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/script.ts#L260-L300
[globals]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/script.ts#L60-L61
[i115]: https://github.com/zzliux/assttyys_autojs/issues/115
[i12]: https://github.com/zzliux/assttyys_autojs/issues/12
[i136]: https://github.com/zzliux/assttyys_autojs/issues/136
[i14]: https://github.com/zzliux/assttyys_autojs/issues/14
[i169]: https://github.com/zzliux/assttyys_autojs/issues/169
[i22]: https://github.com/zzliux/assttyys_autojs/issues/22
[i25]: https://github.com/zzliux/assttyys_autojs/issues/25
[i28]: https://github.com/zzliux/assttyys_autojs/issues/28
[i32]: https://github.com/zzliux/assttyys_autojs/issues/32
[i45]: https://github.com/zzliux/assttyys_autojs/issues/45
[i66]: https://github.com/zzliux/assttyys_autojs/issues/66
[i72]: https://github.com/zzliux/assttyys_autojs/issues/72
[keepScreen]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/script.ts#L218-L225
[lazy]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/Schedule/index.ts#L431-L435
[loops]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/script.ts#L498-L534
[mdc]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/multiDetectColors.ts
[mergeSchemeList]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/toolAuto.ts#L490-L506
[mfc]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/multiFindColors.ts
[mlkit2]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/Ocr/MlkitOcr2.ts#L98
[ncnn]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/ncnn/ncnnBgyx.ts
[ocr]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/script.ts#L128-L216
[oper]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/script.ts#L785-L859
[pkg]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/package.json#L6-L13
[pushclient]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/PushClient/index.ts
[readme]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/README.md#L9-L10
[regionClick]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/helperBridge.ts#L107-L137
[regionSwipe]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/helperBridge.ts#L164-L177
[removeOpenCv]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/project.json#L23
[rerun]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/script.ts#L712-L783
[retest]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/script.ts#L821-L826
[runLoop]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/script.ts#L549-L630
[sched-load]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/webviewEvents/schedule.ts#L42-L49
[schedule]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/Schedule/index.ts#L365-L444
[scheduleList]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/scheduleList.ts
[schemeList]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L6-L824
[schemeList-merge]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/schemeList.ts#L849-L866
[shared]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/script.ts#L1001
[stepRandom]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/helperBridge.ts#L141-L163
[swipePath]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/helperBridge.ts#L181-L208
[tool-set]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/tool.ts#L41-L75
