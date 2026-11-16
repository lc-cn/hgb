# 技术选型、需求调研与可行性结论

版本：1.0-draft；核验日期：2026-11-15。依据[PRD v2.4](../PRD.md)与[系统架构评审稿](system-architecture.md)。本稿给出推荐路线和成立条件，不代表已经集成、通过实机验证或获准实施。本轮没有安装依赖、编译APK、连接游戏或调用真实模型／通知。

## 1. 当前结论

推荐 **Kotlin原生Android App＋唯一任务执行器＋版本化JSON步骤与资源＋本地识别＋可选云视觉**。Windows运行同一产品的安卓APK，通过模拟器验证，不额外建设Windows客户端或要求PC常连控制。

App管理、表单、版本、计划、队列和历史有成熟组件可用；游戏截图输入与具体任务属于有条件可行；**陌生主题泛化、未经适配的新活动自主完成、Root登录态可靠恢复和24小时无人值守，目前没有本项目实测证据，不能作为已成立的承诺。**

这不是“都待定”：下面区分可选定的通用技术、需对照测试的引擎，以及会影响整个方向的能力门槛。正式构建时再锁依赖版本；查到某示例版本，不等于该版本适合本工程。

## 2. 需求调研做到哪一步

| 调研层次 | 已有结果 | 仍缺什么 |
| --- | --- | --- |
| 用户需求 | 平台、云任务、跨主题、模型可选、计划、版本、通知、代理、账号与执行器语义已整理到PRD | 游戏任务逐项参数与未确认的预算／日程策略，不用开发者自行猜测 |
| 同类项目与故障 | 查过OAS、MaaYYs、assttyys的README、部分issue及源码，记录皮肤／分辨率、误判完成、重复操作、卡住等历史经验 | 未运行这些项目；issue不是本项目复现，也不保证当前上游仍有同一缺陷 |
| 引擎与平台 | 本轮查Android官方文档和运行时实际源码，比较原生、Maa、Auto.js系；逐项记录权限与集成限制 | 尚无目标Android设备上的工程验证 |
| 业务任务规格 | 有22项任务目录夹具，已纳入永久勾玉卡、商店、御魂等明确方向 | 不是已验证的完整游戏清单。入口、资格、刷新规则、真实消耗和完成证据仍需当前版本采样 |

例如永久勾玉卡还需核对拥有资格／已领取／不可领取三种实际画面；周三／周六商店需核对开放时区、商品身份、币种和限额；御魂整理需先定用户允许的处置方式，再验证对象识别及保留条件。不能把原型里的示例值当游戏事实。

需求逐项结论见[F01–F21需求与可行性矩阵](../research/requirements-feasibility-matrix.md)；历史故障证据见[开源调研](../research/onmyoji-automation-survey.md)。

## 3. 推荐技术栈

