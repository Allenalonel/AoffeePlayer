package com.webdav.player.domain.model

enum class SortField {
    NAME,
    LAST_MODIFIED,
    SIZE
}

enum class SortDirection {
    ASCENDING,
    DESCENDING
}

data class DirectorySortOrder(
    val field: SortField = SortField.NAME,
    val direction: SortDirection = SortDirection.ASCENDING
)
