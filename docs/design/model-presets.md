# 云端视觉模型预设与密钥获取引导

版本：0.1<br>
核验日期：2026-11-15<br>
状态：仅核验官方文档契约，未创建账号、获取密钥或实际调用。模型可用性、额度、网络与游戏视觉效果仍需使用用户账号验证。

## 一 普通用户界面

用户从服务商下拉中选择，再点右侧“配置”展开，内置服务普通路径只填API Key，可主动验证。新增入口允许添加具名服务商并配置协议、地址、模型和密钥；不再用固定“高级自定义”选项代表所有自有服务。新增不自动启用模型。切换服务保留各自配置与密钥草稿，连接身份变化不会串用凭据。

建议初始提供以下两个待验收预设，不自动选择厂商，不在失败、欠费或限流时自动切换模型／厂商：

| 预设名称 | 用户填写 | 内置模型建议 | 服务区域及适用密钥 |
| --- | --- | --- | --- |
| Google Gemini · AI Studio | Gemini API Key | Gemini 4 Hydrogen，`gemini-4-hydrogen` | Gemini Developer API 的全球接入服务；受官方可用国家／地区限制。此预设不属于 Vertex AI，不接受其 OAuth／服务账号配置冒充 API Key |
| 阿里云百炼 · 中国站北京 | 百炼 API Key | 通义千问 Plus，`qwen3.7-plus` | 华北2（北京）按量付费 API；只使用该地域模型服务的普通 API Key，不使用其他地域或 Token／Coding Plan 专属 Key |

