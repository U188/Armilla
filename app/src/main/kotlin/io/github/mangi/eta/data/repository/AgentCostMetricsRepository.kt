package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.datastore.SettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 成本度量的 5 个计数维度（阶段 0 只加计数，不改变任何既有行为）。
 *
 * [wire] 是落盘的字段名，必须与 [AgentCostMetricsLedger] 内部维护的 wire 名保持一致。
 */
enum class AgentCostMetricKind(val wire: String) {
    ModelResponses("responses"),
    Delegations("delegations"),
    TaskQueries("queries"),
    Compactions("compactions"),
    ChildNotices("notices"),
}

/**
 * 一行度量：某个会话、某个作用域、某个本地小时内的一批计数。
 *
 * [counts] 始终包含全部 5 个维度（缺失的维度为 0），方便直接取值展示与断言。
 */
data class AgentCostMetricRow(
    val conversationId: String,
    val scope: String,
    val hourEpochMillis: Long,
    val counts: Map<AgentCostMetricKind, Int>,
)

/**
 * 度量快照。[rows] 固定按「小时倒序 → 同小时 parent 在前 → scope 字典序」排序。
 */
data class AgentCostMetricsSnapshot(val rows: List<AgentCostMetricRow>) {
    /** 该会话的全部行（含所有小时与作用域）。 */
    fun forConversation(conversationId: String): List<AgentCostMetricRow> =
        rows.filter { sameConversation(it.conversationId, conversationId) }

    /** 该会话某个小时的全部行。 */
    fun forHour(conversationId: String, hourEpochMillis: Long): List<AgentCostMetricRow> =
        rows.filter {
            sameConversation(it.conversationId, conversationId) && it.hourEpochMillis == hourEpochMillis
        }

    /** 验收用：某会话某小时全部计数，按 scope 列出（scope 顺序同 [rows]）。 */
    fun table(
        conversationId: String,
        hourEpochMillis: Long,
    ): Map<String, Map<AgentCostMetricKind, Int>> =
        forHour(conversationId, hourEpochMillis)
            .associate { it.scope to it.counts }
}

/**
 * 子代理成本度量基线的仓库（阶段 0 只做度量，不做任何成本优化）。
 *
 * 与 [UsageStatsRepository] 同构：[record] 是唯一写入口，内部用 Mutex 串行化并写 DataStore；
 * 读取走 [snapshotFlow] / [load]，UI 只读快照。所有异常都被吞掉，统计失败绝不影响 Agent 主流程。
 *
 * 会话标识：空串/空白会话（例如尚未落库的草稿会话）统一归一化为 [DRAFT_CONVERSATION_ID]，
 * 读取侧用同样的规则比较，因此用 `""` 记录、用 `""` 查询依然可以正常命中。
 */
object AgentCostMetricsRepository {
    const val SCOPE_PARENT = "parent"

    private val writeLock = Mutex()

    private val rowOrder = compareByDescending<AgentCostMetricRow> { it.hourEpochMillis }
        .thenBy { scopeRank(it.scope) }
        .thenBy { it.scope }
        .thenBy { it.conversationId }

    fun childScope(agentId: String): String = "child:" + agentId

    /** 唯一的写入口。任何线程可调用；内部 Mutex 串行化，落 DataStore。 */
    fun record(
        conversationId: String,
        scope: String,
        atMillis: Long,
        kind: AgentCostMetricKind,
        amount: Int = 1,
    ) {
        if (amount <= 0) return
        val normalizedId = normalizeConversationId(conversationId)
        val normalizedScope = scope.ifBlank { SCOPE_PARENT }
        // 度量失败绝不能影响 Agent 主流程，也不允许把异常抛回调用方。
        runCatching {
            runBlocking(Dispatchers.IO) {
                writeLock.withLock {
                    SettingsDataStore.updateAgentCostMetrics { raw ->
                        val state = AgentCostMetricsLedger.decode(raw)
                        val changed = AgentCostMetricsLedger.apply(
                            state = state,
                            conversationId = normalizedId,
                            scope = normalizedScope,
                            atMillis = atMillis,
                            kindWire = kind.wire,
                            amount = amount,
                        )
                        if (changed) AgentCostMetricsLedger.encode(state) else raw
                    }
                }
            }
        }
    }

    fun snapshotFlow(): Flow<AgentCostMetricsSnapshot> =
        SettingsDataStore.agentCostMetricsFlow()
            .map { raw -> snapshotOf(AgentCostMetricsLedger.decode(raw)) }
            .distinctUntilChanged()

    suspend fun load(): AgentCostMetricsSnapshot =
        runCatching {
            snapshotOf(AgentCostMetricsLedger.decode(SettingsDataStore.agentCostMetricsJson()))
        }.getOrElse { AgentCostMetricsSnapshot(emptyList()) }

    /** 便于测试与 UI 复用的纯函数式聚合：把账本状态还原成有序快照。 */
    fun snapshotOf(state: Map<String, Map<String, Int>>): AgentCostMetricsSnapshot {
        val rows = ArrayList<AgentCostMetricRow>(state.size)
        state.forEach { (key, rawCounts) ->
            val parsed = AgentCostMetricsLedger.parseRowKey(key) ?: return@forEach
            val counts = LinkedHashMap<AgentCostMetricKind, Int>(AgentCostMetricKind.values().size)
            AgentCostMetricKind.values().forEach { kind ->
                counts[kind] = (rawCounts[kind.wire] ?: 0).coerceAtLeast(0)
            }
            rows += AgentCostMetricRow(
                conversationId = parsed.first,
                scope = parsed.second,
                hourEpochMillis = parsed.third,
                counts = counts,
            )
        }
        return AgentCostMetricsSnapshot(rows.sortedWith(rowOrder))
    }

    private val asyncWriter: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "agent-cost-metrics").apply { isDaemon = true }
        }

    /**
     * 关键路径（watchdog 线程、子代理 worker、取消路径）专用：绝不阻塞调用线程。
     * 度量写入是旁路观测，慢一点没关系，但绝不能顺延心跳、租约续期或任务启动。
     */
    fun recordAsync(
        conversationId: String,
        scope: String,
        atMillis: Long,
        kind: AgentCostMetricKind,
        amount: Int = 1,
    ) {
        if (amount <= 0) return
        runCatching { asyncWriter.execute { record(conversationId, scope, atMillis, kind, amount) } }
    }

    private fun scopeRank(scope: String): Int = if (scope == SCOPE_PARENT) 0 else 1
}

/** 空会话标识的稳定占位；记录与查询两侧共用同一套归一化，空串因此也能正常存取。 */
private const val DRAFT_CONVERSATION_ID = "draft"

private fun normalizeConversationId(conversationId: String): String =
    conversationId.ifBlank { DRAFT_CONVERSATION_ID }

private fun sameConversation(left: String, right: String): Boolean =
    normalizeConversationId(left) == normalizeConversationId(right)
