# Maa 进程边界证据

2026-11-16，专用 API36 ARM64、4KiB 页模拟器。合同与限制见[进程边界](../../maa-process-boundary.md)。

- `android-process-tests.txt`：最终 7 项显式进程用例通过，含两个独立子进程故障，不包含旧的宿主终止实验。
- `default-tests.txt`：默认完整 13 项中 10 通过、3 跳过；跳过两项子进程故障及旧宿主终止故障。与上批 5 个正常进程用例重复，不累加声称 17 个独立通过。
- `process-boundary.json`：仅合成场景的宿主／子进程 PID、Binder 死亡及回调时序。宿主同一 PID 存活，每个已接受记录先取得死亡证据再释放；正常空白与 PROCESS_DIED／CANCELLED 分开。
- `crash-excerpt.txt`：仅保留对应实际坏 ONNX 子进程的 SIGABRT、ORT protobuf 原因及栈；没有修复或隐藏上游异常。APK 安装路径归一化，不含寄存器／内存转储。
- `native-packaging.json`：七个上游库摘要与锁相同；八库 ELF／APK 偏移满足 16KiB 对齐，但未在 16KiB 内核执行。默认测试 APK assets 为空，不打包私有游戏图。
- `build.txt` / `lint.txt`：构建与 Lint；`installed-artifacts.json` / `verification.json` 核对实际测试 APK、源码与环境。
- `production-build.txt` / `production-independence.json`：Maa 准备输入暂时不存在时正式 App 独立构建；正式 APK 与资源版本阶段相同。本轮未重跑正式 App 的 Android／游戏测试，core 测试任务复用未变的缓存结果。
- `cleanup.json`：已清理专用模拟器实验包数据并卸载测试 APK，未操作真机游戏或正式助手数据。

源码自审将结果生成失败与交付／关闭失败分开，防止已经可能交付结果后再发第二个拒绝终态；同一平台批次已重跑，描述符关闭失败本身未做故障注入。native 阻塞标记来自实际 JNI，停止走独立服务 Looper；没有将 Java Future 取消当作 native 已结束。

这是同 UID 进程影响范围与交接实验，未接正式资源租约、执行器、截图或业务完成判据；没有证明恶意代码权限隔离、Binder／控制线程卡死恢复、宿主强停、长期运行或真实领取。真机本轮只核对游戏焦点并读取标题页，画面只留忽略目录，没有在此证据集或测试 APK 打包。
