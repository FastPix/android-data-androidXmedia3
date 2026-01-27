package io.fastpix.reel

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.SeekBar
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import io.fastpix.data.domain.model.CustomDataDetails
import io.fastpix.data.domain.model.PlayerDataDetails
import io.fastpix.data.domain.model.VideoDataDetails
import io.fastpix.data.exo.FastPixBaseMedia3Player
import io.fastpix.media3.databinding.ActivityReelBinding
import io.fastpix.media3.databinding.ItemVideoBinding
import io.fastpix.reelapp.VideoItem
import java.util.UUID
import java.util.concurrent.TimeUnit

@OptIn(UnstableApi::class)
class ReelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReelBinding
    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var hideControlsRunnable: Runnable? = null
    private var progressUpdateRunnable: Runnable? = null
    private var isUserSeeking = false
    private var currentViewHolder: VideoAdapter.ViewHolder? = null
    private var fastPixDataSDK: FastPixBaseMedia3Player? = null
    private var currentPosition = 0

    // Video URLs list
    private val videoUrls = listOf(
        "https://stream.fastpix.app/318097e1-2a96-4c02-ac39-16d357d47740.m3u8",
        "https://stream.fastpix.app/2fad9e00-2d66-40cb-a8a8-ee086810b380.m3u8",
        "https://stream.fastpix.app/99adf6d7-fa27-4b73-b650-f485735a0e79.m3u8",
        "https://stream.fastpix.app/5e9fda86-808f-433d-9219-c37243a56f8c.m3u8",
        "https://stream.fastpix.app/a20f3f13-77aa-4aff-82ff-10f84fb6a5c6.m3u8",
        "https://stream.fastpix.app/e71c5ef5-08ec-4054-9175-6ad05f3bcd88.m3u8",
        "https://stream.fastpix.app/9c7a49f0-2d61-40e6-aea8-ae27de678589.m3u8",
        "https://stream.fastpix.app/e35c9ffb-70b0-4778-8ccc-9607669ae858.m3u8",
        "https://stream.fastpix.app/e44e3327-9f8c-4371-ae24-b010c02784f1.m3u8",
        "https://stream.fastpix.app/f00fb0fb-f94b-4c69-9d87-3c4d70f72b04.m3u8"
    )

    private fun monitorPlayerThroughFastPix(playerView: PlayerView, position: Int) {

        val videoDataDetails =
            VideoDataDetails(videoItems[position].id, videoItems[position].url).apply {
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
            playerView = playerView, // media3 playerView from XML
            exoPlayer = player!!, // media3 player
            workSpaceId = "1115905885550051330",
            enableLogging = true,
            playerDataDetails = playerDataDetails,
            videoDataDetails = videoDataDetails,
            customDataDetails = customDataDetails
        )
    }

    private val videoItems: List<VideoItem> = videoUrls.mapIndexed { index, url ->
        VideoItem(url, "video_$index")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Hide system bars for full-screen experience
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        binding = ActivityReelBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initializePlayer()
        setupRecyclerView()
    }

    /**
     * Player Setup
     * Initializes a single ExoPlayer instance that will be reused for all videos.
     * We swap MediaItems when scrolling to different videos.
     */
    private fun initializePlayer() {
        player = ExoPlayer.Builder(this).build().apply {
            // Enable looping
            repeatMode = Player.REPEAT_MODE_ONE

            // Listen for playback state changes
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    currentViewHolder?.let { holder ->
                        when (playbackState) {
                            Player.STATE_BUFFERING -> {
                                holder.binding.loadingIndicator.visibility = View.VISIBLE
                            }

                            Player.STATE_READY -> {
                                holder.binding.loadingIndicator.visibility = View.GONE
                                // Update initial time display
                                val duration = duration
                                if (duration > 0) {
                                    updateTimeDisplay(holder.binding, currentPosition, duration)
                                }
                                // Start progress updates
                                startProgressUpdates()
                            }

                            Player.STATE_ENDED -> {
                                // Video ended - will loop automatically
                            }

                            Player.STATE_IDLE -> {
                                // Nothing
                            }
                        }
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    currentViewHolder?.let { holder ->
                        if (!isPlaying && !isUserSeeking) {
                            showPlayIcon(holder.binding)
                        } else {
                            hidePlayIcon(holder.binding)
                        }
                    }
                }
            })
        }
    }

    /**
     * RecyclerView Setup with Snap Scrolling
     * Creates a vertical RecyclerView with snap scrolling (Instagram Reels-like)
     * Each item is full-screen and snaps into place when scrolling stops.
     */
    private fun setupRecyclerView() {
        val layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        binding.recyclerView.layoutManager = layoutManager

        // Add PagerSnapHelper for snap scrolling (one item at a time)
        val snapHelper = PagerSnapHelper()
        snapHelper.attachToRecyclerView(binding.recyclerView)

        val adapter = VideoAdapter(videoItems) { holder, video, position ->
            // Bind video item to view holder
            bindVideoItem(holder, video, position)
        }
        binding.recyclerView.adapter = adapter

        // Listen for scroll events to switch videos
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    // Scrolling stopped - find the current visible item
                    val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
                    val firstVisiblePosition =
                        layoutManager?.findFirstCompletelyVisibleItemPosition()
                            ?: layoutManager?.findFirstVisibleItemPosition() ?: -1

                    if (firstVisiblePosition >= 0 && firstVisiblePosition != currentPosition) {
                        // Switch to new video
                        switchToVideo(firstVisiblePosition)
                    }
                }
            }
        })

        // Setup tap gesture for play/pause
        setupTapGesture()

        // Load first video after layout
        binding.recyclerView.post {
            switchToVideo(0)
        }
    }

    /**
     * Bind Video Item
     * Sets up the view holder with tap gesture and attaches player when visible
     */
    private fun bindVideoItem(holder: VideoAdapter.ViewHolder, video: VideoItem, position: Int) {
        // Setup tap gesture for this item
        setupItemTapGesture(holder.binding)
        setupItemSeekBar(holder.binding)
    }

    /**
     * Switch to Video
     * Switches the player to a new video when user scrolls to a different item.
     * This is called when scrolling stops and a new item is fully visible.
     */
    private fun switchToVideo(position: Int) {
        if (position < 0 || position >= videoItems.size) return
        // Stop progress updates for previous video
        stopProgressUpdates()

        // Hide controls from previous video
        currentViewHolder?.let { prevHolder ->
            prevHolder.binding.controlsContainer.visibility = View.GONE
            prevHolder.binding.playPauseIcon.visibility = View.GONE
        }

        val viewHolder =
            binding.recyclerView.findViewHolderForAdapterPosition(position) as? VideoAdapter.ViewHolder

        if (viewHolder != null) {
            currentViewHolder = viewHolder
            currentPosition = position

            val video = videoItems[position]

            // Release previous SDK instance before switching to new video
            fastPixDataSDK?.release()

            // Attach player to new view
            player?.let { player ->
                // Detach from previous view (setting player to null automatically detaches)
                currentViewHolder?.binding?.playerView?.player = null

                // Set new media item
                val mediaItem = MediaItem.fromUri(video.url)
                player.setMediaItem(mediaItem)

                // Attach player to PlayerView (this automatically handles surface management)
                viewHolder.binding.playerView.player = player

                // Initialize FastPix SDK before video starts (before prepare)
                monitorPlayerThroughFastPix(viewHolder.binding.playerView, position)

                // Prepare and auto-play
                player.prepare()
                player.playWhenReady = true
            }
        } else {
            // ViewHolder not available yet, try again after a short delay
            handler.postDelayed({
                switchToVideo(position)
            }, 100)
        }
    }

    /**
     * Tap Gesture Handling for RecyclerView
     * Detects single tap to toggle play/pause and show/hide controls.
     */
    private fun setupTapGesture() {
        val gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    currentViewHolder?.let { holder ->
                        togglePlayPause()
                        showControlsTemporarily(holder.binding)
                    }
                    return true
                }
            }
        )

        binding.recyclerView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
        }
    }

    /**
     * Setup tap gesture for individual video item
     */
    private fun setupItemTapGesture(binding: ItemVideoBinding) {
        val gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    if (binding.root.isAttachedToWindow) {
                        togglePlayPause()
                        showControlsTemporarily(binding)
                    }
                    return true
                }
            }
        )

        binding.root.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
        }
    }

    /**
     * Toggle play/pause and show play icon when paused
     */
    private fun togglePlayPause() {
        player?.let {
            if (it.isPlaying) {
                it.pause()
                currentViewHolder?.let { holder -> showPlayIcon(holder.binding) }
            } else {
                it.play()
                currentViewHolder?.let { holder -> hidePlayIcon(holder.binding) }
            }
        }
    }

    /**
     * Show play icon with fade-in animation
     */
    private fun showPlayIcon(binding: ItemVideoBinding) {
        binding.playPauseIcon.apply {
            visibility = View.VISIBLE
            alpha = 0f
            animate()
                .alpha(1f)
                .setDuration(200)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    /**
     * Hide play icon with fade-out animation
     */
    private fun hidePlayIcon(binding: ItemVideoBinding) {
        binding.playPauseIcon.animate()
            .alpha(0f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                binding.playPauseIcon.visibility = View.GONE
            }
            .start()
    }

    /**
     * SeekBar Sync Logic
     * Sets up SeekBar to update with video progress and handle user seeking.
     */
    private fun setupItemSeekBar(binding: ItemVideoBinding) {
        binding.seekBar.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar?,
                progress: Int,
                fromUser: Boolean
            ) {
                if (fromUser) {
                    isUserSeeking = true
                    player?.let { player ->
                        val duration = player.duration
                        if (duration > 0) {
                            val position = progress * duration / 1000
                            updateTimeDisplay(binding, position, duration)
                        }
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
                currentViewHolder?.let { holder ->
                    showControlsTemporarily(holder.binding)
                }
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                player?.let { player ->
                    val duration = player.duration
                    if (duration > 0) {
                        val position = (seekBar?.progress ?: 0) * duration / 1000
                        player.seekTo(position.toLong())
                    }
                }
                isUserSeeking = false
            }
        })
    }

    /**
     * Start progress updates to sync SeekBar with video playback
     */
    private fun startProgressUpdates() {
        progressUpdateRunnable = object : Runnable {
            override fun run() {
                player?.let { player ->
                    currentViewHolder?.let { holder ->
                        if (player.isPlaying || player.playbackState == Player.STATE_BUFFERING) {
                            val duration = player.duration
                            val position = player.currentPosition

                            if (duration > 0 && !isUserSeeking) {
                                // Update SeekBar progress (0-1000 scale)
                                val progress = (position * 1000 / duration).toInt()
                                holder.binding.seekBar.progress = progress
                                updateTimeDisplay(holder.binding, position, duration)
                            }
                        }
                    }
                }
                // Update every 100ms for smooth progress
                handler.postDelayed(this, 100)
            }
        }
        handler.post(progressUpdateRunnable!!)
    }

    /**
     * Stop progress updates
     */
    private fun stopProgressUpdates() {
        progressUpdateRunnable?.let {
            handler.removeCallbacks(it)
        }
        progressUpdateRunnable = null
    }

    /**
     * Update time display text (mm:ss format)
     */
    private fun updateTimeDisplay(
        binding: ItemVideoBinding,
        currentPosition: Long,
        duration: Long
    ) {
        val currentMinutes = TimeUnit.MILLISECONDS.toMinutes(currentPosition)
        val currentSeconds = TimeUnit.MILLISECONDS.toSeconds(currentPosition) % 60
        val durationMinutes = TimeUnit.MILLISECONDS.toMinutes(duration)
        val durationSeconds = TimeUnit.MILLISECONDS.toSeconds(duration) % 60

        binding.timeDisplay.text = String.format(
            "%02d:%02d / %02d:%02d",
            currentMinutes,
            currentSeconds,
            durationMinutes,
            durationSeconds
        )
    }

    /**
     * Auto-hide Logic
     * Shows controls on touch and automatically hides them after 2 seconds of inactivity.
     */
    private fun showControlsTemporarily(binding: ItemVideoBinding) {
        // Cancel any existing hide runnable
        hideControlsRunnable?.let {
            handler.removeCallbacks(it)
        }

        // Show controls with fade-in animation
        binding.controlsContainer.apply {
            visibility = View.VISIBLE
            animate()
                .alpha(1.0f)
                .setDuration(200)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        // Schedule auto-hide after 2 seconds
        hideControlsRunnable = Runnable {
            binding.controlsContainer.animate()
                .alpha(0.0f)
                .setDuration(200)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    binding.controlsContainer.visibility = View.GONE
                }
                .start()
        }
        handler.postDelayed(hideControlsRunnable!!, 2000)
    }

    override fun onResume() {
        super.onResume()
        // Resume playback when activity resumes
        player?.play()
    }

    override fun onPause() {
        super.onPause()
        // Pause playback when activity is paused
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up: stop progress updates, remove callbacks, and release player
        stopProgressUpdates()
        hideControlsRunnable?.let {
            handler.removeCallbacks(it)
        }
        fastPixDataSDK?.release()
        player?.release()
        player = null
    }
}
