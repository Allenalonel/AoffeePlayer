package com.webdav.player.domain.storage

import com.webdav.player.domain.model.InactiveReason
import kotlinx.coroutines.flow.StateFlow

data class TrackKey(
    val serverId: Long,
    val remotePath: String,
)

interface StorageReachabilityTracker {
    val inactiveTracks: StateFlow<Map<TrackKey, InactiveReason>>
    val serverReachability: StateFlow<Map<Long, Boolean>>
    val serverReasons: StateFlow<Map<Long, InactiveReason>>

    fun reportTrackUnavailable(
        serverId: Long,
        remotePath: String,
        reason: InactiveReason,
    )

    fun reportServerUnavailable(
        serverId: Long,
        reason: InactiveReason,
    )

    fun reportServerReachable(serverId: Long)

    suspend fun triggerReachabilitySelfHealing()
}
