package de.uwumail.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AccountEntity::class,
        IdentityEntity::class,
        FolderEntity::class,
        MessageEntity::class,
        AttachmentEntity::class,
        BlocklistEntity::class,
        BlocklistEntryEntity::class,
        RuleEntity::class,
        RuleConditionEntity::class,
        RuleActionEntity::class,
        RuleLogEntity::class,
        OutboxEntity::class
    ],
    version = 11,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun identityDao(): IdentityDao
    abstract fun folderDao(): FolderDao
    abstract fun messageDao(): MessageDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun blocklistDao(): BlocklistDao
    abstract fun ruleDao(): RuleDao
    abstract fun outboxDao(): OutboxDao

    companion object {
        /** Adds OAuth columns without discarding existing accounts or cached mail. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE accounts ADD COLUMN authType TEXT NOT NULL DEFAULT 'PASSWORD'"
                )
                db.execSQL("ALTER TABLE accounts ADD COLUMN oauthProvider TEXT")
            }
        }

        /** Optimistic removal, plus per-device folder ordering and hiding. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE messages ADD COLUMN pendingRemoval INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL("ALTER TABLE folders ADD COLUMN sortOverride INTEGER")
                db.execSQL("ALTER TABLE folders ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")

                db.execSQL("ALTER TABLE messages ADD COLUMN senderDomain TEXT")
                db.execSQL(
                    """UPDATE messages
                       SET senderDomain = LOWER(SUBSTR(fromAddress, INSTR(fromAddress, '@') + 1))
                       WHERE fromAddress IS NOT NULL AND INSTR(fromAddress, '@') > 0"""
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_messages_senderDomain " +
                        "ON messages (senderDomain)"
                )

                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS blocklists (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        url TEXT,
                        enabled INTEGER NOT NULL DEFAULT 0,
                        builtIn INTEGER NOT NULL DEFAULT 0,
                        entryCount INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0,
                        lastError TEXT,
                        position INTEGER NOT NULL DEFAULT 0
                    )"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS blocklist_entries (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        listId INTEGER NOT NULL,
                        pattern TEXT NOT NULL,
                        FOREIGN KEY(listId) REFERENCES blocklists(id) ON DELETE CASCADE
                    )"""
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_blocklist_entries_pattern " +
                        "ON blocklist_entries (pattern)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_blocklist_entries_listId " +
                        "ON blocklist_entries (listId)"
                )
            }
        }

        /** Collapses duplicate identities and stops new ones being created. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE identities SET email = LOWER(TRIM(email))")
                // Keep the oldest row per address; a unique index cannot be
                // created while duplicates are still present.
                db.execSQL(
                    """DELETE FROM identities WHERE id NOT IN (
                           SELECT MIN(id) FROM identities GROUP BY accountId, email
                       )"""
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_identities_accountId_email " +
                        "ON identities (accountId, email)"
                )
            }
        }

        /** Turns background push on for accounts created before it was the default. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE accounts SET pushEnabled = 1")
            }
        }

        /** Lets a queued message remember the draft and the mail it answers. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE outbox ADD COLUMN draftMessageId INTEGER")
                db.execSQL("ALTER TABLE outbox ADD COLUMN answeringMessageId INTEGER")
            }
        }

        /**
         * One entry per pattern per list.
         *
         * The insert always said it ignored conflicts and there was nothing for
         * it to conflict with, so blocking a sender twice made two rows to
         * remove separately — and a public list repeating itself counted twice.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """DELETE FROM blocklist_entries WHERE id NOT IN (
                           SELECT MIN(id) FROM blocklist_entries GROUP BY listId, pattern
                       )"""
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_blocklist_entries_listId_pattern " +
                        "ON blocklist_entries (listId, pattern)"
                )
                db.execSQL(
                    """UPDATE blocklists SET entryCount = (
                           SELECT COUNT(*) FROM blocklist_entries WHERE listId = blocklists.id
                       )"""
                )
            }
        }

        /**
         * Lets any folder notify, not just the inbox.
         *
         * Existing mailboxes keep exactly what they had: the inbox notifies and
         * nothing else does, which is what the code did when it was hardcoded.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE folders ADD COLUMN notify INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL("UPDATE folders SET notify = 1 WHERE type = 'INBOX'")
            }
        }

        /**
         * Gathers mail into conversations.
         *
         * The column is left empty and filled in afterwards from each message's
         * stored headers, which SQL cannot read: see
         * [de.uwumail.sync.SyncManager.backfillThreadIds]. Until that has run a
         * message simply has no thread, which the list reads as a conversation
         * of one — the same thing it showed before.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN threadId TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_threadId ON messages (threadId)")
            }
        }

        /**
         * Lets a folder be marked as playing a role the server did not give it,
         * and lets several folders play the same one.
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE folders ADD COLUMN roleOverride TEXT")
            }
        }

        /**
         * Lets a rule name several accounts instead of one.
         *
         * The folders beside it are text and could hold a list already; the
         * account was an integer, so the column has to change type, which
         * SQLite can only do by rebuilding the table. All three tables are
         * rebuilt, and the reason is worth writing down.
         *
         * The conditions and actions have foreign keys onto the rules table
         * with ON DELETE CASCADE. Dropping a parent table performs an implicit
         * delete that fires them, so rebuilding the rules table the obvious way
         * migrates every rule and silently throws away everything each one
         * does. Renaming the old table out of the way first does not help
         * either: this SQLite rewrites the children to follow the rename, even
         * with legacy_alter_table on, so they end up pointing at the table
         * about to be dropped — which is exactly the same cascade by a longer
         * route, and the reason there is a test that fills a version 10
         * database and counts what is left afterwards.
         *
         * So the children are rebuilt too, pointed back at the new rules table,
         * and the old copies are dropped before their parent — a child can be
         * dropped freely, since nothing cascades from it.
         *
         * A rule scoped to one account keeps exactly that account, and one
         * scoped to none keeps none, which has always meant all of them.
         */
        internal val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("PRAGMA defer_foreign_keys = ON")
                db.execSQL("ALTER TABLE rules RENAME TO rules_old")
                db.execSQL("ALTER TABLE rule_conditions RENAME TO rule_conditions_old")
                db.execSQL("ALTER TABLE rule_actions RENAME TO rule_actions_old")

                db.execSQL(
                    """
                    CREATE TABLE rules (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        priority INTEGER NOT NULL,
                        accountIds TEXT,
                        folderPath TEXT,
                        matchMode TEXT NOT NULL,
                        stopProcessing INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        lastMatchedAt INTEGER,
                        matchCount INTEGER NOT NULL
                    )
                    """
                )
                db.execSQL(
                    """
                    INSERT INTO rules (
                        id, name, enabled, priority, accountIds, folderPath,
                        matchMode, stopProcessing, createdAt, lastMatchedAt, matchCount
                    )
                    SELECT id, name, enabled, priority,
                           CASE WHEN accountId IS NULL THEN NULL ELSE CAST(accountId AS TEXT) END,
                           folderPath, matchMode, stopProcessing, createdAt, lastMatchedAt, matchCount
                    FROM rules_old
                    """
                )

                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_rules_priority ON rules (priority)"
                )

                db.execSQL(
                    """
                    CREATE TABLE rule_conditions (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        ruleId INTEGER NOT NULL,
                        field TEXT NOT NULL,
                        headerName TEXT,
                        operator TEXT NOT NULL,
                        value TEXT NOT NULL,
                        caseSensitive INTEGER NOT NULL,
                        negate INTEGER NOT NULL,
                        FOREIGN KEY(ruleId) REFERENCES rules(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """
                )
                db.execSQL(
                    """
                    INSERT INTO rule_conditions (
                        id, ruleId, field, headerName, operator, value, caseSensitive, negate
                    )
                    SELECT id, ruleId, field, headerName, operator, value, caseSensitive, negate
                    FROM rule_conditions_old
                    """
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_rule_conditions_ruleId" +
                        " ON rule_conditions (ruleId)"
                )

                db.execSQL(
                    """
                    CREATE TABLE rule_actions (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        ruleId INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        stringArg TEXT,
                        orderIndex INTEGER NOT NULL,
                        FOREIGN KEY(ruleId) REFERENCES rules(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """
                )
                db.execSQL(
                    """
                    INSERT INTO rule_actions (id, ruleId, type, stringArg, orderIndex)
                    SELECT id, ruleId, type, stringArg, orderIndex FROM rule_actions_old
                    """
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_rule_actions_ruleId" +
                        " ON rule_actions (ruleId)"
                )

                // Children first: dropping one cascades nothing, while dropping
                // the parent while they still point at it is the whole problem.
                db.execSQL("DROP TABLE rule_conditions_old")
                db.execSQL("DROP TABLE rule_actions_old")
                db.execSQL("DROP TABLE rules_old")
            }
        }

        /** Every migration, in order, so a test can open a database through them. */
        internal val MIGRATIONS = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
            MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11
        )

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "uwumail.db")
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(*MIGRATIONS)
                .fallbackToDestructiveMigration()
                .build()
    }
}
