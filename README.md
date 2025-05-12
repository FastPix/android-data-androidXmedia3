# FastPix ExoPlayer SDK (Media3 Compatible)

This SDK enables seamless integration with **AndroidX Media3 ExoPlayer**, offering advanced video analytics via the **FastPix Dashboard**. It's a wrapper built on [FastPix’s core Java library](https://github.com/FastPix/android-core-data-sdk) to deliver performance monitoring for video applications using [Google's ExoPlayer via AndroidX Media3](https://developer.android.com/media/media3).

---

## Key Features
- **User engagement tracking** – Monitor viewer interactions in real-time.
- **Playback quality analytics** – Evaluate buffering, resolution changes, and network issues.
- **Custom event tracking** – Track domain-specific user behaviors.
- **Device & app diagnostics** – Gain insights into playback issues across devices.
- **Error logging** – Automatically capture fatal and handled playback errors.
- **Beacon domain support** – Send analytics to a custom tracking domain.

---

## Prerequisites
- Android Studio Arctic Fox or newer
- Android SDK version 21+
- AndroidX Media3 dependency for ExoPlayer
- FastPix ExoPlayer SDK (media3-compatible) as a dependency
- GitHub Personal Access Token (PAT) for private Maven access

---

## Installation

### Step 1: Add the GitHub Maven Repository to `settings.gradle`
```groovy
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/FastPix/android-data-media3-player-sdk")
        credentials {
            username = "<your-github-username>"
            password = "<your-personal-access-token>"
        }
    }
}
```

### Step 2: Add the SDK Dependency to `build.gradle`
```groovy
dependencies {
    implementation 'io.fastpix.data:media3:1.0.0'
}
```

---

## Basic Usage

Ensure Media3 ExoPlayer is initialized properly:

### Kotlin Setup
```kotlin
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class VideoPlayerActivity : AppCompatActivity() {
    private lateinit var exoPlayer: ExoPlayer
    private lateinit var fastPixBaseMedia3Player: FastPixBaseMedia3Player

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)

        val playerView: PlayerView = findViewById(R.id.player_view)
        exoPlayer = ExoPlayer.Builder(this).build()
        playerView.player = exoPlayer

        val customerDataEntity = CustomerDataEntity(
            CustomerPlayerDataEntity().apply {
                workspaceKey = Constants.wsKey
                playerName = "Media3 ExoPlayer"
            },
            CustomerVideoDataEntity().apply {
                videoId = "id"
                videoTitle = "title"
                videoSourceUrl = "url"
            },
            CustomerViewDataEntity().apply {
                viewSessionId = UUID.randomUUID().toString()
            }
        )

        val customOptions = CustomOptions().apply {
            beaconDomain = "your.custom.domain"
        }

        fastPixBaseMedia3Player = FastPixBaseMedia3Player(this, exoPlayer, customerDataEntity, customOptions)
        fastPixBaseMedia3Player.setPlayerView(playerView)
    }

    override fun onDestroy() {
        super.onDestroy()
        fastPixBaseMedia3Player.release()
    }
}
```

### XML Layout
```xml
<androidx.media3.ui.PlayerView
    android:id="@+id/player_view"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@android:color/black"
    app:auto_show="true"
    app:hide_on_touch="true"
    app:surface_type="surface_view" />
```

---

## Changing Video Sources
Call `fastPixBaseMedia3Player.videoChange(CustomerVideoData)` when switching videos to reset analytics context:
```kotlin
val newVideoData = CustomerVideoDataEntity().apply {
    videoId = "newId"
    videoTitle = "New Video"
    videoSourceUrl = "newUrl"
}
fastPixBaseMedia3Player.videoChange(newVideoData)
```

---

## Error Handling
To manually report handled errors:
```kotlin
fastPixBaseMedia3Player.error(FastPixErrorException("Custom playback error"))
```
Disable automatic error tracking if needed:
```kotlin
fastPixBaseMedia3Player.setAutomaticErrorTracking(false)
```

---

## Documentation
For advanced usage and APIs, refer to the [FastPix Developer Docs](https://docs.fastpix.io/docs/******).

