package com.webdav.player.data.repository

import android.content.Context
import org.json.JSONArray
import org.json.JSONException

/**
 * Internal collaborator encapsulating search query history persistence via SharedPreferences,
 * with an in-memory fallback for headless or test environments where Context is absent.
 */
class InternalSearchHistoryStore(
    private val context: Context? = null,
    private val prefsName: String = DEFAULT_PREFS_NAME,
) {
    companion object {
        const val DEFAULT_PREFS_NAME = "webdav_search_history"
        const val MAX_HISTORY_SIZE = 20
        private const val KEY_HISTORY_JSON = "history_json"
    }

    private val inMemoryHistory = mutableListOf<String>()
    private val prefs by lazy {
        context?.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
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

    fun getSearchHistory(): List<String> {
        val sharedPrefs = prefs ?: return synchronized(inMemoryHistory) { inMemoryHistory.toList() }
        val raw = sharedPrefs.getString(KEY_HISTORY_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: JSONException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun recordSearchQuery(query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return getSearchHistory()
        val current = getSearchHistory().filter { it != trimmed }
        val updated = (listOf(trimmed) + current).take(MAX_HISTORY_SIZE)
        saveHistory(updated)
        return updated
    }

    fun removeSearchHistory(query: String): List<String> {
        val updated = getSearchHistory().filter { it != query }
        saveHistory(updated)
        return updated
    }

    fun clearSearchHistory() {
        prefs?.edit()?.remove(KEY_HISTORY_JSON)?.apply()
        synchronized(inMemoryHistory) {
            inMemoryHistory.clear()
        }
    }
}
