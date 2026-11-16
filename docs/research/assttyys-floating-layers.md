# assttyys 悬浮球层级与本项目对照

> 本文记录收敛前的源码对照，提到的 A/B/C 是当时的备选稿；当前已统一为单一三层，见[收敛交付](../design/floating-convergence.md)。

研究日期：2026-11-15。仅核对官方源码与仓库内图片，没有安装 APK、运行 Android／游戏、执行仓库构建脚本，也未改动本项目原型。

## 结论与来源基线

**“小球 → 动作菜单 → 方案选择”这条三层交互链成立；“三个前端页面／三个原生悬浮窗口”不成立。** 第三层是原生方案选择弹窗，复杂参数编辑在独立的主 App WebView。第三层不是每次必经：播放、停止、暂停可以直接在第二层完成；定时任务另开自己的弹窗。[原生菜单][N-menu]、[方案弹窗][N-chooser]、[前端路由][U-router]

本次固定到以下源码提交，所有外部代码链接均为 commit 永久链接：

- `zzliux/assttyys_autojs`：`7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc`（提交日期 2026-11-12）。[README][N-readme] 明确前端已拆分到下列仓库。
- `zzliux/assttyys_ui`：`9e9656339a0268019e1894f53d2489246c0f61c2`（提交日期 2026-08-01）。[package.json][U-package] 版本为 `1.0.14`；原生包[声明依赖][N-package] `assttyys_ui: ^1.0.14`。这只能证明所查源码及声明关系：原生正式路径加载远端 WebView URL、调试路径才加载本地 `dist/index.html`，没有证据证明线上页面与该 UI commit 相同。[WebView 入口][N-webview]

## 实际层级、进退与窗口边界

| 层级／动作 | 源码确认的行为 | 所属实现 |
| --- | --- | --- |
| 第一层：收起小球 | Logo 使用 `ay.png`，未展开时是常驻入口；单击切换 `menuOpen`。 | `MyFloaty` 配置，[Logo 触摸处理][N-touch] |
| 第二层：展开菜单 | 180° 扇形按钮：运行／停止、选择方案、识别当前画面后运行；按设置增加截图、日志、定时；暂停／继续按钮随运行状态显隐。尺寸设为 30 dp，自动收起设为 3000 ms。 | [MyFloaty.init][N-menu] |
| 第三层：选择方案 | `dialogs.build({ title: '选择方案', customView })`；按未隐藏分组列出 `star` 方案。点名称设置当前方案、关闭弹窗；配置允许时延迟启动。这里没有方案参数编辑表单。 | [schemeDialog][N-chooser] |
| 第二层收起 | 再点球、点击返回 false／undefined 的菜单动作、自动收起计时都会关闭菜单。菜单开合自身只改菜单状态／动画，没有调用脚本停止。 | [触摸][N-touch]、[菜单点击][N-item-click]、[菜单动画][N-menu-animation] |
| 第三层返回／取消 | 点击方案后明确 `dismiss()`；所查弹窗没有自定义“回第二层”按钮，也没有在该文件接管 Android 返回键。不能仅凭默认 Dialog 推断真实设备返回键／点外部取消的表现。 | [schemeDialog 选择回调][N-choice] |
| 回到主 App | 当前菜单里的 Home 实现已注释；日志按钮显式启动 Activity／console。没有“点第三层方案即可进入参数编辑”的桥接代码。 | [MyFloaty 注释 Home 与日志实现][N-home-log] |

“层”的三个含义应分开：

1. **交互深度**：上述三步成立，是从源码归纳出的使用路径，项目没有定义统一“三层导航模型”。
2. **Android Window 个数**：`FloatButton` 只创建 `logo`、`menu` 两个 `floaty.rawWindow`，第三层由 `dialogs.build` 单独创建；诊断绘制和定时弹窗也不属于这两个窗口。[窗口初始化][N-windows]、[方案弹窗][N-chooser]、[菜单定时入口][N-menu]
3. **Web 前端页面层级**：Vue 使用 hash router，方案管理、功能管理、设置、定时任务等是主 App 路由；不是球／菜单／弹窗三页。[路由定义][U-router]、[App 的 RouterView][U-app]

## 拖动与横竖屏

