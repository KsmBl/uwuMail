package de.uwumail.data.db

import androidx.room.util.TableInfo
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Moving a rule's account from a column that holds one to a column that holds
 * several.
 *
 * The column changes type, so the table has to be rebuilt, and the obvious way
 * to rebuild it is wrong: the conditions and actions have foreign keys onto
 * the rules table with ON DELETE CASCADE, and dropping a parent table performs
 * an implicit delete that fires them. Doing it in the obvious order would
 * migrate every rule and silently throw away everything each one actually
 * does.
 *
 * So this runs the real migration over a real version 10 database and checks
 * both halves: the scope arrives, and the rules still have their conditions
 * and actions afterwards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RuleAccountMigrationTest {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    /** The shape of the three tables at version 10, as Room had them. */
    private val version10 = listOf(
        """CREATE TABLE rules (
            id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            enabled INTEGER NOT NULL,
            priority INTEGER NOT NULL,
            accountId INTEGER,
            folderPath TEXT,
            matchMode TEXT NOT NULL,
            stopProcessing INTEGER NOT NULL,
            createdAt INTEGER NOT NULL,
            lastMatchedAt INTEGER,
            matchCount INTEGER NOT NULL
        )""",
        """CREATE TABLE rule_conditions (
            id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
            ruleId INTEGER NOT NULL,
            field TEXT NOT NULL,
            headerName TEXT,
            operator TEXT NOT NULL,
            value TEXT NOT NULL,
            negate INTEGER NOT NULL,
            caseSensitive INTEGER NOT NULL,
            FOREIGN KEY(ruleId) REFERENCES rules(id) ON DELETE CASCADE
        )""",
        """CREATE TABLE rule_actions (
            id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
            ruleId INTEGER NOT NULL,
            type TEXT NOT NULL,
            stringArg TEXT,
            orderIndex INTEGER NOT NULL,
            FOREIGN KEY(ruleId) REFERENCES rules(id) ON DELETE CASCADE
        )"""
    )

    @Before
    fun openVersion10() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(NAME)
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(NAME)
            .callback(object : SupportSQLiteOpenHelper.Callback(10) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    version10.forEach(db::execSQL)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()
        helper = FrameworkSQLiteOpenHelperFactory().create(config)
        db = helper.writableDatabase
        // The same enforcement Room opens with, which is what makes the cascade
        // above a real risk rather than a theoretical one.
        db.setForeignKeyConstraintsEnabled(true)
    }

    @After
    fun close() {
        runCatching { db.close() }
        ApplicationProvider.getApplicationContext<android.content.Context>().deleteDatabase(NAME)
    }

    private fun rule(id: Long, name: String, accountId: Long?) {
        db.execSQL(
            "INSERT INTO rules (id, name, enabled, priority, accountId, folderPath," +
                " matchMode, stopProcessing, createdAt, lastMatchedAt, matchCount)" +
                " VALUES (?, ?, 1, ?, ?, 'INBOX', 'ALL', 0, 0, NULL, 3)",
            arrayOf(id, name, id.toInt(), accountId)
        )
        db.execSQL(
            "INSERT INTO rule_conditions (ruleId, field, headerName, operator, value," +
                " negate, caseSensitive) VALUES (?, 'SUBJECT', NULL, 'CONTAINS', 'x', 0, 0)",
            arrayOf(id)
        )
        db.execSQL(
            "INSERT INTO rule_actions (ruleId, type, stringArg, orderIndex)" +
                " VALUES (?, 'MOVE_TO_TRASH', NULL, 0)",
            arrayOf(id)
        )
    }

    /** Run the way Room runs it: inside a transaction, with foreign keys on. */
    private fun migrate() {
        db.beginTransaction()
        try {
            AppDatabase.MIGRATION_10_11.migrate(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun <T> query(sql: String, read: (android.database.Cursor) -> T): T =
        db.query(sql).use { it.moveToFirst(); read(it) }

    private fun count(table: String): Int = query("SELECT COUNT(*) FROM $table") { it.getInt(0) }

    @Test
    fun `a rule scoped to one account keeps that account`() {
        rule(1, "scoped", accountId = 7)

        migrate()

        assertEquals("7", query("SELECT accountIds FROM rules WHERE id = 1") { it.getString(0) })
    }

    @Test
    fun `a rule scoped to no account stays scoped to none`() {
        rule(1, "everywhere", accountId = null)

        migrate()

        assertNull(query("SELECT accountIds FROM rules WHERE id = 1") { it.getString(0) })
    }

    /** The one this test exists for. */
    @Test
    fun `the conditions survive the rebuild`() {
        rule(1, "one", accountId = 7)
        rule(2, "two", accountId = null)

        migrate()

        assertEquals(2, count("rule_conditions"))
    }

    @Test
    fun `and so do the actions`() {
        rule(1, "one", accountId = 7)
        rule(2, "two", accountId = null)

        migrate()

        assertEquals(2, count("rule_actions"))
    }

    @Test
    fun `they still point at the rules they belong to`() {
        rule(1, "one", accountId = 7)
        rule(2, "two", accountId = null)

        migrate()

        assertEquals(
            1,
            query("SELECT COUNT(*) FROM rule_conditions WHERE ruleId = 2") { it.getInt(0) }
        )
    }

    @Test
    fun `every rule comes across`() {
        rule(1, "one", accountId = 7)
        rule(2, "two", accountId = null)
        rule(3, "three", accountId = 9)

        migrate()

        assertEquals(3, count("rules"))
    }

    /** Ids are what the children point at, so they cannot be renumbered. */
    @Test
    fun `the rules keep their ids`() {
        rule(4, "four", accountId = 7)

        migrate()

        assertEquals(4, query("SELECT id FROM rules") { it.getInt(0) })
    }

    @Test
    fun `everything else about a rule comes across untouched`() {
        rule(1, "keep me", accountId = 7)

        migrate()

        db.query("SELECT name, folderPath, matchMode, matchCount FROM rules WHERE id = 1").use {
            it.moveToFirst()
            assertEquals("keep me", it.getString(0))
            assertEquals("INBOX", it.getString(1))
            assertEquals("ALL", it.getString(2))
            assertEquals(3, it.getInt(3))
        }
    }

    /** Nothing of the old table may be left behind for the next migration to trip on. */
    @Test
    fun `the table it was rebuilt from is gone`() {
        rule(1, "one", accountId = 7)

        migrate()

        assertEquals(
            0,
            query("SELECT COUNT(*) FROM sqlite_master WHERE name = 'rules_old'") { it.getInt(0) }
        )
    }

    /**
     * The other half: that what the migration leaves behind is the schema Room
     * expects to find.
     *
     * Room validates every table on open and refuses a mismatch, and the
     * production database is built with a destructive fallback — so a migration
     * whose CREATE TABLE is one column, one constraint or one index out would
     * not fail loudly, it would wipe the mailbox cache and every rule and start
     * again. Rather than hand-write the whole of version 10 to open Room over
     * it, this compares the three rebuilt tables against the same tables in a
     * database Room has just created from the entities themselves: if they
     * match, Room will accept them.
     */
    @Test
    fun `the rebuilt tables are the ones Room would have created`() {
        rule(1, "one", accountId = 7)

        migrate()

        val fresh = TestDatabase.open()
        try {
            val expected = fresh.openHelper.writableDatabase
            listOf("rules", "rule_conditions", "rule_actions").forEach { table ->
                assertEquals(
                    "$table does not match what Room builds",
                    TableInfo.read(expected, table),
                    TableInfo.read(db, table)
                )
            }
        } finally {
            fresh.close()
        }
    }

    private companion object {
        const val NAME = "migration-10-11-test.db"
    }
}
