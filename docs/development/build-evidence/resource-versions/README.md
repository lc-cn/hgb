# 版本化资源验证

2026-11-16，专用 API36 ARM64 `emulator-5560`。范围和合同见[版本资源](../../versioned-resources.md)。

- `verification.json`：源码／构建摘要、测试分类和限制；`installed-artifacts.json` 核对实际安装的主 APK、测试 APK 与构建字节一致。
- `build.txt` / `lint.txt`：正式／测试 APK 构建成功、Lint 无问题；没有引入 OCR 模型或新生产识别依赖。
- `core-results.json`：67 项通过，含资源清单、同 Release 附件、元数据冲突、重定向、完整性、断线与取消静默；原有核心情境保留。
- `android-tests.txt`：完整 94 项批次，92 通过、2 按原条件跳过。跳过的是须在两次 instrumentation 之间另行强停的 seed／verify，用例未在本轮执行，不计为通过。覆盖版本、配置、执行器、实际桥与浮层，以及新增 12 项资源存储／租约情境和 1 项本地 HTTPS 资源贯通。
- `lease-before.txt` / `lease-after.txt`：本次新增实现的租约交付取消回归，修复前断言残留 1，修复后同一断言为 0；测试 APK 字节完全一致。`before-lease-delivery-artifacts.json` 和 `lease-fix.diff` 保留第一版 APK／源码摘要及精确责任边界修复，二进制只留忽略目录。

32MiB 用例以 8KiB 块写入真实私有文件并验证摘要、重开、删除；这是功能边界，不是内存峰值或性能基准。注入失败与 SQLite 触发器拒绝提交有实际断言，不冒充断电实验。没有真实 Release 发布、私有源授权、模型加载、游戏业务、云端截图或外部通知。本次没有修改原生界面布局，也未重跑额外的整机外观 smoke；原生版本／配置／执行组件和浮层的回归包含在实际安装的最终 APK 批次内。
