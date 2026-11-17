# GitHub 会话验证检查点

2026-11-17，基于 `b73126c` 的新增 App 会话及鉴权接线。最终源码和 APK 摘要见 [verification.json](verification.json)；安装前逐一读取主包和测试包摘要，均与构建一致，见 [installed-artifacts.json](installed-artifacts.json)。测试后仅卸载测试包，主 App 数据和真实授权保留。

## 最终构建与设备回归

- [build.txt](build.txt)：离线 `:core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug -PincludeOcrIntegrationModels=true`，构建成功。核心 108 项均通过；suite 汇总见 [core-suites.json](core-suites.json)。
- [android-full-tests.txt](android-full-tests.txt)：专用 API36 ARM64／4 KiB 模拟器，171 项中 167 通过、4 条件跳过，无失败。参数显式允许专用模拟器授权变更、OCR 和子进程 native 故障夹具；不对用户真机改授权。
- 4 个跳过项是需单独选择阶段的两组实际进程测试：不能把 runner 的 `OK (171 tests)` 当成 171 项全部通过。最终 APK 的独立阶段均通过，见下一节。
- [android-focused-tests.txt](android-focused-tests.txt) 是此前候选的 17 项聚焦回归，与全量重复，不额外累加；`pre-origin-*` 保留末次响应 origin 修复前的候选证据，不代表最终源码。

## 实际进程终止

[运行器 seed](runtime-process-seed.txt) → ADB force-stop → [运行器 verify](runtime-process-verify.txt)：PID 变更，已完成步骤、队列及新代次恢复，见 [runtime-process-result.json](runtime-process-result.json)。只使用独立 QA 数据库及无输入任务，不代表真实游戏输入未知状态可重放。

[会话 seed](auth-process-seed.txt) → ADB force-stop → [会话 verify](auth-process-verify.txt)：真实 HTTPS 刷新尚未返回时，已加密写入交接标记；新 PID 从 Keystore 解密后仍识别结果未知，没有重放刷新请求，见 [auth-process-result.json](auth-process-result.json)。只使用专属 QA 文件／密钥，结束清理这些夹具；不导出真实会话或操作生产密钥。

## 自审发现与前后比较

| 复现问题 | 原因与修复归属 | 证据 |
| --- | --- | --- |
| 迟到 401 误拒新授权，即使上游重复返回相同令牌字节 | 会话按实际发出凭据句柄和每次授权代次核对，不能按裸字符串认定同一授权 | [修复前失败](stale-401-before.txt)、[当时摘要](stale-401-before-artifacts.json)，原回归方法保留，后续文件另追加用例；原方法摘要见 [method hash](stale-401-method-sha256.txt) |
| 旧请求拒绝事件覆盖正在展示的新设备码 | 缓存凭据与前台登录分别拥有状态，由统一投影决定显示 | [修复前失败](prompt-before.txt)、[当时摘要](prompt-before-artifacts.json)，原断言保留 |
| 附件重定向目的站点的 401 被当作 GitHub 登录拒绝 | 任务源同时保留最终响应 origin，只有原 API origin 的 401 才反馈原凭据拒绝 | [修复前失败](redirect-401-before.txt)、[JUnit](redirect-401-before.xml)、[当时摘要](redirect-401-before-artifacts.json)；该测试源文件与最终摘要完全相同 |

三项均在最终完整回归通过；没有修改断言或减少质量检查来取得通过。这些是本批新增实现的自审问题，不冒充历史已发布 App 的回归。既有“凭据 POST 503 被 OkHttp 重放”的协议回归仍在先前 [github-auth-protocol](../github-auth-protocol/README.md) 保留。

## 原生界面

[浅色竖屏设备码](code-portrait-light.png)、[深色横屏账号](connected-landscape-dark.png)、[深色存储异常](storage-error-portrait-dark.png) 来自最终回归的原生 Compose 页面，均使用虚构账号／设备码。覆盖复制／打开回调、取消、保存阶段无取消、离线和拒绝后的入口、退出确认、横屏布局；页面测试不等于真实浏览器授权。

[技能检查](ui-validation.txt) 96/100，0 error、1 warning、1 suggestion。启发式扫描未识别 empty／offline 文字命名；实际 SignedOut 空状态及网络错误有页面断言，未为消除提示修改扫描器或补无效占位状态。

## 真实服务核对

用户已亲自完成正式 App 的 GitHub 设备码授权。App 显示已连接账号，以自身会话读取私有 `lc-cn/yys-helper`，当前经仓库访问和 Release 列表核对为无已发布版本。更新最终 APK、重开以及最终回归结束后仍显示已连接；升级后的手动拉取结果一致。经过脱敏的观察记录见 [live-service-check.json](live-service-check.json)。

真实令牌留在 Android 私有加密存储，没有提取会话文件或提交真实授权截图／设备码；没有使用开发机 Token 替代验证。该核对在专用模拟器完成，不声称手机也已更新验证。

没有已发布任务附件可供真实下载；实际到期刷新／撤权、开发者有效写权限、App 内制作／发布、游戏领取、留出主题和长期运行未验证。本轮没有发布 Release、执行游戏操作或发送外部通知，M2／M3／M4 继续。
