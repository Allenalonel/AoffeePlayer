package com.webdav.player.data.repository

import android.content.Context
import com.webdav.player.domain.model.DirectorySortOrder
import com.webdav.player.domain.model.RemoteDirectory
import com.webdav.player.domain.model.SortDirection
import com.webdav.player.domain.model.SortField
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Internal collaborator encapsulating directory browsing cursor retention via SharedPreferences,
 * with an in-memory ConcurrentHashMap cache for zero-latency lookups, and an in-memory fallback
 * when Context is absent.
 */
internal class InternalBrowsingCursorStore(
    private val context: Context? = null,
    private val prefsName: String = DEFAULT_PREFS_NAME
) {
    companion object {
        const val DEFAULT_PREFS_NAME = "media_library_preferences"
        private const val KEY_PREFIX = "cursor_"
        private const val KEY_SORT_FIELD = "directory_sort_field"
        private const val KEY_SORT_DIRECTION = "directory_sort_direction"
    }

    private val inMemoryCursors = ConcurrentHashMap<Long, String>()
    private val inMemorySortOrder = AtomicReference<DirectorySortOrder?>(null)
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
        if (inMemoryCursors[serverId] == normalized) {
            return
        }
        inMemoryCursors[serverId] = normalized
        prefs?.edit()?.putString("$KEY_PREFIX$serverId", normalized)?.apply()
    }

    fun clearCursor(serverId: Long) {
        inMemoryCursors.remove(serverId)
        prefs?.edit()?.remove("$KEY_PREFIX$serverId")?.apply()
    }

    fun getDirectorySortOrder(): DirectorySortOrder {
        inMemorySortOrder.get()?.let { return it }
        val sharedPrefs = prefs ?: return DirectorySortOrder().also { inMemorySortOrder.set(it) }
        val fieldName = sharedPrefs.getString(KEY_SORT_FIELD, null)
        val directionName = sharedPrefs.getString(KEY_SORT_DIRECTION, null)
        val field = try {
            fieldName?.let { SortField.valueOf(it) } ?: SortField.NAME
        } catch (_: Exception) {
            SortField.NAME
        }
        val direction = try {
            directionName?.let { SortDirection.valueOf(it) } ?: SortDirection.ASCENDING
        } catch (_: Exception) {
            SortDirection.ASCENDING
        }
        val order = DirectorySortOrder(field = field, direction = direction)
        inMemorySortOrder.set(order)
        return order
    }

    fun setDirectorySortOrder(sortOrder: DirectorySortOrder) {
        inMemorySortOrder.set(sortOrder)
        prefs?.edit()
            ?.putString(KEY_SORT_FIELD, sortOrder.field.name)
            ?.putString(KEY_SORT_DIRECTION, sortOrder.direction.name)
            ?.apply()
    }
}
