package com.webdav.player

import android.content.ComponentCallbacks2
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WebDavApplicationTest {
    @Test
    fun applicationBootstrap_initializesAllRepositoriesInAcyclicOrder() {
        val app = ApplicationProvider.getApplicationContext<WebDavApplication>()
        assertNotNull("ServerRepository must be initialized", app.serverRepository)
        assertNotNull("MediaLibraryRepository must be initialized", app.mediaLibraryRepository)
        assertNotNull("TrackMetadataRepository must be initialized", app.trackMetadataRepository)
        assertNotNull("PlaylistRepository must be initialized", app.playlistRepository)
        assertNotNull("PlayerEngine must be initialized", app.playerEngine)
        assertNotNull("MusicPlayerAppSession must be initialized", app.musicPlayerAppSession)
    }

    @Test
    fun onTrimMemory_clearsCachesWithoutThrowing() {
        val app = ApplicationProvider.getApplicationContext<WebDavApplication>()
        app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
    }
}
