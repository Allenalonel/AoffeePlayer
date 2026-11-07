package com.webdav.player.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DirectorySyncCheckpointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(checkpoint: DirectorySyncCheckpointEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCheckpoint(checkpoint: DirectorySyncCheckpointEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(checkpoints: List<DirectorySyncCheckpointEntity>)

    @Query("SELECT * FROM directory_sync_checkpoint WHERE serverId = :serverId AND path = :path LIMIT 1")
    suspend fun getCheckpoint(
        serverId: Long,
        path: String,
    ): DirectorySyncCheckpointEntity?

    @Query("SELECT * FROM directory_sync_checkpoint WHERE serverId = :serverId")
    suspend fun getCheckpointsByServerId(serverId: Long): List<DirectorySyncCheckpointEntity>

    @Query("DELETE FROM directory_sync_checkpoint WHERE serverId = :serverId")
    suspend fun deleteByServerId(serverId: Long)

    @Query("DELETE FROM directory_sync_checkpoint WHERE serverId = :serverId AND path LIKE :prefix || '%'")
    suspend fun deleteByServerAndPathPrefix(
        serverId: Long,
        prefix: String,
    )

    @Query(
        """
        SELECT * FROM directory_sync_checkpoint 
        WHERE (:serverId IS NULL OR serverId = :serverId) 
          AND path LIKE '%' || :query || '%'
        ORDER BY path ASC
        """,
    )
    fun searchCheckpoints(
        query: String,
        serverId: Long? = null,
    ): Flow<List<DirectorySyncCheckpointEntity>>
}
