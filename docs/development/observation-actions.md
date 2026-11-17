# 只读观察动作接入

2026-11-16 接入，2026-11-17 完成当前源码的构建及专用模拟器回归。`screen.readText`／`screen.expectText` 已接入唯一执行链，尚无游戏领取结果，M2／M3／M4 保持进行中。[验证证据](build-evidence/observation-actions/README.md)分别记录回归、原故障、安装摘要及尚未验证的边界。

## 同一解释器与版本资源

`BuiltInStepWorkFactory` 沿用同一个动作列表解释器，接受 APK 注册的本地、可重复只读动作提供者；默认等待动作与新增观察动作混用，不新增队列或调度器。`AssistApplication` 注册提供者后，仍将同一版本库交给执行器和 `VersionedTextObserver`；构造时不截图或加载 native。

本轮源码新增两个 v1 原语，不内置任何游戏领取目录：

| 动作 | 必需输入 | 输出／含义 |
| --- | --- | --- |
| `screen.readText` | `packageName`、`detector`、`recognizer`、`dictionary` | `observation`：本步骤尝试内的观察标识，不能作为截图导出或跨任务引用 |
| `screen.expectText` | 前序观察输出、`text`、`minimumConfidence` | 确认本次截图包含满足明确置信度的完全相同文字后，返回 `matched=true` 和核对证据 |

截图包名须属于任务目标，模型 ID 须来自同版本资源清单；这四个值由任务版本直接声明，不能引用用户配置临时改目标或模型。模型角色属于当前 Maa 适配合同，不是模型内置到 APK 的依据，也不代表最终离线后端已经冻结。

置信度由作者明确提供，取零到一之间的数值，没有 App 偷填的默认阈值。核对文字和置信度可以引用本任务的必填配置。即使观察输出尚不可取得，已知常量／配置仍先做参数校验；错误定位回对应配置字段，不用假观察填充参数。只有前序 `screen.readText` 的输出可作为观察来源，常量、配置和其他动作不能伪造标识。

观察表只存在于一次步骤会话，保留实际 `FrameStamp`、版本及候选，标识不写入执行快照或结果历史。旋转、重连或前台应用变化会使核对无效；没有用 JSON 重建画面身份。核对证明的是**所捕获画面中的文字条件**，并不证明画面之后没变、当前账号身份或游戏领取结果。

## 结束与中断

APK 合同新增工具／观察／核对角色。含观察动作的步骤必须以核对动作结束，核对动作还须实际返回核对证据；只有动作名或输出字段不够。缺少观察、识别异常、未确认文字和画面失效不能报告任务成功，也不把 OCR 漏检当作确定业务失败。

每个 `StepWork` 对应一个尝试。继续会由运行宿主创建新工作对象和新会话，从未完成步骤入口重新取得观察；没有动作输出断点。原 JVM 测试也改为通过新工作对象模拟真实宿主重入，原时长和进度断言不变。

工作对象在自己的协程作用域执行。撤销会取消该作用域并等待结束；Maa 适配器仍须先等独立 Binder 死亡、结果读者和 PNG 写者退出，才允许模型租约及位图回收，不能用 Job 取消冒充 native 静默。这里只注册本地只读能力，没有游戏点击或消费动作。

暂停准备先关闭执行授权，再提交控制状态。源码现在在截图交接、模型资源验证后的 backend 开始点和核对交接重查授权；受控撤销返回中断提示，不产生新的未知业务结果。宿主把提示与独立静默证明分开结算，保留执行器拒绝直接接受 `StepFinished(Interrupted)` 的断言。若旧尝试仍有效，只在 APK 的无输入证明及静默都成立时重新进入；不具备该证明时不自动重放。内部事实区分进程恢复与步骤重入，不增加用户恢复选项。

## 截图频率

