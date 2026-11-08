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

    @Test
    fun migration_7_to_8_purgesVirtualTracksAndMigratesCleanly() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbFile = context.getDatabasePath("test_migration_v8.db")
        dbFile.delete()

        val openHelper =
            FrameworkSQLiteOpenHelperFactory().create(
                androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name("test_migration_v8.db")
                    .callback(
                        object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(7) {
                            override fun onCreate(db: SupportSQLiteDatabase) {
                                // Version 7 schema
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
                                    CREATE TABLE IF NOT EXISTS `playlist_tracks` (
                                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                        `playlistId` INTEGER NOT NULL,
                                        `serverId` INTEGER NOT NULL,
                                        `remotePath` TEXT NOT NULL,
                                        `sortOrder` INTEGER NOT NULL,
                                        `title` TEXT NOT NULL,
                                        `artist` TEXT,
                                        `album` TEXT,
                                        `durationMs` INTEGER NOT NULL,
                                        `size` INTEGER NOT NULL,
                                        `format` TEXT NOT NULL,
                                        `coverThumbnailPath` TEXT,
                                        `cuePath` TEXT,
                                        `virtualTrackId` TEXT,
                                        `isInactive` INTEGER NOT NULL DEFAULT 0,
                                        `inactiveReason` TEXT,
                                        FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                                    )
                                    """.trimIndent(),
                                )
                                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_tracks_playlistId` ON `playlist_tracks` (`playlistId`)")
                                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_tracks_playlistId_sortOrder` ON `playlist_tracks` (`playlistId`, `sortOrder`)")

                                db.execSQL(
                                    """
                                    CREATE TABLE IF NOT EXISTS `media_track_index` (
                                        `serverId` INTEGER NOT NULL,
                                        `remotePath` TEXT NOT NULL,
                                        `fileName` TEXT NOT NULL,
                                        `fileSize` INTEGER NOT NULL,
                                        `lastModified` INTEGER NOT NULL,
                                        `format` TEXT NOT NULL,
                                        `isCue` INTEGER NOT NULL,
                                        `parentPath` TEXT NOT NULL,
                                        `parsedTitle` TEXT NOT NULL,
                                        `parsedArtist` TEXT,
                                        PRIMARY KEY(`serverId`, `remotePath`)
                                    )
                                    """.trimIndent(),
                                )
                                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_track_index_serverId_fileName` ON `media_track_index` (`serverId`, `fileName`)")
                                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_track_index_fileName` ON `media_track_index` (`fileName`)")
                                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_track_index_serverId_remotePath` ON `media_track_index` (`serverId`, `remotePath`)")
                                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_track_index_parsedTitle` ON `media_track_index` (`parsedTitle`)")
                                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_track_index_parsedArtist` ON `media_track_index` (`parsedArtist`)")
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

        // Insert playlist
        db.execSQL("INSERT INTO playlists (id, name, createdAtMs, updatedAtMs) VALUES (1, 'Favorites', 1000, 1000)")

        // Insert playlist_tracks:
        // 1. Authentic track (should be preserved with exact metadata)
        db.execSQL(
            """
            INSERT INTO playlist_tracks (id, playlistId, serverId, remotePath, sortOrder, title, artist, album, durationMs, size, format, coverThumbnailPath, cuePath, virtualTrackId, isInactive, inactiveReason)
            VALUES (1, 1, 10, '/music/song1.flac', 0, 'Song 1', 'Artist A', 'Album X', 180000, 25000000, 'FLAC', '/covers/1.jpg', null, null, 0, null)
            """.trimIndent(),
        )
        // 2. CUE virtual track with #cue_ (must be purged)
        db.execSQL(
            """
            INSERT INTO playlist_tracks (id, playlistId, serverId, remotePath, sortOrder, title, artist, album, durationMs, size, format, coverThumbnailPath, cuePath, virtualTrackId, isInactive, inactiveReason)
            VALUES (2, 1, 10, '/music/album.flac#cue_1', 1, 'Virtual Track 1', 'Artist A', 'Album X', 60000, 25000000, 'FLAC', null, '/music/album.cue', 'vt1', 0, null)
            """.trimIndent(),
        )
        // 3. Track with virtualTrackId set (must be purged)
        db.execSQL(
            """
            INSERT INTO playlist_tracks (id, playlistId, serverId, remotePath, sortOrder, title, artist, album, durationMs, size, format, coverThumbnailPath, cuePath, virtualTrackId, isInactive, inactiveReason)
            VALUES (3, 1, 10, '/music/other.flac', 2, 'Virtual Track 2', 'Artist B', 'Album Y', 75000, 30000000, 'FLAC', null, '/music/other.cue', 'vt2', 0, null)
            """.trimIndent(),
        )
        // 4. Another authentic track (must be preserved with exact metadata)
        db.execSQL(
            """
            INSERT INTO playlist_tracks (id, playlistId, serverId, remotePath, sortOrder, title, artist, album, durationMs, size, format, coverThumbnailPath, cuePath, virtualTrackId, isInactive, inactiveReason)
            VALUES (4, 1, 10, '/music/song2.mp3', 3, 'Song 2', 'Artist C', 'Album Z', 210000, 8000000, 'MP3', null, null, null, 1, 'FILE_NOT_FOUND')
            """.trimIndent(),
        )

        // Insert media_track_index:
        // 1. Authentic track (should be preserved)
        db.execSQL(
            """
            INSERT INTO media_track_index (serverId, remotePath, fileName, fileSize, lastModified, format, isCue, parentPath, parsedTitle, parsedArtist)
            VALUES (10, '/music/song1.flac', 'song1.flac', 25000000, 1600000000, 'FLAC', 0, '/music/', 'Song 1', 'Artist A')
            """.trimIndent(),
        )
        // 2. CUE virtual track row with isCue = 1 and #cue_ (must be purged)
        db.execSQL(
            """
            INSERT INTO media_track_index (serverId, remotePath, fileName, fileSize, lastModified, format, isCue, parentPath, parsedTitle, parsedArtist)
            VALUES (10, '/music/album.flac#cue_1', 'album.flac', 50000000, 1600000000, 'FLAC', 1, '/music/', 'Virtual 1', 'Artist A')
            """.trimIndent(),
        )
        // 3. CUE sheet file index with isCue = 1 (must be purged)
        db.execSQL(
            """
            INSERT INTO media_track_index (serverId, remotePath, fileName, fileSize, lastModified, format, isCue, parentPath, parsedTitle, parsedArtist)
            VALUES (10, '/music/album.cue', 'album.cue', 1024, 1600000000, 'CUE', 1, '/music/', 'album', null)
            """.trimIndent(),
        )
        // 4. Row with #cue_ in remotePath even if isCue = 0 (must be purged)
        db.execSQL(
            """
            INSERT INTO media_track_index (serverId, remotePath, fileName, fileSize, lastModified, format, isCue, parentPath, parsedTitle, parsedArtist)
            VALUES (10, '/music/bad.flac#cue_2', 'bad.flac', 100, 1600000000, 'FLAC', 0, '/music/', 'Bad', null)
            """.trimIndent(),
        )
        // 5. Authentic track 2 (should be preserved)
        db.execSQL(
            """
            INSERT INTO media_track_index (serverId, remotePath, fileName, fileSize, lastModified, format, isCue, parentPath, parsedTitle, parsedArtist)
            VALUES (10, '/music/song2.mp3', 'song2.mp3', 8000000, 1600000500, 'MP3', 0, '/music/', 'Song 2', 'Artist C')
            """.trimIndent(),
        )

        // Run MIGRATION_7_8
        AppDatabase.MIGRATION_7_8.migrate(db)

        // --- Verify playlist_tracks migration ---
        val cursorTracks =
            db.query(
                "SELECT id, playlistId, serverId, remotePath, sortOrder, title, artist, album, durationMs, size, format, coverThumbnailPath, isInactive, inactiveReason FROM playlist_tracks ORDER BY id ASC",
            )
        assertEquals(2, cursorTracks.count)

        assertTrue(cursorTracks.moveToFirst())
        assertEquals(1L, cursorTracks.getLong(0))
        assertEquals(1L, cursorTracks.getLong(1))
        assertEquals(10L, cursorTracks.getLong(2))
        assertEquals("/music/song1.flac", cursorTracks.getString(3))
        assertEquals(0, cursorTracks.getInt(4))
        assertEquals("Song 1", cursorTracks.getString(5))
        assertEquals("Artist A", cursorTracks.getString(6))
        assertEquals("Album X", cursorTracks.getString(7))
        assertEquals(180000L, cursorTracks.getLong(8))
        assertEquals(25000000L, cursorTracks.getLong(9))
        assertEquals("FLAC", cursorTracks.getString(10))
        assertEquals("/covers/1.jpg", cursorTracks.getString(11))
        assertEquals(0, cursorTracks.getInt(12))
        assertTrue(cursorTracks.isNull(13))

        assertTrue(cursorTracks.moveToNext())
        assertEquals(4L, cursorTracks.getLong(0))
        assertEquals(1L, cursorTracks.getLong(1))
        assertEquals(10L, cursorTracks.getLong(2))
        assertEquals("/music/song2.mp3", cursorTracks.getString(3))
        assertEquals(3, cursorTracks.getInt(4))
        assertEquals("Song 2", cursorTracks.getString(5))
        assertEquals("Artist C", cursorTracks.getString(6))
        assertEquals("Album Z", cursorTracks.getString(7))
        assertEquals(210000L, cursorTracks.getLong(8))
        assertEquals(8000000L, cursorTracks.getLong(9))
        assertEquals("MP3", cursorTracks.getString(10))
        assertTrue(cursorTracks.isNull(11))
        assertEquals(1, cursorTracks.getInt(12))
        assertEquals("FILE_NOT_FOUND", cursorTracks.getString(13))
        cursorTracks.close()

        // Verify columns in playlist_tracks do not include cuePath or virtualTrackId
        val cursorPlaylistColumns = db.query("PRAGMA table_info('playlist_tracks')")
        val playlistColumns = mutableListOf<String>()
        while (cursorPlaylistColumns.moveToNext()) {
            playlistColumns.add(cursorPlaylistColumns.getString(1))
        }
        cursorPlaylistColumns.close()
        assertFalse("cuePath column should not exist", playlistColumns.contains("cuePath"))
        assertFalse("virtualTrackId column should not exist", playlistColumns.contains("virtualTrackId"))
        assertTrue("id column exists", playlistColumns.contains("id"))
        assertTrue("playlistId column exists", playlistColumns.contains("playlistId"))
        assertTrue("remotePath column exists", playlistColumns.contains("remotePath"))
        assertTrue("isInactive column exists", playlistColumns.contains("isInactive"))

        // Verify indexes on playlist_tracks
        val cursorPlaylistIndices = db.query("PRAGMA index_list('playlist_tracks')")
        val playlistIndices = mutableListOf<String>()
        while (cursorPlaylistIndices.moveToNext()) {
            playlistIndices.add(cursorPlaylistIndices.getString(1))
        }
        cursorPlaylistIndices.close()
        assertTrue("Index on playlistId exists", playlistIndices.contains("index_playlist_tracks_playlistId"))
        assertTrue("Index on playlistId, sortOrder exists", playlistIndices.contains("index_playlist_tracks_playlistId_sortOrder"))

        // --- Verify media_track_index migration ---
        val cursorIndex =
            db.query(
                "SELECT serverId, remotePath, fileName, fileSize, lastModified, format, parentPath, parsedTitle, parsedArtist FROM media_track_index ORDER BY remotePath ASC",
            )
        assertEquals(2, cursorIndex.count)

        assertTrue(cursorIndex.moveToFirst())
        assertEquals(10L, cursorIndex.getLong(0))
        assertEquals("/music/song1.flac", cursorIndex.getString(1))
        assertEquals("song1.flac", cursorIndex.getString(2))
        assertEquals(25000000L, cursorIndex.getLong(3))
        assertEquals(1600000000L, cursorIndex.getLong(4))
        assertEquals("FLAC", cursorIndex.getString(5))
        assertEquals("/music/", cursorIndex.getString(6))
        assertEquals("Song 1", cursorIndex.getString(7))
        assertEquals("Artist A", cursorIndex.getString(8))

        assertTrue(cursorIndex.moveToNext())
        assertEquals(10L, cursorIndex.getLong(0))
        assertEquals("/music/song2.mp3", cursorIndex.getString(1))
        assertEquals("song2.mp3", cursorIndex.getString(2))
        assertEquals(8000000L, cursorIndex.getLong(3))
        assertEquals(1600000500L, cursorIndex.getLong(4))
        assertEquals("MP3", cursorIndex.getString(5))
        assertEquals("/music/", cursorIndex.getString(6))
        assertEquals("Song 2", cursorIndex.getString(7))
        assertEquals("Artist C", cursorIndex.getString(8))
        cursorIndex.close()

        // Verify columns in media_track_index do not include isCue
        val cursorIndexColumns = db.query("PRAGMA table_info('media_track_index')")
        val indexColumns = mutableListOf<String>()
        while (cursorIndexColumns.moveToNext()) {
            indexColumns.add(cursorIndexColumns.getString(1))
        }
        cursorIndexColumns.close()
        assertFalse("isCue column should not exist", indexColumns.contains("isCue"))
        assertTrue("serverId column exists", indexColumns.contains("serverId"))
        assertTrue("remotePath column exists", indexColumns.contains("remotePath"))
        assertTrue("parsedTitle column exists", indexColumns.contains("parsedTitle"))

        // Verify indexes on media_track_index
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

        // Verify write capability
        db.execSQL(
            """
            INSERT INTO playlist_tracks (id, playlistId, serverId, remotePath, sortOrder, title, artist, album, durationMs, size, format, coverThumbnailPath, isInactive, inactiveReason)
            VALUES (10, 1, 10, '/music/song3.flac', 4, 'Song 3', 'Artist D', 'Album W', 300000, 35000000, 'FLAC', null, 0, null)
            """.trimIndent(),
        )
        val cursorWriteTest = db.query("SELECT title FROM playlist_tracks WHERE id = 10")
        assertTrue(cursorWriteTest.moveToFirst())
        assertEquals("Song 3", cursorWriteTest.getString(0))
        cursorWriteTest.close()

        db.close()
    }
}
