# 离线识别首轮验证证据

最终源码／APK 摘要见 verification.json；包含实验探针与正式 App 分别对应的二进制。build.txt 是严格校验下的探针构建；正式 App 的62项核心测试及65项设备回归同样核对，设备日志见 app-regression.txt。app-without-models.txt 验证正式 App 不依赖实验模型目录。

- default-test.txt／default-results.json：同一集成用例的标准3张合成图路径；每候选9次识别。
- private-metrics-test.txt／results.json：显式选择1张此前只读菜单裁剪的路径；每候选12次识别。可分享结果仅保留该图的预设标签指标与识别数量；无图片或识别原文。两次是同一用例的两种输入，不冒充两个不同测试。重复不代表额外独立覆盖。
- light.png、dark.png、blank.png：独立探针生成的合成输入；浅／深图片已查看。
- quickbird-before-*：旧示例 OpenCV4.5.3 native 符号缺失的实际失败；其单独摘要不可当作最终构建。
- opencv-4.14-*：新官方 AAR 在当前 AVD 的 SVE SIGILL 栈与 APK 摘要。只保留必要崩溃头／首帧。
- model-tamper-rejected.txt／model-restored.txt：修改一个模型字节被构建前校验拒绝，原字节精确恢复后通过。

最终实验使用官方 OpenCV4.11.0，仅在4 KB页 API36 ARM64 AVD验证。Lint有0错误、4告警：3条 native 16 KB对齐和1条上游KTX建议，未压制。尚无生产 OCR、Maa、真实手机推理、留出主题、游戏输入／领取、24小时或16 KB环境通过结论。真实手机本轮未连接。完整解释见 ../../offline-vision-probe.md。
