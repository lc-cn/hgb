# Android 独立 APK 平台可行性与选型

核验：2026-11-15。仅一手官方文档和上游源码研究；没有安装依赖、构建 APK、访问 Android 设备/账号、调用模型、发通知或建立代理连接。当前可确认的是平台契约，不能据此宣称阴阳师适配、全天运行或登录态恢复已经可用。相关既有设计：[系统架构](../design/system-architecture.md)、[游戏/登录态边界](../design/game-lifecycle-account-boundaries.md)、[事件通知模块](../design/event-notification-architecture.md)。

## 结论与推荐路线

推荐 **Kotlin + Jetpack Compose 原生 APK**，首轮平台实验选 **Android API 30+**，普通执行采用 **AccessibilityService 截图与手势适配器**，MediaProjection 保留为可选截图适配器。API 30 是无障碍截图能力起点，不是 OCR 的最低版本推断，也暂不把实验下限写成全产品兼容承诺。用户接受的专用 Root 环境只负责强停/登录态快照等特权能力研究，不能让普通执行功能依赖 Root 成功。

核心运行模型按用户最新决定：**唯一 Executor 拥有 pause() / resume(mode=continue|restart_current_task)**；Task 是步骤集合，Plan 是任务集合与定时规则。Task/Plan 不建立第二套暂停机，UI/悬浮球仅发命令和订阅快照。协程只是实现工具，Job.cancel() 不等于“已安全暂停”，gesture 回调也不等于游戏操作业务成功。暂停/恢复仍要由独占输入 dispatcher、代际 fencing、静默证据、新观察和 unknown 核对共同控制。

可直接选定原生语言/UI/状态存储方向；截图设备兼容、悬浮输入、native ABI、SOCKS认证/SMTP和 Root快照必须通过下面 PoC 才冻结具体适配器。没有找到足以证明任意普通 Android 设备开机后无需用户授权即可全天自动操作的官方契约。

## 1. APK 技术栈及职责

