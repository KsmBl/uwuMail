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
    version = 3,
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

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "uwumail.db")
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build()
    }
}
