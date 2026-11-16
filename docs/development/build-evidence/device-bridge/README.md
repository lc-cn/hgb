# Android 平台桥证据

2026-11-16，隔离 API 36 ARM64 模拟器。最终构建和 Lint 通过，45 项核心、36 项 Android 仪器测试零失败、零跳过；其中 8 项测试对真实 Android 截图／手势回调及自有触摸界面操作。见 `verification.json`、`instrumentation.txt`、`trace.txt`。

会话资源交接取消回归先失败，见 `acquisition-regression-before.txt` 及其 APK／测试摘要，修复后保留相同断言通过。没有改成成功预期或跳过该情境。截图交接使用同一资源合同修正，但未测量长时原生内存稳定性。

`native-ui/` 的 10 项整机主题／横竖／路由检查通过，截图已查看。它们在会话交接修复前运行，单独记录 `artifact.json`，不作为最终 APK 的整机 UI 重跑证明。最终 APK 已重新执行完整 36 项设备测试，UI 代码没有随会话交接修复改变。

没有真机 App 无障碍授权、游戏执行、正式 TaskWorker、离线 OCR、三层浮层或长期运行结论；M1 和后续里程碑仍在进行。生产代码不包含测试中的授权脚本。
