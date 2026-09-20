# 实时通话文字流式诊断

客户端按官方 Web 示例读取输出事件的 `text / delta / transcript / content`，只接受非空字符串，不 trim，保留独立空格/换行片段。delta 追加，done 全文替换；空 done 不清空已有输出。ASR 假设仍采用替换语义。所有日志复用 `VoiceDiag id=...` 会话关联，并进入软件现有日志导出/清理路径。

## 诊断阶段

- `rx.response.output_text.delta/done`：WebSocket 接收事件，记录各候选字段字符数，不记录值。
- `text.delta/done`：解析字段编号（0 无有效字段、1 text、2 delta、3 transcript、4 content）、片段长度、累计长度、事件排队时间。
- `text.publish`：发布状态的字符数及回调耗时 `callbackUs`。这不是实际屏幕绘制耗时。
- `text.controller`：控制器是否接纳状态、文字是否改变；排查旧会话状态被丢弃。
- `text.summary`：本地轮次编号、delta 事件/字符总数、最终字符数、首片段时间、流持续时间、最大片段间隔及最大排队时间。`doneOnly=1` 表示到 done 才取得非空正文。

高频阶段限频，但计数保留；每次 done 输出轮次汇总。首片段时间相对本地轮次重置，不表示模型推理时延；流持续时间是在客户端消费到首尾片段之间的时间。

若接收日志的 `textChars` 非零，而旧版界面只在 done 更新，符合只读取 delta 字段的缺陷；新版各字段都能逐片段发布。若没有 delta 接收事件而只有 done，则仍需调查上游返回/网络；若 `maxQueuedMs` 或音频 `audio.enqueue blockedMs` 偏高，需调查事件队列和音频背压。不能以代码修正或单测代替真实通话复测。

不记录正文、API Key、远端 response_id 或音频数据。不人为逐字播放完整回复来伪装网络流式。
