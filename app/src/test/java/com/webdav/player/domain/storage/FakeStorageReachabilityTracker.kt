package com.webdav.player.domain.storage

import com.webdav.player.domain.model.InactiveReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class FakeStorageReachabilityTracker : StorageReachabilityTracker {
    private val _inactiveTracks = MutableStateFlow<Map<TrackKey, InactiveReason>>(emptyMap())
    override val inactiveTracks: StateFlow<Map<TrackKey, InactiveReason>> = _inactiveTracks.asStateFlow()

    private val _serverReachability = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    override val serverReachability: StateFlow<Map<Long, Boolean>> = _serverReachability.asStateFlow()

    private val _serverReasons = MutableStateFlow<Map<Long, InactiveReason>>(emptyMap())
    override val serverReasons: StateFlow<Map<Long, InactiveReason>> = _serverReasons.asStateFlow()

    val reportedUnavailableTracks = mutableListOf<Triple<Long, String, InactiveReason>>()
    val reportedUnavailableServers = mutableListOf<Pair<Long, InactiveReason>>()
    val reportedReachableServers = mutableListOf<Long>()
    var selfHealingCallCount = 0

    override fun reportTrackUnavailable(
        serverId: Long,
        remotePath: String,
        reason: InactiveReason,
    ) {
        reportedUnavailableTracks.add(Triple(serverId, remotePath, reason))
        _inactiveTracks.update { current ->
            current + (TrackKey(serverId, remotePath) to reason)
        }
    }

    override fun reportServerUnavailable(
        serverId: Long,
        reason: InactiveReason,
    ) {
        reportedUnavailableServers.add(serverId to reason)
        _serverReachability.update { current ->
            current + (serverId to false)
        }
        _serverReasons.update { current ->
            current + (serverId to reason)
        }
        _inactiveTracks.update { current ->
            current.mapValues { (key, value) ->
                if (key.serverId == serverId && value != InactiveReason.FILE_NOT_FOUND) reason else value
            }
        }
    }

    override fun reportServerReachable(serverId: Long) {
        reportedReachableServers.add(serverId)
        _serverReachability.update { current ->
            current + (serverId to true)
        }
        _serverReasons.update { current ->
            current - serverId
        }
        _inactiveTracks.update { current ->
            current.filterNot {
                it.key.serverId == serverId &&
                    (it.value == InactiveReason.SERVER_UNREACHABLE || it.value == InactiveReason.SERVER_CREDENTIAL_INVALID)
            }
        }
    }

    override suspend fun triggerReachabilitySelfHealing() {
        selfHealingCallCount++
    }

    fun reset() {
        _inactiveTracks.value = emptyMap()
        _serverReachability.value = emptyMap()
        _serverReasons.value = emptyMap()
        reportedUnavailableTracks.clear()
        reportedUnavailableServers.clear()
        reportedReachableServers.clear()
        selfHealingCallCount = 0
    }
}
