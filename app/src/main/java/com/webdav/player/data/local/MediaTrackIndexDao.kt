package com.webdav.player.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaTrackIndexDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tracks: List<MediaTrackIndexEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTracks(tracks: List<MediaTrackIndexEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(track: MediaTrackIndexEntity)

    @Query("DELETE FROM media_track_index WHERE serverId = :serverId")
    suspend fun deleteByServerId(serverId: Long)

    @Query("DELETE FROM media_track_index WHERE serverId = :serverId AND remotePath = :remotePath")
    suspend fun deleteByServerAndPath(
        serverId: Long,
        remotePath: String,
    )

    @Query(
        """
        DELETE FROM media_track_index 
        WHERE serverId = :serverId 
          AND (remotePath LIKE :prefix || '%' OR parentPath LIKE :prefix || '%')
        """,
    )
    suspend fun deleteByServerAndPathPrefix(
        serverId: Long,
        prefix: String,
    )

    @Query(
        """
        SELECT * FROM media_track_index
        WHERE (:serverId IS NULL OR serverId = :serverId)
          AND (
            fileName LIKE '%' || :query || '%'
            OR parsedTitle LIKE '%' || :query || '%'
            OR (parsedArtist IS NOT NULL AND parsedArtist LIKE '%' || :query || '%')
            OR parentPath LIKE '%' || :query || '%'
            OR remotePath LIKE '%' || :query || '%'
          )
        ORDER BY parsedTitle ASC
        """,
    )
    fun search(
        query: String,
        serverId: Long? = null,
    ): Flow<List<MediaTrackIndexEntity>>

    @Query(
        """
        SELECT * FROM media_track_index
        WHERE (:serverId IS NULL OR serverId = :serverId)
          AND (
            fileName LIKE '%' || :query || '%'
            OR parsedTitle LIKE '%' || :query || '%'
            OR (parsedArtist IS NOT NULL AND parsedArtist LIKE '%' || :query || '%')
            OR parentPath LIKE '%' || :query || '%'
            OR remotePath LIKE '%' || :query || '%'
          )
        ORDER BY parsedTitle ASC
        """,
    )
    suspend fun searchList(
        query: String,
        serverId: Long? = null,
    ): List<MediaTrackIndexEntity>

    @Query("SELECT * FROM media_track_index WHERE serverId = :serverId ORDER BY parsedTitle ASC")
    fun getAllByServerId(serverId: Long): Flow<List<MediaTrackIndexEntity>>

    @Query("SELECT * FROM media_track_index WHERE serverId = :serverId ORDER BY parsedTitle ASC")
    suspend fun getTracksByServerId(serverId: Long): List<MediaTrackIndexEntity>

    @Query("SELECT * FROM media_track_index WHERE serverId = :serverId AND remotePath = :remotePath LIMIT 1")
    suspend fun getTrack(
        serverId: Long,
        remotePath: String,
    ): MediaTrackIndexEntity?

    @Query("SELECT * FROM media_track_index WHERE serverId = :serverId AND parentPath = :parentPath")
    suspend fun getTracksByParentPath(
        serverId: Long,
        parentPath: String,
    ): List<MediaTrackIndexEntity>

    @Query("DELETE FROM media_track_index WHERE serverId = :serverId AND remotePath IN (:remotePaths)")
    suspend fun deleteByServerAndPaths(
        serverId: Long,
        remotePaths: List<String>,
    )

    @Query("SELECT COUNT(*) FROM media_track_index WHERE serverId = :serverId")
    suspend fun countByServerId(serverId: Long): Int
}
