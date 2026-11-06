package com.webdav.player.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalStorageFolderDao {
    @Query("SELECT * FROM local_storage_folders ORDER BY dateAdded ASC")
    fun getAllFoldersFlow(): Flow<List<LocalStorageFolderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(entity: LocalStorageFolderEntity)

    @Query("DELETE FROM local_storage_folders WHERE uriString = :uriString")
    suspend fun deleteFolderByUri(uriString: String)
}
