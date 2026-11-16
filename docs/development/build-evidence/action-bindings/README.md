# 动作参数引用验证

2026-11-16，专用 API36 ARM64、4KiB 页模拟器。合同见[动作参数引用](../../action-bindings.md)。

- `core-tests.txt`／`test-summary.json`：76 项核心测试实际重新执行通过。新增配置／前序输出结构、类型、缺值与非法引用、完整手写任务 JSON、实际解释器虚拟时长、取消后从步骤入口重入及精确数值测试。虚拟时钟不是设备墙钟性能结论。
- `integer-before.txt`／`verification.json`：原动作数值读取按文本 `longOrNull` 拒绝 schema 已接受的 `12.00`，新增断言先失败；改为精确整数转换后相同断言通过，小数／负数／超界断言保持。没有声称原 Android 构建复现过此问题。
- `android-full-tests.txt`：最终完整 107 项中 103 通过、4 条件跳过，跳过两个既有分段进程恢复用例及两个显式 native OCR 用例。新增 3 项在独立 Room 库验证配置保存、候选入队／最终事务双重校验、用户修改配置后在途快照不变，并调用实际等待工作器取得结果。
- `android-boundary-tests.txt`：较早的保存／入队／运行边界批次 33 项通过，与最终完整批次重叠，不累加。最终批次还核对 `12.00` 经真实保存和读取保留原数值表达。
- `build.txt`／`lint.txt`：debug 主 APK、默认测试 APK 和 App Lint 通过；没有新增依赖或修改 Room schema。本轮没有重测 release 或 native 故障，不沿用其历史通过冒充本轮证据。
- `build-artifacts.json`／`installed-artifacts.json`：实际安装摘要与最终构建一致；主 APK 没有模型、游戏图或手写任务夹具，默认测试 APK assets 仍只有 Room schema。夹具只作为 JVM 测试资源，不自动安装到正式任务库。
- `cleanup.json`：仅移除专用模拟器测试包，保留正式助手及数据；没有真机或游戏操作。

本轮等待工作器仍是唯一生产动作。测试没有证明观察、定位、点击、业务完成判据、条件／循环、GitHub 私有源客户端授权、真实领取、跨主题或长期运行。未修改 TaskExecutor 排序、暂停／两种恢复或未知结果规则；只给同一执行路径增加参数来源和合同校验。
