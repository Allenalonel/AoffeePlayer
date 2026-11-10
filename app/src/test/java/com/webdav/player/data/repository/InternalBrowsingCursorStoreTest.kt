package com.webdav.player.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InternalBrowsingCursorStoreTest {

    @Test
    fun inMemoryFallback_whenContextIsNull() {
        val store = InternalBrowsingCursorStore(context = null)
        assertEquals("/", store.getLastBrowsingPath(1L))

        store.setLastBrowsingPath(1L, "/Music/Rock/")
        assertEquals("/Music/Rock/", store.getLastBrowsingPath(1L))

        store.clearCursor(1L)
        assertEquals("/", store.getLastBrowsingPath(1L))
    }

    @Test
    fun sharedPreferencesPersistence_withContext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefsName = "test_browsing_cursor_prefs_1"
        val store1 = InternalBrowsingCursorStore(context = context, prefsName = prefsName)
        store1.clearAllCursors()

        store1.setLastBrowsingPath(1L, "/Music/Jazz/")
        store1.setLastBrowsingPath(2L, "/Audiobooks/")
        assertEquals("/Music/Jazz/", store1.getLastBrowsingPath(1L))
        assertEquals("/Audiobooks/", store1.getLastBrowsingPath(2L))

        // Create a second store instance with same context and prefsName to simulate app relaunch
        val store2 = InternalBrowsingCursorStore(context = context, prefsName = prefsName)
        assertEquals("/Music/Jazz/", store2.getLastBrowsingPath(1L))
        assertEquals("/Audiobooks/", store2.getLastBrowsingPath(2L))

        store2.clearAllCursors()
        assertEquals("/", store2.getLastBrowsingPath(1L))
        assertEquals("/", store2.getLastBrowsingPath(2L))
    }

    @Test
    fun normalizesPaths_onSet() {
        val store = InternalBrowsingCursorStore(context = null)
        store.setLastBrowsingPath(1L, "music/rock")
        assertEquals("/music/rock/", store.getLastBrowsingPath(1L))

        store.setLastBrowsingPath(1L, "")
        assertEquals("/", store.getLastBrowsingPath(1L))

        store.setLastBrowsingPath(1L, "   ")
        assertEquals("/", store.getLastBrowsingPath(1L))
    }

    @Test
    fun serverIsolation_maintainsDistinctCursors() {
        val store = InternalBrowsingCursorStore(context = null)
        store.setLastBrowsingPath(1L, "/Server1/Path/")
        store.setLastBrowsingPath(2L, "/Server2/Path/")
        store.setLastBrowsingPath(0L, "/local/Path/")

        assertEquals("/Server1/Path/", store.getLastBrowsingPath(1L))
        assertEquals("/Server2/Path/", store.getLastBrowsingPath(2L))
        assertEquals("/local/Path/", store.getLastBrowsingPath(0L))

        store.clearCursor(1L)
        assertEquals("/", store.getLastBrowsingPath(1L))
        assertEquals("/Server2/Path/", store.getLastBrowsingPath(2L))
        assertEquals("/local/Path/", store.getLastBrowsingPath(0L))
    }
}
