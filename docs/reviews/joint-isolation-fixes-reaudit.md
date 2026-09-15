# 联合修复复审（源码）

审查对象是当前未提交工作区，基线仍为 `994ad3a`。只读核对实现与原审查项，未编译、未跑测试、未改业务代码。下面的“确认”指源码路径存在，不表示实机已复现。

## 结论

原审查里的主泄漏路径大部分已经按 fail-closed 改掉：任务不再在运行中改绑到全局助手，Skills 不再共用一个可写 `.visible`，会话模型不再按相同 `modelId` 跨提供商回退。

还不能宣称“助手/Skills 完全隔离”或这批改动可以发版。热路径引入了新的正确性风险（空索引误撤权、锁顺序），有几处身份仍回落到 `active()`，测试与生产行为也不完全一致。

## 已真正闭合的主路径

1. Runtime 用 `request.assistantId` / `config.assistantId` 取助手；缺失身份不回落 `active()`。记忆读写、安装归属、权限检查走固定 ID。
2. 记忆 UI / 助手编辑保存带 `assistantId + revision`；过期草稿不能无条件 `replaceAll`。删除写 tombstone，旧 Store 句柄不能重建记忆。
3. 助手 ID 改为严格校验，不再有损替换。备份在应用前校验 profile / memory key。
4. Agent run 使用独立 `.runs/<uuid>/skills` 快照；私有 data 在 `.assistant/<id>/.data/<skill>`；Linux 按技能挂载 data，而不是整个 data 根。
5. `skills_read` / resource 去掉全局 `findInstalledSkill` 回退。停用会从该助手的 run 快照里删代码并打断该执行器的终端 / 自建 daemon。
6. 模型选择传递 `providerId + modelId`；projector 不再跨提供商找同 ID；绑定失效时 `selectedModel == null`，发送被拦截。附件准备记录会话 / 草稿 / generation / 助手，变化则取消发送。

## 高：热路径可能把暂时读失败当成全面撤权

`AgentLocalTools.liveSkillEntries()` 在生产路径（`runSkillsRoot != null`）每次都会：

- `AssistantRepository.currentProfile`（跨进程文件锁 + 整份 index 反序列化）
- `listSkillsForManagement(forceRefresh = true)`（持有 SkillMutationLock，清缓存并重新 seed 内置技能）

然后用 `entries.size != runSkillEntries.size` 决定 `interruptAll`、`stopOwnedDaemons`、`pruneRunSkills`。

`installed` 在 `skillIndexService == null` 或列表为空时是 `emptySet()`。索引抖动、seed 失败、锁超时或异常被上层吞掉后变成空列表，会被当成“本轮技能全部卸载”，关掉终端和 daemon，并删掉快照代码。这比原来的 400ms 缓存回退更危险。应只允许“明确仍启用且快照文件还在”的子集继续用；读失败应保持原快照并报错，不能等价于空授权。

`liveSkillCache` 还在字段里，但新实现已经不用它。

## 高：SkillMutationLock 与助手索引锁顺序相反

安装成功回调是：已持有 `SkillMutationLock` → `AssistantRepository.enableSkills` → `withIndexLock`。

`liveSkillEntries` / 每轮 `skillContextProvider` 是：`withIndexLock`（`currentProfile`）→ `listSkillsForManagement` → `SkillMutationLock`。

两个 Agent run 并发（一个在 install，一个在 list/read 或下一轮拼提示词），或 UI `select/update` 与安装重叠，存在死锁窗口。UI 自己的 `update` 是助手锁再技能锁，和安装路径相反。这不是原审查里的功能项，是这次修复引入的。

## 高：压缩与部分配置构造仍用当前助手

`RuntimeConfigRepository.configForProviderAndModel` 仍 `buildRuntimeConfig(provider, model)`，默认 `AssistantRepository.active()`。后台压缩、手动压缩 fallback、绑定配置读取会带上**此刻全局助手**的 `systemPrompt` / `assistantId`，而不是发起该会话任务的助手。聊天主发送路径已经显式传入 `runAssistant`，所以这是压缩 / 配置侧残留，不是发送主路径回退。

## 中高：记忆页在切助手后仍停在旧草稿

