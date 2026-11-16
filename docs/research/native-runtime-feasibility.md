# Android 独立运行时：技术选型与可行性核验

核验日期：2026-11-15。只研究公开的一手文档和少量源码；未安装 APK、连接 Android／模拟器、运行游戏、编译 native 依赖或部署服务。本次仅新增本文，未修改既有设计或代码。文中的“支持”区分接口存在、上游实现、目标设备实测；前两项均不能代替第三项。

## 需求基线与建议

本轮基线来自用户已确认范围：Android 独立 APK，同一 APK 用于 Windows 安卓模拟器；任务版本从云端拉取；一个 Executor 拥有执行权；Task 由步骤组成，Plan 为任务集合与定时；pause 暂停执行器；resume 可继续未完成步骤或重做当前任务，不做复杂断点。用户接受 Root 专用快照，不等于整个 App 默认要求 Root。本文不重新定义这些产品行为。

**建议以自有 Kotlin 宿主、轻量步骤 Executor 和 Android 平台桥作为主方案；MaaFramework 作为本地识别组件值得优先做接入验证，避免从零写整套视觉引擎。** 依据是：平台权限和生命周期无论如何需要宿主拥有；MaaFramework 提供独立识别调用，控制器可以不绑定；步骤级暂停／继续和云版本选择由本项目拥有，能避免直接借用另一套 Tasker 调度语义。[Maa Tasker 源码][M-tasker-cpp]、[识别 API][M-tasker]

上述“优先验证”只是在本报告的三个运行时比较范围内提出的 PoC 建议，**不是所有 OCR 技术的最终排序**。本轮主技术报告以 PaddleOCR 官方 SDK＋OpenCV 作为首个比较基线，Maa 仅识别路径作为重点备选；需在跨 OCR 对照和 native 集成验证后再冻结。本文没有调查 PaddleOCR SDK，不能替主报告预先下结论，也不能把 Maa 的用途声明与集成门槛误写为“不具备技术能力”。

这是一项**有条件推荐**：必须先验证图像传递、ABI、识别耗时与取消边界。MaaFramework 当前 README 用途声明与目标场景之间的关系也须先澄清，不能只凭 LGPL 宣称已获全部用途授权。[用途声明][M-readme]。若这些条件不能满足，保留直接接入可用 OCR／图像库的选择；本文未对另一套具体 OCR 库做选型，不虚构已确定替代项。

**备选：Kotlin 宿主集成 MaaFramework 完整 pipeline。** 可复用识别、动作及流程 DSL，但本项目必须始终是唯一任务权威；不能让 MaaTasker 的队列与 Plan 各自独立调度、自动重跑。需明确产品步骤与 pipeline 节点的对应和停止边界。[Tasker 接口][M-tasker]、[Tasker 实现][M-tasker-cpp]

**次级备选：AutoJs6 的 APK 脚本运行时。** APK 打包、本地 JS、截图和无障碍输入已有实现，适合快速验证游戏流程；但插件打包、Rhino 语义、脚本停止和本项目步骤契约仍需集成，不是直接替换 Executor。**不建议**直接选旧 Auto.js／停止重点维护的 AutoX 备份分支作为新项目基线，也不建议把现成 MaaFwApp 原封不动用于要求云拉任务版本的产品。[AutoJs6 README][A-readme]、[AutoX 备份 README][X-backup]、[MaaFwApp 接入][F-integration]

## 核验版本与证据等级

以下 commit 是公开 GitHub API 在本次查询取得的版本；固定链接用于复核，避免把不同缓存版本拼成同一份代码。索引中保留 `main`／`master` 的文件均为 **2026-11-15 动态核验**，不宣称它们属于表中的固定 commit 或某个 release；具体固定源码只以包含完整提交号的链接为准。

