# 阴阳师助手原生 Android 交互研究与新方向

日期：2026-11-15<br>
状态：纠偏后的研究与关键页面提案，尚未由用户认可。旧视觉稿保留为未通过方案，不作为后续视觉基线。

> 本文保留参考来源和早期局部稿的研究记录。后续完整重出稿为 [App v2](../prototype/app-v2.html)，覆盖、截图和验证以[最新交付索引](README.md)为准；下文“关键稿／未展开”描述的是研究当时的局部版本。

本轮从真实游戏工具界面和作者原作中提取交互结构，重做关键首页与三种游戏内控制方式。业务范围仍按[主 PRD](../PRD.md)，不增加游戏行为、通知默认规则、硬件优化、账号体系或排行榜。

## 一 旧稿的问题

旧稿虽然保留了需求，却主要是在网页原型上替换颜色和圆角：重复边框卡片、桌面指标块和密集表单被塞进手机框；横屏被直接当成桌面多栏；小悬浮面板承载长说明及多排等权按钮；研究中的“待验证／示例／技术限制”混入了产品页面。

这些问题不靠再换主题解决。新方向将完整管理 App、游戏内快速控制与异常详情分开，按触控任务和窗口高度组织信息。演示与研究说明移到设备框外；产品里只保留用户确实需要知道的当前状态、限制和处理方式。

## 二 已实际看图的来源

### 2.1 Google Android Game Dashboard

