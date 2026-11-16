# 版本资源驱动的文字观察

2026-11-16。本轮将候选 OCR 接入 App 的内部观察 API；没有注册游戏动作，没有执行领取。M2、M3、M4 仍未完成。

## 所有者与调用边界

`AssistApplication` 在主进程创建唯一版本库、执行器和 `VersionedTextObserver`。观察器复用该版本库；构造不会启动 native、截图或加载模型。识别子进程只提供私有 `MaaObservationService`，不创建执行器、任务拉取或第二个观察器。

调用方显式提供固定任务版本、模型合同／资源 ID 与 `ScreenFrame`。观察器取得同一版本的 `TaskResourceLease`，从租约获取路径、长度和预期摘要；不读取最新启用版本，不临时计算摘要当作预期值。模型仍是任务版本附件，不是壳内写死的任务或模型版本中心。当前合同 `maa.ppocr.v1` 有 detector／recognizer／dictionary 三个角色，仅作为首个后端适配器的内部合同，尚未成为可发布的观察动作 JSON。

结果保留原始 `FrameStamp`、版本及任务 JSON 摘要、文字、置信度与像素边界。解码不设置隐含置信度阈值，不把错误候选裁成可点击坐标；类型、有限值、整数和图像边界不合法均报 INVALID_OUTPUT。有效空候选只是观察输出，不能证明业务目标不存在、模型健康或任务成功。识别结果尚未连接设备输入；后续定位仍需现有截图身份、账号／界面与输入权限检查。

## 唯一 native 载体

原 `maaprobe` 的 JNI、IPC、私有服务与 pinned native 输入迁到 `vision` Android library，App 和探针复用同一实现。`MaaNative` 对库外不可见；直接 ABI 测试入口仅在 debug 构建存在。JNI 只调用 resource／tasker 的识别 C API，没有游戏 controller、PostAction、脚本执行或任意 PID 杀进程接口。

本候选只包含 ARM64 native。其他 ABI 返回明确的环境不支持，尚无 Windows x86 模拟器、低版本安卓或 16KiB 内核运行结论。源输入的七库、十二头文件及许可按 `vision/inputs.lock.json` 校验；App 打包可能 strip 符号，源摘要不冒充最终 APK 内库摘要。Maa 仍是集成候选，尚未冻结为最终离线后端。

生产 App 原本需要 INTERNET 来拉取任务，子进程同 UID 继承该权限；它不是权限隔离沙箱，不能沿用无联网权限探针的结论。此适配器代码只传递本地图像／模型描述符，没有新增网络适配器；未做 native 零联网或安全沙箱承诺。

## 取消、静默与文件

PNG 编码和识别结果通过内存管道，不由 App 写成截图或结果文件。子进程按调用 ID 将模型复制到私有缓存，核对长度和摘要后只读使用。有效摘要仍可能是坏 ONNX，因此保留真实进程死亡与拒绝原因，不能当作正常空白。

停止通过独立的服务主 Looper 终止该识别子进程。宿主等待 Binder 死亡及结果读者结束，才关闭模型租约；未提交／未接受有独立静默证据。协程或 Future 取消均不是 native 已停止的证据。停止后还要关闭图像管道、等待 PNG 写者退出，观察调用才返回，调用方此后才能回收位图。不能通过取消把版本文件或仍被编码的像素提前释放。

正常观察结果与目录／解除绑定清理错误分别保存，清理诊断不能抹掉已取得的观察；没有为清理异常补造未知或正常空白。文件租约只由 backend 接受后的静默回调释放，调用前失败则由观察器释放。进程死亡不代替设备桥的输入终态，也不能推断之前点击是否生效。

## 构建与验证入口

主 APK 只需 native 代码和许可，不需要测试模型：

```sh
python3 tools/qa/prepare_maa_inputs.py --native-only
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :vision:lintDebug
```

真实 OCR 集成测试需显式准备模型并仅在测试 APK 打包：

```sh
python3 tools/qa/prepare_maa_inputs.py
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PincludeOcrIntegrationModels=true
adb -s "$QA_EMULATOR" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$QA_EMULATOR" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$QA_EMULATOR" shell am instrument -w -r \
  -e class io.github.yysassist.vision.TextObserverLifecycleTest,io.github.yysassist.vision.MaaTextDecoderTest,io.github.yysassist.vision.RecognitionApplicationProcessTest,io.github.yysassist.vision.VersionedMaaIntegrationTest \
  -e allowOcrIntegration true -e allowChildNativeFaults true \
  io.github.yysassist.test/androidx.test.runner.AndroidJUnitRunner
```

`allowOcrIntegration` 还要求 ranchu 专用模拟器；坏模型实验在子进程真实触发 SIGABRT。不能与旧 `allowCorruptMaaModels` 宿主终止实验混用。普通生命周期测试使用真实 Room／文件／管道及受控 fake backend，真实 OCR 使用合成文字和独立版本库；均不写入正式任务列表或游戏数据。进程启动回归通过 debug-only 私有服务读取该进程的实际 Application 初始化状态；该服务不进入 release。

实测、构建和产物摘要见 [vision-integration](build-evidence/vision-integration/README.md)。

## 后续边界

完整步骤输出／引用／分支与验证合同、正式观察动作、主执行器到截图到观察的真实任务链、GitHub 私有源客户端授权、代表领取、留出主题和长期运行仍待完成。没有把 synthetic `ScreenFrame` 等同于当前游戏帧。控制 Looper／Binder 自身卡死时仍等待独立证据，没有擅自增加超时、自动重试或任务失败策略；宿主强停、跨启动模型缓存清理、清理错误注入及每次启动进程的长期性能仍未验证。