| 项目 | 固定基线／说明 | 本次读到的关键内容 |
| --- | --- | --- |
| MaaFramework | `218bfabcc502bc5e152af36bceebe016792d9686`，提交日期 2026-11-14 | 固定核验 C API、Tasker 实现；Android Native 外部库、构建 workflow、控制方式文档及 LICENSE 为本日动态核验。 |
| MaaFwApp | `f29a0b0d5e5ebdc845368f0e98e8104911765c1a` | 实际读取 native bridge、采集、输入、Kotlin MaaRunner、PrimaryDisplayManager、Gradle；不是只看 README。 |
| AutoJs6 | `b95b98b339c56ab12ba20b279d2b998eb6e2778c`，提交日期 2026-11-03 | README、Gradle ABI／inrt、引擎执行／停止源码；另读截图实现和 LICENSE。 |
| assttyys_autojs | `7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc`，沿用已有调研基线 | 本次读取 helperBridge 的屏幕坐标适配；当前 README 核对开发分辨率约束。未用历史 issue 推断当前缺陷。 |
| AutoX `aiselp/AutoX` | `master` 文档，2026-11-15 核验；该分支 commit API 查询失败 | 仅核对 README 和 LICENSE.md，不把能力表当本机验证。 |

网页工具出现过缓存缺失；随后通过公开 raw URL 定点取得 MaaFwApp 和关键 Maa C API 源码。未取得的内容下文明确列为未知。

## 一、独立 APK 的平台与权限边界

### 自有 Kotlin 平台桥

无 Root 路径在 Android 官方 API 上成立：`AccessibilityService.dispatchGesture` 从 API 24 提供，需要声明 `canPerformGestures` 并启用服务，提供手势完成／取消回调；这是输入投递结果，不是游戏业务成功证明。[Android 输入契约][D-accessibility]

截图有两条需分别评估的标准路径：无障碍 `takeScreenshot` 从 API 30 提供、需 `canTakeScreenshot`，可能返回截屏间隔过短等错误；MediaProjection 需用户授权，Android 14+ 每次会话授权、token 单次使用并有前台服务声明要求。不能承诺 App 被杀或重启后静默恢复原授权。Android 15+ 对从开机广播启动 mediaProjection 前台服务还有限制。[无障碍截图][D-accessibility]、[MediaProjection][D-projection]、[Android 15 前台服务][D-fgs]

因此“可独立运行”不等于“可无人干预跨所有设备重启”。Android 版本、厂商后台策略、锁屏／旋转、目标游戏渲染、实际截图频率需要试验。全新 Kotlin 宿主需要自己完成授权 UI、前台生命周期、截图资源释放、屏幕坐标适配及输入回执；Kotlin 本身不自动提供这些能力。

### MaaFramework Android Native 与 MaaFwApp 的不同职责

MaaFramework 的 Android Native 控制器通过配置里的 `library_path` 加载外部库。本日读取的 `AndroidExternalLib.h` 要求 `GetLockedPixels`、`UnlockPixels`、`DispatchInputMessage` 三个符号；管理器 `connect()` 加载该库，截图取外部 frame，输入交给外部 dispatch。**它不是已经替普通 APK 申请 MediaProjection、启用无障碍或获得注入权限的成品服务。** [外部 ABI][M-native-abi]、[控制管理器][M-native-manager]

MaaFwApp 展示了真实的本机集成：Android 内运行 Shizuku 或 Root 特权进程，经 Binder 接收运行请求；无须 PC 常连，native controller 不经过 ADB 传图或发点击。读取到的实现链为：[MaaRunner][F-runner] → native controller／`libbridge.so` → [bridge JNI 注册][F-bridge]；[PrimaryDisplayManager][F-primary] 经 DisplayManager／SurfaceControl 把屏幕送到 Surface；[bridge_capture.cpp][F-capture] 的 AImageReader 收取 HardwareBuffer；[frame buffer][F-frame] 导出 Maa 要求的 GetLockedPixels；[InputControlUtils.java][F-input] 调用封装的 InputManager.injectInputEvent。

