package com.webdav.player.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.webdav.player.domain.model.DirectorySortOrder
import com.webdav.player.domain.model.SortDirection
import com.webdav.player.domain.model.SortField
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
        val prefsName = "test_browsing_cursor_prefs_persistence"
        val store1 = InternalBrowsingCursorStore(context = context, prefsName = prefsName)
        store1.clearCursor(1L)
        store1.clearCursor(2L)

        store1.setLastBrowsingPath(1L, "/Music/Jazz/")
        store1.setLastBrowsingPath(2L, "/Audiobooks/")
        assertEquals("/Music/Jazz/", store1.getLastBrowsingPath(1L))
        assertEquals("/Audiobooks/", store1.getLastBrowsingPath(2L))

        // Create a second store instance with same context and prefsName to simulate app relaunch
        val store2 = InternalBrowsingCursorStore(context = context, prefsName = prefsName)
        assertEquals("/Music/Jazz/", store2.getLastBrowsingPath(1L))
        assertEquals("/Audiobooks/", store2.getLastBrowsingPath(2L))

        store2.clearCursor(1L)
        store2.clearCursor(2L)
        assertEquals("/", store2.getLastBrowsingPath(1L))
        assertEquals("/", store2.getLastBrowsingPath(2L))
    }

    @Test
    fun setLastBrowsingPath_skipsRedundantWrite_whenPathMatchesCache() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefsName = "test_browsing_cursor_redundant"
        val store = InternalBrowsingCursorStore(context = context, prefsName = prefsName)
        store.clearCursor(1L)

        store.setLastBrowsingPath(1L, "/Music/Jazz/")
        assertEquals("/Music/Jazz/", store.getLastBrowsingPath(1L))

        // Manually alter SharedPreferences behind the scenes to verify that repeated setLastBrowsingPath
        // with the same path returns early and does not hit disk
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        prefs.edit().putString("cursor_1", "/Tampered/").apply()

        // Cache still holds "/Music/Jazz/", calling set with same path should return early
        store.setLastBrowsingPath(1L, "/Music/Jazz/")
        assertEquals("/Tampered/", prefs.getString("cursor_1", null))

        // Setting a different path updates both cache and disk
        store.setLastBrowsingPath(1L, "/Music/Rock/")
        assertEquals("/Music/Rock/", prefs.getString("cursor_1", null))
        assertEquals("/Music/Rock/", store.getLastBrowsingPath(1L))
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

    @Test
    fun sortOrder_inMemoryFallback_whenContextIsNull() {
        val store = InternalBrowsingCursorStore(context = null)
        assertEquals(DirectorySortOrder(SortField.NAME, SortDirection.ASCENDING), store.getDirectorySortOrder())

        val newOrder = DirectorySortOrder(SortField.LAST_MODIFIED, SortDirection.DESCENDING)
        store.setDirectorySortOrder(newOrder)
        assertEquals(newOrder, store.getDirectorySortOrder())
    }

    @Test
    fun sortOrder_persistsAcrossInstances() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefsName = "test_sort_order_prefs_persistence"
        val store1 = InternalBrowsingCursorStore(context = context, prefsName = prefsName)
        assertEquals(DirectorySortOrder(SortField.NAME, SortDirection.ASCENDING), store1.getDirectorySortOrder())

        val customOrder = DirectorySortOrder(SortField.SIZE, SortDirection.DESCENDING)
        store1.setDirectorySortOrder(customOrder)
        assertEquals(customOrder, store1.getDirectorySortOrder())

        // Simulate new app launch with fresh store instance
        val store2 = InternalBrowsingCursorStore(context = context, prefsName = prefsName)
        assertEquals(customOrder, store2.getDirectorySortOrder())
    }
}
