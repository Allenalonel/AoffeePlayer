package com.webdav.player.di

import com.webdav.player.data.remote.WebDavClient
import com.webdav.player.domain.player.AudioPlayerEngine
import com.webdav.player.domain.repository.MediaLibraryRepository
import com.webdav.player.domain.repository.PlaylistRepository
import com.webdav.player.domain.repository.ServerRepository
import com.webdav.player.domain.repository.TrackMetadataRepository
import com.webdav.player.domain.session.MusicPlayerAppSession
import com.webdav.player.domain.storage.StorageReachabilityTracker

/**
 * Unified application-level dependency container providing clean access to core singletons
 * across ViewModels, session hosts, and platform components.
 */
interface AppContainer {
    val serverRepository: ServerRepository
    val mediaLibraryRepository: MediaLibraryRepository
    val playlistRepository: PlaylistRepository
    val storageReachabilityTracker: StorageReachabilityTracker
    val trackMetadataRepository: TrackMetadataRepository
    val playerEngine: AudioPlayerEngine
    val musicPlayerAppSession: MusicPlayerAppSession
    val webDavClient: WebDavClient
}
