package io.github.mangi.eta.data.repository

import java.time.Instant
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 JVM 测试（不使用 Robolectric、不触碰 DataStore）：只覆盖账本与快照聚合的纯函数行为。
 * 时区一律显式传入，避免宿主系统时区导致结果漂移。
 */
class AgentCostMetricsLedgerTest {
    private val utc: ZoneId = ZoneId.of("UTC")
    private val baseHour: Long = Instant.parse("2026-01-02T10:00:00Z").toEpochMilli()

    private fun emptyState(): MutableMap<String, MutableMap<String, Int>> = LinkedHashMap()

    @Test
    fun hourBucketFloorsToLocalHourStartAndSplitsAcrossHours() {
        val justAfterHour = Instant.parse("2026-01-02T10:00:00Z").toEpochMilli()
        val lateInHour = Instant.parse("2026-01-02T10:59:59.999Z").toEpochMilli()
        val nextHour = Instant.parse("2026-01-02T11:00:00Z").toEpochMilli()

        assertEquals(baseHour, AgentCostMetricsLedger.hourBucket(justAfterHour, utc))
        assertEquals(baseHour, AgentCostMetricsLedger.hourBucket(lateInHour, utc))
        assertEquals(baseHour + 3_600_000L, AgentCostMetricsLedger.hourBucket(nextHour, utc))
        assertTrue(AgentCostMetricsLedger.hourBucket(nextHour, utc) != baseHour)

        // 时区参与切分：+05:30 的本地整点落在 UTC 的 :30。
        val kolkata = AgentCostMetricsLedger.hourBucket(
            Instant.parse("2026-01-02T10:34:56Z").toEpochMilli(),
            ZoneId.of("Asia/Kolkata"),
        )
        assertEquals(baseHour + 30 * 60_000L, kolkata)

        // 默认参数使用系统时区（这里只验证与显式系统时区调用一致）。
        assertEquals(
            AgentCostMetricsLedger.hourBucket(baseHour, ZoneId.systemDefault()),
            AgentCostMetricsLedger.hourBucket(baseHour),
        )
    }

    @Test
    fun applyAccumulatesCreatesRowsAndIgnoresNonPositiveAmounts() {
        val state = emptyState()
        assertTrue(AgentCostMetricsLedger.apply(state, "conv", "parent", baseHour + 1_000L, "responses", 3))
        val key = AgentCostMetricsLedger.rowKey("conv", "parent", baseHour)
        assertEquals(mapOf("responses" to 3), state[key])

        assertTrue(AgentCostMetricsLedger.apply(state, "conv", "parent", baseHour + 59_000L, "responses", 2))
        assertEquals(5, state.getValue(key).getValue("responses"))

        assertTrue(
            AgentCostMetricsLedger.apply(
                state,
                "conv",
                AgentCostMetricsRepository.childScope("agent-1"),
                baseHour,
                AgentCostMetricKind.TaskQueries.wire,
                1,
            ),
        )
        assertEquals(2, state.size)

        val before = state.mapValues { (_, counts) -> counts.toMap() }
        assertFalse(AgentCostMetricsLedger.apply(state, "conv", "parent", baseHour, "responses", 0))
        assertFalse(AgentCostMetricsLedger.apply(state, "conv", "parent", baseHour, "responses", -4))
        assertFalse(AgentCostMetricsLedger.apply(state, "conv", "parent", baseHour, "   ", 1))
        assertFalse(AgentCostMetricsLedger.apply(state, "conv", "", baseHour, "responses", 1))
        assertEquals(before, state)
    }