这条路径使用特权进程和隐藏平台接口，不能直接当作无 Root 普通应用的公开 API 示例。MaaFwApp README 明确要求 Shizuku／Root 二选一。Shizuku 官方说明：Android 11+ 可通过无线调试在手机本机启动，不需电脑，但重启后要再执行启动步骤；Android 10 及以下的非 Root 启动要借助电脑。**启动时需要电脑与运行时需电脑常连是不同要求。** [MaaFwApp README][F-readme]、[Shizuku 官方手册][D-shizuku]

本项目可选择无障碍／MediaProjection 平台桥，或在用户单独授权设备上采用 Shizuku／Root 适配。本文不把后一条默认化，更不承诺所有 Android／模拟器都支持其虚拟屏或隐藏 API。

## 二、JNI、ABI 和 Windows 模拟器

MaaFramework 构建 workflow 有 Android aarch64 与 x86_64 产物；MaaFwApp 接入文档默认 APK 包含 `arm64-v8a`、`x86_64`，agent 有 native 依赖时也必须提供匹配的 ABI。其 setup 脚本实际把 Maa `.so` 铺到 `app/src/main/jniLibs/<abi>`，要求核心库及 `libMaaAndroidNativeControlUnit.so`，并检查最低核心版本。[Maa Android workflow][M-build]、[打包接入][F-integration]、[setup 脚本][F-setup]

MaaFwApp 的 Gradle 实际使用 JNA AAR（提供 native 分发库）调用 Maa C API，同时用 `externalNativeBuild` 编译 bridge；bridge 则使用 JNI 注册 Java 方法。**所以不是“已有官方 Kotlin Maven SDK，加入一个依赖即可全部使用”**。本次未核实存在适合本项目直接使用的官方稳定 Kotlin SDK；可证明的是 C API 与该社区宿主的 JNA＋JNI 集成。[Gradle][F-gradle]、[bridge][F-bridge]

AutoJs6 固定 Gradle 有 app／inrt 变体；ABI 拆分包含 arm64-v8a、armeabi-v7a、x86_64、x86，runtime-kit 也列出相应变体。[AutoJs6 Gradle][A-gradle]。这只能说明构建配置存在；实际 APK、OCR／OpenCV 插件、模拟器 Android 版本和 CPU 翻译层是否一致仍需验证。AutoX v7 README 当时只声明提供 arm64-v8a 和 mini-arm64-v8a APK，不能据此保证 x86 Windows 模拟器原生兼容。[AutoX README][X-readme]

同一 Android APK 在 Windows 安卓模拟器内运行是可评估方向，**不是 Windows 原生 EXE 支持**。是否使用 ARM 翻译不应作为未测试的兼容承诺。

## 三、云任务版本与资源热更新

MaaFramework 固定头文件确有 `MaaResourcePostBundle(path)`、`PostPipeline(path)`、`PostImage(path)`、`PostOcrModel(path)` 和 `OverridePipeline(json)`；可从宿主给出的目录加载资源／JSON。这提供云版本下载后的本地载入能力，**框架底层并非“不支持动态资源”。** [资源 C API][M-resource]

但它们不是本项目要求的完整云版本服务：签名／完整性验证、引擎兼容校验、下载失败保留旧版、运行绑定版本、切换及回退，要由宿主做。本文未查到这些 API 对并发执行中换整套 bundle 的事务／一致性保证，因此不建议把“能加载文件”表述为“任意运行时原子热切换”。绑定运行版本、只在已批准切换时机换资源是宿主职责，不是上游已验证行为。

**MaaFwApp 当前产品层则明确没有热资源更新。** INTEGRATION 的资源约定表说明资源构建期打入 APK，替换资源需重新出包；Mirror 酱暂不支持。这是该宿主的取舍，不是 MaaFramework 引擎限制。[接入文档][F-integration]。其 MaaRunner 本身会根据不同资源路径重建 resource 并调用 `MaaResourcePostBundle`，也证明运行层可接收路径，但不能据此说它的 UI／产品已有云拉资源功能。[MaaRunner][F-runner]

