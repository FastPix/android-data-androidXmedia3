[![License](https://img.shields.io/badge/License-Proprietary-blue.svg)](LICENSE)
[![Version](https://img.shields.io/badge/version-1.2.1-green.svg)](CHANGELOG.md)
[![Min SDK](https://img.shields.io/badge/minSdk-24-orange.svg)](app/build.gradle.kts)

# FastPix Media3 SDK

This SDK enables seamless integration with **AndroidX Media3 ExoPlayer**, offering advanced video analytics via the **FastPix Dashboard**. It's a wrapper built on [FastPix’s core Kotlin library](https://github.com/FastPix/android-core-data-sdk) to deliver performance monitoring for video applications using [Google's ExoPlayer via AndroidX Media3](https://developer.android.com/media/media3).

## Key Features
- **User engagement tracking** – Monitor viewer interactions in real-time.
- **Playback quality analytics** – Evaluate buffering, resolution changes, and network issues.
- **Custom event tracking** – Track domain-specific user behaviors.
- **Device & app diagnostics** – Gain insights into playback issues across devices.
- **Error logging** – Automatically capture fatal and handled playback errors.
- **Beacon domain support** – Send analytics to a custom tracking domain.

## 🔧 Requirements

- **Android Studio**: Ladybug | 2024.2.1 or later
- **Minimum SDK**: 24 (Android 7.0)
- **Target SDK**: 35 (Android 15)
- **Compile SDK**: 36
- **Kotlin**: 2.2.10+
- **Java**: 11
- **Gradle**: 8.9+

## 🚀 Setup

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
    implementation 'io.fastpix.data:media3:1.2.1'
}
```

## 📖 Usage

### Basic Player Setup

```kotlin
class VideoPlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityVideoPlayerBinding
    private lateinit var exoPlayer: ExoPlayer
    private lateinit var fastPixDataSDK: FastPixBaseMedia3Player

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPlayer()
        setupAnalytics()
    }

    private fun setupPlayer() {
        exoPlayer = ExoPlayer.Builder(this).build()
        binding.playerView.player = exoPlayer
        
        val mediaItem = MediaItem.fromUri("YOUR_VIDEO_URL")
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
    }

    private fun setupAnalytics() {
        // Optional
        val videoDataDetails = VideoDataDetails(
            videoId = UUID.randomUUID().toString(),
            videoTitle = "My Video"
        ).apply {
            videoSeries = "Demo Series"
            videoProducer = "Demo Producer"
            videoContentType = "VOD"
            // ..etc
        }
        // Optional
        val playerDataDetails = PlayerDataDetails(
            playerName = "media3",
            playerVersion = "latest-version"
        )
        // Optional
        val customDataDetails = CustomDataDetails().apply {
            customField1 = "Custom Value 1"
            customField2 = "Custom Value 2"
            // ..etc
        }

        fastPixDataSDK = FastPixBaseMedia3Player(
            context = this,
            playerView = binding.playerView,
            exoPlayer = exoPlayer,
            workSpaceId = "workspace-key",
            playerDataDetails = playerDataDetails,
            videoDataDetails = videoDataDetails,
            customDataDetails = customDataDetails
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer.release()
        fastPixDataSDK.release()
    }
}
```

## 📄 License

Copyright © 2025 FastPix. All rights reserved.

This project is proprietary software. The FastPix SDK components are proprietary and require appropriate licensing. See individual module README files for specific license information.

## 📧 Support

For questions, issues, or feature requests:

- **Email**: support@fastpix.io
- **Documentation**: [FastPix Documentation](https://docs.fastpix.io)
- **SDK Issues**: [GitHub Issues](https://github.com/FastPix/android-core-data-sdk/issues)

## 🔗 Related Documentation

- [Android Data Core SDK README](android-data-core/README.md)
- [FastPix Documentation](https://docs.fastpix.io)
- [AndroidX Media3 Documentation](https://developer.android.com/guide/topics/media/media3)
- [ExoPlayer Developer Guide](https://developer.android.com/guide/topics/media/exoplayer)

---

**Built with ❤️ using AndroidX Media3 and FastPix Analytics**

