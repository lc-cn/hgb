# 离线 OCR：进入 Android 对照实验前的合同核验

2026-11-16。只核验一手源码、发布元数据和可取得的产物；未修改正式构建，未编译／安装 OCR APK，未处理游戏画面或运行游戏动作。本文补充既有[技术方案](../design/technical-plan.md)与[运行时研究](native-runtime-feasibility.md)，不重新冻结生产后端。

本轮的新证据是：ML Kit 的模型 AAR 已实际取得并检查；Paddle 的 SDK 源码及两份 tiny 模型已取得；大型 native 分发物只用 HTTP Range 读取 ZIP 中心目录确认包含的库，而非下载／运行整个包。原始材料在忽略目录 `.local/vision-research/`；不把依赖包或模型二进制提交仓库。

## 可以直接开始的对照基线

| 候选 | 本轮固定基线 | 已核对的接入合同 | 尚未证明 |
| --- | --- | --- | --- |
| Paddle 官方 Android SDK | PaddleOCR 提交 `dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf`，`deploy/ppocr-android/ppocr-sdk` | Android Library 源码，minSdk 26、JDK17；使用 ONNX Runtime Android 1.21.1、Quickbird OpenCV 4.5.3、coroutines 1.9.0；不是已经找到官方 Maven AAR 坐标 | 与本项目 AGP9／Kotlin2.4 的组合、实际 native 加载、精度／耗时／内存 |
| ML Kit Chinese bundled | `com.google.mlkit:text-recognition-chinese:16.0.1` | Google Maven metadata、POM 与 AAR 均实际读取；AAR 内有 Hani 与 Latn 模型 assets；不需要首次再下载该模型 | 无 Google Play 服务环境首次识别、实际 API 依赖合并、SDK 网络路径 |
| Maa 纯识别 | MaaFramework `v5.16.1`，提交 `ea944bd7b75f649851dc4807295966b3676bc821` | 有 Android aarch64／x86_64 ZIP，C API 与仅 resource、无 controller 的 recognition 路径仍存在 | 本项目 JNI／JNA、依赖装载、色彩／像素传递、模型和释放／取消适配 |

