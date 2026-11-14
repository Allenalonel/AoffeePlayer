package com.webdav.player.domain.model

data class AudioPhysicalSpecs(
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val bitrateKbps: Int? = null,
    val channels: Int? = null
) {
    val isEmpty: Boolean
        get() = sampleRate == null && bitDepth == null && bitrateKbps == null && channels == null

    val isNotEmpty: Boolean
        get() = !isEmpty
}