- 小球移动超过任一轴 30 个原始像素后算拖动；菜单未展开时才移动窗口，松手按屏幕中线选择左右停靠。停靠动画修正上下越界、保存相对纵向位置。不能直接把源码阈值当作本项目推荐值。[触摸处理][N-touch]、[停靠动画][N-dock]
- 原生每 500 ms 检查方向；变化时先关菜单，再交换宽高并更新小球和菜单位置。方案弹窗在打开时读当前宽高：竖屏一列、横屏三列并加宽。代码未在此弹窗里注册旋转重建／保留选择的监听，所以不能声称“弹窗打开后旋转仍无损”。[方向监听][N-orientation]、[窗口初始化与位置][N-windows]、[方案弹窗布局][N-chooser]
- Web 主 App 的方案卡片按宽度分为一／二／三列；这是 CSS 响应式布局，并非同一个原生悬浮窗重排系统。[方案页 CSS][U-grid]。本项目明确要求手机横屏紧凑顶栏、单轴表单；不能据此把横屏整体改成三栏。[本地设计说明](../design/app-v2-design.md)

## 任务启动、暂停、停止与场景筛选

| 入口 | 核对结果与边界 |
| --- | --- |
| 运行／停止图标 | 复选状态区分播放与停止；全新启动会清 `isPause`，可按设置启动关联应用；停止调用 `script.stop()`。[MyFloaty RunStop][N-menu] |
| 暂停／继续 | 暂停设置 `isPause=true`，再经停止广播终止运行线程；继续再次 `run()`，`_run` 根据暂停状态保留部分运行数据。不是本项目独立执行器暂停契约的现成实现。[pause][N-pause]、[run／_run][N-run]、[停止实现][N-stop]、[广播监听][N-broadcast] |
| 普通选择方案 | **打开选择框前就 `script.stop()`**，并非打开菜单只查看；列表仅按 `star` 和分组隐藏过滤。选中方案默认只切换，`floaty_scheme_direct_run` 初始为 false，另一个开关控制是否启动关联 App。[菜单][N-menu]、[方案过滤与选择][N-chooser]、[开关初始值][N-defaults] |
| 识别后运行 | 入口同样先停止；只对收藏方案逐个功能 `desc` 做当前画面匹配，任何功能匹配即可纳入候选；0 个提示“无法识别当前界面”，1 个直接运行，多个进入同一原生选择弹窗。没有证据证明它判断完整任务链的可达性、资源条件或区分“识别中／已确定不支持”。[菜单][N-menu]、[autoRun][N-auto] |
| 图标反馈 | 执行器 run／stop callback 会更新运行／暂停图标、Logo 着色；但复选按钮点击本身也会先翻转本地状态。因此可借鉴“状态回调驱动最终呈现”，不能把它描述为已有可靠的命令确认协议。[运行回调][N-callback]、[按钮翻转与点击][N-item-click]、[执行线程启动及回调][N-run] |

上述“选择即停止”“唯一匹配即运行”均与本项目当前约定不同：本项目暂停保留同一运行；冲突不排队、不替换；候选受常用、保存规则和场景检查约束；当前运行区不参与候选过滤，未知场景也保留暂停／继续／停止。[本地设计约定](../design/app-v2-design.md)、[当前原型候选与运行控制](../prototype/app-v2.html)（`candidates`、`activeControls`、`requestRun`、`requestPause`、`requestStop`）。不能用外部项目行为覆盖本项目契约。

## Web 路由／状态怎样与原生悬浮层联动

联动核心是**原生存储和桥接事件，不是同步一套前端 overlay 路由栈**：

