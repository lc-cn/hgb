# 阴阳师助手

Android 任务管理与执行容器，目标同时用于安卓手机和 Windows 安卓模拟器。首期验证阴阳师，核心合同预留其他游戏和普通 App 的目标隔离。当前原生壳可构建、安装，核心测试与设备验证进度分别记录。

- [当前实现进度](docs/development/implementation-status.md)
- [多目标应用扩展边界](docs/development/target-extensibility.md)
- [开发基线、首期范围与验收](docs/development/README.md)
- [完整需求](docs/PRD.md)
- [已认可的 UI v5 预览](docs/design/ui-v5/index.html)
- [设计与技术资料索引](docs/design/README.md)

App 提供基础动作能力，开发者发布版本化步骤、任务与配置 schema，用户拉取任务后直接执行或组合计划。具体技术实施与分期以开发基线中的确认状态为准。

## 项目结构

```text
core/                 各端共用
  protocol/           App 与云端之间的接口定义
  engine/             任务包格式、步骤程序、执行器和计划，Android 与以后的 Windows 端共用
  server/             云端服务（Ktor + SQLite），同时托管 client/web 的控制台页面
client/               各端客户端
  android/app/        Android App
  android/vision/     Maa 识别子进程与原生库
  android/visionprobe/、android/maaprobe/   识别实验探针，不进正式 App
  web/                Web 控制台（静态页面，构建时打进 core/server）
  windows/            以后的 Windows 端，尚未开始
```

依赖方向只能从 client 指向 core，core 内部为 server → engine → protocol。云端开发说明见 [云端控制台](docs/development/cloud-console.md)。

## 构建

需要 JDK 17、Android SDK 37.0 和 Build Tools 36.0.0。设置 `JAVA_HOME`，通过 `ANDROID_HOME` 或本地不提交的 `local.properties` 提供 SDK 路径后运行：

```sh
./gradlew :core:engine:test :client:android:app:assembleDebug
```

Windows 使用 `gradlew.bat`。首次构建需从官方仓库下载依赖；Wrapper 分发校验和固定。APK 输出为 `client/android/app/build/outputs/apk/debug/app-debug.apk`，只有构建通过后才存在。
