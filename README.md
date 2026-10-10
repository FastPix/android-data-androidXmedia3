# FastPix ExoPlayer SDK for AndroidX Media3 - monitor playback performance and QoE

[![License](https://img.shields.io/badge/License-Proprietary-blue.svg)](LICENSE)
[![Version](https://img.shields.io/badge/version-1.2.7-green.svg)](CHANGELOG.md)
[![Min SDK](https://img.shields.io/badge/minSdk-24-orange.svg)](app/build.gradle.kts)
[![ExoPlayer](https://img.shields.io/badge/ExoPlayer-AndroidX%20Media3-3DDC84.svg)](https://developer.android.com/media/media3)

**Use the FastPix ExoPlayer SDK to monitor streaming quality, playback performance, and user interactions in your Android app.** It is the official FastPix Video Data SDK for [AndroidX Media3 ExoPlayer](https://developer.android.com/media/media3) - wrap your `ExoPlayer` with `FastPixBaseMedia3Player` and it automatically tracks startup time, rebuffering, bitrate changes, playback errors, and viewer engagement, then streams them as real-time quality-of-experience (QoE) analytics to the [FastPix dashboard](https://dashboard.fastpix.com). It builds on [FastPix's Android Data Core SDK](https://github.com/FastPix/android-core-data-sdk).

It is not a video player - it is a drop-in analytics layer for the ExoPlayer you already use. Under the hood it hooks into Media3's `AnalyticsListener`, so there is no manual event wiring.

**Works with:** AndroidX Media3 ExoPlayer on Android 7.0 (API 24) and above, HLS and MP4 sources, Kotlin and Java. Powered by [FastPix](https://fastpix.com), the video API platform.

[FastPix docs](https://fastpix.com/docs/video-data/monitors/androidx-media3) · [Video Data overview](https://fastpix.com/docs/video-data/overview) · [Create a free account](https://dashboard.fastpix.com/signup?utm_source=github&utm_medium=referral&utm_campaign=github_signup)

---

## Start here

New to the SDK? Follow these in order and you will have ExoPlayer analytics flowing to your dashboard:

1. [Why FastPix for ExoPlayer?](#why-fastpix-for-exoplayer)
2. [What you can track](#what-you-can-track)
3. [How it works](#how-it-works)
4. [Who it's for](#who-its-for)
5. [Requirements](#requirements)
6. [Before you start](#before-you-start)
7. [Add the GitHub Packages repositories](#add-the-github-packages-repositories)
8. [Add the SDK dependency](#add-the-sdk-dependency)
9. [Add the required permissions](#add-the-required-permissions)
10. [Attach the SDK to your ExoPlayer](#attach-the-sdk-to-your-exoplayer)
11. [Verify events in your dashboard](#verify-events-in-your-dashboard)
12. [Which FastPix data SDK do I need?](#which-fastpix-data-sdk-do-i-need)
13. [FAQ](#faq)
14. [Troubleshooting](#troubleshooting)

---

## Why FastPix for ExoPlayer?

Media3's `AnalyticsListener` and `PlaybackStatsListener` give you raw playback events, but turning them into startup-time, rebuffering, and error dashboards is work you have to build and maintain. FastPix does that for you: wrap your `ExoPlayer`, pass your workspace key, and every session shows up on the dashboard with QoE metrics you can slice by device, app version, video, and custom dimensions.

- **No new player required.** Keep the `ExoPlayer` you already ship. The SDK observes it; it does not replace it.
- **Real-time QoE analytics.** Startup time, rebuffering, bitrate/resolution changes, and playback failures, collected as the video plays.
- **Viewer engagement.** See what people actually watch, and where they drop off.
- **Built on Media3's own hooks.** It attaches through `AnalyticsListener`, so it stays accurate across ExoPlayer versions.
- **Custom and beacon domains.** Attach up to ten custom dimensions and send analytics to a custom tracking domain.

## What you can track

- **User engagement** - how viewers interact with your video in real time.
- **Playback quality** - buffering, resolution changes, and network-aware context.
- **Device and app diagnostics** - spot playback issues specific to a device, OS, or app version.
- **Errors** - automatic capture of fatal and handled playback errors.
- **Custom metadata** - domain-specific dimensions you can filter the dashboard by.

## How it works

<Image alt="How FastPix monitors ExoPlayer: AndroidX Media3 player to the FastPix SDK to the dashboard" border={false} src="https://static.fastpix.com/media3-exoplayer-data-flow.png" />

Your app plays a video with AndroidX Media3 `ExoPlayer`. You wrap that player with `FastPixBaseMedia3Player`, which attaches to its `AnalyticsListener` and turns playback signals (startup, bitrate, rebuffering, errors, engagement) into beacons sent to FastPix, keyed to your workspace. A few minutes later the session appears in your FastPix dashboard as QoE and engagement analytics.

## Who it's for

Any Android team that streams video with ExoPlayer and needs to see how playback actually performs:

- **OTT and streaming apps** - track startup time and rebuffering across phones, tablets, and Android TV.
- **E-learning and course apps** - see which lessons stall or get abandoned.
- **Live events and sports** - catch stream-quality problems while they are happening.
- **Short-form and UGC platforms** - measure engagement and playback failures at scale.

## Requirements

- **Android Studio**: Ladybug | 2024.2.1 or later
- **Minimum SDK**: 24 (Android 7.0)
- **Target SDK**: 35 (Android 15)
- **Compile SDK**: 36
- **Kotlin**: 2.2.10+
- **Java**: 11
- **Gradle**: 8.9+

## Before you start

You will need:

- **A FastPix account and workspace key.** [Create a free account](https://dashboard.fastpix.com/signup?utm_source=github&utm_medium=referral&utm_campaign=github_signup), then copy the **Workspace Key** from your FastPix dashboard. See [Set up a workspace](https://fastpix.com/docs/getting-started/set-up-a-workspace). This is the only value the SDK strictly requires.
- **An ExoPlayer that already plays a video.** The SDK wraps your Media3 `ExoPlayer`, so it must already be playing before you add analytics.
- **A GitHub Personal Access Token (PAT)** with the `read:packages` scope. The SDK and its core dependency are published to **GitHub Packages**, which requires authentication even for public packages. [Create a token](https://github.com/settings/tokens).

---

## Add the GitHub Packages repositories

The SDK (`io.fastpix.data:media3`) and its required core dependency (`io.fastpix.data:core`) live in **two** GitHub Packages repositories. Add **both**, or the core dependency will fail to resolve. Put your GitHub username and PAT in `gradle.properties` (or `~/.gradle/gradle.properties`, so credentials stay out of source control) and reference them here.

In `settings.gradle`:

```groovy
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/FastPix/android-data-androidXmedia3")
            credentials {
                username = "<your-github-username>"
                password = "<your-personal-access-token>"
            }
        }
        maven {
            url = uri("https://maven.pkg.github.com/FastPix/android-core-data-sdk")
            credentials {
                username = "<your-github-username>"
                password = "<your-personal-access-token>"
            }
        }
    }
}
```

## Add the SDK dependency

In your app module's `build.gradle`:

```groovy
dependencies {
    implementation 'io.fastpix.data:media3:1.2.7'
}
```

This pulls in `io.fastpix.data:core` transitively, which is why the core repository above is required.

## Add the required permissions

The SDK sends analytics over the network, so your app's `AndroidManifest.xml` needs both:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

## Attach the SDK to your ExoPlayer

Set up your `ExoPlayer` as usual, then create a `FastPixBaseMedia3Player` with your player view, the ExoPlayer instance, and your workspace key. The video, player, and custom metadata objects are optional but make the dashboard easier to slice. Release the SDK alongside the player.

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

Replace `workspace-key` with your real Workspace Key and `YOUR_VIDEO_URL` with your stream. For the full list of metadata fields, see [Use custom dimensions](https://fastpix.com/docs/video-data/use-custom-dimensions).

## Verify events in your dashboard

1. Run the app and play a video for at least 10 seconds. Pause, resume, and seek so the SDK captures a few events.
2. Open the [FastPix dashboard](https://dashboard.fastpix.com) and go to **Video Data**.
3. A few minutes after playback, your session appears with views, startup time, rebuffering, and any errors.

If nothing shows up, see [Troubleshooting](#troubleshooting).

---

## Which FastPix data SDK do I need?

Pick the data SDK that matches the player you ship. All of them report to the same FastPix Video Data dashboard.

| You are using | Use this SDK |
|---|---|
| AndroidX Media3 / ExoPlayer | **This repo** ([android-data-androidXmedia3](https://github.com/FastPix/android-data-androidXmedia3)) |
| Legacy ExoPlayer 2.x (`com.google.android.exoplayer2`) | [android-data-exoplayer-sdk](https://github.com/FastPix/android-data-exoplayer-sdk) |
| Android native `MediaPlayer` | [android-media-player-data](https://github.com/FastPix/android-media-player-data) |
| AVPlayer (iOS) | [iOS-data-avplayer-sdk](https://github.com/FastPix/iOS-data-avplayer-sdk) |
| React Native (Android + iOS) | [react-native-video-data](https://github.com/FastPix/react-native-video-data) |
| An HTML5 web player (Video.js, Shaka, HLS.js) | [web-video-data-core-sdk](https://github.com/FastPix/web-video-data-core-sdk) |

Related building block: [android-core-data-sdk](https://github.com/FastPix/android-core-data-sdk) - the foundational data layer this SDK wraps.

## FAQ

**How do I monitor ExoPlayer performance in Android?**
Wrap your Media3 `ExoPlayer` with `FastPixBaseMedia3Player` and pass your workspace key (see [Attach the SDK to your ExoPlayer](#attach-the-sdk-to-your-exoplayer)). From then on, streaming quality, startup time, rebuffering, playback errors, and engagement appear in the FastPix dashboard - no manual `AnalyticsListener` wiring.

**How do I track video analytics in an Android app?**
If your app uses AndroidX Media3 / ExoPlayer, add this SDK, wrap your `ExoPlayer`, and pass your workspace key. It builds on Media3's `AnalyticsListener` and sends the data to the FastPix dashboard. Start at [Add the GitHub Packages repositories](#add-the-github-packages-repositories).

**Is this an alternative to Mux Data for ExoPlayer?**
Yes. Like Mux Data for ExoPlayer, FastPix provides a native tracking wrapper for AndroidX Media3 / ExoPlayer that monitors startup time, rebuffering, bitrate, playback errors, and engagement, and reports them to the FastPix dashboard.

**Does it work with the old ExoPlayer 2.x library?**
This repo is for AndroidX Media3 (the current ExoPlayer). For the legacy standalone `com.google.android.exoplayer2` library, use [android-data-exoplayer-sdk](https://github.com/FastPix/android-data-exoplayer-sdk). Both report to the same FastPix dashboard.

**What metrics does it track?**
Startup time, rebuffering, bitrate and resolution changes, playback errors, and viewer engagement - the signals that make up a quality-of-experience (QoE) score - plus device, OS, and app diagnostics.

**What is the minimum Android version?**
Android 7.0 (API level 24).

**Why do I need a GitHub Personal Access Token to install it?**
The SDK and its core dependency are hosted on GitHub Packages, which requires authentication (a PAT with `read:packages`) even for public packages. This is a GitHub requirement.

**Why does Gradle say it can't find `io.fastpix.data:core`?**
Because you only added the media3 repository. The SDK depends on the core artifact in a separate GitHub Packages repo. Add both repositories from [Add the GitHub Packages repositories](#add-the-github-packages-repositories).

**Where do I get my workspace key?**
From the FastPix dashboard. [Create a free account](https://dashboard.fastpix.com/signup?utm_source=github&utm_medium=referral&utm_campaign=github_signup) and copy the Workspace Key. See [Set up a workspace](https://fastpix.com/docs/getting-started/set-up-a-workspace).

## Troubleshooting

- **No data in the dashboard.** Confirm the `INTERNET` permission is present ([Add the required permissions](#add-the-required-permissions)), that the workspace key is your real key, and that the video actually played. Data can take a few minutes to appear.
- **Build fails resolving `io.fastpix.data:core`.** Add the core GitHub Packages repository ([Add the GitHub Packages repositories](#add-the-github-packages-repositories)).
- **401 / 403 from GitHub Packages.** Your PAT is missing or lacks the `read:packages` scope. [Regenerate it](https://github.com/settings/tokens) and re-run the build.

## License

Copyright © 2025 FastPix. All rights reserved. This project is proprietary software; the FastPix SDK components require appropriate licensing. See [LICENSE](LICENSE).

## Support

For questions, issues, or feature requests:
- **Email**: support@fastpix.com
- **Documentation**: [Monitor AndroidX Media3 / ExoPlayer](https://fastpix.com/docs/video-data/monitors/androidx-media3)
- **Issues**: [GitHub Issues](https://github.com/FastPix/android-data-androidXmedia3/issues)

## Related documentation

- [Monitor AndroidX Media3 / ExoPlayer](https://fastpix.com/docs/video-data/monitors/androidx-media3)
- [FastPix Video Data overview](https://fastpix.com/docs/video-data/overview)
- [Use custom dimensions](https://fastpix.com/docs/video-data/use-custom-dimensions)
- [AndroidX Media3 Documentation](https://developer.android.com/media/media3)
- [ExoPlayer Developer Guide](https://developer.android.com/media/media3/exoplayer)
