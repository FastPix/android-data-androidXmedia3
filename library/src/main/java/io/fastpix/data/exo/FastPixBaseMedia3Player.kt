package io.fastpix.data.exo

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.database.VersionTable
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.TrackGroupArray
import com.google.gson.Gson
import io.fastpix.data.Interfaces.DeviceContract
import io.fastpix.data.Interfaces.EventContract
import io.fastpix.data.Interfaces.PlayerObserver
import io.fastpix.data.entity.CustomerDataEntity
import io.fastpix.data.entity.CustomerPlayerDataEntity
import io.fastpix.data.entity.CustomerVideoDataEntity
import io.fastpix.data.entity.CustomerViewDataEntity
import io.fastpix.data.entity.NetworkBandwidthEntity
import io.fastpix.data.entity.UserSessionTag
import io.fastpix.data.request.AnalyticsEventLogger
import io.fastpix.data.request.CustomOptions
import io.fastpix.data.request.FastPixMetrics
import io.fastpix.data.request.MediaPresentation
import io.fastpix.data.request.PlayerViewOrientation
import io.fastpix.data.request.RequestFailureException
import io.fastpix.data.streaming.EventHandler
import io.fastpix.data.streaming.InternalErrorEvent
import io.fastpix.data.streaming.MediaStreaming
import io.fastpix.data.streaming.StreamingHub
import org.json.JSONException
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.LinkedList
import java.util.Timer
import java.util.TimerTask
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil


/**
 * FastPixBaseExoPlayer is a custom implementation of ExoPlayer that integrates with EventBus
 * for event-driven communication and implements IPlayerListener for handling player events.
 */
