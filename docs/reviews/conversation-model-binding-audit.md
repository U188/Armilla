# 模型切换器与会话绑定审查

## 范围和验证等级

检查当前工作区的 AgentAppState、模型切换器投影与输入控件、SettingsDataStore、RuntimeConfigRepository、ProviderRepository、会话持久化、附件准备和 Runtime 配置入口。本轮仅审查并新增本报告，没有修改业务代码，没有运行 Kotlin/Robolectric 测试或编译。以下风险基于代码路径和异步时序推演，不冒充实机复现。

## 总体结论

会话确实持久化 providerId + 内部 modelId，正常发送也优先解析该绑定；并非完全没有绑定。但 UI 是“全局 selection -> 切换器 -> 当前选中会话”的双向回写结构，缺少会话所有权和版本化操作令牌。会话恢复、手动切模型、发送准备、提供商失效等路径可出现显示/绑定/实际请求不一致。

## 已核实问题

### 1. 高：异步恢复和手动选择没有固定操作所属会话，旧操作可污染新会话

证据：
- `AgentAppState.kt:1104-1130` selectModel 只接收 modelId，异步写全局 Settings，没有捕获目标 conversationId/bindingRevision。
- `:1194-1207,4190-4233` 会话切换异步恢复全局选择，未保存 Job、取消旧恢复或校验恢复操作是否仍属于当前会话。
- `:4168-4187` bindCurrentConversationModel 采用回调执行时的 selectedConversationId，而不是最初发起操作的会话。
- restoringConversationModel 只是全局 Boolean，任意一次绑定回调可消费并清零，不能对应某个目标模型或恢复请求。

可达时序：打开 A 发起恢复 MA，随即打开 B 发起恢复 MB；旧恢复或 Settings 事件迟到时，UI 仍接纳并可能把 MA 回写到 B。手动在 A 选模型后立刻切 B，也有同类风险。collectLatest 仅约束收集任务，不取消另外启动的恢复协程和已经写入的全局选择。

修复：会话模型绑定作为事实源；选择事件带 conversationId + providerId + modelId + revision/generation。过期结果忽略；后台全局选择变更不得自动重绑当前会话。

### 2. 高：模型切换/恢复未完成仍可发送，显示和实际模型可能分离

证据：
- `AgentChatInputBar.kt:193-200` canSend 检查压缩、窗口和内容，没有模型绑定就绪条件。
- `AgentAppState.send` 无 isChanging/restoringConversationModel 检查。
- `selectConversation` 立即换 homeState，modelPickerState 要等全局流；restoreConversationRuntimeModel 不设置 picker isChanging。
- 手动 selectModel 的 finally 清 isChanging 也不等于已经收到并应用目标 selection。

影响：切换后马上发送可能仍用旧绑定；会话绑定已换而切换器尚未换时，UI 显示旧模型但请求使用新会话的模型。应等待同一模型引用的配置和能力快照就绪，按钮与发送入口双重保护。

### 3. 高：原绑定不可用时静默 fallback，可能将会话发送给另一提供商

证据：
- `AgentAppState.kt:4235-4243` runtimeConfigForBoundModel 对指定绑定解析失败后调用 currentRuntimeConfig，而不是返回“绑定不可用”。
- `:4190-4217` 恢复找不到模型时，直接把当前切换器选中的模型回绑给当前会话。
- ProviderRepository.repairSelection 会为无效全局 selection 选择其他可用项。

场景：原模型被删除/停用、提供商被删除、导入会话保留了本机不存在的模型 ID。界面/发送流程可能无明确重新选择就使用别的模型或提供商。除模型效果和费用外，也影响会话数据的发送目的地。

修复：有明确旧绑定但不可用应显示“模型不可用”并阻止发送，要求用户重新绑定；只有真正未绑定的新草稿可以采用默认模型。

### 4. 高：绑定配置解析漏查提供商 isEnabled

`RuntimeConfigRepository.kt:164-170` configForProviderAndModel 检查 provider 存在和 model.isEnabled，但未检查 provider.isEnabled；与全局选择修复、切换器过滤和 setSelectedModelIdIfPresent 的规则不一致。

因此已绑定会话/后台任务在解析配置时仍可能获得已关闭提供商的地址与凭据。修复应集中到同一个可用性解析入口；不把列表不可见当作执行层撤权。

### 5. 高：附件暂存跨会话返回时没有校验发送来源，可能混用另一会话的模型和状态

证据：
- `AgentAppState.kt:1445-1477` send 捕获 A 的 conversationId/history/附件与视觉能力后异步 stageChatImages。
- 回主线程只检查 homeState.isStreaming 和压缩状态，未检查 selectedConversationId、draft/version 或模型选择是否还是发起时的值。
- `:1489-1569` startPreparedSend 使用旧 conversationId/history，却从最新 homeState 取 messages、state、reasoningEffort；后者包含当前会话的 providerId/modelId。

场景：A 点发送带图消息，图片暂存时切到 B；回调可能用 B 的模型/消息状态启动写向 A 的任务，或清掉 B 的草稿。本问题影响实际发送准备，不仅是切换器图标。

