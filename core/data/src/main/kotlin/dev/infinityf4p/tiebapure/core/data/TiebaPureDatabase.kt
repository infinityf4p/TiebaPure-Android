package dev.infinityf4p.tiebapure.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BlocklistEntity::class,
        BrowsingHistoryEntity::class,
        ContentDraftEntity::class,
        ReadingPositionEntity::class,
        RecentForumEntity::class,
        SearchHistoryEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class TiebaPureDatabase : RoomDatabase() {
    abstract fun blocklistDao(): BlocklistDao
    abstract fun browsingHistoryDao(): BrowsingHistoryDao
    abstract fun contentDraftDao(): ContentDraftDao
    abstract fun readingPositionDao(): ReadingPositionDao
    abstract fun recentForumDao(): RecentForumDao
    abstract fun searchHistoryDao(): SearchHistoryDao

    companion object {
        @Volatile
        private var instance: TiebaPureDatabase? = null

        fun getInstance(context: Context): TiebaPureDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                TiebaPureDatabase::class.java,
                "tiebapure.db",
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("DROP TABLE IF EXISTS `content_drafts`")
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `content_drafts` (`account_id` TEXT NOT NULL, `target_key` TEXT NOT NULL, `title` TEXT NOT NULL, `body` TEXT NOT NULL, `target_metadata` BLOB NOT NULL, `attachment_file_name` TEXT NOT NULL, `attachment_byte_count` INTEGER NOT NULL, `attachment_sha256` TEXT NOT NULL, `image_count` INTEGER NOT NULL, `updated_at_ms` INTEGER NOT NULL, PRIMARY KEY(`account_id`, `target_key`))""",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_content_drafts_account_id` ON `content_drafts` (`account_id`)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_content_drafts_updated_at_ms` ON `content_drafts` (`updated_at_ms`)",
                )
            }
        }
    }
}
