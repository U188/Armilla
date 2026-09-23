package io.github.mangi.eta.data.repository

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.json.JSONObject

/**
 * 「子代理成本优化」阶段 0 的度量基线账本（纯数据层，只依赖 org.json，不触碰 Android API）。
 *
 * 存储形态（`\u0000` 为分隔符，行键 3 段，值只存 5 个计数维度，绝不写入任何正文）：
 * ```json
 * {"version":1,"rows":{"<conversationId>\u0000<scope>\u0000<hourEpochMillis>":{"responses":3,"queries":5}}}
 * ```
 *
 * - 行粒度：(会话, 本地小时, 作用域)。
 * - 作用域取值：`parent`（主对话）或 `child:<agentId>`（子代理）；账本把 scope 当作不透明字符串，
 *   但要求非空白，否则该行无法被 [parseRowKey] 读回。
 * - 会话标识：本层原样保存调用方给出的 `conversationId`（**包括空串**——行键允许空段，[parseRowKey]
 *   也能原样读回，因此空串与普通 id 一样可以正常存取）。把空串归一化成 `"draft"` 这类稳定占位的
 *   工作由上层 [AgentCostMetricsRepository] 统一完成，账本自身不做改写，避免出现两套标识。
 *
 * 线程安全：本对象无状态，线程安全性由调用方（Repository 的 Mutex）保证。
 */
internal object AgentCostMetricsLedger {
    /** 行数上限：超出后按本地小时升序丢弃最旧的行。 */
    const val MAX_ROWS = 4000

    /** 五个计数维度的 wire 名；列表顺序即编码顺序，与 [AgentCostMetricKind.wire] 一一对应。 */
    private val WIRE_KINDS = listOf("responses", "delegations", "queries", "compactions", "notices")

    /** 行键第 2 段与第 3 段的分隔符；JSON 会把它写成 `\u0000` 转义。 */
    private const val SEP = "\u0000"

    private const val VERSION_KEY = "version"
    private const val ROWS_KEY = "rows"
    private const val VERSION = 1

    /** 把毫秒时间戳落到所在「本地小时」的起点（按 [zone] 切分，默认系统时区）。 */
    fun hourBucket(atMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(atMillis)
            .atZone(zone)
            .truncatedTo(ChronoUnit.HOURS)
            .toInstant()
            .toEpochMilli()

    /**
     * 解析账本 JSON。输入为空、非法 JSON、结构不符时一律返回空表（不抛异常），
     * 只有 5 个已知维度且数值大于 0 的条目会被保留，未知字段与无法解析的行键直接丢弃。
     */
    fun decode(json: String?): MutableMap<String, MutableMap<String, Int>> {
        val empty = LinkedHashMap<String, MutableMap<String, Int>>()
        if (json.isNullOrBlank()) return empty
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return empty
        val rowsJson = root.optJSONObject(ROWS_KEY) ?: return empty
        val state = LinkedHashMap<String, MutableMap<String, Int>>()
        rowsJson.keys().forEach { key ->
            if (parseRowKey(key) == null) return@forEach
            val rowJson = rowsJson.optJSONObject(key) ?: return@forEach
            val counts = LinkedHashMap<String, Int>()
            WIRE_KINDS.forEach { wire ->
                val value = rowJson.optInt(wire, 0)
                if (value > 0) counts[wire] = value
            }
            if (counts.isNotEmpty()) state[key] = counts
        }
        return state
    }

    /** 编码账本 JSON。键按字典序写入，同一状态输出稳定；只写计数，不写任何正文。 */
    fun encode(state: Map<String, Map<String, Int>>): String {
        val rowsJson = JSONObject()
        state.keys.sorted().forEach { key ->
            val counts = state[key] ?: return@forEach
            val rowJson = JSONObject()
            WIRE_KINDS.forEach { wire ->
                val value = counts[wire] ?: 0
                if (value > 0) rowJson.put(wire, value)
            }
            if (rowJson.length() > 0) rowsJson.put(key, rowJson)
        }
        val root = JSONObject()
        root.put(VERSION_KEY, VERSION)
        root.put(ROWS_KEY, rowsJson)
        return root.toString()
    }

    /**
     * 把一次计数累加进 [state]。
     *
     * @return true 表示状态发生变化。`amount <= 0`、维度名为空白、作用域为空白（这些行读不回来）
     *   一律视为无操作并返回 false，不会为了写入而创建空行或空维度。
     */
    fun apply(
        state: MutableMap<String, MutableMap<String, Int>>,
        conversationId: String,
        scope: String,
        atMillis: Long,
        kindWire: String,
        amount: Int,
    ): Boolean {
        if (amount <= 0 || kindWire.isBlank() || scope.isBlank()) return false
        val key = rowKey(conversationId, scope, hourBucket(atMillis))
        val counts = state.getOrPut(key) { LinkedHashMap() }
        counts[kindWire] = (counts[kindWire] ?: 0) + amount
        pruneOverflow(state)
        return true
    }

    /** 行键：`<conversationId>\u0000<scope>\u0000<hourEpochMillis>`（[conversationId] 允许为空串）。 */
    fun rowKey(conversationId: String, scope: String, hourEpochMillis: Long): String =
        conversationId + SEP + scope + SEP + hourEpochMillis

    /**
     * 解析行键，返回 (conversationId, scope, hourEpochMillis)。
     * 非法输入（分隔符数量不对、scope 为空、小时不是整数）返回 null。
     */
    fun parseRowKey(key: String): Triple<String, String, Long>? {
        if (key.isEmpty()) return null
        val first = key.indexOf(SEP)
        if (first < 0) return null
        val second = key.indexOf(SEP, first + 1)
        if (second < 0) return null
        if (key.indexOf(SEP, second + 1) >= 0) return null
        val conversationId = key.substring(0, first)
        val scope = key.substring(first + 1, second)
        if (scope.isEmpty()) return null
        val hourEpochMillis = key.substring(second + 1).toLongOrNull() ?: return null
        return Triple(conversationId, scope, hourEpochMillis)
    }

    /** 超出 [MAX_ROWS] 时按小时升序丢弃最旧的行；解析不出小时的行视为最旧，优先丢弃。 */
    private fun pruneOverflow(state: MutableMap<String, MutableMap<String, Int>>) {
        if (state.size <= MAX_ROWS) return
        val oldestFirst = state.keys.sortedWith(
            compareBy<String> { parseRowKey(it)?.third ?: Long.MIN_VALUE }.thenBy { it },
        )
        oldestFirst.take(state.size - MAX_ROWS).forEach { key -> state.remove(key) }
    }
}
