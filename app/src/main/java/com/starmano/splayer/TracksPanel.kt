package com.starmano.splayer

import android.content.SharedPreferences
import android.graphics.Color
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.core.text.buildSpannedString
import androidx.core.text.inSpans
import androidx.core.view.isVisible
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.starmano.splayer.databinding.TracksPanelBinding

/**
 * Netflix-style overlay for choosing the audio track, the subtitle track and how subtitles look.
 *
 * Each track is listed by language (dubbed movies have one audio track per language) with its
 * name, kind, channels and format underneath. Unlike Media3's built-in menus it also lists
 * tracks the phone can't decode, marked as such, and it remembers the subtitle size and style.
 */
@OptIn(UnstableApi::class)
class TracksPanel(
    private val binding: TracksPanelBinding,
    private val subtitleView: SubtitleView?,
    private val preferences: SharedPreferences,
    private val onVisibilityChanged: (visible: Boolean) -> Unit,
) {
    var player: Player? = null

    val isShowing: Boolean
        get() = binding.root.isVisible

    private val context = binding.root.context
    private val detailColor = context.getColor(R.color.text_secondary)

    private var subtitleSize = SubtitleSize.entries
        .firstOrNull { it.name == preferences.getString(KEY_SUBTITLE_SIZE, null) }
        ?: SubtitleSize.NORMAL
    private var subtitleBox = preferences.getBoolean(KEY_SUBTITLE_BOX, false)

    init {
        binding.closeButton.setOnClickListener { hide() }
        setUpSubtitleAppearance()
    }

    fun show() {
        refresh()
        binding.root.isVisible = true
        onVisibilityChanged(true)
    }

    fun hide() {
        if (!isShowing) return
        binding.root.isVisible = false
        onVisibilityChanged(false)
    }

    /** Rebuilds the track lists from what the player currently offers. */
    fun refresh() {
        val tracks = player?.currentTracks ?: Tracks.EMPTY
        val audioTracks = fillTrackList(binding.audioTracks, tracks, C.TRACK_TYPE_AUDIO)
        binding.audioEmpty.isVisible = audioTracks == 0
        val subtitleTracks = fillTrackList(binding.subtitleTracks, tracks, C.TRACK_TYPE_TEXT)
        binding.subtitlesEmpty.isVisible = subtitleTracks == 0
    }

    /** Fills [list] with one option per track of [trackType] and returns how many tracks there are. */
    private fun fillTrackList(list: RadioGroup, tracks: Tracks, trackType: Int): Int {
        list.setOnCheckedChangeListener(null)
        list.removeAllViews()
        val actions = mutableMapOf<Int, () -> Unit>()
        var checkedId = View.NO_ID
        if (trackType == C.TRACK_TYPE_TEXT) {
            val off = addOption(list, context.getString(R.string.subtitles_off), enabled = true)
            actions[off.id] = ::turnSubtitlesOff
            checkedId = off.id
        }
        var count = 0
        for (group in tracks.groups) {
            if (group.type != trackType) continue
            for (index in 0 until group.length) {
                val format = group.getTrackFormat(index)
                val supported = group.isTrackSupported(index)
                // Side-loaded subtitles often carry their language only as a name like "eng".
                val language = format.language
                    ?: format.label?.takeIf(::looksLikeLanguageCode)?.let(Util::normalizeLanguageCode)
                val description = describeTrack(
                    language,
                    format.label,
                    isAudio = trackType == C.TRACK_TYPE_AUDIO,
                    format.channelCount,
                    format.sampleMimeType,
                    format.roleFlags,
                    format.selectionFlags,
                )
                val details = if (supported) {
                    description.details
                } else {
                    description.details + context.getString(R.string.track_not_supported)
                }
                val option = addOption(list, optionText(description.title, details), enabled = supported)
                actions[option.id] = { select(group, index) }
                if (group.isTrackSelected(index)) checkedId = option.id
                count++
            }
        }
        if (checkedId != View.NO_ID) list.check(checkedId)
        list.setOnCheckedChangeListener { _, id -> actions[id]?.invoke() }
        return count
    }

    /** The track's language on the first line and its details, smaller, on the second. */
    private fun optionText(title: String, details: List<String>): CharSequence {
        if (details.isEmpty()) return title
        return buildSpannedString {
            append(title)
            append('\n')
            inSpans(RelativeSizeSpan(0.8f), ForegroundColorSpan(detailColor)) {
                append(details.joinToString(" · "))
            }
        }
    }

    private fun addOption(list: RadioGroup, label: CharSequence, enabled: Boolean): RadioButton {
        val option = RadioButton(context, null, 0, R.style.TracksPanelOption).apply {
            id = View.generateViewId()
            text = label
            isEnabled = enabled
        }
        list.addView(option, RadioGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return option
    }

    private fun select(group: Tracks.Group, index: Int) {
        val player = player ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(group.type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            .build()
    }

    private fun turnSubtitlesOff() {
        val player = player ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    private fun setUpSubtitleAppearance() {
        val sizeOptions = mapOf(
            binding.sizeSmall.id to SubtitleSize.SMALL,
            binding.sizeNormal.id to SubtitleSize.NORMAL,
            binding.sizeLarge.id to SubtitleSize.LARGE,
            binding.sizeHuge.id to SubtitleSize.HUGE,
        )
        binding.subtitleSize.check(sizeOptions.entries.first { it.value == subtitleSize }.key)
        binding.subtitleSize.setOnCheckedChangeListener { _, id ->
            subtitleSize = sizeOptions[id] ?: return@setOnCheckedChangeListener
            preferences.edit { putString(KEY_SUBTITLE_SIZE, subtitleSize.name) }
            applySubtitleAppearance()
        }

        binding.subtitleStyle.check(if (subtitleBox) binding.styleBox.id else binding.styleOutline.id)
        binding.subtitleStyle.setOnCheckedChangeListener { _, id ->
            subtitleBox = id == binding.styleBox.id
            preferences.edit { putBoolean(KEY_SUBTITLE_BOX, subtitleBox) }
            applySubtitleAppearance()
        }

        applySubtitleAppearance()
    }

    private fun applySubtitleAppearance() {
        val view = subtitleView ?: return
        // Some subtitle files set their own font sizes; the chosen size should win.
        view.setApplyEmbeddedFontSizes(false)
        view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * subtitleSize.scale)
        view.setStyle(if (subtitleBox) BOX_STYLE else OUTLINE_STYLE)
    }

    private enum class SubtitleSize(val scale: Float) {
        SMALL(0.75f),
        NORMAL(1f),
        LARGE(1.35f),
        HUGE(1.7f),
    }

    private companion object {
        const val KEY_SUBTITLE_SIZE = "subtitle_size"
        const val KEY_SUBTITLE_BOX = "subtitle_box"

        val OUTLINE_STYLE = CaptionStyleCompat(
            Color.WHITE,
            Color.TRANSPARENT,
            Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            Color.BLACK,
            null,
        )
        val BOX_STYLE = CaptionStyleCompat(
            Color.WHITE,
            0xB3000000.toInt(),
            Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_NONE,
            Color.TRANSPARENT,
            null,
        )
    }
}
