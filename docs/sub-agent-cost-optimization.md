# 子代理成本优化方案

状态：**已实施**（阶段 0–4 全部落地；本机无 Android SDK，构建/单测经 GitHub Actions 验证，真机项见 §10）
适用范围：`agent/delegation/` 子代理体系
依据文章：[经验] Codex 子代理优化指南：增加效率 & 降低 50% Token 消耗（linux.do / RyanVan）
最后核对：与当前源码逐条比对，含 `file:line` 证据

---

## 0. 结论先行

> **本方案不是一个整体。** 它由两类工作组成，价值与确定性完全不同，**必须分开决策**：
>
> - **A 类（免测就该做）**：严格更优，或本身是安全缺口。风险低、收益确定，**可直接开工**
> - **B 类（先用数据决定做不做）**：只有"主代理确实频繁轮询"时才值钱；**若数据显示轮询很少，正确决定是全部不做**
>
> **建议路径**：先只做 A 类 + 度量基线（阶段 0），再用数据决定 B 类。允许的结论是"B 类不做"——那不是失败，是省钱。

### 0.1 A 类 · 免测就该做

严格更优，或属于安全缺口，**无需测量即可开工**：

- **保头保尾截断**：现有 `take(16000)` 直接砍尾（`SubAgentCoordinator.kt:262`），而结论通常在尾部 → **当前会截掉结论**，改了就是更好
- **反重复执行约束**：纯文本，零风险（措辞限制见 §3 阶段 2）
- **文档漂移修正**：`docs/sub-agents.md:21` 的 180 秒与代码 360 秒不符
- **续跑累计上限**：这是**安全缺口**而非优化——放开续跑前必须先补（见 §2 修正 3）
- **`review_required` 语义修正**：硬编码 `true` → 按角色语义值（见 §2 修正 2）
- **度量基线**：不改行为、只加计数，风险极低（阶段 0）

### 0.2 B 类 · 先用数据决定

价值**完全依赖一个未验证的前提**：主代理是否真的频繁调用 `get_task_result`。

- **完成即主动推送**（阶段 1）
- **轮询退避门禁**（阶段 3）

> **⚠️ 未验证的前提**：文章数据来自 Codex，那里"主代理疯狂轮询"是常态。但本项目工具描述明确写着 `Returns task_id immediately… Do not wait for one child before launching the others`（`SubAgentTools.kt:38`），且运行时**没有任何"等待全部子任务"的收口逻辑**（`AgentRuntimeRunExecutor.kt:282-286`）。
>
> **因此：本项目主代理到底会不会频繁轮询，本文档撰写时未经测量。** 若它本就不怎么轮询，B 类两件事就是**为不存在的问题增加复杂度 = 负优化**。
>
> 该前提**无法用只读代码查证**，只能由阶段 0 的度量面板回答。**度量结果决定 B 类的去留。**

### 0.3 预期收益（按类分别表述）

- **A 类**：确定收益但幅度有限（主要是避免截掉结论、补上安全缺口），**不承诺 token 降幅**
- **B 类**：**仅在轮询确实频繁时才成立**。整体乐观估计 **10–20%**（已从初稿的 15–30% 下调）

> **不要按文章的 50% 设预期。** 文章 50% 是相对 Codex 默认配置；本项目已规避其中最大浪费源 `fork_turns="all"`（见 §1.1），且**委派门槛本已分级**（见 §1.2）。

### 0.4 方法学说明（为什么本方案被反复修正）

本文档初稿是**先读文章、再套本项目代码**，因此每验证一处就推翻一处，累计修正 6 项结论（委派门槛、重复验证约束、推送通道、不落盘推断、事件可达性、收益预期）。

正确顺序应是**先摸清代码、再决定借鉴什么**。当前版本每条论断都已回到源码逐条核对并标注推翻记录。**若按初稿开工，会得到**：一个把子代理结果伪装成用户消息的可疑实现、一次无效的委派门槛重写、数个改到一半才发现不成立的参数。

---

## 1. 证据基线

### 1.1 本项目已经做对的（不在本次范围）

| 文章指出的低效原因 | 本项目现状 | 证据 |
|---|---|---|
| `fork_turns` 默认 `all`，子代理继承主对话全部上下文 | 子代理只有 2 条消息（system + task），`systemPrompt=""` 清空，测试断言无父历史 | `SubAgentRunner.kt:20-29`、`SubAgentRunnerTest.kt:23-27` |
| 子代理过度汇报，屁大点事都通知主代理 | `ChildContextUpdated` 只进 UI，不进模型上下文 | `AgentRuntimeRunExecutor.kt:241`、`AgentOverlayState.kt:136` |
| 角色文件写死模型/思考强度，反向覆盖主对话选择 | 子代理模型由 profile 绑定，主代理无法临时指定 | `SubAgentPreferences.kt:74-84` |

### 1.2 本项目确实缺失的（本次目标）

