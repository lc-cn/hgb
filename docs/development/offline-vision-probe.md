# 离线识别 APK 首轮对照

2026-11-16。两个候选已在无 INTERNET 权限的独立 APK 中完成本地识别；**尚未选定生产后端，也没有接入游戏执行器**。商店菜单出现真实漏检／错字，当前结果不足以直接驱动游戏定位。证据在 [vision-probe](build-evidence/vision-probe/)，复现步骤在 [探针说明](../../client/android/visionprobe/README.md)。

后续已完成相同 SDK 的 tiny／small 配对实验，并补庭院入口、商品及价格局部，见[模型对照](vision-model-comparison.md)。本文保留首轮原始基线；新的局部与重复不算留出主题，未因此冻结生产后端。

## 实测边界

隔离 API36 ARM64 AVD，实际页大小 4096。独立 applicationId `io.github.yysassist.visionprobe`，不包含无障碍服务、执行器、启动入口或游戏操作。合并后的 target／test Manifest 与设备权限均检查没有 INTERNET；这证明本次 App 不具备直接网络权限和候选能在该条件下识别，不作为 SDK 全部跨进程通信或生产代理合规的证明。ML Kit 的指标行为仍按[一手合同研究](../research/offline-vision-integration-contracts.md)处理。

Paddle 使用固定提交 `dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf` 的 26 份未改 Kotlin 源码、官方 v6 tiny ONNX 与 ORT1.21.1。当前对照搭配官方 OpenCV4.11.0；ML Kit 使用 Chinese bundled16.0.1，实际 AAR 与独立取得的官方摘要一致。源码许可、路径和摘要留在 `third_party/paddle-ocr-sdk/`，模型按字节数／摘要获取，打包前再次逐文件校验。一次改动模型字节的反例已被打包前检查拒绝，恢复原字节后通过。

## 结果

每张图重复三次。合成浅／深各含同样的 4 条短语或数字，重复和改颜色都不作为新的主题或独立覆盖。菜单是此前只读样本的局部裁剪，人工标注 4 个通用入口，未包含账号／资源栏；图片和原始诊断只保留在忽略目录。

| 样本／指标 | Paddle v6 tiny | ML Kit Chinese bundled |
| --- | --- | --- |
| 浅色合成文字／数字严格匹配 | 4/4 | 4/4 |
| 深色合成文字／数字严格匹配 | 4/4 | 4/4 |
| 空白图识别文字 | 0 | 0 |
| 商店菜单严格匹配 | 2/4 | 1/4 |

菜单的失败包含漏检、形近字及简繁差异，不只是行分组。没有修改期望或放宽匹配让准确率变绿。探针的集成测试检查能够加载、正例非空、空白无输出及框在图内；测试通过不代表所有标签匹配或游戏可用。

最终无其他测试并发的一次记录：Paddle 创建约182 ms、首次合成图推理约279 ms；ML Kit 创建约23 ms、首次合成图推理约104 ms。完整逐次耗时和位置指标在 `results.json`。这是小样本、单 AVD 的点测，创建时间不包括整个进程／provider 启动，不能当作真机性能或稳定排行；PSS 是进程某时点值，也不是隔离的引擎峰值。真机在本轮 ADB 核对时未连接。

## 实际遇到的 native 问题

| 依赖对照 | 实际证据与影响 |
| --- | --- |
| 上游示例 Quickbird OpenCV4.5.3 | native 加载报缺失 `__sfp_handle_exceptions`，Paddle 未到模型推理；ML Kit 在同一次失败实验中完成了识别。|
| 官方 OpenCV4.14.0 | 越过原符号错误，但库构造阶段 SIGILL；崩溃地址反汇编为 SVE `rdvl x0,#1`，调用方按 `getauxval(26)` 的能力位进入该路径。AVD 报告 `sve2`，实际执行该指令失败。未证明真实手机也有此问题，未定位或修改模拟器底层原因。|
| 官方 OpenCV4.11.0 | 当前 4 KB AVD 可以加载／推理，但 Lint 仍报告其 `libc++_shared.so` 未按16 KB对齐。当前只用于实验；没有压制告警或选为生产依赖。|

官方 OpenCV 从4.9开始提供 Maven AAR，见[官方说明](https://opencv.org/opencv4android-usage-models/)；4.14 是[固定正式发布](https://github.com/opencv/opencv/releases/tag/4.14.0)。RDVL 的 SVE 指令语义见 [Arm 文档](https://documentation-service.arm.com/static/67e40f3398aa3c3b6eea6a85)。Paddle 上游也有[同名符号错误报告](https://github.com/PaddlePaddle/PaddleOCR/discussions/18193)，这里的结论以本机重现为依据，不沿用其未经本项目验证的降级建议。

4.11 是实验性兼容取值，解除条件是找到或构建一个在代表 AVD、真机及16 KB环境都通过的 native 组合。不能以它让本次对照跑起来，代替生产兼容性验证。没有为 SIGILL 添加捕获后假成功、运行时降级或 CPU 能力伪装。

## 工程验证和剩余工作

- 严格锁定／SHA 校验下构建通过；探针 Lint 0错误、4告警（3条16 KB对齐报告、1条上游源码 KTX建议），原样保留。正式 App Lint 无问题。
- 标准合成样本及显式选择本地裁剪的路径分别运行同一个集成用例并通过；不是两个不同测试。可分享结果不保存私人样本的识别原文或图片。
- 正式 App／core 的依赖锁和源码不变；新模块根配置下重新构建正式 APK，65项 Android 回归与62项核心测试通过。移走探针模型后正式 App 仍可单独构建。重构建 APK 的 Manifest 初始化条目次序和 DEX元数据有变化，已核对指令反汇编并在对应新 APK 重跑回归，没有拿旧二进制测试冒充。
- 第三候选 Maa纯识别、较大模型对照、完整人工标注集、关键数字与误检、真正留出主题、动态资源加载及取消／native资源长期生命周期仍待完成。本轮没有选择生产后端、发 Release、操作真机或游戏，也没有代表领取任务或24小时结论。

下一步据菜单错误分析更大模型与语义目标／图标／相对布局的组合，并补 Maa 的同图识别对照。只有代表任务的入口、资格和结果判据得到真实证据后，才将定位与输入接到正式任务动作。
