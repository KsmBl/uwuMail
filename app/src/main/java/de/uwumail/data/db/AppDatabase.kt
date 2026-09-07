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
    version = 10,
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

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "uwumail.db")
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                    MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10
                )
                .fallbackToDestructiveMigration()
                .build()
    }
}
