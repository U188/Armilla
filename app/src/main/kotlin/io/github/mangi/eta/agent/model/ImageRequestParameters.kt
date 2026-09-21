package io.github.mangi.eta.agent.model

import org.json.JSONObject

/** Shared explicit JSON directives and standalone shape clauses; arbitrary prose is not mined. */
internal object ImagePromptOptions {
    data class Parsed(val prompt: String, val options: AgentImageGenerationOptions)

    fun parse(prompt: String): Parsed {
        // One standalone JSON directive, not quoted examples/fences or loose numbers such as times.
        val lines = prompt.lines()
        val indexes = lines.indices.filter { lines[it].trimStart().startsWith("image_options:") }
        if (indexes.isEmpty()) return parseShapeClauses(prompt)
        if (indexes.size != 1 || prompt.contains("```"))
            AgentImageGenerationOptions.invalid("请只在独立一行填写一次 image_options: {...}，不要放在代码块或示例中。")
        val index = indexes.single()
        val raw = lines[index].trim().removePrefix("image_options:").trim()
        val json = try { JSONObject(raw) } catch (_: Exception) {
            AgentImageGenerationOptions.invalid("image_options 行需要有效 JSON 对象。")
        }
        val options = AgentImageGenerationOptions.fromJson(json).also { it.validateShape() }
        return Parsed(lines.filterIndexed { i, _ -> i != index }.joinToString("\n").trim(), options)
    }
    private fun parseShapeClauses(prompt: String): Parsed {
        val values = JSONObject()
        // Only whole standalone clauses are interpreted. Never mine dimensions from narrative,
        // dates/times, negations, quoted examples, code fences, or an arbitrary sentence.
        val clauses = prompt.split(Regex("[,，;；\\n]"))
        val patterns = listOf(
            "aspect_ratio" to Regex("(?:比例|宽高比|aspect_ratio)\\s*[=:：]?\\s*([0-9]+(?:\\.[0-9]+)?[:：][0-9]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE),
            "resolution" to Regex("(?:分辨率|清晰度|resolution)\\s*[=:：]?\\s*([1-9][0-9]?k)", RegexOption.IGNORE_CASE),
            "size" to Regex("(?:尺寸|size)\\s*[=:：]?\\s*([1-9][0-9]*[x×][1-9][0-9]*)", RegexOption.IGNORE_CASE),
            "aspect_ratio" to Regex("([0-9]+(?:\\.[0-9]+)?[:：][0-9]+(?:\\.[0-9]+)?)"),
            "resolution" to Regex("([1-9][0-9]?k)", RegexOption.IGNORE_CASE),
            "size" to Regex("([1-9][0-9]*[x×][1-9][0-9]*)", RegexOption.IGNORE_CASE),
        )
        if (prompt.contains("```") || Regex("不要|不是|而非|例如|比如|示例|不需要|don't|not |example", RegexOption.IGNORE_CASE).containsMatchIn(prompt)) {
            if (clauses.any { clause -> patterns.any { (_, pattern) -> pattern.matches(clause.trim().trimEnd('。', '.')) } })
                AgentImageGenerationOptions.invalid("描述包含否定、示例或代码块，不能确定尺寸意图；请用独立一行 image_options JSON 明确指定。")
            return Parsed(prompt, AgentImageGenerationOptions())
        }
        for (clause in clauses) {
            for ((key, pattern) in patterns) {
                val match = pattern.matchEntire(clause.trim().trimEnd('。', '.')) ?: continue
                val value = match.groupValues[1].lowercase().replace('：', ':').replace('×', 'x')
                if (values.has(key) && values.getString(key) != value)
                    AgentImageGenerationOptions.invalid("出现多个冲突的生图$key 参数；请用一行 image_options JSON 明确指定。")
                values.put(key, value)
                break
            }
        }
        return Parsed(prompt, AgentImageGenerationOptions.fromJson(values).also { it.validateShape() })
    }

}

/** The private eta_image_config object belongs to Eta; it must never reach a provider. */
internal object ImageRequestParameters {
    const val CONFIG_KEY = "eta_image_config"
    val keys = setOf("aspect_ratio", "resolution", "size", "n", "quality", "response_format")
    data class Prepared(
        val body: JSONObject,
        val options: AgentImageGenerationOptions,
        val editProtocol: String,
        val summary: String,
    )