| 领域 | 推荐选择 | 选择理由与成立条件 |
| --- | --- | --- |
| 应用与界面 | Kotlin＋Jetpack Compose＋Material 3 | Android是唯一产品平台，原生处理授权、窗口与横竖屏；不引入WebView承载正式业务界面 |
| 状态与并发 | coroutines＋Flow；一个Executor串行处理控制命令 | UI和浮层观察同一状态；耗时识别／网络移出控制路径，不引入通用Agent框架来管理游戏任务 |
| 任务执行 | 轻量Kotlin步骤／动作解释器；开发者编写JSON任务schema、步骤动作编排与资源引用，kotlinx.serialization解析 | App实现并更新动作能力；用户拉取云任务、填写配置并执行或组合计划。流程更新走任务版本，resume(mode)按步骤恢复或重做当前任务，不恢复调用栈 |
| App内任务制作与调试 | 动作合同驱动参数表单、步骤列表、schema编辑与JSON生成；草稿快照复用Executor试跑 | 简化为选动作、填参数、试跑，查看输入输出及错误位置；不另建调试执行器，不必先上传才能调试 |
| 配置与记录 | Room／SQLite；DataStore仅简单偏好；私有文件放模型、图片与包 | 队列去重、步骤结果和必要业务事实需事务；不把所有状态塞到偏好文件或通用事件溯源平台 |
| 截图与输入 | 首轮验证AccessibilityService截图＋dispatchGesture；MediaProjection为对照路线 | 首轮实验建议API30+，API34再比较窗口截图；最终最低版本与支持设备由实验确定，不隐含改成Root方案 |
| 游戏内浮层 | WindowManager原生小窗口＋ComposeView | 复用原生控件；单独管理窗口生命周期，限制实际触摸区域，验证截图污染和触摸遮挡 |
| 本地文字识别 | 优先评估PaddleOCR官方Android SDK／ONNX Runtime路线；ML Kit中文bundled作对照 | 前者便于把模型与预处理作为可核验资源管理；不是已证明游戏准确。用同一数据比较后只选一个生产后端 |
| 图形与布局识别 | OpenCV的稳定图标／局部特征＋OCR文字与相对布局 | 不用单整页模板绑定庭院主题；Maa纯识别接口作为可复用后端一并评估，不重复造一套通用视觉平台 |
| HTTP与云视觉 | OkHttp＋kotlinx.serialization；服务商协议适配 | 模型／任务源／外部HTTP通知各用已保存路由；直接接REST契约，保持取消与输入控制分开 |
| 通知 | 本机NotificationManager；机器人／Bark／Webhook走HTTP适配；邮件比较Angus Mail Android实现 | SMTP不是HTTP，不能直接套OkHttp；真实鉴权、业务响应和代理必须逐项测试 |
| 凭据与快照 | Android Keystore保护加密键，私有密文仓储，业务库只存引用 | API Key、代理、渠道及登录态按归属隔离；Root专用登录态是独立能力，不成为普通任务的隐含前提 |
| 开发者授权与任务发布 | GitHub OAuth授权＋指定仓库权限；GitHub Releases／assets分发，优先验证GitHub App用户令牌的Device Flow | 复用GitHub鉴权与仓库权限，无须自建发布后端；登录不等于具备写权，App单次上传完成附件与发布，不另增人工审批 |

上表是技术推荐，尚未安装或锁版本。Compose／协程／Room等属于可直接采用的基础；OCR后端、输入承载、邮件／SOCKS认证和Root恢复必须经对应实验再冻结。

