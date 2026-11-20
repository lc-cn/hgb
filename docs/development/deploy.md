# 部署

线上分两处：

| 部分 | 放在哪 | 地址 |
| --- | --- | --- |
| 首页、文档站（`client/site`）和控制台（`client/web`） | Vercel，一个项目 | `https://hgb.l2cl.link/`、`/docs/`、`/console/` |
| 服务端（`core/server`） | 自己的主机 | `https://api-hgb.l2cl.link/api/...` |

控制台直接请求 `api-hgb.l2cl.link`，不经过 Vercel 转发，实时连接（SSE）和 App 的长连接都直连主机。两个域名同属 `l2cl.link`，是“同站”，登录 cookie 会随请求带上；服务端只允许 `YYS_ALLOWED_ORIGINS` 里的来源跨域调用。

## Vercel：首页、文档站和控制台

1. 在 Vercel 导入 GitHub 仓库 `lc-cn/hgb`，根目录用仓库根目录。仓库根目录的 `vercel.json` 用 Vercel 的服务模式（Services）把它定义成一个项目里的两个服务，构建命令、输出目录都写在里面，不用在页面上填：

   | 服务 | 目录 | 对外路径 |
   | --- | --- | --- |
   | `site` | `client/site`（Astro） | `/` 和 `/docs/`，以及其他所有没匹配上的路径 |
   | `console` | `client/web`（Vite） | `/console/`；`/console` 会跳到 `/console/` |

   两个服务之间不互相调用，所以没有绑定（bindings）。控制台调用的 API 在自己的主机上（`api-hgb.l2cl.link`），不是 Vercel 的服务。
2. 环境变量加 `VITE_API_ORIGIN=https://api-hgb.l2cl.link`（Production 和 Preview 都要），控制台构建时会写进页面。
3. Node.js 版本选 24.x（Astro 7 要求 22.23 以上，22.x 的小版本不一定够）。Vercel 按锁文件版本（lockfile v9）自动选 pnpm 9 以上，不用另外装。
4. 两个服务的构建都会读仓库根目录的 `design/tokens.json`（生成样式），项目设置里“包含根目录以外的文件”要保持打开（默认就是开的）。
5. 在项目的 Domains 里加 `hgb.l2cl.link`，按提示在 DNS 里添加记录。

**预览部署登不上**：Preview 部署的地址是 `*.vercel.app`，和 `api-hgb.l2cl.link` 不同站，浏览器不会带上登录 cookie，服务端也不在允许的来源里，所以预览版的首页和文档能看，控制台登录不了。要在预览里试控制台，给 Preview 分支绑一个 `l2cl.link` 下的子域名（如 `preview-hgb.l2cl.link`），并把它加进服务端的 `YYS_ALLOWED_ORIGINS`（逗号分隔）。

Vercel 把请求原样交给服务，`/console/assets/...` 到了控制台服务还是这个路径，所以控制台构建到 `client/web/dist/console/`，服务的输出目录是 `client/web/dist`。控制台服务自己的改写规则把 `/console/` 下除静态资源以外的地址都交给 `index.html`，直接打开或刷新深层地址不会 404。

本地一起跑两个服务用 `vercel dev`。要放到 Vercel 以外的静态主机，用 `scripts/build-sites.sh` 把网站和控制台合并到 `dist-sites`（网站在根路径，控制台在 `/console`）：

```bash
VITE_API_ORIGIN=https://api-hgb.l2cl.link bash scripts/build-sites.sh
```

## 主机：服务端

当前部署在 `213.35.99.41`（Ubuntu，已装 OpenJDK 21，nginx 同时服务这台主机上的其他站点）。`api-hgb.l2cl.link` 在 Cloudflare 上指向这台主机并开启代理，SSL/TLS 模式为 Full (strict)。

### 目录和账号

| 位置 | 用途 |
| --- | --- |
| `/opt/hgb/releases/<提交号>-<运行编号>-<重试次数>/` | 每次发布一个目录，内容是打包文件里的 `bin`、`lib` 和 `release.json`（文件摘要） |
| `/opt/hgb/server` | 软链接，指向正在用的发布目录；回滚就是把它指回上一个 |
| `/etc/hgb/server.env` | 环境变量（root，权限 600，不进仓库） |
| `/var/lib/hgb/hgb.db` | 全部数据，一个 SQLite 文件 |
| 系统用户 `hgb`、`hgb-deploy` | `hgb` 运行服务；`hgb-deploy` 只供 GitHub Actions 发布用 |
| `/var/backups/hgb/<发布标识>/` | 每次发布前的数据库快照和发布记录 |

`/etc/hgb/server.env`：

