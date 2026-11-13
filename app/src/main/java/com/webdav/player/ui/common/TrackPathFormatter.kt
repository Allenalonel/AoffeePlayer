package com.webdav.player.ui.common

import android.net.Uri
import android.provider.DocumentsContract
import com.webdav.player.domain.model.LOCAL_STORAGE_ID
import com.webdav.player.domain.model.TrackIdentity
import java.net.URLDecoder

/**
 * Utility for formatting local SAF document URIs, file URIs, and remote WebDAV paths
 * into human-readable display paths and directory structures for UI presentation.
 */
object TrackPathFormatter {
    fun formatDisplayPath(path: String): String {
        val identity = TrackIdentity(LOCAL_STORAGE_ID, path)
        if (!identity.isLocal) {
            return path
        }
        if (path.startsWith("file://")) {
            return path.removePrefix("file://")
        }
        if (path.startsWith("/local/")) {
            val relative = path.removePrefix("/local/").trim('/')
            val parts = relative.split('/', limit = 2)
            val decodedUri =
                try {
                    URLDecoder.decode(parts[0], "UTF-8")
                } catch (_: Exception) {
                    parts[0]
                }
            val formattedBase = formatDisplayPath(decodedUri)
            return if (parts.size > 1) {
                "$formattedBase/${parts[1]}"
            } else {
                formattedBase
            }
        }
        if (!path.startsWith("content://")) {
            return path
        }

        return try {
            val uri = Uri.parse(path)
            val docId =
                try {
                    DocumentsContract.getDocumentId(uri)
                } catch (_: Exception) {
                    null
                } ?: run {
                    val pathString = uri.path.orEmpty()
                    when {
                        pathString.contains("/document/") -> pathString.substringAfter("/document/")
                        pathString.contains("/tree/") -> pathString.substringAfter("/tree/")
                        else -> uri.lastPathSegment ?: path
                    }
                }

            val rawDecoded =
                try {
                    URLDecoder.decode(docId, "UTF-8")
                } catch (_: Exception) {
                    docId
                }
            val decoded =
                if (rawDecoded.contains("%")) {
                    try {
                        URLDecoder.decode(rawDecoded, "UTF-8")
                    } catch (_: Exception) {
                        rawDecoded
                    }
                } else {
                    rawDecoded
                }

            if (decoded.contains(":")) {
                val volume = decoded.substringBefore(":")
                val relativePath = decoded.substringAfter(":").trimStart('/')
                val volumePrefix =
                    if (volume.equals("primary", ignoreCase = true)) {
                        "内部存储"
                    } else {
                        "SD卡 ($volume)"
                    }
                if (relativePath.isBlank()) volumePrefix else "$volumePrefix/$relativePath"
            } else {
                decoded
            }
        } catch (_: Exception) {
            path
        }
    }

    fun formatDirectoryPath(path: String): String {
        val display = formatDisplayPath(path)
        val slashIdx = display.lastIndexOf('/')
        return if (slashIdx > 0) {
            display.substring(0, slashIdx + 1)
        } else if (slashIdx == 0) {
            "/"
        } else {
            "$display/"
        }
    }
}