@OptIn(androidx.media3.common.util.UnstableApi::class)
class FastPixBaseMedia3Player(
    context: Context,
    player: ExoPlayer,
    data: CustomerDataEntity?,
    options: CustomOptions?
) : EventHandler(), PlayerObserver {
    val ERROR_UNKNOWN = -1

    /**
     * Enum representing various states of the player during playback.
     */
    enum class PlayerState {
        BUFFERING,  // Player is loading and buffering content
        REBUFFERING,  // Player is buffering after already playing
        SEEKING,  // User is seeking to a different position
        SEEKED,  // Seeking operation has completed
        ERROR,  // An error occurred during playback
        PAUSED,  // Playback is paused
        PLAY,  // Playback is about to start
        PLAYING,  // Player is currently playing content
        INIT,  // Player is initialized but not yet playing
        ENDED // Playback has completed
    }

    // MIME type of the media source being played
    protected var mediaMimeType: String? = null

    // Width of the media source in pixels
    protected var mediaSourceWidth: Int? = null

    // Codec used for encoding the media source
    protected var sourceCodeC: String? = null

    // Hostname of the media source (e.g., CDN or streaming server)
    protected var hostName: String? = null

    // Frames per second (FPS) of the media source
    protected var sourceFPS: Float = 0f

    // Height of the media source in pixels
    protected var mediaSourceHeight: Int? = null

    // Advertised bitrate of the media source in bits per second (bps)
    protected var mediaSourceAdvertisedBitrate: Int? = null

    // Advertised frame rate of the media source
    protected var sourceAdvertisedFrameRate: Float? = null

    // Total duration of the media source in milliseconds
    protected var mediaSourceDuration: Long? = null

    // ExoPlayer timeline window object to track the current playback position
    protected var currentTimelineWindow: Timeline.Window = Timeline.Window()

    // Handler for managing player-related tasks and events
    protected var playerHandler: ExoPlayerHandler

    // Timer for periodically updating the playhead (current playback position)
    protected var updatePlayheadPositionTimer: Timer? = null

    // Object representing the device capabilities and characteristics
    protected var fpDevice: FPDevice?

    // Weak reference to the ExoPlayer instance to prevent memory leaks
    var player: WeakReference<ExoPlayer?>?

    // Weak reference to the View displaying the player to prevent memory leaks
    protected var playerView: WeakReference<View>? = null

    // Weak reference to the Android Context to avoid memory leaks
    protected var contextRef: WeakReference<Context>

    // Flag indicating whether the MIME type should be detected automatically
    protected var detectMimeType: Boolean = true

    // Flag indicating whether the first frame has been received
    protected var firstFrameReceived: Boolean = false

    // Counter tracking the number of events sent
    protected var numberOfEventsSent: Int = 0

    // Counter tracking the number of play events sent
    protected var numberOfPlayEventsSent: Int = 0

    // Counter tracking the number of pause events sent
    protected var numberOfPauseEventsSent: Int = 0

    // Type of the media stream (e.g., live, VOD), initialized to -1 (unknown)
    protected var mediaStreamType: Int = -1

    // Timestamp (in milliseconds) when the first frame was rendered, initialized to -1 (not set)
    protected var firstFrameRenderedAt: Long = -1

    // List of session tags used for tracking playback sessions
    protected var sessionTags: List<UserSessionTag>? = LinkedList()

    // Current state of the player
    var state: PlayerState

    // Check boolean value for autoplay
    var isItAutoPlay: Boolean = false

    // Object responsible for tracking playback statistics
    protected lateinit var fastPixMetrics: FastPixMetrics

    // Flag indicating whether a seek operation is in progress
    var seekingInProgress: Boolean = false

    // Flag indicating whether the current playback item contains a video track
    var playItemHaveVideoTrack: Boolean

    // Flag indicating whether an HLS extension was found in the media source
    private var foundHlsExtension: Boolean = true

    // Counter tracking the number of dropped frames during playback
    private var numberOfDroppedFrames: Long = 0L

    // Dispatcher for tracking and sending bandwidth metric events.
    private val bandwidthDispatcher = BandwidthMetricDispatcher()

    //list of renditionList included into networkBandwidthEntity
    protected var renditionList: List<NetworkBandwidthEntity.Rendition>? = null

    @Throws(JSONException::class)
    fun handleExoPlaybackState(playbackState: Int, playWhenReady: Boolean) {
        when (playbackState) {
            Player.STATE_BUFFERING -> {
                buffering()
                if (playWhenReady) {
                    play()
                } else if (state != PlayerState.PAUSED) {
                    pause()
                }
            }

            Player.STATE_READY -> if (playWhenReady) {
                playing()
            } else if (state != PlayerState.PAUSED) {
                fastPixMetrics.setPreLoaded(true)
                pause()
            }

            Player.STATE_ENDED -> ended()

            Player.STATE_IDLE -> if (state == PlayerState.PLAY || state == PlayerState.PLAYING) {

                // If we are playing/preparing to play and go idle, the player was stopped
                pause()
            }
        }
    }

    protected val isLivePlayback: Boolean
        get() = false

    protected val isHlsExtensionAvailable: Boolean
        get() {
            if (foundHlsExtension == null) {
                try {

                    // This class is for sure in the hls extension
                    Class.forName(HlsManifest::class.java.canonicalName)
                    foundHlsExtension = true
                } catch (e: ClassNotFoundException) {
                    AnalyticsEventLogger.w(
                        TAG,
                        "ExoPlayer library-HLS not available. Some features may not work. Exception: ${e.message}"
                    )
                    foundHlsExtension = false
                }
            }
            return foundHlsExtension
        }

    protected fun parseHlsManifestTag(tagName: String): String = ""

    @Throws(JSONException::class)
    fun updateCustomerData(data: CustomerDataEntity?) {
        fastPixMetrics.customerData = data
    }

    val customerData: CustomerDataEntity
        get() = fastPixMetrics.customerData

    fun overwriteDeviceName(deviceName: String?) {
        if (fpDevice != null) {
            fpDevice?.overwriteDeviceName(deviceName)
        }
    }

    fun overwriteOsFamilyName(osFamilyName: String?) {
        if (fpDevice != null) {
            fpDevice?.overwriteOsFamilyName(osFamilyName)
        }
    }

    fun overwriteOsVersion(osVersion: String?) {
        if (fpDevice != null) {
            fpDevice?.overwriteOsFamilyName(osVersion)
        }
    }

    fun overwriteManufacturer(manufacturer: String?) {
        if (fpDevice != null) {
            fpDevice?.overwriteOsFamilyName(manufacturer)
        }
    }

    @Throws(JSONException::class)
    fun updateCustomerData(
        customPlayerData: CustomerPlayerDataEntity?,
        customVideoData: CustomerVideoDataEntity
    ) {
        customVideoData.videoDuration = mediaSourceDuration
        fastPixMetrics.updateCustomerData(customPlayerData, customVideoData)
    }

    @Throws(JSONException::class)
    fun updateCustomerData(
        customerPlayerData: CustomerPlayerDataEntity?,
        customerVideoData: CustomerVideoDataEntity,
        customerViewData: CustomerViewDataEntity?
    ) {
        customerVideoData.videoDuration = mediaSourceDuration
        fastPixMetrics.updateCustomerData(customerPlayerData, customerVideoData, customerViewData)
    }

    val customerVideoData: CustomerVideoDataEntity
        get() = fastPixMetrics.customerVideoData

    val customerPlayerData: CustomerPlayerDataEntity
        get() = fastPixMetrics.customerPlayerData

    val customerViewData: CustomerViewDataEntity
        get() = fastPixMetrics.customerViewData

    fun enableFPCoreDebug(enable: Boolean, verbose: Boolean) {
        fastPixMetrics.allowLogcatOutput(enable, verbose)
    }

    @Throws(JSONException::class)
    fun videoChange(customerVideoData: CustomerVideoDataEntity) {
        state = PlayerState.INIT
        resetInternalStats()
        customerVideoData.videoDuration = mediaSourceDuration
        fastPixMetrics.videoChange(customerVideoData)
        sessionTags = null
    }

    @Throws(JSONException::class)
    fun programChange(customerVideoData: CustomerVideoDataEntity) {
        resetInternalStats()
        customerVideoData.videoDuration = mediaSourceDuration
        fastPixMetrics.programChange(customerVideoData)
    }

    @Throws(JSONException::class)
    fun orientationChange(orientation: PlayerViewOrientation?) {
        fastPixMetrics.orientationChange(orientation)
    }

    fun presentationChange(mediaPresentation: MediaPresentation?) {
        fastPixMetrics.presentationChange(mediaPresentation)
    }

    fun setPlayerView(playerView: View) {
        this.playerView = WeakReference(playerView)
    }

    fun setPlayerSize(width: Int, height: Int) {
        fastPixMetrics.setPlayerSize(pxToDp(width), pxToDp(height))
    }

    fun setIsItAutoPlay(value: Boolean) {
        isItAutoPlay = value
    }

    fun setScreenSize(width: Int, height: Int) {
        fastPixMetrics.setScreenSize(pxToDp(width), pxToDp(height))
    }

    @Throws(JSONException::class)
    fun error(requestFailureException: RequestFailureException?) {
        fastPixMetrics.error(requestFailureException)
    }

    fun setAutomaticErrorTracking(enabled: Boolean) {
        fastPixMetrics.setAutomaticErrorTracking(enabled)
    }

    @Throws(JSONException::class)
    fun release() {
        if (updatePlayheadPositionTimer != null) {
            updatePlayheadPositionTimer?.cancel()
        }
        fastPixMetrics.release()
        player = null
    }

    fun setStreamType(type: Int) {
        mediaStreamType = type
    }

    @Throws(JSONException::class)
    override fun dispatch(event: EventContract) {
        player?.get()?.let { playerInstance ->
            // Use playerInstance safely here
            numberOfEventsSent++
            if (event.type.equals(MediaStreaming.EventType.play.toString(), ignoreCase = true)) {
                numberOfPlayEventsSent++
            }
            if (event.type.equals(MediaStreaming.EventType.pause.toString(), ignoreCase = true)) {
                numberOfPauseEventsSent++
            }
            super.dispatch(event)
        }
    }

    override fun getCurrentPosition(): Long {
        if (playerHandler != null) {
            return playerHandler.getPlayerCurrentPosition()
        }
        return 0
    }

    override fun getMimeType(): String = mediaMimeType.orEmpty()

    override fun getSourceWidth(): Int = mediaSourceWidth ?: 0

    override fun getSourceHostName(): String = hostName.orEmpty()

    override fun getPlayerCodec(): String = sourceCodeC.orEmpty()

    override fun getSourceFps(): Int = sourceFPS.toInt()

    override fun getSourceHeight(): Int = mediaSourceHeight ?: 0

    override fun getSourceAdvertisedBitrate(): Int = mediaSourceAdvertisedBitrate ?: 0

    override fun getSourceAdvertisedFramerate(): Float = sourceAdvertisedFrameRate ?: 0F

    override fun getSourceDuration(): Long = mediaSourceDuration ?: 0

    /* private fun configurePlaybackHeadUpdateInterval() {
         if (player == null) {
             return
         }
         val trackGroups = player?.get()?.currentTrackGroups ?: return
         playItemHaveVideoTrack = false
         if (trackGroups.length > 0) {
             for (groupIndex in 0 until trackGroups.length) {
                 val trackGroup = trackGroups[groupIndex]
                 if (0 < trackGroup.length) {
                     val trackFormat = trackGroup.getFormat(0)
                     if (trackFormat.sampleMimeType != null && trackFormat.sampleMimeType!!.contains(
                             TAG_VIDEO
                         )
                     ) {
                         playItemHaveVideoTrack = true
                         break
                     }
                 }
             }
         }
         setPlaybackHeadUpdateInterval()
     }
 */
    protected fun setPlaybackHeadUpdateInterval() {
        if (updatePlayheadPositionTimer != null) {
            updatePlayheadPositionTimer?.cancel()
        }

        // Schedule timer to execute, this is for audio only content.
        updatePlayheadPositionTimer = Timer()
        updatePlayheadPositionTimer?.schedule(object : TimerTask() {
            override fun run() {
                playerHandler.obtainMessage(ExoPlayerHandler.UPDATE_PLAYER_CURRENT_POSITION)
                    .sendToTarget()
            }
        }, 0, 150)
    }

    override fun isBuffering(): Boolean {
        return state == PlayerState.BUFFERING
    }

    override fun getPlayerViewWidth(): Int {
        val pv = playerView?.get()
        if (pv != null) {
            return pxToDp(pv.width)
        }
        return 0
    }

    override fun getPlayerViewHeight(): Int {
        val pv = playerView?.get()
        if (pv != null) {
            return pxToDp(pv.height)
        }
        return 0
    }

    override fun getPlayerProgramTime(): Long {
        if (playerHandler != null) {
            return currentTimelineWindow.windowStartTimeMs + playerHandler.getPlayerCurrentPosition()
        }
        return -1L
    }

    override fun getPlayerManifestNewestTime(): Long {
        if (isLivePlayback) {
            return currentTimelineWindow.windowStartTimeMs
        }
        return -1L
    }

    override fun getVideoHoldback(): Long? {
        return if (isLivePlayback) parseHlsManifestTagLong("HOLD-BACK") else null
    }

    override fun getVideoPartHoldback(): Long? {
        return if (isLivePlayback) parseHlsManifestTagLong("PART-HOLD-BACK") else null
    }

    override fun getVideoPartTargetDuration(): Long? {
        return if (isLivePlayback) parseHlsManifestTagLong("PART-TARGET") else null
    }

    override fun getVideoTargetDuration(): Long? {
        return if (isLivePlayback) parseHlsManifestTagLong("EXT-X-TARGETDURATION") else null
    }

    override fun isPaused(): Boolean {
        return state == PlayerState.PAUSED || state == PlayerState.ENDED || state == PlayerState.ERROR || state == PlayerState.INIT
    }

    override fun isAutoPlay(): Boolean {
        Log.e("isAutoPlay", "1" + isItAutoPlay)

        return isItAutoPlay
    }

    @Throws(JSONException::class)
    protected fun buffering() {
        if (state == PlayerState.REBUFFERING || seekingInProgress || state == PlayerState.SEEKED) {

            // ignore
            return
        }

        // If we are going from playing to buffering then this is rebuffer event
        if (state == PlayerState.PLAYING) {
            rebufferingStarted()
            return
        }

        // This is initial buffering event before playback starts
        state = PlayerState.BUFFERING
        dispatch(MediaStreaming(MediaStreaming.EventType.timeUpdate, null))
    }

    @Throws(JSONException::class)
    fun pause() {
        if (state == PlayerState.SEEKED && numberOfPauseEventsSent > 0) {

            // No pause event after seeked
            return
        }
        if (state == PlayerState.REBUFFERING) {
            rebufferingEnded()
        }
        if (seekingInProgress) {
            seeked(false)
            return
        }
        state = PlayerState.PAUSED
        dispatch(MediaStreaming(MediaStreaming.EventType.pause, null))
    }

    @Throws(JSONException::class)
    protected fun play() {
        if ((state == PlayerState.REBUFFERING || seekingInProgress || state == PlayerState.SEEKED) && (numberOfPlayEventsSent > 0)) {
            return
        }
        state = PlayerState.PLAY
        dispatch(MediaStreaming(MediaStreaming.EventType.play, null))
    }

    @Throws(JSONException::class)
    protected fun playing() {
        if (seekingInProgress) {

            // We will dispatch playing event after seeked event
            return
        }
        if (state == PlayerState.PAUSED) {
            play()
        } else if (state == PlayerState.REBUFFERING) {
            rebufferingEnded()
        } else if (state == PlayerState.PLAYING) {

            // No need to re-enter the playing state
            return
        }
        state = PlayerState.PLAYING
        dispatch(MediaStreaming(MediaStreaming.EventType.playing, null))
    }

    @Throws(JSONException::class)
    protected fun rebufferingStarted() {
        state = PlayerState.REBUFFERING
        dispatch(MediaStreaming(MediaStreaming.EventType.buffering, null))
    }

    @Throws(JSONException::class)
    protected fun rebufferingEnded() {
        dispatch(MediaStreaming(MediaStreaming.EventType.buffered, null))
    }

    @Throws(JSONException::class)
    protected fun seeking() {
        if (state == PlayerState.PLAYING) {
            dispatch(MediaStreaming(MediaStreaming.EventType.pause, null))
        }
        state = PlayerState.SEEKING
        seekingInProgress = true
        firstFrameRenderedAt = -1
        dispatch(MediaStreaming(MediaStreaming.EventType.seeking, null))
        firstFrameReceived = false
    }

    @Throws(JSONException::class)
    protected fun seeked(timeUpdateEvent: Boolean) {
        if (seekingInProgress) {
            if (timeUpdateEvent) {
                if ((System.currentTimeMillis() - firstFrameRenderedAt > TIME_TO_WAIT_AFTER_FIRST_FRAME_RENDERED) && firstFrameReceived) {

                    // This is a playback !!!
                    dispatch(MediaStreaming(MediaStreaming.EventType.seeked, null))
                    seekingInProgress = false
                    playing()
                }
            } else {

                // the player was seeking while paused
                dispatch(MediaStreaming(MediaStreaming.EventType.seeked, null))
                seekingInProgress = false
                state = PlayerState.SEEKED
            }
        }
    }

    @Throws(JSONException::class)
    protected fun ended() {
        dispatch(MediaStreaming(MediaStreaming.EventType.pause, null))
        dispatch(MediaStreaming(MediaStreaming.EventType.ended, null))
        if (seekingInProgress) {
            seeked(true)
        }
        state = PlayerState.ENDED
    }

    @Throws(JSONException::class)
    protected fun internalError(error: PlaybackException) {
        dispatch(
            InternalErrorEvent(
                error.errorCode,
                "${error.javaClass.canonicalName}",
                error.errorCodeName
            )
        )
    }

    protected fun handleVariantChange(format: Format) {
        if (format != null) {
            mediaSourceAdvertisedBitrate = format.bitrate
            if (format.frameRate > 0) {
                sourceAdvertisedFrameRate = format.frameRate
            }
            mediaSourceWidth = format.width
            mediaSourceHeight = format.height
            sourceFPS = format.frameRate
            sourceCodeC = format.codecs
            dispatch(MediaStreaming(MediaStreaming.EventType.variantChanged, null))
        }
    }

    private fun resetInternalStats() {
        detectMimeType = true
        numberOfPauseEventsSent = 0
        numberOfPlayEventsSent = 0
        numberOfEventsSent = 0
        firstFrameReceived = false
        firstFrameRenderedAt = -1
        currentTimelineWindow = Timeline.Window()
    }

    private fun parseHlsManifestTagLong(tagName: String): Long {
        var value = parseHlsManifestTag(tagName)
        value = value.replace(".", "")
        try {
            return value.toLong()
        } catch (e: NumberFormatException) {
            AnalyticsEventLogger.d(TAG, "Bad number format for value: $value")
        }
        return -1L
    }

    class ExoPlayerHandler(looper: Looper, var fastPixBaseMedia3Player: FastPixBaseMedia3Player) :
        Handler(
            looper
        ) {
        var playerCurrentPosition: AtomicLong = AtomicLong(0)
        fun getPlayerCurrentPosition(): Long = playerCurrentPosition.get()
        override fun handleMessage(msg: Message) {
            if (msg.what == UPDATE_PLAYER_CURRENT_POSITION) {
                if (fastPixBaseMedia3Player.player == null) {
                    return
                }
                fastPixBaseMedia3Player.player?.get()?.let { playerInstance ->
                    playerCurrentPosition.set(playerInstance.contentPosition)
                }
                if (fastPixBaseMedia3Player.seekingInProgress) {
                    try {
                        fastPixBaseMedia3Player.seeked(true)
                    } catch (e: JSONException) {
                        Log.e(JSON_EXCEPTION, e.toString())
                    }
                }
            } else {
                AnalyticsEventLogger.d(
                    TAG,
                    "ExoPlayerHandler>> Unhandled message type: " + msg.what
                )
            }
        }

        companion object {
            const val UPDATE_PLAYER_CURRENT_POSITION: Int = 1
        }
    }

    class FPDevice(ctx: Context) : DeviceContract {
        protected var contextRef: WeakReference<Context>
        private var deviceId: String
        private var appName = ""
        private var appVersion = ""
        var fPModelName: String? = null
            protected set
        var fPOSFamily: String? = null
            protected set
        var fPOSVersion: String? = null
            protected set
        var fPManufacturer: String? = null
            protected set

        init {
            val sharedPreferences =
                ctx.getSharedPreferences(FASTPIX_DEVICE_ID, Context.MODE_PRIVATE)
            deviceId = sharedPreferences.getString(FASTPIX_DEVICE_ID, "").toString()
            if (deviceId.equals("")) {
                deviceId = UUID.randomUUID().toString()
                val editor = sharedPreferences.edit()
                editor.putString(FASTPIX_DEVICE_ID, deviceId)
                editor.commit()
            }
            contextRef = WeakReference(ctx)
            try {
                val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                appName = pi.packageName
                appVersion = pi.versionName!!
            } catch (e: PackageManager.NameNotFoundException) {
                AnalyticsEventLogger.w(
                    TAG,
                    "ExoPlayer library-HLS not available. Some features may not work. Exception: ${e.message}"
                )
            }
        }

        fun overwriteDeviceName(deviceName: String?) {
            fPModelName = deviceName
        }

        fun overwriteOsFamilyName(osFamily: String?) {
            fPOSFamily = osFamily
        }

        fun overwriteOsVersion(osVersion: String?) {
            fPOSVersion = osVersion
        }

        fun overwriteManufacturer(manufacturer: String?) {
            fPManufacturer = manufacturer
        }

        override fun getHardwareArchitecture(): String = Build.HARDWARE

        override fun getOSFamily(): String = "Android"

        override fun getOSVersion(): String =
            Build.VERSION.RELEASE + " (" + Build.VERSION.SDK_INT + ")"

        override fun getDeviceName(): String = ""

        override fun getDeviceCategory(): String = ""

        override fun getManufacturer(): String = Build.MANUFACTURER

        override fun getModelName(): String = Build.MODEL

        override fun getPlayerVersion(): String = MediaLibraryInfo.VERSION

        override fun getDeviceId(): String = deviceId

        override fun getAppName(): String = appName

        override fun getAppVersion(): String = appVersion

        override fun getPluginName(): String = "media3_fastpix"

        override fun getPluginVersion(): String = BuildConfig.LIBRARY_VERSION

        override fun getPlayerSoftware(): String = EXO_SOFTWARE

        override fun getNetworkConnectionType(): String {
            val context = contextRef.get()
            val connectivityMgr =
                context!!.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            var activeNetwork: NetworkInfo? = null
            if (connectivityMgr != null) {
                activeNetwork = connectivityMgr.activeNetworkInfo
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val nc = connectivityMgr.getNetworkCapabilities(connectivityMgr.activeNetwork)
                    if (nc == null) {
                        AnalyticsEventLogger.d(
                            TAG,
                            "ERROR: Failed to obtain NetworkCapabilities manager !!!"
                        )
                        return ""
                    }
                    return if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                        CONNECTION_TYPE_WIRED
                    } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        CONNECTION_TYPE_WIFI
                    } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        CONNECTION_TYPE_CELLULAR
                    } else {
                        CONNECTION_TYPE_OTHER
                    }
                } else {
                    return if (activeNetwork?.type == ConnectivityManager.TYPE_ETHERNET) {
                        CONNECTION_TYPE_WIRED
                    } else if (activeNetwork?.type == ConnectivityManager.TYPE_WIFI) {
                        CONNECTION_TYPE_WIFI
                    } else if (activeNetwork?.type == ConnectivityManager.TYPE_MOBILE) {
                        CONNECTION_TYPE_CELLULAR
                    } else {
                        CONNECTION_TYPE_OTHER
                    }
                }
            }
            return ""
        }

        override fun getElapsedRealtime(): Long = SystemClock.elapsedRealtime()

        override fun outputLog(logPriority: String, s: String, s1: String, throwable: Throwable) {
            Log.e("outputLog", s)
        }

        override fun outputLog(logPriority: String, tag: String, msg: String) {
            when (logPriority) {
                "error" -> Log.e(tag, msg)
                "warn" -> Log.w(tag, msg)
                "info" -> Log.i(tag, msg)
                "debug" -> Log.d(tag, msg)
                else -> Log.v(tag, msg)
            }
        }

        override fun outputLog(tag: String, msg: String) {
            Log.v(tag, msg)
        }

        companion object {
            private const val EXO_SOFTWARE = "media3-generic"
            const val CONNECTION_TYPE_CELLULAR: String = "cellular"
            const val CONNECTION_TYPE_WIFI: String = "wifi"
            const val CONNECTION_TYPE_WIRED: String = "wired"
            const val CONNECTION_TYPE_OTHER: String = "other"
            const val FASTPIX_DEVICE_ID: String = "FASTPIX_DEVICE_ID"
        }
    }

    internal open inner class BandwidthMetric {
        var availableTracks: TrackGroupArray? = null
        var loadedSegments: HashMap<Long, NetworkBandwidthEntity> = HashMap()

        @Throws(JSONException::class)
        open fun onLoadError(
            loadTaskId: Long,
            e: IOException,
            segmentUrl: String?
        ): NetworkBandwidthEntity {
            var segmentData: NetworkBandwidthEntity? = loadedSegments[loadTaskId]
            if (segmentData == null) {
                segmentData = NetworkBandwidthEntity()
            }
            segmentData.requestError = e.toString()
            segmentData.requestErrorCode = -1
            segmentData.requestErrorText = e.message
            segmentData.requestResponseEnd = System.currentTimeMillis()
            return segmentData
        }

        @Throws(JSONException::class)
        open fun onLoadCanceled(
            loadTaskId: Long,
            segmentUrl: String?
        ): NetworkBandwidthEntity {
            var segmentData: NetworkBandwidthEntity? = loadedSegments[loadTaskId]
            if (segmentData == null) {
                segmentData = NetworkBandwidthEntity()
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
        ): NetworkBandwidthEntity {

            // Populate segment time details.
            if (player != null) {
                synchronized(currentTimelineWindow) {
                    try {
                        val playerInstance = player?.get()
                        val timelineWindow = currentTimelineWindow
                        if (playerInstance != null) {
                            playerInstance.currentTimeline.getWindow(
                                playerInstance.currentWindowIndex, timelineWindow
                            )
                        } else {
                            Log.e(
                                "NetworkBandwidthEntity",
                                "Player or currentTimelineWindow is null"
                            )
                        }
                    } catch (e: IllegalStateException) {
                        Log.e(
                            "NetworkBandwidthEntity",
                            "Player is in an invalid state: ${e.message}",
                            e
                        )
                    }
                }
            }
            val segmentData = NetworkBandwidthEntity()
            segmentData.requestResponseStart = System.currentTimeMillis()
            segmentData.requestMediaStartTime = mediaStartTimeMs
            segmentData.requestVideoWidth = mediaSourceWidth
            segmentData.requestVideoHeight = mediaSourceHeight
            segmentData.requestUrl = segmentUrl
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
            segmentData.requestResponseHeaders = null
            segmentData.requestHostName = host
            hostName = host
            segmentData.requestRenditionLists = renditionList
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
        ): NetworkBandwidthEntity {
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
            Log.d("LDonLoadStarted", Gson().toJson(loadData))
            return loadData
        }

        @Throws(JSONException::class)
        open fun onLoadCompleted(
            loadTaskId: Long,
            segmentUrl: String?,
            bytesLoaded: Long,
            trackFormat: Format?
        ): NetworkBandwidthEntity? {
            val segmentData: NetworkBandwidthEntity = loadedSegments[loadTaskId] ?: return null
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


    internal inner class BandwidthMetricHls : BandwidthMetric() {
        @Throws(JSONException::class)
        override fun onLoadError(
            loadTaskId: Long,
            e: IOException,
            segmentUrl: String?
        ): NetworkBandwidthEntity {
            val loadData: NetworkBandwidthEntity =
                super.onLoadError(loadTaskId, e, segmentUrl)
            return loadData
        }

        @Throws(JSONException::class)
        override fun onLoadCanceled(
            loadTaskId: Long,
            segmentUrl: String?
        ): NetworkBandwidthEntity {
            val loadData: NetworkBandwidthEntity =
                super.onLoadCanceled(loadTaskId, segmentUrl)
            loadData.requestCancel = "FragLoadEmergencyAborted"
            return loadData
        }

        @Throws(JSONException::class)
        override fun onLoadCompleted(
            loadTaskId: Long,
            segmentUrl: String?,
            bytesLoaded: Long,
            trackFormat: Format?
        ): NetworkBandwidthEntity? {
            val loadData: NetworkBandwidthEntity? =
                super.onLoadCompleted(loadTaskId, segmentUrl, bytesLoaded, trackFormat)
            if (trackFormat != null && loadData != null) {
                loadData.requestLabeledBitrate = trackFormat.bitrate
            }
            return loadData
        }
    }

    internal inner class BandwidthMetricDispatcher {
        private val bandwidthMetricHls: BandwidthMetric = BandwidthMetricHls()
        var allowedHeaders: ArrayList<String> = ArrayList()
        protected var requestSegmentDuration: Long = 1000
        protected var lastRequestSentAt: Long = -1
        protected var maxNumberOfEventsPerSegmentDuration: Int = 10
        protected var numberOfRequestCompletedBeaconsSentPerSegment: Int = 0
        protected var numberOfRequestCancelBeaconsSentPerSegment: Int = 0
        protected var numberOfRequestFailedBeaconsSentPerSegment: Int = 0

        init {
            allowedHeaders.add("x-cdn")
            allowedHeaders.add("content-type")
            allowedHeaders.add("server")
            allowedHeaders.add("x-request-id")
        }

        fun currentBandwidthMetric(): BandwidthMetric = bandwidthMetricHls

        @Throws(JSONException::class)
        fun onLoadError(
            loadTaskId: Long,
            segmentUrl: String?,
            e: IOException
        ) {
            if (player == null) {
                return
            }
            val loadData: NetworkBandwidthEntity = currentBandwidthMetric().onLoadError(
                loadTaskId,
                e,
                segmentUrl
            )
            Log.d("LDonLoadFailed", Gson().toJson(loadData))
            dispatch(loadData, MediaStreaming(MediaStreaming.EventType.requestFailed, null))
        }

        @Throws(JSONException::class)
        fun onLoadCanceled(
            loadTaskId: Long,
            segmentUrl: String?,
            headers: Map<String, List<String>>
        ) {
            if (player == null) {
                return
            }
            val loadData: NetworkBandwidthEntity = currentBandwidthMetric().onLoadCanceled(
                loadTaskId,
                segmentUrl
            )
            parseHeaders(loadData, headers)
            Log.d("LDonLoadCanceled", Gson().toJson(loadData))
            dispatch(loadData, MediaStreaming(MediaStreaming.EventType.requestCanceled, null))
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
            if (player == null) {
                return
            }
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

        @Throws(JSONException::class)
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
            if (player == null) {
                return
            }
            val loadData: NetworkBandwidthEntity? = currentBandwidthMetric().onLoadCompleted(
                loadTaskId,
                segmentUrl,
                bytesLoaded,
                trackFormat
            )
            if (loadData != null) {
                parseHeaders(loadData, responseHeaders)
                Log.d("LDonLoadCompleted", Gson().toJson(loadData))
                dispatch(loadData, MediaStreaming(MediaStreaming.EventType.requestCompleted, null))
            }
        }

        @Throws(JSONException::class)
        private fun parseHeaders(
            loadData: NetworkBandwidthEntity,
            responseHeaders: Map<String, List<String>>
        ) {
            val headers: Map<String, String>? = parseHeaders(responseHeaders)
            if (headers != null) {
                loadData.requestId = headers["x-request-id"]
                loadData.requestResponseHeaders = headers
            }
        }

        @Throws(JSONException::class)
        fun onTracksChanged(trackGroups: TrackGroupArray) {
            currentBandwidthMetric().availableTracks = trackGroups
            if (player == null) {
                return
            }
            if (trackGroups.length > 0) {
                for (groupIndex in 0 until trackGroups.length) {
                    val trackGroup = trackGroups[groupIndex]
                    if (0 < trackGroup.length) {
                        var trackFormat = trackGroup.getFormat(0)
                        if (trackFormat.containerMimeType != null && trackFormat.containerMimeType!!.contains(
                                TAG_VIDEO
                            )
                        ) {
                            val renditions: MutableList<NetworkBandwidthEntity.Rendition> =
                                ArrayList()
                            for (i in 0 until trackGroup.length) {
                                trackFormat = trackGroup.getFormat(i)
                                val rendition: NetworkBandwidthEntity.Rendition =
                                    NetworkBandwidthEntity.Rendition()
                                rendition.bitrate = trackFormat.bitrate.toLong()
                                rendition.width = trackFormat.width
                                rendition.height = trackFormat.height
                                rendition.codec = trackFormat.codecs
                                rendition.fps = trackFormat.frameRate.toInt()
                                renditions.add(rendition)
                            }
                            renditionList = renditions
                        }
                    }
                }
            }
        }

        @Throws(JSONException::class)
        private fun dispatch(data: NetworkBandwidthEntity, event: StreamingHub) {
            if (data != null && shouldDispatchEvent(data, event)) {
                event.bandwidthMetricData = data
                this@FastPixBaseMedia3Player.dispatch(event)
            }
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

                    // Skip this header, we do not need it
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
            data: NetworkBandwidthEntity?,
            event: StreamingHub
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
                lastRequestSentAt = System.currentTimeMillis();
                numberOfRequestCompletedBeaconsSentPerSegment = 0;
                numberOfRequestCancelBeaconsSentPerSegment = 0;
                numberOfRequestFailedBeaconsSentPerSegment = 0;
            }
            if (event.type.equals(
                    MediaStreaming(
                        MediaStreaming.EventType.requestCompleted,
                        null
                    )
                )
            ) {
                numberOfRequestCompletedBeaconsSentPerSegment++;
            }
            if (event.type.equals(MediaStreaming(MediaStreaming.EventType.requestCanceled, null))) {
                numberOfRequestCancelBeaconsSentPerSegment++;
            }
            if (event.type.equals(MediaStreaming(MediaStreaming.EventType.requestFailed, null))) {
                numberOfRequestFailedBeaconsSentPerSegment++;
            }
            if (numberOfRequestCompletedBeaconsSentPerSegment > maxNumberOfEventsPerSegmentDuration
                || numberOfRequestCancelBeaconsSentPerSegment > maxNumberOfEventsPerSegmentDuration
                || numberOfRequestFailedBeaconsSentPerSegment > maxNumberOfEventsPerSegmentDuration
            ) {
                return false;
            }
            return true;
        }
    }

    private fun pxToDp(px: Int): Int {
        val context = contextRef.get()
        if (context == null) {
            AnalyticsEventLogger.d(
                TAG,
                "Error retrieving Context for logical resolution, using physical"
            )
            return px
        }
        val displayMetrics = context.resources.displayMetrics
        return ceil((px / displayMetrics.density).toDouble()).toInt()
    }

    /**
     * Constructor for FastPixBaseExoPlayer.
     *
     * @param context     The Android Context, used for accessing system resources.
     * @param player  The ExoPlayer instance used for media playback.
     * @param data    Customer-specific data related to the player session.
     * @param options Custom playback options and configurations.
     */
    init {

        // Store a weak reference to the ExoPlayer instance to prevent memory leaks
        this.player = WeakReference(player)

        // Store a weak reference to the Context to avoid memory leaks
        this.contextRef = WeakReference(context)

        // Initialize the player state to "INIT" (not yet started)
        state = PlayerState.INIT

        // Create an FPDevice instance to capture device-related information
        fpDevice = FPDevice(context)

        // Set the device information in FastPixMetrics for tracking
        FastPixMetrics.setHostDevice(fpDevice)

        // Set the network API for handling network-related requests
        FastPixMetrics.setHostNetworkApi(FastPixNetworkRequests())
        try {
            fastPixMetrics = FastPixMetrics(this, "Exo Player", data, options)

            if (context.resources.getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
                fastPixMetrics.presentationChange(MediaPresentation.FULLSCREEN)
            } else {
                fastPixMetrics.presentationChange(MediaPresentation.NORMAL)

            }
        } catch (e: JSONException) {
            Log.e(JSON_EXCEPTION, e.toString())
        }
        Log.e("isAutoPlay", "inti " + player.playWhenReady)

        setIsItAutoPlay(player.playWhenReady)

        // Add FastPixMetrics as a listener to receive player event updates
        addListener(fastPixMetrics)

        // Initialize ExoPlayerHandler for handling player-related operations using the player's main looper
        playerHandler = ExoPlayerHandler(player.applicationLooper, this)

        // Set flag to indicate whether the current media item has a video track (default: false)
        playItemHaveVideoTrack = false

        // Set the interval at which playback head position updates occur
        setPlaybackHeadUpdateInterval()

        // Check if the player is currently buffering
        if (player.playbackState == Player.STATE_BUFFERING) {
            try {

                // Attempt to start playback
                play()

                // Notify that the player is buffering
                buffering()
            } catch (e: JSONException) {
                Log.e(JSON_EXCEPTION, e.toString())
            }
        } else if (player.playbackState == Player.STATE_READY) {

            // We have to simulate all the events we expect to see here, even though not ideal
            try {

                // Attempt to start playback
                play()

                // Notify that buffering is happening (though ideally, this shouldn't happen in READY state)
                buffering()

                // Notify that the player is now playing content
                playing()
            } catch (e: JSONException) {
                Log.e(JSON_EXCEPTION, e.toString())
            }
        }
        player.addAnalyticsListener(object : AnalyticsListener {

            override fun onSeekStarted(eventTime: AnalyticsListener.EventTime) {
                try {
                    seeking()
                } catch (e: JSONException) {
                    Log.e(JSON_EXCEPTION, e.toString())
                }
            }

            override fun onPositionDiscontinuity(
                eventTime: AnalyticsListener.EventTime,
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {

                //If they seek while paused, this is how we know the seek is complete
                if (reason == Player.DISCONTINUITY_REASON_SEEK && state == PlayerState.PAUSED) { //|| !mediaHasVideoTrack){
                    try {
                        seeked(false)
                    } catch (e: JSONException) {
                        Log.e(JSON_EXCEPTION, e.toString())
                    }
                }
            }

            override fun onDownstreamFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                mediaLoadData: MediaLoadData
            ) {
                if (detectMimeType && mediaLoadData.trackFormat != null && mediaLoadData.trackFormat!!.containerMimeType != null) {
                    mediaMimeType = mediaLoadData.trackFormat!!.containerMimeType
                }
            }

            override fun onPlayWhenReadyChanged(
                eventTime: AnalyticsListener.EventTime,
                playWhenReady: Boolean,
                reason: Int
            ) {
                if (player != null) {
                    try {
                        handleExoPlaybackState(player.playbackState, playWhenReady)
                    } catch (e: JSONException) {
                        Log.e(JSON_EXCEPTION, e.toString())
                    }
                }
            }

            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long
            ) {
                firstFrameRenderedAt = System.currentTimeMillis()
                firstFrameReceived = true
            }

            override fun onPlaybackStateChanged(
                eventTime: AnalyticsListener.EventTime,
                state: Int
            ) {
                if (player != null) {
                    try {
                        handleExoPlaybackState(player.playbackState, player.playWhenReady)
                    } catch (e: JSONException) {
                        Log.e(JSON_EXCEPTION, e.toString())
                    }
                }
            }

            override fun onTimelineChanged(eventTime: AnalyticsListener.EventTime, reason: Int) {
                if (player != null && eventTime.timeline.windowCount > 0) {
                    val window = Timeline.Window()
                    eventTime.timeline.getWindow(0, window)
                    mediaSourceDuration = window.durationMs
                }
            }

            override fun onPlayerError(
                eventTime: AnalyticsListener.EventTime,
                error: PlaybackException
            ) {
                try {
                    internalError(error)
                } catch (e: JSONException) {
                    Log.e(JSON_EXCEPTION, e.toString())
                }
            }

            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?
            ) {
                try {
                    handleVariantChange(format)
                } catch (e: JSONException) {
                    Log.e(JSON_EXCEPTION, e.toString())
                }
            }

            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long
            ) {
                numberOfDroppedFrames = numberOfDroppedFrames + droppedFrames.toLong()
                fastPixMetrics.setDroppedFramesCount(numberOfDroppedFrames)
            }

            override fun onTracksChanged(eventTime: AnalyticsListener.EventTime, tracks: Tracks) {
                val asArray = TrackGroupArray()
                for (i in tracks.groups.indices) {
                    asArray.indexOf(tracks.groups[i].mediaTrackGroup)
                }
                try {
                    bandwidthDispatcher.onTracksChanged(asArray)
                } catch (e: JSONException) {
                    Log.e(JSON_EXCEPTION, e.toString())
                }
            }

            override fun onVideoSizeChanged(
                eventTime: AnalyticsListener.EventTime,
                videoSize: VideoSize
            ) {
                val videoFormat = player.videoFormat
                if (videoFormat != null) {
                    mediaSourceWidth = videoSize.width
                    mediaSourceHeight = videoSize.height
                    sourceFPS = videoFormat.frameRate
                    mediaSourceAdvertisedBitrate = videoFormat.bitrate
                } else {
                    mediaSourceWidth = videoSize.width
                    mediaSourceHeight = videoSize.height
                }
            }

            override fun onLoadCanceled(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData
            ) {
                if (loadEventInfo.uri != null) {
                    bandwidthDispatcher.onLoadCanceled(
                        loadEventInfo.loadTaskId,
                        loadEventInfo.uri.path,
                        loadEventInfo.responseHeaders
                    )
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
                    bandwidthDispatcher.onLoadCompleted(
                        loadEventInfo.loadTaskId,
                        loadEventInfo.uri.path,
                        loadEventInfo.bytesLoaded,
                        mediaLoadData.trackFormat,
                        loadEventInfo.responseHeaders, mediaLoadData.dataType,
                        loadEventInfo.uri.host, segmentMimeType
                    )
                } catch (e: JSONException) {
                    Log.e("onLoadCompleted", "catch")
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
                    bandwidthDispatcher.onLoadError(
                        loadEventInfo.loadTaskId,
                        loadEventInfo.uri.path,
                        error
                    )
                } catch (e: JSONException) {
                    Log.e(JSON_EXCEPTION, e.toString())
                }
            }

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
                    bandwidthDispatcher.onLoadStarted(
                        loadEventInfo.loadTaskId,
                        mediaLoadData.mediaStartTimeMs,
                        mediaLoadData.mediaEndTimeMs,
                        loadEventInfo.uri.path,
                        mediaLoadData.dataType,
                        loadEventInfo.uri.host,
                        segmentMimeType,
                        segmentWidth,
                        segmentHeight
                    )
                }
            }
        })
    }

    companion object {

        // Tag for logging statistics-related events
        protected const val TAG: String = "FastPixBaseExoPlayer"

        // Constant for handling JSON exceptions
        protected const val JSON_EXCEPTION: String = "JSONException"

        // Tag used for identifying video-related logs or events
        protected const val TAG_VIDEO: String = "video"

        // Time to wait (in milliseconds) after the first frame is rendered before performing certain actions
        protected const val TIME_TO_WAIT_AFTER_FIRST_FRAME_RENDERED: Long = 50 // in ms
    }
}