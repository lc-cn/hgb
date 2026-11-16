# 运行宿主集成验证

当前 APK 与源码摘要见 verification.json；构建日志 build.txt，61 项合并 Android 测试见 android-tests.txt，14 项定向宿主／原生控制测试见 targeted-tests.txt。核心 62 项与 Lint 结果也在 verification.json。

- binding-before.txt：旧绑定结果覆盖新核对结果的真实失败，修复前源码／APK 摘要单独保留。修复后相同断言包含在上述设备日志。
- task-controls-portrait.png、workbench-landscape-dark.png：运行于真实 SQLite／宿主的组件夹具，已查看；顶端两个切换按钮属于 QA 容器，非完整 App 导航。
- process-seed.txt、process-verify.txt、process-recovery.json：隔离 API36 ARM64 模拟器，两次显式选中的测试间执行 adb force-stop，专用 QA DB 使用生产宿主＋实际等待动作；旧 PID／新 PID 和尝试代次已核对，完成步骤与队列保留。
- native-ui/：完整 MainActivity 的外观和路由检查，主 App 数据库保持真实空目录；与组件夹具分开，不把空目录截图当作游戏执行证明。

重放进程验证时只在专用 ranchu 模拟器运行：先安装本轮 App／测试 APK，使用 instrumentation 参数 runtimeProcessPhase=seed 和类方法 RuntimeProcessRecoveryTest#seedBeforeExternalProcessStop，确认 OK；force-stop io.github.yysassist；再以 runtimeProcessPhase=verify 运行 #verifyAfterExternalProcessStop，确认 OK。测试前专用 qa-process-recovery.db 必须不存在，避免覆盖遗留数据。verify 通过后清理自己的 QA 数据库，不操作主数据库。

当前仅 control.wait v1 可执行；无设备输入动作宿主、游戏领取、私有源 OAuth、Release 发布或 24 小时验证。本轮未操作真机。
