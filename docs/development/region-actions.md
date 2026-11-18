# 区域与图像状态动作

2026-11-18 实施，依据[区域状态动作提案](../design/region-state-actions-proposal.md)及其[离线实验](build-evidence/region-state/README.md)。实施前先做了三处结构调整，下面一并说明。

## 先做的结构调整

**类型化句柄（core）。** `CapabilityContract` 新增 `outputHandles`／`inputHandles`。观察、目标、区域都是句柄：只能通过前序动作输出绑定传入，不能用常量或配置；任何可能的输入之后失效；不能作为普通参数传递或参与分支比较。`HandleRequirement.forbiddenAncestors` 按任意深度检查来源链。原先 `ScreenActions` 里逐个动作手写的“必须引用当前输入阶段的前序观察”规则全部改为声明。规则在 core 统一校验，见 `handleIssues`。

**任务包视觉配置。** `TaskBundle.visionProfiles` 为每个目标声明一次截图包名、识别合同和模型资源。core 校验目标、包名和资源引用；识别合同与模型角色由 APK 校验。没有视觉配置的旧任务包写出时不带该字段，字节不变。

**`screen.readText` 升到 v2。** 输入只有 `profile`。v1（每次重复写包名和三个模型）已移除。尚未发布过任何正式任务，所以没有兼容负担。

**识别子进程改为复用（不常驻）。** 实测冷启动：模拟器每次观察 370–570ms，真机 865–966ms；真机子进程识别时 PSS 约 275–286MB。用户决定不常驻，改为复用：
- 正常完成的请求在子进程写完结果、关闭全部输入、交还所有权后发 `IDLE`，宿主以此（`child-idle`）作为静默证据并释放模型租约，子进程保持加载；
- 子进程按三份模型的长度与摘要缓存已加载会话，摘要不变时跳过复制和加载；
- 拒绝、取消、协议错误、无法读取的结果和崩溃，仍结束子进程并以 Binder 死亡为静默证据，失败后的原生状态不复用；
- 空闲 20 秒后宿主解除绑定，子进程自行退出；必须观察到旧进程死亡后才绑定新进程，避免连到正在退出的进程。宿主进程死亡时子进程也随之结束。

复用后模拟器单次观察约 258ms。

**结构化 trace。** `ActionResult` 的 Produced／Verified／Unavailable 可带一个 `trace` JSON，只记录区域、得分、模板摘要、候选数量等，不含像素、识别文字或账号值。格式由产出它的动作定义；**目前还没有持久化**，任何逻辑都不能依赖它。

## 动作合同

| 动作 | 输入 | 输出 | 说明 |
| --- | --- | --- | --- |
| `screen.readText` v2 | `profile` | `observation`（句柄） | 观察时保留本帧 PNG，供本次尝试中的区域请求复用，不另行截图 |
| `screen.deriveRegion` v1 | `target`（句柄）、`dx`、`dy`、`width`、`height`、`referenceWidth`、`referenceHeight`（整数常量） | `region`（句柄） | 偏移相对锚点水平中心和底边；按当前帧与参考尺寸缩放；宽高比相差超过 1% 或区域超出画面时判为无法确认 |
| `screen.readRegionText` v1 | `region` | `observation`（句柄，来源链含区域） | 只识别区域内文字，现有 `expectText`／`locateText`／`classifyText` 都可以直接引用 |
| `screen.matchImage` v1 | `region`、`template`（已声明资源）、`minimumScore` | `matched`、`score` | Maa 归一化相关系数，取区域内最高分 |
| `screen.classifyRegion` v1 | `region`、`states` | `state` | 每个状态可组合 `text`（需出现的文字）、`present`（需出现的图像）、`absent`（需缺失的图像），至少一个条件；恰好一个状态成立才输出，否则无法确认并给出得分 |

另有两个输入动作，与 `screen.tap` 一样在输入前于新截图中重新唯一定位目标，并写入动作意图与回执：

| 动作 | 输入 | 说明 |
| --- | --- | --- |
| `screen.longPress` v1 | `target`、`durationMs` | 在目标中心按住指定毫秒 |
| `screen.swipe` v1 | `target`、`durationMs`、`dx`、`dy`、`referenceWidth`、`referenceHeight`、`variation` | 从目标起按缩放位移单笔拖动；位移不能为零，路径任一点超出画面或宽高比不符时不输入 |