1. Vue 方案页通过 `AutoWeb.autoPromise('getSchemeList' / 'getGroupSchemeNames')` 读取原生数据；收藏直接修改方案 `star` 再 `saveScheme`。方案页折叠／显示配置另存 Web `localStorage`，不等同于执行器当前方案。[方案页加载／收藏／页面配置][U-schemes]
2. `AutoWeb` 将事件与 JSON 参数经 `prompt` 发给宿主，临时注册全局 callback；原生 `webview.on(...)` 响应。`saveScheme` 写原生 `schemeList`；悬浮选择框打开时从同一 store 重读它，因而能获得已保存收藏和分组。[桥接][U-bridge]、[原生方案读写][N-scheme-events]、[悬浮选择][N-chooser]
3. 点方案正文进入 `/FunctionManagementPage?schemeName=...`。该页加载配置草稿；保存执行 `saveScheme` 后 `setCurrentScheme`；启动执行保存再 `startScript`。公共参数、功能开关与参数在该页及其 Web 对话框中编辑。[方案卡跳转][U-card]、[功能页编辑与保存启动][U-functions]、[参数表单][U-function-form]。`setCurrentScheme` 根据原生已保存方案补参数默认值并写 `currentScheme`，悬浮 `run()` 也使用此身份。[原生设当前方案][N-current]
4. 主 App 加载完成触发 `webloaded`，原生注册返回键桥接并在具备悬浮权限时初始化 `MyFloaty`。Android 返回键在 App 侧调用 `window.routeBack()`：非方案首页且有历史时 `router.back()`，否则短时间内二次返回退出。这不是第三层原生方案弹窗的自定义返回实现。[原生 webloaded／back][N-webloaded]、[Web routeBack][U-back]
5. 主 App 设置的“自选悬浮窗按钮显示”是配置菜单项；UI 明说“重启生效”。与收藏影响下次方案弹窗候选是不同生命周期，不能混为“所有浮层设置即时同步”。[UI 设置弹窗][U-float-settings]、[原生保存配置][N-float-settings]

## 仓库实际图片能证明什么

已查看 README 和 docs。README 没有操作界面截图；`docs/q&a.md` 引用的[换狗粮准备界面图片][N-screenshot]是仓库内实际图片：雷电模拟器中的游戏准备页，左缘可见收起的 AY 小球；图片未展开菜单或方案框。[图片说明][N-image-doc] 只能辅助确认“小球覆盖在游戏上”的视觉占用，不能作为当前 commit 三层展开、触摸穿透、横竖屏切换或 Android 生命周期的运行验证。本次没有把本地浏览器／源码阅读说成 Android 实测。

## 对我们现有方案的 5 条启发与取舍

| 启发 | 与本项目当前方案关系 | 取舍／审批边界 |
| --- | --- | --- |
| 按需逐步展开：入口 → 小控制区 → 常用选择 | 本地 A 已有“小球 → 图标条 → 常用短浮层”，与原生三步链同构；B／C 仍是比较方案。[外部菜单与弹窗][N-menu]、[本地设计](../design/app-v2-design.md) | **可直接沿用已批准职责划分**，无需因“三层”重做层级。固定选 A、改变默认结构或给 B／C 增一层仍需评审。 |
| 常用是原实体属性，选择面板只消费已保存数据 | 对方 `star → saveScheme → 原生 store → chooser` 提供真实来源链；我方 `commonRefs → eligibility → candidates` 已覆盖更丰富的场景与规则。[外部数据链][U-schemes]、[chooser][N-chooser] | **可继承单一来源原则**。不新增独立收藏库／排序流程；不把对方“唯一匹配自动启动”移植为隐式触发。 |
| 运行控制不应淹没在候选里 | 对方第二层直接提供 RunStop 和运行时 Pause；我方当前运行紧凑行右侧小 pause／stop、run 独立于 scene 候选，已更明确表达这一点。[回调与控制][N-callback]、[本地 activeControls](../prototype/app-v2.html) | **保持现有小图标与独立运行区**；不照搬打开候选先停任务。新增始终可见的运行胶囊、双击／长按快捷停止都是新增交互，需评审。 |
| 复杂参数留主 App，游戏内只做短操作 | 对方第三层只选方案，功能参数在 Web 功能页；与我方复杂编辑回 App 一致。[chooser][N-chooser]、[功能页][U-functions] | **沿用已批准边界**。若要在第三层直接改预算、次数或运行参数，需要明确草稿／保存／在途配置含义后再评审。 |
| 原生 overlay 应独立处理位置、旋转和命令回执 | 对方有停靠、旋转关闭菜单、执行回调，但不能证明其系统兼容性；我方目前只是浏览器夹具。[原生位置和旋转][N-orientation]、[dock][N-dock]、[callback][N-callback] | 原生实现时可据此列验证点：展开后旋转、拖到边角、未知候选仍能停止、收起不停止、回执与按钮状态一致。**30 px、3 秒自动收起、靠边吸附、旋转强制收起均不是已批准产品行为**，不可直接采用。 |