AutoJs6 `engines.execScript` 和 `execScriptFile` 接受代码／文件，其 Rhino 引擎会 compileReader 后执行；本地 JSON 和 JS 作为任务包在技术上能被读取并解释。**自动从云拉包、校验版本、替换并安全执行并不是这两个 API 自动提供的承诺。** 通用脚本可调用更多宿主能力，若用它解释云任务，本项目仍需要限制 Task 能力、管理唯一执行权。[Engines API 实现][A-engines]、[Rhino 实现][A-rhino]

## 四、识别、OCR 与云视觉扩展

### Maa 可只做识别，不掌控输入

固定接口 `MaaTaskerPostRecognition(tasker, reco_type, reco_param, image)` 直接接受宿主提供的图像。`Tasker::inited()` 明确要求有效 resource，controller 为可选；`post_recognition()` 构造 RecognitionTask，并未在此创建控制器。**因此存在有源码依据的仅识别集成路径**：平台桥截图 → Maa 识别请求 → 结果返回 → 本项目 Executor 决定输入；不绑定 controller，不调用 PostAction。[识别 API][M-tasker]、[Tasker 源码][M-tasker-cpp]

它内部仍有异步工作队列，但这是识别组件实现细节，不应另设业务调度权。Maa 的 image buffer 传递、OCR 模型格式、Android CPU 耗时与内存、中文准确率均尚未实测。可以复用识别／OCR而保留轻量 Executor，不能把“controller 可选”推导成完整 pipeline 的任意动作也无条件可用。

MaaResource 提供自定义识别／动作注册；`MaaCustomRecognitionCallback` 收到 image、ROI、参数，返回 box／detail。这是接云视觉的扩展点：宿主或 agent 可发网络请求并把结构化结果回传。框架不自动解决模型授权、取消、时延、结果未知或坐标校验。[自定义注册][M-resource]、[回调 ABI][M-def]

AutoJs6 当前 README／版本说明将 OCR、OpenCV 等功能拆为独立插件，并要求 APK 打包时选择所需 OpenCV 插件。不能沿用旧资料假设“一个 APK 自带所有 OCR”。自定义 Java／HTTP 能力可接云视觉，但本次没有验证一个确定的云视觉适配器，也不能从支持 HTTP 推出自主理解所有新活动。[AutoJs6 README][A-readme]

自有 Kotlin 可直接实现网络模型调用、协议和取消，并接本地识别库；这属于待实现能力，不是现有本项目功能。“尽量减少活动人工适配”需要专门用未编写过流程的新活动测成功率、资源消耗和错误操作，任何上述引擎名称本身都不能证明该能力。

## 五、暂停、取消与恢复的真实边界

MaaTasker 公共接口有 `PostStop`／`Stopping`／`Wait`；本次读取的头文件没有 `Pause`／`Resume`。实现先置 stop 标志、清 pending 队列，再向当前任务、resource 和 controller 发 stop。它是协作停止，不是冻结任意 C++／网络回调再无损续跑，也不回滚已经发生的游戏操作。[Tasker C API][M-tasker]、[Tasker 实现][M-tasker-cpp]

AutoJs6 `ScriptEngine` 有 execute、forceStop、destroy；固定 Rhino 实现 forceStop 中断线程并取消脚本 jobs，没有本项目的“继续未完成步骤／重做当前任务”契约。Rhino 支持 continuations 的内部执行分支也不能被当作持久化业务断点接口。[ScriptEngine][A-engine]、[Rhino][A-rhino]

因此本项目两种 resume 模式由**同一 Executor**保留当前 Task 与未完成步骤身份、重新观察现场再执行；这是本项目已有需求的实现职责，不需要引入复杂 VM 断点。到底能在手势、截图、OCR、网络请求中的哪个位置确认暂停，需要由相应适配器报告，不应从 `stop()` 名字猜测。未确认动作是否生效时仍需保留未知结果，不能通过“重做当前任务”自动解释为操作可安全重复。

## 六、横向比较

