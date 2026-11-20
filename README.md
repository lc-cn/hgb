# 护肝宝

护肝宝替你把游戏里每天重复的日常跑完：在手机上按任务的步骤看画面、点按钮，到点自动开始，需要时先切到对应的游戏账号，跑完把结果推给你。网页控制台管理所有设备、游戏账号、计划和通知。第一个支持的游戏是阴阳师，任务还在真机上调试，尚未正式发布。

- 不需要 Root，不问、不保存游戏密码；切号只从游戏自己记住的账号里选。
- 认不出画面、遇到没见过的弹窗就停下来等人，不会乱点；没法确认的结果标为“待核对”，不假装成功。
- 开源：代码在 GitHub，欢迎在 [Issues](https://github.com/lc-cn/hgb/issues) 反馈问题。

## 组成

| 部分 | 是什么 | 位置 |
| --- | --- | --- |
| 安卓 App | 执行端：无障碍 + 截图操作游戏，悬浮球，本机运行；也能管理账号、设置和计划 | `client/android/app` |
| 服务端 | 账号、游戏账号、任务设置和计划都存这里；计时、分配设备、下发作业；事件规则和通知渠道 | `core/server`（Ktor + SQLite） |
| 网页控制台 | 管理设备、游戏账号、计划、运行记录和通知，挂在 `/console` | `client/web`（React + TypeScript + Vite） |
| 共用页面 | 控制台的五个页面（设备、游戏账号、任务库、通知、我的），安卓 App 和以后的 Windows 版共用 | `client/shared`（Kotlin 多平台 + Compose Multiplatform） |
| 首页和文档站 | 首页 `/`，文档 `/docs` | `client/site`（Astro + Starlight） |
| 执行引擎 | 任务包格式、步骤程序、执行器、云端代理；安卓和以后的 Windows 端共用 | `core/engine` |
| 协议 | App 与服务端之间的数据类型 | `core/protocol` |
| 设计变量 | 颜色、字号、间距、圆角、断点的唯一来源，生成网页和网站的 CSS | `design/tokens.json` |

安卓 App 登录后用 `client/shared` 的页面，和网页同一套结构；本机才有的运行条、悬浮球和“开发”页留在 App 里。Windows 端计划用 Compose Multiplatform 桌面版，同样接 `client/shared`，还在计划中。

```text
core/
  protocol/        App 与服务端的接口类型
  engine/          任务包、步骤程序、执行器、云端代理（CloudAgent）
  server/          服务端，只提供 /api
client/
  android/app/     安卓 App
  android/vision/  Maa 识别子进程与原生库
  shared/          控制台页面，安卓和 Windows 共用
  web/             网页控制台（/console）
  site/            首页和文档站（/、/docs）
design/tokens.json 设计变量
docs/              需求、设计和开发资料
```

依赖方向只能从 client 指向 core，core 内部为 server → engine → protocol。

## 开发

需要 JDK 17、Android SDK（compileSdk 37、Build Tools 36.0.0）、Node 22.23 以上和 pnpm。设置 `JAVA_HOME`，SDK 路径放在环境变量 `ANDROID_HOME` 或不提交的 `local.properties` 里。

测试和构建：

```bash
./gradlew :core:engine:test :core:server:test :client:android:app:assembleDebug
```

```bash
pnpm --dir client/web install && pnpm --dir client/web build
```

```bash
pnpm --dir client/site install && pnpm --dir client/site build
```

本地运行服务端、网页控制台和首页、文档站：

```bash
./gradlew :core:server:installDist
YYS_SECRET="$(openssl rand -base64 48)" YYS_MAIL_MODE=console YYS_SECURE_COOKIES=false core/server/build/install/server/bin/server
```

```bash
pnpm --dir client/web dev
```

```bash
pnpm --dir client/site dev
```

控制台在 <http://127.0.0.1:5173/console/>，首页和文档在 <http://127.0.0.1:4321/>。服务端的环境变量、部署方式见[云端控制台](docs/development/cloud-console.md)。改颜色、字号等只改 `design/tokens.json`，构建时会重新生成各端的样式。

APK 输出在 `client/android/app/build/outputs/apk/debug/app-debug.apk`。

## 进度

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| 第 1 期 | 设备连接、在线状态、网页实时查看 | 完成 |
| 第 2 期 | 游戏账号、服务端计划和调度、网页下发和控制、识别账号和界面切号 | 完成（阴阳师的识别和切号任务还在调试任务包里，未发布） |
| 第 3 期 | 通知：事件规则、钉钉 / 企业微信 / Bark / 邮件 / Webhook / 本机通知、设备上报的事件 | 完成 |
| 网站 | 首页和文档站；Vercel 部署配置 | 完成 |
| 三端统一 | 安卓 App 改成和网页同一套页面结构（完成）；Windows 版（计划中） | 进行中 |
| 第 4 期 | 任务版本上传、服务端草稿和当前版本、设备自动下载、开发页 | 未开始 |
| 第 5 期 | AI 识图 | 未开始 |

## 资料

- [云端方案与已确认的决定](docs/design/cloud-executor.md)：账号、调度、切号、通知、三端界面等的设计和决定记录
- [设计稿](https://claude.ai/artifact/7VRpYb4jWcQSAP7xa5Lyhk)：规范（三端）、网站、网页控制台、各页窄档和中档、App
- [云端控制台开发说明](docs/development/cloud-console.md)
- [部署](docs/development/deploy.md)：首页、文档站和控制台放 Vercel，服务端放自己的主机
- [开发基线与验收](docs/development/README.md)、[实现进度](docs/development/implementation-status.md)
- [需求](docs/PRD.md)、[设计与技术资料索引](docs/design/README.md)
