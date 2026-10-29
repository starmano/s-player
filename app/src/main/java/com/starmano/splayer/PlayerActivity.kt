package com.starmano.splayer

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.DrawableRes
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ErrorMessageProvider
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import com.starmano.splayer.databinding.ActivityPlayerBinding
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen video player that shrinks into a floating picture-in-picture window, the way
 * Netflix and YouTube do:
 *
 * - Leaving the app while a video plays (home button or gesture) moves it into PiP: Android 12+
 *   does this itself via auto-enter, older versions through [onUserLeaveHint]. The PiP button in
 *   the top bar does the same on demand.
 * - The window matches the video's aspect ratio, animates from the video's on-screen bounds and
 *   offers rewind / play-pause / forward buttons.
 * - Expanding the window goes back to full screen; closing it stops playback.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var tracksPanel: TracksPanel
    private var request: PlaybackRequest? = null
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    // Carried over when the player is released in onStop and rebuilt in onStart.
    private var resumePositionMs = C.TIME_UNSET
    private var resumePlayWhenReady = true
    private var trackSelectionParameters: TrackSelectionParameters? = null

    // Display size of the current video, kept while the player is released so PiP keeps its shape.
    private var videoWidth = 0
    private var videoHeight = 0

    private val supportsPip by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }
    private val maxPipActions by lazy { maxNumPictureInPictureActions }

    // Playback pauses while the audio & subtitles panel is open and resumes when it closes.
    private var resumeAfterTracksPanel = false
    private val closeTracksPanelOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = tracksPanel.hide()
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_VIDEO_SIZE_CHANGED)) {
                val size = player.videoSize
                if (size.width > 0 && size.height > 0) {
                    videoWidth = (size.width * size.pixelWidthHeightRatio).roundToInt()
                    videoHeight = size.height
                }
            }
            if (events.containsAny(
                    Player.EVENT_PLAY_WHEN_READY_CHANGED,
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_VIDEO_SIZE_CHANGED,
                )
            ) {
                binding.playerView.keepScreenOn = player.isPlaybackActive()
                updatePipParams()
            }
            if (events.contains(Player.EVENT_TRACKS_CHANGED) && tracksPanel.isShowing) {
                tracksPanel.refresh()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) finish()
        }

        override fun onPlayerError(error: PlaybackException) {
            // A live stream that fell too far behind jumps back to the live edge.
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player?.run {
                    seekToDefaultPosition()
                    prepare()
                }
            }
        }
    }

    /** Receives taps on the buttons inside the PiP window. */
    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val player = player ?: return
            when (intent.getIntExtra(EXTRA_CONTROL, 0)) {
                CONTROL_PLAY_PAUSE -> player.togglePlayPause()
                CONTROL_REWIND -> player.seekBack()
                CONTROL_FORWARD -> player.seekForward()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ContextCompat.registerReceiver(
            this,
            pipActionReceiver,
            IntentFilter(ACTION_PIP_CONTROL),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        request = PlaybackRequest.fromIntent(intent)
        if (request == null) {
            Toast.makeText(this, R.string.error_no_stream, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (savedInstanceState != null) {
            resumePositionMs = savedInstanceState.getLong(KEY_POSITION, C.TIME_UNSET)
            resumePlayWhenReady = false
        }

        setUpWindow()
        setUpControls()
        bindTitle()
        tracksPanel = TracksPanel(
            binding.tracksPanel,
            binding.playerView.subtitleView,
            getSharedPreferences(PREFERENCES, MODE_PRIVATE),
            ::onTracksPanelVisibilityChanged,
        )
        onBackPressedDispatcher.addCallback(this, closeTracksPanelOnBack)
        mentionAvailableUpdate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Another stream was opened while this one is playing (possibly in PiP): replace it.
        val newRequest = PlaybackRequest.fromIntent(intent) ?: return
        setIntent(intent)
        request = newRequest
        resumePositionMs = C.TIME_UNSET
        resumePlayWhenReady = true
        trackSelectionParameters = null
        videoWidth = 0
        videoHeight = 0
        bindTitle()
        player?.let { player ->
            player.trackSelectionParameters = TrackSelectionParameters.getDefaults(this)
            load(player, newRequest)
        }
    }

    override fun onStart() {
        super.onStart()
        initializePlayer()
    }

    override fun onStop() {
        super.onStop()
        // Not called when entering PiP (the activity is only paused), but called when the PiP
        // window is closed, the screen turns off, or the app goes to the background without PiP.
        releasePlayer()
    }

    override fun onDestroy() {
        unregisterReceiver(pipActionReceiver)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_POSITION, player?.currentPosition ?: resumePositionMs)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaybackActive() != true) return
        // Hide the controls so that only the video shrinks into the PiP window.
        binding.playerView.hideController()
        // Android 12+ enters PiP on its own because auto-enter is enabled while playing.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) enterPip()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            tracksPanel.hide()
            binding.playerView.useController = false
            binding.topBar.isVisible = false
        } else if (lifecycle.currentState == Lifecycle.State.CREATED) {
            // The PiP window was dismissed rather than expanded, so playback is over.
            finishAndRemoveTask()
        } else {
            binding.playerView.useController = true
            hideSystemBars()
        }
    }

    private fun initializePlayer() {
        val request = request ?: return
        if (player != null) return
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        // Many phones can't decode the Dolby Digital, DTS and TrueHD audio common in movie releases,
        // which would play the video without sound. The bundled FFmpeg decoder takes over for any
        // audio format the phone can't handle itself.
        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val player = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
            .build()
        trackSelectionParameters?.let { player.trackSelectionParameters = it }
        player.addListener(playerListener)
        binding.playerView.player = player
        // Lets headset buttons and other media controllers drive playback.
        mediaSession = MediaSession.Builder(this, player)
            .setId(UUID.randomUUID().toString())
            .build()
        this.player = player
        tracksPanel.player = player
        load(player, request)
    }

    private fun load(player: ExoPlayer, request: PlaybackRequest) {
        val startPositionMs = when {
            resumePositionMs != C.TIME_UNSET -> resumePositionMs
            request.startPositionMs > 0 -> request.startPositionMs
            else -> C.TIME_UNSET
        }
        val mediaSource = mediaSourceFactory(request).createMediaSource(request.toMediaItem())
        player.setMediaSource(mediaSource, startPositionMs)
        player.playWhenReady = resumePlayWhenReady
        player.prepare()
    }

    private fun releasePlayer() {
        val player = player ?: return
        resumePositionMs = player.currentPosition
        // Coming back from the background shouldn't start blasting audio; wait for play.
        resumePlayWhenReady = false
        trackSelectionParameters = player.trackSelectionParameters
        mediaSession?.release()
        mediaSession = null
        tracksPanel.player = null
        binding.playerView.player = null
        player.release()
        this.player = null
    }

    private fun mediaSourceFactory(request: PlaybackRequest): DefaultMediaSourceFactory {
        val userAgent = request.headers.entries
            .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
            ?.value
            ?: Util.getUserAgent(this, USER_AGENT_NAME)
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(request.headers)
            .setAllowCrossProtocolRedirects(true)
            // Torrents served by Stremio's local streaming server can be slow to send first bytes.
            .setConnectTimeoutMs(HTTP_TIMEOUT_MS)
            .setReadTimeoutMs(HTTP_TIMEOUT_MS)
        return DefaultMediaSourceFactory(DefaultDataSource.Factory(this, httpFactory))
    }

    private fun setUpWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        // Keep the video and controls clear of camera cutouts.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            view.setPadding(cutout.left, cutout.top, cutout.right, cutout.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun setUpControls() {
        binding.playerView.apply {
            setShowPreviousButton(false)
            setShowNextButton(false)
            // Subtitles are picked in the audio & subtitles panel instead.
            setShowSubtitleButton(false)
            setErrorMessageProvider(
                ErrorMessageProvider<PlaybackException> { error ->
                    android.util.Pair.create(0, getString(R.string.error_playback, error.errorCodeName))
                },
            )
            setControllerVisibilityListener(
                PlayerView.ControllerVisibilityListener { visibility ->
                    val visible = visibility == View.VISIBLE
                    binding.topBar.isVisible = visible
                    if (!visible) hideSystemBars()
                },
            )
            // The PiP animation starts from the video's bounds, so keep them up to date.
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePipParams() }
        }
        binding.backButton.setOnClickListener { finish() }
        binding.tracksButton.setOnClickListener { openTracksPanel() }
        binding.pipButton.isVisible = supportsPip
        binding.pipButton.setOnClickListener {
            if (!enterPip()) {
                Toast.makeText(this, R.string.error_pip_unavailable, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openTracksPanel() {
        resumeAfterTracksPanel = player?.isPlaybackActive() == true
        player?.pause()
        // Keeps the controls from popping up behind the panel while paused.
        binding.playerView.useController = false
        tracksPanel.show()
    }

    private fun onTracksPanelVisibilityChanged(visible: Boolean) {
        closeTracksPanelOnBack.isEnabled = visible
        if (visible) return
        if (isInPictureInPictureMode) return
        binding.playerView.useController = true
        if (resumeAfterTracksPanel) {
            resumeAfterTracksPanel = false
            player?.play()
        } else {
            binding.playerView.showController()
        }
    }

    /**
     * S Player is mostly opened through Stremio, so once a day the player checks for a newer
     * version and points to the app's launcher screen, where it can be installed.
     */
    private fun mentionAvailableUpdate() {
        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - preferences.getLong(KEY_LAST_UPDATE_CHECK, 0) < UPDATE_CHECK_INTERVAL_MS) return
        preferences.edit { putLong(KEY_LAST_UPDATE_CHECK, now) }
        lifecycleScope.launch {
            val latest = withContext(Dispatchers.IO) { runCatching { Updates.latestBuild() }.getOrNull() }
            if (latest != null && latest > Updates.currentBuild(this@PlayerActivity)) {
                Toast.makeText(this@PlayerActivity, R.string.update_available_toast, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun bindTitle() {
        binding.title.text = request?.title.orEmpty()
    }

    private fun enterPip(): Boolean {
        if (!supportsPip) return false
        if (isInPictureInPictureMode) return true
        binding.playerView.hideController()
        return try {
            enterPictureInPictureMode(buildPipParams())
        } catch (e: IllegalStateException) {
            false
        }
    }

    private fun updatePipParams() {
        if (supportsPip) setPictureInPictureParams(buildPipParams())
    }

    private fun buildPipParams(): PictureInPictureParams {
        val (numerator, denominator) = pipAspectRatio(videoWidth, videoHeight)
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(numerator, denominator))
            .setActions(pipActions())
        videoBoundsInWindow()?.let(builder::setSourceRectHint)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(player?.isPlaybackActive() == true)
        }
        return builder.build()
    }

    /** Where the video is drawn on screen, so PiP can animate from exactly there. */
    private fun videoBoundsInWindow(): Rect? {
        val view = binding.playerView
        if (view.width == 0 || view.height == 0) return null
        val aspect = if (videoWidth > 0 && videoHeight > 0) {
            videoWidth.toFloat() / videoHeight
        } else {
            16f / 9f
        }
        val box = fitCenter(view.width, view.height, aspect)
        val location = IntArray(2).also(view::getLocationInWindow)
        return Rect(box.left, box.top, box.right, box.bottom).apply { offset(location[0], location[1]) }
    }

    private fun pipActions(): List<RemoteAction> {
        val playPause = if (player?.isPlaybackActive() == true) {
            remoteAction(CONTROL_PLAY_PAUSE, R.drawable.ic_pause, R.string.pause)
        } else {
            remoteAction(CONTROL_PLAY_PAUSE, R.drawable.ic_play, R.string.play)
        }
        val actions = listOf(
            remoteAction(CONTROL_REWIND, R.drawable.ic_rewind, R.string.rewind),
            playPause,
            remoteAction(CONTROL_FORWARD, R.drawable.ic_forward, R.string.fast_forward),
        )
        return if (actions.size <= maxPipActions) actions else listOf(playPause)
    }

    private fun remoteAction(control: Int, @DrawableRes icon: Int, @StringRes label: Int): RemoteAction {
        val intent = Intent(ACTION_PIP_CONTROL)
            .setPackage(packageName)
            .putExtra(EXTRA_CONTROL, control)
        val pendingIntent =
            PendingIntent.getBroadcast(this, control, intent, PendingIntent.FLAG_IMMUTABLE)
        val text = getString(label)
        return RemoteAction(Icon.createWithResource(this, icon), text, text, pendingIntent)
    }

    private companion object {
        const val ACTION_PIP_CONTROL = "com.starmano.splayer.action.PIP_CONTROL"
        const val EXTRA_CONTROL = "control"
        const val CONTROL_PLAY_PAUSE = 1
        const val CONTROL_REWIND = 2
        const val CONTROL_FORWARD = 3

        const val SEEK_INCREMENT_MS = 10_000L
        const val HTTP_TIMEOUT_MS = 30_000
        const val USER_AGENT_NAME = "SPlayer"
        const val KEY_POSITION = "position"
        const val PREFERENCES = "player"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }
}

/** True while playback is running or about to (buffering), as opposed to paused or stopped. */
private fun Player.isPlaybackActive(): Boolean =
    playWhenReady && (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING)

private fun Player.togglePlayPause() {
    if (isPlaybackActive()) {
        pause()
        return
    }
    when (playbackState) {
        Player.STATE_IDLE -> prepare() // Retry after an error.
        Player.STATE_ENDED -> seekToDefaultPosition()
    }
    play()
}
