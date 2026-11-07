package com.webdav.player.data.repository

import android.content.Context
import com.webdav.player.data.local.DirectoryCacheDao
import com.webdav.player.data.local.DirectoryCacheEntity
import com.webdav.player.data.local.DirectorySyncCheckpointDao
import com.webdav.player.data.local.DirectorySyncCheckpointEntity
import com.webdav.player.data.local.MediaTrackIndexDao
import com.webdav.player.data.local.MediaTrackIndexEntity
import com.webdav.player.data.local.RemoteDirectoryJsonSerializer
import com.webdav.player.data.local.TrackMetadataDao
import com.webdav.player.data.local.TrackMetadataEntity
import com.webdav.player.domain.cue.CueSheetResolver
import com.webdav.player.domain.model.AudioFormat
import com.webdav.player.domain.model.AudioTrack
import com.webdav.player.domain.model.LibrarySearchResult
import com.webdav.player.domain.model.RemoteDirectory
import com.webdav.player.domain.model.RemoteFileType
import com.webdav.player.domain.model.WebDavServer
import com.webdav.player.domain.repository.LibrarySearchRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import org.json.JSONArray

class LibrarySearchRepositoryImpl(
    private val directoryCacheDao: DirectoryCacheDao,
    private val trackMetadataDao: TrackMetadataDao,
    private val mediaTrackIndexDao: MediaTrackIndexDao? = null,
    private val directorySyncCheckpointDao: DirectorySyncCheckpointDao? = null,
    private val cueSheetResolver: CueSheetResolver? = null,
    private val context: Context? = null,
    private val prefsName: String = "webdav_search_history",
) : LibrarySearchRepository {
    companion object {
        private const val MAX_HISTORY_SIZE = 20
        private const val KEY_HISTORY_JSON = "history_json"
        private const val MAX_CACHE_ENTRIES = 100
    }

    private val inMemoryHistory = mutableListOf<String>()

    private val prefs by lazy {
        context?.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    }

    private val directoryCache =
        object : LinkedHashMap<String, RemoteDirectory>(MAX_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RemoteDirectory>?): Boolean = size > MAX_CACHE_ENTRIES
        }

    private fun getOrDeserialize(dirEntity: DirectoryCacheEntity): RemoteDirectory? {
        val cacheKey = "${dirEntity.serverId}:${dirEntity.path}:${dirEntity.lastUpdatedMs}:${dirEntity.dataJson.length}"
        synchronized(directoryCache) {
            directoryCache[cacheKey]?.let { return it }
        }
        val deserialized = RemoteDirectoryJsonSerializer.deserialize(dirEntity.dataJson) ?: return null
        synchronized(directoryCache) {
            directoryCache[cacheKey] = deserialized
        }
        return deserialized
    }

    private fun saveHistory(history: List<String>) {
        val sharedPrefs = prefs
        if (sharedPrefs != null) {
            sharedPrefs.edit().putString(KEY_HISTORY_JSON, JSONArray(history).toString()).apply()
        } else {
            synchronized(inMemoryHistory) {
                inMemoryHistory.clear()
                inMemoryHistory.addAll(history)
            }
        }
    }

    override fun getSearchHistory(): List<String> {
        val sharedPrefs = prefs ?: return synchronized(inMemoryHistory) { inMemoryHistory.toList() }
        val raw = sharedPrefs.getString(KEY_HISTORY_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun recordSearchQuery(query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return getSearchHistory()
        val current = getSearchHistory().filter { it != trimmed }
        val updated = (listOf(trimmed) + current).take(MAX_HISTORY_SIZE)
        saveHistory(updated)
        return updated
    }

    override fun removeSearchHistory(query: String): List<String> {
        val updated = getSearchHistory().filter { it != query }
        saveHistory(updated)
        return updated
    }

    override fun clearSearchHistory() {
        val sharedPrefs = prefs
        if (sharedPrefs != null) {
            sharedPrefs.edit().remove(KEY_HISTORY_JSON).apply()
        } else {
            synchronized(inMemoryHistory) {
                inMemoryHistory.clear()
            }
        }
    }

    private fun isMatch(
        query: String,
        vararg candidates: String?,
    ): Boolean = candidates.any { it?.contains(query, ignoreCase = true) == true }

    private fun createAudioTrack(
        serverId: Long,
        remotePath: String,
        fileName: String,
        fileSize: Long,
        format: AudioFormat,
        metadata: TrackMetadataEntity?,
        titleFallback: String? = null,
        artistFallback: String? = null,
        albumFallback: String? = null,
        cuePath: String? = null,
    ): AudioTrack =
        AudioTrack(
            id = "$serverId:$remotePath",
            serverId = serverId,
            remotePath = remotePath,
            title = metadata?.title?.takeIf { it.isNotBlank() } ?: titleFallback?.takeIf { it.isNotBlank() } ?: fileName,
            artist = metadata?.artist?.takeIf { it.isNotBlank() } ?: artistFallback,
            album = metadata?.album?.takeIf { it.isNotBlank() } ?: albumFallback,
            durationMs = metadata?.durationMs ?: 0L,
            size = fileSize,
            format = format,
            coverThumbnailPath = metadata?.coverThumbnailPath,
            cuePath = cuePath,
        )

    override fun search(
        query: String,
        serverId: Long?,
    ): Flow<LibrarySearchResult> {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) {
            return flowOf(LibrarySearchResult())
        }

        val indexFlow: Flow<List<MediaTrackIndexEntity>> =
            mediaTrackIndexDao?.search(cleanQuery, serverId) ?: flowOf(emptyList())
        val checkpointFlow: Flow<List<DirectorySyncCheckpointEntity>> =
            directorySyncCheckpointDao?.searchCheckpoints(cleanQuery, serverId) ?: flowOf(emptyList())
        val dirFlow = directoryCacheDao.searchDirectoryCacheFlow(cleanQuery, serverId)
        val metaFlow =
            if (serverId != null) {
                trackMetadataDao.getAllMetadataForServerFlow(serverId)
            } else {
                trackMetadataDao.getAllMetadataFlow()
            }

        return combine(
            indexFlow,
            checkpointFlow,
            dirFlow,
            metaFlow,
        ) {
            indexedTracks: List<MediaTrackIndexEntity>,
            checkpoints: List<DirectorySyncCheckpointEntity>,
            dirEntities: List<DirectoryCacheEntity>,
            metaEntities: List<TrackMetadataEntity>,
            ->
            val metadataMap = metaEntities.associateBy { "${it.serverId}:${it.remotePath}" }
            val matchedDirs = mutableMapOf<String, RemoteDirectory>()
            val matchedTracks = mutableMapOf<String, AudioTrack>()

            for (cp in checkpoints) {
                if (isMatch(cleanQuery, cp.path)) {
                    val dirName = cp.path.trimEnd('/').substringAfterLast('/')
                    if (dirName.isNotBlank()) {
                        matchedDirs["${cp.serverId}:${cp.path}"] =
                            RemoteDirectory(
                                path = cp.path,
                                name = dirName,
                                subDirectories = emptyList(),
                                files = emptyList(),
                                serverId = cp.serverId,
                            )
                    }
                }
            }

            for (entity in indexedTracks) {
                if (entity.isCue && !entity.remotePath.contains("#cue_")) {
                    // Raw .cue file row, not a playable audio track. Skip from matchedTracks.
                } else if (entity.isCue && entity.remotePath.contains("#cue_")) {
                    val parentAudioPath = entity.remotePath.substringBefore("#cue_")
                    val parentAudioBase = parentAudioPath.substringAfterLast('/').substringBeforeLast('.')
                    val trackNum = entity.remotePath.substringAfter("#cue_").toIntOrNull() ?: 1

                    val parentMeta = metadataMap["${entity.serverId}:$parentAudioPath"]
                    val directMeta = metadataMap["${entity.serverId}:${entity.remotePath}"]
                    val effectiveMeta = directMeta ?: parentMeta

                    val cueFileFromIndex =
                        indexedTracks
                            .firstOrNull {
                                it.serverId == entity.serverId &&
                                    it.parentPath == entity.parentPath &&
                                    it.isCue &&
                                    !it.remotePath.contains("#cue_") &&
                                    it.remotePath
                                        .substringAfterLast('/')
                                        .substringBeforeLast('.')
                                        .equals(parentAudioBase, ignoreCase = true)
                            }?.remotePath ?: indexedTracks
                            .firstOrNull {
                                it.serverId == entity.serverId &&
                                    it.parentPath == entity.parentPath &&
                                    it.isCue &&
                                    !it.remotePath.contains("#cue_")
                            }?.remotePath

                    val cuePath = cueFileFromIndex ?: (parentAudioPath.substringBeforeLast('.') + ".cue")
                    val server = WebDavServer(id = entity.serverId, name = "", url = "", port = 0)
                    val cachedTracks = cueSheetResolver?.getCachedVirtualTracks(server, cuePath, parentAudioPath)
                    val cachedVt = cachedTracks?.firstOrNull { it.trackNumber == trackNum }

                    val format =
                        AudioFormat.fromExtension(entity.format)
                            ?: AudioFormat.fromFileName(parentAudioPath)
                            ?: AudioFormat.FLAC

                    val durationMs =
                        when {
                            cachedVt != null && cachedVt.durationMs > 0L -> cachedVt.durationMs
                            effectiveMeta != null && effectiveMeta.durationMs > 0L -> effectiveMeta.durationMs
                            else -> 0L
                        }

                    val track =
                        AudioTrack(
                            id = "${entity.serverId}:${entity.remotePath}",
                            serverId = entity.serverId,
                            remotePath = entity.remotePath,
                            title = entity.parsedTitle.ifBlank { entity.fileName },
                            artist = entity.parsedArtist ?: effectiveMeta?.artist,
                            album = effectiveMeta?.album ?: parentAudioPath.trimEnd('/').substringAfterLast('/').substringBeforeLast('.'),
                            durationMs = durationMs,
                            size = entity.fileSize,
                            format = format,
                            coverThumbnailPath = effectiveMeta?.coverThumbnailPath,
                            cuePath = cuePath,
                        )
                    matchedTracks["${entity.serverId}:${entity.remotePath}"] = track
                } else {
                    val metaKey = "${entity.serverId}:${entity.remotePath}"
                    val meta = metadataMap[metaKey]
                    val format =
                        AudioFormat.fromExtension(entity.format)
                            ?: AudioFormat.fromFileName(entity.fileName)
                            ?: AudioFormat.MP3
                    val track =
                        createAudioTrack(
                            serverId = entity.serverId,
                            remotePath = entity.remotePath,
                            fileName = entity.fileName,
                            fileSize = entity.fileSize,
                            format = format,
                            metadata = meta,
                            titleFallback = entity.parsedTitle,
                            artistFallback = entity.parsedArtist,
                        )
                    matchedTracks[metaKey] = track
                }

                if (entity.parentPath.contains(cleanQuery, ignoreCase = true)) {
                    val dirName = entity.parentPath.trimEnd('/').substringAfterLast('/')
                    val dirKey = "${entity.serverId}:${entity.parentPath}"
                    if (!matchedDirs.containsKey(dirKey) && dirName.isNotBlank()) {
                        matchedDirs[dirKey] =
                            RemoteDirectory(
                                path = entity.parentPath,
                                name = dirName,
                                subDirectories = emptyList(),
                                files = emptyList(),
                                serverId = entity.serverId,
                            )
                    }
                }
            }

            for (dirEntity in dirEntities) {
                val isCueCandidate = cueSheetResolver != null && dirEntity.dataJson.contains(".cue", ignoreCase = true)
                val matchesPath = dirEntity.path.contains(cleanQuery, ignoreCase = true)
                val matchesJson = dirEntity.dataJson.contains(cleanQuery, ignoreCase = true)
                if (!matchesPath && !matchesJson && !isCueCandidate) {
                    continue
                }

                val remoteDir = getOrDeserialize(dirEntity) ?: continue

                if (isMatch(cleanQuery, remoteDir.name, remoteDir.path)) {
                    matchedDirs["${dirEntity.serverId}:${remoteDir.path}"] = remoteDir.copy(serverId = dirEntity.serverId)
                }

                for (sub in remoteDir.subDirectories) {
                    if (isMatch(cleanQuery, sub.name, sub.path)) {
                        matchedDirs["${dirEntity.serverId}:${sub.path}"] = sub.copy(serverId = dirEntity.serverId)
                    }
                }

                val audioFiles = remoteDir.files.filter { it.isAudio }
                val cueFiles = remoteDir.files.filter { it.isCue }

                for (file in audioFiles) {
                    val metaKey = "${dirEntity.serverId}:${file.path}"
                    val meta = metadataMap[metaKey]
                    val format = (file.fileType as? RemoteFileType.Audio)?.format ?: AudioFormat.MP3
                    val track = createAudioTrack(dirEntity.serverId, file.path, file.name, file.size, format, meta)

                    if (isMatch(cleanQuery, track.title, track.artist, track.album, file.name, file.path)) {
                        matchedTracks[track.id] = track
                    }
                }

                if (cueFiles.isNotEmpty() && audioFiles.isNotEmpty() && cueSheetResolver != null) {
                    val server = WebDavServer(id = dirEntity.serverId, name = "", url = "", port = 0)
                    val parentDurations =
                        audioFiles.associate {
                            it.path to (metadataMap["${dirEntity.serverId}:${it.path}"]?.durationMs ?: 0L)
                        }
                    val albums = cueSheetResolver.resolveDirectoryCueAlbums(server, remoteDir, parentDurations)
                    for (album in albums) {
                        val matchedAudioFile = album.audioFile ?: continue
                        if (album.tracks.isEmpty()) continue

                        val parentMeta = metadataMap["${dirEntity.serverId}:${matchedAudioFile.path}"]
                        val parentFormat = (matchedAudioFile.fileType as? RemoteFileType.Audio)?.format ?: AudioFormat.FLAC
                        val parentTrack =
                            createAudioTrack(
                                serverId = dirEntity.serverId,
                                remotePath = matchedAudioFile.path,
                                fileName = matchedAudioFile.name,
                                fileSize = matchedAudioFile.size,
                                format = parentFormat,
                                metadata = parentMeta,
                                albumFallback = album.cueFile.name.substringBeforeLast('.'),
                            )

                        for (vt in album.tracks) {
                            val vtTrack = vt.toAudioTrack(parentTrack, cuePath = album.cueFile.path)
                            if (isMatch(cleanQuery, vt.title, vt.performer, vtTrack.album, parentTrack.title)) {
                                matchedTracks[vtTrack.id] = vtTrack
                            }
                        }
                    }
                }
            }

            for (meta in metaEntities) {
                val metaKey = "${meta.serverId}:${meta.remotePath}"
                if (!matchedTracks.containsKey(metaKey) && isMatch(cleanQuery, meta.title, meta.artist, meta.album, meta.remotePath)) {
                    val fileName = meta.remotePath.substringAfterLast('/')
                    val format = (RemoteFileType.fromFileName(fileName) as? RemoteFileType.Audio)?.format ?: AudioFormat.MP3
                    matchedTracks[metaKey] =
                        createAudioTrack(
                            serverId = meta.serverId,
                            remotePath = meta.remotePath,
                            fileName = fileName,
                            fileSize = 0L,
                            format = format,
                            metadata = meta,
                        )
                }
            }

            LibrarySearchResult(
                matchedDirectories = matchedDirs.values.toList(),
                matchedTracks = matchedTracks.values.toList(),
            )
        }
    }
}
