# Maa 纯识别 Android 实测

2026-11-16。MaaFramework v5.16.1 已通过独立 ARM64 APK 完成 native 加载及同图 OCR。现有三块游戏局部的目标标签均匹配，但损坏模型的独立故障实验触发了上游进程崩溃。**未冻结生产后端，未接入任务执行器或操作游戏。**

## 边界与来源

使用[固定 Release](https://github.com/MaaXYZ/MaaFramework/releases/tag/v5.16.1)的 Android aarch64 分发，完整 ZIP 的55,969,896字节及官方元数据摘要均已复算。只打包识别依赖闭包的7个上游库与自有JNI；不带控制器、agent、toolkit、plugin或触摸程序。Maa自带另一套同名ORT库，因此使用独立 `maaprobe` 模块，避免与前一实验的Java ORT混装。模型来自MaaCommonAssets固定提交 `dabcd4681ac990dc4361de26416d986abd80e4aa` 的转换版v6 small，按Git blob身份及SHA-256核验；其字节、字典和预处理与官方Paddle SDK实验不同，不能将差异全部归因于框架。

JNI使用[固定C API](https://github.com/MaaXYZ/MaaFramework/blob/v5.16.1/include/MaaFramework/Instance/MaaTasker.h)：只创建resource、绑定tasker资源并PostRecognition，不绑定controller，不调用PostAction或业务pipeline。源码的[Tasker初始化与识别](https://github.com/MaaXYZ/MaaFramework/blob/v5.16.1/source/MaaFramework/Tasker/Tasker.cpp)允许该路径。设备输出每次核对controller为空、action ID无效及识别节点身份。target／test实际权限均无INTERNET；图像只在本地处理。许可及分发来源保存在锁和准备流程中，本轮没有发布native二进制或作生产许可定案。

## 同图结果

三个私有区域的PNG／标注文件及三张合成PNG与先前候选逐字节一致。相同的空白处理、严格文字与一对一正交集匹配规则，未放宽旧期望；三次重复结果一致。表中Maa按`all`候选测量，不能用其`taskStatus=Succeeded`推导业务成功。原生filtered候选对这16个已标注目标也均保留，单独记录在`filtered-summary.json`。

| 局部 | Paddle tiny | Paddle small | ML Kit | Maa转换small |
| --- | ---: | ---: | ---: | ---: |
| 商店底栏 | 2/4 | 4/4 | 1/4 | 4/4 |
| 庭院底栏 | 2/4 | 3/4 | 1/4 | 4/4 |
| 商品名与价格 | 8/8 | 8/8 | 6/8 | 8/8 |
| 合成浅／深各4条 | 各4/4 | 各4/4 | 各4/4 | 各4/4 |
| 空白误识别 | 0 | 0 | 0 | 0 |

标注只覆盖选定目标，其他返回文字并未完整标注，因此不能计算全画面误检率。这些仍是同一主题的历史只读区域；庭院／商品两块由原图目视标注，未由用户独立复核。不证明不同主题、当前资格、币种、账号身份、领取结果或实际点击安全。

## 已验证的平台与生命周期

- API36 ARM64模拟器，内核页大小4096；native版本实际返回v5.16.1。7个上游库在APK内保留固定字节，JNI加上它们共8库的ELF LOAD及APK ZIP数据偏移都满足16KB对齐。Lint无问题。**未在16KB内核、真机或Windows模拟器运行。**
- 有效模型的加载、无控制器识别、正常空白、非法图像拒绝后有效识别、重复close及新session、复制后模型损坏拒绝，共5项正常用例通过。默认批次另跳过一项显式上游故障实验，并且只读合成样本；私人诊断文件不存在。
- PNG与C API图像buffer保持至独立任务终态，结果按UTF-8字节复制；Maa对象由对应C释放函数销毁，先tasker后resource。close与读串行。没有把取消协程当作native取消，也没有生产Worker的停止／超时合同。
- PostOcrModel仅注册资源，模型在首次识别懒加载；注册耗时包括入口摘要校验，不能与Paddle SDK的eager创建直接排名。内存与时间只记录点值，未证明峰值、泄漏或24小时稳定性。

## 实际崩溃与入口缓解

独立故障用例把det／rec ONNX替换为三个无效字节。资源注册后，首次识别在上游后台线程加载模型，抛出`Ort::Exception`（protobuf解析失败），进程SIGABRT；没有返回可判定的空结果。栈经过FastDeploy模型初始化、OCRResMgr和AsyncRunner。固定[Tasker任务执行](https://github.com/MaaXYZ/MaaFramework/blob/v5.16.1/source/MaaFramework/Tasker/Tasker.cpp)直接运行task，调用方JNI的catch无法接住另一线程的异常。该原生故障仍未修复，故障实验不算质量检查通过，也不将崩溃改报“无文字”。

当前PoC的`MaaOcr`在创建原生worker前，验证复制模型与锁派生的APK摘要。相同损坏输入现在被入口拒绝，随后有效session仍可运行。此门槛是**临时入口缓解**，不是上游根因修复，不覆盖核验后文件变化、所有原生异常或卡死；保留显式绕过门槛的故障复现。生产接入仍须落实资源所有权与native故障边界。

生产模型预期归云任务版本资源。待版本化资源所有者提供已验证、受租约保护的模型后，替换PoC固定APK摘要门槛，不另建第二套版本系统，也不把实验内置模型变成壳程序的产品限制。不能通过catch信号、清未知结果或静默降级掩盖这次崩溃。

## 工程隔离与后续

两类探针的准备输入全部移开时，`:core:test :app:assembleDebug :app:lintDebug`仍通过，正式App APK与已测悬浮阶段字节相同；没有新增生产模型、JNI、权限或动作注册。复现、各次APK／源码摘要、正常／故障日志及对齐数据见[证据](build-evidence/maa-probe/README.md)和[模块说明](../../maaprobe/README.md)。私有原图、识别原文及含这些素材的测试APK仅留忽略目录。

下一步先收敛版本化资源交接与native故障隔离方案，再决定生产识别接入；补真正留出主题及当前账号／领取前后证据。当前实验不满足真实领取闭环的完成条件。