Paddle 的依赖版本与最低版本来自固定源码的[SDK Gradle](https://github.com/PaddlePaddle/PaddleOCR/blob/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf/deploy/ppocr-android/ppocr-sdk/build.gradle.kts)和[版本目录](https://github.com/PaddlePaddle/PaddleOCR/blob/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf/deploy/ppocr-android/gradle/libs.versions.toml)。其中 Quickbird 是示例使用的第三方 OpenCV 打包坐标，不应写成 OpenCV 官方 Maven 发行；未将其依赖版本倒灌到生产工程。

ML Kit 依据：[官方 bundled／unbundled 接入](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)、[Google Maven metadata](https://dl.google.com/dl/android/maven2/com/google/mlkit/text-recognition-chinese/maven-metadata.xml)、[实际 16.0.1 POM](https://dl.google.com/dl/android/maven2/com/google/mlkit/text-recognition-chinese/16.0.1/text-recognition-chinese-16.0.1.pom)。官方页中的 `play-services-mlkit-text-recognition-chinese:16.0.2` 是动态模型路线，不能替换 bundled 坐标或误认为它是 bundled 的新版本。页面摘要仍写 API21，正文要求 API23；已取到的中文模型 AAR manifest 写 minSdk21，但传递依赖及其最终合并要求尚未核验。本项目 minSdk30 不依赖该矛盾来下调。

Maa 依据：[固定版本发布](https://github.com/MaaXYZ/MaaFramework/releases/tag/v5.16.1)、[纯识别 C 头文件](https://github.com/MaaXYZ/MaaFramework/blob/v5.16.1/include/MaaFramework/Instance/MaaTasker.h)、[Tasker 实现](https://github.com/MaaXYZ/MaaFramework/blob/v5.16.1/source/MaaFramework/Tasker/Tasker.cpp)。固定版本[集成说明](https://github.com/MaaXYZ/MaaFramework/blob/v5.16.1/docs/en_us/2.1-Integration.md)明确将 Java binding 标成仅适配 v3、已过时；不应为 v5.16.1 直接引入该旧 Java 包。

## Paddle：API 可用，但资源路径有一个实际差别

固定源码提供 `suspend PaddleOCR.create(context, ...)`、`recognize(Bitmap)`、`recognize(ByteArray)` 和 `release()`；输出包含文字、置信度、四点框与分阶段耗时。初始化 OpenCV 的 `OpenCVUtils.init(context)` 返回 Boolean，需检查，不能只调用后假定 native 加载成功。[PaddleOCR.kt](https://github.com/PaddlePaddle/PaddleOCR/blob/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf/deploy/ppocr-android/ppocr-sdk/src/main/java/com/paddle/ocr/PaddleOCR.kt)、[OpenCVUtils.kt](https://github.com/PaddlePaddle/PaddleOCR/blob/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf/deploy/ppocr-android/ppocr-sdk/src/main/java/com/paddle/ocr/util/OpenCVUtils.kt)

**当前 SDK 的 `detModelAssetPath`／`recModelAssetPath` 和识别字典配置均只读 `context.assets`。** 可配置 asset 名称不等于接受云任务下载的私有文件路径。`ORTSessionManager` 内部读取 asset 为 byte array，再调用 `OrtEnvironment.createSession(bytes, options)`；`ModelConfig.parse` 也按 asset 读取。隔离 PoC 可以先使用 assets；正式云资源必须后续在这些拥有加载职责的边界适配文件／字节与校验，不建立第二套模型加载器绕过版本锁。[会话实现](https://github.com/PaddlePaddle/PaddleOCR/blob/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf/deploy/ppocr-android/ppocr-sdk/src/main/java/com/paddle/ocr/engine/ORTSessionManager.kt)、[字典配置](https://github.com/PaddlePaddle/PaddleOCR/blob/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf/deploy/ppocr-android/ppocr-sdk/src/main/java/com/paddle/ocr/model/ModelConfig.kt)

官方[Android 部署说明](https://github.com/PaddlePaddle/PaddleOCR/blob/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf/docs/version3.x/inference_deployment/cross_platform/android_deployment.md)当前标题为 PP-OCRv6，并同时列出 v5_mobile、v6_small 和 v6_tiny 的官方模型。它说明 AAR 需本地构建，单文件 AAR 不自动传递依赖。此次取得较小的 v6_tiny 仅为准备候选，不代表其精度优于 v5 或已选定它；官方文档的测速不是本项目结果。

**模型转 ONNX 目前不是 Paddle 官方 Demo 对照的实质阻塞。** Demo 实际使用 ONNX，不使用 `.pdmodel`／`.pdiparams` 或 Paddle Lite `.nb`；官方已经提供上述 ONNX tar，此次取得的 tiny 包中确有 ONNX 与 YAML，无须先自行转换。Maa 的预转换 ONNX 同样已列入官方资源仓库；这两种预处理与字典格式不能直接互换，实际算子与输出兼容仍须推理验证。

复制隔离 SDK 的文件边界如下；不需要 Demo 的 Compose／ViewModel／图库或完整 App Gradle。此清单来自固定提交的实际目录树，尚未纳入正式源码或编译：

| 相对 `ppocr-sdk/` 的位置 | 文件 |
| --- | --- |
| 构建与资源声明 | `build.gradle.kts`、`proguard-rules.pro`、`src/main/AndroidManifest.xml`；依赖坐标另按固定版本目录解析，不复制其整个工程插件组合 |
| `src/main/java/com/paddle/ocr/` | `PaddleOCR.kt`、`PaddleOCRConfig.kt`、`EngineConfig.kt` |
| `engine/` | `DetectionEngine.kt`、`RecognitionEngine.kt`、`OCREngine.kt`、`OCREngineResult.kt`、`ORTSessionManager.kt` |
| `model/` | `ModelConfig.kt`、`OCRBox.kt`、`OCRError.kt`、`OCRResult.kt`、`OCRRunResult.kt` |
| `preprocess/` | `DetPreprocessor.kt`、`RecPreprocessor.kt` |
| `postprocess/` | `BoxSorter.kt`、`CTCDecoder.kt`、`DBPostProcessor.kt`、`PolygonUnclip.kt`、`QuadGeometry.kt`、`QuadTextCrop.kt` |
| `util/` | `BitmapUtils.kt`、`ImageUtils.kt`、`MathUtils.kt`、`OpenCVUtils.kt`、`YamlUtils.kt` |
| 测试用模型 assets | 手动放入 `models/det/inference.onnx`、`models/rec/inference.onnx`、`models/rec/inference.yml`；源码树本身没有附带这些模型 |

来源：[固定 SDK 目录](https://github.com/PaddlePaddle/PaddleOCR/tree/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf/deploy/ppocr-android/ppocr-sdk)。SDK 不是只有 `PaddleOCR.kt` 的包装类；前后处理和字典解析共同决定结果，不能省掉后另外假定兼容。

| 已实际取得文件 | 字节数 | 本机 SHA-256 |
| --- | ---: | --- |
| PP-OCRv6_tiny det 官方 tar | 1,792,000 | `ff6ab415b0a6e0c488550f2fb5d5046f1719848df220b2dc21b56402a65bc05d` |
| PP-OCRv6_tiny rec 官方 tar | 4,526,080 | `1e13b22717b1edd89d4cde4fda272b6c17d5b505c97c2baea99da1a3a2d54b29` |
| ML Kit Chinese bundled 16.0.1 AAR | 2,036,186 | `cb81f8036a6410e036cff88350ee8548e5258ff6f9e2ffbc7455defa7f672dbe` |

两个 tar 实际检查到各自的 `inference.onnx` 与 `inference.yml`；rec ONNX 为 4,462,639 字节、rec YAML 为 55,571 字节，det ONNX 为 1,780,590 字节。原始来源为文档关联的官方 BOS [tiny det](https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/PP-OCRv6_tiny_det_onnx_infer.tar)／[tiny rec](https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/PP-OCRv6_tiny_rec_onnx_infer.tar)。这些是本次下载的摘要，不声称是上游独立公布的签名或校验值。v5_mobile det／rec 官方 URL 的 HEAD 分别返回 4,843,520／16,701,440 字节与 HTTP200，未下载完整文件。

## 实际 native 分发与 Maa 模型

以下都取得 HTTP206，读取完整 ZIP 中心目录并核对条目数量；只证明归档列有这些 ABI／库，不证明解压、动态链接、16KB page size 或目标设备兼容。

| 分发物 | 归档完整大小 | 已看到的 native 条目 |
| --- | ---: | --- |
| ONNX Runtime Android 1.21.1 AAR | 27,944,395 | arm64-v8a、armeabi-v7a、x86、x86_64 的 `libonnxruntime.so` 与 `libonnxruntime4j_jni.so` |
| Quickbird OpenCV 4.5.3 AAR | 54,063,091 | 上述四 ABI 的 `libopencv_java4.so` 与 `libc++_shared.so` |
| ML Kit bundled-common 17.0.0 AAR | 17,960,748 | 上述四 ABI 的 `libmlkit_google_ocr_pipeline.so` |
| Maa Android aarch64 v5.16.1 ZIP | 55,969,896 | `libMaaFramework.so`、MaaUtils、OpenCV、ONNX Runtime、fastdeploy_ppocr、ncnn 等；还有不应全量照搬的控制器／agent库 |

来源：[ORT AAR](https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.21.1/onnxruntime-android-1.21.1.aar)、[OpenCV 示例 AAR](https://repo.maven.apache.org/maven2/com/quickbirdstudios/opencv/4.5.3/opencv-4.5.3.aar)、[ML Kit bundled-common AAR](https://dl.google.com/dl/android/maven2/com/google/mlkit/text-recognition-bundled-common/17.0.0/text-recognition-bundled-common-17.0.0.aar)、[Maa 发布资产](https://github.com/MaaXYZ/MaaFramework/releases/download/v5.16.1/MAA-android-aarch64-v5.16.1.zip)。读取的 ZIP tail 和目录元数据留在 `.local/vision-research/`，未以其摘要冒充整个 AAR 的摘要。

Maa Release API 对 aarch64 给出的整个 ZIP 摘要为 `71e6f1a81e432d64cd62e780977c66d8307e230a0b6faebee252b0195f6a841c`；x86_64 资产为 58,879,592 字节、摘要 `5073b03bbb16aafd2347109268f3c7731c59c3d3ac8c60fdf91bff84ff520afd`。此次没有完整下载来复算这两个摘要。[发布元数据](https://api.github.com/repos/MaaXYZ/MaaFramework/releases/tags/v5.16.1)

Maa 的 ONNX OCR 目录需要 `det.onnx`、`rec.onnx`、`keys.txt`；预转换模型来自 MaaCommonAssets。此次固定该仓库提交 `dabcd4681ac990dc4361de26416d986abd80e4aa`，树中存在 v3／v4／v5 和 v6_tiny／small／medium，未下载全套模型。例：`OCR/ppocr_v6/tiny` 的 det／rec／keys 分别为 1,793,196／4,454,793／27,156 字节，**与 Paddle 官方 tiny ONNX 字节大小不同，不能默认为同一预处理／模型基线**。[模型仓库固定目录](https://github.com/MaaXYZ/MaaCommonAssets/tree/dabcd4681ac990dc4361de26416d986abd80e4aa/OCR/ppocr_v6/tiny)、[固定快速入门的模型要求](https://github.com/MaaXYZ/MaaFramework/blob/v5.16.1/docs/en_us/1.1-QuickStarted.md)

v5.16.1 源码存在可选 NCNN 路径，Android CMake 默认编译它，但编译包含不等于已选择该执行后端。ONNX 与 NCNN 模型文件格式不同；切换到／离开 NCNN 在资源开始加载后会被拒绝，需 clear 或重建资源。PoC 必须固定执行后端后再加载，不用它的隐式回退来掩盖失败。[CMake](https://github.com/MaaXYZ/MaaFramework/blob/v5.16.1/CMakeLists.txt)、[ResourceMgr.cpp](https://github.com/MaaXYZ/MaaFramework/blob/v5.16.1/source/MaaFramework/Resource/ResourceMgr.cpp)、[OCRResMgr.cpp](https://github.com/MaaXYZ/MaaFramework/blob/v5.16.1/source/MaaFramework/Resource/OCRResMgr.cpp)

## 网络、许可与最小实验顺序

ML Kit 的 POM 使用 ML Kit Terms of Service；网页代码样例的 Apache2 许可不能替代 SDK 条款。官方说明输入与输出在设备内处理，不发送给 Google；SDK 仍可能联系 Google 并发送性能／使用指标。现有 App 有网络权限且要求按域配置代理，不能未经验证宣称这些内部请求走 OkHttp 代理。对照先放在无 INTERNET 权限的独立测试 APK，并实测首次运行；不把“无需下载模型”说成 SDK 零网络。[实际 POM](https://dl.google.com/dl/android/maven2/com/google/mlkit/text-recognition-chinese/16.0.1/text-recognition-chinese-16.0.1.pom)、[官方 Terms & Privacy](https://developers.google.com/ml-kit/terms)

Paddle 已读取的 SDK 源文件带 Apache2 声明；模型、OpenCV 打包、ORT 及其传递依赖仍需在分发时分别保留来源和通知。Maa 的 LGPL／用途声明边界沿用现有研究，不从 native 包可取得推导用途授权；此次不产生新的合法性结论。

建议的实验顺序是工程判断，不是产品默认或已经通过的选型：

1. 先做两个隔离识别适配：Paddle 固定 SDK＋tiny ONNX，ML Kit bundled 固定坐标；仅输入本地受控图，返回文字／位置／错误，不连接执行器或游戏输入。
2. 与同一组人工标注截图对照：关键文字／数值、框位置、漏检与误检分别记；冷启、重复推理、取消／旋转后返回与资源释放分别验证。候选差异应同时标明模型差异，不能将不同模型的效果全归因于推理库。
3. Maa 用固定 aarch64 native、转换模型和 C 接口建立第三条纯识别对照，不绑定 controller；图像可经 `MaaImageBufferSetEncoded` 输入，结果经 task／node／recognition 详情 API取得，实际所有权和回调取消仍需 APK 证明。[Buffer API](https://github.com/MaaXYZ/MaaFramework/blob/v5.16.1/include/MaaFramework/Utility/MaaBuffer.h)、[Tasker API](https://github.com/MaaXYZ/MaaFramework/blob/v5.16.1/include/MaaFramework/Instance/MaaTasker.h)
4. 选出生产后端前，至少使用真实游戏已适配与留出主题、商店关键数字、定位与完成判据做对照；合成颜色／同图缩放不算另一主题。没有留出主题及真实业务证据时，只报告识别接入进展。

当前无代码级通用阻塞：依赖／源码和至少一组小模型已可取得，隔离对照可以开始。生产后端定案仍缺 APK 加载和推理结果、相同标注样本、留出主题、网络路由、性能与生命周期证据。本文没有访问真机、上传画面、发布 Release 或修改执行行为。

## 后续实验准备记录

主线已把上述固定提交的 26 个 Kotlin 主源码、manifest、构建文件、混淆配置及根 LICENSE 共 30 份文件取到 `.local/vision-poc/upstream/`，逐份核对 Git 树公开的 blob 身份并保存 SHA-256 清单 `upstream-manifest.json`。这是忽略目录中的固定来源准备，没有新增正式模块、依赖或编译／推理通过结论。下一阶段可直接用这些已核对文件建立独立、无 INTERNET 权限的对照 APK，避免同时把多个候选堆入生产容器。
