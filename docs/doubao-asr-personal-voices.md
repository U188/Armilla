# 豆包 ASR 与个人音色

## 设置入口

设置 → 语音转文字：豆包 ASR API Key、识别资源、离线/云端切换，以及声音复刻管理。
ASR 与复刻各保存自己的 API Key；不会继承朗读或实时会话配置。
音频仅在用户主动录音/选择样本时上传；ASR 失败不回退其他引擎。

## ASR

使用官方 `wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async` 二进制协议，16 kHz / mono / PCM16，200ms 分包；默认 2.0 小时资源，可选择 1.0 或并发资源。
开启二遍识别和全量假设，文本替换而非追加；最终包、错误、gzip、消息边界有校验。10 秒无文字或 60 秒单次录音限制。取消时关闭麦克风和 socket。
接入输入框听写、普通语音对话；端到端实时会话仍使用其内置 ASR。

## 复刻及个人音色

使用 V3 `voice_clone` 与 `get_voice`。导入 WAV/MP3/OGG/M4A/AAC 样本，最大 10 MiB；创建自动生成 ID 的后付费音色。训练前保存本地身份，完成后保存状态、模型能力及试听链接。进程退出后可查询原任务，不会自动重训。
试听文本和正式合成均可能计费，首次正式使用可能触发音色费用；界面需用户确认才进入可选列表。临时音色 7 天后可能被服务端删除，试听链接约 1 小时有效；重新查询更新链接。

已购买的音色可通过 `BatchListMegaTTSTrainStatus` 分页拉取（Success/Active），AK/SK HMAC-SHA256 签名，仅在设置页临时使用。导入时逐个用 API Key 查询模型能力；不把列表返回等同于合成可用。
音色按 API Key 指纹隔离（同账户不同 Key 不自动合并）。朗读仅放入状态 2/4 且 model_type 4/5 的 ICL 2.0 音色，使用 seed-icl-2.0；不用于 seed-audio create 接口。V3 音色也在实时会话中提供选择，实际权限由服务端判断。无效个人音色明确失败，不静默回退默认音色。

当前样本入口为系统文件选择器；可从录音 App 选择录音。不自动购买预付费槽位，不自动迁移/重训已有音色。

## 官方依据

- https://docs.volcengine.com/docs/DoubaoVoice/bidirectional-streaming-automatic-speech-recognition-websocket?lang=zh
- https://docs.volcengine.com/docs/DoubaoVoice/LargemodelstreamingautomaticspeechrecognitionAPI?lang=zh
- https://docs.volcengine.com/docs/DoubaoVoice/SoundReplicaAPI-V3?lang=zh
- https://docs.volcengine.com/docs/DoubaoVoice/tone-query-http?lang=zh
- https://docs.volcengine.com/docs/DoubaoVoice/BatchListMegaTTSTrainStatus-PageQuerySpeakerIDStatus?lang=zh
- https://docs.volcengine.com/docs/DoubaoVoice/HTTPChunkedSSEUnidirectionalStreaming-V3?lang=zh

真实云端鉴权、计费权限、音质和录音端到端行为需要配置账户后实机验证；协议单测与编译不能替代这些验证。


## 免费/预付费槽位与后付费创建

默认选择已有/免费槽位，传控制台真实 `S_…` 作为 `speaker_id`，不发送 `custom_speaker_id`。
可以直接填写 ID，或通过 AK/SK 同步 Unknown、Training、Success、Active 四种状态的槽位。
目录数据不代表可合成；仍由 get_voice 校验训练状态和模型能力。单个查询失败会保留带错误提示的目录条目。
训练前明确提示可能覆盖原声音、消耗训练次数；重新训练清除旧试听、能力与正式使用确认，防止使用旧状态。
后付费是显式选项，只在该选项下自动生成 custom_speaker_id，不因槽位 ID 缺失或请求失败自动切换。
45000030 提示检查同项目声音复刻权限与单独的后付费音色服务；上传明确 4xx 拒绝显示“请求被拒绝”，网络/5xx 未确认不自动重训。
训练成功后的查询失败保留最近已知状态，不把查询拒绝误标成训练拒绝。

官方依据：
- https://docs.volcengine.com/docs/DoubaoVoice/Soundreplicationorderingandusageguide?lang=zh （免费音色归入预付费；后付费需单独开通）
- https://docs.volcengine.com/docs/DoubaoVoice/tone-training-http?lang=zh （V3 X-Api-Key；speaker_id 与 custom_speaker_id）
- https://docs.volcengine.com/docs/DoubaoVoice/BatchListMegaTTSTrainStatus-PageQuerySpeakerIDStatus?lang=zh （Unknown 等状态）

尚未使用真实音色执行训练，免费额度是否适用和资源授权仍需真实账户验证。
