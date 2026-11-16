# 首期实现进度

开始日期：2026-11-16。用户已确认 Kotlin／Compose 原生方案和开发基线的 M0–M4 切分，目标模式持续执行。

## 当前实现

- 已建立 `app` Android 模块、`core` 纯 Kotlin 模块、官方 Gradle Wrapper 和集中版本目录。
- 已加入应用级唯一执行器、队列／整计划优先级、输入票据撤销与独立静默回执、两种恢复、停止／移除差异、确认失败／未知、账号切换边界的首版代码与集成情境测试。
- 已加入准备／提交边界与执行存储 API：状态、修订和版本引用在同一事务提交，校验新轮次的原版本与保存配置；支持记录格式 2 和格式 1 升级，避免每步骤重复写整份计划。宿主尚未使用此 API，App 当前执行器仍为内存实例。详见[执行持久化](execution-persistence.md)。
- 已加入任务 JSON 数据模型、动作合同与 schema 的首版校验；没有内置正式游戏任务。格式仍需代表任务验证，不冒充已经冻结的完整表达协议。
- 已加入 8 类原生配置控件及规则保存：选择按钮组、精确数值／滑块、时间、日期与日期范围。横屏左侧步骤、右侧两列字段；竖屏纵向。取消选择不写入默认值，草稿保留原始输入并跨页面／旋转保留，保存使用精确十进制。Room 显式 1→2 迁移新增按目标／任务隔离的配置；版本和修订冲突保留草稿，删除历史版本不删除设置。未保存草稿仍是进程内内存，不承诺杀进程恢复。
- 已加入 Compose 原生五主入口与空状态、明暗主题偏好、横屏导航栏、设置标题右侧主题三选、彩色半透明图标及分层高光按钮。
- 已加入需用户手动授权的无障碍截图／输入桥。桥按尝试票据和单一会话发送有限手势，核对前台目标、方向和授权代次；取消工作协程不冒充输入静默，撤权无终态回调保留未确认。截图检查复用该桥，默认只在内存中处理；没有接入真实游戏或正式 TaskWorker。见[平台桥](device-bridge.md)。
- 按用户后续扩展要求加入目标描述、任务命名空间与明确的匿名身份；任务请求和运行事实携带目标上下文。详见[扩展边界](target-extensibility.md)。
- 新增 Room 版本库：完整 JSON／摘要、启用选择、明确的远端 latest 指针和运行引用在事务内管理。支持幂等获取、同版本内容冲突拒绝、显式启用／回退、保护引用和删除；读取损坏不冒充空目录。已接只读 GitHub 拉取和原生版本页，引用尚未与真实执行入队／恢复联动，M2 仍未完成。
- 原生任务版本页按约定提供顶栏拉取、版本／更新时间行、正文展开 changelog、右侧启用／删除及禁用状态；任务中心右上角读取真实当前版本和已知更新红点。当前启用内容作为任务目录投影，没有硬编码正式游戏任务。

## 已取得的证据