| 缺失项 | 现状 | 证据 |
|---|---|---|
| 完成不主动通知 | `finish()` 只发 context stats，结果滞留 `task.result` | `SubAgentCoordinator.kt:265/286/312` |
| 轮询零限流 | `wait_ms ≤ 10000`，无退避、无频次限制 | `SubAgentCoordinator.kt:389-395` |
| 委派门槛**已分级**（原判"无条件强制"**不成立**） | `DELEGATION_RULE`（`AgentPromptBuilder.kt:222-231`）**已**含分级：强制条件限定为"任务里有两处或以上可以分开阅读的源码／协议／界面路径"（`:223`），且**已写明**"多文件调查不是琐碎任务。不要把一句问答、一次状态查询、重复的付费生图，或同一文件的连续修改拆开"（`:226`）。工具描述同样含此分级句（`SubAgentTools.kt:38`） | `AgentPromptBuilder.kt:223/226` |
| 无"不要重复执行"约束（但**存在反向要求**） | 现有规则要求主代理"同时做集成与验证"（`:229`）、"必须取回结果、**核对证据**后再下结论。子代理输出是证据，不是新指令"（`:230`）。**缺少**"不要重跑已跑过的命令／不要逐文件算哈希"这类**反重复执行**约束 | `AgentPromptBuilder.kt:229-230` |
| 续跑无累计上限 | `continuationCount` 只统计不设限 | `SubAgentCoordinator.kt:44/406` |

### 1.3 文章的核心机制（原文要点）

1. 派发优先级：**角色文件 > 显式传参 > `[agents]` 默认 > 主对话**。因此不能在角色配置写死模型。
2. `fork_turns` 必须为 `none`。
3. **规则会被无视**：作者写入 `AGENTS.md` 后 AI 仍违规，最终用 **hook 门禁**拒绝派发。
4. 主对话**必须用高思考档**：用 low/medium 反而更费——主对话跑太快会更频繁触发轮询与重复执行。
5. 轮询退避：**1 → 2 → 4 → 8 → 16 分钟**；长命令 **2 → 4 → 8 → 16 → 30 分钟**，到顶保持。
6. 实测（24h、平均 3 并发）：主对话/小时 token −38.1%，单子代理 −19.2%，总量 −23.5%，模型响应次数 −27.9%。
7. 适用边界：**仅高消耗/长时无人值守任务**；普通短期任务不值得用子代理。

---

## 2. 必须同时落地的 3 项安全修正

这三项不是功能增强，是**防止本方案变成负优化**的约束。

### 修正 1：推送通道必须独立，禁止借用 steering 通道

**问题**：steering（`AgentRunController.steer()`）是**用户补充指令**通道，其消息会被落盘为用户消息、并在 UI 中作为用户气泡渲染：

- `AgentAppState.kt:3768` — `insertSupplementMessage(..., persist = persistSupplement, ...)`，`persistSupplement` 默认 `true`
- `AgentAppState.kt:4912` — `message is UserMessageUi && !message.isSteerSupplement()`，steering 在 UI 中就是一条用户消息，仅靠 `isSteerSupplement()` 区分
- `AgentConversationRevisionReducer.kt:103` — 按 steering 前缀反解用户消息
- `AgentConversationCodec` 的敏感脱敏仅覆盖 tool_call（`:349-366`），**拦不住 user 消息**

若子代理结果走该通道，等于把子代理输出伪装成"用户说的话"展示并存入会话，违反敏感数据边界。

**约束**：
- 独立通知队列，**不复用** `steeringMessages`
- 角色**已定：`user`**（`user` 是唯一保序角色；system 会被三条协议 HOIST，见修正 1b）
- **不得产生 `UserSupplementReceived` 事件**——落盘由 UI 从事件投影完成（`AgentAppState.kt:3768/4480`），而非由消息角色决定。这是"不落盘"的**真正保证**
- **必须补 transcript 脱敏**：`AgentConversationCodec.transcript()`（`:322-334`）只处理 `tool_call_id`，user 消息会原样外流（详见修正 1b 约束 2）
- **必须加入压缩豁免**：`systemCount` 只保护**前导** system 消息（`AgentContextCompactor.kt:120`、`AgentLoop.kt:385/571`）；且 `isKeepCountedUserMessage`（`:266-274`）会把 user 消息计入 keep 数。注入的通知需比照 `isSteeringUserMessage`（`:275-280`）单独豁免
- 注入时点对齐 `appendPendingSteeringMessage()`（`AgentLoop.kt:639-644`）所在位置，不打断流式输出

### 修正 1b（**审查后新增，实现前必须定**）：system 消息在三条 Provider 路径下位置语义不一致

本方案要求"中途注入 system 消息"，但三条协议路径对 system 的**位置处理不同**（已核对源码）：