来源：[官方组件说明](https://developer.android.com/games/gamedashboard/components?hl=en)。本轮实际查看了官方完整 overlay 和展开快捷条图像，而非只阅读文字。

观察：快捷条是在游戏边缘显示的少量图标入口，完整 Dashboard 则是覆盖游戏的独立界面。两者信息量与使用时长明显不同。官方将入口、快捷条和完整 overlay 分层，不能把完整 Dashboard 缩小成持续悬浮的小网页。

借鉴：低占用入口 → 少量高频控制 → 需要更多信息时进入完整管理或动作界面。游戏中不放商品规则、御魂筛选、密钥表单或通知投递明细。

不照搬：Google 的设备优化、FPS、录屏和成就不是本产品需求；其系统级入口能力也不能被视为本 App 已具备的权限。

原图：[完整 Overlay](https://developer.android.com/static/images/games/game-dashboard/overlay.png)、[展开快捷条](https://developer.android.com/static/images/games/game-dashboard/shortcutbar-expanded.png)。

### 2.2 Samsung Game Booster

来源：[Samsung 官方快捷方式说明](https://www.samsung.com/au/support/apps-services/game-booster-shortcuts/)。本轮实际查看了页面里的边缘提示与展开工具条截图。

观察：收起时只保留边缘提示，展开后是一排紧凑的图标胶囊，游戏本身仍占主要视野。小工具条没有承担一整页解释、统计和配置。

借鉴：边缘入口和可短时展开的控制；显示范围与触摸范围都应受控。图标条只容纳少量立即可理解的动作，更多内容另开入口。

不照搬：页面说明设备型号和版本可能不同；Samsung 的系统集成、位置记忆和手势不能直接推导本应用兼容性。具体吸边、位置持久化与手势仍需本产品确认和验证。

原图：[边缘提示](https://images.samsung.com/is/image/samsung/assets/au/faq/apps-services/game-booster/shortcuts/gamebooster_shortcuts5.png)、[展开工具条](https://images.samsung.com/is/image/samsung/assets/au/faq/apps-services/game-booster/shortcuts/gamebooster_shortcuts6.png)。

### 2.3 Dribbble 作者原作

来源：[UI Ninja 的 Game Speed Booster](https://dribbble.com/shots/6851828-Game-Speed-Booster)，作者将其描述为简洁游戏加速器设计，发布于 2019-07-27。本轮实际查看了其三屏作品原图。

观察：设置主要依靠分组标题、平面列表、开关和分隔线；主屏突出开启状态与功能内容，没有把所有内容包成数据看板卡片。作品包含欢迎页、游戏选择和设置，视觉用途明确。

借鉴：状态与列表分层、少量强调操作、设置依业务分组。新稿的任务中心和公共设置改为原生工具列表，不再堆叠同质卡片。

不照搬：这是作者展示作品，不能证明已经发布或真实可用；不继承其网络加速、清理缓存、游戏库或欢迎流程，更不复制图形和游戏素材。

原图：[作者作品图](https://cdn.dribbble.com/userupload/42484863/file/original-454910ef62b34df77f7e5a6f2ceb31e3.png)。

另已读取 [DucManh 的 Game Booster UI Short Design 作者页](https://dribbble.com/shots/25637925-Game-Booster-UI-Short-Design)，但未在本轮实际查看该作品完整视觉，不据其文字和配色摘要声称已完成图像分析。

### 2.4 Android 官方横屏设计与自适应规则

本轮实际查看了 Android 官方横屏示例图：横屏内容重排，但仍有底部导航。参考入口：[Android 布局与内容](https://developer.android.com/design/ui/mobile/guides/layout-and-content/layout-basics)。

官方自适应导航说明，默认在宽度或高度为 compact 的窗口显示导航栏，其余情况才显示导航轨。手机横屏通常具有 compact height；官方也明确提醒在中等宽度、紧凑高度场景，双窗格未必实际可用。因此“横屏必侧栏、必三列”不是可靠规则。[自适应导航](https://developer.android.com/develop/ui/compose/layouts/adaptive/build-adaptive-navigation)、[窗口尺寸分类](https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes)

新稿对手机横屏采用紧凑顶栏、单轴滚动和较紧凑的底部主导航。模型等二级表单隐藏主导航，保留返回和固定操作区。平板、模拟器大窗口的 rail／双窗格应依实际可用宽高另验，不能仅通过方向字符串决定。

## 三 灵感网站的访问边界

| 平台 | 本轮可用证据 | 使用结论 |
| --- | --- | --- |
| Dribbble | 已读作者页，并实际看 UI Ninja 完整三屏图 | 可用于视觉与结构启发；不等于真实 App 行为证据 |
| Behance | [Clario](https://www.behance.net/gallery/240072287/Clario-Task-Management-App-Mobile-App-Design) 与 [Taskyo](https://www.behance.net/gallery/249531073/Taskyo-AI-Task-Management-Mobile-App-UI-UX-Design) 在本轮网页工具返回 403 | 不声称已看完整项目，不据标题虚构借鉴内容 |
| Mobbin | 浏览入口未取得可分析的完整 App 流程 | 不声称已比较其 Android 完整案例 |
| Pageflows／Screenlane | 本轮未获得可分析的完整页面与流程 | 不以付费墙或访问受限页面的标题代替研究 |

实际可见的来源足以验证当前纠偏方向；不继续扩张泛案例数量，也不为作品集效果添加产品未要求的功能。

## 四 新的主 App 方向

关键稿：[原生交互方向草案](native-ui-preview.html)。这是一份浏览器中表达的 Android 交互草案，不是真实 Compose 实现，也不是已通过用户验收的界面。

主 App 采用顶栏、分组标题、平面列表、原生风格状态行及底部导航。首页只强调当前运行主操作；停止藏于清晰的更多操作入口，不与暂停摆成两颗等权大按钮。当前异常用短行进入接管页，详细资源影响在接管页阅读。

五个主入口与六个业务分类保持不变。列表项目不加多层卡片外框；周期进度用短摘要而非多个桌面指标块。通知、模型表单保留必要输入与固定操作区，不把研究的待决事项当表单文案。

本轮只画与交互相关的关键页面，未强行铺完三十页。占位的任务入口会提示本轮未展开，不暗示真实能力已经实现。

## 五 三种悬浮交互提案

| 变体 | 收起入口 | 展开方式与内容 | 主要取舍 |
| --- | --- | --- | --- |
| A 小球与工具条 | 可移动的小球与状态点 | 点击后展开紧凑图标条：暂停／继续、待处理、更多；更多进入动作底单 | 展开占用较少，任务细节需要进一步打开 |
| B 边缘入口与短面板 | 屏幕边缘提示 | 点按或向内拉出短侧面板：简短任务、进度、单一主操作、问题与返回助手 | 任务信息更直观，面板比图标条遮挡更多 |
| C 状态胶囊与动作底单 | 短进度胶囊 | 点击后打开原生风格动作底单，临时完成暂停、查看问题或停止 | 收起时可读进度，展开较明显打断游戏 |

三者不是换配色：入口占用、手势、展开几何和信息量均不同。草案支持横竖屏比较、收起／展开切换和状态切换，用户尚未选定其中任何一个。

暂停先表达请求中，再模拟确认状态。结果未知进入核对页，不因通知渠道接收就清除问题。停止范围明确为本次托管，且没有真实游戏副作用。

吸边、自动展开、位置记忆、隐藏唤回及手势阈值仍是待确认项。网页中用于比较的拖动判定数值不构成 Android 产品默认值。

## 六 交付与检查

- 主 App 对照：`native-ui-preview.html?board=app`，包含首页竖屏、首页横屏、模型设置横屏。
- 三种悬浮竖屏：`?board=overlays-portrait`。
- 三种悬浮横屏：`?board=overlays-landscape`。
- 交互入口：`?view=app&orientation=landscape#home` 或 `?view=overlay&orientation=landscape&variant=a`。

脚本通过语法检查，未连接真实模型、通知服务、游戏或系统授权。最终 Android 点击区、窗口 inset、旋转、截图遮挡和平台生命周期仍须真实实现验证。后续以用户对新方向的反馈决定是否继续铺全页面。

主 agent 已在独立 Chrome 会话中检查 9 个关键页面的横竖屏渲染，以及模型开关、模拟通知、暂停／停止请求与确认、底单遮罩关闭、问题保留和三种悬浮变体；未发现运行时异常。该检查不代表 Android 真机实现或游戏能力验证。
