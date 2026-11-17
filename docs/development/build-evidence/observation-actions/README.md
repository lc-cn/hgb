# 只读观察动作验证

2026-11-17，专用 Android 16／API36、ARM64、4KiB 页模拟器。合同与限制见[只读观察动作](../../observation-actions.md)。

- `build.txt`：当前源码离线构建、83 项核心测试及 App Lint 通过；具体计数与逐项 Android 结果见 `test-summary.json`。
- `android-full-tests.txt`：123 项中 121 通过、2 条件跳过。两项跳过仅为需要单独阶段参数的真实进程恢复测试，分别在 `process-seed.txt`／`process-verify.txt` 通过，中间实际执行强停；`process-result.json` 记录 PID、尝试代次改变及已完成步骤／队列保留。
- `android-boundary-tests.txt`／`android-native-tests.txt`：31 项边界及 11 项实际原生链路均通过，与完整批次重叠，不累加。包含入队事务拒绝后的旧只读重入、模型资源获取期间撤权、并发平台截图、真实坏模型崩溃及 C++ 阻塞后的暂停继续／移除。
- `pause-fence-before.txt`：原路径在暂停提交被阻塞后仍启动 native 一次，新增回归实际失败；`pause-fence-intermediate.txt` 的部分修复关闭了该窗口，但宿主结算仍超时，仍是失败。`pause-fence-after.txt` 使用保存的同一测试 APK 复验最终修复，启动零次并正确进入暂停。摘要见 `verification.json`，未声称复现过未执行的构建。
- `native-control-before.txt`：保留实际 9 通过、1 因系统截图错误 3 失败的批次。随后设备桥节流修复经当前完整及原生批次验证，未修改原断言或把错误隐藏为自动重试。
- `installed-artifacts.json`：实际安装包摘要与本轮构建一致；主 APK assets 没有模型或游戏样例。仅明确开启的测试 APK 携带模型。原生控制记录来自本轮定向批次的自有画面，下一次识别开始在旧 Binder 死亡之后，观察用例触摸计数零。
- 本轮未改依赖、Manifest 或 Room schema。没有重测 Release 或发布任务版本；没有 CI、真机、游戏领取、跨主题或长期运行结论。全部数据库及画面均为测试夹具，不是正式账号／目录。
- `cleanup.json`：测试临时数据库及模型目录已由用例释放；完成后只卸载测试 APK，正式助手与数据保留，未操作游戏或真机。
