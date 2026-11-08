package com.webdav.player.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        WebDavServerEntity::class,
        TrackMetadataEntity::class,
        DirectoryCacheEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        LocalStorageFolderEntity::class,
        MediaTrackIndexEntity::class,
        DirectorySyncCheckpointEntity::class,
    ],
    version = 8,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun webDavServerDao(): WebDavServerDao

    abstract fun trackMetadataDao(): TrackMetadataDao

    abstract fun directoryCacheDao(): DirectoryCacheDao

    abstract fun playlistDao(): PlaylistDao

    abstract fun localStorageFolderDao(): LocalStorageFolderDao

    abstract fun mediaTrackIndexDao(): MediaTrackIndexDao

    abstract fun directorySyncCheckpointDao(): DirectorySyncCheckpointDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
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
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_directory_cache_serverId` ON `directory_cache` (`serverId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_directory_cache_serverId_path` ON `directory_cache` (`serverId`, `path`)")
                }
            }

        val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
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
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_playlist_tracks_playlistId_sortOrder` ON `playlist_tracks` (`playlistId`, `sortOrder`)",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_directory_cache_path` ON `directory_cache` (`path`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_track_metadata_title` ON `track_metadata` (`title`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_track_metadata_artist` ON `track_metadata` (`artist`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_track_metadata_album` ON `track_metadata` (`album`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_track_metadata_remotePath` ON `track_metadata` (`remotePath`)")
                }
            }

        val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
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
            }

        val MIGRATION_6_7 =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
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
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_media_track_index_serverId_fileName` ON `media_track_index` (`serverId`, `fileName`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_media_track_index_fileName` ON `media_track_index` (`fileName`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_media_track_index_serverId_remotePath` ON `media_track_index` (`serverId`, `remotePath`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_media_track_index_parsedTitle` ON `media_track_index` (`parsedTitle`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_media_track_index_parsedArtist` ON `media_track_index` (`parsedArtist`)",
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `directory_sync_checkpoint` (
                            `serverId` INTEGER NOT NULL,
                            `path` TEXT NOT NULL,
                            `lastModified` INTEGER NOT NULL,
                            `etag` TEXT,
                            `lastScannedMs` INTEGER NOT NULL,
                            PRIMARY KEY(`serverId`, `path`)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_directory_sync_checkpoint_serverId` ON `directory_sync_checkpoint` (`serverId`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_directory_sync_checkpoint_serverId_path` ON `directory_sync_checkpoint` (`serverId`, `path`)",
                    )
                }
            }

        val MIGRATION_7_8 =
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // Recreate playlist_tracks without cuePath and virtualTrackId, purging virtual tracks
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `playlist_tracks_new` (
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
                            `isInactive` INTEGER NOT NULL DEFAULT 0,
                            `inactiveReason` TEXT,
                            FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        INSERT INTO `playlist_tracks_new` (
                            `id`, `playlistId`, `serverId`, `remotePath`, `sortOrder`, `title`, `artist`, `album`, `durationMs`, `size`, `format`, `coverThumbnailPath`, `isInactive`, `inactiveReason`
                        )
                        SELECT `id`, `playlistId`, `serverId`, `remotePath`, `sortOrder`, `title`, `artist`, `album`, `durationMs`, `size`, `format`, `coverThumbnailPath`, `isInactive`, `inactiveReason`
                        FROM `playlist_tracks`
                        WHERE `remotePath` NOT LIKE '%#cue_%' AND `virtualTrackId` IS NULL
                        """.trimIndent(),
                    )
                    db.execSQL("DROP TABLE `playlist_tracks`")
                    db.execSQL("ALTER TABLE `playlist_tracks_new` RENAME TO `playlist_tracks`")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_tracks_playlistId` ON `playlist_tracks` (`playlistId`)")
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_playlist_tracks_playlistId_sortOrder` ON `playlist_tracks` (`playlistId`, `sortOrder`)",
                    )

                    // Recreate media_track_index without isCue, purging isCue = 1 and #cue_ rows
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `media_track_index_new` (
                            `serverId` INTEGER NOT NULL,
                            `remotePath` TEXT NOT NULL,
                            `fileName` TEXT NOT NULL,
                            `fileSize` INTEGER NOT NULL,
                            `lastModified` INTEGER NOT NULL,
                            `format` TEXT NOT NULL,
                            `parentPath` TEXT NOT NULL,
                            `parsedTitle` TEXT NOT NULL,
                            `parsedArtist` TEXT,
                            PRIMARY KEY(`serverId`, `remotePath`)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        INSERT INTO `media_track_index_new` (
                            `serverId`, `remotePath`, `fileName`, `fileSize`, `lastModified`, `format`, `parentPath`, `parsedTitle`, `parsedArtist`
                        )
                        SELECT `serverId`, `remotePath`, `fileName`, `fileSize`, `lastModified`, `format`, `parentPath`, `parsedTitle`, `parsedArtist`
                        FROM `media_track_index`
                        WHERE `isCue` = 0 AND `remotePath` NOT LIKE '%#cue_%'
                        """.trimIndent(),
                    )
                    db.execSQL("DROP TABLE `media_track_index`")
                    db.execSQL("ALTER TABLE `media_track_index_new` RENAME TO `media_track_index`")
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_media_track_index_serverId_fileName` ON `media_track_index` (`serverId`, `fileName`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_media_track_index_fileName` ON `media_track_index` (`fileName`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_media_track_index_serverId_remotePath` ON `media_track_index` (`serverId`, `remotePath`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_media_track_index_parsedTitle` ON `media_track_index` (`parsedTitle`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_media_track_index_parsedArtist` ON `media_track_index` (`parsedArtist`)",
                    )
                }
            }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                val instance =
                    Room
                        .databaseBuilder(
                            context.applicationContext,
                            AppDatabase::class.java,
                            "webdav_player.db",
                        ).addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                        .fallbackToDestructiveMigration()
                        .build()
                INSTANCE = instance
                instance
            }
    }
}
