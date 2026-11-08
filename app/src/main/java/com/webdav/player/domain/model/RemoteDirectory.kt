package com.webdav.player.domain.model

data class RemoteDirectory(
    val path: String,
    val name: String,
    val subDirectories: List<RemoteDirectory> = emptyList(),
    val files: List<RemoteFile> = emptyList(),
    val serverId: Long? = null,
    val lastModified: Long = 0L,
    val etag: String? = null,
) {
    val audioFiles: List<RemoteFile> get() = files.filter { it.isAudio }
    val isEmpty: Boolean get() = subDirectories.isEmpty() && files.isEmpty()

    companion object {
        fun normalizePath(path: String): String {
            var p = path.replace('\\', '/')
            while (p.contains("//")) {
                p = p.replace("//", "/")
            }
            if (!p.startsWith("/")) p = "/$p"
            if (!p.endsWith("/")) p = "$p/"
            while (p.contains("//")) {
                p = p.replace("//", "/")
            }
            return p
        }

        fun getParentPath(currentPath: String): String? {
            val normalized = normalizePath(currentPath)
            val cleanPath = normalized.trim('/')
            if (cleanPath.isEmpty() || cleanPath == "local") return null

            val segments = cleanPath.split('/').filter { it.isNotBlank() }
            if (segments.size <= 1) return "/"

            if (segments[0] == "local") {
                if (segments.size == 2) {
                    return "/"
                }
                return "/" + segments.dropLast(1).joinToString("/") + "/"
            }

            return "/" + segments.dropLast(1).joinToString("/") + "/"
        }

        fun isAncestor(
            ancestorPath: String,
            currentPath: String,
        ): Boolean {
            val normAncestor = normalizePath(ancestorPath)
            val normCurrent = normalizePath(currentPath)
            if (normAncestor == normCurrent) return false
            return normCurrent.startsWith(normAncestor)
        }

        fun buildBreadcrumbs(
            server: WebDavServer?,
            path: String,
        ): List<Breadcrumb> {
            val isLocal = server?.isLocal == true || path.startsWith("/local/")
            val rootName =
                if (isLocal) {
                    server?.name?.takeIf { it.isNotBlank() } ?: "本地存储"
                } else {
                    server?.name?.takeIf { it.isNotBlank() } ?: "根目录"
                }
            val list = mutableListOf(Breadcrumb(name = rootName, path = "/"))

            val normalized = normalizePath(path)
            val cleanPath = normalized.trim('/')
            if (cleanPath.isEmpty() || cleanPath == "local") return list

            val segments = cleanPath.split('/').filter { it.isNotBlank() }
            if (isLocal && segments.firstOrNull() == "local") {
                if (segments.size >= 2) {
                    val encodedUri = segments[1]
                    val mountedPath = "/local/$encodedUri/"
                    val mountedFolderName = deriveMountedFolderName(encodedUri)
                    list.add(Breadcrumb(name = mountedFolderName, path = mountedPath))

                    var accumulated = "/local/$encodedUri"
                    for (sub in segments.drop(2)) {
                        val cleanSub =
                            try {
                                java.net.URLDecoder.decode(sub, "UTF-8")
                            } catch (_: Exception) {
                                sub
                            }
                        accumulated += "/$sub"
                        list.add(Breadcrumb(name = cleanSub, path = "$accumulated/"))
                    }
                }
                return list
            }

            var accumulated = ""
            for (segment in segments) {
                accumulated += "/$segment"
                list.add(Breadcrumb(name = segment, path = "$accumulated/"))
            }
            return list
        }

        fun deriveMountedFolderName(encodedUri: String): String =
            try {
                var decoded = java.net.URLDecoder.decode(encodedUri, "UTF-8")
                if (decoded.contains("%")) {
                    try {
                        decoded = java.net.URLDecoder.decode(decoded, "UTF-8")
                    } catch (_: Exception) {
                    }
                }
                val lastSegment = decoded.trimEnd('/').substringAfterLast('/')
                if (lastSegment.contains(":")) {
                    lastSegment.substringAfterLast(':').ifBlank { "本地目录" }
                } else {
                    lastSegment.ifBlank { "本地目录" }
                }
            } catch (_: Exception) {
                encodedUri
            }
    }
}

data class Breadcrumb(
    val name: String,
    val path: String,
)
