# 输入许可与状态提交集成验证

2026-11-16，隔离 API36 ARM64 模拟器，自有触摸界面。当前 APK 与源码摘要见 verification.json；62 项核心测试、65 项 Android 合并回归零失败／零跳过，构建与 Lint 通过。

- pause-before.txt：旧生产宿主在暂停等待 SQLite 提交时仍准入新手势，真实桥返回 Completed，原断言失败。修复后相同回归在 android-tests.txt 通过：旧票据在提交等待期间返回 NOT_OWNER，自有界面没有收到新 ACTION_DOWN。
- corrupt-identity-before.txt：锁定版本身份损坏被当作普通命令拒绝，可能重开旧输入，新增真实 SQLite／宿主断言失败；存储错误分类修复后相同断言在最终合并回归通过。
- 两份 before-artifacts.json 各自标识其修复前 APK／测试源码，不冒充最终构建。targeted-tests.txt 是中间修复检查点的 12 项定向测试；最终 65 项结果以 android-tests.txt 为准。

当前只新增生产宿主的许可投影与错误分类，不新增任务队列或暂停状态机。测试工作器使用实际桥控制自有界面；正式动作注册表依然只有 control.wait，无生产坐标点击。真实业务动作日志、游戏定位／输入与完成核对、真机及长期运行未验证。没有操作真机、发通知或发布 Release。界面未改，本检查点没有重跑整机外观截图，不将上一轮截图标为本 APK 的新证据。
