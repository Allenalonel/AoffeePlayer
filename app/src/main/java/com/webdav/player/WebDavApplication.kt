package com.webdav.player

import android.app.Application
import android.content.ComponentCallbacks2
import com.webdav.player.data.local.AppDatabase
import com.webdav.player.data.local.CoverArtStorageImpl
import com.webdav.player.data.network.DefaultNetworkConnectivityObserver
import com.webdav.player.data.player.Media3AudioPlayerEngine
import com.webdav.player.data.player.WebDavMediaSourceBuilder
import com.webdav.player.data.remote.OkHttpWebDavClient
import com.webdav.player.data.remote.WebDavClient
import com.webdav.player.data.repository.DataStorePlaybackSessionStore
import com.webdav.player.data.repository.LocalStorageDirectoryResolver
import com.webdav.player.data.repository.LocalStorageIndexScanner
import com.webdav.player.data.repository.MediaLibraryRepositoryImpl
import com.webdav.player.data.repository.PlaylistRepositoryImpl
import com.webdav.player.data.repository.ServerRepositoryImpl
import com.webdav.player.data.repository.TrackMetadataRepositoryImpl
import com.webdav.player.data.repository.WebDavSyncScanner
import com.webdav.player.data.service.PlaybackSessionHost
import com.webdav.player.data.storage.StorageReachabilityTrackerImpl
import com.webdav.player.di.AppContainer
import com.webdav.player.domain.player.AudioPlayerEngine
import com.webdav.player.domain.repository.MediaLibraryRepository
import com.webdav.player.domain.repository.PlaylistRepository
import com.webdav.player.domain.repository.ServerRepository
import com.webdav.player.domain.repository.TrackMetadataRepository
import com.webdav.player.domain.session.MusicPlayerAppSession
import com.webdav.player.domain.session.MusicPlayerAppSessionImpl
import com.webdav.player.domain.storage.StorageReachabilityTracker

class WebDavApplication :
    Application(),
    AppContainer {
    override lateinit var serverRepository: ServerRepository
        private set

    override lateinit var webDavClient: WebDavClient
        private set

    override lateinit var trackMetadataRepository: TrackMetadataRepository
        private set

    override lateinit var mediaLibraryRepository: MediaLibraryRepository
        private set

    override lateinit var playlistRepository: PlaylistRepository
        private set

    override lateinit var storageReachabilityTracker: StorageReachabilityTracker
        private set

    override lateinit var playerEngine: AudioPlayerEngine
        private set

    lateinit var sessionHost: PlaybackSessionHost
        private set

    override lateinit var musicPlayerAppSession: MusicPlayerAppSession
        private set

    override fun onCreate() {
        super.onCreate()

        val database = AppDatabase.getInstance(this)
        val coverArtStorage = CoverArtStorageImpl(this)
        val webDavClient = OkHttpWebDavClient()
        this.webDavClient = webDavClient

        val mediaTrackIndexDao = database.mediaTrackIndexDao()
        val checkpointDao = database.directorySyncCheckpointDao()

        trackMetadataRepository =
            TrackMetadataRepositoryImpl(
                trackMetadataDao = database.trackMetadataDao(),
                webDavClient = webDavClient,
                coverArtStorage = coverArtStorage,
                webDavServerDao = database.webDavServerDao(),
                context = this,
            )

        val media3Engine =
            Media3AudioPlayerEngine(
                context = this,
                mediaSourceBuilder = WebDavMediaSourceBuilder(this, webDavClient),
            )
        playerEngine = media3Engine
        val sessionHost = PlaybackSessionHost.getInstance(this, media3Engine)
        this.sessionHost = sessionHost

        val sessionStore = DataStorePlaybackSessionStore(this)

        val localStorageDirectoryResolver =
            LocalStorageDirectoryResolver(
                context = this,
                localStorageFolderDao = database.localStorageFolderDao(),
                coverArtStorage = coverArtStorage,
            )

        val localStorageIndexScanner =
            LocalStorageIndexScanner(
                context = this,
                mediaTrackIndexDao = mediaTrackIndexDao,
                checkpointDao = checkpointDao,
                localStorageFolderDao = database.localStorageFolderDao(),
            )

        val webDavSyncScanner =
            WebDavSyncScanner(
                webDavClient = webDavClient,
                mediaTrackIndexDao = mediaTrackIndexDao,
                directorySyncCheckpointDao = checkpointDao,
            )

        val mediaLibraryRepository =
            MediaLibraryRepositoryImpl(
                webDavClient = webDavClient,
                directoryCacheDao = database.directoryCacheDao(),
                trackMetadataDao = database.trackMetadataDao(),
                mediaTrackIndexDao = mediaTrackIndexDao,
                directorySyncCheckpointDao = checkpointDao,
                trackMetadataRepository = trackMetadataRepository,
                localStorageDirectoryResolver = localStorageDirectoryResolver,
                localStorageFolderDao = database.localStorageFolderDao(),
                webDavSyncScanner = webDavSyncScanner,
                localStorageIndexScanner = localStorageIndexScanner,
                context = this,
            )
        this.mediaLibraryRepository = mediaLibraryRepository

        serverRepository =
            ServerRepositoryImpl(
                serverDao = database.webDavServerDao(),
                coverArtStorage = coverArtStorage,
                webDavClient = webDavClient,
                trackMetadataDao = database.trackMetadataDao(),
                localStorageFolderDao = database.localStorageFolderDao(),
                context = this,
                cascadeInvalidator = mediaLibraryRepository,
            )

        storageReachabilityTracker =
            StorageReachabilityTrackerImpl(
                webDavClient = webDavClient,
                serverRepository = serverRepository,
                context = this,
            )

        playlistRepository =
            PlaylistRepositoryImpl(
                playlistDao = database.playlistDao(),
                serverRepository = serverRepository,
                trackMetadataRepository = trackMetadataRepository,
                webDavClient = webDavClient,
                context = this,
            )

        val networkConnectivityObserver = DefaultNetworkConnectivityObserver(this)

        musicPlayerAppSession =
            MusicPlayerAppSessionImpl(
                playerEngine = playerEngine,
                serverRepository = serverRepository,
                trackMetadataRepository = trackMetadataRepository,
                sessionStore = sessionStore,
                playlistRepository = playlistRepository,
                storageReachabilityTracker = storageReachabilityTracker,
                networkConnectivityObserver = networkConnectivityObserver,
                hardwareLockManager = sessionHost.hardwareLockManager,
            )
        sessionHost.bindAppSession(musicPlayerAppSession)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            com.webdav.player.ui.common.ThumbnailMemoryCache
                .clear()
            com.webdav.player.ui.player.ArtworkColorExtractor
                .clearCache()
            if (::mediaLibraryRepository.isInitialized) {
                mediaLibraryRepository.clearMemoryCache()
            }
        }
    }
}
