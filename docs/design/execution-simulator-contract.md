# 执行器原型接口合同

版本：1.0，2026-11-15。实现：[execution-simulator.js](../prototype/execution-simulator.js)。这是可丢弃的评审模拟器；不是 Android 执行器、远程任务运行时或可发布应用。无网络、真实游戏输入、真实时钟、定时器、持久化和通知发送。HTML 仅调用此文件并投影快照，不另维护运行游标或调度状态机。

## 加载和数据所有权

浏览器通过普通 script 加载后使用 `window.YysExecutionSimulator.create()`；Node 使用 `require('./execution-simulator.js').create()`。导出版本为 `1.0.0-prototype`。

`getSnapshot()` 返回 JSON 副本；`onChange(listener)` 立即交付一份快照，后续状态变化继续交付，返回取消订阅函数。监听器异常不能改变已提交业务结果。界面只读快照；入队后修改原配置、原参数对象、快照均不改变既有运行锁。

所有变更命令必须提供非空 `commandId`。完全相同命令重投返回原结果且不重复推进；同 ID 不同内容返回 `command_conflict`。同一次失败命令也会被记住，条件变化后重新操作需使用新 commandId。命令返回 `{ok,status,...}`；拒绝一般附 `message`。不能把 `ok:false` 当作确定的游戏失败。

## 入队合同

```js
const engine = YysExecutionSimulator.create();
engine.enqueue({
  commandId: 'request-1',
  ref: {kind: 'task', id: 'daily-rewards'},
  name: '每日领取', priority: 10,
  configVersion: 'cfg-1', packageVersion: 'pkg-1',
  steps: [{id:'locate', label:'定位'}, {id:'claim', label:'领取并核对'}],
  configValues: {},
  eligibility: {status:'ready', observationId:'observation-1'}
});
```

| 字段/API | 合同 |
| --- | --- |
| `enqueue` | `ref.kind` 为 task 或 plan，`ref.id` 稳定；返回 runId。同一 kind + id 尚未终结时，其他 command 返回 already_present 和原 runId，不创建第二项；执行、排队、挂起及取消静默期间均占用身份，终结后可重新提交。 |
| `enqueueTask` / `enqueuePlan` | 同一入队实现的便利包装，可传 `id` 或 taskId/planId。 |
| task | priority、configVersion、packageVersion、非空 steps 必填。步骤 ID 在该 task 内唯一；字符串步骤自动分配 step-N。 |
| plan | 非空 tasks 数组、planRevision 必填；每个成员具备 task 所需字段及 id。成员顺序保留。 |
| priority | 显式可精确表示的整数，允许 0 和负值；无默认值。plan 锁定成员总和，总和也须可精确表示。这是 JS 原型的数值表示约束。 |
| `configValues` / `planConstraints` | 仅复制元数据；后者可保留 endCondition/maxDurationMinutes，不实现超时、重试或消费策略。不得传凭据；已知敏感字段过滤不构成通用脱敏保证。 |
| `occurrenceKey` | 建议 `{scheduleId,occurrenceId}`，也接受稳定字符串。同 key 即使换 commandId 也返回原 runId；取消后保留去重记录。 |
| `clockDue` | enqueue 别名，仅注入一次到点事件。不会计算日期、补作、跨周期合并或真实定时。 |
| `eligibility` | ready / blocked / unknown；缺省为 unknown，不因当前场景未知拒绝合法请求。ready 应来自宿主观察并带 observationId。 |
| `setEligibility` | `{commandId,runId,status,observationId?,reason?}`；ready 必须带观察 ID。临时环境变化保留已入队项，不能被视为任务下架/配置丢失。 |

定义、步骤、版本、数值校验不合格返回 invalid 类结果，不以 priority=0 等方式兜底。包/配置的真实性、资源和权限的实际核验由宿主提供；原型不读取包，也不识别场景。

## 调度、游标与输入

执行项单位为 taskRun 或整份 planRun。plan 的全局 priority 在入队时锁定为全部成员之和，任务完成或失败都不减分。全局按 priority 降序、enqueueSeq 升序选择，只有严格更高才请求抢占；同分不抢占。计划内部沿用户顺序执行。