    @Test
    fun encodeDecodeRoundTripsCountsWithoutStoringProse() {
        assertEquals(
            listOf("responses", "delegations", "queries", "compactions", "notices"),
            AgentCostMetricKind.values().map { it.wire },
        )

        val state = emptyState()
        AgentCostMetricsLedger.apply(state, "conv-a", "parent", baseHour, AgentCostMetricKind.ModelResponses.wire, 3)
        AgentCostMetricsLedger.apply(state, "conv-a", "parent", baseHour, AgentCostMetricKind.Delegations.wire, 1)
        AgentCostMetricsLedger.apply(
            state,
            "conv-a",
            AgentCostMetricsRepository.childScope("agent-1"),
            baseHour + 60_000L,
            AgentCostMetricKind.ChildNotices.wire,
            4,
        )
        AgentCostMetricsLedger.apply(
            state,
            "conv-a",
            "parent",
            baseHour + 3_600_000L,
            AgentCostMetricKind.TaskQueries.wire,
            2,
        )
        AgentCostMetricsLedger.apply(state, "", "parent", baseHour, AgentCostMetricKind.Compactions.wire, 7)

        val encoded = AgentCostMetricsLedger.encode(state)
        val decoded = AgentCostMetricsLedger.decode(encoded)
        assertEquals(state, decoded)
        // 空串会话标识原样存取，不丢行。
        assertTrue(decoded.containsKey(AgentCostMetricsLedger.rowKey("", "parent", baseHour)))

        val root = JSONObject(encoded)
        assertEquals(1, root.getInt("version"))
        assertEquals(state.size, root.getJSONObject("rows").length())
        val rowsJson = root.getJSONObject("rows")
        for (rowKey in rowsJson.keys()) {
            for (field in rowsJson.getJSONObject(rowKey).keys()) {
                assertTrue(
                    "unexpected metric field: $field",
                    field in setOf("responses", "delegations", "queries", "compactions", "notices"),
                )
            }
        }
        // 反复编码稳定（语义一致）。
        assertEquals(decoded, AgentCostMetricsLedger.decode(AgentCostMetricsLedger.encode(decoded)))
    }

    @Test
    fun decodeToleratesCorruptInput() {
        assertTrue(AgentCostMetricsLedger.decode(null).isEmpty())
        assertTrue(AgentCostMetricsLedger.decode("").isEmpty())
        assertTrue(AgentCostMetricsLedger.decode("   ").isEmpty())
        assertTrue(AgentCostMetricsLedger.decode("not json").isEmpty())
        assertTrue(AgentCostMetricsLedger.decode("[]").isEmpty())
        assertTrue(AgentCostMetricsLedger.decode("{\"version\":1}").isEmpty())
        assertTrue(AgentCostMetricsLedger.decode("{\"rows\":\"nope\"}").isEmpty())
        assertTrue(AgentCostMetricsLedger.decode("{\"rows\":{\"bad\":1}}").isEmpty())

        // 半损坏：能解析的行保留，未知字段与非法行键丢弃。
        val goodKey = AgentCostMetricsLedger.rowKey("conv", "parent", baseHour)
        val raw = "{\"version\":1,\"rows\":{" +
            JSONObject.quote(goodKey) + ":{\"responses\":2,\"bogus\":9}," +
            JSONObject.quote("broken") + ":{\"responses\":5}," +
            JSONObject.quote(AgentCostMetricsLedger.rowKey("conv", "", baseHour)) + ":{\"responses\":5}}}"
        val decoded = AgentCostMetricsLedger.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals(mapOf("responses" to 2), decoded[goodKey])
    }

    @Test
    fun applyDropsOldestHoursPastRowLimit() {
        val state = emptyState()
        val hourCount = AgentCostMetricsLedger.MAX_ROWS + 3
        var newestBucket = baseHour
        for (index in 0 until hourCount) {
            newestBucket = baseHour + index * 3_600_000L
            assertTrue(AgentCostMetricsLedger.apply(state, "conv", "parent", newestBucket, "responses", 1))
        }

        assertEquals(AgentCostMetricsLedger.MAX_ROWS, state.size)
        assertFalse(state.containsKey(AgentCostMetricsLedger.rowKey("conv", "parent", baseHour)))
        assertFalse(state.containsKey(AgentCostMetricsLedger.rowKey("conv", "parent", baseHour + 3_600_000L)))
        assertFalse(state.containsKey(AgentCostMetricsLedger.rowKey("conv", "parent", baseHour + 2 * 3_600_000L)))
        assertTrue(state.containsKey(AgentCostMetricsLedger.rowKey("conv", "parent", baseHour + 3 * 3_600_000L)))
        assertTrue(state.containsKey(AgentCostMetricsLedger.rowKey("conv", "parent", newestBucket)))
    }

