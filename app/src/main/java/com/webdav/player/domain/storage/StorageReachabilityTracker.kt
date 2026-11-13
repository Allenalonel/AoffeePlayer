package com.webdav.player.domain.storage

import com.webdav.player.domain.model.InactiveReason
import com.webdav.player.domain.model.TrackIdentity
import kotlinx.coroutines.flow.StateFlow

interface StorageReachabilityTracker {
    val inactiveTracks: StateFlow<Map<TrackIdentity, InactiveReason>>
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
