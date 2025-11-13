package io.fastpix.data.exo

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import android.view.View
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.TrackGroupArray
import io.fastpix.data.FastPixDataSDK
import io.fastpix.data.domain.SDKConfiguration
import io.fastpix.data.domain.enums.PlayerEventType
import io.fastpix.data.domain.listeners.PlayerListener
import io.fastpix.data.domain.model.BandwidthModel
import io.fastpix.data.domain.model.CustomDataDetails
import io.fastpix.data.domain.model.ErrorModel
import io.fastpix.data.domain.model.PlayerDataDetails
import io.fastpix.data.domain.model.VideoDataDetails
import io.fastpix.data.exo.sdkInfo.FastPixMedia3LibraryInfo
import java.io.IOException
import kotlin.math.ceil

/**
 * FastPix ExoPlayer wrapper that automatically integrates with FastPixDataSDK
 * This wrapper provides a seamless integration between ExoPlayer and FastPix analytics
 */
@UnstableApi
class FastPixBaseMedia3Player(
    val context: Context,
    val playerView: View,
    private val exoPlayer: ExoPlayer,
    private val workSpaceId: String,
    private val beaconUrl: String? = null,
    private val enableLogging: Boolean = false,
    private val playerDataDetails: PlayerDataDetails? = null,
    private val videoDataDetails: VideoDataDetails? = null,
    private val customDataDetails: CustomDataDetails? = null,
) : PlayerListener {

    private val TAG = "FastPixBaseMedia3Player"
    private lateinit var fastPixDataSDK: FastPixDataSDK

    // State machine for valid event transitions
    private var currentEventState: PlayerEvent? = null

    // Queue for variant change events that need to be deferred until play event
    private val pendingVariantChangeEvents = mutableListOf<Boolean>()

    // Chunk downloading monitoring
    private val bandwidthDispatcher = BandwidthMetricDispatcher()
    private var renditionList: List<ChunkRendition>? = null
    private var detectMimeType: Boolean = true
    private var currentTimelineWindow: Timeline.Window = Timeline.Window()

    private var frameRate: String? = null
    private var codec: String? = null
    private var bitRate: String? = null
    private var mimeType: String? = null
    private var videoSourceWidth: Int? = null
    private var videoSourceHeight: Int? = null
    private var errorCode: String? = null
    private var errorMessage: String? = null
    private var playerCodec: String? = null
    private var isBuffering = false
    private var videoSourceUrl: String? = null
    private var isSeeking = false
    private var isEnded = false
    private var isVideoPlaying = false

    init {
        initializeFastPixSDK()
    }

    private fun initializeFastPixSDK() {
        setUpListener()
        fastPixDataSDK = FastPixDataSDK()
        val sdkConfiguration = SDKConfiguration(
            workspaceId = workSpaceId,
            beaconUrl = beaconUrl,
            playerData = playerDataDetails ?: PlayerDataDetails(
                "Exoplayer",
                "2.19.1"
            ),
            videoData = videoDataDetails,
            playerListener = this,
            enableLogging = enableLogging,
            customData = customDataDetails
        )
        fastPixDataSDK.initialize(sdkConfiguration, context)
        dispatchViewBegin()
        dispatchPlayerReadyEvent()
        dispatchPlayEvent()
    }

    private fun dispatchViewBegin() {
        if (enableLogging) {
            Log.d(TAG, "Dispatching ViewBegin event")
        }
        fastPixDataSDK.dispatchEvent(PlayerEventType.viewBegin)
    }

    private fun dispatchPlayerReadyEvent() {
        if (enableLogging) {
            Log.d(TAG, "Dispatching Play Ready event")
        }
        fastPixDataSDK.dispatchEvent(PlayerEventType.playerReady)
    }

    /**
     * Validates if the transition from current state to new state is valid
     */
    private fun isValidTransition(newEvent: PlayerEvent): Boolean {
        val allowedTransitions = validTransitions[currentEventState] ?: emptySet()
        return newEvent in allowedTransitions
    }

    /**
     * Safely transitions to a new event state if valid
     */
    private fun transitionToEvent(newEvent: PlayerEvent): Boolean {
        if (isValidTransition(newEvent)) {
            if (newEvent != PlayerEvent.VARIANT_CHANGED) {
                currentEventState = newEvent
            }
            return true
        } else {
            Log.e(TAG, "Wrong transitionToEvent: ${newEvent.name}")
            return false
        }
    }

    private var isViewBeginCalled = false

    private fun setUpListener() {
        exoPlayer.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                playerCodec = decoderName
            }

            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?
            ) {
                bitRate = format.bitrate.takeIf { it > 0 }.toString()
                frameRate = format.frameRate.takeIf { it > 0 }?.toInt().toString()
                videoSourceWidth = format.width
                videoSourceHeight = format.height
            }

            // Chunk downloading monitoring methods
            override fun onLoadStarted(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData
            ) {
                if (loadEventInfo.uri != null) {
                    var segmentMimeType = "unknown"
                    var segmentWidth = 0
                    var segmentHeight = 0
                    if (mediaLoadData.trackFormat != null) {
                        val format = mediaLoadData.trackFormat
                        if (format!!.sampleMimeType != null) {
                            segmentMimeType = format.sampleMimeType!!
                        }
                        segmentWidth = format.width
                        segmentHeight = format.height
                    }
                    try {
                        if (videoSourceUrl != null) {
                            videoSourceUrl = loadEventInfo.uri.toString()
                        }
                        val fullUri = loadEventInfo.uri.toString()
                        val hostWithScheme = "https://${loadEventInfo.uri.host}"
                        val encodedPath = fullUri.removePrefix(hostWithScheme)
                        bandwidthDispatcher.onLoadStarted(
                            loadEventInfo.loadTaskId,
                            mediaLoadData.mediaStartTimeMs,
                            mediaLoadData.mediaEndTimeMs,
                            encodedPath,
                            mediaLoadData.dataType,
                            loadEventInfo.uri.host,
                            segmentMimeType,
                            segmentWidth,
                            segmentHeight
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error handling load started: ${e.message}")
                    }
                }
            }

            override fun onLoadCompleted(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData
            ) {
                try {
                    var segmentMimeType = "unknown"
                    if (mediaLoadData.trackFormat != null) {
                        val format = mediaLoadData.trackFormat
                        if (format!!.sampleMimeType != null) {
                            segmentMimeType = format.sampleMimeType!!
                        }
                    }
                    val fullUri = loadEventInfo.uri.toString()
                    val hostWithScheme = "https://${loadEventInfo.uri.host}"
                    val encodedPath = fullUri.removePrefix(hostWithScheme)
                    bandwidthDispatcher.onLoadCompleted(
                        loadEventInfo.loadTaskId,
                        encodedPath,
                        loadEventInfo.bytesLoaded,
                        mediaLoadData.trackFormat,
                        loadEventInfo.responseHeaders,
                        mediaLoadData.dataType,
                        loadEventInfo.uri.host,
                        segmentMimeType
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling load completed: ${e.message}")
                }
            }

            override fun onLoadCanceled(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData
            ) {
                if (loadEventInfo.uri != null) {
                    try {
                        val fullUri = loadEventInfo.uri.toString()
                        val hostWithScheme = "https://${loadEventInfo.uri.host}"
                        val encodedPath = fullUri.removePrefix(hostWithScheme)
                        bandwidthDispatcher.onLoadCanceled(
                            loadEventInfo.loadTaskId,
                            encodedPath,
                            loadEventInfo.responseHeaders
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error handling load canceled: ${e.message}")
                    }
                }
            }

            override fun onLoadError(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
                error: IOException,
                wasCanceled: Boolean
            ) {
                try {
                    val fullUri = loadEventInfo.uri.toString()
                    val hostWithScheme = "https://${loadEventInfo.uri.host}"
                    val encodedPath = fullUri.removePrefix(hostWithScheme)
                    bandwidthDispatcher.onLoadError(
                        loadEventInfo.loadTaskId,
                        encodedPath,
                        error
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling load error: ${e.message}")
                }
            }
        })



        exoPlayer.addListener(object : Player.Listener {

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (videoSourceUrl != null) {
                    mediaItem?.localConfiguration?.uri?.let {
                        videoSourceUrl = it.toString()
                    }
                }
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (videoSourceUrl != null) {
                    exoPlayer.currentMediaItem?.localConfiguration?.uri?.let {
                        videoSourceUrl = it.toString()
                    }
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                super.onVideoSizeChanged(videoSize)
                if (videoSize.height > 0 && videoSize.width > 0) {
                    videoSourceWidth = videoSize.width
                    videoSourceHeight = videoSize.height
                    dispatchVariantChangeEvent()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                super.onPlaybackStateChanged(playbackState)

                when (playbackState) {
                    Player.STATE_READY -> {
                        isVideoPlaying = true
                        videoSourceHeight = exoPlayer.videoSize.height
                        videoSourceWidth = exoPlayer.videoSize.width
                        if (isBuffering) {
                            isBuffering = false
                            dispatchBufferedEvent()
                            dispatchPlayingEvent()
                        }
                        if (isSeeking) {
                            isSeeking = false
                            dispatchSeekedEvent()
                        }
                    }

                    Player.STATE_BUFFERING -> {
                        if (!isSeeking && !isBuffering) {
                            isBuffering = true
                            dispatchBufferingEvent()
                        }
                    }

                    Player.STATE_IDLE -> {
                        dispatchPauseEvent()
                    }

                    Player.STATE_ENDED -> {
                        isEnded = true
                        dispatchPauseEvent(exoPlayer.duration.toInt())
                        dispatchEndedEvent(exoPlayer.duration.toInt())
                    }
                }

            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                super.onPlayWhenReadyChanged(playWhenReady, reason)
                if (!playWhenReady) {
                    dispatchPauseEvent()
                }
            }


            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                super.onPositionDiscontinuity(oldPosition, newPosition, reason)
                if (!isSeeking && reason == Player.DISCONTINUITY_REASON_SEEK) {
                    isSeeking = true
                    val currentSeekPosition = oldPosition.positionMs.toInt()
                    if (isVideoPlaying) {
                        dispatchPauseEvent(currentSeekPosition)
                    }
                    dispatchSeekingEvent(currentSeekPosition)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    isVideoPlaying = true
                    if (currentEventState != PlayerEvent.SEEKED) {
                        dispatchPlayEvent()
                    }
                    if (isSeeking) {
                        isSeeking = true
                        dispatchSeekedEvent()
                    }
                    if (isBuffering) {
                        isBuffering = false
                        dispatchBufferedEvent()
                    }
                    dispatchPlayingEvent()
                } else {
                    isVideoPlaying = false
                    dispatchPauseEvent()
                }
            }


            override fun onTracksChanged(tracks: Tracks) {
                super.onTracksChanged(tracks)
                try {
                    val trackGroups =
                        TrackGroupArray(*tracks.groups.map { it.mediaTrackGroup }.toTypedArray())
                    if (trackGroups.length > 0) {
                        mimeType = trackGroups.get(0).getFormat(0).containerMimeType
                    }
                    bandwidthDispatcher.onTracksChanged(trackGroups)
                } catch (ex: Exception) {
                    mimeType = null
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                super.onPlayerError(error)

                // Detect Error event: can happen from any state
                if (currentEventState != PlayerEvent.ERROR) {
                    dispatchErrorEvent(error)
                }
            }
        })
    }

    private fun dispatchPlayEvent() {
        if (transitionToEvent(PlayerEvent.PLAY)) {
            if (enableLogging) {
                Log.d(TAG, "Dispatching Play event")
            }
            fastPixDataSDK.dispatchEvent(PlayerEventType.play)

            // Process any queued variant change events after play event
            processQueuedVariantChangeEvents()
        }
    }

    private fun dispatchPlayingEvent() {
        if (transitionToEvent(PlayerEvent.PLAYING)) {
            if (enableLogging) {
                Log.d(TAG, "Dispatching Playing event")
            }
            fastPixDataSDK.dispatchEvent(PlayerEventType.playing)
        }
    }

    private fun dispatchPauseEvent(currentPosition: Int? = null) {
        if (transitionToEvent(PlayerEvent.PAUSE)) {
            if (enableLogging) {
                Log.d(TAG, "Dispatching Pause event")
            }
            // Note: There's no Pause event type in PlayerEventType enum
            // For now, we'll just track the state transition without dispatching
            // You may need to add a Pause event type to PlayerEventType enum
            if (currentPosition != null) {
                fastPixDataSDK.dispatchEvent(PlayerEventType.pause, currentPosition)
            } else {
                fastPixDataSDK.dispatchEvent(PlayerEventType.pause)
            }
        }
    }

    private fun dispatchSeekingEvent(currentPosition: Int? = null) {
        if (transitionToEvent(PlayerEvent.SEEKING)) {
            if (enableLogging) {
                Log.d(TAG, "Dispatching Seeking event")
            }
            // Temporarily set currentPosition to seeking start position for the seeking event
            fastPixDataSDK.dispatchEvent(PlayerEventType.seeking, currentPosition)
        }
    }

    private fun dispatchSeekedEvent() {
        if (transitionToEvent(PlayerEvent.SEEKED)) {
            if (enableLogging) {
                Log.d(TAG, "Dispatching Seeked event")
            }
            fastPixDataSDK.dispatchEvent(PlayerEventType.seeked)
        }
    }

    private fun dispatchBufferingEvent() {
        if (transitionToEvent(PlayerEvent.BUFFERING)) {
            if (enableLogging) {
                Log.d(TAG, "Dispatching Buffering event")
            }
            fastPixDataSDK.dispatchEvent(PlayerEventType.buffering)
        }
    }

    private fun dispatchBufferedEvent() {
        if (transitionToEvent(PlayerEvent.BUFFERED)) {
            if (enableLogging) {
                Log.d(TAG, "Dispatching Buffered event")
            }
            fastPixDataSDK.dispatchEvent(PlayerEventType.buffered)
        }
    }

    private fun dispatchEndedEvent(currentPosition: Int? = null) {
        if (transitionToEvent(PlayerEvent.ENDED)) {
            if (enableLogging) {
                Log.d(TAG, "Dispatching Ended event")
            }
            if (currentPosition != null) {
                fastPixDataSDK.dispatchEvent(PlayerEventType.ended, currentPosition)
            } else {
                fastPixDataSDK.dispatchEvent(PlayerEventType.ended)
            }
        }
    }

    private fun dispatchVariantChangeEvent() {
        if (transitionToEvent(PlayerEvent.VARIANT_CHANGED)) {
            if (enableLogging) {
                Log.d(TAG, "Dispatching VariantChange event")
            }
            fastPixDataSDK.dispatchEvent(PlayerEventType.variantChanged)
        } else {
            pendingVariantChangeEvents.add(true)
        }
    }

    private fun dispatchErrorEvent(error: PlaybackException) {
        if (transitionToEvent(PlayerEvent.ERROR)) {
            if (enableLogging) {
                Log.d(TAG, "Dispatching Error event: ${error.message}")
            }
            errorCode = error.errorCode.toString()
            errorMessage = "${error.cause}"
            fastPixDataSDK.dispatchEvent(PlayerEventType.error)
        }
    }

    /**
     * Process any queued variant change events that were deferred until after play event
     */
    private fun processQueuedVariantChangeEvents() {
        if (pendingVariantChangeEvents.isNotEmpty()) {
            pendingVariantChangeEvents.clear()
            dispatchVariantChangeEvent()
        }
    }


    override fun playerHeight(): Int? {
        val density = context.resources.displayMetrics.density
        val rawHeight = playerView.height
        val height = ceil(rawHeight / density)
        return height.toInt()
    }

    override fun playerWidth(): Int? {
        val density = context.resources.displayMetrics.density
        val rawWidth = playerView.width
        val width = ceil(rawWidth / density)
        return width.toInt()
    }

    override fun videoSourceWidth(): Int? = videoSourceWidth

    override fun videoSourceHeight(): Int? = videoSourceHeight

    override fun playHeadTime(): Int? = exoPlayer.currentPosition.toInt()

    override fun mimeType(): String? =
        mimeType ?: exoPlayer.currentMediaItem?.localConfiguration?.mimeType

    override fun sourceFps(): String? = frameRate

    override fun sourceAdvertisedBitrate(): String? = bitRate

    override fun sourceAdvertiseFrameRate(): String? = frameRate

    override fun sourceDuration(): Int? = exoPlayer.duration.toInt()

    override fun isPause(): Boolean? = !exoPlayer.isPlaying

    override fun isAutoPlay(): Boolean? = false

    override fun isBuffering(): Boolean? = exoPlayer.playbackState == Player.STATE_BUFFERING

    override fun playerCodec(): String? = playerCodec

    override fun sourceHostName(): String? =
        exoPlayer.currentMediaItem?.localConfiguration?.uri.toString()

    override fun getPlayerError(): ErrorModel = ErrorModel(errorCode, errorMessage)

    override fun getVideoCodec(): String? = codec
    override fun getSoftwareName(): String? {
        return "media3-generic"
    }

    override fun getSoftwareVersion(): String? {
        return MediaLibraryInfo.VERSION
    }

    override fun getFastPixSDKName(): String? {
        return FastPixMedia3LibraryInfo.SDK_NAME
    }

    override fun getFastPixSDKVersion(): String? {
        return FastPixMedia3LibraryInfo.SDK_VERSION
    }

    override fun isLive(): Boolean? =
        exoPlayer.isCurrentMediaItemLive // This is a sample video, not live

    override fun sourceUrl(): String? =
        videoSourceUrl ?: exoPlayer.currentMediaItem?.localConfiguration?.uri.toString()

    override fun isFullScreen(): Boolean? {
        val orientation = context.resources.configuration.orientation
        return orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    override fun getBandWidthData(): BandwidthModel {
        val bandWidthModel = BandwidthModel()
        bandWidthModel.requestId = chunkDownloadData?.requestId
        bandWidthModel.requestUrl =
            "https://${chunkDownloadData?.requestHostName}${chunkDownloadData?.requestUrl}"
        bandWidthModel.requestMethod = chunkDownloadData?.requestType
        bandWidthModel.requestResponseHeaders = chunkDownloadData?.requestResponseHeaders
        bandWidthModel.requestResponseCode = chunkDownloadData?.requestErrorCode.toString()
        bandWidthModel.requestResponseSize = chunkDownloadData?.requestBytesLoaded.toString()
        bandWidthModel.requestErrorCode = chunkDownloadData?.requestErrorCode.toString()
        bandWidthModel.requestHostName = chunkDownloadData?.requestHostName
        bandWidthModel.requestCancel = chunkDownloadData?.requestCancel
        bandWidthModel.requestErrorText = chunkDownloadData?.requestErrorText
        return bandWidthModel
    }

    fun release() {
        fastPixDataSDK.release()
        mimeType = null
        frameRate = null
        playerCodec = null
        currentEventState = null
        isEnded = false
        videoSourceHeight = null
        videoSourceWidth = null
        detectMimeType = false
        renditionList = null
        codec = null
        bitRate = null
        errorCode = null
        errorMessage = null
        pendingVariantChangeEvents.clear()
    }

    /**
     * Data class for chunk rendition information
     */
    data class ChunkRendition(
        var bitrate: Long = 0,
        var width: Int = 0,
        var height: Int = 0,
        var codec: String? = null,
        var fps: Int = 0
    )

    /**
     * Data class for chunk download information
     */
    data class ChunkDownloadData(
        var loadTaskId: Long = 0,
        var requestResponseStart: Long = 0,
        var requestResponseEnd: Long = 0,
        var requestMediaStartTime: Long = 0,
        var requestMediaDuration: Long = 0,
        var requestVideoWidth: Int? = null,
        var requestVideoHeight: Int? = null,
        var requestUrl: String? = null,
        var requestType: String? = null,
        var requestHostName: String? = null,
        var requestBytesLoaded: Long = 0,
        var requestCurrentLevel: Int = 0,
        var requestLabeledBitrate: Int = 0,
        var requestError: String? = null,
        var requestErrorCode: Int = 0,
        var requestErrorText: String? = null,
        var requestCancel: String? = null,
        var requestId: String? = null,
        var requestResponseHeaders: Map<String, String>? = null,
        var requestRenditionLists: List<ChunkRendition>? = null
    )

    /**
     * BandwidthMetric class for tracking individual chunk downloads
     */
    internal open inner class BandwidthMetric {
        var availableTracks: TrackGroupArray? = null
        var loadedSegments: HashMap<Long, ChunkDownloadData> = HashMap()

        open fun onLoadError(
            loadTaskId: Long,
            e: IOException,
            segmentUrl: String?
        ): ChunkDownloadData {
            var segmentData: ChunkDownloadData? = loadedSegments[loadTaskId]
            if (segmentData == null) {
                segmentData = ChunkDownloadData(loadTaskId = loadTaskId)
            }
            segmentData.requestError = e.toString()
            segmentData.requestErrorCode = -1
            segmentData.requestErrorText = e.message
            segmentData.requestResponseEnd = System.currentTimeMillis()
            return segmentData
        }

        open fun onLoadCanceled(
            loadTaskId: Long,
            segmentUrl: String?
        ): ChunkDownloadData {
            var segmentData: ChunkDownloadData? = loadedSegments[loadTaskId]
            if (segmentData == null) {
                segmentData = ChunkDownloadData(loadTaskId = loadTaskId)
            }
            segmentData.requestCancel = "genericLoadCanceled"
            segmentData.requestResponseEnd = System.currentTimeMillis()
            return segmentData
        }

        protected fun onLoad(
            loadTaskId: Long,
            mediaStartTimeMs: Long,
            mediaEndTimeMs: Long,
            segmentUrl: String?,
            dataType: Int,
            host: String?,
            segmentMimeType: String
        ): ChunkDownloadData {
            synchronized(currentTimelineWindow) {
                try {
                    exoPlayer.currentTimeline.getWindow(
                        exoPlayer.currentWindowIndex, currentTimelineWindow
                    )
                } catch (e: IllegalStateException) {
                    Log.e(
                        "ChunkDownloadData",
                        "Player is in an invalid state: ${e.message}",
                        e
                    )
                }
            }

            val segmentData = ChunkDownloadData(
                loadTaskId = loadTaskId,
                requestResponseStart = System.currentTimeMillis(),
                requestMediaStartTime = mediaStartTimeMs,
                requestVideoWidth = videoSourceWidth,
                requestVideoHeight = videoSourceHeight,
                requestUrl = segmentUrl,
                requestHostName = host,
                requestRenditionLists = renditionList
            )

            if (dataType == C.DATA_TYPE_MANIFEST) {
                detectMimeType = false
                segmentData.requestType = "manifest"
            } else if (dataType == C.DATA_TYPE_MEDIA_INITIALIZATION) {
                if (segmentMimeType.contains("video")) {
                    segmentData.requestType = "video_init"
                } else if (segmentMimeType.contains("audio")) {
                    segmentData.requestType = "audio_init"
                }
            } else if (dataType == C.DATA_TYPE_MEDIA) {
                segmentData.requestType = "media"
                segmentData.requestMediaDuration = mediaEndTimeMs - mediaStartTimeMs
            } else {
                Log.e("BandwidthMetricDefault", "default")
            }
            loadedSegments[loadTaskId] = segmentData
            return segmentData
        }

        fun onLoadStarted(
            loadTaskId: Long,
            mediaStartTimeMs: Long,
            mediaEndTimeMs: Long,
            segmentUrl: String?,
            dataType: Int,
            host: String?,
            segmentMimeType: String
        ): ChunkDownloadData {
            val loadData = onLoad(
                loadTaskId,
                mediaStartTimeMs,
                mediaEndTimeMs,
                segmentUrl,
                dataType,
                host,
                segmentMimeType
            )
            loadData.requestResponseStart = System.currentTimeMillis()
            return loadData
        }

        open fun onLoadCompleted(
            loadTaskId: Long,
            segmentUrl: String?,
            bytesLoaded: Long,
            trackFormat: Format?
        ): ChunkDownloadData? {
            val segmentData: ChunkDownloadData = loadedSegments[loadTaskId] ?: return null
            segmentData.requestBytesLoaded = bytesLoaded
            segmentData.requestResponseEnd = System.currentTimeMillis()

            if (trackFormat != null && availableTracks != null) {
                availableTracks?.let { tracksList ->
                    for (i in 0 until tracksList.length) {
                        val tracks = tracksList[i]
                        for (trackGroupIndex in 0 until tracks.length) {
                            val currentFormat = tracks.getFormat(trackGroupIndex)
                            if (trackFormat.width == currentFormat.width &&
                                trackFormat.height == currentFormat.height &&
                                trackFormat.bitrate == currentFormat.bitrate
                            ) {
                                segmentData.requestCurrentLevel = trackGroupIndex
                            }
                        }
                    }
                }
            }
            loadedSegments.remove(loadTaskId)
            return segmentData
        }
    }

    /**
     * HLS-specific bandwidth metric implementation
     */
    internal inner class BandwidthMetricHls : BandwidthMetric() {
        override fun onLoadError(
            loadTaskId: Long,
            e: IOException,
            segmentUrl: String?
        ): ChunkDownloadData {
            val loadData: ChunkDownloadData =
                super.onLoadError(loadTaskId, e, segmentUrl)
            return loadData
        }

        override fun onLoadCanceled(
            loadTaskId: Long,
            segmentUrl: String?
        ): ChunkDownloadData {
            val loadData: ChunkDownloadData =
                super.onLoadCanceled(loadTaskId, segmentUrl)
            loadData.requestCancel = "FragLoadEmergencyAborted"
            return loadData
        }

        override fun onLoadCompleted(
            loadTaskId: Long,
            segmentUrl: String?,
            bytesLoaded: Long,
            trackFormat: Format?
        ): ChunkDownloadData? {
            val loadData: ChunkDownloadData? =
                super.onLoadCompleted(loadTaskId, segmentUrl, bytesLoaded, trackFormat)
            if (trackFormat != null && loadData != null) {
                loadData.requestLabeledBitrate = trackFormat.bitrate
            }
            return loadData
        }
    }

    private var chunkDownloadData: ChunkDownloadData? = null

    /**
     * BandwidthMetricDispatcher handles chunk downloading events and dispatches them
     */
    internal inner class BandwidthMetricDispatcher {
        private val bandwidthMetricHls: BandwidthMetric = BandwidthMetricHls()
        var allowedHeaders: ArrayList<String> = ArrayList()
        private var requestSegmentDuration: Long = 1000
        private var lastRequestSentAt: Long = -1
        private var maxNumberOfEventsPerSegmentDuration: Int = 10
        private var numberOfRequestCompletedBeaconsSentPerSegment: Int = 0
        private var numberOfRequestCancelBeaconsSentPerSegment: Int = 0
        private var numberOfRequestFailedBeaconsSentPerSegment: Int = 0

        init {
            allowedHeaders.add("x-cdn")
            allowedHeaders.add("content-type")
            allowedHeaders.add("server")
            allowedHeaders.add("x-request-id")
        }

        fun currentBandwidthMetric(): BandwidthMetric = bandwidthMetricHls

        fun onLoadError(
            loadTaskId: Long,
            segmentUrl: String?,
            e: IOException
        ) {
            val loadData: ChunkDownloadData = currentBandwidthMetric().onLoadError(
                loadTaskId,
                e,
                segmentUrl
            )
            dispatchChunkEvent(REQUEST_FAILED, loadData)
        }

        fun onLoadCanceled(
            loadTaskId: Long,
            segmentUrl: String?,
            headers: Map<String, List<String>>
        ) {
            val loadData: ChunkDownloadData = currentBandwidthMetric().onLoadCanceled(
                loadTaskId,
                segmentUrl
            )
            parseHeaders(loadData, headers)
            dispatchChunkEvent(REQUEST_CANCELLED, loadData)
        }

        fun onLoadStarted(
            loadTaskId: Long,
            mediaStartTimeMs: Long,
            mediaEndTimeMs: Long,
            segmentUrl: String?,
            dataType: Int,
            host: String?,
            segmentMimeType: String,
            segmentWidth: Int,
            segmentHeight: Int
        ) {
            currentBandwidthMetric().onLoadStarted(
                loadTaskId,
                mediaStartTimeMs,
                mediaEndTimeMs,
                segmentUrl,
                dataType,
                host,
                segmentMimeType
            )
        }

        fun onLoadCompleted(
            loadTaskId: Long,
            segmentUrl: String?,
            bytesLoaded: Long,
            trackFormat: Format?,
            responseHeaders: Map<String, List<String>>,
            dataType: Int,
            host: String?,
            segmentMimeType: String
        ) {
            val loadData: ChunkDownloadData? = currentBandwidthMetric().onLoadCompleted(
                loadTaskId,
                segmentUrl,
                bytesLoaded,
                trackFormat
            )
            if (loadData != null) {
                parseHeaders(loadData, responseHeaders)
                dispatchChunkEvent(REQUEST_COMPLETED, loadData)
            }
        }

        private fun parseHeaders(
            loadData: ChunkDownloadData,
            responseHeaders: Map<String, List<String>>
        ) {
            val headers: Map<String, String>? = parseHeaders(responseHeaders)
            if (headers != null) {
                loadData.requestId = headers["x-request-id"]
                loadData.requestResponseHeaders = headers
            }
        }

        fun onTracksChanged(trackGroups: TrackGroupArray) {
            currentBandwidthMetric().availableTracks = trackGroups
            if (trackGroups.length > 0) {
                for (groupIndex in 0 until trackGroups.length) {
                    val trackGroup = trackGroups[groupIndex]
                    if (0 < trackGroup.length) {
                        var trackFormat = trackGroup.getFormat(0)
                        if (trackFormat.containerMimeType != null && trackFormat.containerMimeType!!.contains(
                                "video"
                            )
                        ) {
                            val renditions: MutableList<ChunkRendition> = ArrayList()
                            for (i in 0 until trackGroup.length) {
                                trackFormat = trackGroup.getFormat(i)
                                val rendition = ChunkRendition(
                                    bitrate = trackFormat.bitrate.toLong(),
                                    width = trackFormat.width,
                                    height = trackFormat.height,
                                    codec = trackFormat.codecs,
                                    fps = trackFormat.frameRate.toInt()
                                )
                                renditions.add(rendition)
                            }
                            renditionList = renditions
                        }
                    }
                }
            }
        }

        private fun dispatchChunkEvent(eventType: String, data: ChunkDownloadData) {
            if (shouldDispatchEvent(data, eventType)) {
                when (eventType) {
                    REQUEST_FAILED -> {
                        dispatchRequestFailed(data)
                    }

                    REQUEST_CANCELLED -> {
                        dispatchRequestCancelled(data)
                    }

                    REQUEST_COMPLETED -> {
                        dispatchRequestCompleted(data)
                    }
                }
            }
        }

        private fun dispatchRequestCompleted(data: ChunkDownloadData) {
            chunkDownloadData = data
            fastPixDataSDK.dispatchEvent(PlayerEventType.requestCompleted)
        }

        private fun dispatchRequestCancelled(data: ChunkDownloadData) {
            chunkDownloadData = data
            fastPixDataSDK.dispatchEvent(PlayerEventType.requestCanceled)
        }

        private fun dispatchRequestFailed(data: ChunkDownloadData) {
            chunkDownloadData = data
            fastPixDataSDK.dispatchEvent(PlayerEventType.requestFailed)
        }

        private fun parseHeaders(responseHeaders: Map<String, List<String>>): Map<String, String> {
            if (responseHeaders.isEmpty()) {
                return emptyMap()
            }
            val headers: MutableMap<String, String> = HashMap()
            for ((headerName, headerValues) in responseHeaders) {
                var headerAllowed = false
                synchronized(this) {
                    for (allowedHeader in allowedHeaders) {
                        if (allowedHeader.equals(headerName, ignoreCase = true)) {
                            headerAllowed = true
                            break
                        }
                    }
                }
                if (!headerAllowed) {
                    continue
                }

                if (headerValues != null && !headerValues.isEmpty()) {
                    val headerValue = java.lang.String.join(", ", headerValues)
                    headers[headerName.lowercase()] = headerValue.lowercase()
                }
            }
            return headers
        }

        private fun shouldDispatchEvent(
            data: ChunkDownloadData?,
            eventType: String
        ): Boolean {
            if (data != null) {
                requestSegmentDuration =
                    if (data.requestMediaDuration == null || data.requestMediaDuration < 1000) {
                        1000
                    } else {
                        data.requestMediaDuration
                    }
            }
            val timeDiff = System.currentTimeMillis() - lastRequestSentAt
            if (timeDiff > requestSegmentDuration) {
                // Reset all stats
                lastRequestSentAt = System.currentTimeMillis()
                numberOfRequestCompletedBeaconsSentPerSegment = 0
                numberOfRequestCancelBeaconsSentPerSegment = 0
                numberOfRequestFailedBeaconsSentPerSegment = 0
            }

            when (eventType) {
                REQUEST_COMPLETED -> numberOfRequestCompletedBeaconsSentPerSegment++
                REQUEST_CANCELLED -> numberOfRequestCancelBeaconsSentPerSegment++
                REQUEST_FAILED -> numberOfRequestFailedBeaconsSentPerSegment++
            }

            if (numberOfRequestCompletedBeaconsSentPerSegment > maxNumberOfEventsPerSegmentDuration
                || numberOfRequestCancelBeaconsSentPerSegment > maxNumberOfEventsPerSegmentDuration
                || numberOfRequestFailedBeaconsSentPerSegment > maxNumberOfEventsPerSegmentDuration
            ) {
                return false
            }
            return true
        }
    }


    companion object {
        private const val REQUEST_FAILED = "requestFailed"
        private const val REQUEST_CANCELLED = "requestCanceled"
        private const val REQUEST_COMPLETED = "requestCompleted"
    }
}