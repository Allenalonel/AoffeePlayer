package com.webdav.player.data.repository

import android.content.Context
import com.webdav.player.domain.model.RemoteDirectory
import java.util.concurrent.ConcurrentHashMap

/**
 * Internal collaborator encapsulating directory browsing cursor retention via SharedPreferences,
 * with an in-memory ConcurrentHashMap cache for zero-latency lookups, and an in-memory fallback
 * when Context is absent.
 */
class InternalBrowsingCursorStore(
    private val context: Context? = null,
    private val prefsName: String = DEFAULT_PREFS_NAME,
) {
    companion object {
        const val DEFAULT_PREFS_NAME = "media_library_preferences"
        private const val KEY_PREFIX = "cursor_"
    }

    private val inMemoryCursors = ConcurrentHashMap<Long, String>()
    private val prefs by lazy {
        context?.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    }

    fun getLastBrowsingPath(serverId: Long): String {
        inMemoryCursors[serverId]?.let { return it }
        val sharedPrefs = prefs ?: return "/"
        val stored = sharedPrefs.getString("$KEY_PREFIX$serverId", null)
        return if (stored != null) {
            inMemoryCursors[serverId] = stored
            stored
        } else {
            "/"
        }
    }

    fun setLastBrowsingPath(serverId: Long, path: String) {
        val normalized = if (path.isBlank()) "/" else RemoteDirectory.normalizePath(path)
        inMemoryCursors[serverId] = normalized
        prefs?.edit()?.putString("$KEY_PREFIX$serverId", normalized)?.apply()
    }

    fun clearCursor(serverId: Long) {
        inMemoryCursors.remove(serverId)
        prefs?.edit()?.remove("$KEY_PREFIX$serverId")?.apply()
    }

    fun clearAllCursors() {
        inMemoryCursors.clear()
        val sharedPrefs = prefs ?: return
        val editor = sharedPrefs.edit()
        sharedPrefs.all.keys.filter { it.startsWith(KEY_PREFIX) }.forEach { key ->
            editor.remove(key)
        }
        editor.apply()
    }
}