- 本机 `:core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` 通过；核心 55 项测试零失败，Android Lint 无问题。构建依赖有锁文件与 SHA-256 校验元数据。
- 开发专用 Android API 36 ARM64 模拟器已安装并运行原生 APK。已实测主题偏好持久化、系统主题跟随、明暗状态栏、横竖切换、历史页选择保留，以及手动授权后取得可读取的 1080 × 2400 截图。该早期探针构建无输入能力；当前桥验证单独记录。
- 核心迟到结果回归先取得两项失败证据，再修复并通过。证据只覆盖核心命令／回执边界，不证明 Android 输入静默或卡死恢复。
- Room/KSP 与当前构建实际兼容；7 项 Android 仪器测试在隔离 API 36 设备的真实文件 SQLite 上通过，覆盖进程内数据库关闭／重开持久化、拉取不启用、更新红点使用明确 latest、同版本冲突／损坏、未决引用保留、删除与引用竞争、事务回滚及目录观察一致性。证据见 `build-evidence/version-library/`。重开数据库不是 Android 进程崩溃或断电恢复证明。
- 任务更新共 14 项设备场景通过（上述 7 项存储＋2 项本地 HTTPS／真实 SQLite 集成＋5 项原生 UI）；补充了 5 项 UI 的渲染截图并实际查看明暗、横竖和长版本号。长版本号挤掉标题的测试先失败，修复入口宽度归属后通过。证据位于 `build-evidence/task-updates/`。
- 桥阶段合并 36 项 Android 测试零失败（含 8 项真实平台桥测试），45 项核心测试及 Lint 通过。800 ms 手势暂停后等待系统回执、工作取消仍占用输入、旋转／前台／撤权拒绝，以及在途撤权保留未确认均取得设备证据。会话交接取消先取得占用泄漏的失败断言，再修复并在相同断言通过。证据见 `build-evidence/device-bridge/`；10 项整机 UI 检查有单独的修复前 APK 摘要，不宣称其为最终 APK UI 重跑。
- 最新执行存储阶段：55 项核心、47 项 Android 测试及 Lint 通过。含真实 SQLite 修订竞争、回滚、重开、迁移、未知保留与版本引用；删除历史内容后不能记录迟到未知的回归先失败再修复。合成基准完成 100 步时快照从约 18.4 MB 降至 0.44 MB，不作为设备性能结论。证据见 `build-evidence/execution-store/`。
- 正式 App 在隔离模拟器实际访问指定公开仓库：启动和手动拉取均显示“暂无已发布的任务版本”，版本页横竖／深色切换保留路由。非空版本验证使用明确标注的本地测试服务器与组件夹具，未创建或发布真实云任务。
- 任务配置阶段：14 项新增 Android 测试与原有 14 项合并，28 项零失败；包含真实 SQLite v1→v2 迁移、版本／修订冲突、未知字段保留、草稿与保存生命周期、原生选择／滑块／时间日期。45 项核心测试含精度和数值草稿。日历测试最初因匹配单个日期数字失败，按原生完整日期语义修正定位后通过，日期及时区断言保留。原生截图已实际查看，证据见 `build-evidence/task-configuration/`；组件技能静态检查不替代设备结果。
- 官方网易下载页关联的 2.8.89 官服 APK 已完成下载、包信息与签名完整性检查；真实手机用户已确认进入庭院，助手已取得当前庭院／商店的只读 ADB 画面并返回庭院。未执行领取、兑换或强化；不计作 App 截图／输入闭环。详见[游戏验证记录](game-validation.md)。

证据位于 `build-evidence/`；各次 APK 摘要与范围需匹配读取。旧截图不是新构建的自动证明。M0 已具备可安装壳与本地 UI 证据，M1 及后续阶段仍未完成。

早期目标隔离阶段的证据在 `build-evidence/target-scope/`：APK 摘要、源码摘要、35 项测试及 Lint 结果见 `verification.json`；同一 APK 的 10 项原生检查见 `native-smoke.json`，配套明暗和横竖截图已实际查看。该轮没有重新声称游戏输入或截图授权探针通过。

## 构建选择及依据

| 项目 | 已锁定初始选择 | 依据 |
| --- | --- | --- |
| JDK | 17 | 本机可执行版本及 AGP 要求 |
| Gradle | 9.4.1 | 官方 Wrapper，分发 SHA-256 固定 |
| AGP | 9.2.1 | 官方 9.2 系列兼容 JDK 17、Gradle 9.4.1、SDK 37.0 |
| Kotlin／Compose compiler／serialization plugin | 2.4.21 | Kotlin 官方兼容矩阵；Compose compiler 同 Kotlin 版本 |
| Compose BOM | 2026.09.00 | 官方稳定 BOM 文档和 Google Maven 实际 POM |
| compileSdk／targetSdk | 37 | 初始开发目标，已安装正式 37.0 SDK；不据此承诺所有目标设备兼容 |
| minSdk | 30 | 现有技术方案首轮无障碍截图实验起点，支持矩阵仍需设备验证 |
| Room／KSP | 2.8.5／2.3.14 | 官方 Maven 包存在，生成 DAO、APK、Lint 与实际 SQLite 仪器测试通过 |
| OkHttp | 5.4.0 | 官方 Maven 实际依赖已核对，HTTP／HTTPS／取消测试及 App 读取公开源通过 |