基础合同：[Compose](https://developer.android.com/compose)、[协程](https://kotlinlang.org/docs/coroutines-overview.html)、[Flow](https://kotlinlang.org/docs/flow.html)、[Room](https://developer.android.com/training/data-storage/room)、[DataStore](https://developer.android.com/topic/libraries/architecture/datastore)、[JSON序列化](https://kotlinlang.org/docs/serialization.html)、[Keystore](https://developer.android.com/privacy-and-security/keystore)。其中JSON序列化是官方标为稳定的格式；本文不依据文档示例固定Kotlin／插件／依赖的组合版本。

## 4. 为什么不直接套一个现成项目

| 路线 | 可复用价值 | 对本需求的缺口 | 当前建议 |
| --- | --- | --- | --- |
| 自有Kotlin容器与轻量步骤执行器 | 完整控制任务版本、原生UI、账号和单一执行器语义 | 需要实现平台适配与任务解释；不因此自研OCR／图像算法 | 主路线，复用成熟识别与存储组件 |
| MaaFramework | pipeline、资源加载、OCR／图形识别，已有Android与纯识别接口 | Android Native控制仍需宿主提供平台能力；不能把框架接口存在当授权／生命周期已解决；取消与步骤恢复需适配 | 优先比较纯识别后端；是否承接更多执行能力以对照实验为依据 |
| Maa生态Android宿主 | 提供Kotlin／Compose及本机运行集成参考 | 当前宿主资源打包与热更新情况、特权环境前提要分别查；不等于底层框架不能动态加载资源 | 参考宿主接合方式，不直接fork后承诺全部需求已满足 |
| Auto.js／AutoJs6／AutoX系 | 本机脚本、界面与截图输入生态，旧任务较多 | 引擎版本、打包、脚本权限、停止方式、许可／声明需要逐仓核对，不能当统一产品；脚本任务也不自动解决主题泛化 | 保留对照，不作为未经验证的默认壳 |
| OAS／MaaYYs任务实现 | 游戏流程、失败案例、素材和维护经验 | PC产品不能直接成为Android执行器，任务存在不等于本项目可靠 | 研究代表任务与回归样本，复用代码／资源前逐项核对来源 |

本轮源码核验见[Android运行时比较](../research/native-runtime-feasibility.md)。MaaFramework的Resource加载能力与某Android宿主尚不支持热更新，是两个层面的事实；不应据后者否定底层框架。只复用纯识别时，不让它另持有游戏业务队列或输入权。

## 5. 视觉选型与跨主题的实际难点

[PaddleOCR官方Android部署](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/inference_deployment/cross_platform/android_deployment.md)提供独立SDK／AAR及ONNX推理示例，说明存在Android集成路径；示例依赖和测速不是本项目兼容或性能证据。[ONNX Java接口](https://onnxruntime.ai/docs/api/java/ai/onnxruntime/OrtEnvironment.html)支持从文件路径或字节创建session，为模型资源管理提供基础，但本项目仍需适配资源路径、算子兼容、前后处理及资源释放。

[ML Kit中文bundled](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)可随APK带模型，适合快速离线识别基线。同页摘要与正文最低API存在差异，实施以所选artifact合同核验；首轮平台实验API30+并非由OCR最低API推导。其[官方隐私说明](https://developers.google.com/ml-kit/terms)区分本地输入处理与SDK指标通信，故bundled不等于整个SDK零网络，也不能默认认为内部请求经过本项目代理。

[OpenCV官方Android集成](https://docs.opencv.org/4.x/d5/df8/tutorial_dev_with_OCV_on_Android.html)提供SDK／Maven接入，但它不会自动理解游戏主题。技术推断是：文字、图形、相对布局和当前步骤可减少对一张整页模板的依赖；是否能跨未知主题，必须用未参与适配的主题样本和真实任务验证。

对照至少覆盖文字／关键数字、目标定位、实际任务完成、未知输出与误操作，区分庭院／商店／御魂等界面。不能只测OCR总准确率，不能把同图缩放当新主题。先比较Paddle＋OpenCV、ML Kit基线及Maa纯识别；再据错误、延迟、内存、包体、模型维护和网络路径冻结后端，不把所有引擎一起堆进APK。

新活动自主理解另做实验：选没有编写专用流程的活动，记录规则理解、动作提案、资源变化、完整完成情况，以及所有人工帮助与专用修补。云模型返回JSON或读懂规则不代表能够完成活动；模型仅向当前步骤提案，不能扩大消费或直接输入。服务预设沿用[模型预设调研](model-presets.md)，具体模型和协议实施前重新核验，不在本稿用旧型号保证效果。

## 6. 平台、代理与常驻约束

[AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)提供公开截图／手势合同，但节流、安全窗口、OEM、模拟器和授权变化都会影响可用性。[MediaProjection](https://developer.android.com/media/grow/media-projection)有会话授权与结束约束，不是永久截图许可。框架能加载不代表游戏画面能采到，手势回调成功也不代表领取已生效。

浮层须验证Compose的窗口生命周期和实际触摸范围，不能用全屏透明层无条件穿透。游戏关闭、开机自启、后台FGS、锁屏、用户强停分别按系统合同处理；不能把自启推导为自动解锁、启动游戏或恢复队列。普通安装也不能据截图／无障碍权限直接读另一App私有登录态。详见[Android平台与网络核验](../research/android-platform-feasibility.md)和[账号边界](game-lifecycle-account-boundaries.md)。

代理的三个scope按已保存配置独立路由。OkHttp的HTTP代理认证不能证明SOCKS用户名密码也支持；Android底层SOCKS、DNS与java.net.Authenticator涉及不同路径，必须证明不会跨目标取错秘密。Angus的SMTP代理路径另测，HTTP测试成功不能代替邮件。未选范围不改路由，失败不自行直连或跨服务重发。

独立DeviceBridge是为卡死撤销提出的实现建议，不是第二执行器。其进程、私有IPC和系统组件归属须经真实撤销测试决定；若同进程已满足代表故障可用较小部署，若无法处理不可中断阻塞则不能声称支持该场景。

24小时结论需同时记录真实任务结果、暂停／恢复、授权与游戏中断、Java／native内存、磁盘、CPU／温度、请求量及错误；仅进程活着不算。若目标环境需常亮、充电或特定电源设置，应报告条件并另行确定，不能用默认改系统设置掩盖限制。分发也另议：Play对Accessibility自主操作的限制与APK可侧载是不同问题，不能把独立APK技术路线说成已满足商店上架。[Play官方政策](https://support.google.com/googleplay/android-developer/answer/16558241)

## 7. 应先做的可行性实验

以下是风险验证顺序，不重新安排功能分期，也不授权真实消费或消息发送。

| 门槛 | 最小验证 | 达成后才能说什么 | 不达成时怎么办 |
| --- | --- | --- | --- |
| P0 平台闭环 | 目标手机与专用模拟器各取得游戏画面，受控输入，验证旋转、悬浮与撤权 | 独立APK有可用的截图输入路线 | 比较MediaProjection／不同支持版本；需要特权前先报告差异，不默默改为PC常连 |
| P1 执行器 | pause后两种resume、输入在途、卡死移除与旧结果晚到 | 控制模型和真实输入静默成立 | 比较较小部署与独立薄桥；超时不能冒充静默，不重加任务／计划暂停机 |
| P2 本地跨主题 | 同一代表领取任务在已适配与留出主题执行；关键字／数字／目标分别核对；无模型条件测试 | 本地子集具备明确主题覆盖 | 更换识别后端或增加语义证据；不能退回只支持一套主题却称目标已满足 |
| P3 任务制作与版本 | 在App制作／调试任务并生成JSON，上传即发布；同一APK拉取两个完整任务集，验证引用、删除与中断 | 制作路径足够简单，常规更新独立于APK，上传与分发闭环成立 | 调整编辑器／步骤原语／发布协议；缺动作明确升级，不能用手工改外部文件代替App制作验收 |
| P4 代表业务 | 领取、兑换、强化／整理按已明确规则验证完成、未生效与未知；核对对象与消耗 | 各代表任务具备实际正确性证据 | 缺规则先补规格；不能靠放宽保留条件、重试消费或自动购买通过 |
| P4a 登录态 | 用户自有账号在同一Root环境A→B→A，含停止、提交中断、错误身份与回退 | 该环境下快照恢复有具体证据 | 将该环境能力判为不成立，调整适配或提出人工登录替代供评审，不把文件复制成功当登录成功 |
| P5 云视觉与新活动 | 先验图片协议／坐标／取消，再验未写专用流程的完整活动目标，记录帮助与成本 | 能力达到所测活动与范围 | 区分协议接入成功和自主完成；不得承诺任意新活动全自动 |
| P6 通知与代理 | 六类渠道、三域路由、HTTP／SOCKS5认证／DNS／TLS／SMTP分别实测 | 所测渠道与代理组合可用 | 独立修传输适配，不静默直连、换服务或推导其他组合也成功 |
| P7 长期运行 | 支持环境分别做真实24小时运行，覆盖任务切换、暂停恢复、故障和资源趋势 | 有条件的长期运行结论 | 定位生命周期、识别、内存或网络瓶颈；不得以进程存活代替任务完成 |

编号与需求矩阵统一。P0／P1先建立平台与控制链，之后准备P2／P3；P4a可独立验证登录态，失败不否定普通已登录任务；P5基于真实步骤／观察闭环，P6独立核验协议，P7汇总长期表现。实验输出应列环境、容器／任务版本、步骤、预期与实际结果、失败分类、证据和恢复方式，不包含凭据。详细清单见[架构验证路线](architecture-validation-plan.md)。

## 8. 本次做了什么，没做什么

已完成资料与源码级比较、F01–F21需求分级、推荐技术栈及关键门槛；没有实际构建、设备、截图样本跑分、登录态恢复或长期运行。旧72个内存情境与512项浏览器检查只验证旧交互原型，不证明最新执行器模式或以上平台能力。

后续开发准备复核纠正了环境结论：本机已有可直接执行的 Homebrew JDK 17 和 Android SDK，ADB 37.0.1 可运行；默认 PATH／macOS Java 注册未配置，不能据此判断未安装。具体安装组件和验证边界见[环境记录](../development/environment-audit.json)。当前仍没有 Android／Gradle 工程或目标游戏实测；SDK 组件存在不代表所选构建版本组合已验证。本轮未安装环境、启动模拟器或连接设备。

完整游戏任务行为规格尚未采集完；不再重复询问已经确认的队列、恢复动作、通知和版本交互。待补资料集中在目标游戏／渠道／设备及代表任务证据。通用技术推荐可先评审，业务正确性与高不确定能力须用上表实验形成结论。
