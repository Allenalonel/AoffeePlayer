package com.webdav.player.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DatabaseMigrationTest {
    @Test
    fun migration_4_to_5_createsPlaylistsAndTracksTablesPreservingExistingData() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbFile = context.getDatabasePath("test_migration.db")
        dbFile.delete()

        val openHelper =
            FrameworkSQLiteOpenHelperFactory().create(
                androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name("test_migration.db")
                    .callback(
                        object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(4) {
                            override fun onCreate(db: SupportSQLiteDatabase) {
                                // Version 4 schema
                                db.execSQL(
                                    """
                                    CREATE TABLE IF NOT EXISTS `servers` (
                                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                        `name` TEXT NOT NULL,
                                        `url` TEXT NOT NULL,
                                        `username` TEXT NOT NULL,
                                        `password` TEXT NOT NULL,
                                        `isDefault` INTEGER NOT NULL,
                                        `allowSelfSigned` INTEGER NOT NULL DEFAULT 0
                                    )
                                    """.trimIndent(),
                                )
                                db.execSQL(
                                    """
                                    CREATE TABLE IF NOT EXISTS `track_metadata` (
                                        `serverId` INTEGER NOT NULL,
                                        `remotePath` TEXT NOT NULL,
                                        `title` TEXT,
                                        `artist` TEXT,
                                        `album` TEXT,
                                        `trackNumber` INTEGER,
                                        `durationMs` INTEGER NOT NULL,
                                        `coverThumbnailPath` TEXT,
                                        `lyrics` TEXT,
                                        PRIMARY KEY(`serverId`, `remotePath`)
                                    )
                                    """.trimIndent(),
                                )
                                db.execSQL(
                                    """
                                    CREATE TABLE IF NOT EXISTS `directory_cache` (
                                        `serverId` INTEGER NOT NULL,
                                        `path` TEXT NOT NULL,
                                        `dataJson` TEXT NOT NULL,
                                        `lastUpdatedMs` INTEGER NOT NULL,
                                        PRIMARY KEY(`serverId`, `path`)
                                    )
                                    """.trimIndent(),
                                )
                            }

                            override fun onUpgrade(
                                db: SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) {}
                        },
                    ).build(),
            )

        val db = openHelper.writableDatabase

        // Insert test data into v4 tables
        db.execSQL(
            "INSERT INTO servers (id, name, url, username, password, isDefault, allowSelfSigned) VALUES (1, 'NAS', 'http://nas', 'u', 'p', 1, 0)",
        )
        db.execSQL("INSERT INTO directory_cache (serverId, path, dataJson, lastUpdatedMs) VALUES (1, '/music', '{\"files\":[]}', 123456)")

        // Run MIGRATION_4_5
        AppDatabase.MIGRATION_4_5.migrate(db)

        // Verify existing v4 data is intact
        val cursorServers = db.query("SELECT name FROM servers WHERE id = 1")
        assertTrue(cursorServers.moveToFirst())
        assertEquals("NAS", cursorServers.getString(0))
        cursorServers.close()

        val cursorCache = db.query("SELECT path FROM directory_cache WHERE serverId = 1")
        assertTrue(cursorCache.moveToFirst())
        assertEquals("/music", cursorCache.getString(0))
        cursorCache.close()

        // Verify playlists table was created and can be written to
        db.execSQL("INSERT INTO playlists (id, name, createdAtMs, updatedAtMs) VALUES (10, '精选集', 1000, 1000)")
        val cursorPlaylists = db.query("SELECT name FROM playlists WHERE id = 10")
        assertTrue(cursorPlaylists.moveToFirst())
        assertEquals("精选集", cursorPlaylists.getString(0))
        cursorPlaylists.close()

        // Verify playlist_tracks table was created and can be written to
        db.execSQL(
            """
            INSERT INTO playlist_tracks (id, playlistId, serverId, remotePath, sortOrder, title, artist, album, durationMs, size, format, coverThumbnailPath, cuePath, virtualTrackId)
            VALUES (100, 10, 1, '/music/song.flac', 0, 'Song 1', 'Artist', 'Album', 200000, 15000000, 'FLAC', null, null, null)
            """.trimIndent(),
        )
        val cursorTracks = db.query("SELECT title, format FROM playlist_tracks WHERE id = 100")
        assertTrue(cursorTracks.moveToFirst())
        assertEquals("Song 1", cursorTracks.getString(0))
        assertEquals("FLAC", cursorTracks.getString(1))
        cursorTracks.close()

        // Verify B-tree indices on directory_cache and track_metadata
        val cursorMetaIndices = db.query("PRAGMA index_list('track_metadata')")
        val metaIndices = mutableListOf<String>()
        while (cursorMetaIndices.moveToNext()) {
            metaIndices.add(cursorMetaIndices.getString(1))
        }
        cursorMetaIndices.close()
        assertTrue("Index on title exists", metaIndices.contains("index_track_metadata_title"))
        assertTrue("Index on artist exists", metaIndices.contains("index_track_metadata_artist"))
        assertTrue("Index on album exists", metaIndices.contains("index_track_metadata_album"))

        val cursorDirIndices = db.query("PRAGMA index_list('directory_cache')")
        val dirIndices = mutableListOf<String>()
        while (cursorDirIndices.moveToNext()) {
            dirIndices.add(cursorDirIndices.getString(1))
        }
        cursorDirIndices.close()
        assertTrue("Index on path exists", dirIndices.contains("index_directory_cache_path"))

        db.close()
    }

    @Test
    fun migration_5_to_6_createsLocalStorageFoldersTablePreservingExistingData() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbFile = context.getDatabasePath("test_migration_v6.db")
        dbFile.delete()

        val openHelper =
            FrameworkSQLiteOpenHelperFactory().create(
                androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name("test_migration_v6.db")
                    .callback(
                        object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(5) {
                            override fun onCreate(db: SupportSQLiteDatabase) {
                                // Version 5 schema
                                db.execSQL(
                                    """
                                    CREATE TABLE IF NOT EXISTS `servers` (
                                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                        `name` TEXT NOT NULL,
                                        `url` TEXT NOT NULL,
                                        `username` TEXT NOT NULL,
                                        `password` TEXT NOT NULL,
                                        `isDefault` INTEGER NOT NULL,
                                        `allowSelfSigned` INTEGER NOT NULL DEFAULT 0
                                    )
                                    """.trimIndent(),
                                )
                                db.execSQL(
                                    """
                                    CREATE TABLE IF NOT EXISTS `playlists` (
                                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                        `name` TEXT NOT NULL,
                                        `createdAtMs` INTEGER NOT NULL,
                                        `updatedAtMs` INTEGER NOT NULL
                                    )
                                    """.trimIndent(),
                                )
                            }

                            override fun onUpgrade(
                                db: SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) {}
                        },
                    ).build(),
            )

        val db = openHelper.writableDatabase

        // Insert test data into v5 tables
        db.execSQL(
            "INSERT INTO servers (id, name, url, username, password, isDefault, allowSelfSigned) VALUES (1, 'My WebDAV', 'http://webdav', 'u', 'p', 1, 0)",
        )
        db.execSQL("INSERT INTO playlists (id, name, createdAtMs, updatedAtMs) VALUES (5, '车载列表', 2000, 2000)")

        // Run MIGRATION_5_6
        AppDatabase.MIGRATION_5_6.migrate(db)

        // Verify existing v5 data is intact
        val cursorServers = db.query("SELECT name FROM servers WHERE id = 1")
        assertTrue(cursorServers.moveToFirst())
        assertEquals("My WebDAV", cursorServers.getString(0))
        cursorServers.close()

        val cursorPlaylists = db.query("SELECT name FROM playlists WHERE id = 5")
        assertTrue(cursorPlaylists.moveToFirst())
        assertEquals("车载列表", cursorPlaylists.getString(0))
        cursorPlaylists.close()

        // Verify local_storage_folders table was created and can be written to
        db.execSQL(
            """
            INSERT INTO local_storage_folders (uriString, displayName, dateAdded)
            VALUES ('content://com.android.externalstorage.documents/tree/primary%3AMusic', 'Music', 1700000000000)
            """.trimIndent(),
        )
        val cursorFolders =
            db.query(
                "SELECT uriString, displayName, dateAdded FROM local_storage_folders WHERE uriString = 'content://com.android.externalstorage.documents/tree/primary%3AMusic'",
            )
        assertTrue(cursorFolders.moveToFirst())
        assertEquals("content://com.android.externalstorage.documents/tree/primary%3AMusic", cursorFolders.getString(0))
        assertEquals("Music", cursorFolders.getString(1))
        assertEquals(1700000000000L, cursorFolders.getLong(2))
        cursorFolders.close()

        db.close()
    }

    @Test
    fun migration_6_to_7_createsMediaTrackIndexAndCheckpointTablesPreservingExistingData() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbFile = context.getDatabasePath("test_migration_v7.db")
        dbFile.delete()

        val openHelper =
            FrameworkSQLiteOpenHelperFactory().create(
                androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name("test_migration_v7.db")
                    .callback(
                        object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(6) {
                            override fun onCreate(db: SupportSQLiteDatabase) {
                                // Version 6 schema
                                db.execSQL(
                                    """
                                    CREATE TABLE IF NOT EXISTS `servers` (
                                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                        `name` TEXT NOT NULL,
                                        `url` TEXT NOT NULL,
                                        `username` TEXT NOT NULL,
                                        `password` TEXT NOT NULL,
                                        `isDefault` INTEGER NOT NULL,
                                        `allowSelfSigned` INTEGER NOT NULL DEFAULT 0
                                    )
                                    """.trimIndent(),
                                )
                                db.execSQL(
                                    """
                                    CREATE TABLE IF NOT EXISTS `playlists` (
                                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                        `name` TEXT NOT NULL,
                                        `createdAtMs` INTEGER NOT NULL,
                                        `updatedAtMs` INTEGER NOT NULL
                                    )
                                    """.trimIndent(),
                                )
                                db.execSQL(
                                    """
                                    CREATE TABLE IF NOT EXISTS `local_storage_folders` (
                                        `uriString` TEXT NOT NULL,
                                        `displayName` TEXT NOT NULL,
                                        `dateAdded` INTEGER NOT NULL,
                                        PRIMARY KEY(`uriString`)
                                    )
                                    """.trimIndent(),
                                )
                            }

                            override fun onUpgrade(
                                db: SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) {}
                        },
                    ).build(),
            )

        val db = openHelper.writableDatabase

        // Insert test data into v6 tables
        db.execSQL(
            "INSERT INTO servers (id, name, url, username, password, isDefault, allowSelfSigned) VALUES (2, 'Remote NAS', 'https://remote', 'admin', 'pass', 1, 1)",
        )
        db.execSQL("INSERT INTO playlists (id, name, createdAtMs, updatedAtMs) VALUES (8, '经典摇滚', 3000, 3000)")
        db.execSQL(
            "INSERT INTO local_storage_folders (uriString, displayName, dateAdded) VALUES ('content://media/music', 'Music', 1800000000000)",
        )

        // Run MIGRATION_6_7
        AppDatabase.MIGRATION_6_7.migrate(db)

        // Verify existing v6 data is preserved
        val cursorServers = db.query("SELECT name FROM servers WHERE id = 2")
        assertTrue(cursorServers.moveToFirst())
        assertEquals("Remote NAS", cursorServers.getString(0))
        cursorServers.close()

        val cursorPlaylists = db.query("SELECT name FROM playlists WHERE id = 8")
        assertTrue(cursorPlaylists.moveToFirst())
        assertEquals("经典摇滚", cursorPlaylists.getString(0))
        cursorPlaylists.close()

        val cursorFolders = db.query("SELECT displayName FROM local_storage_folders WHERE uriString = 'content://media/music'")
        assertTrue(cursorFolders.moveToFirst())
        assertEquals("Music", cursorFolders.getString(0))
        cursorFolders.close()

        // Verify media_track_index table exists and supports inserts
        db.execSQL(
            """
            INSERT INTO media_track_index (
                serverId, remotePath, fileName, fileSize, lastModified, format, isCue, parentPath, parsedTitle, parsedArtist
            ) VALUES (
                0, 'content://media/music/01 - Artist - Song.flac', '01 - Artist - Song.flac', 25000000, 1600000000, 'FLAC', 0, '/local/primary%3AMusic/', 'Song', 'Artist'
            )
            """.trimIndent(),
        )
        val cursorMediaIndex = db.query("SELECT parsedTitle, parsedArtist, format FROM media_track_index WHERE serverId = 0")
        assertTrue(cursorMediaIndex.moveToFirst())
        assertEquals("Song", cursorMediaIndex.getString(0))
        assertEquals("Artist", cursorMediaIndex.getString(1))
        assertEquals("FLAC", cursorMediaIndex.getString(2))
        cursorMediaIndex.close()

        // Verify B-tree indices on media_track_index
        val cursorMediaIndices = db.query("PRAGMA index_list('media_track_index')")
        val mediaIndices = mutableListOf<String>()
        while (cursorMediaIndices.moveToNext()) {
            mediaIndices.add(cursorMediaIndices.getString(1))
        }
        cursorMediaIndices.close()
        assertTrue("Index on serverId, fileName exists", mediaIndices.contains("index_media_track_index_serverId_fileName"))
        assertTrue("Index on fileName exists", mediaIndices.contains("index_media_track_index_fileName"))
        assertTrue("Index on serverId, remotePath exists", mediaIndices.contains("index_media_track_index_serverId_remotePath"))
        assertTrue("Index on parsedTitle exists", mediaIndices.contains("index_media_track_index_parsedTitle"))
        assertTrue("Index on parsedArtist exists", mediaIndices.contains("index_media_track_index_parsedArtist"))

        // Verify directory_sync_checkpoint table exists and supports inserts
        db.execSQL(
            """
            INSERT INTO directory_sync_checkpoint (
                serverId, path, lastModified, etag, lastScannedMs
            ) VALUES (
                0, '/local/primary%3AMusic/', 1600000000, 'etag123', 1700000000000
            )
            """.trimIndent(),
        )
        val cursorCheckpoint =
            db.query(
                "SELECT etag, lastScannedMs FROM directory_sync_checkpoint WHERE serverId = 0 AND path = '/local/primary%3AMusic/'",
            )
        assertTrue(cursorCheckpoint.moveToFirst())
        assertEquals("etag123", cursorCheckpoint.getString(0))
        assertEquals(1700000000000L, cursorCheckpoint.getLong(1))
        cursorCheckpoint.close()

        // Verify B-tree indices on directory_sync_checkpoint
        val cursorCheckpointIndices = db.query("PRAGMA index_list('directory_sync_checkpoint')")
        val checkpointIndices = mutableListOf<String>()
        while (cursorCheckpointIndices.moveToNext()) {
            checkpointIndices.add(cursorCheckpointIndices.getString(1))
        }
        cursorCheckpointIndices.close()
        assertTrue("Index on serverId exists", checkpointIndices.contains("index_directory_sync_checkpoint_serverId"))
        assertTrue("Index on serverId, path exists", checkpointIndices.contains("index_directory_sync_checkpoint_serverId_path"))

        db.close()
    }
}
