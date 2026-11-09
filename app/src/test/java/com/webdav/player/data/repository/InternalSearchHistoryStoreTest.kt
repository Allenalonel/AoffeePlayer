package com.webdav.player.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InternalSearchHistoryStoreTest {

    @Test
    fun inMemoryFallback_whenContextIsNull() {
        val store = InternalSearchHistoryStore(context = null)
        assertTrue(store.getSearchHistory().isEmpty())

        store.recordSearchQuery("Acoustic")
        store.recordSearchQuery("Beethoven")
        assertEquals(listOf("Beethoven", "Acoustic"), store.getSearchHistory())

        store.removeSearchHistory("Beethoven")
        assertEquals(listOf("Acoustic"), store.getSearchHistory())

        store.clearSearchHistory()
        assertTrue(store.getSearchHistory().isEmpty())
    }

    @Test
    fun sharedPreferencesPersistence_withContext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = InternalSearchHistoryStore(context = context, prefsName = "test_search_history_1")
        store.clearSearchHistory()

        store.recordSearchQuery("Rock")
        store.recordSearchQuery("Jazz")
        assertEquals(listOf("Jazz", "Rock"), store.getSearchHistory())

        // Create a new instance pointing to same prefs to ensure persistence
        val store2 = InternalSearchHistoryStore(context = context, prefsName = "test_search_history_1")
        assertEquals(listOf("Jazz", "Rock"), store2.getSearchHistory())

        store2.clearSearchHistory()
        assertTrue(store2.getSearchHistory().isEmpty())
    }

    @Test
    fun trimsWhitespace_andIgnoresBlank() {
        val store = InternalSearchHistoryStore(context = null)
        store.recordSearchQuery("   ")
        store.recordSearchQuery("\t\n")
        assertTrue(store.getSearchHistory().isEmpty())

        store.recordSearchQuery("  Lo-Fi  ")
        assertEquals(listOf("Lo-Fi"), store.getSearchHistory())
    }

    @Test
    fun deduplicatesAndBumpsToFront() {
        val store = InternalSearchHistoryStore(context = null)
        store.recordSearchQuery("Synthwave")
        store.recordSearchQuery("Ambient")
        store.recordSearchQuery("Synthwave")

        assertEquals(listOf("Synthwave", "Ambient"), store.getSearchHistory())
    }

    @Test
    fun capsAt20Queries() {
        val store = InternalSearchHistoryStore(context = null)
        for (i in 1..25) {
            store.recordSearchQuery("Query $i")
        }
        val history = store.getSearchHistory()
        assertEquals(20, history.size)
        assertEquals("Query 25", history.first())
        assertEquals("Query 6", history.last())
    }

    @Test
    fun removeSearchHistory_removesSpecificQuery() {
        val store = InternalSearchHistoryStore(context = null)
        store.recordSearchQuery("Query 1")
        store.recordSearchQuery("Query 2")
        store.recordSearchQuery("Query 3")

        val result = store.removeSearchHistory("Query 2")
        assertEquals(listOf("Query 3", "Query 1"), result)
        assertEquals(listOf("Query 3", "Query 1"), store.getSearchHistory())
    }

    @Test
    fun handlesCorruptedJsonGracefully() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("corrupted_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("history_json", "invalid json string").apply()

        val store = InternalSearchHistoryStore(context = context, prefsName = "corrupted_prefs")
        assertTrue(store.getSearchHistory().isEmpty())
    }
}