| 选择 | 推荐职责 | 不承担什么/备选 | 官方依据 |
| --- | --- | --- | --- |
| Kotlin + Compose | 管理页面、横竖布局、schema参数控件、运行快照与悬浮面板内容 | Compose remember 不是持久执行状态；界面离开不能丢执行游标。复杂原生窗口可局部保留传统 View。 | [Compose状态](https://developer.android.com/develop/ui/compose/state) |
| coroutines | 有明确生命周期的异步截图/识别/网络/存储；IO与计算移出主线程，dispatcher可注入测试 | 不自动保障单一input owner、持久命令幂等或系统保活；不为每个Task建独立暂停域。 | [Android协程实践](https://developer.android.com/kotlin/coroutines/coroutines-best-practices) |
| StateFlow / Flow | Executor/仓库发布只读最新状态，UI按生命周期收集；业务事件由持久记录投影 | SharedFlow/内存流不能替代审计库或可靠通知outbox，订阅者掉线不应丢事实。 | [StateFlow/SharedFlow](https://developer.android.com/kotlin/flow/stateflow-and-sharedflow) |
| Room | 用户任务配置/计划引用、任务包索引与激活版本、RunPackageLock、游标/命令意图、事件/投递记录等关系数据 | 不用一个巨大DataStore对象模拟关系库；Room事务不等于游戏消费与数据库的分布式事务。 | [Room](https://developer.android.com/training/data-storage/room) |
| Typed DataStore | 少量设置：显示偏好、网络scope/route配置引用、普通开关；序列化schema升级 | 大型历史、部分更新、引用完整性放Room；不当作凭据自动加密器。 | [DataStore](https://developer.android.com/topic/libraries/architecture/datastore) |
| Android Keystore | 保存不可导出加密密钥，用于保护本App的模型key、代理凭据、快照密文等 | Keystore不是任意字符串数据库，不代表可读取游戏密钥；密钥丢失/失效后要明确需重新配置。 | [Keystore](https://developer.android.com/privacy-and-security/keystore) |

官方当前文档的依赖示例会更新，本文**不冻结 Kotlin、AGP、Compose、Room、DataStore 版本**。构建 PoC 再从实际Gradle依赖树锁定兼容版本，复查minSdk、KSP/编译器、迁移和打包约束。不得把未安装的文档示例当成本仓库已有依赖。

## 2. 截图与输入：普通 Android 路线

| 适配器 | 已核官方能力 | 重要限制与建议 |
| --- | --- | --- |
| 无障碍 display截图 | takeScreenshot(displayId, executor, callback) 自 API30；需要canTakeScreenshot | 可能返回权限、无效display、内部错误或调用间隔过短；不硬编码无限帧率。管理HardwareBuffer/Bitmap资源与线程，按实际耗时节流。 |
| 无障碍 window截图 | takeScreenshotOfWindow(windowId, executor, callback) 自 API34；官方明确适用于ML视觉理解 | 可避开覆盖目标的accessibility overlay内容，但不能据此宣称任意TYPE_APPLICATION_OVERLAY都被排除；invalidWindow/secureWindow要显式处理。 |
| 无障碍手势 | dispatchGesture 自 API24；需canPerformGestures，提供完成/取消回调 | 新手势会取消用户或其他服务的进行中手势。协调器必须串行发输入；返回true是提交成功，不是领奖/兑换成功。放大/触摸探索可影响坐标。 |

上述契约见 [AccessibilityService API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)（takeScreenshot、takeScreenshotOfWindow、dispatchGesture及错误常量）。建议以截图适配器返回 Observation（时间/窗口/方向/尺度/图像身份）而非裸坐标；发生旋转、窗口切换、授权丢失后停止沿用旧坐标。游戏若为渲染画布，不能假定存在完整可点击无障碍节点；应以实际截图结果验证本地视觉输入。

备选 **MediaProjection**：API21起可采集显示内容，Android14支持用户选择单App窗口；每个投屏会话必须用户同意、token单次使用，重用可能SecurityException。用户停止、锁屏、进程死亡等能使会话失效，onStop后释放显示/Surface并等待重新授权。截图适配器可换，不能伪造永久授权或绕过secure内容。[Media projection官方说明](https://developer.android.com/media/grow/media-projection)

**阻断与退出条件：** 目标游戏在用户选定设备/模拟器上若无法取得可识别截图，或手势无法稳定命中并得到新观察验证，普通自动执行不进入功能开发扩张；先换已授权截图适配器/设备。PoC至少实测API30一组与API34+一组、横竖与系统缩放、悬浮展开/收起、权限撤回、截图节流、secure/error回调、手势取消和人工接管。截图频率、耗电、内存及长时目标由实验测定，不在此捏造可靠率或阈值。

## 3. 常驻、开机与游戏生命周期

Android12对target31+后台启动FGS有限制；开机等例外仍受服务类型/权限限制。target35+不能从BOOT_COMPLETED receiver启动mediaProjection FGS；dataSync/mediaProcessing也不是可随意套用的全天游戏执行类型，其中有总时长约束。无障碍是系统绑定服务，其生命周期也不能推导进程永不退出。是否需要另一合法FGS类型必须按真实用途声明并验证，不伪称普通常驻即有无限后台权。[FGS启动限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)、[Android15类型变化](https://developer.android.com/about/versions/15/changes/foreground-service-types)

App开机偏好、收到启动事件、自身初始化、解锁、截图授权、游戏拉起、恢复队列是不同阶段。Direct Boot的凭据加密存储在用户首次解锁前不可用；自启不能绕过设备解锁或重建投屏授权。[Direct Boot](https://developer.android.com/privacy-and-security/direct-boot) 未验证厂商省电策略或专用模拟器的重启行为，必须显示实际能力/待授权原因。

普通App可以请求公开游戏入口，但后台Activity启动有限制；退出到桌面不是force-stop。FORCE_STOP_PACKAGES为signature|privileged；Android14+第三方killBackgroundProcesses只作用自身。ADB提供授权shell，不自动拥有root；run-as拒绝non-debuggable release包。上述细节与已批准“游戏运行时不得切换账号，先停止才能选择其他快照”的规则见 [登录态边界研究](../design/game-lifecycle-account-boundaries.md)，本轮重新核对了 [AOSP权限声明](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/AndroidManifest.xml)、[run-as固定源码](https://android.googlesource.com/platform/system/core.git/+/c007c43335ebde667c06a56a9706b403d09c9dd6/run-as/run-as.cpp) 和 [ADB官方](https://developer.android.com/tools/adb)。

**Root专用路线独立门槛：** UID沙箱阻止普通App读其他游戏私有登录态；SELinux对root仍有限制，游戏若使用不可导出/硬件绑定Keystore密钥，文件复制不必然恢复登录。Root模式需独立执行桥、游戏已停止与输入静默、原状态备份、分阶段提交/回退、新观察核账号，再开放任务输入；unknown消费证据不因换号清除。未核实私有文件集合、到期规则、同设备重复恢复或跨设备可移植性。[App Sandbox](https://source.android.com/docs/security/app-sandbox)、[SELinux](https://source.android.com/docs/security/features/selinux)、[Keystore](https://developer.android.com/privacy-and-security/keystore)

PoC通过条件是同一专用环境A/B合法登录态多次往返恢复与账号/区服核对、升级/中断/回退/过期/unknown均有可解释结果；不通过就保留普通模式与人工登录，不能把交互模拟写成账号恢复已实现。本文不提供凭据提取命令或猜测文件路径。

## 4. 原生悬浮球与Compose承载

推荐 WindowManager 管理小范围真实窗口，ComposeView承载面板内容。普通悬浮使用TYPE_APPLICATION_OVERLAY与用户SYSTEM_ALERT_WINDOW授权；另一备选是已启用AccessibilityService持有的TYPE_ACCESSIBILITY_OVERLAY，两种窗口类型/权限分别建适配器，不能以无障碍overlay的可信特性替普通overlay作担保。[WindowManager.LayoutParams](https://developer.android.com/reference/android/view/WindowManager.LayoutParams)

ComposeView要求视图树传播LifecycleOwner与SavedStateRegistryOwner；Service不是现成Activity宿主，直接new ComposeView并addView不等于生命周期已正确。推荐悬浮宿主显式拥有/传播这些owner，按添加/显示/隐藏/移除/服务销毁更新生命周期；用ViewModel API时另提供对应ViewModelStoreOwner，销毁时清理composition/收集任务/窗口。选择与宿主匹配的ViewCompositionStrategy，勿复制Fragment示例后假定悬浮已无泄漏。[ComposeView约束](https://developer.android.com/reference/kotlin/androidx/compose/ui/platform/ComposeView.html)、[Compose与View互操作](https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/compose-in-views)

Android12阻止部分不可信遮挡下的穿透触摸，TYPE_APPLICATION_OVERLAY不是可信窗口，FLAG_NOT_TOUCHABLE也不意味着任意全屏遮罩可安全穿透。推荐球和面板按真实边界开窗，收起后移除不用窗口，避免整屏透明可点击层；编辑输入时再管理焦点/键盘，不抢游戏触摸。[Android12触摸遮挡规则](https://developer.android.com/about/versions/12/behavior-changes-all)

PoC应验证系统授权撤回、横竖/多窗口/IME/安全区域、拖拽与点击分离、背后游戏点击、截图是否含面板、removeView后composition和服务引用释放。若Compose宿主生命周期在目标环境无法稳定管理，悬浮部分改传统View是局部备选，不需要重写整个管理App。

## 5. 手机和Windows模拟器的native兼容

Kotlin/Compose可共用APK；native图像/OCR/推理依赖必须按ABI逐项核查。Android NDK支持arm64-v8a、x86_64等不同ABI，arm64 .so不能直接当作x86_64库使用；Windows可安装Android APK只说明有模拟器，不证明它自带所需ARM转译、GMS或可用Root。[NDK ABI官方](https://developer.android.com/ndk/guides/abis)

推荐首轮构建检查arm64-v8a与x86_64两套所需.so，核查全部传递依赖、C++runtime、最低native API、CPU指令、许可及模型资源。Android15支持16KB页设备，任何直接/间接native库都需检查ELF/打包对齐与16KB兼容；不能只因Java层可运行就放行。[16KB页兼容](https://developer.android.com/guide/practices/page-sizes)

退出条件：两种目标环境均能安装、加载全部native依赖、离线完成同一识别样例，且无UnsatisfiedLinkError/指令不支持/页对齐问题，再锁依赖版本。若候选SDK没有x86_64包，选择可官方构建的依赖或明确限制为实测ARM环境；不能默认模拟器转译兜底。OCR选型由主报告另核，本文不引入新的模型runtime。

## 6. 按请求域的HTTP/SOCKS5路由

已批准模型、任务源、外部通知三个scope由用户多选；每次请求锁定**已保存route版本快照**，草稿编辑不影响进行中请求。推荐NetworkClientFactory按协议/route版本/凭据引用缓存不可变客户端，显式配置proxy或直连；不改JVM全局ProxySelector/System属性来偷偷影响未选scope，也不擅自代理游戏进程。旧请求沿原快照结束，新请求使用新保存版本。

这些scope只约束由本App网络适配器发起的请求，不自动拦截第三方SDK内部流量。例如[ML Kit官方隐私条款](https://developers.google.com/ml-kit/terms)说明输入/输出在设备处理，但SDK会联系Google并上报性能/使用指标；bundled模型不等于整个SDK零网络，也不能假设其流量受我们的OkHttp route控制。OCR候选的内部联网/遥测路径和无GMS环境应另行验证，本文不将任何候选标成已验证的绝对断网方案。

### HTTP/HTTPS：推荐OkHttp，认证和DNS分别验证

本轮实际读取Square **parent-4.12.0**固定tag源码用于验证契约，**不是选定生产依赖版本/宣称最新版本**。[OkHttpClient源码](https://raw.githubusercontent.com/square/okhttp/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/OkHttpClient.kt) 提供显式proxy、proxySelector、proxyAuthenticator及派生client；按路由构建/复用client，避免每请求新建池。固定proxy时RouteSelector只尝试该proxy，显式直连可用Proxy.NO_PROXY；真实选用版本须复查相同契约。

HTTP代理的HTTPS CONNECT与407认证由OkHttp proxyAuthenticator处理，origin401与proxy407区分；该HTTP认证器不会自动成为SOCKS5用户名/密码握手。[Authenticator源码](https://raw.githubusercontent.com/square/okhttp/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/Authenticator.kt) 凭据只能交给匹配的proxy连接，不转发到origin或重定向目标；连接超时/认证失败保持原错误，不自动直连回退或重复消费请求。

[RouteSelector源码](https://raw.githubusercontent.com/square/okhttp/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/internal/connection/RouteSelector.kt) 的SOCKS分支创建unresolved目标地址，其他分支经Dns解析相关socketHost。它支持把目标域名交SOCKS代理，但**不是所有DNS都不出本地**：代理主机名本身仍需解析，认证与其他SDK也可能查询DNS。HTTP代理转发/CONNECT的目标解析与本地解析需分别实测；不要把设置自定义Dns等同配置远端DNS。

### SOCKS5：可用传输基础，用户名密码隔离是冻结门槛

Android35官方预置源码 [SocksSocketImpl](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/+/refs/heads/androidx-wear-wear-input-release/android-35/java/net/SocksSocketImpl.java) 明确含NO_AUTH/USER_PASSW握手、java.net.Authenticator.requestPasswordAuthentication路径，以及未解析目标以DOMAIN_NAME发送的分支。此固定API35源码证据不能代表所有OS/厂商版本已实测；普通SOCKS5与带认证SOCKS5必须分开标能力。

[Android java.net.Authenticator](https://developer.android.com/reference/java/net/Authenticator) 的默认认证器为进程级机制，不能每个请求临时setDefault、结束再复原：并发模型/任务源/SMTP可能错用凭据，既不满足scope隔离，也不能可靠绑定route快照。

推荐先核验目标OS上无认证SOCKS5，再独立PoC认证候选：受控的SocketFactory/传输适配器承载每路由SOCKS握手；备选是精确匹配协议/host/port的进程级凭据broker，但同proxy不同路由凭据/并发快照不能正确区分时必须拒绝冻结该方案。没有通过前不把“用户名/密码表单能保存”标为连接受支持。不要在普通HTTP interceptor内插SOCKS握手或把Proxy-Authorization头当作SOCKS密码。

### SMTP：独立协议适配器，不用OkHttp冒充

推荐SMTP候选为Jakarta Mail的 **Eclipse Angus Mail SMTP provider**，官方 [SMTP属性契约](https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html) 明确提供mail.smtp.proxy.host/port/user/password（HTTP代理）、mail.smtp.socks.host/port，以及自定义socketFactory/ssl.socketFactory入口。它没有在该表承诺独立的mail.smtp.socks.user/password；不能把HTTP代理认证属性套给SOCKS。该provider的Android/激活依赖、TLS/STARTTLS、代理CONNECT目标端口限制及实际邮件服务兼容仍须PoC；本文不声称已可安装或已发送。

SMTP adapter持有同一个route快照协议，但自己建立SMTP/TLS连接；不能让HTTP客户端自动承担SMTP。自定义SocketFactory失败时provider有fallback属性，遵守用户所选路由需显式处理该回退，不能悄悄直连。若候选在目标Android运行时不能兼容，可换经核验的SMTP实现；不要擅自把用户SMTP配置转交未确认的邮件HTTP中转服务。

### 网络PoC的通过/阻断标准

| 场景 | 通过证据 | 失败含义与退出条件 |
| --- | --- | --- |
| 三scope并发/保存切换 | 模型/任务源/HTTP通知各只使用所选route；未选scope直连；旧请求保留旧版本 | 任一串域/草稿生效/凭据错用即阻断client factory冻结。 |
| HTTP代理无认证/407/CONNECT | 目标TLS主机校验正常，错误凭据只产生proxy认证失败 | 不得禁TLS校验或自动直连取绿；SMTP端口被CONNECT策略拒绝要显示真实原因。 |
| SOCKS5无认证/认证/错误凭据 | 正确握手、并发不同route凭据不混用；IPv4/IPv6/域名均有明确结果 | 无法绑定凭据快照则认证路线未支持，不冒称整个SOCKS5已覆盖。 |
| DNS | 受控代理/本地DNS记录区分代理地址与目标域名，证实各请求的解析路径 | 未验证就只描述契约，不承诺无DNS泄漏；重定向也需复测。 |
| SMTP直连/HTTP代理/SOCKS | 受控测试服务器验证SMTP AUTH、STARTTLS或TLS、hostname校验、取消与断线结果 | provider无法在Android运行或无安全路由即不冻结SMTP adapter。真实邮件发送另行授权。 |
| 通知与业务隔离 | 投递失败留eventId/channel/route归因，Executor仍按原输入与unknown门运行 | 通知故障影响调度/切账号/消费结果即架构不通过。 |

## 7. 实施前需要冻结的实验，不新增产品默认

优先顺序：平台截图/输入/悬浮PoC → 双ABI离线依赖与存储恢复PoC → 网络route隔离/认证/DNS/SMTP PoC → 专用Root账号快照PoC。所有实验只验证既有范围，不新增长期保活技巧、自动解锁、隐式权限获取、代理直连回退或默认任务触发。

每轮通过后记录实际设备/模拟器、Android版本、targetSdk、APK与native依赖版本、测试证据及失败退路，再冻结适配器。普通平台PoC通过只证明平台链路成立；还需游戏页面/主题样本和任务闭环验证。Root快照未通过不得阻断已可用普通任务模块，但产品应显示其真实不可用状态。

## 来源核对与未验证声明

本文所引主要Android/AOSP、Square固定tag及Eclipse provider页面均在本轮实际打开核验；未用搜索摘要替代核心契约。部分Square文档URL返回404，GitHub master页面还重定向至非上游fork，**未将这些失败/重定向页面作为证据**，改用Square官方raw固定tag。API35 SocksSocketImpl是指定平台源码样本，主分支AOSP/动态文档可能继续变化，正式构建须对所选版本再核对。

本仓库目前是产品/交互/内存模拟阶段。本报告没有APK构建、Android实机/模拟器、24小时耐久、代理抓包、SMTP发送、私有登录态或云服务实测证据；给出的是可启动工程验证的技术推荐与明确退出条件。
