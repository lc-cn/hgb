# Android 11 最低版本兼容验证

2026-11-17，基于 `a06e8f5`。工程 `minSdk=30`，本轮使用本机已有 Google APIs ARM64／API30 revision16 镜像创建独立 `yys-assist-api30`，软件渲染、1080×2400、420dpi。它与原 API36 助手 AVD、旧游戏 AVD 分开；不是手机或 Windows 验证，没有读取真实登录态。

## 源码修复

1. **截图节流版本差异。** 原设备桥对所有版本统一 334ms；Android11 服务端实际拒绝小于等于 1000ms 的请求。真实连续截图及观察／点击链出现错误3，不能完成任务。唯一设备桥现按 API30 使用 1001ms，API31 起保持 334ms。仍在提交前等待，不新增截图重试、缓存画面、任务等待参数或产品超时；取消与位图交接不变。
2. **窗口集合变更漏查。** 原服务只把窗口状态及 FOCUSED 变化转成就绪事件。Android11 返回已有 Activity 时，最后只发 REMOVED／ADDED，设备已 READY，宿主仍等待前台。原入口现也接收这两类窗口集合变化，复用同一个检查与执行器。没有轮询、额外启动、人工暂停自动继续或第二套状态。

统一 334ms 的引入检查点为 `fd96faa`；窗口就绪过滤来自 `265ad55`。均为未发布开发代码在新支持环境暴露的问题，不声称线上事故。

## 失败与通过证据

| 问题 | 修复前证据 | 核对依据／修复后验证 |
| --- | --- | --- |
| 并发截图及快速观察被错误3拒绝 | [原并发用例失败](pacing-before.txt)、[当时源码／APK摘要](before-artifacts.json) | 固定 Android11／12 客户端字段与服务端比较均实际下载核对，见 [platform-contracts.json](platform-contracts.json)；原并发回归方法未变，见 [method digest](pacing-regression-method.json) |
| 已回前台却仍等待 | [清理前状态](readiness-before-state.txt)同时记录 availability=READY、宿主 Waiting 和本测试 Activity 实际焦点；[原断言失败](readiness-before.txt) | [修复前事件](readiness-events-before.txt)在 WRONG_FOREGROUND 检查后只有 REMOVED／ADDED；[修复后事件](readiness-events-after.txt)随同类事件取得 READY；[聚焦四项](focused-four-passed.txt)全通过。原自动继续／零触摸／引用释放断言保留 |

初次 API30 全量为155通过／12失败／4跳过；修正截图及浮层测试坐标后为165通过／2失败／4跳过，之后才定位就绪事件遗漏及另一个回调测试假设。[before-summary.json](before-summary.json)保留各批次本地日志摘要，最小复现日志和状态在本目录；不把中间批次计作完成或隐藏失败。

## 两项测试合同校正

- **浮层屏幕坐标。** [原失败](viewport-before.txt)使用 context 资源的2138px可用宽度，直接比较原始屏幕坐标中的 right=2253。实际 [2400×1080像素图](landscape-ball-before.png)及[窗口几何](landscape-ball-before-geometry.txt)显示完整悬浮球，原因是可用宽度与屏幕原点不同。测试改用实际截图坐标空间，检查完整矩形和原宽度要求，保留拖动、两方向独立锚点、交互、截图排除及状态断言；未改浮层生产布局。
- **目标消费与平台终态。** [实际时间线](gesture-order-before.txt)中 quiet=1049669、目标 ACTION_UP=1049670，相差1ms。固定 Android11 [MotionEventInjector](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-11.0.0_r48/services/accessibility/java/com/android/server/accessibility/MotionEventInjector.java#L154)在向下游发送末事件后通知服务，不等待目标 View 回调完成。原 `UP <= quiet` 断言没有该合同保证，现分别等待平台 Completed 和唯一消费 UP，保留在途不能报静默、拒绝新输入、完整触摸序列及执行器暂停状态检查。不是用平台终态代替业务完成。

这两处预期校正有独立实际平台证据；不能笼统声称所有测试断言完全未变。截图回归方法、回前台自动继续和业务结果保护没有放宽。

## 最终验证与产物

[build.txt](build.txt)为移除全部生产临时诊断后的 debug 主／测试 APK 与 Lint 构建。核心未改，`:core:test` 复用已有108项报告，不算本轮重新运行。最终源文件、主／测试 APK 摘要见 [verification.json](verification.json)，两台设备安装摘要均匹配，见 [installed-artifacts.json](installed-artifacts.json)。

最终 Android 批次及独立进程阶段以 [test-summary.json](test-summary.json)、[API30全量](api30-full-tests.txt)、[API36受影响链路](api36-affected-tests.txt)为准。后者为38项，不冒充本轮重跑整个 API36 测试集。所有图像、触摸、任务、配置、数据库和密钥均为自有夹具，不操作游戏或正式任务库。

最终 API30 为167通过／4条件跳过，API36受影响38项全通过。4个跳过项是独立选择阶段的执行器／会话进程用例：

- [运行器seed](runtime-process-seed.txt) → 实际force-stop → [运行器verify](runtime-process-verify.txt)，PID更换，完成步骤及队列保留，见 [result](runtime-process-result.json)。
- [会话seed](auth-process-seed.txt) → 实际force-stop → [会话verify](auth-process-verify.txt)，真实Keystore的刷新未知标记保留，没有重放刷新，见 [result](auth-process-result.json)。

四阶段均实际通过，QA密钥／数据库隔离，不从真实会话取令牌。跨进程用例验证无输入任务恢复与模拟HTTPS续期交接，不证明游戏副作用可重放。

生产临时 `YysReadyProbe` 已删除；保留的事件文本只含类型、掩码、检查结果和时间，用于证明根因。测试仅在失败时保留自有运行器状态，未向产品增加诊断页面、通知或用户操作。

本轮没有新增依赖、Room schema、App权限、任务动作或 JSON 控制流，未发布 Release、发送通知或执行游戏操作。真机、Windows、游戏领取、留出主题、非空私有任务附件和长期运行仍未完成；控制流提案仍等待确认，M2／M3／M4 继续。AOSP兼容间隔也不保证所有厂商的截图实现。
