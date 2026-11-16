# 任务、计划与队列交付核对（历史基线）

本文件及 verification-execution.json 记录较早的队列版本，截图、计数和源码指纹只对应当时版本。后续通知、网络、游戏账号和布局变更以[本轮交付核对](settings-convergence.md)及其机器记录为准。

日期：2026-11-15。基线：[PRD v2.1](../PRD.md)、[领域用语](../../CONTEXT.md)、[执行调度设计](execution-scheduler.md)。当前交互入口为 [App 原型](../prototype/app-v2.html)。这是设计与内存事件模拟，不是可安装的 Android 应用。

## 当前交互

工作台是运行管理入口，负责状态机暂停／恢复、队列管理、执行状态与结果摘要；任务中心负责操作规则，计划负责任务组合与时间，详细历史保留在消息与记录。

- 任务是一组具体操作步骤；计划引用有序任务和执行时间。任务与计划列表按状态操作：未入队可添加，队列中显示标识并禁用按钮，执行中暂停／继续；常用星标仍在原列表行。同一任务／计划未结束不得重复添加。
- 一整轮计划按全部任务优先级之和排序，本轮不随子任务完成而减分。更高优先级在安全点先执行，随后恢复原进度。
- 工作台展示当前项和待执行项。横屏将队列放到独立滚动的副列，当前项与三个排队项可同时看到；竖屏顺排。
- 右滑移除支持当前、等待和挂起项。移除当前项撤销旧执行器，由独立输入通道确认静默后继续剩余项；不依赖卡死任务返回。行内小移除图标提供另一种操作方式。
- 暂停／长按停止仍暂停整个队列，等待用户继续。继续先新观察与进度核对，再由统一优先级选取；已有人工暂停不会被移除等待项解除。
- 确认失败记录后继续，整轮可为部分失败；未知结果保留证据并核对，移除也不能把未知变成失败或成功。
- 设置提供排队等待通知和小时／分钟输入，渠道订阅中增加“排队等待过久”。没有配置就不猜阈值；通知不自动取消、提权或重试，投递记录不拥有调度状态。

## 实际截图

截图从当前 HTML 渲染导出，尺寸为竖屏 412×860、横屏 900×412。游戏背景、资源、步骤和时间均为演示夹具。

| 内容 | 竖屏 | 横屏 |
| --- | --- | --- |
| 当前执行与排队 | [查看](../prototype/screens-v2/queue-home-portrait.png) | [查看](../prototype/screens-v2/queue-home-landscape.png) |
| 人工暂停队列 | [查看](../prototype/screens-v2/queue-paused-portrait.png) | [查看](../prototype/screens-v2/queue-paused-landscape.png) |
| 等待通知时长 | [查看](../prototype/screens-v2/queue-notification-portrait.png) | [查看](../prototype/screens-v2/queue-notification-landscape.png) |
| 任务列表加入队列 | [查看](../prototype/screens-v2/queue-tasks-portrait.png) | [查看](../prototype/screens-v2/queue-tasks-landscape.png) |
| 计划列表运行与排队 | [查看](../prototype/screens-v2/queue-plans-portrait.png) | [查看](../prototype/screens-v2/queue-plans-landscape.png) |
| 计划与任务编排 | [查看](../prototype/screens-v2/queue-plan-portrait.png) | [查看](../prototype/screens-v2/queue-plan-landscape.png) |
| 撤销在途输入 | [查看](../prototype/screens-v2/queue-removing-portrait.png) | [查看](../prototype/screens-v2/queue-removing-landscape.png) |
| 悬浮常用与当前控制 | [查看](../prototype/screens-v2/queue-overlay-portrait.png) | [查看](../prototype/screens-v2/queue-overlay-landscape.png) |

## 验证证据

[机器记录](verification-execution.json)绑定源码 SHA-256，历史检查不代替本次结果。

| 层次 | 结果与覆盖 |
| --- | --- |
| 调度模块 | 30 个情境通过；[可重跑脚本](../prototype/tests/execution-simulator.test.cjs)，使用 `node docs/prototype/tests/execution-simulator.test.cjs` |
| 实际浏览器 | 163 项通过；49 个清单页面分别横竖访问，以及列表入队、重复提交拦截、真实鼠标右滑、卡死撤销、暂停／继续／长按、等待阈值、渠道记录、失败继续及留存输入校验 |
| 列表实际手势 | 17 项通过；任务／计划横竖屏行内短按暂停、核对恢复、长按停止及无迟到重复添加 |
| 此前时长与布局回归 | 20 项此前通过，本次未重跑，保留原源码指纹；计划时长非法值、结构化保存与快照、横屏内层滚动保留、方向切换、计划双列及列表常用 |
| 静态检查 | HTML 内联脚本和独立模拟器语法通过；当前文档本地链接核对 |

自审发现并修复了五处生命周期缺陷：恢复后旧代次检查点改写、已结算子任务的迟到失败影响下一项、继续复用旧观察、停止后的未知核对复活取消项、未知核对复用其他项的旧观察。根检查实际复现了旧检查点、继续旧观察和跨项未知旧观察的失败；其他两项由独立架构 agent 在修复前复现，根检查在修复后保留相应回归。新加队列能力没有旧生产实现，不能声称这些测试覆盖了旧 Android 行为。

## 边界与待决

模拟器是唯一运行状态所有者，HTML 仅发送命令并投影状态；公开 API 与限制见[接口合同](execution-simulator-contract.md)。没有真实截图、输入、云端包下载、通知发送、墙钟到点执行或进程恢复。本轮没有安装依赖、构建 APK、操作账号或发布。

等待演示口径为首次开始前的等待，每项产生一次事件；被抢占后的恢复等待、人工暂停如何计时及重复提醒还需冻结生产规则。优先级生产范围与默认值、日程重叠／补做、时长到限处置仍未确定。真实 Android 卡死输入撤销、观察新鲜度与24小时运行须在设备上验证，字符串票据与显式模拟事件不能替代设备证据。

本次去重规则变更已先将旧“不同手动点击可以重复入队”的预期改为用户确认的新要求；修复前根测试实际失败，修复后30个情境通过。去重在调度器入口执行，按钮禁用只是同一快照的呈现；各入口不能绕过。
