# 设备码与任务源鉴权验证

2026-11-17；实现边界见[GitHub 设备码协议](../../github-device-authorization.md)。没有真实令牌、授权码或手机数据，测试凭据均为 `fixture_` 值。

- `build.txt`：最终核心测试、主／测试 APK 构建与 App Lint 通过。`test-summary.json` 记录核心 105 项实际执行通过，以及专用 API36 ARM64、4KiB 模拟器 4 项任务分发集成通过。未重新运行上一轮完整执行器／UI 批次，不将其计入本轮证据。
- `auth.GitHubDeviceAuthorizationTest.xml`：10 项协议测试，实际本地 HTTP、可注入的单调时钟；验证表单无密钥、官方提示链接、待授权／slow_down 间隔、过期、拒绝／配置错误、无效响应不泄露原文、重定向拒绝、取消、响应中断及不可重放 POST。虚拟时间只证明算法间隔，未声称 GitHub 实网时序或 App 生命周期。
- `content.GitHubTaskSourceTest.xml`：13 项源适配器回归，包含当前凭据头、401 和私有 404 意义保留、两服务跨 origin 附件重定向剥离 Authorization，以及原摘要、流式资源和取消边界。
- `android-https-tests.txt`：4 项真实 Android TLS／SQLite 情境。新增用例在本地 HTTPS 取得模拟设备码令牌，接给原 TaskUpdates，安装任务版本；后续 401 不改变当前启用和已知版本。该用例没有正式授权 UI、令牌持久化或真实 GitHub 服务。
- `post-replay-before.txt`／`.xml`：本轮新协议自审中复现 OkHttp 5.4.0 的 503 Retry-After follow-up，虽然关闭连接重试，令牌 POST 仍再次投递，原断言失败。请求体改为 isOneShot 后最终 10 项协议测试通过；前后该回归测试源码摘要相同，见 `post-replay-before-artifacts.json` 与 `verification.json`。它是开发中发现的缺口，不宣称旧发布 App 已有此登录功能或发生过真实令牌事故。
- `verification.json` 固定源码与最终 APK 摘要，`installed-artifacts.json` 对照模拟器实际安装的主／测试包。没有新增依赖、Room 表或 Manifest 权限；正式 App 仍使用缺省未登录任务源。

用户提供的 App ID／公开 Client ID 与报告权限记录在[注册配置](../../github-app-registration.md)。未代为注册、安装、改权限或发布；尚未验证 Device Flow 实际开启、App 会话、令牌私有存储／刷新轮换／重启、指定仓库有效权限、真实私有附件及指定开发者账号。M2／M3／M4 未完成。
