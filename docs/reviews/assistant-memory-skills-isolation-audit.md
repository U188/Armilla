# 助手记忆与 Skills 隔离 / 中途变更审查

## 范围与验证等级

审查当前未提交工作区源码，包括 Runtime、工具执行、助手编辑 UI、记忆存储、Skills 发布目录、Linux 挂载和现有测试。未读取用户真实 MEMORY.md、技能 data 或聊天内容。此轮只新增审查文档，不修改业务代码；未编译、未执行 Kotlin/Robolectric 测试。

下面的“确认”指源码存在对应路径，不表示已在设备上复现泄漏。不能将文件层面的隔离等同于带 Root/Shell 的安全沙箱。

## 结论

普通显式 assistantId 的记忆文件是分开的；工具写入也有 revision 冲突检查。但运行时使用的助手身份不统一，Skills 的全局可见目录和热更新权限存在确定的设计缺口。当前不能保证“助手之间完全隔离”或“中途修改都安全”。

### 1. 高：运行中记忆注入 / 开关判定与读写目标不是同一个助手

证据：
- `agent/runtime/AgentRuntimeRunExecutor.kt:96,154,194,220-245`：启动时捕获 assistant，把 `assistant.id` 传给 memoryAssistantId；后续 memoryToolsEnabled、skillContextProvider、memoryContextProvider 却调用 `AssistantRepository.active()`。
- `agent/tool/AgentLocalTools.kt:299-345`：权限由动态回调判定，而 read/mutate 使用固定 memoryAssistantId。
- `agent/runtime/AgentRuntimeWire.kt:165-173`：RunRequest 没有 assistantId；任务准备排队期间也可能错配请求里已固化的人格提示词与后来选中的记忆。
- `ui/app/AgentAppState.kt:2715`：聊天页切换只拦截当前选中会话的未暂停运行；不是全局所有任务的身份绑定。
- `ui/screens/assistants/AssistantsScreen.kt:97`：另有直接 select 的路径，Repository 本身没有 Runtime 互斥。

场景：A 任务仍运行时全局切到 B，下一请求可注入 B 记忆，但 memory_get/write 仍操作 A；甚至 A 关闭记忆后，可因 B 的 memoryEnabled=true 而继续通过权限检查。revision 只校验内容，不校验助手身份；不能用它代替隔离。

修复原则：入口捕获并持久化 assistantId，整条请求/恢复/工具链显式传递；只刷新该 ID 的资料。助手被删除应 fail-closed，不回落到 active()。

### 2. 高：Skills 共用 .visible，技能 data 会残留、串助手或被删除

证据：
- `agent/skill/SkillRuntime.kt:660-687`：所有助手发布到同一个 `skills/.visible`；清理只按 skill ID。
- `agent/skill/SkillRuntime.kt:771-784`：同步遇到 data 只 mkdir，既不复制该助手的数据，也不切换 data 指向。
- `agent/terminal/ProotCommandBuilder.kt:38`、`ShellProcessSupervisor.kt:303-316`：Linux /var/minis/skills 挂载同一个全局目录；旧终端不绑定所属助手。
- `agent/skill/SkillRuntime.kt:692-704`：关闭技能直接删除 `.assistant/<assistant>/<skill>`，没有把停用与删除 data 分开。

场景：A/B 都启用 alpha，A 的终端在 `.visible/alpha/data` 写记录，切 B 后 alpha 目录因 ID 相同保留；sync 跳过 data，B 能继续看到 A 留下的文件。若新助手不启用 alpha，该目录直接删掉，A 的数据也可能丢失。代码中没有把 .visible/data 写回所属助手的路径。

修复原则：包代码与助手私有可写 data 分离；终端 / run 绑定助手专属根，不原地复用全局可写目录；停用隐藏能力但保留 data。跨助手共享必须是显式选项。

### 3. 高：本轮关闭 Skill 后仍能通过专用工具读取

证据：
- `agent/tool/AgentLocalTools.kt:947-995`：resolveRunSkill 失败仍回退 indexService.findInstalledSkill（全局已安装包）。
- `agent/tool/AgentLocalTools.kt:1248-1262`：isVisibleInCurrentRun 在初始快照非空时只查快照，不与当前启用集合求交集。
- 同文件 `liveSkillEntries` 还有 400ms 缓存；但本问题不是只有 400ms：全局回退 + 初始白名单可持续允许读取。

场景：任务开始时 alpha 已启用；中途关闭后最新提示词和 skills_list 可以不再展示 alpha，但 skills_read(alpha) / skills_read_resource(alpha, ...) 仍可找到全局包并通过初始快照检查。

修复原则：执行时必须同时满足固定助手当前授权与本 run 已批准版本。移除全局回退，不以本轮启动快照作为撤权后的授权。

### 4. 高：UI 记忆保存没有 revision，旧草稿可覆盖助手新写入

证据：
- `data/repository/AgentMemoryRepository.kt:85-101`：mutate 检查 revision；replaceAll 无条件写入。
- `ui/screens/assistants/AssistantEditScreen.kt:88,139`：加载只保存 content，保存调用 replaceAll(memoryDraft, assistantId)。
- `ui/app/AgentAppState.kt:539-650`：通用记忆页 snapshot/save/clear 使用默认 active ID；异步任务未固定草稿所属 assistantId，也没有 revision。

场景：用户打开记忆编辑页，模型随后 memory_write 追加内容，用户保存旧草稿会覆盖这次追加且不报冲突；通用记忆页还存在助手切换后将旧助手草稿写入新助手的风险。

