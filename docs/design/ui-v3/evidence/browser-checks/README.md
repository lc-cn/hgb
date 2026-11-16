# 本地 UI 浏览器检查

这些 Node.js 脚本使用 Chrome DevTools Protocol 连接 `127.0.0.1:9444` 的专用无头浏览器，默认需要 Node.js 24 的内建 WebSocket。不会自动启动浏览器，也不应指向日常使用的浏览器会话。

在项目根运行对应脚本，例如：

```sh
node docs/design/ui-v3/evidence/browser-checks/yys-v3-main-check.mjs
node docs/design/ui-v3/evidence/browser-checks/yys-v3-dev-check.mjs
```

结果写入 evidence 对应 JSON；部分设置套件会更新 screens 下的交互夹具图。所有操作仅作用本地设计稿和内存数据。旧事件／账号套件的恢复推进使用显式 legacy engine 评审夹具；不能据此证明新的主 App 恢复执行已实现。测试断言范围和实际限制见上级 acceptance-report。
