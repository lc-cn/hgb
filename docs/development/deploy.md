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

需要 JDK 17 运行时。在开发机构建，把 `core/server/build/install/server` 整个目录拷到主机：

```bash
./gradlew :core:server:installDist
```

用一个单独的系统用户运行，数据放在 `/var/lib/hgb`。环境变量写在 `/etc/hgb/server.env`（权限 600，不要提交到仓库）：

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
- GitHub App 的回调地址（Callback URL）要改成 `https://api-hgb.l2cl.link/api/auth/github/callback`。GitHub 登录时服务端会给浏览器发一个只在这次回调里核对的 cookie（`yys_oauth`，10 分钟），从别人发来的链接完成的登录会被拒绝。

systemd 服务示例（`/etc/systemd/system/hgb.service`）：

```ini
[Unit]
Description=护肝宝服务端
After=network-online.target

[Service]
User=hgb
EnvironmentFile=/etc/hgb/server.env
ExecStart=/opt/hgb/server/bin/server
Restart=on-failure
NoNewPrivileges=true
ProtectSystem=strict
PrivateTmp=true
StateDirectory=hgb

[Install]
WantedBy=multi-user.target
```

`ProtectSystem=strict` 让整个文件系统只读：`StateDirectory=hgb` 创建 `/var/lib/hgb` 并让服务可写；`PrivateTmp=true` 给服务一个可写的私有 `/tmp`，SQLite 驱动启动时要把原生库解压到那里，少了它服务会起不来。

服务端只监听本机，由反向代理提供 HTTPS。用 Caddy 时（自动申请证书，WebSocket 自动转发）：

```text
api-hgb.l2cl.link {
	reverse_proxy 127.0.0.1:8787 {
		flush_interval -1
	}
}
```

`flush_interval -1` 让控制台的实时事件立即送出，不被缓冲。用 Nginx 的话，`/api/device/socket` 要开 WebSocket 升级，`/api/events` 要关 `proxy_buffering` 并把 `proxy_read_timeout` 调长。

## App

App 的“云端控制”里，服务器地址填 `https://api-hgb.l2cl.link`。正式版只接受 https 地址。

## 还没解决的

- **发信服务**：服务端还没接正式的发信服务（设计文档 C4 未定）。不配置时，邮箱注册、验证码登录、找回密码都会返回“发信服务未配置”；GitHub 登录不受影响。`YYS_MAIL_MODE=console` 只是把验证码打印到服务端日志，仅限本地开发，线上不要用。
- **备份**：数据都在 `YYS_DB` 这一个 SQLite 文件里，需要自己定时备份，比如在服务停止或用 `sqlite3 hgb.db ".backup /backup/hgb-$(date +%F).db"` 做在线备份。
- **访问速度**：Vercel 在中国大陆没有节点，大陆访问首页和控制台可能偏慢；`.link` 域名不能做 ICP 备案，用不了国内 CDN。