| 维度 | 自有 Kotlin 宿主＋轻量 Executor | Kotlin＋Maa 完整 pipeline | AutoJs6／本地脚本运行时 |
| --- | --- | --- | --- |
| 独立 APK | 标准 Android 能力；全部平台桥自己集成 | 可行；MaaFwApp 源码证明本机方案，宿主与权限仍需做 | 已有 APK 打包／inrt；模板与插件要锁版 |
| 不默认 Root | 可走无障碍／MediaProjection，但会话与保活有限制 | native controller 依赖外部控制库；可自建普通权限适配，MaaFwApp 本身要求 Shizuku／Root | 无障碍与截图路径已存在；Root 是增强能力，不应默认强制 |
| 云拉 Task 版本 | 契约可精确围绕产品 Task 实现，需写版本管理 | C API 支持目录／JSON 加载；云管理需宿主补；不能照搬 MaaFwApp 的 APK 资源绑定 | 本地 JS／JSON可运行，云包与权限约束仍需实现 |
| 本地识别 | 不从零造轮子；Maa 仅识别是已核实备选 | 强复用 pipeline／OCR／自定义扩展 | 识别和 OCR 走引擎／插件，要验 APK 中实际组件 |
| 唯一 Executor／步骤语义 | 最容易保持本项目 Task、Plan、pause／resume | 要清楚隔离 pipeline 内部流转和产品调度 | 引擎可多开，需约束为唯一拥有执行权；脚本 stop 不等业务 pause |
| 主要成本 | Android 生命周期＋步骤与版本协议＋识别桥 | 前项成本加 C/JNA/JNI、pipeline 映射及 native ABI | 运行时／模板／插件维护、Rhino API 迁移和脚本沙箱／状态约束 |
| 推荐等级 | **主方案方向，先验证平台桥和识别接入** | **备选，须证明复用收益超过语义映射成本** | **流程研究／原型备选；未验证前不作最终宿主基线** |

## 七、许可证与用途声明分开记录

这是来源核验，不作合法性结论；不能由许可证名称推导游戏运营方许可或跳过 README 的明确用途表述。

| 来源 | 许可证文件／声明 | README 用途或其他限制 | 选型影响 |
| --- | --- | --- | --- |
| MaaFramework | LICENSE 为 LGPL-3.0。[LICENSE][M-license] | README 预期为自动化黑盒测试；另写明禁止破坏／绕过正常功能和开发分发违反第三方条款的游戏自动化等。[README][M-readme] | 不能只写“LGPL 可用”就声称本场景已获授权；需澄清用途声明与实际场景。 |
| MaaFwApp | README 标明 AGPL-3.0，指向 LICENSE。[README][F-readme] | 未在本次读到单独游戏用途许可；依赖 MaaFramework、Shizuku。 | 不能因是示例宿主就直接复制进任意许可的产品；要独立评估复用方式及依赖。 |
| AutoJs6 | LICENSE 文件为 MPL-2.0。[LICENSE][A-license] | README 介绍 Android JS 自动化与 APK 打包；本次未检出独立的游戏用途授权。 | 不因 MPL 名称推断所有历史来源、插件／模板许可都相同；分发前需逐依赖核对。 |
| AutoX `aiselp/AutoX` | LICENSE.md 为 MPL-2.0 并附非商业限制。[LICENSE][X-license] | README 又称 GPL-v2，并要求满足 MPL＋非商业及 GPL-v2。[README][X-readme] | **文档与许可表述冲突，不能简化成单一 GPL 或 MPL；纳入基线前需澄清。** |
| 原 Auto.js | 当前 README 表示 Pro 不再开源、旧源码已删除；并列 MPL-2.0＋非商业限制。[README][O-readme] | 以学习与效率提升平台定位。 | 不能把旧 Auto.js、Auto.js Pro 与所有分支当同一个可复用开源 SDK。 |
| assttyys_autojs | README 末尾 MIT。[README][S-readme] | README 为学习用途、勿用于非法途径；且基于 `@auto.pro` 等外部框架。 | 其业务源码许可不自动覆盖执行运行时及外部库，不能只按 MIT 算整个 APK。 |