| `screen.back` v1 | `observation`（句柄） | 系统返回键。先重新截图，确认观察所属应用仍在前台，再在同一输入所有权、画面与前台检查下调用 `GLOBAL_ACTION_BACK`。系统接受返回键只说明按键已送出，业务效果仍须之后重新观察核对 |

`variation` 用于测试程序在不同输入形态下的完整性，所有取值由任务作者明确给出，App 不填默认值：`startJitter`（0–1，起点在目标框内的偏移比例）、`curve`（0–0.5，二次贝塞尔弯曲深度占位移长度的比例）、`durationJitter`（0–0.5，时长变化比例）、`points`（2–32，路径采样点数），`seed` 可选。同一种子生成同一路径；未给种子时由 App 生成，并连同实际时长和点数写入持久化的输入回执，便于复现。全部取 0、`points` 取 2 即为直线。

提案中原写作“`screen.readText` 加可选 `region`”，实施时改为独立的 `screen.readRegionText`。原因：`readText` 会截取新画面，而区域读取必须复用已观察的同一帧，两者语义不同。

**区域内定位到的目标不能点击**（`screen.tap` 声明 `forbiddenAncestors = {screen.region}`，运行时另有保护）。点击前要在新截图里重新定位目标，而区域来源链无法在新画面上重放。需要点击时，应在整图观察中定位按钮文字。

## 识别后端与保护

- `vision` JNI 新增 `overrideTemplate` 与 `inspectRaw`，原生层只接受 `OCR`／`TemplateMatch`。子进程沿用原 OBSERVE 协议：附带 `requests` 和由宿主分配名称（`t0.png`…）的模板描述符，复制模板时校验长度和 SHA-256。
- 结果解码要求每个结果框都落在请求区域内，否则判为无效输出。
- **CPU 保护**：如果 `/proc/cpuinfo` 声明了 `sve2` 却没有 `sve`（Apple Silicon 宿主上的 AVD 就是这样），图像请求在启动子进程前即返回 `UNSUPPORTED_ENVIRONMENT`，避免 OpenCV KleidiCV 的 SIGILL。文字请求不受影响。

## 验证

- core：128 项通过，含新增句柄与视觉配置测试 9 项。
- 专用 API36 模拟器：`TextObservationContractTest` 15 项通过；`VersionedMaaIntegrationTest` 3 项通过、2 项按设计跳过，图像请求走 CPU 保护分支；`TextObservationRuntimeTest` 21 项通过、3 项按设计跳过。完整回归结果见实现进度。
- 真机 SM-G9910／API35：只运行 `VersionedMaaIntegrationTest`，4 项通过、1 项跳过。合成帧上同一模板在付费卡区域得分 0.999999、免费卡区域 0.31，区域内读出“领取”。运行时游戏保持前台，未发送输入，无障碍服务保持启用；测试包已卸载，手机上的助手已更新为本次构建。

## 未覆盖

真实游戏画面上的整条任务（锚点 → 区域 → 状态 → 点击 → 核对）尚未运行；阈值没有基于足够样本确定；跨分辨率缩放只有单元级计算，没有设备证据；Windows 模拟器无证据；trace 未持久化。

## 每日礼包离线回放（草稿任务）

`DailyGiftReplayTest` 用真实 `ScreenActions`、版本资源和原生识别，配合脚本化设备回放私人截图，不连接游戏。草稿步骤：整图观察 → 定位“每日礼包” → 推出按钮区域 → `classifyRegion`（`claimable`：区域内有“领取”且没有魂玉图标；`paid`：有魂玉图标）→ `claimable` 才在整图定位“领取”并点击，点击后重新观察并核对“获得奖励”；`paid` 不输入，核对列表后完成。魂玉模板在测试运行时从私人帧裁出，阈值 0.45 只是暂定值，来源是同一天、同一设备的样本。脚本设备只有在点击点落在免费按钮内时，才切换到奖励帧。

需要 `-PincludePrivateReplayFrames=true -PincludeOcrIntegrationModels=true` 构建，并传 `-e includePrivateReplay true -e allowOcrIntegration true` 运行；默认测试包不含这些帧，运行后应卸载测试包。

专用 API36 模拟器上 5 项通过，全部走 CPU 防护分支：图像证据不可用，程序停在无法确认，没有输入，也没有写入动作意图。完整流程（点击一次并核对奖励、付费状态不输入、点击未产生奖励时保持未知、无关画面不被猜测）需要在真机上运行，尚待手机连接。奖励弹窗的关闭和后续“已领取”核对不在本草稿内：关闭需要点击空白处或返回键，现有动作还不支持。
