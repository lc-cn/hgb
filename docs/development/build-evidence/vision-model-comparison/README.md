# 同轮 tiny／small 模型对照证据

2026-11-16。API36 ARM64 `emulator-5560`，4096字节页，独立无网络权限探针；没有真实游戏输入或真机操作。结果解读及限制见[实验记录](../../vision-model-comparison.md)。

- `tiny-results.json` / `small-results.json`：各引擎、样本、重复的文字完全匹配、IoU及一对一位置匹配观测。私有样本没有原始识别输出或图片，只有通用期望标签及指标；合成样本保留完整输出。
- `tiny-tests.txt` / `small-tests.txt`：分别通过相同2项用例，不代表4项独立业务或3种主题。
- `verification.json`：模型、测试APK、运行ID、同输入字节、源码摘要和正式APK独立性。目标APK内实际模型字节另外核对过锁文件与设备报告，不只核对构建前目录。两个测试APK字节相同。
- `*-build.txt`：对应变体构建。Lint保留OpenCV4.11的16KB对齐及上游KTX建议，共0错误、4告警；无抑制。
- `wrong-provenance-rejected.txt` / `model-tamper-rejected.txt`：错误变体声明、模型首字节改动分别被拒绝；`model-guard-restored.txt` 在恢复后通过。
- `config-cache-before.txt` / `config-cache-fixed.txt`：新校验任务最初捕获Gradle脚本对象，配置缓存检查失败；改为任务局部不可变文件／版本值后通过，没有禁用缓存。
- `production-without-models.txt`：两套探针模型目录都不存在时，正式App／core构建及Lint通过。正式APK SHA-256与悬浮阶段相同，原证据仍对应同一二进制；本轮未重跑或新增正式游戏能力验证。
- `default-private-off-tests.txt` / `default-private-off-results.json`：未选私有样本时仅处理light／dark／blank，私人诊断文件不存在。清理探针数据并卸载测试包后结束，正式助手和游戏未改动。

原始私有样本、标注清单及含这些素材的测试APK仅留忽略目录，不提交或发布。三块游戏局部是历史只读画面中的同一主题，后两块由原图目视标注，未由用户独立复核；复现私人样本需要本地文件，仓库本身可复现合成样本及模型完整性检查。

重复数字的匹配按IoU降序、正交集、一对一分配；没有引入或放宽生产点击阈值。small仍有庭院漏检；价格匹配不证明币种、资格、消费或业务结果。没有选定生产OCR后端、发Release、外发画面或运行24小时验证。
