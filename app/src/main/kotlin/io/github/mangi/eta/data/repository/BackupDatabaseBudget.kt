package io.github.mangi.eta.data.repository

import androidx.sqlite.db.SupportSQLiteDatabase

/** SQL aggregates only: reject oversized snapshots before Room allocates all row DTOs. */
internal object BackupDatabaseBudget {
    private val tables = listOf(
        "model_providers", "provider_models", "conversations", "conversation_messages",
        "conversation_context_checkpoints", "conversation_state", "conversation_folders",
        "skill_registry", "mcp_servers",
    )
    const val MAX_BYTES = 8L * 1024 * 1024
    const val MAX_ROWS = 50_000L
    /**
     * 单行字节上限。Android CursorWindow 单窗口约 2MB，单行超过它时 Room 的 `SELECT *`
     * 会抛 Row too big / NO_MEMORY，导出在写 ZIP 前就崩、只留 0KB 空文件。分页不能救单行超大，
     * 因此这里在读取前用 MAX(单行字节) 体检，超限就给出可读提示而不是崩溃。留 256KB 余量。
     */
    const val MAX_ROW_BYTES = 2L * 1024 * 1024 - 256L * 1024

    fun validate(database: SupportSQLiteDatabase, conversationId: String? = null) {
        var bytes = 0L
        var rows = 0L
        for (table in tables) {
            if (conversationId != null && table !in setOf("conversations", "conversation_messages", "conversation_context_checkpoints")) continue
            val columns = database.query("PRAGMA table_info(`$table`)").use { cursor ->
                val names = mutableListOf<String>()
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex)
                    require(name.matches(Regex("[a-zA-Z_][a-zA-Z_0-9]*"))) { "不支持的数据库字段名" }
                    names += name
                }
                names
            }
            require(columns.isNotEmpty()) { "备份数据库表缺失：$table" }
            val sum = columns.joinToString(" + ") { "COALESCE(LENGTH(CAST(`$it` AS BLOB)), 0)" }
            val where = if (conversationId == null) "" else
                " WHERE `${if (table == "conversations") "id" else "conversation_id"}` = ?"
            val args = if (conversationId == null) emptyArray<Any>() else arrayOf<Any>(conversationId)
            database.query("SELECT COUNT(*), COALESCE(SUM($sum), 0), COALESCE(MAX($sum), 0) FROM `$table`$where", args).use { cursor ->
                check(cursor.moveToFirst())
                rows += cursor.getLong(0)
                bytes += cursor.getLong(1)
                // 单行体检：CursorWindow 逐行 2MB 限制，总量校验发现不了单行超大。
                require(cursor.getLong(2) <= MAX_ROW_BYTES) {
                    "备份中有单条记录（例如某条超长消息）过大，无法放入系统读取窗口。" +
                        "请先在该会话里删除或拆分这条超长内容，再重试导出。"
                }
            }
            require(rows <= MAX_ROWS && bytes <= MAX_BYTES) {
                "备份元数据超过当前安全快照上限，请先导出单个会话或减少数据"
            }
        }
    }
}
