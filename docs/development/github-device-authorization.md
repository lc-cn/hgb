# GitHub 设备码与任务源鉴权协议

2026-11-17，M2 进行中。本文保留 `b73126c` 设备码协议检查点；后续正式 [Android 会话](github-session.md)已接入，用户已完成真实设备码授权，私有仓库可访问且暂无发布。没有代为安装 GitHub App、修改权限、写入仓库业务内容或发布 Release。实现配置见[GitHub App 注册配置](github-app-registration.md)。

## 本轮实现边界

`GitHubDeviceAuthorization.authorize` 是一次由调用者拥有的登录尝试，按 GitHub 设备码协议请求挑战、展示用户码，再轮询结果。只有 `authorization_pending`／`slow_down` 允许继续轮询；拒绝、设备码过期、配置错误、HTTP 错误、网络结果未确认和无效响应分别返回，不把它们当作继续等待。取消会取消实际 HTTP Call，不自动重新取码或登录。

轮询按响应间隔等待；省略 interval 时使用 RFC 8628 的 5 秒协议默认，slow_down 增加至少 5 秒并尊重服务器给出的更长间隔。采用单调时钟，从挑战请求前开始保守计算过期边界，过期后不再请求。等待／时钟可在测试注入，生产仍用系统单调时间和协程延迟。规则依据 [GitHub 设备码文档](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-user-access-token-for-a-github-app#using-the-device-flow-to-generate-a-user-access-token)和 [RFC 8628](https://www.rfc-editor.org/rfc/rfc8628.html)。

请求只把公开 Client ID 和设备码放在表单体，不携带 Client Secret、不增加 OAuth scope。展示链接只接受官方 `https://github.com/login/device`；凭据只接受可用于 HTTP 头的可打印 ASCII，避免头构造异常将原始值带入异常文本。公开凭据／提示对象的 `toString()` 遮蔽内容，解码错误不保留可能包含令牌的响应正文或异常原因。

凭据 POST 禁止重定向和自动重放。自审在实际锁定的 OkHttp 5.4.0 复现：仅 `retryOnConnectionFailure(false)` 仍会在 `503 + Retry-After: 0` 后重发令牌请求，新回归因此失败；请求体改为 `isOneShot()` 后原断言通过。显式收到待授权结果后的下一轮协议轮询与传输层自动重放分开，前者仍按服务器间隔执行。结果丢失不尝试猜测是否已经生成令牌。

初始协议检查点没有存储、App 会话或刷新 API；后续已增加单次刷新 API 并接入应用级会话，交接和持久化见[Android 会话](github-session.md)。返回令牌仍不等于已持久化登录，不能把一次协程返回当作安全存储证明。GitHub 当前文档允许设备码用户令牌的刷新请求省略客户端密钥，但仍需实现轮换交接，不能盲目重放刷新。见[刷新合同](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/refreshing-user-access-tokens)。

GitHub 和 RFC 将设备码流主要用于输入受限或无回调的客户端，并不把它当成所有手机 OAuth 的首选。这里沿用既定的独立 Android／模拟器路线，因为 GitHub 当前 Web 兑换接口仍要求 Client Secret，不能把密钥塞入 APK；这是本项目的取舍，不是通用安全结论。[GitHub 流程比较](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-user-access-token-for-a-github-app)。

## 任务源接线

原 `GitHubTaskSource` 增加可选 suspend 凭据提供者；缺省仍为未登录读取，不改变启动拉取、启用、版本锁或失败处理。JSON／资源请求从当前会话取得值，只放在请求头；任务 JSON 和远端附件不能提供令牌或改变请求仓库。API origin 的 401 保持 HTTP 错误并通知会话拒绝该请求凭据，不自动清空本地版本或再次请求。刷新由后续凭据请求按会话合同处理，非 API origin 的 401 不拒绝 GitHub 凭据。

初始检查点的正式 `AssistApplication` 使用缺省未登录提供者，独立 Android 集成以模拟设备码返回的令牌验证原 `TaskUpdates` 的 JSON 安装及后续 401 保留当前版本。后续正式 App 已改为应用级凭据提供者，并完成真实授权与私有空 Release 读取；开发者有效写权限和入口仍未实现。

附件下载沿用既有 GitHub asset ID、长度／摘要核对及资源写入静默边界。OkHttp 跨 origin 重定向会移除 Authorization，已用两个独立本地服务实际断言；不把这一结果推广到任意自定义 `Call.Factory`。私有 API 的 404 仍须核对访问，不冒充“没有发布”。支持 200／302 的依据见[附件接口](https://docs.github.com/en/rest/releases/assets#get-a-release-asset)。

本轮测试、实际构建／安装摘要和重放回归前后证据见 [github-auth-protocol](build-evidence/github-auth-protocol/README.md)。该历史批次不证明后续会话；私有存储、应用级登录／取消、原生 UI、续期与实际进程恢复的新增证据见[Android 会话](github-session.md)。真实非空私有附件、真实到期续期和开发者权限仍待验证；M2 保持进行中。
