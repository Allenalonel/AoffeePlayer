package com.webdav.player.data.cue

import com.webdav.player.data.local.MediaTrackIndexDao
import com.webdav.player.data.local.MediaTrackIndexEntity
import com.webdav.player.domain.cue.CueSheetResolver
import com.webdav.player.domain.model.RemoteDirectory
import com.webdav.player.domain.model.WebDavServer

/**
 * Resolves CUE sheets into virtual track entries in [MediaTrackIndexEntity]
 * so that single-file rips and cues are directly indexed and searchable.
 */
open class CueVirtualTrackIndexer(
    private val cueSheetResolver: CueSheetResolver,
    private val mediaTrackIndexDao: MediaTrackIndexDao,
) {
    open suspend fun indexDirectoryCues(
        server: WebDavServer,
        directory: RemoteDirectory,
        parentDurationsByPath: Map<String, Long> = emptyMap(),
    ): List<MediaTrackIndexEntity> = emptyList()
}
