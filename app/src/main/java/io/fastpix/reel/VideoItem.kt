package io.fastpix.reelapp

/**
 * Data class representing a video item in the feed
 */
data class VideoItem(
    val url: String,
    val id: String = url
)

