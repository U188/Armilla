# 流式长会话渲染优化

问题：流式更新与手动滚动同时进行时，历史相关计算仍占用主线程。设备现有 StreamDiag 显示会话已约 3,900 条消息；此日志不是受控性能对照实验。

## 本次改动

- `visibleTurnSpeechPrefaces` 一次扫描生成最终回答的朗读前文，替代每个最终回答从历史开头扫描。4,000 条消息、1,000 轮测试中，访问次数从 2,002,000 减到 4,000；保留补充消息、轮次边界和隐藏思考的原有语义。这是该计算的工作量变化，不代表整体帧率提升 500 倍。
- LazyColumn 用批量 items、稳定 key 和 contentType 声明时间线。原先也懒组合内容，但每次列表更新仍 forEach 注册所有 item，且提前扫描所有工作过程；现在工作过程状态在相应条目进入组合时初始化。
- 逐字淡入 saveLayer 从整段范围缩到当前字形路径边界。保留字符显现、双向文字路径和跟底行为，不通过滑动时暂停输出降低负载。
- 新增 timeline.prefaces / timeline.project 计时，便于在软件日志中区分历史投影与 Markdown 解析。

## RikkaHub 对照

本地 `/workspace/rikkahub-source` 中 MarkdownBlock 使用后台 mapLatest 解析，ChatList 使用 itemsIndexed 和稳定消息 key。浑天 已有后台解析、合并与解析会话，区别不只是解析线程；浑天 还维护工具过程、逐字显现和自动跟底。源码对照不能证明所有设备和会话都同样流畅。

## 验证边界

单元测试验证输出相同与遍历次数，CI 构建验证编译；尚未完成相同设备、相同长文本与手势的新版/旧版帧耗时对照。真实 120Hz 滚动体验仍需安装包后的受控测试。


## 长回答绘制与用户手势接管

设备安装哈希确认仍为 8a29a85。StreamDiag 会话 9bc2f53a 在仅 6 条消息、回答长度约 5,270 字符 / 布局高度 25,300 px 的区间，frame.draw 均值约 5.5 ms、frame.total 均值约 18 ms，历史投影仅几十微秒。表明短会话也存在长文绘制压力；聚合日志不足以证明所有耗时均来自某一个组件。

- 顶层 Markdown 块使用 graphicsLayer 隔离 RenderNode display list，尾部 invalidateDraw 不必重录所有稳定段落。未强制离屏纹理、未冻结流式输入。
- 添加 markdown.blockDraw 计时观察段落重录次数与耗时。
- NestedScrollConnection 在 UserInput 的非零垂直滚动到来时同步标记用户接管，返回 Offset.Zero，不消耗手势；跟底在等待帧后和获取滚动 mutation 后都检查最新接管状态。
- 手势接管结束仍使用既有拖动/惯性状态及跟底策略，不新增自动回底。
- 单元测试/构建不验证 RenderNode 在真实 GPU 上的收益；本次仍待同机同负载滑动对照，不能据此宣称掉帧已根治。