```bash
YYS_SECRET=<openssl rand -base64 48 生成，之后不要再改>
YYS_DB=/var/lib/hgb/hgb.db
YYS_HOST=127.0.0.1
YYS_PORT=8787
YYS_PUBLIC_URL=https://api-hgb.l2cl.link
YYS_CONSOLE_URL=https://hgb.l2cl.link/console/
YYS_ALLOWED_ORIGINS=https://hgb.l2cl.link
GITHUB_CLIENT_ID=<GitHub App 的 Client ID>
GITHUB_CLIENT_SECRET=<GitHub App 的 Client secret>
# 发信：阿里云邮件推送的 SMTP（设计文档 C4）。不设 YYS_SMTP_HOST 就没有邮箱登录和邮件通知
YYS_SMTP_HOST=smtpdm.aliyun.com
YYS_SMTP_PORT=465
YYS_SMTP_USER=noreply@mail.l2cl.link
YYS_SMTP_PASSWORD=<阿里云给这个发信地址设的 SMTP 密码>
YYS_MAIL_FROM_NAME=护肝宝
```

- `YYS_SECRET` 用来给验证码做摘要，也用来派生通知渠道密钥的加密密钥；换掉它，已保存的渠道密钥就解不开了，需要重新填写。
- `YYS_SECURE_COOKIES` 不要设，线上必须用安全 cookie。
- `YYS_CHANNELS_PRIVATE` 不要设，线上不允许通知发往内网。
- GitHub 登录用 GitHub App `yys-helper`（App 下载任务版本用的也是它，App 用它的设备授权换来的令牌登录服务端）。在 GitHub App 设置里：
  - Callback URL 加上 `https://api-hgb.l2cl.link/api/auth/github/callback`；
  - 生成一个 Client secret，和 Client ID 一起填进上面的 `GITHUB_CLIENT_ID`、`GITHUB_CLIENT_SECRET`。
- GitHub 登录时服务端会给浏览器发一个只在这次回调里核对的 cookie（`yys_oauth`，10 分钟），从别人发来的链接完成的登录会被拒绝。

### 发布一个新版本

服务端和 App 都由 GitHub Actions 的“Build”工作流（`.github/workflows/build.yml`）构建：

| 触发方式 | 服务端 | 安卓 App |
| --- | --- | --- |
| 推送到 master，且改到服务端（`core/`、Gradle 配置、`deploy/host/`、工作流本身） | 测试、打包，放进滚动预发布 `server-edge`（只留最近 5 个），部署到主机 | 不构建 |
| 推送版本标签 `v1.2.3` | 打包放进发布 `v1.2.3`，部署这个版本 | 测试、签名打包，放进同一个发布 |
| 在 Actions 页面手动运行 | 勾选“构建服务端并部署到主机” | 勾选“构建安卓 App”，必须填版本号；填了版本号就建发布 `v版本号` |

部署由主机自己从 Releases 下载：

1. 工作流用仓库 Secret `HGB_DEPLOY_SSH_KEY`（专用部署密钥）和 `HGB_DEPLOY_KNOWN_HOSTS`（主机指纹），以 `hgb-deploy` 身份连到变量 `HGB_DEPLOY_HOST` 指的主机，发送 `fetch <提交号>-<运行编号>-<重试次数>`，标准输入里只有服务端包在发布里的下载地址、SHA-256 和大小。主机经 GitHub 的 CDN 下载 Releases 约每秒 6 MB；构建机往主机推、或主机下载 Actions 构建产物，都只有每秒 70 KB 左右。
2. 主机上 `hgb-deploy` 的密钥被限定只能执行 `/usr/local/libexec/hgb-receive`：`fetch` 只下载 `https://github.com/lc-cn/hgb/releases/download/<发布>/server-*.tgz`（跳转后必须落在 `*.githubusercontent.com`），上限 256 MB，核对 SHA-256 后才解包；包里只接受顶层是 `server/` 的普通文件，解到 `/opt/hgb/releases/<发布标识>/`，记下每个文件的摘要，再调用 `sudo /usr/local/sbin/hgb-activate`（sudoers 只允许这一个命令）。`deploy` 命令仍可从标准输入直接收包，留给手动部署。
3. `hgb-activate` 停服务，把数据库快照到 `/var/backups/hgb/<发布标识>/`，切换 `/opt/hgb/server`，启动后检查 `/api/me` 返回未登录；不健康就恢复数据库快照、切回原来的版本并让工作流失败。另外每天备份一次（`hgb-backup.timer` 运行 `hgb-activate --daily`）：服务不停，用 SQLite 在线备份复制到 `/var/backups/hgb/daily-YYYYMMDD/`，核对完整性。两种快照都只留 14 天，每次部署后和每天备份后删除更早的，隐私政策里也这样写。备份和数据库在同一台主机上，防误删和坏版本，不防主机丢失。

