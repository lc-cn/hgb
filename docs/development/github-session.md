# Android GitHub 会话

2026-11-17，接在[设备码协议](github-device-authorization.md)和原任务分发器上。公开 Client ID 从仓库配置生成 BuildConfig；不使用开发机凭据、Client Secret、App 私钥或任务 JSON 提供的认证信息。

## 所有权与启动

主进程 `AssistApplication` 持有唯一 GitHubSession，与原唯一任务执行器、版本库及 TaskUpdates 并列。OCR 子进程仍在主服务初始化前返回，不创建会话或拉取任务。会话不拥有游戏队列、账号身份或任务执行控制。

启动拉取仍是原来的一次拉取，只在取得凭据前等待本地会话读取结束。登录成功不额外拉取或启用任务；用户进入任务更新后手动拉取。当前账号资料只从 `/user` 读取公开登录名，不读取邮箱、头像或其他账号数据，也不把登录成功或公开账号资料当成开发者权限。接口不要求额外账户权限，见 [GitHub 用户接口](https://docs.github.com/en/rest/users/users#get-the-authenticated-user)。

## 私有存储

令牌、有效期、客户端身份、凭据代次、续期状态和缓存登录名保存在 `noBackupFilesDir/github-session.bin`。AES/GCM 加密密钥由 Android Keystore 创建和持有，IV 由平台加密器产生；完整明文没有写到临时文件、Room 或日志。沿用现有关闭备份／设备迁移的规则，没有增加权限或依赖。Keystore 的密钥不可直接导出不等于能抵抗被控制的 App 进程或 Root；不作这一保证。见 [Android Keystore](https://developer.android.com/privacy-and-security/keystore)。

唯一会话拥有者串行写入 AtomicFile，加密写入后回读核对。写入报错时只读核对实际结果，相同记录才确认完成；否则停用凭据入口并显示存储异常。缺文件、不可读、损坏、密钥丢失保持区别；读取不生成替代密钥或把问题当作登出，原文件保留。明确重新登录成功或用户确认清除本机登录，才替换记录。AtomicFile 仍依赖单一写者和文件系统，不据此宣称掉电／磁盘故障恢复已通过。见 [AtomicFile](https://developer.android.com/reference/android/util/AtomicFile)。

退出只清除此设备凭据，保留任务版本、配置、游戏账号和执行队列；不撤销 GitHub 服务端授权或卸载 GitHub App。使用持久化空记录确认清除结果，未确认时不显示“已退出”。

## 登录和续期交接

设备码登录由 App scope 持有。导航、旋转、切到浏览器不取消或重新申请；只有明确取消停止本次轮询，旧回调不能写入后一次操作。收到令牌后进入保存阶段，提交在不可取消边界完成，界面不再提供取消按钮。取消本地轮询不代表 GitHub 端绝对没有授予令牌，客户端不冒充已经撤销远端授权。

有效期从成功的那次令牌请求前开始计算，设备码等待时间不计入访问令牌寿命；启动后使用缓存期限，服务器 401 仍是校正凭据可用性的证据。只有需要凭据的请求发现过期／已拒绝时才尝试续期，没有定时刷新、提前刷新阈值或授权后台重试。

续期前先持久化 IN_FLIGHT，再发一次不可重放的刷新 POST。工作属于 App，而非某个请求／页面；请求方取消后仍接收并保存轮换结果。互斥边界覆盖实际请求和持久化，其他请求重新核对后共用新凭据，不再次轮换。每次新登录／轮换生成新代次；迟到 401 只能拒绝原发出凭据句柄，不能只靠字符串相等而误伤新代次。

成功保存转为 IDLE；明确的过期／拒绝／配置结果转为 REAUTHORIZE；网络或响应结果未知保留 IN_FLIGHT。App 重开后不会重放后二者的刷新令牌，分别显示已失效或上次结果未确认，等待用户重新登录。GitHub 刷新会使旧令牌失效，协议依据见[官方刷新说明](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/refreshing-user-access-tokens)。

只有最终响应仍来自原 GitHub API origin 的 401 才拒绝该请求发出的凭据句柄；附件重定向站点的 401 保持下载错误，不使 GitHub 登录失效。401 不自动重放当前任务源 GET，也不改变本地版本；下一次用户拉取或已授权的启动拉取再取得可用凭据。403、404、网络失败没有被当成令牌已撤销。`TaskSourceProblem.AUTHORIZATION` 保留本地认证／存储问题，避免被分发器误报为任务保存失败。

## 原生界面与状态

设置新增 GitHub 一行。设备码可选择／复制，打开官方浏览器页面由用户完成授权；没有密码或 Token 输入框。横屏说明与操作按 38%／62% 分栏，竖屏将主登录操作置底；使用原明暗主题、半透明图标、玻璃按钮和最小触摸区域。错误文字与状态可被无障碍读取。

缓存凭据结果与当前登录操作分别持有，统一投影出 Loading、SignedOut、SigningIn、Saving、Connected、Failure。后台旧请求不能把新设备码界面覆盖成旧账号或登出；保存失败不显示登录成功。退出有明确的本机范围说明。

## 验证范围

本轮在专用 API36 ARM64 模拟器以真实 Keystore、文件和本地 HTTPS 验证；随后由用户完成正式 App 的真实 GitHub 设备码授权。App 已显示账号并读取私有任务源，结果为经仓库访问和 Release 列表核对的“暂无已发布的任务版本”。更新 APK 并重开后仍保留登录、手动拉取结果一致。没有导出真实令牌或会话文件，没有使用开发机凭据替代。主／测试 APK 摘要、回归前后失败、原生截图和实际进程验证见 [github-session](build-evidence/github-session/README.md)。不会把模拟 HTTP 令牌、宿主重建或截图等同于真实 GitHub 私有源或游戏领取。

开发者有效仓库写权、制作／发布、真实非空任务附件、Game 身份与领取、留出主题及长期运行仍需继续；GitHub 登录本身不解锁未实现的功能或使 M2／M3／M4 自动完成。
