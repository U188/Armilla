# 主代理与子代理

设置 → 模型功能 → 子代理，分别配置实现模型和审查／总结模型。
原来的槽位 1、2 保留模型引用，分别对应两个职责；允许使用同一模型，也允许与主代理相同。
当前聊天模型负责调度和最终验收。长按模型选择器打开本会话协作开关，默认开启，下一次运行生效。

## 委派与角色

delegate_task 的 role 为 research（默认）、implementation、review 或 summary。
implementation 自动选实现槽位，review/summary 自动选审查槽位；指定 worker 也必须匹配职责。
未配置的职责返回 ROLE_NOT_CONFIGURED，不静默换模型。
get_task_result 返回状态、结果、角色、project、workspace_id 和 workspace_path。
最多两个并行任务，每轮最多 16 项，每项最长 180 秒。cancel_task 或父任务结束会取消子任务。
原始委派上下文和结果按敏感工具内容处理，不进入持久会话。

## 一项目一目录

项目任务必须明确指定 /workspace/<项目名>，不默认 Eta，不允许使用 /workspace 或 mounts。
项目需已初始化 Git 且至少有一个提交，创建实现工作树前必须没有未提交源码改动。
换项目传新 project，旧项目的工作区 ID 不能在新项目使用。

.agent/worktrees/<ID> 存独立 detached worktree；.agent/results/<ID>.json 仅记录基线、
状态和交付提交，不记录密钥或任务正文。不创建或推送临时分支。.agent 应加入 Git 忽略规则。
使用设置中所选 Linux，需要 Python 和 Git；不可用时返回错误，不改走 Android Shell。

1. implementation 传 project，创建独立工作树。子代理仅通过 workspace_file 读写源文件。
2. 完成后运行时提交并冻结，主代理获得工作树路径及 ID，在该路径执行构建／测试。
3. review 传同一 project 和 workspace_id，只读检查固定提交。
4. 主代理读取审查结论、核对 diff 和测试后，明确调用 manage_agent_workspace 的 merge。
   reviewed 仅代表审查流程完成，不代表审查无问题。快进合入要求主仓库干净且仍在基线，交付未变。
5. 合入后删除工作树；失败或取消保留现场，list/inspect 可查询，明确放弃才 discard。

## 权限与恢复

workspace_file 提供 list_files/read/diff/write/delete，运行时绑定项目和 ID，模型不能覆盖归属。
相对路径拒绝 ..、Git 元数据、.agent、符号链接、硬链接和特殊文件。文件上限 64 KiB、输出分页。
审查角色只能读取。子代理不能运行任意 Shell、操作手机、发送消息或创建子代理。
主代理仍有原有权限；目录隔离不是主代理 Root Shell 的系统沙箱。

跨进程项目锁串行化文件操作、冻结、合入和回收。审查期间不可合入或删除。
遗留任务租期到期后 inspect 标记待处理并保留现场。最多 8 个未回收工作树，每任务累计写入预算 16 MiB，创建前检查至少 512 MiB 剩余空间；不自动删除未合入改动。
重启不恢复模型循环，但可在原项目继续查看和处理交付物。
当前不支持自动初始化无 Git 项目、自动 rebase 或子代理任意命令执行。

## 验证

Python 集成测试覆盖真实 Git 隔离写入、冻结、审查、快进合入、回收，跨项目归属、
越界/符号/硬链接、脏主仓库、基线变化、失败保留、过期恢复和分页。
Kotlin 测试覆盖职责路由、角色缺失、工作区参数绑定、只读执行限制与取消。
设置图标使用人物（助手）与眼睛（辅助视觉），与标题/推理功能区分。