    fun prepare(body: JSONObject, inline: AgentImageGenerationOptions, explicit: AgentImageGenerationOptions): Prepared {
        val rawConfig = body.remove(CONFIG_KEY)
        val config = when (rawConfig) {
            null -> JSONObject()
            is JSONObject -> rawConfig
            else -> AgentImageGenerationOptions.invalid("eta_image_config 必须是对象。")
        }
        if (config.keys().asSequence().any { it !in setOf("protocol", "fields", "sizes", "edit_protocol") })
            AgentImageGenerationOptions.invalid("eta_image_config 含未知配置。")
        val protocol = config.optString("protocol", "passthrough")
        if (protocol !in setOf("passthrough", "size")) AgentImageGenerationOptions.invalid("生图协议应为 passthrough 或 size。")
        val edit = config.optString("edit_protocol", "multipart")
        if (edit !in setOf("multipart", "json_image_url")) AgentImageGenerationOptions.invalid("编辑协议配置无效。")
        val fields = if (config.has("fields")) config.optJSONObject("fields")
            ?: AgentImageGenerationOptions.invalid("fields 必须是对象。") else JSONObject()
        if (fields.keys().asSequence().any { it !in keys }) AgentImageGenerationOptions.invalid("fields 仅允许生图参数名。")
        val paths = keys.associateWith { key ->
            val value = if (fields.has(key)) fields.opt(key) as? String
                ?: AgentImageGenerationOptions.invalid("字段映射必须是字符串。") else key
            val parts = value.split('.')
            if (parts.size > 6 || parts.any { !Regex("[A-Za-z_][A-Za-z0-9_]{0,63}").matches(it) } ||
                parts.first() in setOf("model", "prompt", "messages", "image", "images", "stream", "tools", CONFIG_KEY))
                AgentImageGenerationOptions.invalid("生图字段映射路径无效或覆盖保留字段。")
            parts
        }
        val allPaths = paths.values.toList()
        for (i in allPaths.indices) for (j in i+1 until allPaths.size) {
            val a = allPaths[i]; val b = allPaths[j]
            if (a.take(b.size) == b || b.take(a.size) == a) AgentImageGenerationOptions.invalid("生图字段映射路径冲突。")
        }
        // Canonical configured defaults are authoritative over their mapped representations.
        val canonical = JSONObject()
        keys.forEach { key ->
            val value = if (body.has(key)) body.get(key) else readPath(body, paths.getValue(key))
            if (value != null) canonical.put(key, value)
        }
        inline.applyTo(canonical)
        explicit.applyTo(canonical)
        var options = AgentImageGenerationOptions.fromJson(canonical).also { it.validateShape() }
        if (protocol == "size" && (options.aspectRatio != null || options.resolution != null)) {
            if (options.size == null) {
                val ratio = options.aspectRatio ?: AgentImageGenerationOptions.invalid("size 协议转换需要明确比例。")
                val mappingKey = if (options.resolution == null) ratio else "$ratio@${options.resolution}"
                val sizes = config.optJSONObject("sizes") ?: AgentImageGenerationOptions.invalid("请配置 sizes 的精确尺寸映射，不会自动猜测。")
                val mapped = sizes.opt(mappingKey) as? String ?: AgentImageGenerationOptions.invalid("未配置 sizes[$mappingKey]；不会替换为近似比例。")
                options = options.copy(size = mapped).also { AgentImageGenerationOptions.fromJson(it.toJson()); it.validateShape() }
            } else if (options.resolution != null) {
                AgentImageGenerationOptions.invalid("size 协议不能同时指定 size 和 resolution，请选用精确映射。")
            }
            canonical.remove("aspect_ratio"); canonical.remove("resolution")
            canonical.put("size", options.size)
        }
        keys.forEach { key -> body.remove(key); removePath(body, paths.getValue(key)) }
        val sent = JSONObject()
        canonical.keys().forEach { key ->
            val value = canonical.get(key)
            writePath(body, paths.getValue(key), value)
            sent.put(paths.getValue(key).joinToString("."), value)
        }
        return Prepared(body, options, edit, "发送参数：$sent")
    }

    private fun readPath(root: JSONObject, parts: List<String>): Any? {
        var node = root
        for (part in parts.dropLast(1)) node = node.optJSONObject(part) ?: return null
        return node.opt(parts.last())
    }
    private fun removePath(root: JSONObject, parts: List<String>) {
        var node = root
        for (part in parts.dropLast(1)) node = node.optJSONObject(part) ?: return
        node.remove(parts.last())
    }
    private fun writePath(root: JSONObject, parts: List<String>, value: Any) {
        var node = root
        for (part in parts.dropLast(1)) {
            if (node.has(part) && node.optJSONObject(part) == null) AgentImageGenerationOptions.invalid("映射路径与已有非对象参数冲突。")
            node = node.optJSONObject(part) ?: JSONObject().also { node.put(part, it) }
        }
        node.put(parts.last(), value)
    }
}