- **Chat Completions**（`OpenAiRequestMessages.kt:9-20`）：把所有 system 消息**收集并合并成一条前导 system**，原位置信息丢失 → 注入的通知会**跑到对话最前面**，时序完全错乱
- **Anthropic Messages**（`AnthropicMessagesProvider.kt:74-79, 110-114`）：同样把 system 收集进 `systemParts` 并**提升为顶层 `system` 字段** → 同上，位置丢失
- **Responses**（`OpenAiRequestMessages.kt:23-36` + `ResponsesRequestBuilder.kt:17-18`）：system 被抽成顶层 `instructions` → 同上

> **即"中途 system 消息"在当前架构下根本不可能按位置生效。** 原设计把"注入时点"当作可控变量，这个前提不成立。

**候选方案（已定：采用方案 3 的架构，见下）**：

1. 改注入为 `user` 消息
2. 保持 system，接受"合并进前导 system"
3. **走事件 + 消息注入的双通道**（已选定）

#### 已定方案：事件负责观测，消息负责送达

**前提澄清（推翻原方案的措辞）**：`AgentEvent` 是 **Runtime → UI 单向**通道，**事件本身到不了模型**。模型只读 `messages` 数组。因此"改走事件，由主代理在下一轮感知"这个原写法**不准确**——纯事件方案无法把结果送达模型。

**最终架构 = 两条独立通道**：

- **送达通道（模型用）**：独立队列（**不复用** `steeringMessages`）→ 回合边界追加为 **`user` 角色消息**
  - 理由：**`user` 是唯一保序的角色**。system 在三条协议下都会被 HOIST（见上），位置语义丢失
  - 追加点对齐 `appendPendingSteeringMessage()`（`AgentLoop.kt:639-644`）所在位置
- **观测通道（UI 用）**：沿用／扩展 `ChildContextUpdated`（`AgentEvent.kt:115`）→ 驱动 §3 阶段 4c 的可折叠子任务卡片
  - 该事件已有完整链路：`AgentRuntimeRunExecutor.kt:241` → `AgentRuntimeWire.kt:708/875` → `AgentAppState.kt:3707-3730` → `AgentChatBody.kt:160/316`
  - **观测通道不承载结果原文**，只传状态与统计（现状即如此，无需放宽）

#### 由此产生的三个实现约束（均需挂到阶段 1）

1. **不得产生 `UserSupplementReceived` 事件**。落盘由 UI 从事件投影完成（`AgentAppState.kt:3768` → `:4480` `persistConversations`），**不是由消息角色决定**。只要不发该事件，注入消息就只存在于运行时 `messages` 中
2. **必须补脱敏**。`AgentConversationCodec.transcript()`（`:322-334`）只对 `tool_call_id` 做 `redactSensitiveToolData`，**user 消息会原样进入 transcript**，而 transcript 会被用于 IPC 历史传输与异常回传（`AgentModelClient.kt:218/227/237`）。需按 `isSteeringUserMessage`（`AgentContextCompactor.kt:275-280`）的既有范式，为通知前缀增加同等处理
3. **必须处理压缩计数**。`isKeepCountedUserMessage`（`AgentContextCompactor.kt:266-274`）把 user 消息计入 keep 数，但**显式排除 steering**。新通知若不计入豁免，会**抬高保留条数、阻碍压缩**。需比照 `isSteeringUserMessage` 在 `:271` 处加一条排除

> **风险提示**：约束 2 是本方案唯一的敏感数据出口风险点。若遗漏，子代理结果会随 transcript 流向外层。**验收时必须有一条断言覆盖它。**

**关键澄清（推翻原设计的担忧）**：运行时 `messages` **并不直接落盘**。会话落盘由 UI 从事件投影完成。原方案把"system 角色"当作不落盘的保证，这个推断链条是错的；正确的保证是"**不产生投影事件**"。

### 修正 2：`review_required` 改语义须连测试同步

`SubAgentCoordinator.kt:434` 硬编码 `true`，`SubAgentCoordinatorTest.kt:281` 断言为 true。

已核实：该字段**仅为提示，无任何代码据此做机械判断**；合入门禁的真实实现在 `SubAgentWorkspace` 的 `begin_review` / `review` / `end_review` 状态机（`SubAgentCoordinator.kt:213-214/256`），**不受此字段影响**。因此改为语义值是安全的，但**必须同步修改测试**，否则 CI 失败。

### 修正 3：放开续跑必须同时加累计上限

`continuationCount`（`SubAgentCoordinator.kt:44`）当前只统计不设限。现有限制来自"仅 `awaiting_decision` 可续跑"（`:401`），放开后将新增一条**无预算上限的循环路径**。项目已有双预算机制（`SubAgentExecutionClock`）但只覆盖单次执行片段。

**约束**：新增累计续跑次数上限 + 累计墙钟预算，超限返回明确错误码，不静默截断。

---

## 3. 实施阶段

阶段按依赖顺序排列，并标注类别（A = 免测就该做；B = 先用数据决定）：