主机上这两个脚本的源文件在仓库的 `deploy/host/`，改了以后要手动装到主机（`sudo install -o root -g root -m 755`），属主必须是 root。同一个发布标识不能重复部署；要重试就在 GitHub 上重新运行（重试次数会变）。主机和密钥的准备都已完成，换主机或换密钥时才需要重新设置这几个 Secret。

数据库表结构由服务端启动时自己建好；发布前没有正式用户，不做迁移（改了表结构就清库重来）。

### systemd

`/etc/systemd/system/hgb.service`（已启用，开机自启）：

```ini
[Unit]
Description=HGB server
After=network-online.target

[Service]
Type=simple
User=hgb
Group=hgb
WorkingDirectory=/var/lib/hgb
EnvironmentFile=/etc/hgb/server.env
Environment=JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
ExecStart=/opt/hgb/server/bin/server
Restart=on-failure
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
StateDirectory=hgb
StateDirectoryMode=0700
UMask=0077

[Install]
WantedBy=multi-user.target
```

`ProtectSystem=strict` 让整个文件系统只读：`StateDirectory=hgb` 创建 `/var/lib/hgb` 并让服务可写；`PrivateTmp=true` 给服务一个可写的私有 `/tmp`，SQLite 驱动启动时要把原生库解压到那里，少了它服务会起不来。看日志用 `journalctl -u hgb`。

### nginx 和证书

服务端只监听本机 8787，由 nginx 提供 HTTPS（站点文件 `/etc/nginx/sites-available/hgb`）：

- 80 端口只留证书续期用的 `/.well-known/acme-challenge/`（目录 `/var/lib/letsencrypt`），其余跳转 HTTPS；
- 443 端口用 Let's Encrypt 证书（`/etc/letsencrypt/live/api-hgb.l2cl.link/`，certbot 用 webroot 方式自动续期），`/api/` 转给 `127.0.0.1:8787`，其余返回 404；
- `/api/device/socket` 开 WebSocket 升级，读写超时 1 小时；
- `/api/events` 关 `proxy_buffering`，读超时 1 小时，控制台的实时事件才能立即送出；
- 从 Cloudflare 的 IP 段取 `CF-Connecting-IP` 作为访客地址（只影响日志，服务端不用访客 IP）。Cloudflare 的 IP 段变了要更新这份列表。

Cloudflare 会断开 100 秒没有数据的连接；事件流每 15 秒发一次保活，设备长连接两端每 30 秒（App）和 20 秒（服务器）各 ping 一次，都不受影响。

**国内移动网络经 Cloudflare 很不稳（2026-11-20 实测）。** 同一台手机（5G）ping 百度、Vercel 上的网站、api.github.com 都不丢包（65～130 ms），ping `api-hgb.l2cl.link`（Cloudflare 免费版）丢包 50%～80%、延迟 300 ms 以上，直连主机 IP 丢包约 10%、280 ms；电脑宽带走两条路都正常。表现是设备连接每分钟因心跳超时断一次，交给手机的作业迟迟确认不了。代码上已经按弱网处理：作业确认期限 2 分钟，App 的请求以“连接 10 秒、20 秒没有数据”判定卡住并重试，心跳 30 秒。网络上的选择（关掉 Cloudflare 代理直连主机，或换国内访问更好的线路）还没定；判断前先在手机上用真实 HTTPS 请求对比两条路，而不只看 ping。改完 nginx 先 `sudo nginx -t` 再 `sudo systemctl reload nginx`。

## App

App 的“云端控制”里，服务器地址填 `https://api-hgb.l2cl.link`。正式版只接受 https 地址。

### 发布安卓版

推送形如 `v1.2.3` 的标签（或手动运行“Build”并填版本号），工作流的 App 部分会：

1. 装好 SDK 37.0、构建工具 36、NDK 和 CMake，按 `client/android/vision/inputs.lock.json` 下载并核对 MaaFramework 原生库；
2. 跑引擎、共用模块和 App 的单元测试；
3. 用仓库 Secret `HGB_ANDROID_KEYSTORE`（base64 的 PKCS12）和 `HGB_ANDROID_KEYSTORE_PASSWORD` 签名打包，版本名取标签（`1.2.3`），版本号是 `1×10000 + 2×100 + 3`；
4. 核对签名证书后，和同版本的服务端包 `server-1.2.3.tgz` 一起放进发布 `v1.2.3`，附各自的 `.sha256`。

```bash
git tag v0.1.1 && git push origin v0.1.1
```

签名密钥：别名 `hgb`，证书 SHA-256 `B4:8C:9F:15:D0:9C:69:77:DB:0A:0B:2F:9D:DA:AF:7D:DA:6D:2A:D0:FE:54:A2:D3:96:41:AE:3B:D7:20:07:77`。密钥文件和密码由维护者离线保管，丢失后无法再发布能覆盖安装的更新。原生库在 APK 里压缩存放（约 67 MB），安装时由系统解压。

