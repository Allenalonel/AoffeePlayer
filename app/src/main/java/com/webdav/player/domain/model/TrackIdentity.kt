package com.webdav.player.domain.model

import java.net.URLDecoder

class TrackIdentity(
    serverId: Long,
    val remotePath: String
) {
    val serverId: Long = if (
        remotePath.startsWith("content://") ||
        remotePath.startsWith("file://") ||
        remotePath.startsWith("/local/") ||
        serverId == LOCAL_STORAGE_ID
    ) {
        LOCAL_STORAGE_ID
    } else {
        serverId
    }

    val isLocal: Boolean
        get() = serverId == LOCAL_STORAGE_ID

    val compositeKey: String
        get() = if (isLocal) "local:$remotePath" else "$serverId:$remotePath"

    val trackId: String
        get() = "$serverId:$remotePath"

    val fileName: String
        get() = extractFileName(remotePath)

    fun matchesScope(scopeServerId: Long?): Boolean = when {
        scopeServerId == null -> true
        scopeServerId == LOCAL_STORAGE_ID -> isLocal
        else -> !isLocal && serverId == scopeServerId
    }

    operator fun component1(): Long = serverId
    operator fun component2(): String = remotePath

    fun copy(
        serverId: Long = this.serverId,
        remotePath: String = this.remotePath
    ): TrackIdentity = TrackIdentity(serverId = serverId, remotePath = remotePath)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrackIdentity) return false
        return serverId == other.serverId && remotePath == other.remotePath
    }

    override fun hashCode(): Int {
        var result = serverId.hashCode()
        result = 31 * result + remotePath.hashCode()
        return result
    }

    override fun toString(): String = "TrackIdentity(serverId=$serverId, remotePath=$remotePath)"

    companion object {
        fun extractFileName(remotePath: String): String {
            if (remotePath.isBlank()) return ""
            val lastSegment = remotePath.substringAfterLast('/')
            val decoded =
                try {
                    URLDecoder.decode(lastSegment, "UTF-8")
                } catch (_: Exception) {
                    lastSegment
                }
            val nameAfterSlash = decoded.substringAfterLast('/')
            val nameAfterColon = nameAfterSlash.substringAfterLast(':')
            return nameAfterColon.ifBlank { lastSegment }
        }

        fun cleanTitle(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val cleanName = extractFileName(raw).ifBlank { raw }
            return cleanName.substringBeforeLast('.').ifBlank { cleanName }
        }

        fun sanitizePersistedTitle(title: String?): String? {
            if (title == null) return null
            val isSafToken = title.startsWith("primary%") || title.startsWith("primary:") || title.contains("%2F")
            return if (isSafToken) cleanTitle(title) ?: title else title
        }
    }
}