## 八、assttyys 能借鉴什么，不能据此承诺什么

当前 README 明确开发分辨率 720×1280，其他分辨率适配依赖缩放和位移，但没有充分测试，并提示异型屏下游戏特殊布局会影响锚点比色。固定 `helperBridge.ts` 确有读取屏幕宽高、横竖交换以及用开发尺寸构造 AnchorGraphicHelper 的实现。这支持**坐标适配仍需面向游戏布局验证**，不能据“有缩放”宣称兼容所有屏幕。[README][S-readme]、[helperBridge][S-helper]

本次读取的上述文件**没有提供足以证明全部庭院主题／皮肤兼容的明确契约**，因此将主题覆盖列为待验证，不编造统一要求默认皮肤。也未用其历史 issue 断言当前缺陷。

## 九、阻断项与最小实机验证

**阻断最终技术定案的事项：**

1. 目标手机与 Windows 模拟器的 Android 版本、ABI、可用权限和常驻条件尚未在设备上核验；不能提前默认 Root 或假设所有模拟器为 ARM。
2. 若选 Maa，须先澄清用途声明、锁定 `.so`／头文件／模型版本，验证 recognition-only 的图像传递与内存性能；还没有 APK 级集成结果。
3. 若选 AutoJs6，须验证实际 inrt 模板、OCR／OpenCV 插件在目标双环境打包可用；不能凭 IDE 能运行就证明独立 APK 可用。
4. 云版本管理、跨步骤暂停与恢复均没有本项目真实运行时；当前文档／模拟器测试不证明 Android native 行为。

**可以回答取舍的最小验证，不等同完整产品开发：**

- 同一 APK 在一台目标 Android 与一台 Windows 安卓模拟器：截图、坐标点按／滑动、横竖屏／遮挡、权限撤销后错误分类；确认没有 PC 控制进程依赖。
- 仅识别实验：宿主取一帧 → Maa OCR／TemplateMatch → 结构化结果，整个实验不绑定控制器；测耗时、CPU、峰值内存、连续调用内存趋势。
- 一个有可验证完成条件的 Task：步骤执行、在截图／OCR／网络／输入边界请求 pause、收到明确暂停回执后停止新输入；分别验证两种 resume，不保存复杂 VM 断点。
- 两个云任务包：下载中断、损坏／不兼容、新版加载失败时保留旧版；运行使用的资源身份一致。真实切换时机遵循已批准产品契约。
- 长时运行及生命周期：后台、锁屏、进程被系统杀、设备重启、Shizuku 失联（仅对选择该后端的设备）。分别记录可自动恢复、需用户授权与未知结果，不能统称“24 小时可靠”。

以上尚未实施。只有这些验证给出证据后，才适合把方向升级成正式技术基线和交付时间估算。

## 一手资料索引

