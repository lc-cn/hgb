# UI v5 完整流程与开发者主题交付

采用已安装的 `mobile-uiux-pro-max` 技能，读取 `rules/system/dark-mode.md` 与 `design-system/token-spec.md`。目标是 Android 的单页面／列表详情结构，HTML 仅用于本地可点击评审。使用共享明暗语义角色，黑白灰仅为主色，功能状态保留语义色；液态玻璃只用于明确按钮，内容、表单、运行记录和日志保持实底。

## 文件与入口

- `full.html`：保留已验收 UI v3 完整业务模拟，入口默认托管计划。`#plans`、`#settings`、`#messages`、`#task-updates`、`#network`、`#model`、`#notification-management` 等完整详情仍可访问。
- `full-base.css`：继承的页面布局与原生控件结构，原有配色／阴影已归入共享语义角色。
- `full-controls.css`：完整流程的补充布局、按钮热区与主题材料适配。
- `developer.html/css/js`：受 GitHub 保护的制作、schema、只读 JSON、观察、唯一执行器调试、诊断和一次上传 Release 流程。
- 主导航的工作台／任务中心／游戏跳往 `app.html?page=home|tasks|game&orientation=…`。full 的计划／历史／设置保持完整路由；详情返回不跳绿色 v3。
- 设置页顶栏标题右侧直接调用共享 `uiAppearance.controlMarkup()` 的系统／浅色／深色紧凑按钮，不增加外观页面或列表入口；外观变化只重绘展示，不改变运行配置或规则。
- 开发者返回 `full.html?orientation=…#settings`，相关入口携带方向。跨页主题由共享 Appearance 管理，JS 导航也保留主题／降低透明度／减少动效状态。

所有本地模拟器脚本仍从 `../../prototype/` 引入；PRD、使用帮助的相对路径保持同层有效。v3/v4 文件没有修改。

## 主题与材料

加载顺序：同步 `appearance.js` → `theme-tokens.css` → 私有布局／控件 CSS → 最后 `material.css`。

- 默认跟随系统，手动浅色／深色由共享外观模块管理，不按主题创建另一份业务状态。
- 未定义或覆盖共享语义颜色角色，组件中无 hex／rgb 原色。阴影引用 `--shadow-*`。
- primary／selected 明确使用 strong glass；单选／多选选中态保留文字／勾选语义，未用颜色独自表达。
- 有操作的图标／运行／选择按钮按 `--hit:48px`；图标按 `--icon:22px`。列表文字点击区 `row-main` 也有 48 px 热区。
- 不用全局 disabled opacity 淡化账号、队列或正文；禁用操作使用共享禁用角色，资料和状态继续可读。
- 降低透明度和无 backdrop-filter 的 fallback 由共享材料提供。减少动效由共享模块处理。
- 观察示意的文字与定位标签使用独立实底／半实底，避免白字直接贴在中灰画面上。
- 外置主题评审控件在本页面重渲染时保留，不因切路由、旋转或编辑消失。

## 业务边界保留

开发者代码相对 v3 只增加按钮材料类、共享主题评审节点保留和返回路径，没有新增或放宽授权／发布／输入规则。仍保留：指定仓库权限检查、所有路由与命令鉴权、未知结果禁止重置、草稿／测试配置快照、两种恢复方式、配置约束校验并定位、错误按任务拥有、完整任务集上传、响应未知阻止重发、明确权限拒绝后重授权可恢复上传。

full 保留唯一已有执行／事件／游戏模拟模块，五主导航、任务／计划／配置／通知／版本语义不变。新增的跨页导航只改变展示入口，不引入真实网络、输入、登录态或发布。

## 验证与限制

- `node --check` 通过 full 的抽取内联脚本与 developer.js。
- 既有开发者 Node 事件／状态回归对 v5 副本通过：保护路由和命令、fork 权限拒绝、unknown 证据、schema 草稿、配置范围／日期范围、快照、暂停／继续／重做、完整上传、权限撤销回调、跨任务错误归属。
- 技能 `run_all.py` 对 developer.html、developer.css、full-base.css、full-controls.css 报 PASS。该静态工具不证明浏览器合成透明材料后的真实对比度、目标像素或全部运行路径。
- 明暗主题、横竖屏、玻璃与降低透明度的实际渲染由根 agent 统一浏览器验收。本子 agent 没有启动或操作浏览器，也没有真实 GitHub／游戏能力。

## 用户纠正后的状态色

运行／信息为蓝，成功为绿，警告／结果未知为琥珀，失败／校验错误为红，更新圆点为红。来源为共享 `--status-*` 和 `--update-dot`，在明暗主题分别解析。日志图标、校验结果、上传图标、授权结果、运行标识、历史结果及版本提示按现有结果添加展示属性，不改变调度、授权、校验或发布决策；普通标题、账号资料、优先级、资源和数字仍为黑白灰。未知结果不再套失败红，仍保留文案与图标。

## 材料降级的级联归属

`full-base.css`、`full-controls.css`、`developer.css` 的全部组件规则整体位于原生 `@layer components` 中，文件内的既有顺序保持不变。共享主题／材料保持 unlayered，因此玻璃主按钮、选中按钮、降低透明度和无 backdrop-filter 的降级样式优先于组件继承的高特异性背景，避免靠 `!important` 或继续堆选择器补丁。组件负责布局和内容状态角色；本次未改业务 JavaScript。URL 手动主题与刷新一致性由共享 Appearance 管理。

路由参数归属修正：full 的 `urlUpdate()` 仅 set 自己拥有的 view／orientation／scene／layer，并更新 page hash，不覆盖整段查询；theme／transparency／motion 及其他外部参数在导航、旋转和刷新中保留。共享 Appearance 无新增兜底。

## 后续图标外观调整

用户允许图标丰富配色，并要求混入透明度。共享材质层对现有 SVG 增加色彩与透明度，未增加业务操作：普通图标82%、提示状态图标92%、禁用图标保留灰色可读；选中态使用适配强色背景的反色角色。事件容器的真实状态优先于图形身份色，未知仍为琥珀，运行仍为蓝。最终图像与复核结果见 `review-report.md`。
