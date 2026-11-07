package com.webdav.player.data.metadata

data class ParsedMediaFilename(
    val title: String,
    val artist: String?,
)

object MediaFilenameParser {
    private val TRACK_PREFIX_REGEX = Regex("""^(?:track\s*\d*|\d{1,3})[\s._-]+\s*""", RegexOption.IGNORE_CASE)
    private val PURE_TRACK_REGEX = Regex("""^(?:track\s*\d*|\d{1,3})$""", RegexOption.IGNORE_CASE)

    fun parse(fileName: String): ParsedMediaFilename {
        val base =
            if (fileName.contains('.')) {
                val nameWithoutExt = fileName.substringBeforeLast('.')
                if (nameWithoutExt.isBlank()) fileName else nameWithoutExt
            } else {
                fileName
            }.trim()

        if (base.isEmpty()) {
            return ParsedMediaFilename(title = fileName, artist = null)
        }

        val parts = base.split(" - ").map { it.trim() }.filter { it.isNotEmpty() }

        return when {
            parts.size <= 1 -> {
                val part = parts.firstOrNull() ?: base
                val stripped = TRACK_PREFIX_REGEX.replace(part, "").trim()
                val title = if (stripped.isNotBlank()) stripped else part
                ParsedMediaFilename(title = title, artist = null)
            }

            parts.size == 2 -> {
                val first = parts[0]
                val second = parts[1]
                if (PURE_TRACK_REGEX.matches(first)) {
                    val cleanSecond = TRACK_PREFIX_REGEX.replace(second, "").trim()
                    ParsedMediaFilename(title = if (cleanSecond.isNotBlank()) cleanSecond else second, artist = null)
                } else {
                    val cleanFirst = TRACK_PREFIX_REGEX.replace(first, "").trim()
                    val artist = if (cleanFirst.isNotBlank()) cleanFirst else first
                    ParsedMediaFilename(title = second, artist = artist)
                }
            }

            parts.size == 3 -> {
                val p0 = parts[0]
                val p1 = parts[1]
                val p2 = parts[2]
                if (PURE_TRACK_REGEX.matches(p0)) {
                    // e.g. "01 - Artist - Title" or "Track - Artist - Title"
                    val cleanP1 = TRACK_PREFIX_REGEX.replace(p1, "").trim()
                    val artist = if (cleanP1.isNotBlank()) cleanP1 else p1
                    ParsedMediaFilename(title = p2, artist = artist)
                } else {
                    // e.g. "Artist - Album - Title"
                    val cleanP0 = TRACK_PREFIX_REGEX.replace(p0, "").trim()
                    val artist = if (cleanP0.isNotBlank()) cleanP0 else p0
                    ParsedMediaFilename(title = p2, artist = artist)
                }
            }

            else -> {
                val p0 = parts[0]
                if (PURE_TRACK_REGEX.matches(p0)) {
                    val p1 = parts[1]
                    val cleanP1 = TRACK_PREFIX_REGEX.replace(p1, "").trim()
                    val artist = if (cleanP1.isNotBlank()) cleanP1 else p1
                    val title = parts.drop(2).joinToString(" - ")
                    ParsedMediaFilename(title = title, artist = artist)
                } else {
                    val cleanP0 = TRACK_PREFIX_REGEX.replace(p0, "").trim()
                    val artist = if (cleanP0.isNotBlank()) cleanP0 else p0
                    val title = parts.drop(1).joinToString(" - ")
                    ParsedMediaFilename(title = title, artist = artist)
                }
            }
        }
    }
}
