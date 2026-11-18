# 屏幕定位与输入动作

> 2026-11-18：`screen.readText` 已升为 v2，只接受任务包 `visionProfiles` 中的 `profile`；下文 v1 字段为历史记录。观察／目标的引用规则改由 core 的类型化句柄统一校验，见[区域与图像状态动作](region-actions.md)。

2026-11-17。注册 `screen.locateText`／`screen.tap` v1，沿用同一动作列表解释器、执行宿主、版本库、原生识别器及设备桥。已在专用 API36 ARM64 模拟器的自有画面跑通 JSON → 观察 → 定位 → 输入意图 → 实际手势 → 新观察 → 核对 → 持久化结果。尚无游戏领取或留出主题结论，M2／M3／M4 未完成。

## 动作合同

| 动作 | 输入 | 输出与含义 |
| --- | --- | --- |
| `screen.readText` v1 | 原任务版本声明的包名及三种模型资源 | 本尝试内的 `observation` 引用，原合同保持 |
| `screen.locateText` v1 | 前序观察引用、完全匹配文字、明确置信度 | 唯一且坐标有效的识别候选对应 `target` 引用 |
| `screen.tap` v1 | 前序定位引用、明确正整数毫秒 `durationMs` | `delivered=true` 仅表示平台完成，不能作为业务成功 |
| `screen.expectText` v1 | 本输入阶段的观察引用、文字、明确置信度 | 实际核对证据，原只读合同保持；输入后必须引用新观察 |

包名和模型仍来自完整任务版本；文字和置信度可引用已保存配置。定位只接受满足明确置信度的唯一完全相同文字，不静默选择第一个、最接近或分数最高的重复项。候选框必须在实际画面内，不能用 JSON、配置或其他动作输出伪造目标。未确认、重复、失效和识别错误均不冒充业务失败或“已完成”。

点击在自身动作内再截图、识别同一语义目标，用新位置中心发手势，不复用旧坐标。最近输入之前的观察和定位不能用于之后的定位／核对／点击；运行中同样清掉旧引用，继续执行创建新会话。作者仍须在最后明确读取并核对输入后的结果。置信度及手势时长没有 App 默认，时长按精确整数及当前平台上限校验，未解析目标前也校验已知配置值。

示例仅展示自有测试夹具的动作数组，文字、阈值和时长不是游戏任务或生产默认：

```json
[
  {"id":"read","capability":"screen.readText","contractVersion":1,"inputs":{"packageName":"test.fixture","detector":"det","recognizer":"rec","dictionary":"keys"}},
  {"id":"locate","capability":"screen.locateText","contractVersion":1,"inputs":{"text":"领取物资","minimumConfidence":0.75},"bindings":{"observation":{"type":"output","actionId":"read","field":"observation"}}},
  {"id":"tap","capability":"screen.tap","contractVersion":1,"inputs":{"durationMs":80},"bindings":{"target":{"type":"output","actionId":"locate","field":"target"}}},
  {"id":"after","capability":"screen.readText","contractVersion":1,"inputs":{"packageName":"test.fixture","detector":"det","recognizer":"rec","dictionary":"keys"}},
  {"id":"verify","capability":"screen.expectText","contractVersion":1,"inputs":{"text":"已领取","minimumConfidence":0.75},"bindings":{"observation":{"type":"output","actionId":"after","field":"observation"}}}
]
```

## 输入所有权与记录

`ReadOnlyActionProvider` 演进为 `ActionProvider`，保留同一解释器。APK 合同的 `sendsDeviceInput` 元数据决定是否需要输入会话交接，不由任务 JSON 声明。没有实现独立输入交接的会话默认返回未确认；不会因工作协程结束就得到静默证明。只读外部资源仍须在 `perform()` 内完成独立释放。

点击先通过原宿主写入唯一意图，只有新 `Recorded` 回执才可能发送；重复、拒绝和未确认都不开输入。有限的不可取消段仅用于弄清已接受记录及旧输入的结算，不将整个任务变成不可取消。记录不是输入许可；设备桥仍在主线程核对原票据、连接、方向和前台。

`InputGestureCall` 在执行前创建，保留该次平台提交及回调。关闭通道后，未提交、完成、取消和未确认仍可区分；“当前无在途手势”不等于“此前未发送”。旧 `gesture()` 与 `InputDrain` 行为保留。平台静默和已发送后的业务未知分别保存，暂停后不会盲目重放；最后核对已完成但工作器结果交接被取消时，由同一会话保留完成证据，避免再点一次。

进程恢复继续使用静态无输入证明；含输入程序不靠现有记录猜测可重放。当前会话的独立 `Quiet(Interrupted)` 则可结合 APK 程序校验及无矛盾日志，从步骤入口重入，例如高优候选最终入队被拒后原尝试未发送任何输入的情况。自定义工作器没有该证明时仍不自动重入，没有增加恢复按钮或多层暂停状态机。

## 验证及限制

证据见 `build-evidence/screen-input/`：核心 92 项通过，完整 Android 143 项中 141 通过、2 条件跳过；两个既有实际进程强停／恢复用例另行通过，它们验证无输入程序，不证明游戏输入被杀后的恢复。debug 主／测试 APK 及 Lint 通过，实际安装摘要一致，主包仍无模型或游戏任务夹具。

实际平台夹具只有文字区域可点击。定位后移动目标能命中新位置；目标消失或重复时零输入；完成回调但后置文字不满足时保留未知；提交后、投递前暂停记为未发送，继续从新观察开始；实际手势中暂停等待平台终态且保留业务未知；核对后交接取消不重复输入。高优最终拒绝与独立完成证据两条宿主回归均先失败后修复通过，原断言保留。期间加强了夹具命中检查并演进接口，不声称修复前后使用同一个测试 APK。

截图、OCR、SQLite 与 Android 输入不是跨进程原子事务。输入内重新观察以及桥的前台／几何检查不能保证之后每个像素、账号或商品都未变化；任务必须按实际业务提供前提和结果。没有添加输入重试、超时、消费预算或业务等待默认。

本轮新构建只安装到专用模拟器。最后一次真机验证记录来自前一检查点 `b5ff227` 的已授权、已通过截图检查的版本；没有通过当前输入动作操作真机游戏、发布 Release 或发送通知。完整条件／循环、账号与启动就绪、未知结果的实际业务核对、常用场景判断、私有任务源客户端授权、免费领取、留出主题及长期运行继续推进。
