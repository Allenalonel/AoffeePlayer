package com.webdav.player.data.repository

import android.content.Context
import android.net.Uri
import com.webdav.player.data.local.CoverArtStorage
import com.webdav.player.data.local.TrackMetadataDao
import com.webdav.player.data.local.TrackMetadataEntity
import com.webdav.player.data.local.WebDavServerDao
import com.webdav.player.data.lyrics.LrcParser
import com.webdav.player.data.metadata.DefaultTrackMetadataResolver
import com.webdav.player.data.remote.WebDavClient
import com.webdav.player.domain.model.AudioTrack
import com.webdav.player.domain.model.LOCAL_STORAGE_ID
import com.webdav.player.domain.model.Lyrics
import com.webdav.player.domain.model.RemoteFile
import com.webdav.player.domain.model.TrackIdentity
import com.webdav.player.domain.model.TrackMetadata
import com.webdav.player.domain.model.WebDavServer
import com.webdav.player.domain.model.isLocal
import com.webdav.player.domain.repository.TrackMetadataRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

class TrackMetadataRepositoryImpl internal constructor(
    private val trackMetadataDao: TrackMetadataDao,
    private val trackMetadataResolver: DefaultTrackMetadataResolver,
    private val coverArtStorage: CoverArtStorage,
    private val webDavClient: WebDavClient? = null,
    private val webDavServerDao: WebDavServerDao? = null,
    private val maxConcurrency: Int = 3,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val repositoryScope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher),
    private val serverLookup: (suspend (Long) -> WebDavServer?)? = null,
    private val context: Context? = null,
) : TrackMetadataRepository {
    constructor(
        trackMetadataDao: TrackMetadataDao,
        webDavClient: WebDavClient,
        coverArtStorage: CoverArtStorage,
        webDavServerDao: WebDavServerDao? = null,
        maxConcurrency: Int = 3,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        repositoryScope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher),
        serverLookup: (suspend (Long) -> WebDavServer?)? = null,
        context: Context? = null,
    ) : this(
        trackMetadataDao = trackMetadataDao,
        trackMetadataResolver = DefaultTrackMetadataResolver(webDavClient, coverArtStorage, ioDispatcher, context),
        coverArtStorage = coverArtStorage,
        webDavClient = webDavClient,
        webDavServerDao = webDavServerDao,
        maxConcurrency = maxConcurrency,
        ioDispatcher = ioDispatcher,
        repositoryScope = repositoryScope,
        serverLookup = serverLookup,
        context = context,
    )

    private val semaphore = Semaphore(maxConcurrency)
    private val knownServers = java.util.concurrent.ConcurrentHashMap<Long, WebDavServer>()
    private val inFlightSelfHealing = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val resolvedLyricsCache = java.util.concurrent.ConcurrentHashMap<String, Lyrics>()

    init {
        repositoryScope.launch {
            try {
                trackMetadataDao.cleanupCorruptLocalMetadata()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun findServer(serverId: Long): WebDavServer? {
        if (serverId == LOCAL_STORAGE_ID) {
            return knownServers.computeIfAbsent(LOCAL_STORAGE_ID) {
                WebDavServer.createLocalStorage()
            }
        }
        knownServers[serverId]?.let { return it }
        serverLookup?.invoke(serverId)?.let {
            knownServers[serverId] = it
            return it
        }
        webDavServerDao?.getServerById(serverId)?.toDomain()?.let {
            knownServers[serverId] = it
            return it
        }
        return null
    }

    private fun triggerSelfHealing(
        serverId: Long,
        remotePath: String,
    ) {
        val key = "$serverId:$remotePath"
        val activeJob = inFlightSelfHealing[key]
        if (activeJob != null && activeJob.isActive) return
        if (activeJob != null && !activeJob.isActive) {
            inFlightSelfHealing.remove(key, activeJob)
        }

        val job =
            repositoryScope.launch(start = CoroutineStart.LAZY) {
                try {
                    val server = findServer(serverId) ?: return@launch
                    val fileName = remotePath.substringAfterLast('/').ifBlank { "track" }
                    val file = RemoteFile(name = fileName, path = remotePath)
                    val resolved =
                        semaphore.withPermit {
                            trackMetadataResolver.resolve(server, file)
                        }
                    if (resolved != null) {
                        trackMetadataDao.insertOrUpdate(TrackMetadataEntity.fromDomain(resolved))
                    }
                } catch (_: Exception) {
                    // Ignore transient errors during background self-healing
                } finally {
                    inFlightSelfHealing.remove(key, coroutineContext[Job]!!)
                }
            }

        val existing = inFlightSelfHealing.putIfAbsent(key, job)
        val shouldStart = existing == null || (!existing.isActive && inFlightSelfHealing.replace(key, existing, job))
        if (shouldStart) {
            job.start()
        } else {
            job.cancel()
        }
    }

    private fun mapAndSelfHeal(entity: TrackMetadataEntity?): TrackMetadata? {
        if (entity == null) return null
        val thumbPath = entity.coverThumbnailPath
        return if (thumbPath != null) {
            if (coverArtStorage.isValidThumbnailFile(thumbPath)) {
                entity.toDomain()
            } else {
                triggerSelfHealing(entity.serverId, entity.remotePath)
                entity.toDomain().copy(coverThumbnailPath = null)
            }
        } else {
            entity.toDomain()
        }
    }

    override fun getAllMetadataFlow(serverId: Long): Flow<List<TrackMetadata>> =
        trackMetadataDao.getAllMetadataForServerFlow(serverId).map { list ->
            list.mapNotNull { mapAndSelfHeal(it) }
        }

    override fun getMetadataForPathsFlow(
        serverId: Long,
        remotePaths: List<String>,
    ): Flow<List<TrackMetadata>> =
        trackMetadataDao.getMetadataForPathsFlow(serverId, remotePaths).map { list ->
            list.mapNotNull { mapAndSelfHeal(it) }
        }

    override fun getMetadataFlow(
        serverId: Long,
        remotePath: String,
    ): Flow<TrackMetadata?> =
        trackMetadataDao.getMetadataFlow(serverId, remotePath).map {
            mapAndSelfHeal(it)
        }

    override suspend fun getCachedMetadata(
        serverId: Long,
        remotePath: String,
    ): TrackMetadata? =
        withContext(ioDispatcher) {
            mapAndSelfHeal(trackMetadataDao.getMetadata(serverId, remotePath))
        }

    override suspend fun resolveMetadata(
        server: WebDavServer,
        files: List<RemoteFile>,
        forceRefresh: Boolean,
    ) {
        resolveMetadata(server, files, forceRefresh, null, null)
    }

    override suspend fun resolveMetadata(
        server: WebDavServer,
        files: List<RemoteFile>,
        forceRefresh: Boolean,
        directoryFiles: Collection<RemoteFile>?,
    ) {
        resolveMetadata(server, files, forceRefresh, directoryFiles, null)
    }

    override suspend fun resolveMetadata(
        server: WebDavServer,
        files: List<RemoteFile>,
        forceRefresh: Boolean,
        directoryFiles: Collection<RemoteFile>?,
        logicalDirectoryPath: String?,
    ) = withContext(ioDispatcher) {
        knownServers[server.id] = server
        val audioFiles = files.filter { it.isAudio }
        if (audioFiles.isEmpty()) return@withContext

        // Query what is already cached in Room
        val cachedEntities = trackMetadataDao.getMetadataForPaths(server.id, audioFiles.map { it.path })
        val cachedEntityMap = cachedEntities.associateBy { it.remotePath }

        val toResolve =
            if (forceRefresh) {
                trackMetadataResolver.clearCache()
                audioFiles
            } else {
                audioFiles.filter { file ->
                    val cached = cachedEntityMap[file.path]
                    if (cached == null) {
                        true
                    } else if (cached.coverThumbnailPath != null && !coverArtStorage.isValidThumbnailFile(cached.coverThumbnailPath)) {
                        // Disk cache was deleted (e.g. user cleared app cache): must re-resolve to restore thumbnail!
                        true
                    } else {
                        false
                    }
                }
            }
        if (toResolve.isEmpty()) return@withContext

        val buffer = java.util.Collections.synchronizedList(mutableListOf<TrackMetadataEntity>())
        val batchThreshold = 6

        try {
            coroutineScope {
                toResolve.forEach { file ->
                    launch {
                        val metadata =
                            semaphore.withPermit {
                                trackMetadataResolver.resolve(server, file, directoryFiles, logicalDirectoryPath)
                            } ?: return@launch

                        val existing = cachedEntityMap[file.path]
                        val effectiveCompanionLyrics = metadata.companionLyricsPath ?: existing?.companionLyricsPath
                        val entity =
                            TrackMetadataEntity.fromDomain(
                                metadata.copy(companionLyricsPath = effectiveCompanionLyrics),
                            )
                        val batchToWrite: List<TrackMetadataEntity>? =
                            synchronized(buffer) {
                                buffer.add(entity)
                                if (buffer.size >= batchThreshold) {
                                    val copy = ArrayList(buffer)
                                    buffer.clear()
                                    copy
                                } else {
                                    null
                                }
                            }

                        if (!batchToWrite.isNullOrEmpty()) {
                            trackMetadataDao.insertOrUpdateAll(batchToWrite)
                        }
                    }
                }
            }
        } finally {
            // Flush remaining entries in buffer even if coroutine was cancelled
            withContext(NonCancellable) {
                val remaining =
                    synchronized(buffer) {
                        val copy = ArrayList(buffer)
                        buffer.clear()
                        copy
                    }
                if (remaining.isNotEmpty()) {
                    trackMetadataDao.insertOrUpdateAll(remaining)
                }
            }
        }
    }

    override suspend fun matchCompanionManifest(
        server: WebDavServer,
        files: List<RemoteFile>,
        directoryFiles: Collection<RemoteFile>?,
        logicalDirectoryPath: String?,
    ): Map<String, TrackMetadata> =
        withContext(ioDispatcher) {
            trackMetadataResolver.matchCompanionManifest(server, files, directoryFiles, logicalDirectoryPath)
        }

    override suspend fun resolveSingleTrackMetadata(
        server: WebDavServer,
        file: RemoteFile,
    ): TrackMetadata = resolveSingleTrackMetadata(server, file, null)

    override suspend fun resolveSingleTrackMetadata(
        server: WebDavServer,
        file: RemoteFile,
        directoryFiles: Collection<RemoteFile>?,
    ): TrackMetadata =
        withContext(ioDispatcher) {
            knownServers[server.id] = server
            val entity = trackMetadataDao.getMetadata(server.id, file.path)
            if (entity != null) {
                val thumbPath = entity.coverThumbnailPath
                if (thumbPath == null || coverArtStorage.isValidThumbnailFile(thumbPath)) {
                    return@withContext entity.toDomain()
                }
            }
            val metadata =
                semaphore.withPermit {
                    trackMetadataResolver.resolve(server, file, directoryFiles)
                }
            if (metadata != null) {
                trackMetadataDao.insertOrUpdate(TrackMetadataEntity.fromDomain(metadata))
                metadata
            } else {
                // Transient error: do NOT write permanent failure to Room.
                // Return ephemeral fallback for immediate playback/display.
                TrackMetadata(
                    serverId = server.id,
                    remotePath = file.path,
                    title = file.name.substringBeforeLast('.').ifBlank { file.name },
                    artist = null,
                    album = null,
                    trackNumber = null,
                    durationMs = 0L,
                    coverThumbnailPath = null,
                    lyrics = null,
                )
            }
        }

    override suspend fun batchQueryAndHealMetadata(
        identities: List<TrackIdentity>,
        eligibleForHealing: Set<TrackIdentity>,
        onCoverHealed: (suspend (TrackIdentity, String) -> Unit)?,
    ): Map<TrackIdentity, TrackMetadata> =
        withContext(ioDispatcher) {
            if (identities.isEmpty()) return@withContext emptyMap()

            val byServer = identities.groupBy { it.serverId }
            val resultMap = mutableMapOf<TrackIdentity, TrackMetadata>()

            for ((serverId, group) in byServer) {
                val paths = group.map { it.remotePath }
                val entities = trackMetadataDao.getMetadataForPaths(serverId, paths)
                for (entity in entities) {
                    val domain = entity.toDomain()
                    val thumb = domain.coverThumbnailPath
                    val isCoverLivenessValid = thumb != null && coverArtStorage.isValidThumbnailFile(thumb)
                    val effectiveMeta =
                        if (thumb != null && !isCoverLivenessValid) {
                            domain.copy(coverThumbnailPath = null)
                        } else {
                            domain
                        }
                    resultMap[domain.identity] = effectiveMeta
                }
            }

            // Bounded background healing for eligible tracks with missing or invalid covers
            for (identity in identities) {
                if (identity in eligibleForHealing) {
                    val meta = resultMap[identity]
                    val needsHealing = meta == null || meta.coverThumbnailPath == null
                    if (needsHealing) {
                        scheduleCoverHealing(identity, onCoverHealed)
                    }
                }
            }

            resultMap
        }

    private fun scheduleCoverHealing(
        identity: TrackIdentity,
        onCoverHealed: (suspend (TrackIdentity, String) -> Unit)?,
    ) {
        val key = identity.compositeKey
        val existing = inFlightSelfHealing[key]
        if (existing != null && existing.isActive) return

        val job =
            repositoryScope.launch {
                try {
                    semaphore.withPermit {
                        val server =
                            if (identity.isLocal) {
                                WebDavServer.createLocalStorage()
                            } else {
                                knownServers[identity.serverId]
                                    ?: serverLookup?.invoke(identity.serverId)
                                    ?: webDavServerDao?.getServerById(identity.serverId)?.toDomain()
                            } ?: return@withPermit

                        val fileName = identity.remotePath.substringAfterLast('/').ifBlank { "track" }
                        val file = RemoteFile(name = fileName, path = identity.remotePath)
                        val resolved = trackMetadataResolver.resolve(server, file)
                        val newCover = resolved?.coverThumbnailPath
                        if (!newCover.isNullOrBlank() && coverArtStorage.isValidThumbnailFile(newCover)) {
                            val current = trackMetadataDao.getMetadata(identity.serverId, identity.remotePath)
                            if (current != null) {
                                trackMetadataDao.insertOrUpdate(current.copy(coverThumbnailPath = newCover))
                            } else {
                                trackMetadataDao.insertOrUpdate(TrackMetadataEntity.fromDomain(resolved))
                            }
                            onCoverHealed?.invoke(identity, newCover)
                        }
                    }
                } catch (_: Throwable) {
                    // Ignore transient healing errors
                } finally {
                    inFlightSelfHealing.remove(key)
                }
            }
        inFlightSelfHealing[key] = job
    }

    private fun readLocalText(path: String): String? =
        try {
            when {
                path.startsWith("content://") -> {
                    context
                        ?.contentResolver
                        ?.openInputStream(Uri.parse(path))
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() }
                }
                else -> {
                    val clean = path.removePrefix("file://")
                    val f = File(clean)
                    if (f.exists() && f.length() > 0L) f.readText() else null
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }

    override suspend fun resolveLyrics(
        server: WebDavServer,
        track: AudioTrack,
    ): Lyrics =
        withContext(ioDispatcher) {
            val cacheKey = "${server.id}:${track.remotePath}"
            resolvedLyricsCache[cacheKey]?.let { return@withContext it }

            val cachedMetadata = getCachedMetadata(server.id, track.remotePath)
            val isLocal = server.isLocal || track.isLocal

            // 1. External companion .lrc has absolute first priority
            val companionPath = cachedMetadata?.companionLyricsPath
            if (!companionPath.isNullOrBlank() && companionPath != DefaultTrackMetadataResolver.NO_COMPANION_LRC) {
                val companionText: String? =
                    if (isLocal || companionPath.startsWith("content://") || companionPath.startsWith("file://")) {
                        readLocalText(companionPath)
                    } else {
                        try {
                            webDavClient?.fetchText(server, companionPath)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            null
                        }
                    }

                if (!companionText.isNullOrBlank()) {
                    val parsed = LrcParser.parse(companionText)
                    if (parsed.isNotEmpty) {
                        resolvedLyricsCache[cacheKey] = parsed
                        return@withContext parsed
                    }
                }
            }

            // 1b. Legacy local path compatibility if lyrics column held content:// or file://
            if (isLocal && cachedMetadata?.lyrics != null) {
                val rawLyrics = cachedMetadata.lyrics
                if (rawLyrics.startsWith("content://") || rawLyrics.startsWith("file://") ||
                    (rawLyrics.startsWith("/") && !rawLyrics.startsWith("["))
                ) {
                    val legacyText = readLocalText(rawLyrics)
                    if (!legacyText.isNullOrBlank()) {
                        val parsed = LrcParser.parse(legacyText)
                        if (parsed.isNotEmpty) {
                            resolvedLyricsCache[cacheKey] = parsed
                            return@withContext parsed
                        }
                    }
                }
            }

            // 1c. If remote WebDAV and companionPath was unverified (null), probe WebDAV with negative caching
            if (!isLocal && companionPath == null) {
                val lrcPath = track.remotePath.substringBeforeLast('.') + ".lrc"
                try {
                    val remoteText = webDavClient?.fetchText(server, lrcPath)
                    if (!remoteText.isNullOrBlank()) {
                        val parsed = LrcParser.parse(remoteText)
                        if (parsed.isNotEmpty) {
                            resolvedLyricsCache[cacheKey] = parsed
                            if (cachedMetadata != null) {
                                trackMetadataDao.insertOrUpdate(
                                    TrackMetadataEntity.fromDomain(cachedMetadata.copy(companionLyricsPath = lrcPath)),
                                )
                            }
                            return@withContext parsed
                        }
                    } else {
                        if (cachedMetadata != null) {
                            trackMetadataDao.insertOrUpdate(
                                TrackMetadataEntity.fromDomain(
                                    cachedMetadata.copy(companionLyricsPath = DefaultTrackMetadataResolver.NO_COMPANION_LRC),
                                ),
                            )
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    // Ignore transient network errors
                }
            }

            // 2. Fall back to embedded lyrics (permanent fallback, zero 404 network requests if manifest verified)
            val embeddedText =
                cachedMetadata?.lyrics?.takeIf {
                    it.isNotBlank() && !it.startsWith("content://") && !it.startsWith("file://")
                }
            if (!embeddedText.isNullOrBlank()) {
                val parsed = LrcParser.parse(embeddedText)
                if (parsed.isNotEmpty) {
                    resolvedLyricsCache[cacheKey] = parsed
                    return@withContext parsed
                }
            }

            // 2b. If cachedMetadata was null (cold track never scanned), resolve on demand
            if (cachedMetadata == null) {
                // If local filesystem track, check companion file on disk directly
                if (isLocal && (track.remotePath.startsWith("file://") ||
                        (!track.remotePath.startsWith("content://") && !track.remotePath.contains("://")))
                ) {
                    try {
                        val cleanPath = track.remotePath.removePrefix("file://")
                        val lrcPath = cleanPath.substringBeforeLast('.') + ".lrc"
                        val lrcFile = File(lrcPath)
                        if (lrcFile.exists() && lrcFile.length() > 0L) {
                            val lrcText = lrcFile.readText()
                            if (lrcText.isNotBlank()) {
                                val parsed = LrcParser.parse(lrcText)
                                if (parsed.isNotEmpty) {
                                    resolvedLyricsCache[cacheKey] = parsed
                                    return@withContext parsed
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        // Ignore
                    }
                }

                try {
                    val file = RemoteFile(name = track.title, path = track.remotePath)
                    val resolved = resolveSingleTrackMetadata(server, file)
                    val resolvedCompanion = resolved.companionLyricsPath
                    if (!resolvedCompanion.isNullOrBlank()) {
                        val companionText =
                            if (isLocal) {
                                val clean = resolvedCompanion.removePrefix("file://")
                                val f = File(clean)
                                if (f.exists()) f.readText() else null
                            } else {
                                webDavClient?.fetchText(server, resolvedCompanion)
                            }
                        if (!companionText.isNullOrBlank()) {
                            val parsed = LrcParser.parse(companionText)
                            if (parsed.isNotEmpty) {
                                resolvedLyricsCache[cacheKey] = parsed
                                return@withContext parsed
                            }
                        }
                    }
                    if (!resolved.lyrics.isNullOrBlank()) {
                        val parsed = LrcParser.parse(resolved.lyrics)
                        if (parsed.isNotEmpty) {
                            resolvedLyricsCache[cacheKey] = parsed
                            return@withContext parsed
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    // Ignore error
                }
            }

            // 3. No lyrics available from either source
            val empty = Lyrics.EMPTY
            resolvedLyricsCache[cacheKey] = empty
            empty
        }

    override fun clearCache() {
        resolvedLyricsCache.clear()
        trackMetadataResolver.clearCache()
    }

    override fun clearCacheForServer(serverId: Long) {
        val prefix = "$serverId:"
        val lyricsKeysToRemove = resolvedLyricsCache.keys().toList().filter { it.startsWith(prefix) }
        lyricsKeysToRemove.forEach { resolvedLyricsCache.remove(it) }
        knownServers.remove(serverId)
        trackMetadataResolver.clearCacheForServer(serverId)
    }
}