没有因本研究新增默认、触发、手势、限制或自动化；除本说明文件外未编辑本项目任何文件。

[N-readme]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/README.md#L12-L13
[N-package]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/package.json#L64-L74
[N-webview]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/index.ts#L30-L41
[N-menu]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/MyFloaty.ts#L14-L177
[N-home-log]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/MyFloaty.ts#L30-L141
[N-chooser]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/schemeDialog.ts#L10-L108
[N-choice]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/schemeDialog.ts#L91-L108
[N-touch]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/FloatButton/FloatButton.js#L394-L430
[N-windows]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/FloatButton/FloatButton.js#L283-L339
[N-orientation]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/FloatButton/FloatButton.js#L107-L124
[N-dock]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/FloatButton/js/Anim.js#L73-L148
[N-menu-animation]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/FloatButton/js/Anim.js#L151-L188
[N-item-click]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/FloatButton/js/CreateRoundButtonView.js#L24-L60
[N-callback]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/MyFloaty.ts#L154-L225
[N-run]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/script.ts#L536-L625
[N-stop]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/script.ts#L679-L705
[N-pause]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/script.ts#L773-L777
[N-auto]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/script.ts#L628-L675
[N-defaults]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/webviewEvents/settings.ts#L33-L39
[N-scheme-events]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/webviewEvents/schemeList.ts#L67-L129
[N-current]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/common/tool.ts#L41-L76
[N-webloaded]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/webviewEvents/schemeList.ts#L217-L246
[N-float-settings]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/webviewEvents/settings.ts#L671-L687
[N-screenshot]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/docs/img/%E6%8D%A2%E7%8B%97%E7%B2%AE_%E5%87%86%E5%A4%87%E7%95%8C%E9%9D%A2.png
[N-image-doc]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/docs/q%26a.md#L11-L13
[U-package]: https://github.com/zzliux/assttyys_ui/blob/9e9656339a0268019e1894f53d2489246c0f61c2/package.json#L1-L14
[U-router]: https://github.com/zzliux/assttyys_ui/blob/9e9656339a0268019e1894f53d2489246c0f61c2/src/pages/router.ts#L10-L30
[U-app]: https://github.com/zzliux/assttyys_ui/blob/9e9656339a0268019e1894f53d2489246c0f61c2/src/App.vue#L14-L18
[U-bridge]: https://github.com/zzliux/assttyys_ui/blob/9e9656339a0268019e1894f53d2489246c0f61c2/src/tools/AutoWeb.ts#L6-L53
[U-schemes]: https://github.com/zzliux/assttyys_ui/blob/9e9656339a0268019e1894f53d2489246c0f61c2/src/pages/SchemeManagementPage.vue#L20-L90
[U-card]: https://github.com/zzliux/assttyys_ui/blob/9e9656339a0268019e1894f53d2489246c0f61c2/src/components/SchemeItemCard.vue#L95-L105
[U-functions]: https://github.com/zzliux/assttyys_ui/blob/9e9656339a0268019e1894f53d2489246c0f61c2/src/pages/FunctionManagementPage.vue#L102-L158
[U-back]: https://github.com/zzliux/assttyys_ui/blob/9e9656339a0268019e1894f53d2489246c0f61c2/src/main.ts#L30-L46
[U-grid]: https://github.com/zzliux/assttyys_ui/blob/9e9656339a0268019e1894f53d2489246c0f61c2/src/pages/SchemeManagementPage.vue#L403-L425
[U-float-settings]: https://github.com/zzliux/assttyys_ui/blob/9e9656339a0268019e1894f53d2489246c0f61c2/src/components/FloatyListRefDialog.vue#L10-L28

[N-broadcast]: https://github.com/zzliux/assttyys_autojs/blob/7d26c420f10f43ea9eebfc8eeacf73537ea3bbdc/src/system/script.ts#L1000-L1005
[U-function-form]: https://github.com/zzliux/assttyys_ui/blob/9e9656339a0268019e1894f53d2489246c0f61c2/src/pages/FunctionManagementPage.vue#L382-L439
