# 生图参数：按接口配置，不按模型名称

直接选择生图模型与子代理都通过 `AgentImageGenerationClient.generate` 合并参数：
配置默认值 < 本次描述中的明确参数 < 子代理 `image_options`。

## 本次请求

直接聊天支持明确独立的参数分句，例如：

```text
画一只猫，9:16，2k
```

复杂描述请使用独立一行 JSON，避免时间、例子和否定句被误识别：

```text
画一只猫
image_options: {"aspect_ratio":"9:16","resolution":"2k"}
```

这里的代码块仅用于文档展示；实际发送时不包括反引号。结构化行会从图像提示词中移除。
不是任意自然语言理解器：叙述中的数字不会被自动提取。明确参数冲突会本地拒绝，不能保证理解每种“不要”“例如”表达。
子代理应直接使用 `delegate_task.image_options`，不要仅把尺寸写在任务说明里。

## 默认协议

使用 `/images/generations` JSON 接口，通用字段 `aspect_ratio`、`resolution`、`size`、`n`、`quality`、`response_format` 原样发送，不因模型名称陌生而拦截。不保证对方接口支持所有字段，服务端拒绝时原样报告错误。未知额外请求字段保留。

## 显式字段映射

在提供商/模型的额外请求体或自定义请求体中配置保留对象 `eta_image_config`；不新增模型名单，不硬猜兼容规则。示例：

```json
{
  "eta_image_config": {
    "protocol": "passthrough",
    "fields": {
      "aspect_ratio": "generationConfig.imageConfig.aspectRatio",
      "resolution": "generationConfig.imageConfig.imageSize"
    }
  }
}
```

这只映射请求体字段，**不代表已适配某厂商原生接口的 URL、鉴权和响应格式**。只能用于支持这些字段的当前兼容 images 接口。

若接口只支持精确 `size`，由配置提供精确映射，不把 9:16 偷换为 2:3：

```json
{
  "eta_image_config": {
    "protocol": "size",
    "sizes": {
      "9:16": "864x1536",
      "9:16@2k": "1152x2048"
    }
  }
}
```

尺寸是否被服务端接受由接口契约决定；未提供对应映射则请求前报错。

编辑请求默认 multipart；要求 JSON `image: {type: image_url, url: data:...}` 的接口需设置
`"eta_image_config": {"edit_protocol": "json_image_url"}`，仅支持一张参考图。
此前按 Grok 模型名自动选择编辑协议的行为移除，原有 Grok 编辑配置需明确选择该协议。

## 结果与保护

内部 `eta_image_config` 在请求前移除；不在错误或结果中输出提示词、认证信息或任意额外字段。
成功结果包含发送的生图参数白名单摘要，以及解码得到的实际宽高、比例/尺寸不符提示。
明确参数不会为兼容自动删掉或改成默认方图；不会因失败重发可能计费的请求，不自动裁剪缩放。
分辨率档位是接口定义的概念，实际宽高检查不能证明服务商内部是否采用某个档位。
