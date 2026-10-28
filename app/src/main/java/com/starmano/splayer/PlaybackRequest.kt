package com.starmano.splayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.content.IntentCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import java.io.File

/**
 * A stream handed to the player by another app.
 *
 * Stremio (like most Android apps) opens external players with `ACTION_VIEW` and the stream URL.
 * Optional extras follow the de-facto MX Player and VLC conventions, which is what Stremio and
 * other apps send to third-party players.
 */
data class PlaybackRequest(
    val uri: Uri,
    val mimeType: String?,
    val title: String?,
    val startPositionMs: Long,
    val headers: Map<String, String>,
    val subtitles: List<Subtitle>,
) {
    data class Subtitle(val uri: Uri, val label: String?, val selected: Boolean)

    @OptIn(UnstableApi::class)
    fun toMediaItem(): MediaItem = MediaItem.Builder()
        .setUri(uri)
        .setMimeType(mimeType)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
        .setSubtitleConfigurations(
            subtitles.map { subtitle ->
                MediaItem.SubtitleConfiguration.Builder(subtitle.uri)
                    .setMimeType(subtitleMimeType(subtitle.uri.toString()))
                    .setLabel(subtitle.label)
                    .setSelectionFlags(if (subtitle.selected) C.SELECTION_FLAG_DEFAULT else 0)
                    .build()
            },
        )
        .build()

    companion object {
        fun fromIntent(intent: Intent): PlaybackRequest? {
            val uri = intent.data ?: return null
            val extras = intent.extras ?: Bundle.EMPTY
            return PlaybackRequest(
                uri = uri,
                mimeType = streamMimeType(intent.type),
                title = extras.getCharSequence(EXTRA_TITLE)?.toString()
                    ?: extras.getCharSequence(Intent.EXTRA_TITLE)?.toString()
                    ?: titleFromPathSegment(uri.lastPathSegment),
                startPositionMs = startPosition(extras),
                headers = parseHeaders(extras.getStringArray(EXTRA_HEADERS)),
                subtitles = subtitles(intent, extras),
            )
        }

        private fun startPosition(extras: Bundle): Long {
            if (extras.getBoolean(EXTRA_VLC_FROM_START)) return 0
            // MX Player sends an int, VLC a long.
            @Suppress("DEPRECATION")
            val position = extras.get(EXTRA_POSITION) as? Number
            return position?.toLong()?.coerceAtLeast(0) ?: 0
        }

        private fun subtitles(intent: Intent, extras: Bundle): List<Subtitle> {
            val uris = IntentCompat.getParcelableArrayExtra(intent, EXTRA_SUBS, Uri::class.java)
                ?.filterIsInstance<Uri>()
                .orEmpty()
            val names = extras.getStringArray(EXTRA_SUBS_NAME)
            val enabled = IntentCompat.getParcelableArrayExtra(intent, EXTRA_SUBS_ENABLE, Uri::class.java)
                ?.filterIsInstance<Uri>()
                ?.toSet()
                .orEmpty()
            val mxSubtitles = uris.mapIndexed { index, uri ->
                Subtitle(uri, names?.getOrNull(index), selected = uri in enabled)
            }
            val vlcSubtitle = extras.getString(EXTRA_VLC_SUBTITLES)?.let { location ->
                val uri = Uri.parse(location)
                Subtitle(if (uri.scheme == null) Uri.fromFile(File(location)) else uri, null, selected = true)
            }
            return mxSubtitles + listOfNotNull(vlcSubtitle)
        }

        private const val EXTRA_TITLE = "title"
        private const val EXTRA_POSITION = "position"
        private const val EXTRA_HEADERS = "headers"
        private const val EXTRA_SUBS = "subs"
        private const val EXTRA_SUBS_NAME = "subs.name"
        private const val EXTRA_SUBS_ENABLE = "subs.enable"
        private const val EXTRA_VLC_FROM_START = "from_start"
        private const val EXTRA_VLC_SUBTITLES = "subtitles_location"
    }
}

/**
 * Only adaptive formats need an explicit MIME type; progressive files (mp4, mkv, webm, ...) are
 * sniffed by Media3, so a generic `video/...` type from the caller is better ignored.
 */
@OptIn(UnstableApi::class)
internal fun streamMimeType(intentType: String?): String? = when (intentType?.lowercase()) {
    "application/x-mpegurl", "application/vnd.apple.mpegurl" -> MimeTypes.APPLICATION_M3U8
    "application/dash+xml" -> MimeTypes.APPLICATION_MPD
    else -> null
}

/** MX Player's `headers` extra is a flat array of alternating names and values. */
internal fun parseHeaders(flat: Array<String>?): Map<String, String> {
    if (flat == null) return emptyMap()
    return flat.toList()
        .chunked(2)
        .filter { it.size == 2 && it[0].isNotBlank() }
        .associate { (name, value) -> name to value }
}

/** Media3 can't sniff side-loaded subtitle formats; guess from the URL and fall back to SubRip. */
@OptIn(UnstableApi::class)
internal fun subtitleMimeType(url: String): String {
    val path = url.substringBefore('#').substringBefore('?').lowercase()
    return when {
        path.endsWith(".vtt") || path.endsWith(".webvtt") -> MimeTypes.TEXT_VTT
        path.endsWith(".ass") || path.endsWith(".ssa") -> MimeTypes.TEXT_SSA
        path.endsWith(".ttml") || path.endsWith(".dfxp") || path.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.APPLICATION_SUBRIP
    }
}

/**
 * Uses the file name as a title when it looks like one. Stremio's torrent URLs end in a bare file
 * index (`.../<infohash>/0`), which isn't worth showing.
 */
internal fun titleFromPathSegment(segment: String?): String? =
    segment?.takeIf { '.' in it && it.length > 2 }
