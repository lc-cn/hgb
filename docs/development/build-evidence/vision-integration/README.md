# App 版本文字观察集成证据

2026-11-16。实际环境为专用 API36 ARM64、4096 字节页模拟器。源码合同与调用入口见[版本文字观察](../../versioned-text-observation.md)。

- `app-default-tests.txt`：完整 104 项中 100 通过、4 跳过；两个既有分段重启用例及两个显式 OCR 用例不在默认批次执行。
- `app-ocr-tests.txt`：显式集成 10 项全部通过，包括 4 项真实文件／Room／管道加受控 backend 的生命周期测试、3 项解码合同、真实子进程 Application 初始化边界及 2 项真实 Maa 模型测试。与默认批次重叠 8 项，合并为 102 项唯一通过、2 项仍跳过，不能相加成 110 项。
- `startup-before.txt`：相同进程启动测试在修复前失败，实际识别子进程创建了执行器。修复位于 `AssistApplication.onCreate`，限定主进程初始化；最终完整批次与显式批次的同一断言通过。debug-only 私有服务只观察实际 Application 字段，不代替业务执行器。
- `probe-process-tests.txt`：共享模块迁移后 7 项真实进程用例通过，含坏 ONNX SIGABRT、C++ 阻塞及独立停止。`probe-default-tests.txt` 为默认 10 通过、3 跳过；合并为 12 项唯一通过、旧宿主终止实验 1 项未运行。相关共享源码此后未修改。
- `probe-process-boundary.json`：仅合成图像的宿主／子进程 PID 与 Binder 死亡／静默回调顺序。`app-child-crash-excerpt.txt` 为最终 App 集成批次的坏 ONNX 子进程真实 SIGABRT／ORT 原因；没有寄存器或内存转储。故障被分类为 ENGINE_UNAVAILABLE，随后显式有效模型调用通过，没有改报空白。
- `build.txt`、`lint.txt`：App、vision 与 maaprobe 构建和三者 Lint 无问题。`model-free-build.txt` 在整个测试模型 assets 目录实际移出期间完成最终 App／默认测试 APK 构建，随后恢复输入。core 任务复用未变的 67 项通过报告，没有声称本轮重新执行。
- `build-artifacts.json`、`app-packaging.json`：主 APK 只有网络公共域名表和 Maa 许可 assets，无模型／截图／结果素材；默认测试 APK 只有 Room schema。显式模型只在独立测试 APK。App 共 16 个 native 库，其中 Maa 闭包 7 个上游库加 JNI；全部 LOAD 段及未压缩 APK 偏移满足 16KiB 对齐，仅在 4KiB 内核运行。
- `release-build.txt`、`release-boundary.json`、两个 manifest：本地未签名 release 构建通过，debug 探针服务与原生直接访问器在 release 的 manifest／DEX 中不存在。release 未安装、签发或发布。debug 约 170.5MiB、未签名 release 约 162.5MiB，当前 native 候选有明显体积成本，尚未作性能或分发定案。
- `installed-default-test.json`、`installed-artifacts.json`：分别核对默认完整批次与显式 OCR 批次实际安装产物摘要。主 APK SHA-256 为 `1f551406b5076367fc06587c5d9f8b42cf284d97b8b7ef2d389300a6490566ff`，最终默认／模型测试 APK 是两个明确不同的产物。
- `verification.json`：本轮源码／输入锁摘要和构建组合。共享输入锁相对原探针逐字节不变；仅新增的 AGP library 插件标记按 Google Maven 核验，未移除依赖校验。
- `cleanup.json`：保存证据后卸载模拟器测试包及合成探针，保留正式助手与游戏数据；没有操作真机游戏。

所有 OCR 均为合成帧和专用数据库，未改正式任务列表／队列。没有新建或发布 Release、没有私有任务源真实授权联调、没有运行游戏任务。真实截图到执行器动作、留出主题、代表领取、宿主强停、控制线程卡死、跨启动缓存清理与长期运行仍未验证。有效空候选不等于模型健康或业务目标不存在。App 子进程同 UID 有 INTERNET 权限，不能沿用独立探针无网络权限的结论。