真实快速继续用例复现了截图错误 3。SDK 将该错误定义为截图请求间隔过短；AOSP 公开源码中的间隔字段为 333ms，服务端使用小于等于判断。本机 SDK 没有公开该间隔常量，源码修复采用 334ms 适配间隔，不调用隐藏 API。[SDK 错误说明](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT)、[AOSP 间隔字段](https://android.googlesource.com/platform/frameworks/base/+/main/core/java/android/accessibilityservice/AccessibilityService.java)、[AOSP 服务端检查](https://android.googlesource.com/platform/frameworks/base/+/39b9fc2a718d/services/accessibility/java/com/android/server/accessibility/AbstractAccessibilityServiceConnection.java)

源码在唯一设备桥内串行安排截图，依据实际提交／完成边界等待后续请求，等待发生在隐藏浮层之前。取消等待不启动截图，结果不缓存复用；当前连接的截图回调尚未结束时保持占用信息，旧连接不会阻塞新连接。位图交付后没有新增 dispatcher 返回边界，保留原取消回收路径。没有把等待塞到游戏任务、增加自动重试、改变错误为正常空白或设置任务超时。

该数值是 AOSP 兼容参数，**不能据此承诺所有厂商、Android 版本或截图实现**。固定 Android 16 tag 原文读取未成功，只有公开索引内容和 SDK 错误合同可供本轮源审；目标 API36 上并发三次截图及实际阻塞识别后的快速继续均已通过，没有将此前失败隐藏为重试成功。

## 当前证据与未完成项

- 当前核心 83 项通过，debug 主／测试 APK 构建及 App Lint 通过。最终完整 Android 批次 123 项中 121 通过、2 条件跳过；这两项分阶段进程用例又分别执行并通过，中间实际强停 Android 进程，已完成步骤及排队项保留。
- 定向宿主／模型租约／设备桥 31 项与实际识别链 11 项均通过，和完整批次重叠，不累加。独立 Room／版本 blob、平台截图、实际 Maa 子进程、文字核对及持久化结果贯通；文字不存在和坏 ONNX 子进程 SIGABRT 保留未知。观察用例触摸计数为零。
- 原扩展控制批次保留 9 通过、1 失败的记录：快速继续触发系统截图间隔限制。修复后实际 C++ 阻塞识别的暂停继续和移除排队均通过；下一次观察的 backend 开始时间在旧 Binder 死亡证据之后，版本租约按静默释放。
- 暂停交接回归在原路径实际出现“原生启动预期零次，实为一次”。首次部分修复虽然关闭该窗口，最终暂停仍超时；最后宿主结算修复已用保存的**同一测试 APK**复验通过，包括 native 启动零次、中断提示和最终暂停状态。前后 APK 摘要及两轮失败日志保留，未把中间失败计作通过。
- 高优先级候选在最终事务前遭遇配置更改的场景已通过：真实事务拒绝入队；因授权暂时关闭而中断的旧只读尝试，在独立静默及无输入证明之后重入。原 run 身份保留，新候选未执行、未留下队列或版本引用，也没有虚构成功／失败。
- 实际安装摘要与本轮构建一致；主 APK 的 assets 只有公共域名表及许可证，没有模型或游戏任务夹具。模型仍仅按明确选项进入测试 APK。没有新增依赖或修改 Room schema。
- 全部画面是自有绘制界面，数据库为独立测试库。没有把夹具装入正式任务目录，没有执行真机游戏、发布 Release、发送通知或验证长期运行。

构建入口仍为 `:core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`。真实 OCR 测试需 `-PincludeOcrIntegrationModels=true`，测试模型只进入测试 APK；运行 `TextObservationRuntimeTest` 需同时显式开启 `allowDeviceGrantChanges`、`allowOcrIntegration`，原生故障／阻塞用例还需 `allowChildNativeFaults`，并限定 ranchu 模拟器。

完整分支／循环、输入与副作用核对、账号前提、首次恢复时平台尚未就绪的等待、常用场景判断、私有源客户端授权、真实领取和留出主题仍需继续。本文不将只读画面核对等同于游戏闭环或完整 M4 联验。
