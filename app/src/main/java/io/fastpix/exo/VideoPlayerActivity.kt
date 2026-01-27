package io.fastpix.exo

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowInsetsController
import android.widget.SeekBar
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.fastpix.data.domain.model.CustomDataDetails
import io.fastpix.data.domain.model.PlayerDataDetails
import io.fastpix.data.domain.model.VideoDataDetails
import io.fastpix.data.exo.FastPixBaseMedia3Player
import io.fastpix.media3.databinding.ActivityVideoPlayerBinding
import java.util.Locale

@OptIn(UnstableApi::class)
class VideoPlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityVideoPlayerBinding
    private lateinit var exoPlayer: ExoPlayer
    private var isFullscreen = false
    private var controlsVisible = true
    private val controlsHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideControls() }
    private lateinit var fastPixDataSDK: FastPixBaseMedia3Player
    private var videoModel: DummyData? = null


    // Flag to track if fullscreen button was just pressed
    private var userTriggeredFullscreen = false

    // Network connectivity monitoring
    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wasPlayingBeforeNetworkLoss = false
    private var hadNetworkError = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            videoModel = intent.getParcelableExtra("video_model", DummyData::class.java)
        }

        // Allow sensor-based rotation at all times
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR

        // Initialize connectivity manager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        setupPlayer()
        setupControls()
        setupBackPressHandler()
        setupNetworkMonitoring()

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Check if error is network related
                val isNetworkError = isNetworkRelatedError(error)
                if (isNetworkError) {
                    hadNetworkError = true
                    wasPlayingBeforeNetworkLoss = true
                    // For network errors, show loader instead of error UI
                    binding.loadingIndicator.isVisible = true
                    binding.errorContainer.isVisible = false
                } else {
                    // For non-network errors, show error UI
                    binding.loadingIndicator.isVisible = false
                    binding.errorContainer.isVisible = true
                    binding.errorMessage.text = error.cause?.message
                }
            }
        })
        monitorPlayerThroughFastPix()
    }

    private fun monitorPlayerThroughFastPix() {

        val videoDataDetails = VideoDataDetails("video-id", "video-title").apply {
            videoSeries = "video-series"
            videoProducer = "video-producer"
            videoContentType = "video-content-type"
            videoVariant = "video-variant"
            videoLanguage = "video-language"
            videoDuration = "video-duration"
            videoDrmType = "widevine"
            //...etc
        }
        val customDataDetails = CustomDataDetails().apply {
            customField1 = "Custom 1"
            customField2 = "Custom 2"
            //...etc
        }
        // By Default player sets these things, You don't have to worry about it unless you're not using
        // some wrapper around media3
        val playerDataDetails = PlayerDataDetails(
            playerName = "media3",
            playerVersion = "latest-version"
        )
        fastPixDataSDK = FastPixBaseMedia3Player(
            this, // context
            playerView = binding.playerView, // media3 playerView from XML
            exoPlayer = exoPlayer, // media3 player
            workSpaceId = "1109888358169935873",
            enableLogging = false,
            playerDataDetails = playerDataDetails,
            videoDataDetails = videoDataDetails,
            customDataDetails = customDataDetails
        )
    }

    private fun setupPlayer() {
        // Create ExoPlayer instance
        exoPlayer = ExoPlayer.Builder(this).build()

        // Set player to PlayerView
        binding.playerView.player = exoPlayer
        // Create MediaItem from URL
        val mediaItem = MediaItem.fromUri(videoModel?.url.orEmpty())
        // Set media item and prepare
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        // Set up player listener
        Handler(Looper.getMainLooper()).postDelayed({
            exoPlayer.seekTo(exoPlayer.currentWindowIndex, 10000)
        }, 500)
        // Set up player listener
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        binding.loadingIndicator.visibility = View.VISIBLE
                        Log.d(TAG, "Player state: BUFFERING")
                    }

                    Player.STATE_READY -> {
                        binding.loadingIndicator.visibility = View.GONE
                        // Clear network error flag when player is ready
                        if (hadNetworkError) {
                            hadNetworkError = false
                        }
                        updateDuration()
                    }

                    Player.STATE_ENDED -> {
                        binding.loadingIndicator.visibility = View.GONE
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseButton(isPlaying)
                // Hide loader when video starts playing (especially after network recovery)
                if (isPlaying) {
                    binding.loadingIndicator.visibility = View.GONE
                    if (hadNetworkError) {
                        hadNetworkError = false
                    }
                }
            }
        })
    }

    private fun setupControls() {
        binding.playPauseButton.setOnClickListener {
            exoPlayer.let { player ->
                if (player.isPlaying) {
                    player.pause()
                } else {
                    if (player.currentPosition >= player.duration) {
                        player.seekTo(0)
                    }
                    player.play()
                }
            }
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    exoPlayer.let { player ->
                        val duration = player.duration
                        if (duration != C.TIME_UNSET) {
                            val position = (progress * duration / 100).toLong()
                            player.seekTo(position)
                        }
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                controlsHandler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                startHideControlsTimer()
            }
        })

        // Fullscreen button
        binding.fullscreenButton.setOnClickListener {
            toggleFullscreen()
        }

        // PlayerView click to toggle controls
        binding.playerView.setOnClickListener {
            toggleControls()
        }

        // Start timer to hide controls
        startHideControlsTimer()
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isFullscreen) {
                    exitFullscreen()
                } else {
                    finish()
                }
            }
        })
    }

    private fun toggleFullscreen() {
        if (isFullscreen) {
            exitFullscreen()
        } else {
            enterFullscreen()
        }
    }

    private fun enterFullscreen() {
        isFullscreen = true
        userTriggeredFullscreen = true
        // Lock to landscape orientation
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        // After a delay, allow sensor-based rotation and clear the flag
        Handler(Looper.getMainLooper()).postDelayed({
            if (isFullscreen) {  // Only allow sensor if still in fullscreen
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
            }
            userTriggeredFullscreen = false
        }, 5000)
        hideSystemUI()
        binding.fullscreenButton.setImageResource(io.fastpix.media3.R.drawable.ic_fullscreen_exit)
    }

    private fun exitFullscreen() {
        isFullscreen = false
        userTriggeredFullscreen = true
        // Lock to portrait orientation
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        // After a delay, allow sensor-based rotation and clear the flag
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFullscreen) {  // Only allow sensor if still not in fullscreen
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
            }
            userTriggeredFullscreen = false
        }, 5000)
        showSystemUI()
        binding.fullscreenButton.setImageResource(io.fastpix.media3.R.drawable.ic_fullscreen)
    }


    @SuppressLint("InlinedApi")
    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE)
            }
        } else {
            window.decorView.systemUiVisibility =
                (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }

    @SuppressLint("InlinedApi")
    private fun showSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.show(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE)
            }
        } else {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun toggleControls() {
        if (controlsVisible) {
            hideControls()
        } else {
            showControls()
        }
    }

    private fun showControls() {
        controlsVisible = true
        binding.controlsContainer.visibility = View.VISIBLE
        startHideControlsTimer()
    }

    private fun hideControls() {
        controlsVisible = false
        binding.controlsContainer.visibility = View.GONE
    }

    private fun startHideControlsTimer() {
        controlsHandler.removeCallbacks(hideControlsRunnable)
        controlsHandler.postDelayed(hideControlsRunnable, 3000) // Hide after 3 seconds
    }

    private fun updatePlayPauseButton(isPlaying: Boolean) {
        binding.playPauseButton.setImageResource(
            if (isPlaying) io.fastpix.media3.R.drawable.ic_pause else io.fastpix.media3.R.drawable.ic_play_arrow
        )
    }

    private fun updateDuration() {
        exoPlayer.let { player ->
            val duration = player.duration
            if (duration != C.TIME_UNSET) {
                binding.durationText.text = formatTime(duration)
                binding.seekBar.max = 100

                // Update progress periodically
                updateProgress()
            }
        }
    }

    private fun updateProgress() {
        exoPlayer.let { player ->
            val currentPosition = player.currentPosition
            val duration = player.duration

            if (duration != C.TIME_UNSET) {
                val progress = (currentPosition * 100 / duration).toInt()
                binding.seekBar.progress = progress
                binding.currentTimeText.text = formatTime(currentPosition)

                // Schedule next update
                controlsHandler.postDelayed({ updateProgress() }, 1000)
            }
        }
    }

    private fun formatTime(timeMs: Long): String {
        val totalSeconds = timeMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    override fun onPause() {
        super.onPause()
        exoPlayer.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        controlsHandler.removeCallbacks(hideControlsRunnable)
        unregisterNetworkCallback()
        exoPlayer.release()
        fastPixDataSDK.release()
    }

    override fun onResume() {
        super.onResume()
        exoPlayer.play()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // Ignore configuration changes if user just pressed the fullscreen button
        if (userTriggeredFullscreen) {
            Log.d(TAG, "Ignoring configuration change - user triggered fullscreen")
            return
        }

        // Handle orientation change and sync fullscreen state
        when (newConfig.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> {
                if (!isFullscreen) {
                    isFullscreen = true
                    hideSystemUI()
                    binding.fullscreenButton.setImageResource(io.fastpix.media3.R.drawable.ic_fullscreen_exit)
                }
            }

            Configuration.ORIENTATION_PORTRAIT -> {
                if (isFullscreen) {
                    isFullscreen = false
                    showSystemUI()
                    binding.fullscreenButton.setImageResource(io.fastpix.media3.R.drawable.ic_fullscreen)
                }
            }
        }
    }

    /**
     * Sets up network connectivity monitoring to automatically resume playback
     * when internet connection is restored after a network loss
     */
    private fun setupNetworkMonitoring() {
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)

                // Resume playback on main thread when network is restored
                runOnUiThread {
                    handleNetworkRestored()
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                Log.d(TAG, "Network lost")

                runOnUiThread {
                    // Track if player was playing when network was lost
                    // Don't show loader yet - video might still play from buffer
                    // Loader will be shown only when player actually encounters an error
                    if (exoPlayer.isPlaying) {
                        wasPlayingBeforeNetworkLoss = true
                    }
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                super.onCapabilitiesChanged(network, networkCapabilities)
                val hasInternet =
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        }

        try {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    /**
     * Handles network restoration by resuming playback if it was interrupted
     * due to connectivity issues
     */
    private fun handleNetworkRestored() {
        // If player had a network error or was playing before network loss, try to resume
        if (wasPlayingBeforeNetworkLoss || hadNetworkError) {
            try {
                // Show loading indicator while re-connecting
                binding.loadingIndicator.isVisible = true

                // Retry preparation and resume playback
                exoPlayer.prepare()
                exoPlayer.play()
                wasPlayingBeforeNetworkLoss = false
                Log.d(TAG, "Attempting to resume playback after network restoration")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resume playback", e)
                // Keep showing loader - player state listeners will handle hiding it
                // or showing error if the retry also fails
            }
        }
    }

    /**
     * Checks if a PlaybackException is network-related
     */
    private fun isNetworkRelatedError(error: PlaybackException): Boolean {
        // Check for common network-related error types
        val errorMessage = error.message?.lowercase() ?: ""
        val causeMessage = error.cause?.message?.lowercase() ?: ""

        val networkKeywords = listOf(
            "network", "connection", "timeout", "unreachable",
            "unable to connect", "failed to connect", "no internet",
            "http", "socket", "dns"
        )

        return networkKeywords.any { keyword ->
            errorMessage.contains(keyword) || causeMessage.contains(keyword)
        } || error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                || error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                || error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
    }

    /**
     * Unregisters network callback to prevent memory leaks
     */
    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
                Log.d(TAG, "Network callback unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister network callback", e)
            }
        }
        networkCallback = null
    }

    companion object {
        private const val TAG = "VideoPlayerActivity"
    }
}
