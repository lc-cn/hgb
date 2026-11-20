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
```

- `YYS_SECRET` 用来给验证码做摘要，也用来派生通知渠道密钥的加密密钥；换掉它，已保存的渠道密钥就解不开了，需要重新填写。
- `YYS_SECURE_COOKIES` 不要设，线上必须用安全 cookie。
- `YYS_CHANNELS_PRIVATE` 不要设，线上不允许通知发往内网。
- GitHub 登录用 GitHub App `yys-helper`（App 下载任务版本用的也是它，App 用它的设备授权换来的令牌登录服务端）。在 GitHub App 设置里：
  - Callback URL 加上 `https://api-hgb.l2cl.link/api/auth/github/callback`；
  - 生成一个 Client secret，和 Client ID 一起填进上面的 `GITHUB_CLIENT_ID`、`GITHUB_CLIENT_SECRET`。
- GitHub 登录时服务端会给浏览器发一个只在这次回调里核对的 cookie（`yys_oauth`，10 分钟），从别人发来的链接完成的登录会被拒绝。

### 发布一个新版本

推送到 master 且改到服务端（`core/`、Gradle 配置）时，GitHub Actions 的“Deploy server”工作流（`.github/workflows/deploy-server.yml`）会自动跑测试、打包并部署；也可以在 GitHub 的 Actions 页面手动运行。

1. 工作流用仓库 Secret `HGB_DEPLOY_SSH_KEY`（专用部署密钥）和 `HGB_DEPLOY_KNOWN_HOSTS`（主机指纹），以 `hgb-deploy` 身份连到变量 `HGB_DEPLOY_HOST` 指的主机，发送 `deploy <提交号>-<运行编号>-<重试次数>`，把 gzip 压缩的打包文件从标准输入传过去。
2. 主机上 `hgb-deploy` 的密钥被限定只能执行 `/usr/local/libexec/hgb-receive`：它只接受顶层是 `server/` 的普通文件，解到 `/opt/hgb/releases/<发布标识>/`，记下每个文件的摘要，再调用 `sudo /usr/local/sbin/hgb-activate`（sudoers 只允许这一个命令）。
3. `hgb-activate` 停服务，把数据库快照到 `/var/backups/hgb/<发布标识>/`，切换 `/opt/hgb/server`，启动后检查 `/api/me` 返回未登录；不健康就恢复数据库快照、切回原来的版本并让工作流失败。

同一个发布标识不能重复部署；要重试就在 GitHub 上重新运行（重试次数会变）。主机和密钥的准备都已完成，换主机或换密钥时才需要重新设置这几个 Secret。

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

Cloudflare 会断开 100 秒没有数据的连接；事件流每 15 秒发一次保活，设备长连接每 20 秒 ping 一次，都不受影响。改完 nginx 先 `sudo nginx -t` 再 `sudo systemctl reload nginx`。

## App

App 的“云端控制”里，服务器地址填 `https://api-hgb.l2cl.link`。正式版只接受 https 地址。

### 发布安卓版

推送形如 `v1.2.3` 的标签，GitHub Actions 的“Release App”工作流（`.github/workflows/release-app.yml`）会：

1. 装好 SDK 37、构建工具 36、NDK 和 CMake，按 `client/android/vision/inputs.lock.json` 下载并核对 MaaFramework 原生库；
2. 跑引擎、共用模块和 App 的单元测试；
3. 用仓库 Secret `HGB_ANDROID_KEYSTORE`（base64 的 PKCS12）和 `HGB_ANDROID_KEYSTORE_PASSWORD` 签名打包，版本名取标签（`1.2.3`），版本号是 `1×10000 + 2×100 + 3`；
4. 核对签名证书后，在本仓库建同名发布，附 `hgb-1.2.3.apk` 和它的 `.sha256`。

```bash
git tag v0.1.0 && git push origin v0.1.0
```

签名密钥：别名 `hgb`，证书 SHA-256 `B4:8C:9F:15:D0:9C:69:77:DB:0A:0B:2F:9D:DA:AF:7D:DA:6D:2A:D0:FE:54:A2:D3:96:41:AE:3B:D7:20:07:77`。密钥文件和密码由维护者离线保管，丢失后无法再发布能覆盖安装的更新。原生库在 APK 里压缩存放（约 67 MB），安装时由系统解压。

本仓库的发布只放安装包；任务包发布在 `lc-cn/hgb-tasks`（设计文档 C46）。

## 还没解决的

- **发信服务**：首次上线不接（设计文档 C45），只用 GitHub 登录。服务端没有发信服务时，`/api/auth/options` 返回 `email: false`，网页和 App 的登录页只显示 GitHub 登录，通知渠道里也没有“邮件”。以后接发信时走 SMTP。`YYS_MAIL_MODE=console` 只是把验证码打印到服务端日志，仅限本地开发，线上不要用。
- **备份**：数据都在 `YYS_DB` 这一个 SQLite 文件里，需要自己定时备份，比如在服务停止或用 `sqlite3 hgb.db ".backup /backup/hgb-$(date +%F).db"` 做在线备份。
- **访问速度**：Vercel 在中国大陆没有节点，大陆访问首页和控制台可能偏慢；`.link` 域名不能做 ICP 备案，用不了国内 CDN。
