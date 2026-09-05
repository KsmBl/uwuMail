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
        RuleEntity::class,
        RuleConditionEntity::class,
        RuleActionEntity::class,
        RuleLogEntity::class,
        OutboxEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun identityDao(): IdentityDao
    abstract fun folderDao(): FolderDao
    abstract fun messageDao(): MessageDao
    abstract fun attachmentDao(): AttachmentDao
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

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "uwumail.db")
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build()
    }
}