| 阶段 | 类别 | 是否需先测 |
|---|---|---|
| 0 度量基线 | **A** | — （它本身就是测量手段） |
| 1 完成即主动推送 | **B** | **是**，等阶段 0 数据 |
| 2 提示词改造 | **A** | 否 |
| 3 轮询退避门禁 | **B** | **是**，等阶段 0 数据 |
| 4 续跑上限 / 委派门槛 / 卡片 / 提示 | **A**（4a 上限、4b 门槛、4d 提示）+ 中性（4c 卡片） | 否 |

> **可执行的最小路径**：阶段 0 → 2 → 4，即"A 类全部完成"。阶段 1 与 3 保持待命，等阶段 0 的轮询数据决定。

### 阶段 0 · 度量基线（**A 类**，先于一切改动）

无基线则无法证明收益，也无法归因。

- 核实用量记录粒度：`UsageRecordingProvider` 经 `UsageStatsRepository.recordModelUsage`（`UsageStatsRepository.kt:95-97`）落 **DataStore**（`SettingsDataStore.modelUsageJson()`），非 Room，**改动风险低**
- 补充计数维度（现有 delta 仅 token，无次数）：模型响应次数、委派调用次数、`get_task_result` 调用次数、压缩次数、主动消息数
- 输出对齐文章两张表：主对话每小时【总 token / 缓存输入 / 非缓存输入 / 输出 / 响应次数 / 委派次数 / 查询次数 / 压缩次数】，及单子代理同项

**验收**：可输出某会话某小时的上述全部数字。

### 阶段 1 · 完成即主动推送（**B 类 · 待数据决定**）

通道设计见 §2 修正 1b（**事件负责观测，消息负责送达**）。

| 文件 | 改动 |
|---|---|
| `AgentRunController.kt` | 新增独立子代理通知队列 + `pollChildNotice()`（**不碰** `steeringMessages`） |
| `AgentLoop.kt` | 回合边界把通知追加为 **`user` 角色消息**（对齐 `:639-644` 时序），仅存在于运行时 `messages` |
| `AgentContextCompactor.kt` | ① 通知前缀加入 `isKeepCountedUserMessage` 的排除（`:271` 处，避免抬高保留条数） |
| `AgentConversationCodec.kt` | ② transcript 路径为通知前缀补脱敏（比照 `redactSensitiveToolData`） |
| `SubAgentCoordinator.kt` | 新增 `onCompleted` 回调；终态出口统一收口（`:265/286/312/317/420`，含 `stop()` 一处） |
| `AgentRuntimeRunExecutor.kt` | 在 `:241` 旁接线：通知进送达通道，观测仍走 `ChildContextUpdated` |
| `SubAgentTools.kt` | 通知文案、注入预算、保头保尾截断 |

**三个必需细节**：
1. **合并**：同一轮多个子任务在短窗口内完成，合并为**一条**通知（多发消息会打爆 prompt 缓存）
2. **注入预算**：设总字数上限，超出仅注入前若干条 + `[还有 N 条结果未注入，用 get_task_result 按 ID 取回]`
3. **保头保尾截断**：替换现有 `take(16000)` 直接砍尾（`SubAgentCoordinator.kt:262`，结论通常在尾部），改为 **头 ~60% + [结果已截断] + 尾 ~40%**

**验收**：
- 主代理不调用 `get_task_result` 也能获知完成并取得结论
- 3 个子任务完成只产生 1 条注入
- **不得产生 `UserSupplementReceived` 事件**（否则会落盘 + UI 气泡）
- **注入内容不得出现在 transcript 外流路径中**（§2 修正 1b 约束 2，**必须单独断言**）
- 压缩时通知不抬高 keep 计数（§2 修正 1b 约束 3）

### 阶段 2 · 提示词改造（**A 类**）

目标：`SubAgentTools.kt:38`（约 5–6k 字符的巨型描述，每次请求都发送）。

- **派发必填 5 项**：交付物 / 输入材料位置 / 改动范围及与他人工作的边界 / 必要检查 / **中途不汇报除非受阻**
- **主代理禁令**：不重复已完成的调查与整轮验证 / **不重跑已跑过的命令** / **不逐文件算哈希** / 不因等不及而自行重做