    @Test
    fun rowKeyRoundTripsAndRejectsIllegalInput() {
        assertEquals(
            Triple("conv", "child:agent-1", baseHour),
            AgentCostMetricsLedger.parseRowKey(
                AgentCostMetricsLedger.rowKey("conv", AgentCostMetricsRepository.childScope("agent-1"), baseHour),
            ),
        )
        assertEquals(
            Triple("", "parent", 0L),
            AgentCostMetricsLedger.parseRowKey(AgentCostMetricsLedger.rowKey("", "parent", 0L)),
        )
        assertEquals(
            Triple("conv", "parent", -3_600_000L),
            AgentCostMetricsLedger.parseRowKey(AgentCostMetricsLedger.rowKey("conv", "parent", -3_600_000L)),
        )

        assertNull(AgentCostMetricsLedger.parseRowKey(""))
        assertNull(AgentCostMetricsLedger.parseRowKey("conv"))
        assertNull(AgentCostMetricsLedger.parseRowKey("conv\u0000parent"))
        assertNull(AgentCostMetricsLedger.parseRowKey("conv\u0000parent\u0000not-a-number"))
        assertNull(AgentCostMetricsLedger.parseRowKey("conv\u0000parent\u0000123\u0000extra"))
        // 空会话标识 + 合法 scope 仍然可以解析（空串必须能正常存取）。
        assertEquals(
            Triple("", "parent", 123L),
            AgentCostMetricsLedger.parseRowKey("\u0000parent\u0000123"),
        )
        assertNull(AgentCostMetricsLedger.parseRowKey("conv\u0000\u0000123"))
    }

    @Test
    fun snapshotOfGroupsByHourAndScopeFillingMissingKinds() {
        val state = emptyState()
        AgentCostMetricsLedger.apply(state, "conv-a", AgentCostMetricsRepository.SCOPE_PARENT, baseHour, "responses", 2)
        AgentCostMetricsLedger.apply(state, "conv-a", AgentCostMetricsRepository.SCOPE_PARENT, baseHour, "delegations", 1)
        AgentCostMetricsLedger.apply(
            state,
            "conv-a",
            AgentCostMetricsRepository.childScope("agent-1"),
            baseHour,
            "queries",
            3,
        )
        AgentCostMetricsLedger.apply(
            state,
            "conv-a",
            AgentCostMetricsRepository.SCOPE_PARENT,
            baseHour + 3_600_000L,
            "responses",
            5,
        )
        AgentCostMetricsLedger.apply(state, "conv-b", AgentCostMetricsRepository.SCOPE_PARENT, baseHour, "responses", 9)

        val snapshot = AgentCostMetricsRepository.snapshotOf(state)
        assertEquals(4, snapshot.rows.size)
        // 小时倒序，同小时 parent 在前。
        assertEquals(baseHour + 3_600_000L, snapshot.rows.first().hourEpochMillis)
        val sameHour = snapshot.forHour("conv-a", baseHour)
        assertEquals(2, sameHour.size)
        assertEquals(AgentCostMetricsRepository.SCOPE_PARENT, sameHour.first().scope)
        assertEquals("child:agent-1", sameHour.last().scope)
        assertEquals(3, snapshot.forConversation("conv-a").size)

        val table = snapshot.table("conv-a", baseHour)
        assertEquals(setOf("parent", "child:agent-1"), table.keys)
        assertEquals(2, table.getValue("parent").getValue(AgentCostMetricKind.ModelResponses))
        assertEquals(1, table.getValue("parent").getValue(AgentCostMetricKind.Delegations))
        assertEquals(0, table.getValue("parent").getValue(AgentCostMetricKind.ChildNotices))
        assertEquals(3, table.getValue("child:agent-1").getValue(AgentCostMetricKind.TaskQueries))
        assertTrue(snapshot.table("conv-b", baseHour + 3_600_000L).isEmpty())
    }

    @Test
    fun emptyConversationIdStoresAndQueriesThroughStablePlaceholder() {
        val state = emptyState()
        AgentCostMetricsLedger.apply(state, "", AgentCostMetricsRepository.SCOPE_PARENT, baseHour, "responses", 4)

        val snapshot = AgentCostMetricsRepository.snapshotOf(state)
        assertEquals(1, snapshot.forConversation("").size)
        assertEquals(1, snapshot.forConversation("draft").size)
        assertEquals(4, snapshot.table("", baseHour).getValue("parent").getValue(AgentCostMetricKind.ModelResponses))
        assertTrue(snapshot.forConversation("other").isEmpty())
    }
}
