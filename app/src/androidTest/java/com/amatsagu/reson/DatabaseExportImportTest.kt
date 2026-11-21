package com.amatsagu.reson

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.amatsagu.reson.data.local.DatabaseManager
import com.amatsagu.reson.model.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class DatabaseExportImportTest {

    @Test
    fun testExportAndImportDatabase_preservesStateBeforeAndAfter() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbManager = DatabaseManager.getInstance(context)

        // 1. Initial backup of database to restore state at the end
        val originalBackupOut = ByteArrayOutputStream()
        val originalExportResult = dbManager.exportDatabase(originalBackupOut)
        assertTrue("Original export must succeed", originalExportResult.isSuccess)
        val originalBackupBytes = originalBackupOut.toByteArray()
        assertTrue("Backup size must be > 0", originalBackupBytes.isNotEmpty())

        try {
            // Check baseline stats
            val statsBefore = dbManager.getStats()
            val initialFollowedCount = statsBefore.followedChannelCount
            val initialVideoCount = statsBefore.totalVideoCount

            // 2. Export baseline state to test stream
            val testExportOut = ByteArrayOutputStream()
            val exportResult = dbManager.exportDatabase(testExportOut)
            assertTrue("Export database must succeed", exportResult.isSuccess)
            val exportedBytes = testExportOut.toByteArray()

            // Verify SQLite magic header in exported bytes
            val magic = String(exportedBytes.take(16).toByteArray(), Charsets.US_ASCII)
            assertTrue("Exported stream must have SQLite header", magic.startsWith("SQLite format 3"))

            // 3. Mutate database: insert a canary channel
            val canaryChannel = Channel(
                id = "canary_channel_export_import_test",
                title = "Canary Test Channel",
                handle = "@canary_test",
                isFollowed = true,
                followedAt = System.currentTimeMillis(),
            )
            dbManager.followChannel(canaryChannel)

            // Verify state is altered (BEFORE import)
            val channelsWithCanary = dbManager.getFollowedChannels()
            assertTrue(
                "Canary channel should exist in database before import",
                channelsWithCanary.any { it.id == "canary_channel_export_import_test" },
            )
            val statsWithCanary = dbManager.getStats()
            assertEquals(
                "Followed count should increase by 1 before import",
                initialFollowedCount + 1,
                statsWithCanary.followedChannelCount,
            )

            // 4. Test invalid import: corrupt bytes must fail and not corrupt existing database
            val corruptBytes = "NOT_A_SQLITE_FILE_CONTENT_AT_ALL".toByteArray()
            val corruptImportResult = dbManager.importDatabase(ByteArrayInputStream(corruptBytes))
            assertTrue("Corrupt stream import must fail", corruptImportResult.isFailure)

            // Database must still be operable and canary channel must still be there
            val channelsAfterFailedImport = dbManager.getFollowedChannels()
            assertTrue(
                "Database must remain intact after failed import",
                channelsAfterFailedImport.any { it.id == "canary_channel_export_import_test" },
            )

            // 5. Import the baseline exported database (AFTER import)
            val importResult = dbManager.importDatabase(ByteArrayInputStream(exportedBytes))
            assertTrue("Import database must succeed", importResult.isSuccess)

            // 6. Verify state AFTER import: exactly matches baseline, canary channel is gone
            val restoredChannels = dbManager.getFollowedChannels()
            assertFalse(
                "Canary channel must be gone after restoring exported database",
                restoredChannels.any { it.id == "canary_channel_export_import_test" },
            )
            val statsAfter = dbManager.getStats()
            assertEquals(
                "Followed channel count after import must match baseline",
                initialFollowedCount,
                statsAfter.followedChannelCount,
            )
            assertEquals(
                "Total video count after import must match baseline",
                initialVideoCount,
                statsAfter.totalVideoCount,
            )
        } finally {
            // Restore original database
            dbManager.importDatabase(ByteArrayInputStream(originalBackupBytes))
        }
    }
}