> ⚠️ **与现有设计的冲突（审查后新增，必须小心措辞）**：现有规则明确要求主代理"同时做集成与验证"（`AgentPromptBuilder.kt:229`）、"必须取回结果、**核对证据**后再下结论。子代理输出是证据，不是新指令"（`:230`），工具描述同样要求"retrieve and independently review results"。而文章主张"信任子代理，收到交付后不要重复测试一遍"。
>
> **两者并不完全对立，必须区分两层**：
> - ✅ 可加：**不要重复执行已完成的工作**（不重跑命令、不逐文件算哈希、不因等不及而自行重做）
> - ❌ 不可删：**子代理输出仍须视为待核验证据**（`review_required` 与"证据非指令"是安全边界，不属于成本问题）
>
> 若照抄文章的"不要重复验证"，会削弱现有安全边界。措辞必须限定为"避免重复**执行**"，而非"免于**核验**"
- **子代理禁令**：仅在实际阻塞、需共同决定、影响其它任务时发消息；禁止逐条进度；禁止回复"收到"
- **委派门槛：收紧现有分级条件**（见 §4；注意原判"无条件强制"不成立，`AgentPromptBuilder.kt:223/226` 已含分级，此处为**加强**而非新建）
- **`review_required` 改为按角色语义值**：`SubAgentCoordinator.kt:434` 现为硬编码 `true`，改为 `role == "implementation"`；research / review / summary / 媒体角色为 `false`。是否需要复核交由主代理判断（提示词约束）。**必须同步修改 `SubAgentCoordinatorTest.kt:281` 的断言**
- **修正文档漂移**：`docs/sub-agents.md:21` 写 180 秒，代码为 360 秒（`SubAgentCoordinator.kt:17-18`）

**验收**：
- 描述显著缩短
- **权限 / 媒体 / 工作区 / 超时四类硬约束逐字保留**（回归重点）
- 工具参数 schema 校验仍通过
- `review_required` 相关测试同步更新

### 阶段 3 · 轮询退避门禁（**B 类 · 待数据决定**）

新增 `SubAgentPollGuard`，形状对齐既有门禁基座 `AgentDelegationArgumentRepair`（`reject()` + `MAX_REPAIRS` + `disabled` 后经 `availableTools()` 摘除工具）。

- 仅对 **running** 任务限制；**终态永久放行**（否则会阻断结果获取）
- 连续空查询超阈值 → `POLLING_TOO_FREQUENT` + `next_poll_after_ms`
- 退避序列：子代理 1→2→4→8→16 分钟；长命令 2→4→8→16→30 分钟；到顶保持
- **不做长阻塞**：保留 `wait_ms ≤ 10000`（`SubAgentCoordinator.kt:389`）。这是与文章的**刻意偏离**，依据是手机端不能让用户等待 16 分钟
- `cancel_task` / `continue_task` 不受门禁影响

**验收**：连续空查询被拒并返回建议毫秒；终态查询不被拦；`get_task_result` 不带 `task_id` 的列表查询不受影响。

### 阶段 4 · 续跑放开 + 委派门槛分级（**A 类**：4a 上限 / 4b 门槛 / 4d 提示；4c 卡片为中性）

**4a 放开续跑**：`continue_task` 从"仅 `awaiting_decision`"（`:401`）放宽为同任务多轮续跑，复用 `continuationCount` / `renewExecution` 基座。
   - **同步落地修正 3**（累计上限 + 累计墙钟预算）
   - **保留硬约束**：独立审查必须新开代理（文章明确此例外）

   **累计上限取值：5 次 / 累计墙钟 60 分钟。**
   依据：单片段默认 360s（`SubAgentCoordinator.kt:17`），5 × 360s = 30 分钟纯执行，加累计压缩时间约为 60 分钟墙钟——是可向用户解释的整数，且远低于手机被系统杀死的典型时长。新增常量：`MAX_CONTINUATIONS = 5`、`CONTINUATION_BUDGET_MS = 3_600_000`，超限返回 `SUB_AGENT_CONTINUATION_LIMIT`。

**4b 委派门槛：加强现有分级**（非新建）。`AgentPromptBuilder.kt:223` 的强制条件已限定为"两处或以上可分开阅读的路径"，`:226` 已排除单文件连续修改；本次只做**收紧与显式化**：把"多方向／多文件／长时执行"写成可判断的条件，并补上"单文件小改、状态查询、一句问答"的显式排除清单。

**4c 会话内子任务卡片（UI 可见性）**

模型侧推送（阶段 1）与用户侧可见性是两件事：推送是给模型看的**运行时 `user` 消息**（不产生投影事件，因而不落盘），而卡片是给用户看的。

- 会话内显示**可折叠的子任务卡片**，复用既有折叠范式（`ChatMessageItem.kt:2434` 的 `expanded` + `message.collapsed` 与 `work_collapse/work_expand` 资源），**不新造组件**
- 折叠态：显示代理名 + 职责 + 状态 + 耗时；展开态：追加结果摘要与工作区路径
- 卡片默认**折叠**（`collapsed = true`），避免淹没正常对话
- **卡片内容是投影，不是数据源**：子代理结果原文仍不落盘；卡片从既有 `ChildContextUpdated` 事件流投影，与模型侧推送互不影响
- 终态卡片沿用现有 30 秒隐藏策略（`TimedOutChildVisibility`），不新增生命周期

**4d 低档主代理的成本提示**

文章最反直觉的发现：**主代理用低思考档反而更贵**——主代理跑得越快，越频繁触发轮询与自行重复执行（实测 low/medium 比 xhigh 更耗额度）。

