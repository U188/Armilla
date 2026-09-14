package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 文件路径到模型视觉输入的通用能力，不依赖任何个人数据 Provider。 */
internal object AgentFileVisionToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "read_image",
                description = "读取用户指定路径或系统相册 URI 中的一张图片或视频，并作为视觉输入提供给模型。视频会抽取一帧封面，不必先用终端转码。Linux 环境的 /workspace、/var/minis 与 minis:// 路径会自动映射到宿主文件，不必先拷到 Android 路径。同一轮最多调用一次；需要查看多张时，等待当前结果返回并观察后，再在下一轮读取下一张。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject().put(
                            "path",
                            JSONObject()
                                .put("type", "string")
                                .put("maxLength", 1_024)
                                .put("description", "任意绝对图片或视频路径、file URI 或系统相册 content URI；支持 Linux /workspace 与 /var/minis 路径；本机路径由 Root 读取，视频自动抽封面"),
                        ),
                    )
                    .put("required", JSONArray(listOf("path"))),
            ),
        )
    }
}
