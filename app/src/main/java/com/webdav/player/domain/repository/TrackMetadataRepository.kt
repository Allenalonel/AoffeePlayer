package com.webdav.player.domain.repository

import com.webdav.player.domain.model.AudioTrack
import com.webdav.player.domain.model.Lyrics
import com.webdav.player.domain.model.RemoteFile
import com.webdav.player.domain.model.TrackIdentity
import com.webdav.player.domain.model.TrackMetadata
import com.webdav.player.domain.model.WebDavServer
import kotlinx.coroutines.flow.Flow

interface TrackMetadataRepository {
    /**
     * Observes all resolved metadata for a given server.
     */
    fun getAllMetadataFlow(serverId: Long): Flow<List<TrackMetadata>>

    /**
     * Observes resolved metadata for a list of remote paths on a given server.
     */
    fun getMetadataForPathsFlow(
        serverId: Long,
        remotePaths: List<String>,
    ): Flow<List<TrackMetadata>>

    /**
     * Observes resolved metadata for a specific path on a given server.
     */
    fun getMetadataFlow(
        serverId: Long,
        remotePath: String,
    ): Flow<TrackMetadata?>

    /**
     * Retrieves cached metadata directly from the local Room database, or null if not resolved.
     */
    suspend fun getCachedMetadata(
        serverId: Long,
        remotePath: String,
    ): TrackMetadata?

    /**
     * Resolves metadata for remote audio files in the background with bounded concurrency.
     * Uses Room cache to skip previously parsed files, and executes HTTP Range requests for new ones.
     * Optionally accepts directoryFiles to pre-filter candidate external covers without remote 404 network storms.
     */
    suspend fun resolveMetadata(
        server: WebDavServer,
        files: List<RemoteFile>,
        forceRefresh: Boolean = false,
        directoryFiles: Collection<RemoteFile>? = null,
    ) {
        resolveMetadata(server, files, forceRefresh, directoryFiles, null)
    }

    suspend fun resolveMetadata(
        server: WebDavServer,
        files: List<RemoteFile>,
        forceRefresh: Boolean,
        directoryFiles: Collection<RemoteFile>?,
        logicalDirectoryPath: String?,
    ) {
        resolveMetadata(server, files, forceRefresh)
    }

    suspend fun resolveMetadata(
        server: WebDavServer,
        files: List<RemoteFile>,
        forceRefresh: Boolean,
    )

    /**
     * Proactively matches companion artwork and lyrics from directoryFiles manifest
     * in milliseconds, returning an initial TrackMetadata map before embedded tag parsing.
     */
    suspend fun matchCompanionManifest(
        server: WebDavServer,
        files: List<RemoteFile>,
        directoryFiles: Collection<RemoteFile>?,
        logicalDirectoryPath: String? = null,
    ): Map<String, TrackMetadata> = emptyMap()

    /**
     * Resolves metadata for a single audio file on demand.
     */
    suspend fun resolveSingleTrackMetadata(
        server: WebDavServer,
        file: RemoteFile,
        directoryFiles: Collection<RemoteFile>? = null,
    ): TrackMetadata = resolveSingleTrackMetadata(server, file)

    suspend fun resolveSingleTrackMetadata(
        server: WebDavServer,
        file: RemoteFile,
    ): TrackMetadata

    /**
     * Batch queries metadata for a list of track identities and validates cover art physical liveness on disk.
     * For tracks with missing or deleted cover thumbnails that are marked eligible for healing (active/reachable),
     * triggers bounded background healing (both Local SAF and remote WebDAV) and returns valid metadata snapshots.
     * Optionally invokes [onCoverHealed] as background healing tasks complete.
     */
    suspend fun batchQueryAndHealMetadata(
        identities: List<TrackIdentity>,
        eligibleForHealing: Set<TrackIdentity>,
        onCoverHealed: (suspend (TrackIdentity, String) -> Unit)? = null,
    ): Map<TrackIdentity, TrackMetadata> = emptyMap()

    /**
     * Resolves lyrics for an audio track using dual-source priority:
     * 1. Probes remote directory for ${baseName}.lrc via WebDAV GET.
     * 2. If remote .lrc is not found (404/error), falls back to embedded metadata
     *    (ID3 USLT/SYLT, Vorbis comment, ASF WM/Lyrics).
     * 3. Returns Lyrics.EMPTY if neither source provides lyrics.
     */
    suspend fun resolveLyrics(
        server: WebDavServer,
        track: AudioTrack,
    ): Lyrics = Lyrics.EMPTY

    /**
     * Clears all in-memory metadata and lyrics caches.
     */
    fun clearCache() {}

    /**
     * Clears in-memory lyrics, folder artwork, and metadata caches for a specific server.
     */
    fun clearCacheForServer(serverId: Long) {}
}