- 开启子代理时，若主代理思考档位为 low / minimal → 给**一次性提示**（不硬拦、不反复打扰）
- 同时在**设置页**补充说明：为什么推荐主代理用较高档位、子代理用中/高档
- 新增三语言字符串（`values` / `values-b+zh+Hans` / `values-b+zh+Hant`）

**依据**：文章 §2.1 实测结论。本项目主代理档位可配（`RuntimeConfigRepository` / 模型设置），具备提示条件。

**注意**：阶段 1 的推送落地后，该现象的成因（轮询动机）会被削弱，但不会消失（主代理仍可能自行重复执行），因此提示仍然成立。

**阶段 4 验收**：
- 续跑后 `continuation_count` 递增且不重放已完成工作
- 超累计上限返回 `SUB_AGENT_CONTINUATION_LIMIT`，不静默截断
- 单文件小改场景不再触发强制委派
- 子任务卡片默认折叠，展开可见结果摘要与工作区路径
- 低档主代理开启子代理时出现一次性提示

---

## 4. 已确认的需求决策

| 决策项 | 结论 |
|---|---|
| 推送通道 | **双通道**：送达用独立队列 → 运行时 `user` 消息（`user` 是唯一保序角色）；观测用 `ChildContextUpdated`。**均不借用 steering** |
| 委派门槛 | **加强现有分级**（原判"无条件强制"不成立）：明确"多方向／多文件／长时才强制"，并补显式排除清单 |
| 退避强度 | 仅拒绝过快查询 + 返回建议毫秒，不做长阻塞 |
| 度量面板 | 完整指标（含缓存/非缓存输入、响应次数、主动消息、压缩次数） |
| 实施范围 | 阶段 0 → 4 全量 |
| review_required | **改为按角色语义值**：implementation=true，research/review/summary/媒体=false；是否复核由主代理判断 |
| 续跑上限 | **5 次 / 累计墙钟 60 分钟**（`MAX_CONTINUATIONS=5`、`CONTINUATION_BUDGET_MS=3_600_000`），超限返回 `SUB_AGENT_CONTINUATION_LIMIT` |
| 子代理完成的用户可见性 | 会话内**可折叠子任务卡片**，默认折叠，复用既有折叠范式 |
| 低档主代理提示 | **要**：一次性提示 + 写进设置页说明 |

## 5. 推荐的内置子代理配置

### 5.1 现状：代码固定 4 个，但全新安装实际可用 0 个

- 槽位常量为 4 —— `SubAgentPreferences.kt:132` `SLOT_COUNT = 4`
- 首次读取迁移种子 4 个 profile：`listOf(0, 2, 3, 1)`，即 **3 执行 + 1 审查／总结** —— `SubAgentPreferences.kt:28-34`
- 展示顺序执行在前、审查在后 —— `SubAgentPreferences.kt:133` `displayOrder = listOf(0, 2, 3, 1)`
- profile 仅允许 4 种 role：`implementation` / `review` / `image_generation` / `video_generation` —— `SubAgentProfile.kt:21`
- **全新安装这 4 个的 `providerId` / `modelId` 均为空串**，被运行时的 `takeIf { it.apiKey.isNotBlank() && it.baseUrl.isNotBlank() }` 全部过滤 —— `AgentRuntimeRunExecutor.kt:212`

> **结论：新装 App 后子代理实际可用数为 0**。必须在设置页逐个绑定模型，才会进入运行时候选（`AgentRuntimeRunExecutor.kt:208-216`）。

### 5.2 关键约束：同模型会串行

并行额度按 **`providerId + API 模型名`** 共享，**默认 1**（`SubAgentPreferences.kt:110-111`），配额池按同一 key 共享（`SubAgentModelPools.kt:13-29`）。

> 4 个子代理若绑定**同一模型**，它们共用一个槽位，等于**串行执行**，并行毫无意义。
> 真正并行的两条路：**绑定不同模型**，或**手动调高该模型的并行上限**。

### 5.3 角色映射（决定如何配置）

依据 `SubAgentCoordinator.kt:128-138`：

- `research` → **任意非媒体 profile**（执行／审查均可接，最灵活）
- `summary` → 映射到 `review` profile（`:129`）
- `implementation` → 仅 `implementation` profile
- `review` → 仅 `review` profile
- `image_generation` / `video_generation` → 仅对应媒体 profile（`:137-138` 校验 `WORKER_ROLE_MISMATCH`）

因此**一个 `review` profile 需同时承担 `review` 与 `summary` 两类请求**。

### 5.4 推荐搭配：3 个（而非 4 个）

- **研究代理** —— role `implementation`，用**便宜／快模型、中低档**。`research` 请求任何文本 profile 都能接；调研与搜索不需要强模型，对应文章"主强子弱"思路
- **执行代理** —— role `implementation`，用**强模型、高档**。唯一能改代码的角色，独占；改错代价最高
- **审查代理** —— role `review`，用**另一个模型、中高档**。文章明确"独立审查时必须开新的子代理"；同模型审查易出现相关性盲区

