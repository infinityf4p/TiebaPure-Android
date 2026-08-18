package dev.infinityf4p.tiebapure.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TiebaPureDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TiebaPureDatabase::class.java,
    )

    @Test
    @Throws(IOException::class)
    fun migrationFromOneDropsOnlyPreReleaseDrafts() {
        helper.createDatabase(DATABASE_NAME, 1).apply {
            execSQL(
                "INSERT INTO content_drafts (account_id,target_key,title,body,images_blob,updated_at_ms) VALUES ('a','t','title','body',X'01',1)",
            )
            execSQL(
                "INSERT INTO browsing_history (thread_id,title,author_name,forum_name,visited_at_ms) VALUES (7,'thread','author','forum',9)",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            2,
            true,
            TiebaPureDatabase.MIGRATION_1_2,
        ).use { database ->
            database.query("SELECT COUNT(*) FROM content_drafts").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
            database.query("SELECT title FROM browsing_history WHERE thread_id = 7").use { cursor ->
                cursor.moveToFirst()
                assertEquals("thread", cursor.getString(0))
            }
        }
    }

    private companion object {
        const val DATABASE_NAME = "draft-migration-test"
    }
}