`selectAssistant` 只 `select + sync + refreshRequestOverhead`，不增加 `memoryEditGeneration`，也不 `refreshMemory()`。保存会被“草稿属于另一助手”挡住，这点是对的；但开关仍可能改到**草稿所属旧助手**，界面也继续显示旧内容。用户切到 B 后看到的是 A 的记忆，容易误操作。

## 中：测试和生产对“本轮变更”的可见性不一致

无 `runSkillsRoot` 的测试会从 `currentSkillEntries` 里滤掉 `mutatedSkillIds`，所以安装/覆盖后 `skills_read` 得到 `NEXT_TURN_REQUIRED`。生产有 `runSkillsRoot` 时不过滤，同 ID 更新仍可读**本轮旧快照**。这更接近“本 run 批准版本保持不变”，但现有 `AgentLocalSkillInstallIntegrationTest` 按旧语义断言，未跑测试前不能假设它们会过。

## 中：其它残留与回归点

- `SkillRuntime.visibleSkillsDirectory()` 仍用 `AssistantRepository.active()`。用户终端 / 默认 daemon 挂载的是当前助手，不是某个 run。文档写过，但和 Agent 快照不是同一套根。
- `AssistantRepository.create()` 不发布技能视图。新建助手后、第一次 `select/update` 前，用户终端可能挂到空目录；Agent run 不受影响，因为它走 `createRunSkills`。
- 删除助手仍不取消所属 run。下一次工具 / 下一轮 `currentProfile == null` 会失败；已经发出的模型请求和已启动的进程不会被这次删除撤回。
- `observeRuntimeSelection` 在 providers 内容变化时增加 `modelBindingGeneration`，附件准备会被取消。偏保守，但余额/模型列表刷新可能导致“没切会话却发送失败”。
- `AgentMemoryStore.replaceAll(content)` 无 revision 仍在，仓库默认 `revision = null` 时走这条。UI 已不用；导入/复制仍用。不要从别的调用点重新接回 UI。
- 旧索引若含 `.` / `..` 等以前合法、现在非法的 ID，`readIndex`/`validateProfiles` 会让 `init` 直接失败。这是严格校验的代价，需要迁移或更明确的启动错误，而不是静默改写 ID。
- 会话历史仍无助手归属字段。同一会话切助手不会清历史。这点与原审查一致，尚未做。
- `enabledFlow()` 仍是全局 `SettingsDataStore.memoryEnabledFlow()`，和按助手的 `memoryEnabled` 不是同一来源。

## 测试覆盖（均未执行）

新增用例覆盖了：CAS 冲突、跨 Store 锁、删除 tombstone、ID 校验、双助手 data 分离、更新不改旧快照、停用保留 data、空快照不扩容、释放不跟随 data 指针、模型不跨提供商回退、IPC 身份缺省为空。

没有覆盖：并发 run 的锁顺序、索引为空时的撤权、压缩配置的助手身份、切助手后的记忆 UI、`forceRefresh` 热路径、真实 Linux/PRoot 挂载、删除助手与在途 run、附件准备被 providersFlow 取消。

词法括号检查和 `git diff --check` 不能代替这些。

## 复审后已处理（仍未编译）

1. 热路径撤权：`SkillRunAuthorization` 把“读失败”和“确认撤权”分开。索引/助手资料读失败时保持上一份快照；只有助手已删除，或停用/卸载查询成功，才缩小集合并关闭终端。不再 `forceRefresh`。新增 7 个决策测试。
2. 锁顺序：`AssistantRepository.update/select/delete/create/import/saveAvatar` 先提交索引再发布技能目录，不再在助手索引锁内拿 `SkillMutationLock`。`currentProfile` 使用锁内已刷新的内存快照，避免重复读盘。
3. 压缩配置：`buildRuntimeConfig` / `configForProviderAndModel` 不再默认 `active()`。自定义摘要模型只复用 fallback 上已固定的 `assistantId` 与 systemPrompt；绑定读取与后台压缩在没有会话助手时传 null，不借用当前助手。
4. 切助手：`selectAssistant` 会 `refreshMemory()`。若旧助手有未保存草稿，提示未写入，编辑器改为新助手内容。
