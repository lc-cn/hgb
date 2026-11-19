# 云端控制台开发说明

设计见 [云端控制与 App 执行器化方案](../design/cloud-executor.md)。本文说明当前已实现的范围和本地运行方法。

## 已实现

| 模块 | 内容 |
| --- | --- |
| 用户 | 邮箱密码、邮箱验证码、找回密码、GitHub 登录，登录限频，会话可吊销 |
| 设备 | App 登录后自动注册，上报在线状态和可用任务，控制台可解绑 |
| 作业 | 控制台下发、租约、取消、结果上报、失联处理 |
| Web 控制台 | 登录注册、设备列表、下发作业、作业记录 |
| App | 设置 → 云端控制，登录后自动领取并执行作业 |

作业状态规则：

- 设备领取后 30 秒内必须确认开始，否则作业回到队列。
- 已开始的作业失联后标记为“结果未知”，**绝不自动重试**，避免重复操作游戏。
- App 重启后用同一个命令编号恢复作业，不会重复启动。

## 尚未实现

- 游戏登录态的加密托管、下发和回传（要先在专用 Root 设备上验证）。
- 云端计划调度：计划目前仍在 App 本地。
- 正式发信服务：未配置时，需要发邮件的接口返回“发信服务未配置”。
- 生产部署、HTTPS 证书、数据库备份。

## 本地运行

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :core:server:installDist
YYS_SECRET="$(openssl rand -base64 48)" YYS_MAIL_MODE=console YYS_SECURE_COOKIES=false core/server/build/install/server/bin/server
```

打开 <http://127.0.0.1:8787>。`YYS_MAIL_MODE=console` 会把验证码打印到服务端控制台，只能用于本地开发。

模拟器里的调试版 App 用 `http://10.0.2.2:8787` 连接本机服务。正式版只接受 https 地址。

## 环境变量

| 变量 | 必填 | 说明 |
| --- | --- | --- |
| `YYS_SECRET` | 是 | 至少 32 位随机字符，用于验证码摘要 |
| `YYS_DB` | 否 | SQLite 文件路径，默认 `yys-cloud.db` |
| `YYS_HOST` / `YYS_PORT` | 否 | 监听地址，默认 `127.0.0.1:8787` |
| `YYS_PUBLIC_URL` | 否 | 对外地址，GitHub 回调要用 |
| `YYS_SECURE_COOKIES` | 否 | 只有本地 http 调试时才设为 `false` |
| `YYS_MAIL_MODE` | 否 | `console` 仅用于本地开发 |
| `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET` | 否 | 不配置时 GitHub 登录不可用 |