`step({commandId,runId,generation})` 仅模拟提交当前一步，生成 inputOwner 并返回 inputId、generation、taskIndex/taskId、stepIndex/stepId，不推进游标。`checkpoint({...})` 必须明确 quiescent:true；有在途输入时须匹配 inputId，outcome 为 success/no_effect/unknown。success 才推进一步，no_effect 保留当前位置，unknown 留证并关闭输入门。任务最后一步 success 自动汇总并完成该任务/执行项。`complete` 不能跳过尚未完成步骤。

抢占请求写入 pendingHandoff；旧项只有到 resumable:true 的检查点、输入已静默且无未知结果才自动挂起。被挂起项保留原 runId、游标、步骤结果及版本锁。高项结束后重新选择所有候选；曾执行项自动恢复前进入 awaiting_reconcile，不盲用旧坐标。

宿主对 worker 回调应原样携带 `generation`、对应 `inputId` 和 taskIndex/taskId/stepIndex；核心拒绝所提供身份与当前代际/在途输入/游标不匹配的事件，包括已结算输入的迟到 fail。原型允许手工评审事件省略部分上下文，不能据此声称具备生产 IPC 安全性。所有真实平台回调边界将来须强制这些身份并独立验证输入租约。

## 暂停、停止、继续

| 命令 | 效果 |
| --- | --- |
| `pause({commandId})` | 全局 manual_hold，新入队允许，新输入禁止；在途回执仍可结算。 |
| `stop({commandId,runId?})` | 先全局 hold，再取消匹配的当前执行项；不丢其他排队项。指定 runId 已变化则冲突，但仍 hold。 |
| `resume({commandId})` | 输入静默且未知结果已核对后，进入 reconciling 并返回 reconcileId；尚未放行输入。 |
| `confirmResume({commandId,reconcileId,observationId,confirmed:true})` | 明确模拟一次新观察与检查点核对，然后由核心选择最高就绪项，不能指定某个 job 优先。 |

恢复票据记录创建前已知 observationId，重复使用旧观察拒绝。宿主须给实际新观察分配新身份；字符串不同本身不证明发生了真实截图。自动恢复和手动继续使用相同核对机制。移除候选导致票据变化时，旧 reconcileId 失效，界面读取新票据。manual_hold 不被完成/失败/移除回调清除。

## 确定失败与未知结果

`fail({commandId,runId,generation,inputId?,taskIndex?,taskId?,stepIndex?,scope:'task'|'run',confirmed:true,reason,quiescent:true})` 仅记录已经确认的失败。若仍有输入须匹配且静默。独立 task 或 scope:run 结束为 failed；plan 的 scope:task 将当前成员记失败，继续后续成员，不重试。混合结果最终 partial_failed，全部失败则 failed。仍保留整个 plan 的锁定 priority。

`unknown({commandId,runId,generation,inputId?,reason,quiescent?})` 记录未知结果。quiescent 未确认时保留 inputOwner；全局 safety block 阻止后续输入，不能转为 fail 跳过。

`resolveOutcome`（别名 reconcileUnknown）输入为 `{commandId,runId,inputId?,outcome:'success'|'no_effect',confirmed:true,observationId,quiescent?}`。未知证据创建时锁定当时已知观察 barrier，核对拒绝复用其中任何 run 的旧观察；未知发生后取得的新观察仍可使用。如仍有 owner，须匹配 inputId 并 quiescent:true。success 对匹配未推进游标结算一次，no_effect 不推进。已取消的 run 保持 cancelled；先 stop 后核对也先结算取消，不复活原项。未知证据在审计事件保留。

## 右滑移除与独立撤销

`remove({commandId,runId,reason?})` 与 removeQueued 是同一入口，现均支持 current。移除等待/挂起项保留审计及 occurrence 去重；移除 current 不新增 manual_hold，保留已有 hold。无在途输入时直接 cancelled，并调度其余合法候选。

current 有在途输入时立即撤销旧 executorGeneration，状态 revoking，返回 revocationId；旧 worker 不能通过 checkpoint/fail/complete 自证释放。宿主使用独立 dispatcher 模拟事件：

```js
engine.confirmRemovalQuiescent({
  commandId:'dispatcher-ack-1', runId:'run-1', revocationId:'revocation-1',
  source:'dispatcher', quiescent:true, outcome:'unknown' // 或 success/no_effect
});
```

