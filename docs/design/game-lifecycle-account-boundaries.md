# 游戏生命周期与账号登录态边界

核验日期：2026-11-15。仅研究 Android 官方 API、AOSP 官方源码；没有访问设备、游戏、账号或登录态文件，没有执行提取/恢复操作。

用户已明确账号目标：保存**已登录账号的登录态快照，供下次启动指定账号使用**，不是仅记录角色资料。账号管理与游戏启动/关闭、App 开机自启已进入产品需求；用户已接受专用 Root 环境，先做受控快照恢复验证；实际快照内容、设备和执行桥仍待验证。本文件不提供具体凭据操作，不承诺免登录、跨设备迁移或自动解锁。

## 可评审结论

普通 Android App 可以请求启动游戏公开入口，但受到包可见性、后台 Activity 启动限制；不能把“发出启动请求”写成“已进入指定账号”。普通 App 没有任意读取另一 release App 私有登录态的权限，也没有任意 force-stop 其他包的普通运行时权限。依据分别是 [PackageManager 启动入口](https://developer.android.com/reference/android/content/pm/PackageManager#getLaunchIntentForPackage(java.lang.String))、[Activity 启动限制](https://developer.android.com/guide/components/activities/secure-bal)、[应用沙箱](https://source.android.com/docs/security/app-sandbox) 和 [FORCE_STOP_PACKAGES 声明](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/AndroidManifest.xml#4850)。

普通设备能力与已接受的“专用 Root 快照验证路线”分开呈现。Root 是该受控实验的前提，不是登录态恢复可用性的证明；游戏的数据布局、SDK/设备绑定、服务器会话有效性及恢复结果仍未知。Android [SELinux](https://source.android.com/docs/security/features/selinux) 约束包括 root 的进程，[Keystore](https://developer.android.com/privacy-and-security/keystore) 可使密钥不可导出并绑定硬件，因此“取得文件”不足以推出“恢复账号成功”。

## 1. 启动游戏：请求、可见与登录是三件事

`PackageManager.getLaunchIntentForPackage(packageName)` 返回目标包公开主入口 Intent；找不到相应入口或不识别包名时可返回 null。API 33 的 getLaunchIntentSenderForPackage 可减少查询包可见性的限制，但不会授予私有数据访问或绕过后台启动限制。产品需保存目标安装包身份，实际发行渠道/包名须由真实安装验证，不在本文假定。[官方 API](https://developer.android.com/reference/android/content/pm/PackageManager#getLaunchIntentForPackage(java.lang.String))

targetSdk 30 及以上查询其他已安装 App 默认受包可见性过滤；应按已支持目标声明必要 queries，查询失败不能直接解释成“游戏未安装”。无需为单一游戏默认申请全量包列表权限。[Package visibility](https://developer.android.com/training/package-visibility)

Android 10 起限制后台启动 Activity。当前官方说明列出可见窗口、用户通知点击、特定系统/绑定/授权场景等例外；这些是具体条件，不是常驻服务可无条件拉起游戏的承诺。目标 SDK/OS 的 PendingIntent opt-in 等规则也会变化。实施应按实际 Android 版本验证前台点击、悬浮入口、定时触发分别能否拉起；被阻止时保留请求状态和原因，提示用户前台打开，不宣称游戏启动成功。[Activity security](https://developer.android.com/guide/components/activities/secure-bal)

启动成功的产品证据至少区分：请求已提交 → 游戏窗口已观察到 → 账号/区服已核对 → 可执行任务。此证据分层是本项目设计建议，不是 Android 为游戏提供的登录结果 API。

## 2. 关闭：退出界面不等于停止整个游戏

| 能力 | 官方边界 | 产品含义 |
| --- | --- | --- |
| 返回桌面/返回键 | 无障碍 global action 有 HOME/BACK；HOME 仅表示前往桌面。[AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#GLOBAL_ACTION_HOME) | 可称“返回桌面”；不能称已 force-stop，也不能据此写入快照。 |
| Activity.finish / 移除任务 | API 关闭相应 Activity/任务；不是通用的跨 App 强停令牌。[Activity](https://developer.android.com/reference/android/app/Activity#finish()) | 普通 App 不能拿自己的 finish 去任意结束游戏全部进程。 |
| killBackgroundProcesses | 不是 force-stop，系统可按需重新启动；Android 14 起第三方 App 仅可杀自己的进程。[ActivityManager](https://developer.android.com/reference/android/app/ActivityManager#killBackgroundProcesses(java.lang.String)) | 不能作为游戏停止/数据静默证明。 |
| force-stop 目标包 | FORCE_STOP_PACKAGES 为 signature\|privileged，属于隐藏/系统能力，不是普通 App 弹窗授权项。[AOSP Manifest](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/AndroidManifest.xml#4850) | 普通模式显示能力不足；不能通过请求普通权限伪装支持。 |
| 调试 shell 的强停入口 | AOSP shell 声明上述权限，ActivityManagerShellCommand 调用系统 forceStopPackage；这是 shell 身份能力。[Shell 权限](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/Shell/AndroidManifest.xml#211)、[系统入口](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/am/ActivityManagerShellCommand.java#1613) | 仅在用户明确选择并建立相应执行桥时研究；普通 App 不自动拥有 shell 身份，强停能力也不推出私有登录态读取能力。 |

“游戏退出”“停止游戏进程”“退出账号”应分别命名。本文未核实游戏提供给第三方的官方退出/账号切换 Intent 或 SDK 接口，不能发明该契约。若采用游戏自身 UI 完成退出，只能按实际界面证据核对，其行为和主题兼容性仍需另测。

## 3. 开机自启与会话恢复

接收 BOOT_COMPLETED 需要 RECEIVE_BOOT_COMPLETED；系统在用户解锁后发送相应启动广播。开机后未解锁的 Direct Boot 是另一阶段：默认凭据加密存储不可用，组件须显式 directBootAware 才能在该阶段执行有限工作。不能以“App 开机自启”推导“可自动读取私有登录快照并解锁进入游戏”。[Intent.ACTION_BOOT_COMPLETED](https://developer.android.com/reference/android/content/Intent#ACTION_BOOT_COMPLETED)、[Direct Boot](https://developer.android.com/privacy-and-security/direct-boot)

targetSdk 31 及以上从后台启动前台服务受到限制，开机广播只是带条件的例外，仍须符合服务类型和权限要求。targetSdk 35 及以上不能从 BOOT_COMPLETED receiver 启动 mediaProjection 前台服务。前台服务不是获得所有后台 Activity 启动权或无限保活的通行证。[FGS 后台限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)、[Android 15 类型限制](https://developer.android.com/about/versions/15/changes/foreground-service-types)

MediaProjection 每个会话需用户同意，token 单次使用；会话停止后不能重新创建虚拟显示。进程被杀、锁屏、用户停止分享等可结束会话。因此普通投屏路线在重启后必须进入“截图会话待授权”，不能保存旧授权数据后台复用。若未来选择无障碍截图或特权截图，要按该独立适配器另行验证，不能用其可能性覆盖 MediaProjection 的约束。[Media projection](https://developer.android.com/media/grow/media-projection)

推荐将产品开机链路表示为“收到系统启动事件 → 初始化自身状态 → 检查解锁/执行桥/截图/输入能力 → 缺项等待人工”。是否开机自动恢复队列、自动拉起游戏或自动切账号仍需明确产品配置，不因新增开机自启需求默认启用这些后续行为。厂商后台限制也需按设备验证；Android 官方说明具体资源限制可由制造商决定。[系统后台限制](https://developer.android.com/develop/background-work/background-tasks/bg-work-restrictions)

## 4. 登录态快照的权限路线

| 路线 | 官方证据能支持什么 | 不能承诺什么 |
| --- | --- | --- |
| 普通安装 App | 不同 App 使用不同 UID，内核沙箱隔离私有数据；native/C++ 也在同一隔离模型内。[Application Sandbox](https://source.android.com/docs/security/app-sandbox) | 无障碍、悬浮窗、截图或普通存储授权不能自动变成读取另一 release 游戏私有登录态的权限。 |
| 已授权、非 root ADB shell | ADB 是设备通信/调试 shell；USB 调试需用户启用并确认主机授权。[ADB](https://developer.android.com/tools/adb) | ADB 连接成功不等于 root；不推导可读取任意游戏私有目录或可迁移会话。 |
| run-as | 官方实现要求调用者为 shell/root，并显式拒绝 non-debuggable 包；会设置目标 UID 与 SELinux context。[AOSP run-as 固定源码版本](https://android.googlesource.com/platform/system/core.git/+/c007c43335ebde667c06a56a9706b403d09c9dd6/run-as/run-as.cpp#233) | 不能把任意已发行游戏假定为 debuggable；不能用 run-as 作为普通 release 游戏的通用备份方案。 |
| 专用 Root 环境 | 可研究拥有必要权限的本地执行桥，具体权限依环境；SELinux 对 root 仍执行强制访问控制。[SELinux](https://source.android.com/docs/security/features/selinux) | Root 不自动保证所有路径/密钥可读，不保证游戏接受该环境，不保证会话可恢复。本轮仅记录验证设计；不实施设备改造、凭据读取或状态写入。 |

即使能读取某些数据，也不能预先认定“某个 token 文件就是完整快照”。Android Keystore 的密钥可能不可导出并绑定硬件；这只是可能阻止文件快照迁移的系统机制例子，并非断言该游戏一定使用了它。[Keystore](https://developer.android.com/privacy-and-security/keystore)

同设备/同安装的重复恢复、同模拟器不同实例、不同物理设备、游戏更新后、SDK或渠道切换后、区服变化后的有效性全部**未知待测**。服务端到期、主动登出、多端互踢、验证码/二次验证、设备绑定是否发生及其具体规律也未核实；不能承诺有效时长或绕过登录验证。

## 5. 已接受特权验证路线的恢复设计门槛

以下是为未来实验提出的可靠性约束，不是已实现流程、不是 Android 官方提供的“账号快照原子恢复 API”，也不包含凭据提取命令。

1. **独占环境。** 以设备/Android 用户/目标安装身份建立生命周期锁，协调器停止发新游戏输入并撤销旧执行租约，等待独立输入通道确认静默。存在未核对消费结果时保留 unknown，禁止通过换账号清除证据。
2. **先停止再读写。** 需要可验证的目标游戏停止与数据静默证据；只有退到桌面不足。备份当前可回退状态后，才允许开始目标快照恢复。快照具体文件集合、可能的外部/SDK依赖尚未确定，不能先拍脑袋列文件路径。
3. **分阶段与可恢复日志。** 校验目标安装/用户/版本兼容性、快照完整性和访问权限；先暂存、再提交并记录阶段，避免运行中的游戏同时覆盖数据。多文件提交要单独设计崩溃一致性，不把逐文件复制包装为原子操作。Android AtomicFile 是单文件原子写帮助类，不提供整个游戏状态的事务。[AtomicFile](https://developer.android.com/reference/android/util/AtomicFile)
4. **启动后核账号再放行。** 恢复本地数据成功只代表写入步骤完成；启动游戏后仍需新观察验证实际账号/区服和可用状态。与预期不符、需要登录、验证码、服务器拒绝或证据不足时标记失败/需人工/unknown 中恰当一类；不冒充“指定账号登录成功”。
5. **回退与未知隔离。** 提交失败时先保持游戏停止，保留目标暂存和原状态证据，按经过验证的回退程序恢复原账号本地数据。回退也可能失败或无法证明账号状态；此时禁止继续任务并提示人工处理。恢复原本地数据不保证服务器登录会话回滚，不能把回退当成撤销真实游戏交易。

快照视为可复用登录凭据，未来需独立加密存储、最小显示与脱敏元数据；不进入日志、通知内容、云视觉图片附件或云任务包。是否允许导出/跨设备转移/云备份尚未决定，本次研究不新增这些默认行为。

## 6. 实验和验收，不把研究写成已可用

用户已接受“Root 专用环境，先验证快照恢复”。根 agent 实测当前 command -v adb 无结果，三个常见安装路径均未发现 adb，工具列表也无 Android 设备连接器；本轮无法执行真实恢复验证。待明确专用设备/模拟器、游戏发行渠道、账号范围和可用执行桥后，第一轮应验证**同一专用环境**中的用户自有已登录账号；不从成功一次推导跨设备可用。此环境清单由根 agent 提供，本文件未另行连接设备。

所有下列实验均为待执行，当前只有契约研究与交互模拟；每项记录设备/模拟器、Android、targetSdk、游戏包/签名/版本、时间、快照标识及可核对结果，不记录凭据原文。不能只以文件 hash 相同证明账号可登录。

| 待验证场景 | 输入/前提 | 可评审通过标准与失败边界 |
| --- | --- | --- |
| 同设备同版本多账号基线 | 同一专用 Root 环境，分别人工完成账号 A/B 合法登录 | 分别核对账号及区服，建立环境与快照标签关联；不得仅用昵称或文件存在推断登录成功。 |
| A/B 快照往返恢复 | 已验证停止/静默与原状态备份；依次恢复 A→B→A | 每次先恢复，再启动并新观察核对实际账号/区服；用户选择与实际一致才标可执行，记录多次往返结果。文件内容和依赖集合由实验确定。 |
| 失败回退 | 受控制造权限不足、空间不足、校验失败等局部错误 | 不能覆盖最后可回退证据；保持游戏静默，恢复原本地状态后再核账号。无法证明回退成功就停在待核对，不能自动跑任务。 |
| 过期与重新登录 | 后续真实观察到会话过期/服务端要求验证 | 识别“需重新登录/验证”，不声称快照永久免登录；人工合法登录更新快照前不得覆盖旧证据。过期时间不预设。 |
| 包/SDK版本变更 | 同环境游戏升级、渠道或安装身份不匹配 | 先兼容性判定；未知不自动恢复覆盖。若获准实验，核对结果并记录兼容证据，不用旧版本成功证明新版本可用。 |
| 进程崩溃/提交中断 | 在暂存、提交、首次启动核对等阶段受控中断 | 重启后能识别未完成阶段，保持业务输入关闭；选择完整目标状态或回退，不把混合文件当成成功。不得假设单文件 AtomicFile 已解决全部事务。 |
| 未知业务结果隔离 | 切换前已有 unknown 消费/输入结果 | unknown 证据按原 run/账号保留，不被关闭、恢复或切账号清除；核对前不继续消费型业务。回退不冒充交易撤销。 |
| 残留进程/迟到回调 | 强停失败或旧 worker 恢复/回执迟到 | 独立停止/输入静默证据不足则不写；旧代际事件不能污染新账号状态或发输入。 |
| 重启与授权丢失 | 重启、Root执行桥失效、锁屏或投屏会话结束 | 明确显示需解锁/授权/能力不可用，不宣称开机即恢复任务或指定账号。 |


可供 PRD 采用的状态语义为：能力未配置/不支持、需系统授权、需解锁、快照待验证、恢复处理中、已恢复但账号待核对、已核对可执行、需重新登录、恢复失败、回退待核对、结果未知。具体 UI 枚举与默认触发由主稿统一确认，不在本文件直接实现。

本次只核验上述 Android 公共契约和官方源码；未验证游戏私有数据格式、登录态生命周期、Root兼容性或官方账号接口，也没有任何真机、模拟器、服务调用和性能证据。文档可支持划清产品能力边界，不能作为快照方案已可落地的结论。

## 本轮确认的交互与内存模型

启停与账号选择集中到工作台标题栏；游戏非停止状态时禁用选择，模块命令入口同样拒绝跨账号启动，需先确认关闭。当前模块22个内存情境通过，其中新增守卫具有修前失败的回归；这不等于真实Android验证。

选择待启动账号不改变已核对身份；启动后新观察验证成功且身份实际改变，才清空旧未结束队列，失败／回退保留原队列，所有流程保持人工暂停直到用户继续。计划、任务结果与摘要按已核对账号隔离，原计划保留原归属，到点也不借新账号执行。全局历史标注来源。删除本地登录态资料不删除计划／历史。

工作台只在当前运行账号未保存时显示保存图标；点击先说明当前一致性策略会暂停队列并关闭游戏，再保存未验证快照。没有承诺运行中无影响保存，也没有将本地保存成功写成恢复成功。真实一致性策略仍需上述设备矩阵验证。
