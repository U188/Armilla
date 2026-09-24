# TTS 第一版实现与验收清单

## 范围与状态

工作区 `/workspace/eta-turn-fix`，分支 `feat/tts`，基于 `ee235a9`。
本轮只修改源码与测试；未提交、推送、触发 CI 或安装。版本保持 5.3.0 / 2026091802。
`/workspace/浑仪` 的未提交内容未混入。

## 用户行为

- 通用设置新增“朗读”，默认系统模式，不自动播放。
- 回复正文完成并出现结果操作栏后，提供播放/停止键；不会朗读思考卡片、工具日志、用户消息与会话引用协议块。
- 系统模式只选择引擎声明为本地且已安装的音色；缺失时提示，不自行下载或悄悄联网。
- 云端模式需显式选择提供商/模型和填写音色 ID，仅使用 OpenAI 兼容 `audio/speech` 的 MP3 响应。模型目录不是接口兼容性证明。
- 云端模式会发送清理后的正文，界面明确提示费用、数据发送与合成语音属性。
- 不自动跨服务回退或重播；可以在设置里切回系统模式。
- 手动朗读完成的回复，按句/长度分段；云端最多预取下一段。不是边生成正文边朗读，也不是 MP3 字节流即时播放。

## 修复的基础设计

- 替换阻塞 CountDownLatch：系统引擎异步初始化，每次会话独立引擎，UUID utterance ID，逐句等待完成；取消立即结束等待并 stop/shutdown。
- 单一控制器 + epoch + Mutex：过期回调不更新新播放状态，新会话等上一会话清理后才启动。
- HTTP 使用 enqueue + 协程取消到 Call.cancel；总请求期限 90 秒、单段 8 MiB 上限，流式读取同样受大小限制。
- 检查 MIME 和 MP3 标记；拒绝 HTML/JSON 错误页；原始错误正文和平台异常不直接展示（可能含密钥/正文）。
- 播放器异步准备、超时保护、finally 释放；私有缓存文件播放完/取消即删，下次启动清理崩溃遗留音频。
- 获取/释放音频焦点；失焦、拔耳机、切会话/页面、后台、停止当前任务时停播。
- 开始圆形按钮听写时先取消朗读，并等待音频资源释放，再开麦克风；听写期间拒绝启动朗读。
- 停止声音不改 Agent run/turn/history，也不触发模型重试。
- 使用现有 JetBrains GFM AST 提取正文，不再全局删除下划线等符号；分段按 Unicode 码点，不切坏 emoji；无效分段参数直接拒绝。
- 专用 TTS 型号从聊天/摘要选择器排除，ProviderClientFactory 增加直接调用保护；音频输出模态本身不用于断定 Speech 接口兼容性。
- AndroidManifest 补充 TTS service queries；补齐默认英文和简繁中文界面资源。

## 自动化验证

新增 25 项测试（尚未执行）：

- `SpeechSpeakableTextTest`：空输入/代码块、链接/图片、字面下划线、参数边界、Unicode 分段、顺序、长输入、HTML。
- `SpeechPlaybackEpochTest`：过期回调、停止取消身份、多次停止。
- `CloudSpeechSynthesizerTest`：请求字段隔离、明确音色、错误网页、HTTP 错误不泄漏、大小上限、真正的 Call 取消、MP3/MIME、URL。
- `SpeechModelIsolationTest`：聊天与语音选择器隔离、直接 Agent 调用拒绝。
- `SpeechSynthesisModelsTest`：专用模型、通用 audio 模型不误判、OAuth/Anthropic 排除。

已执行：git diff --check、各语言资源 XML 解析/重复键/TTS 引用检查、Manifest XML 解析。
未执行：Kotlin 编译、单元测试、Release 打包和实机音频测试。静态检查不等同于编译通过。

## 必须进行的真机验收

1. 默认系统模式，已安装本地中文音色可播放；无本地音色时错误清晰，无隐式云请求。
2. 试听、气泡播放、加载中停止、播放中停止、连续点击不同回复，无串台/残留。
3. 初始化中停止、超时后重试，旧回调不结束新的播放。
4. 切会话/后台/拔耳机/失焦及时停播；开启语音输入先停止声音。
5. 已有半段音频播完，下一段云请求失败时不从头读、不自动换服务。
6. 云端 401/429/5xx、HTTP 200 HTML/JSON、超大音频均能退出加载态，且无密钥/正文泄漏。
7. 中英混合、链接、表格、代码、emoji 的实际发音；检查分句之间间隙与音色一致性。
8. 停止朗读不结束任务，停止当前 Agent 任务可以取消当前朗读；旧会话/轮次不受影响。
9. 本地 TTS 无串音的实际效果、旧版本语音输入按钮位置与布局回归。

## 核对过的官方文档

- Android TextToSpeech API：异步初始化、stop/shutdown、manifest queries、voice。
- OpenAI Text-to-speech guide：Speech 的 model/input/voice、MP3 格式和合成语音披露。
- Markdown 依赖沿用项目已有 JetBrains GFM parser，无新增运行库。