修复：不可变 PendingSendSnapshot 绑定 conversationId、draftRevision、完整模型引用/能力、消息与附件所有权；切会话后只投递给原会话，不能读取新的 homeState 拼接；或中止并保留原草稿。

### 6. 中高：模型配置与能力/请求类型分属两套来源

`AgentAppState.kt:1975-1988,2040,2085-2099`：请求 config 来自捕获的会话 state，而图片/视频生成分支、supportsVideo 和部分 token/压缩估算来自全局 modelPickerState；异步准备途中还有再次读取全局 picker 的动作。compressionContextWindow 也优先使用全局 picker 的窗口。

结果：可能选错图片/视频生成执行器、误处理附件、误判上下文大小或过早/过晚压缩。Runtime 最终媒体过滤和预算检查可以挡住一部分错误，不能让之前选错的执行分支或过滤掉的附件自动恢复。

修复：一次发送只解析一份 run model snapshot，模型、类型、模态、窗口、推理配置和提供方参数均从该快照派生。

### 7. 中：切换会话先按旧模型能力改写新会话推理偏好

`AgentAppState.kt:1195-1205` selectConversation 在目标模型尚未恢复时，用当前 currentReasoningCapabilities 调用目标 state.withCurrentReasoningCapabilities，随后 persistConversations。不同模型的可关闭性/档位不同，就可能改变新会话原本保存的 reasoningEffort；之后恢复目标模型时处理的是已被修改后的值。

备份恢复 reloadConversationsAfterBackup 存在类似提前归一化。不是每次切换都会出错，能力兼容时通常看不出；但不能保证会话推理偏好原样保留。

修复：保留用户原始偏好，目标模型就绪后计算 effectiveReasoningEffort，避免用旧能力把归一化结果永久覆盖。

### 8. 中：删除当前会话后自动选下一会话，没有恢复下一会话模型

`AgentAppState.kt:1324-1363` deleteConversation 自动选择 nextId，更新 homeState 和列表，但没有调用 restoreConversationRuntimeModel；还按旧能力归一化下一会话。

结果：下一会话内容与 providerId/modelId 已切换，切换器可能仍显示已删除会话的模型。正常配置解析可能用下一会话绑定，但视觉/生成分支仍受旧 picker 影响。

修复：所有“选中会话发生变化”的入口复用同一绑定恢复流程，包括普通点击、删除后自动选择、新草稿、导入恢复、外部归档进入。

### 9. 中：全局选择观察存在不一致快照窗口

observeRuntimeSelection 分别从 selectedProviderIdFlow 和 selectedModelIdFlow combine，再单独异步 currentRuntimeConfig 读取能力。SettingsDataStore.setSelection 在一次 edit 中写入一对字段是正确的，但两个独立 flow 的组合和额外配置读取，不保证每次 UI 应用都来自同一 selection/provider 版本；ProviderRepository 修复本身也会写全局 selection。

风险为时序相关，未运行调度测试复现，不将其描述为必然发生。修复建议使用一个 Selection(providerId, modelId, revision) 流，针对这个具体引用解析配置；将 picker、能力、绑定一次应用，并验证会话操作 generation。

## 已核实正常 / 不误报的部分

- AgentConversationStore 和 ConversationEntity 确实保存并加载 providerId/modelId，稳定串行情况下发送优先使用该绑定。
- Model.id 是 ProviderModelEntity 的全局主键，API 模型名称在 Model.modelId。不同服务商使用相同 API 名称不等于内部 ID 冲突，不能据此断言“只传 modelId 必然串提供商”。仍建议绑定使用完整引用并校验一致性。
- 普通聊天 RunRequest 带完整 ModelConfig；RuntimeRequestConfigResolver 只为语音来源替换全局配置。普通聊天进入 AgentLoop 后使用固定 config，不会仅因全局选择变化自动把已经运行的请求改成另一个模型。
- 暂停时 selectModel 会 abandonPausedRun，即停止旧任务后换模型，不是原 run 无损热切。这个行为应在 UI 解释清楚。
- 正在运行的会话仍可能因设置页全局选模型事件被回绑，显示/后续默认与正在执行的固定 config 不同；这与“正在执行的请求被热切”是两回事。
- 现有 RuntimeConfigRepositoryTest 主要检查配置构造；AgentModelPickerProjectorTest 主要检查列表过滤、选择投影和 token 展示。没有找到覆盖 AgentAppState 异步恢复、快速切会话、删除、失效绑定及附件准备时切换的回归测试。

## 修复与回归优先级

1. 固定会话级模型引用和操作 generation；不从全局监听回写会话。
2. 禁止失效绑定静默 fallback，统一检查提供商/模型可用性。
3. 发送/附件准备固定不可变快照；绑定未就绪时禁止发送。
4. 所有选中会话变化共用恢复流程；推理原始偏好与有效能力分离。
5. 回归：A/B/C 快速切换、切模型后立即发送、带图发送途中切会话、删除当前会话、提供商停用/删除、导入缺失模型、后台 run 与设置页切换并行、目标模型恢复失败/取消、重启恢复、不同推理和模态模型。

本报告为审查结果，不表示上述问题已修复。提交、推送、GitHub Actions 编译仍需用户授权。
