# 专用游戏 AVD 显示对照

2026-11-17。当前 `yys-assist-dev` 是助手测试 AVD，未安装游戏；本次单独打开此前已安装官服的 `yys-official-validation`，不重装、不清数据、不改变助手 AVD 或其 GitHub 会话。使用已安装 Emulator 明确支持的 `-gpu software -feature -Vulkan`，关闭 guest Vulkan；软件渲染日志仍显示内部 ANGLE／SwiftShader，不把这一内部后端当作游戏在调用 Vulkan。

[experiment.json](experiment.json) 记录环境及实际结果：客户端冷启动成功，出现 46.29 MB 资源进度，启动图标区域出现黑块，随后视频与中文字幕可见。[graphics-error.txt](graphics-error.txt) 是从该游戏 PID 日志提取的最小图形错误。原始完整日志没有保存，截图只留项目忽略目录，没有提交账号资料／登录态／截图。

确认存在 `DynamicTextureGLPBO::DoUpdate` 的 `GL_INVALID_VALUE`，**没有确认其参数错误的根因，也没有证明全部黑块均由此导致**。没有点击协议、登录、领取或游戏其他按钮，没有检查庭院；不能称作修复显示或完成游戏兼容验证。AVD 本次重新启动，游戏资源可自行继续下载；是否全部下载完成仍未核对。

官方依据：[图形加速模式](https://developer.android.com/studio/run/emulator-acceleration)说明可切换软件渲染且不支持的后端可能导致错误图像；[已知问题](https://developer.android.com/studio/run/emulator-troubleshooting)提供 `-feature -Vulkan` 的排查选项。这些仅支持本次诊断方式，不证明对阴阳师有效。实际根因与可用渲染方案仍待验证，优先使用用户此前已确认画面正常的真机做代表任务。
