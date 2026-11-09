package com.webdav.player.data.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.webdav.player.data.remote.ConnectionResult
import com.webdav.player.data.remote.WebDavClient
import com.webdav.player.domain.model.InactiveReason
import com.webdav.player.domain.model.LOCAL_STORAGE_ID
import com.webdav.player.domain.repository.ServerRepository
import com.webdav.player.domain.storage.StorageReachabilityTracker
import com.webdav.player.domain.storage.TrackKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicBoolean

class StorageReachabilityTrackerImpl(
    private val webDavClient: WebDavClient? = null,
    private val serverRepository: ServerRepository? = null,
    private val context: Context? = null,
    private val localUriReachabilityChecker: ((String) -> Boolean)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : StorageReachabilityTracker {
    private val _inactiveTracks = MutableStateFlow<Map<TrackKey, InactiveReason>>(emptyMap())
    override val inactiveTracks: StateFlow<Map<TrackKey, InactiveReason>> = _inactiveTracks.asStateFlow()

    private val _serverReachability = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    override val serverReachability: StateFlow<Map<Long, Boolean>> = _serverReachability.asStateFlow()

    private val isSelfHealingInProgress = AtomicBoolean(false)

    override fun reportTrackUnavailable(
        serverId: Long,
        remotePath: String,
        reason: InactiveReason,
    ) {
        _inactiveTracks.update { current ->
            current + (TrackKey(serverId, remotePath) to reason)
        }
    }

    override fun reportServerUnavailable(
        serverId: Long,
        reason: InactiveReason,
    ) {
        _serverReachability.update { current ->
            current + (serverId to false)
        }
        _inactiveTracks.update { current ->
            current.mapValues { (key, value) ->
                if (key.serverId == serverId) reason else value
            }
        }
    }

    override fun reportServerReachable(serverId: Long) {
        _serverReachability.update { current ->
            current + (serverId to true)
        }
        _inactiveTracks.update { current ->
            current.filterNot {
                it.key.serverId == serverId &&
                    (it.value == InactiveReason.SERVER_UNREACHABLE || it.value == InactiveReason.SERVER_CREDENTIAL_INVALID)
            }
        }
    }

    fun checkLocalTrackReachability(remotePath: String): Boolean {
        if (localUriReachabilityChecker != null) {
            return localUriReachabilityChecker.invoke(remotePath)
        }
        val ctx = context ?: return false
        return try {
            if (remotePath.startsWith("content://")) {
                val uri = Uri.parse(remotePath)
                val cursor =
                    try {
                        ctx.contentResolver.query(
                            uri,
                            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                            null,
                            null,
                            null,
                        )
                    } catch (_: SecurityException) {
                        null
                    } catch (_: FileNotFoundException) {
                        null
                    }
                val count = cursor?.use { it.count } ?: 0
                if (count > 0) {
                    true
                } else {
                    try {
                        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { true }
                            ?: ctx.contentResolver.openInputStream(uri)?.use { true }
                            ?: false
                    } catch (_: SecurityException) {
                        false
                    } catch (_: FileNotFoundException) {
                        false
                    } catch (_: Throwable) {
                        false
                    }
                }
            } else if (remotePath.startsWith("file://")) {
                val file = File(Uri.parse(remotePath).path ?: "")
                file.exists() && file.canRead()
            } else if (remotePath.startsWith("/")) {
                val file = File(remotePath)
                file.exists() && file.canRead()
            } else {
                false
            }
        } catch (_: SecurityException) {
            false
        } catch (_: FileNotFoundException) {
            false
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun triggerReachabilitySelfHealing() {
        if (!isSelfHealingInProgress.compareAndSet(false, true)) return
        try {
            withContext(ioDispatcher) {
                // 1. Probe WebDav servers that are currently marked unreachable
                if (webDavClient != null && serverRepository != null) {
                    val unreachableServerIds =
                        _serverReachability.value
                            .filter { !it.value && it.key != LOCAL_STORAGE_ID }
                            .keys
                            .toList()

                    if (unreachableServerIds.isNotEmpty()) {
                        coroutineScope {
                            unreachableServerIds
                                .map { serverId ->
                                    async {
                                        val server = serverRepository.getServerById(serverId)
                                        if (server != null && server.url.isNotBlank()) {
                                            val result =
                                                try {
                                                    webDavClient.testConnection(server)
                                                } catch (_: Throwable) {
                                                    ConnectionResult.Failure("Reachability probe failed")
                                                }
                                            if (result is ConnectionResult.Success) {
                                                reportServerReachable(serverId)
                                            }
                                        }
                                    }
                                }.awaitAll()
                        }
                    }
                }

                // 2. Probe local tracks marked unreachable in inactiveTracks
                val localInactiveTracks = _inactiveTracks.value.filter { it.key.serverId == LOCAL_STORAGE_ID }
                for ((trackKey, _) in localInactiveTracks) {
                    val isReachable = checkLocalTrackReachability(trackKey.remotePath)
                    if (isReachable) {
                        _inactiveTracks.update { current ->
                            current - trackKey
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // Ignore transient errors during probe
        } finally {
            isSelfHealingInProgress.set(false)
        }
    }
}