这是根据当前官方模型清单作出的候选选择，并非游戏效果排名。Google 官方把 Hydrogen 标为稳定、支持图像输入与结构化输出；阿里云当前将 `qwen3.7-plus` 列为文本与视觉的均衡选择。成本优先的 `qwen3.8-flash` 可作后续人工比较候选，不作为自动回退。旧 `qwen-vl-plus`／`qwen3-vl-plus` 不再作为新预设的默认模型。[Gemini Hydrogen 模型页](https://aistudio.google.com/docs/models/gemini-4-hydrogen)、[百炼文本生成与视觉理解](https://help.aliyun.com/zh/model-studio/text-generation-model/)

预设参数应随应用发布版本记录，用户能查看当前所用模型。预设变更后需要重新验证，不无提示更换已保存的模型。没有 Key 的用户可跳过，继续使用本地已适配任务。

## 二 Google Gemini 预设

### 给用户看的三步说明

1. 打开 Google AI Studio，使用符合使用条件的 Google 账号登录，并按页面要求接受条款、创建或导入项目。
2. 在 API Keys 页面创建并复制 Gemini API Key；如果项目要求验证或计费设置，按控制台提示完成。
3. 返回本 App，选择“Google Gemini · AI Studio”，粘贴 Key，点击“验证图片识别”。

按钮链接：

- **获取 API Key**：[Google AI Studio API Keys](https://aistudio.google.com/apikey)
- **查看官方教程**：[Using Gemini API keys](https://aistudio.google.com/docs/api-key)
- **查看服务地区**：[Available regions](https://aistudio.google.com/docs/available-regions)
- **查看额度与计费**：[Billing](https://aistudio.google.com/docs/billing)

官方说明新用户接受条款后可自动获得默认项目与 Key，已有 Cloud 项目的用户可能需要导入；项目、Key 权限和验证条件以控制台为准。AI Studio 要求 18 岁及以上，且仅在支持的地区提供服务。不能因为填写了 Key 就声称任何地区可用，也不能将 Google 聊天订阅或一般 Cloud 赠金等同于该 API 的可用额度。[密钥管理](https://aistudio.google.com/docs/api-key)、[AI Studio 使用条件与问题处理](https://aistudio.google.com/docs/troubleshoot-ai-studio)、[计费说明](https://aistudio.google.com/docs/billing)

简短辅助文案建议：**“需要可用的 Google AI Studio 项目及 Gemini API Key；额度和费用以服务商控制台为准。”** 不写“永久免费”“注册必送可全天使用额度”。

### 内置连接参数

| 项目 | 值 |
| --- | --- |
| 适配器 | Gemini Interactions API 原生协议 |
| HTTP endpoint | `POST https://generativelanguage.googleapis.com/v1beta/interactions` |
| 认证 | `x-goog-api-key: <用户填写的 Key>` |
| API revision | `Api-Revision: 2026-05-20`，按当前官方示例记录，实施前重新核验 |
| 模型 | `gemini-4-hydrogen` |
| 图片输入 | `input` 中的 image 内容包含 Base64 `data` 与 `mime_type`；图片与文字提示组合 |

这些字段均来自当前官方入门 REST／图片输入示例。Google 文档当前从 `ai.google.dev/gemini-api/docs` 重定向至 `aistudio.google.com/docs`；不要仅根据旧文档示例继续固定旧 Flash 型号或混用不同 API 的请求体。[官方入门与图片输入](https://aistudio.google.com/docs/get-started)

## 三 阿里云百炼中国站北京预设

### 给用户看的三步说明

1. 登录阿里云百炼中国站，选择“华北2（北京）”；完成控制台要求的实名认证等条件，确认百炼服务可用。
2. 打开北京地域 API Key 页面，创建普通模型 API Key。新版 Key 仅创建成功时完整显示一次，应立即复制保存；创建权限不足时由账号管理员处理。
3. 返回本 App，选择“阿里云百炼 · 中国站北京”，粘贴 Key，点击“验证图片识别”。

按钮链接：

- **获取北京 API Key**：[北京 API Key 控制台](https://bailian.console.aliyun.com/cn-beijing/model/settings/api-key)
- **查看官方教程**：[获取与配置 API Key](https://help.aliyun.com/zh/model-studio/get-api-key)
- **开通遇到问题**：[百炼常见问题](https://help.aliyun.com/zh/model-studio/faq-about-alibaba-cloud-model-studio)
- **查看额度与用完即停**：[新人免费额度](https://help.aliyun.com/zh/model-studio/new-free-quota)

官方当前说明：主账号或具备相应权限的 RAM 账号满足实名认证等条件后，登录控制台可自动开通服务，不应在教程中杜撰所有用户都需要点击的“立即开通”按钮。免费额度是否获得、具体模型额度及有效期以控制台为准；用完后可能转为按量计费，用完即停是服务商侧可选设置，App 不替用户开启。[开通规则](https://help.aliyun.com/zh/model-studio/faq-about-alibaba-cloud-model-studio)、[新人额度规则](https://help.aliyun.com/zh/model-studio/new-free-quota)

简短辅助文案建议：**“请使用华北2（北京）的百炼普通 API Key；其他地域及 Token／Coding Plan Key 不适用。额度和费用以百炼控制台为准。”**

### 内置连接参数

| 项目 | 值 |
| --- | --- |
| 适配器 | 百炼 OpenAI-compatible Chat Completions 协议 |
| Base URL | `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| HTTP endpoint | `POST https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions` |
| 认证 | `Authorization: Bearer <用户填写的 Key>` |
| 模型 | `qwen3.7-plus` |
| 图片输入 | user message 的 content 使用 `type: image_url`，URL 可为 `data:image/png;base64,...` 等官方支持格式，组合文字提示 |

官方目前推荐 `https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1` 的业务空间专属域名，同时明确原 DashScope 共享域名仍可使用。普通用户只填 Key 的预设使用上述仍受支持的共享域名，因此无需额外填 Workspace ID；高级自定义可容纳专属域名，但不得通过猜测 Key 推导 Workspace ID。[Base URL 总览](https://help.aliyun.com/zh/model-studio/base-url)、[图片请求格式](https://help.aliyun.com/zh/model-studio/vision)

各地域有独立的域名、Key 和模型列表，不能跨地域混用；同样必须区分普通按量 API、Token Plan 与 Coding Plan 的 Key 及端点。预设失败不能自动拿北京 Key 去请求国际站或其他计费端点。当前模型目录及图片文档支持以上候选，但账号能否调用北京具体模型仍须实际验证。[密钥地域说明](https://help.aliyun.com/zh/model-studio/get-api-key)、[计费方案与地域端点](https://help.aliyun.com/zh/model-studio/base-url)、[模型能力](https://help.aliyun.com/zh/model-studio/text-generation-model/)

## 四 高级设置与测试结果

普通界面只显示预设、Key、帮助入口、验证按钮及结果。高级配置折叠显示，并明确属于自定义连接：协议、端点、模型和必要参数不能互相独立随意混搭。展开不改变当前设置，修改和保存也不自动代表连接验证通过。

验证使用 App 内置的非个人示例图，不在没有说明的情况下上传当前游戏截图。测试完成至少区分：Key／认证不通过、服务地区或账户条件不满足、模型无权限／不存在、额度或余额不足、限流、网络超时、图片输入不兼容、响应格式不满足、图片测试通过。测试“图片通过”不等于“所有游戏任务可用”。

不要只用文本 ping 将连接标成视觉可用；也不要在用户只是输入 Key 时自动触发计费测试。按钮应明确“验证图片识别”，并提示会发起一次模型调用、可能消耗服务商额度。具体自动重试与预算仍按用户确认规则执行。

## 五 凭据与移动端实现约束

这里的“只填 Key”指用户自带凭据，不是把开发者共用 Key 编译进 APK。Google 官方反对在生产客户端代码中暴露密钥，百炼官方也提示移动应用中不应直接配置长期 Key，建议临时凭据。BYOK 本地保存、访问限制及是否采用临时凭据需在实现评审中明确，不能仅用“已加密”宣称不存在提取风险，也不能为解决此问题自动增加未批准的后端服务。[Google 密钥安全](https://aistudio.google.com/docs/api-key#security)、[百炼凭据说明](https://help.aliyun.com/zh/model-studio/get-api-key)

本稿不修改 HTML 或主 PRD，不保存任何真实 Key，不打开用户云控制台执行账号操作，也不实际调用模型。实施前重新核验模型生命周期、预设端点和协议 revision；失效时说明具体原因，不静默换厂商或模型。
