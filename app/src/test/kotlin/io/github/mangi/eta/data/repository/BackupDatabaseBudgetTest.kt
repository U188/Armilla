package io.github.mangi.eta.data.repository

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackupDatabaseBudgetTest {
    private fun database(): SupportSQLiteOpenHelper {
        val context = RuntimeEnvironment.getApplication() as Context
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE conversations (id TEXT PRIMARY KEY, history_json TEXT)")
                        db.execSQL("CREATE TABLE conversation_messages (conversation_id TEXT, content TEXT)")
                        db.execSQL("CREATE TABLE conversation_context_checkpoints (conversation_id TEXT, history_json TEXT)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
    }

    @Test fun singleConversationBudgetDoesNotLoadOrCountOtherConversations() {
        database().use { helper ->
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO conversations VALUES ('small', '[]')")
            db.execSQL("INSERT INTO conversation_messages VALUES ('other', zeroblob(?))", arrayOf<Any>(BackupDatabaseBudget.MAX_BYTES + 1))
            BackupDatabaseBudget.validate(db, "small")
            assertTrue(runCatching { BackupDatabaseBudget.validate(db, "other") }.isFailure)
        }
    }

    @Test fun quotedConversationIdentifierIsBoundAsData() {
        database().use { helper ->
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO conversations VALUES (?, '[]')", arrayOf<Any>("' OR 1=1 --"))
            BackupDatabaseBudget.validate(db, "' OR 1=1 --")
            db.query("SELECT COUNT(*) FROM conversations").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
            }
        }
    }

    @Test fun oversizedSingleRowFailsBudgetWithReadableMessage() {
        database().use { helper ->
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO conversations VALUES ('c', '[]')")
            // 单行超过 CursorWindow 承载上限：总量校验（8MB）之外，单行体检必须拦下。
            db.execSQL(
                "INSERT INTO conversation_messages VALUES ('c', ?)",
                arrayOf<Any>("x".repeat((BackupDatabaseBudget.MAX_ROW_BYTES + 1).toInt())),
            )
            // 用会话作用域校验，只扫 conversation_* 表（fixture 未建其余表）。单行体检应命中并给出可读提示。
            val failure = runCatching { BackupDatabaseBudget.validate(db, "c") }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure!!.message!!.contains("单条记录"))
        }
    }
}