[M-tasker]: https://github.com/MaaXYZ/MaaFramework/blob/218bfabcc502bc5e152af36bceebe016792d9686/include/MaaFramework/Instance/MaaTasker.h
[M-tasker-cpp]: https://github.com/MaaXYZ/MaaFramework/blob/218bfabcc502bc5e152af36bceebe016792d9686/source/MaaFramework/Tasker/Tasker.cpp
[M-resource]: https://github.com/MaaXYZ/MaaFramework/blob/218bfabcc502bc5e152af36bceebe016792d9686/include/MaaFramework/Instance/MaaResource.h
[M-def]: https://github.com/MaaXYZ/MaaFramework/blob/218bfabcc502bc5e152af36bceebe016792d9686/include/MaaFramework/MaaDef.h
[M-native-abi]: https://github.com/MaaXYZ/MaaFramework/blob/main/source/MaaAndroidNativeControlUnit/General/AndroidExternalLib.h
[M-native-manager]: https://github.com/MaaXYZ/MaaFramework/blob/main/source/MaaAndroidNativeControlUnit/Manager/AndroidNativeControlUnitMgr.cpp
[M-build]: https://github.com/MaaXYZ/MaaFramework/blob/main/.github/workflows/build.yml
[M-readme]: https://github.com/MaaXYZ/MaaFramework/blob/main/README.md#声明与许可
[M-license]: https://github.com/MaaXYZ/MaaFramework/blob/main/LICENSE.md
[F-readme]: https://github.com/Aliothmoon/MaaFwApp/blob/main/README.md
[F-integration]: https://github.com/Aliothmoon/MaaFwApp/blob/main/INTEGRATION.md
[F-gradle]: https://github.com/Aliothmoon/MaaFwApp/blob/main/app/build.gradle.kts
[F-setup]: https://github.com/Aliothmoon/MaaFwApp/blob/main/scripts/setup_maa_framework.py
[F-runner]: https://github.com/Aliothmoon/MaaFwApp/blob/f29a0b0d5e5ebdc845368f0e98e8104911765c1a/app/src/main/java/com/aliothmoon/maafw/remote/MaaRunner.kt
[F-bridge]: https://github.com/Aliothmoon/MaaFwApp/blob/f29a0b0d5e5ebdc845368f0e98e8104911765c1a/app/src/main/native/bridge.cpp
[F-primary]: https://github.com/Aliothmoon/MaaFwApp/blob/f29a0b0d5e5ebdc845368f0e98e8104911765c1a/app/src/main/java/com/aliothmoon/maafw/remote/internal/PrimaryDisplayManager.kt
[F-capture]: https://github.com/Aliothmoon/MaaFwApp/blob/f29a0b0d5e5ebdc845368f0e98e8104911765c1a/app/src/main/native/bridge_capture.cpp
[F-frame]: https://github.com/Aliothmoon/MaaFwApp/blob/f29a0b0d5e5ebdc845368f0e98e8104911765c1a/app/src/main/native/bridge_frame_buffer.cpp
[F-input]: https://github.com/Aliothmoon/MaaFwApp/blob/f29a0b0d5e5ebdc845368f0e98e8104911765c1a/app/src/main/java/com/aliothmoon/maafw/bridge/InputControlUtils.java
[A-readme]: https://github.com/SuperMonster003/AutoJs6/blob/b95b98b339c56ab12ba20b279d2b998eb6e2778c/.readme/README-en.md
[A-gradle]: https://github.com/SuperMonster003/AutoJs6/blob/b95b98b339c56ab12ba20b279d2b998eb6e2778c/app/build.gradle.kts
[A-engines]: https://github.com/SuperMonster003/AutoJs6/blob/b95b98b339c56ab12ba20b279d2b998eb6e2778c/app/src/main/java/org/autojs/autojs/runtime/api/modules/engines/Engines.kt
[A-engine]: https://github.com/SuperMonster003/AutoJs6/blob/master/app/src/main/java/org/autojs/autojs/engine/ScriptEngine.java
[A-rhino]: https://github.com/SuperMonster003/AutoJs6/blob/b95b98b339c56ab12ba20b279d2b998eb6e2778c/app/src/main/java/org/autojs/autojs/engine/RhinoJavaScriptEngine.kt
[A-license]: https://github.com/SuperMonster003/AutoJs6/blob/master/LICENSE
[X-backup]: https://github.com/autox-community/AutoX/blob/master/README.md
[X-readme]: https://github.com/aiselp/AutoX/blob/master/README.md
[X-license]: https://github.com/aiselp/AutoX/blob/master/LICENSE.md
[O-readme]: https://github.com/hyb1996/Auto.js/blob/master/README.md
[S-readme]: https://github.com/zzliux/assttyys_autojs/blob/master/README.md
[S-helper]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/helperBridge.ts
[D-accessibility]: https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
[D-projection]: https://developer.android.com/media/grow/media-projection
[D-fgs]: https://developer.android.com/about/versions/15/changes/foreground-service-types
[D-shizuku]: https://shizuku.rikka.app/guide/setup/
