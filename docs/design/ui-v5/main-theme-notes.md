# 主 App UI v5：主题与按钮材质交接

入口 [app.html](app.html)，样式 [app.css](app.css)，行为夹具 [app.js](app.js)，任务真值 [fixture-data.js](fixture-data.js)。v3/v4均保留。此次以已签收 v4 结构为基线，不改变任务模型或建立新执行器。

## 采用的技能与平台选择

已读 `mobile-uiux-pro-max/SKILL.md`、`rules/system/dark-mode.md`、`design-system/token-spec.md`、`rules/system/platform-conventions.md`，并运行技能 validators。平台仍为 Android：五项底部导航、横屏窄rail、原生式顶栏与列表/配置分栏。用户明确要求按钮液态玻璃，因此采用有意统一的控制材质，不引入 iOS 返回流程、额外确认或新动效。没有连续动画。

预生成判断：目标是管理队列与任务规则，正文优先可读；主执行控制仍在工作台下部；控件命中48px、图标20–22px；数据状态、未决结果与请求中状态延续既有夹具；先保证明暗对比和降级，再看材质表现。原型使用固定评审视口，不能当作 Android 实际 inset、Dynamic Type 或屏幕阅读器实现证明。

## 主题职责

加载顺序：同步 `appearance.js` → `theme-tokens.css` → `app.css` → `material.css`。后三个共享主题文件由根拥有，主稿不覆写其明暗映射、偏好逻辑或玻璃实现。初始默认跟随系统，系统/浅色/深色可手动切换。

组件只读语义角色。原硬编码颜色均删除（功能状态通过共享语义色恢复）：背景与文本使用 surface/ink/muted，边界用 line/line-strong，异常用 status-warning-bg/status-warning，游戏占位背景用 game-*，浮层用 overlay-*。主色黑白灰不等于只允许灰度：运行/信息为蓝，成功绿，待核对/警告琥珀，失败红，更新点红；全部使用共享status角色。原状态文字/图标、更新aria与行为不变，不以颜色独自表达。优先级、任务名和普通正文不染色。

字体、间距、控件及固定评审框尺寸集中在 app.css 的组件语义 token 定义段，常规字体/触达/图标别名指向共享 `--font-* / --hit / --icon / --tap-gap`。阴影指向共享 shadow-sm/md/lg；没有组件内 hex 或另写深色分支。媒体查询700px为 CSS 原生query的静态评审断点（CSS变量不能直接用在普通media query）；JS中视口fit坐标仍是夹具几何计算，不是生产Android尺寸或系统inset。

## 材质应用边界

明确操作控件加 `glass-button`，包括hit、enqueue、star、choice、保存、移除与球/浮层控制；强调操作可用 `is-primary`，安静图标用 `is-quiet`，导航只有选中项采用克制玻璃。任务行正文、队列、长表单、分区和整页背景不施加玻璃，避免内容混浊或整屏毛玻璃。

可评审状态由根共享 `window.uiAppearance` 提供：`getState()`、`setPreference('system'|'light'|'dark')`、`setReviewTransparency(bool)`、`setReviewMotion(bool)`；`appearancechange` 通知。根属性 `data-theme` / `data-theme-preference` / `data-transparency` / `data-motion` 由共享owner统一更新。本稿不另建主题状态或在每次render重置外观。

低透明度/不支持backdrop时走material.css不透明回退；减弱动态时走共享reduced-motion。玻璃只是材质，不改变disabled、aria、48px命中或请求中阻止重复操作；无continuous animation。实际透明叠加后的对比与fallback视觉由根浏览器验证，不以静态lint替代。

## 保留的交互与数据

- 工作台队列优先，底部唯一执行条；当前任务名16px/600，状态/来源次级，紧凑暂停与“移除”，没有播放器式X歧义。
- 横屏任务周期筛选在顶栏中部，正文仅分类一行；选任务后列表/配置分栏。竖屏保存在路由顶栏，横屏pane保存sticky，长表单到底可操作。
- 22任务字段、约束、值、steps/common来自原fixture；没有按分类虚造新规则。数据只在内存，无凭据、请求或持久化。
- 球/快捷/常用三层、当前控制去重、48px透明命中/20px图标和8px间距保留。已排队常用项原位禁用文字“队列中”，不以✓误指完成。
- 拖动球不自动吸边；展开面板只做视口fit，上翻/左右钳制不写回球anchor。候选内滚，当前和头部不被卷走。
- 恢复选项仍是UI展示，不证明真实Executor已能restart；暂停/移除延迟只是请求态演示，不是底层静默证据。

计划、历史、设置、完整规则、更新和游戏账号原入口统一到同版 `full.html#...`，没有跳回旧配色；本轮主稿不重建这些业务owner。主题设置由同版完整稿入口承接。

## API 与评审入口

`window.uxPrototype` 保留原selector与API：`getState/setPage/setOrientation/setFixture/getManifest/setOverlayLayer/setScene/selectTask/enqueue/pause/stop/remove/recover/saveTask`。页面参数依旧为 `page=home|tasks|game`、`orientation=portrait|landscape`、`fixture=running|paused|pausing|hold|idle|unknown`、`layer=ball|quick|common`。所有状态夹具控件在设备外。

默认同内容对比：账号A，daily计划30/当前每日物资领取，等待御魂25、悬赏10、结界挂卡10，均7分钟，商店兑换未知短提示保留。此条旧unknown不伪装成当前结果未知。

## 检查与尚未验证

Node语法与惰性DOM回归覆盖全部22schema、横竖页面、引用去重、草稿/选中保留、暂停恢复/移除/停止。技能机械审计未报error，唯一warning是球锚点绝对左右坐标；当前授权为中文LTR固定视口，完整RTL不是本轮交付范围，不以忽略规则冒充已适配。

静态contrast/target/state检查不能解析所有运行时主题/玻璃叠加，更不能证明真机Android可用。明暗、跟随系统、手动覆盖、reduced-transparency、reduce-motion、无backdrop、实际长表单和悬浮边界，由根独占浏览器做可见验证。子agent未操作Chrome，也未宣称真实游戏/Root/通知/模型能力完成。

焦点token复改：主稿尺寸改为`--main-focus-width`，共享`--focus-ring`专用于颜色，不再被3px覆写。同次自审修正数值替换引入的无效标题tracking语法，使用独立`--main-title-tracking`，未改字号或布局。

材质所有权复改：app.css全部既有声明按原顺序放入`@layer components`，组件层负责布局与语义状态；共享theme/material保持unlayered，材质与降级由sharedmaterial统一优先决定，避免旧组件高specificity盖掉fallback，不叠加!important。设备外说明使用“无业务持久化”，主题偏好按用户授权单独保存。

已选星标SVG的fill/stroke使用currentColor，跟随shared material为按钮解析的前景色，避免明暗strong玻璃出现同色星形；未选仍空心，星标属性与形状不改。

## 后续图标外观调整

用户允许图标丰富配色，并要求混入透明度。共享材质层对现有 SVG 增加色彩与透明度，未增加业务操作：普通图标82%、提示状态图标92%、禁用图标保留灰色可读；选中态使用适配强色背景的反色角色。事件容器的真实状态优先于图形身份色，未知仍为琥珀，运行仍为蓝。最终图像与复核结果见 `review-report.md`。
