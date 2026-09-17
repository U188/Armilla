# 摘要请求诊断

本次改动仅增加诊断，不增加模型请求，不改变分块、重试、提交或 120 秒总时限。

## 关联字段

- `group`：一次压缩操作，覆盖各分块、合并和格式修复。
- `request`：一次 completeCompression 调用。思考档回退与输出上限重试共享此 ID 和原来的总时限。
- `phase`：`chunk_N_of_M`、`merge`，格式修复带 `/repair`。
- `attempt`：本次调用内的尝试编号；不同尝试的事件统计隔离。
- `remaining_ms`：该次尝试开始时剩余总预算，不是重新开始的 120 秒。
- `endpoint` / `streaming_text`：适配器声明的接口与流式能力，不输出服务商地址或凭据。

## 时间与计数

使用单调时钟，记录响应头、首段非空正文、最近 ProviderEvent 和最近正文事件的时间。
`text_delta_chars` / `thinking_delta_chars` 是收到的增量 UTF-16 长度总和，不是 token 数、
最终摘要长度或网络字节数。适配器没有上报的事件标为 `unknown`，不能据此断言没有网络数据。
ProviderEvent 不包含全部 SSE 心跳或底层 socket 活动；`last_event_ago_ms` 不是网络空闲超时。

响应头、首段正文即时记录；等待期间至多每 15 秒一条进度。成功、异常和 watchdog
终止均有快照。失败日志先于取消检查，避免底层异常被本地超时替换后丢失诊断。

`reason=total_deadline` 表示本地总时限；`cancelled` 表示取消；
`transport_timeout_or_interrupted_io` 表示直接收到 InterruptedIOException，不能等同于服务端超时。
其他为 `provider_or_validation_failure`，配合安全错误代码和 observed_stage 判断。
观察到正文不意味着正在持续传输；要结合最后事件/正文距今时间判断。

`摘要响应` 仅表示 complete 返回，不等于摘要校验通过或已提交；最终以外层
`运行中压缩已提交` 与检查点 committed 状态为准。

## 隐私与开销

不记录提示词、摘要正文、思考内容、工具参数、HTTP 头内容、URL、API Key 或异常原始消息。
每次尝试仅保存时间戳、计数与有限状态；复用现有 watchdog，不增加线程。
日志异常用 runCatching 隔离，不能改变摘要结果。

## 验证

新增 SummaryRequestDiagnosticsTest 的确定性时钟、并发计数、限频、事件缺失及内容不泄漏测试。
仍需 GitHub Actions 运行单元测试与 Release 编译；当前文档不代表云端或实机已验证。
