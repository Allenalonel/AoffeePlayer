package com.webdav.player.ui.common

import android.net.Uri
import android.provider.DocumentsContract
import java.net.URLDecoder

/**
 * Utility for formatting local SAF document URIs, file URIs, and remote WebDAV paths
 * into human-readable display paths and directory structures for UI presentation.
 */
object TrackPathFormatter {
    fun formatDisplayPath(path: String): String {
        if (!path.startsWith("content://")) {
            return if (path.startsWith("file://")) {
                path.removePrefix("file://")
            } else {
                path
            }
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