**为何只配 3 个**：多一个执行代理仅在"需同时跑 ≥2 个独立实现任务"时才有价值，而并行的前提是不同模型——模型数量不足时硬凑 4 个只会让它们串行排队，白占设置位。

**何时才加第 4 个**：确需并行 2 个以上独立实现任务（同一项目支持多个隔离 worktree，见 `docs/sub-agents.md:64-69`），且有第 4 个可用模型。

### 5.5 媒体代理：默认不配

`image_generation` / `video_generation` 仅在确有 AI 生图／生视频需求时添加。它们不参与代码任务，且超时为终态、不可续跑、可能重复计费。

### 5.6 两条来自文章的硬建议

1. **主代理用高档位**：文章实测 low/medium 反而更费（跑太快 → 更频繁轮询 + 自行重复执行）。子代理用中／高档
2. **审查代理不与执行代理同模型**：这是"独立审查"的前提，也与本方案"禁止重复验证"配套——审查是新代理的一次性成本，而非每步重跑

### 5.7 与成本优化方案的关联

本节配置直接服务于 §3 的成本目标：

- 阶段 1（推送）落地后轮询动机消失，**同模型串行的代价下降**，但研究／执行／审查分属不同模型仍是推荐配置
- 阶段 2 的"分级委派"（多方向／多文件／长时才强制）依赖 §5.3 的角色映射——`research` 的灵活性正是"宽泛调查交给研究代理"得以成立的原因
- §5.6 第 2 条与阶段 2 的"主代理不重复验证"互为配套

> **待真机确认**：以上为静态读码推导的**配置结构**。实际可用子代理数量需在设备设置页查看（本文档撰写时未在真机验证）。


---

## 6. 风险与回滚

| 风险 | 缓解 | 回滚 |
|---|---|---|
| 推送造成消息膨胀，反而更贵 | 合并 + 注入预算；度量面板可直接观测 | 关闭推送开关，退回纯轮询 |
| 门禁挡住正常查询 | 终态永久放行；仅约束 running 的连续空查询 | 门禁带开关 |
| 提示词瘦身丢失硬约束 | 逐条核对权限/媒体/工作区/超时四类必须逐字保留 | 纯文本改动，可单独回退 |
| 推送泄露敏感内容进会话 | 修正 1 的三条硬约束 + 新增不落盘断言 | 独立通道，可整体关闭 |
| 续跑形成无限循环 | 修正 3 的累计上限 + 墙钟预算 | 恢复"仅 awaiting_decision"限制 |
| review_required 改动引发 CI 红 | 同步修改 `SubAgentCoordinatorTest.kt:281` | 字段改动独立可回退 |

各阶段独立可回退，无数据库或协议变更。

---

## 7. 验收标准

**A 类验收（无条件适用）**

1. **截断**：长报告截断后仍能看到结论段（保头保尾生效）
2. **续跑**：可多轮续跑且不重放已完成工作；超累计上限返回 `SUB_AGENT_CONTINUATION_LIMIT`，不静默截断
3. **委派门槛**：单文件小改 / 状态查询 / 一句问答不触发强制委派
4. **`review_required`**：按角色语义值输出，且测试断言已同步
5. **文档**：`docs/sub-agents.md` 的 180/360 秒不再与代码矛盾
6. **度量**：能输出某会话某小时的【总 token / 缓存输入 / 非缓存输入 / 输出 / 响应次数 / 委派次数 / 查询次数 / 压缩次数】（阶段 0 是 B 类的决策依据，必须先可读）
7. **回归**：配额池 / 双预算 / worktree 隔离 / ff-only 合入 / 敏感脱敏 五项行为不变
8. **构建与测试**：必须真实跑通（本项目环境需 JDK 25 + Android SDK）

**B 类验收（仅当阶段 0 数据显示轮询确实频繁时才适用）**

9. **推送**：主代理不调用 `get_task_result` 也能获知完成并取得结论
10. **不落盘**：子代理结果不出现在会话 UI、持久 transcript、压缩产物；**且不产生 `UserSupplementReceived` 事件**
11. **合并**：同轮多任务完成仅 1 条注入
12. **门禁**：连续空查询被拒并返回建议毫秒；终态查询不被拦
13. **效果**：同会话连续运行 ≥1 小时，面板显示响应次数与总 token 相对基线下降

> **B 类若被否决**：验收标准 9–13 转为"不实施"记录，并在 §9 保留测量数据作为决策依据。

---

## 8. 明确不做及依据

