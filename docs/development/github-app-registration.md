# GitHub App 注册配置

2026-11-17；用户报告 App `yys-helper` 已创建并安装到 `lc-cn/yys-helper`，App ID `5794664`、公开 Client ID `Iv23liVW7AdPVQ5Ue4iz`，仅配置 Contents Read & Write。**用户已完成正式 App 的真实设备码授权，Device Flow 和私有源访问已实测；令牌过期配置及其他实际设置未逐项读取。本助手没有代为创建、安装或发布 Release。** 对应 [github-app-registration.json](../../config/github-app-registration.json) 将用户提供信息和建议注册项分开，不是 GitHub API 请求或可直接提交的 Manifest。配置中的 status 是注册准备时的检查点标签，实际联调结果以本文及会话证据为准；客户端仅从该文件读取公开 Client ID，不将建议注册项作为已验证设置。公开 Client ID 可随客户端配置分发，不能把数字 App ID 当作 OAuth Client ID。

当前仓库已只读核对为 `lc-cn/yys-helper`、个人账号所有、private、默认分支 master。开发机登录仍只用于现有代码协作，不传给 Android 客户端。

## 注册表单

注册表的建议值如下，已有 App 可据此核对，不再重复创建；实际变更与安装仍需核对用户已执行的状态。

| 表单项 | 计划值 | 原因 |
| --- | --- | --- |
| 所属账号 | `lc-cn` | 由仓库负责人管理 |
| GitHub App name | `yys-helper` | 用户报告已创建，App ID 另行记录 |
| Description | `Authorize task downloads and permitted task releases for the Android task container.` | 说明下载与授权发布用途 |
| Homepage URL | `https://github.com/lc-cn` | 使用公开负责人主页，避免把私有仓库当作公开介绍页 |
| Callback URL | 留空 | 使用设备码授权，不启用 Web 回调 |
| Expire user authorization tokens | 勾选 | 保留 GitHub 的令牌过期与续期机制 |
| Request user authorization during installation | 不勾选 | 用户在 Android App 主动发起设备码登录 |
| Enable Device Flow | 勾选 | 原生客户端获取用户令牌 |
| Setup URL / Redirect on update | 留空／不勾选 | 当前没有服务器安装流程 |
| Webhook Active / URL | 不勾选／留空 | 不接收仓库回调，无需部署 webhook 服务 |
| Repository permissions → Contents | Read & write | 读取私有任务附件；有权限的开发者发布 Release／上传附件 |
| Repository permissions → Metadata | Read-only | 仓库身份与访问核对，GitHub 必选基础权限 |
| 其余仓库、组织、用户权限 | No access | 不申请成员、邮件、管理、工作流、Issues 等权限 |
| Subscribe to events | 不选择 | 不增加事件订阅 |
| Where can this GitHub App be installed? | Any account | 允许指定开发者使用各自 GitHub 账号授权 |

GitHub 官方说明设备码流可忽略回调 URL，并可在注册表中启用；注册时还可关闭 webhook。主页、名称和表单约束据[注册文档](https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/registering-a-github-app)核对。令牌将按服务返回的有效期处理，不把原型时间或客户端自设时长当成 GitHub 合同。

**App 的公开可授权性与代码仓库可见性是两项设置。** 这里的 Any account 不公开 `lc-cn/yys-helper`，也不授予其他账号对该仓库的读写权。私有 GitHub App 的授权人范围受限，因此若需要负责人之外的开发者分别登录，建议使用公开可授权的 App。此配置仍待确认，不擅自修改任何仓库或 App 可见性。见[GitHub App 可见性](https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/making-a-github-app-public-or-private)。

## 安装范围与权限边界

创建获准后，在 `lc-cn` 下安装，选择 **Only select repositories → yys-helper**，不选 All repositories，不为其他仓库安装。App 权限 Contents write 同时涵盖代码内容写入，并不是“只准发 Release”的独立权限；GitHub 的[Release 创建](https://docs.github.com/en/rest/releases/releases#create-a-release)和[附件上传](https://docs.github.com/en/rest/releases/assets#upload-a-release-asset)均使用 Contents write。配置稿不申请 Workflows write，也不通过发布操作修改工作流或仓库设置。

客户端只对固定任务仓库核对有效权限；登录成功不等于开发者。普通拉取只执行读取；制作／发布入口还需指定仓库的有效写权限，发布前再次核对，最终由 GitHub API 执行权限检查。其他账号即使把公开 App 安装到自己的仓库，也不能用其自有仓库写权限解锁本项目开发者模式。App 用户令牌受用户权限、App 权限及安装仓库范围共同限制，依据[用户令牌权限交集](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/authenticating-with-a-github-app-on-behalf-of-a-user)。

有一项当前环境限制需纠正泛化说法：**个人私有仓库不能邀请只读协作者**。当前本人及获准协作者可以访问；未获仓库访问权的人不能仅凭 GitHub 登录读取私有 Release，客户端须区分不可访问与没有发布。若以后需要向外部测试者提供私有只读任务，需要另定分发方案；这里不迁移仓库、不新增后端，也不为测试者自动授写权。见[个人仓库权限](https://docs.github.com/en/enterprise-cloud%40latest/repositories/managing-your-repositorys-settings-and-features/repository-access-and-collaboration/permission-levels-for-a-personal-account-repository)。之后若用户自行公开仓库，普通公开附件可沿用现有未登录读取路径。

## Android 接入所需信息

注册完成后只需公开 **Client ID**，而不是 App ID。不要生成或提供 Client Secret、App private key、开发机 Token；此路线不需要把这些凭据放进 APK。

已核对当前官方合同：设备码流使用 Client ID 获取挑战及用户令牌；对**由设备码流生成的用户令牌**，刷新请求的 Client Secret 也可省略。此例外不适用于普通 Web 授权码兑换，不能把 Web 流误当成无密钥客户端流程。依据[生成用户令牌](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-user-access-token-for-a-github-app#using-the-device-flow-to-generate-a-user-access-token)和[刷新用户令牌](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/refreshing-user-access-tokens#refreshing-a-user-access-token-with-a-refresh-token)。

设备码请求／轮询／取消和鉴权任务源已有 [core 协议实现](github-device-authorization.md)，后续已接入正式 [Android 会话](github-session.md)：原生授权页、Keystore 私有存储及单次续期交接通过本地集成验证，用户真实授权和私有源空 Release 读取已成功。开发者有效写权限、真实到期续期和非空私有附件仍待验证。刷新会使旧令牌失效，请求结果未知不能盲目重放。实际权限撤销、重启恢复和私有附件下载都要用最终 App 令牌验证；开发机成功查询仓库不代替客户端验证。

## 注册评审与当前边界

原配置稿建议在 `lc-cn` 创建可由其他账号授权的 GitHub App，仅安装到 `lc-cn/yys-helper`，授予 Contents write／Metadata read；用户已报告注册／安装完成，不重复请求创建。是否允许其他账号授权仍需核对；仓库继续私有。创建／安装的恢复方式是卸载该仓库安装并删除新 App 注册；这不会撤回此前发布、修改的代码或已下载附件。因此本次不发 Release、不发消息、不修改仓库内容或成员。

目前已准备配置并核对官方合同，用户报告注册／安装完成且已提供 Client ID。随后正式 App 登录与私有任务源读取已实际成功，重开保留会话；当前没有已发布版本，因此非空附件及指定开发者写权限仍待验证，不能凭这份清单声称 M2 已完成。公开 App 查询返回 404，开发机凭据不能调用 GitHub App 用户安装列表（403）；均不据此否定用户报告或把权限查询失败当作无安装。