修复原则：编辑状态带 assistantId、baseRevision；UI 保存也用 CAS，冲突提示比较/合并，不盲目重试整文件覆盖。回调更新 UI 前校验仍是同一编辑对象。

### 5. 中高：新增、安装和更新 Skill 的生效时机不一致

证据：
- Runtime 每个模型请求都动态注入当前 enabled skills 并重新 publish。
- AgentLocalTools.isVisibleInCurrentRun：初始集合非空时新增 ID 不可用；初始集合为空时却退到 live 集合。
- AgentLocalTools.installResult:1287 调用 AssistantRepository.enableSkills，修改完成时全局 active 助手，而非启动任务所属助手。
- installResult 返回 next_turn，但 enableSkills -> update -> publishVisibleSkills 会立即发布到终端目录；下一模型请求的提示词也没有过滤本轮 mutatedSkillIds。
- 同 ID 更新没有 run 级内容 hash 绑定；.visible 同步覆盖文件。

后果：可能出现“提示词说已启用，skills_read 却拒绝”；也可能“工具说下轮才可用，当前终端已经可读/执行”。安装进行中切换助手，还可能把新技能启用给错误助手。

修复原则：一个版本化的能力快照同时供提示词、list/read/resource 和终端使用。建议新增/升级下一个用户 run 生效；撤权执行时立即拦截。不要把一次工具后模型请求与下一用户轮混用。

### 6. 中高：Skills 发布复制不是事务，更新和撤权可能遇到半更新文件

证据：
- `SkillRuntime.publishVisibleSkills/bindSkillsToAssistant/syncSkillPackage/copySkillTree` 未使用安装与读取已有的 SkillMutationLock。
- 逐文件 overwrite，复制错误 runCatching 吞掉；不会清理源包已移除的普通文件。
- AssistantRepository.update 在配置 publish 后才 refresh，并吞掉 refresh 异常。

后果：启用状态和目录可能不一致，脚本运行中被覆盖，旧文件残留。SkillLoader/ResourceReader 有边界和锁，并不能保护未加入同一锁的目录复制器；已经运行的脚本也不由文件删除自动撤销。

修复原则：受校验版本目录 + 暂存完整发布 + 原子指针/绑定；失败保留旧版本并明确报告。数据目录独立，不能随代码同步删除。

### 7. 中高：删除助手与正在运行的任务没有闭环

证据：AssistantRepository.delete 删除记忆和技能目录但不取消/等待该助手的 run；AgentMemoryRepository.storeFor 未检查 profile 是否存在，旧工具仍持有 memoryAssistantId；isEnabled/setEnabled 对缺失 ID 回落 active。

后果：旧任务可能重新创建已删除的记忆目录，或使用新助手开关判断旧助手工具权限。记忆锁是 store 实例锁，delete 与持有旧实例的读写不在同一事务。

修复原则：助手生命周期锁/删除标记，阻止新操作，取消或收束所属 run，再删除并拒绝后续重建。

### 8. 条件性高风险：导入助手 ID 的路径校验和规范化碰撞

证据：`data/model/Assistant.kt:54-60` 只替换字符并截长，保留 `.`/`..`，不同输入也可归一到同名；`EtaBackupRepository.kt:608-613` 对 profiles ID 只查非空与原值重复。该值用于 memory 与 `.assistant` 目录，部分路径有递归删除。

正常新建 UUID 不触发；外部导入/手工编辑索引可能让不同助手落入相同目录或不再位于预期的助手子目录。应在导入与 repository 入口校验合法 ID、规范化后唯一性以及 canonical containment；不依赖有损替换生成隔离键。未制作或导入恶意备份验证。

## 已核实正常与边界

- 显式 assistantId 的普通记忆存储分目录；已有 AgentMemoryIsolationTest 只验证了这一静态场景，不能证明热切换安全。
- 同一助手、没有并发切换时，工具 memory_write 使用当前 revision，有冲突会返回 MEMORY_CONFLICT；新记忆会在下一次模型请求重新注入，不会修改已经发送到服务商的请求。
- memory/skills 关闭时 PromptBuilder 都返回占位 system 消息，因此仅切换这两个开关不会改变 system 消息条数；本次没有把“固定 systemCount 覆盖历史”误判为它们的必然问题。
- 关闭记忆或技能不可能撤回已经发出的请求；旧工具结果、模型回答或摘要中已经包含的资料也不会因开关自动消失。skills_read 正文会进入普通会话历史；memory_get 原始结果有既有敏感工具脱敏策略，但模型自己写出的答案/摘要另当别论。
- ConversationEntity 与 RunRequest 没有助手归属字段，同一会话切换助手不会自动清空历史。若要求会话也隔离，需要单独设计，不能仅靠记忆目录实现。
- Root/任意 Shell 不是助手级安全沙箱：即使专用工具修好，仍需明确终端可读范围的实际授权边界。

## 推荐修复 / 回归顺序

1. run + 编辑草稿固定 assistantId；权限、记忆注入/读写、安装归属统一，不回落 active。
2. Skills 私有 data 与终端绑定修复；关闭技能立即撤销新的读取，新增和更新下一 run 生效。
3. UI revision CAS；删除生命周期；目录原子发布和内容版本。
4. 回归：A/B 同时任务、排队时切换、记忆 UI/工具冲突、技能关闭后 list/read/resource/terminal 一致性、初始技能为空/非空、中途安装更新、停用再启用保留 data、删除助手、导入不合法 ID。
5. 编译和运行测试继续遵守用户的 GitHub Actions 授权约定；此报告不宣称任何测试已经运行或问题已经修复。