本仓库的发布只放安装包和服务端包；任务包发布在 `lc-cn/hgb-tasks`（设计文档 C46）。

## fork 后部署自己的一套

这一套的地址和仓库只写在 `config/instance.json`，其他地方都从它或构建变量读，fork 不用改代码：

| 值 | 默认（`config/instance.json`） | fork 怎么改 | 谁用 |
| --- | --- | --- | --- |
| API 地址 `apiUrl` | `https://api-hgb.l2cl.link` | 仓库变量 `HGB_API_URL`；网页控制台用 Vercel 环境变量 `VITE_API_ORIGIN` | App 登录页预填的服务器地址、网页控制台 |
| 网站地址 `siteUrl` | `https://hgb.l2cl.link` | 仓库变量 `HGB_SITE_URL` | App“我的”里的文档、下载链接 |
| 代码仓库 `codeRepo` | `lc-cn/hgb` | 不用设：工作流取当前仓库，Vercel 取它正在构建的仓库 | 反馈、GitHub、下载、文档编辑链接；主机只从它的 Releases 拉服务端 |
| 任务仓库 `taskRepo` | `lc-cn/hgb-tasks` | 仓库变量 `HGB_TASK_REPO` | App 下载任务版本（用户也能在 App“开发 → 设置 → 任务来源”里改） |
| GitHub App `githubClientId` | `yys-helper` 的 Client ID | 仓库变量 `HGB_GITHUB_CLIENT_ID` | App 的 GitHub 授权 |

还要准备：

1. **自己的 GitHub App**：开启设备授权（Device Flow），回调地址填 `<你的 API 地址>/api/auth/github/callback`，装到你的任务仓库（Contents 读写）。Client ID 填进 `HGB_GITHUB_CLIENT_ID`，Client ID 和 Client secret 填进主机的 `/etc/hgb/server.env`。
2. **主机**：照上面“主机：服务端”一节准备目录、`server.env`、systemd、nginx 和证书；把 `deploy/host/` 下的两个脚本装到 `/usr/local/libexec/hgb-receive`、`/usr/local/sbin/hgb-activate`，两个 `hgb-backup.*` 装到 `/etc/systemd/system/` 并 `systemctl enable --now hgb-backup.timer`；建 `hgb-deploy` 用户，`authorized_keys` 里限定部署密钥只能执行 `hgb-receive`，sudoers 只允许它执行 `hgb-activate`；写 `/etc/hgb-receive.json`（root，644）：`{"repository": "<你的用户名>/<仓库名>"}`。
3. **仓库 Secret 和变量**：`HGB_DEPLOY_SSH_KEY`、`HGB_DEPLOY_KNOWN_HOSTS`、变量 `HGB_DEPLOY_HOST`；要发 App 还要 `HGB_ANDROID_KEYSTORE`、`HGB_ANDROID_KEYSTORE_PASSWORD`（自己生成的签名密钥，别名 `hgb`）。
4. **Vercel**：导入 fork 的仓库，设 `VITE_API_ORIGIN`，绑定自己的域名。
5. **服务端**的 `YYS_PUBLIC_URL`、`YYS_CONSOLE_URL`、`YYS_ALLOWED_ORIGINS` 写自己的地址。

App 和以后的 Windows 版里，服务器地址在登录时可以改成任何一台护肝宝服务器；网页控制台和它的 API 绑定部署，不做运行时切换。

## 还没解决的

- **发信服务**：用阿里云邮件推送（设计文档 C4），发信域名 `mail.l2cl.link`，发信地址 `noreply@mail.l2cl.link`，服务端经 `smtpdm.aliyun.com:465`（SSL）发送。主机所在的 Oracle 云封了出站 25 端口、IP 没有反向解析，不能自己直接投递。服务端没配 `YYS_SMTP_HOST` 时，`/api/auth/options` 返回 `email: false`，登录页只显示 GitHub 登录，通知渠道里也没有“邮件”。发送失败时用户看到“邮件发送失败，请稍后再试”，服务商的原因记在 `journalctl -u hgb` 里。`YYS_MAIL_MODE=console` 只是把验证码打印到服务端日志，仅限本地开发，线上不要用。
- **国内访问 GitHub**：国内网络常连不上 `github.com`，GitHub 登录（网页跳转授权、App 设备码授权）在不开代理时用不了；下载任务走的 `api.github.com` 和 `*.githubusercontent.com` 能连上。国内用户主要用邮箱登录。
- **备份**：数据都在 `YYS_DB` 这一个 SQLite 文件里，需要自己定时备份，比如在服务停止或用 `sqlite3 hgb.db ".backup /backup/hgb-$(date +%F).db"` 做在线备份。
- **访问速度**：Vercel 在中国大陆没有节点，大陆访问首页和控制台可能偏慢；`.link` 域名不能做 ICP 备案，用不了国内 CDN。
