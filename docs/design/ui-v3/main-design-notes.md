# 主 App UI v3 设计交接

本稿是可点击的本地高保真设计原型，供评审交互和排版，不是 Android 实现，也不表示真实游戏、Root、云模型或通知服务已经验证。

- 入口：[app.html](app.html)，共用基础样式：[base.css](base.css)，颜色与间距：[tokens.css](tokens.css)。
- 开发者工具由独立的 [developer.html](developer.html) 承接 GitHub 与指定仓库权限验证；主 App 不提供任意开启的开发者开关。
- 沿用既有三个内存模块，引用路径为 `../../prototype/`。没有修改旧原型、执行器、事件模块或游戏模块。

## 视觉与布局约定

浅暖白底、苔绿强调色、深灰绿正文。采用 Android 工具类 App 的顶栏、平面列表、分区与底部导航；不使用营销卡片、装饰图片或远程依赖。正文通常 15px，横屏正文 14px，辅助信息 12–13px，标题 20–22px。常规图标触控区 44px，小型行操作 40px，游戏浮层控制保持紧凑。

`base.css` 提供 `.device/.land/.statusbar/.appbar/.appbody/.actionbar/.nav/.gesture`，`.iconbtn/.small/.filled/.tonal/.textbtn`，`.row/.row-main/.row-icon/.row-text/.row-title/.row-sub/.list`，`.form/.field/.choice-group/.choice-option`，`.switch/.section/.summary/.facts/.inline-note/.empty/.tabs/.chip/.badge/.toast`。页面专用排版留在主稿内，不要求开发者页面复用主 App 业务结构。

竖屏 412×860 保持自然纵向；横屏 900×412 按职责分栏：工作台左列完整保留当前执行与异常，右列按队列、下次计划、最近结果顺序共用一个滚区；任务分类窄栏、列表宽栏且任务可两列；任务说明与字段、计划基本参数与任务编排分列；模型仅展开配置后分列；设置与通知管理两列；历史左侧 tabs、右侧内容。实际嵌套滚区保留稳定滚动 key；切方向不清草稿、运行或悬浮层级。

## 页面与状态入口

直接使用 `#页面ID`。加 `?orientation=landscape` 预览横屏；`?view=game&layer=ball`、`layer=quick`、`layer=common` 预览同一套游戏悬浮三层。层级变化不启动、暂停或停止任务。当前执行控制只出现一份，常用候选随场景过滤；第三层顶部当前行与返回、收起固定，候选单独滚动。

恢复状态可用 `?demo=pausing#home`、`demo=paused`、`demo=options`、`demo=continue`、`demo=restart`。外部评审控件和模拟说明位于设备框外，不属于正式产品入口。

以下是初始数据下页面清单；新增计划、账号、渠道、事件、运行与投递记录会扩展相应动态路由。`inputs-demo` 仅为控件评审页，不进入普通设置流程。旧 `#diagnostics` 深链只显示授权入口，不显示受限诊断内容。

| 页面 | 内容 |
| --- | --- |
| `home` | 工作台 |
| `tasks` | 任务中心 |
| `plans` | 托管计划 |
| `messages` | 历史记录 |
| `settings` | 设置 |
| `task/daily-rewards` | 每日物资领取 |
| `task/weekly-rewards` | 每周物资领取 |
| `task/jade-card` | 永久勾玉卡产出 |
| `task/flower` | 花合战奖励 |
| `task/bounty` | 悬赏封印 |
| `task/pet` | 宠物照料 |
| `task/shop-general` | 一般商店兑换 |
| `task/shop-wed` | 周三商店兑换 |
| `task/shop-sat` | 周六商店兑换 |
| `task/soul-run` | 御魂副本 |
| `task/awake-run` | 觉醒副本 |
| `task/explore-run` | 探索 |
| `task/spirit-run` | 御灵 |
| `task/soul-enhance` | 御魂强化 |
| `task/soul-sort` | 御魂整理 |
| `task/realm-claim` | 结界物资收取 |
| `task/realm-card` | 结界挂卡 |
| `task/realm-raid` | 个人结界突破 |
| `task/guild-raid` | 寮突破 |
| `task/hunt` | 狩猎战 |
| `task/event-repeat` | 已适配活动 |
| `task/event-auto` | 自主理解新活动 |
| `plan/daily` | 每日物资托管 |
| `plan/shops` | 周期商店计划 |
| `inputs-demo` | 输入控件示例 |
| `task-updates` | 任务更新 |
| `model` | 云端视觉模型 |
| `environment` | 运行环境 |
| `game-control` | 账号与启动 |
| `accounts` | 账号快照 |
| `network` | 网络设置 |
| `channels` | 通知渠道 |
| `channel/local` | 渠道配置 |
| `channel/webhook` | 渠道配置 |
| `notification-management` | 通知管理 |
| `event-rules` | 事件规则 |
| `event-log` | 事件记录 |
| `event-rule/execution-failed` | 连续执行失败 |
| `event-rule/unknown-result` | 结果待核对 |
| `event-rule/queue-wait` | 排队等待过久 |
| `event-rule/no-progress` | 执行无进展 |
| `event-rule/condition-lost` | 运行条件中断 |
| `event-rule/resource-shortage` | 资源或容量受限 |
| `event-rule/vision-unavailable` | 视觉服务异常 |
| `event-rule/task-started` | 任务开始 |
| `event-rule/task-succeeded` | 任务成功 |
| `event-rule/plan-started` | 计划开始 |
| `event-rule/plan-completed` | 计划结束 |
| `float-settings` | 悬浮球 |
| `data-settings` | 运行与数据 |
| `developer-access` | 开发者授权（受保护入口） |
| `issue/auth` | 问题原因 |
| `issue/resource` | 问题原因 |
| `issue/delivery` | 问题原因 |
| `issue/unknown` | 问题原因 |
| `history/sample-result` | 运行记录 |
| `delivery/sample-delivery` | 通知投递 |
| `preview/soul-sort` | 御魂对象预览 |
| `preview/soul-enhance` | 御魂对象预览 |
| `activity/event-auto` | 活动规则与进度 |