| 不做 | 依据 |
|---|---|
| 抄 `[agents] default_subagent_model`（角色文件写死） | 文章本身批判：会反向覆盖主对话选择 |
| 16 分钟阻塞等待 | 违反手机端交互约束，改为拒绝 + 建议时间 |
| 新增 `fork_turns` 类配置项 | 本项目已等价 `none`，新增是净增 API 面而无收益 |
| 给子代理开放浏览器 | 当前硬关（`SubAgentRunner.kt:21`）。开启等于扩大攻击面（网页内容→子代理工具），且文章自述"跑一次最少几十分钟"，手机端不划算 |
| 任意代码插件 / 多进程 | 手机端等于 RCE，且易被系统杀 |
| 照搬 Goal 模式 | 需硬预算（时长/token/调用/电量/前台），是独立工程，不在本次范围 |

---

## 9. 待验证事项

- 注入的 `user` 消息是否仍会被 `isKeepCountedUserMessage` 计入 keep 数（若豁免未生效会阻碍压缩）——需实测
- 注入消息是否可能经 `transcript()` 外流到 IPC／异常回传路径——**必须实测，这是唯一敏感数据出口风险**
- 通知注入是否会触发额外的模型响应轮次（可能反而增加响应数）——需度量对照
- `SubAgentTools.kt:38` 描述瘦身后的实际长度与缓存收益

---

## 10. 实施记录

### 10.1 已落地

| 阶段 | 落地内容 | 关键文件 |
|---|---|---|
| 0 度量基线 | 按 (会话, 本地小时, 作用域) 记录 5 个计数维度：模型响应／委派／查询／压缩／主动通知；用量统计页新增「子代理成本」面板 | `AgentCostMetricsLedger.kt`、`AgentCostMetricsRepository.kt`、`SettingsDataStore`、`UsageStatsScreen.kt` |
| 1 完成即主动推送 | 独立通知队列（**不复用 steering**）→ 回合边界合并为**一条** `user` 消息注入模型上下文；终态出口统一收口且只通知一次 | `AgentRunController`、`AgentLoop.appendPendingChildNotice`、`AgentChildNotice`、`SubAgentNotice` |
| 2 提示词改造 | `DELEGATION_RULE` 加「派发必填 5 项」与「反重复执行」；`delegate_task` 描述按权限／媒体／工作区／超时四类硬约束重排并瘦身；子代理系统提示加「不逐条汇报」；`review_required` 改按角色语义值；文档 180→360 | `AgentPromptBuilder.kt`、`SubAgentTools.kt`、`SubAgentRunner.kt`、`SubAgentCoordinator.kt`、`docs/sub-agents.md` |
| 3 轮询退避门禁 | `SubAgentPollGuard`：仅约束 running，终态永久放行；1→2→4→8→16 分钟；连续违规短时摘除（60 秒后自动恢复）；`wait_ms ≤ 10000` 不变 | `model/SubAgentPollGuard.kt`、`AgentLoop.executeTool` |
| 4a 续跑累计上限 | `MAX_CONTINUATIONS = 5`、`CONTINUATION_BUDGET_MS = 3_600_000`，超限返回 `SUB_AGENT_CONTINUATION_LIMIT`（不静默截断） | `SubAgentCoordinator.kt` |
| 4b 委派门槛 | 收紧为「多方向／多文件／长时执行才强制」，并补显式排除清单（单文件小改、状态查询、一句问答） | `AgentPromptBuilder.kt`、`SubAgentTools.kt` |
| 4c 子任务卡片 | 会话内可折叠子任务卡片，默认折叠，复用既有折叠范式与 30 秒隐藏策略 | `ui/components/SubAgentCard.kt`、`AgentChatBody.kt` |
| 4d 低档提示 | 低思考档主代理开启协作时的一次性提示 + 设置页说明（三语言） | 子代理设置页、`SubAgentPreferences` |

### 10.2 与文档原稿的三处偏离（均核对源码后调整）

1. **门禁摘除改为有时限**：原稿要求对齐 `AgentDelegationArgumentRepair` 的永久失效语义。但轮询门禁若永久摘除 `get_task_result`，会永久阻断结果获取，属于功能回退。改为 60 秒冷静期后自动恢复。
2. **通知不设 `supplementStartsNewBlock`**：该标志会让助手正文另起新块，等于把子代理结果伪装成用户正文块；通知只复用注入位置，不复用该语义。
3. **脱敏必须独立于敏感工具标记**：`redactSensitiveToolData` 在 `sensitiveToolCallIds` 为空时早退，而通知的临时性与工具敏感标记无关，因此新增独立的 `redactChildNotice`，保证空集时也脱敏。

### 10.3 仍需真机验证

- 通知注入是否会额外增加模型响应轮次（可能反噬收益）——需按 §9 对照度量。
 - 轮询在真实会话中的实际频次：门禁已改为**默认关闭**，只有阶段 0 数据显示轮询确实频繁时才在设置页开启。
- 子任务卡片的视觉与折叠交互需真实界面确认。
- `delegate_task` 描述瘦身后的实际长度与 prompt 缓存收益。