核心匹配撤销票据及 owner 后释放输入并取消旧项。unknown 继续全局 block，须 resolveOutcome；取消/删行不清未知结果。此显式事件验证状态合同，不证明真实 Android dispatcher 能终止卡死线程或输入。真实实现需要独立的租约撤销及输入静默证据。

## 事实、事件规则与快照

调度器只记录运行事实与逻辑时间；等待阈值和事件触发已迁移到独立的 [event-rules-simulator.js](../prototype/event-rules-simulator.js)，不再提供 configureWaitNotification，不再在调度器内生成等待通知。advanceTime（兼容别名advanceWait）仅推进逻辑时间，通知或规则失败不持有执行权。

首次dispatch产生run_started；每个任务成员真正获得执行且输入静默后产生一次task_started。计划成员依次启动；暂停中前一成员结算不会提前宣布下一成员开始。恢复仅有run_dispatched，不重复run_started/task_started。task_finished只代表成员的已核对结果，run_finished代表整份执行项终态；规则模块分别消费，独立任务不因两个完成事实重复报成功。

规则与通知的完整合同见[事件与通知架构](event-notification-architecture.md)。

快照 schemaVersion:1，包括 revision/eventSeq/logicalTimeMs、scheduler、active、queue、preempted、inputOwner、runs、events。queue 已含 queued 和 preempted，UI 不再追加 preempted 造成重复。

- scheduler：mode、manualHold、holdReason、pendingHandoff、reconcile、removal、blockedReason。
- run：runId/ref/name/accountBinding、priority/enqueueSeq、status/phase、cursor `{taskIndex,stepIndex,completedSteps}`、taskCursor/stepCursor、tasks、checkpoint、eligibility、executorGeneration、unresolved、cancelRequested、failures、planRevision/planConstraints。
- 锁：taskLocks 按 taskIndex 保存 taskId/configVersion/packageVersion/priority，支持计划重复引用同一 task；configVersions/packageVersions 为便捷映射，重复 taskId 时以 taskLocks 为准。
- inputOwner：inputId/runId/generation/taskIndex/taskId/stepIndex/stepId；同一时刻最多一个。
- events：稳定 eventId/id、seq、type 及相关 runId。run_finished 含 result，task_finished 含 taskId/taskIndex/result；UI 历史与投递记录按 eventId 去重投影。

mode 使用 running/manual_hold/handoff/blocked/reconciling/revoking；phase 还包括 queued/preempted/ready/input_pending/pausing/paused/awaiting_reconcile/stopping/unknown/awaiting_completion 和终态。空队列的 running 代表调度未暂停，不代表有游戏输入。

## 账号归属

enqueue 可携带不可变 accountBinding `{key,name}`，只复制这两项，不接收登录态内容。宿主按已核对实际账号更新 eligibility；旧账号计划不能因新账号已登录而执行。取消保留 cancelReason，可区分手动停止、移除和切号清队列。账号选择、快照与游戏生命周期属于独立模块，不交给调度器执行。

## 验证与边界

`node docs/prototype/tests/execution-simulator.test.cjs`：当前 32 情境通过，覆盖整计划优先级、嵌套抢占/精确游标、同分顺序、全局 hold、继续最高项、失败汇总、未知核对、命令与 occurrence 幂等、锁定快照、临时现场条件、视图异常、当前/等待/挂起移除、独立撤销、迟到代际与子任务回调、恢复及未知核对的旧观察拒绝、stop 后未知核对、原始生命周期事实与逻辑时间。等待事件的两项情境已迁到事件规则边界，未删除其行为校验。部分生命周期反例先在旧实现复现为红，由根 agent 保留回归测试。本文件不把这些内存测试写成真机、真实图片/任务包、服务或 24 小时可靠性证据。

未实现持久化/进程恢复、Android 权限与输入 dispatcher、实际 observation 真实性、包校验/执行、真实定时 occurrence 生成、通知投递或资源消费核对。所有运行、命令、观察与审计仅驻内存；reset/新建 engine 会丢失，不能作为生产去重库。不得把该模拟器直接接真实游戏输入。