## 交互保留与恢复设计边界

任务与计划列表保留星标和加入队列；计划总优先级由成员任务求和，列表不替执行器拥有运行状态。工作台以当前具体任务为主文，所属计划为次文，显示整执行项优先级；队列允许右滑移除，手动暂停与长按停止仍遵循既有全局 hold。版本列表仍只有版本、更新时间及启用/删除，红点入口保留。模型下拉、配置与新增同组，各服务商草稿与凭据隔离；六类通知渠道与十一项事件规则、网络三范围代理、账号元数据和开机偏好继续使用原内存逻辑。

恢复 sheet 是本轮新增的**独立 UI 评审态**：暂停唯一 Executor 后，可选择从当前未完成步骤继续，或从当前任务第一步重新开始。所属计划不是恢复控制对象，已完成的其他任务不在重做范围。`continue/restart` 只改变展示投影；原执行器保持 hold，游标、队列和历史没有被重写，不表示原模块已实现 restart。设备外持续说明这一边界。没有通过重建 DOM 冒充执行器重启。

恢复展示绑定当前 runId；当前项换代、退出 hold、账号核对 pending 或出现未决结果时清理该展示态。无当前项但队列处于手动 hold 时，继续队列走原执行器的 resume/观察核对路径，不打开当前任务恢复选择。普通控制在暂停中、恢复中、停止中、核对中、移除中统一禁用。

工作台游戏标题栏保留大区、昵称和图标操作。保存未保存账号先说明将暂停并关闭游戏；Root 恢复仍是受控演示，没有实际设备验证。账号切换成功后原未结束执行项清空，未知证据和历史保留；计划配置归原账号，不自动借新账号执行。

## 本轮审查与复改

| 发现 | 修正 |
| --- | --- |
| 任务列表重复完整资源影响，副文两行且难扫描 | 列表仅周期与优先级，完整资源影响留详情；横屏任务行收紧。 |
| 模型未展开配置仍固定窄左栏，大片空白 | 未展开使用完整控制区；展开后才分列；服务商下拉、配置、新增同组。 |
| hold 且无当前项时，继续队列误开恢复 sheet | 无当前项调用原安全 resume 流程，核对后再调度。 |
| 旧恢复夹具残留影响下一执行项 | 所有展示与控制统一用绑定 runId 的 `currentRecoveryKind()`，实际刷新入口清换代状态。 |
| 恢复展示中标签是暂停、图标却是播放 | 图标与文案使用同一恢复投影。 |
| 工作台计划名压过当前任务 | 当前任务作主文，计划名作次文，总优先级不改。 |
| 工作台控制在请求中可重复触发 | 与列表、悬浮及长按共用 `controlPending()`。 |
| 离线或不兼容原因只出现短 toast | 更新列表上方按需一条持续短原因；版本行保持极简。 |
| 横屏工作台结果独占矮滚区，挤压当前区域使异常只露上沿 | 最近结果并入右侧既有滚区，左列恢复完整高度；取消第二 grid 行及结果独立滚动，不改业务顺序与数据范围。 |
| 旧开发开关与深链可能绕过授权 | 删除开放开关与旧诊断实现，设置及深链都只到受保护授权入口。 |

## API 与核查

保留 `window.appPrototype` 原接口（版本号 2 为兼容既有评审脚本），包括 `getManifest/getState/getExecutionState/navigate/dispatch/setOrientation/setOverlayLayer` 等。新增：

- `getDesignState()`：恢复展示态副本。
- `setRecoveryDemo('live'|'pausing'|'paused'|'options'|'continue'|'restart')`：设备外恢复评审夹具。
- `chooseRecovery('continue'|'restart_current_task')`：选择恢复展示方式；不修改真实内存执行器游标。
- 清单中的 `developer-access` 标记 `protected:true`；它只链接独立授权流程。

Node 语法检查与加载真实三个内存模块的 VM 核查覆盖初始页面渲染、受保护深链、无当前项继续队列、恢复投影不改游标、换代后正常暂停、请求态控制和更新失败提示。设计子 agent 未启动浏览器；真实浏览器逐页、双方向、滚动及截图由主 agent 独立完成。最终视觉截图与验证记录以主 agent 的交付为准。

尚未验证：Android 原生视图/触控与系统无障碍表现、真实 Executor 继续或重做、设备 Root 登录态提取恢复、真实网络与云模型、实际通知投递。所有评审数据仅在内存，凭据不写文件也不通过原型外发。
