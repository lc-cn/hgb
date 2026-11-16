# Maa Android 纯识别证据

2026-11-16，专用API36 ARM64、4096字节页模拟器。正常指标与显式故障分别记录，不把进程崩溃算作通过。解释见[实测](../../maa-recognition-probe.md)。

- `android-tests.txt`：含私有区域的正常5项用例通过；`comparison.json`仅保存私有目标标签指标／计数，合成图允许完整输出。
- `default-tests.txt` / `default-comparison.json`：默认只读合成图，5通过、1跳过；跳过的是须显式参数启用的原生损坏模型绕过实验。
- `unsafe-failure-tests.txt` / `unsafe-failure-phase.json` / `unsafe-crash-excerpt.txt`：该实验实际SIGABRT，遗留phase为recognizing，不是完成结果。摘录只保留此次崩溃、错误和调用栈；原始日志留本地。`before-guard-artifacts.json`对应当时实际安装的二进制。
- `verification.json`：最终源码／APK、正常与故障分类、入口缓解、生产APK隔离。修改前后native库字节一致，因此不宣称已修改或修复上游native库。
- `native-load.json` / `first-recognition.json`：实际版本及仅合成文字的C API结构／正常空白；task完成和hit分别表达。
- `packaged-elf.json` / `apk-alignment.json`：8个库的实际ELF段对齐和未压缩APK数据偏移；不作为16KB内核运行证明。
- `input-equivalence.json`：私有输入／标注与先前候选一致，三张合成PNG也逐字节一致；`filtered-summary.json`只含已标注目标在native filtered集合中的计数，没有私有识别原文。
- `input-tamper-rejected.txt` / `extra-native-rejected.txt` / `inputs-restored.txt`：打包输入校验分别拒绝模型字节改动和多余native库，恢复后通过。
- `production-without-probe-inputs.txt`：所有探针准备输入暂时不存在时正式App/core仍构建；正式APK与已验证悬浮阶段相同。本轮未重跑或宣称新增正式Android／游戏业务证明。

Normal入口在复制模型进入native前验证锁摘要，属临时PoC入口缓解；未修复后台线程异常、未实现生产版本资源租约、进程故障隔离或24小时验证。Maa转换模型和原官方Paddle权重／预处理不同；局部都是同一主题、部分标注，不计算完整误检率或宣称生产后端已确定。没有游戏输入、云端通知或Release发布。
