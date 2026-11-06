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
        val cursorFolders = db.query("SELECT uriString, displayName, dateAdded FROM local_storage_folders WHERE uriString = 'content://com.android.externalstorage.documents/tree/primary%3AMusic'")
        assertTrue(cursorFolders.moveToFirst())
        assertEquals("content://com.android.externalstorage.documents/tree/primary%3AMusic", cursorFolders.getString(0))
        assertEquals("Music", cursorFolders.getString(1))
        assertEquals(1700000000000L, cursorFolders.getLong(2))
        cursorFolders.close()

        db.close()
    }
}