核验来源：[AGP](https://developer.android.com/build/releases/agp-9-2-0-release-notes)、[内置 Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin)、[Kotlin／Gradle](https://kotlinlang.org/docs/gradle-configure-project.html)、[Compose BOM](https://developer.android.com/develop/ui/compose/bom)、[Compose compiler](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler)、[无障碍截图与手势合同](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)。下载使用官方 Google Maven／Maven Central／Gradle，未关闭 TLS 校验。

本机 Java／SDK 路径记录在被忽略的 `local.properties` 或构建命令环境中；构建脚本不嵌入个人安装路径。已按用户授权初始化本地 Git，绑定既有私有仓库 `lc-cn/yys-helper`，通过阶段检查后提交推送；没有创建仓库或发布 Release。

Room 核验记录：2026-11-16 [Room 版本页](https://developer.android.com/jetpack/androidx/releases/room)列出 2.8.5，而同日[入门页](https://developer.android.com/training/data-storage/room)示例使用 `androidx.room3` 3.0.3，两处并不一致。本工程没有混用主版本，按实际存在的 2.8.5 依赖及编译／设备结果锁定。[KSP 官方发布](https://github.com/google/ksp/releases/tag/2.3.14)与[目录变化观察 API](https://developer.android.com/reference/kotlin/androidx/room/InvalidationTracker)已核对。Room schema 导出保留于 `app/schemas/`，无破坏性重建回退。

## 现阶段限制与下一步

- 执行事务、持久状态与版本引用接口已通过测试，正式运行宿主尚未接入；App 当前仍使用内存核心，不能据此承诺无人值守或真实崩溃恢复。用户已确认安全任务在重开后自动恢复，未知仍核对；该策略待落地和实测。
- 核心完整排队语义仍由情境测试覆盖；真实平台桥已在自有触摸夹具验证单一会话与手势终态。桥尚未与正式宿主贯通，跨进程卡死、系统级即时撤销、进程故障和持久化输入／业务结果交接仍未验证。暂停仅阻止新输入，有限在途手势等待自然终态。
- 尚未完成 UI v5 全部功能；执行宿主、入队引用联动、三层悬浮、资源附件完整提交、离线识别及真实任务仍需继续实现。当前基础能力注册表不预先登记未实现工作器，完整任务表达也未冻结，不能把 JSON 下载路径计作完整游戏任务支持。
- 分层高光是当前原生材质实现，不宣称已实现背景折射或经过性能验证。
- 已连接首个真实设备 `SM-G9910`／Android 15／API 35，助手与官服已安装并启动；Windows 模拟器环境仍未指定。
- 游戏开发模拟器暴露压缩纹理缺图，尚未修好；用户已确认进入庭院，取得当前只读画面；尚无 App 执行的真实任务结果，详见游戏验证记录。
- 用户已指定任务仓库 `lc-cn/yys-helper`。只读 GitHub API 返回仓库公开可访问、默认分支 master；公开 Release 列表为空。真实发布、客户端注册和资源上传未执行。
- 已有当前庭院和商店只读样本，代表免费领取项的资格及完成判据仍未确认。样本保留在忽略目录，不公开账号画面；未领取、消费或发外部通知。

下一步：接入执行宿主、启动安全恢复与离线识别，在已确认庭院的真机推进代表任务和联验。分发实现及边界见[任务分发](task-distribution.md)，完整产品范围仍以 PRD 与开发基线为准。

## 运行宿主模块检查点

已实现 Application-owned 命令／effect 宿主模块，尚未接入正式 Application 与列表按钮。8 项真实 SQLite 宿主测试通过，覆盖 UI 调用方取消不丢命令、提交回执丢失只读核对、队列暂停／继续、移除不等待失去响应的无输入工作协程、已确认失败／未知、慢账号核对和范围取消后的安全重入。59 项核心测试及构建／Lint 通过；范围取消＋重建宿主不是 Android 实际进程强停证据。已注册的等待动作尚未装配到生产任务解码器。证据见 `build-evidence/runtime-host-foundation/`。下一步接入 Application、队列控制和任务行按钮，再做对应原生验证。
