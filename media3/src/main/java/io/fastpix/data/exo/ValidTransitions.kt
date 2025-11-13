package io.fastpix.data.exo

internal val validTransitions = mapOf(
    null to setOf(PlayerEvent.PLAY, PlayerEvent.ERROR),
    PlayerEvent.PLAY to setOf(PlayerEvent.PLAYING, PlayerEvent.ENDED, PlayerEvent.PAUSE, PlayerEvent.VARIANT_CHANGED,
        PlayerEvent.SEEKING,
        PlayerEvent.ERROR),
    PlayerEvent.PLAYING to setOf(
        PlayerEvent.BUFFERING,
        PlayerEvent.PAUSE,
        PlayerEvent.ENDED,
        PlayerEvent.SEEKING,
        PlayerEvent.VARIANT_CHANGED,
        PlayerEvent.ERROR
    ),
    PlayerEvent.BUFFERING to setOf(PlayerEvent.BUFFERED, PlayerEvent.ERROR,  PlayerEvent.VARIANT_CHANGED),
    PlayerEvent.BUFFERED to setOf(
        PlayerEvent.PAUSE,
        PlayerEvent.SEEKING,
        PlayerEvent.PLAYING,
        PlayerEvent.ENDED,
        PlayerEvent.ERROR,
        PlayerEvent.VARIANT_CHANGED
    ),
    PlayerEvent.PAUSE to setOf(
        PlayerEvent.SEEKING,
        PlayerEvent.PLAY,
        PlayerEvent.ENDED,
        PlayerEvent.ERROR,
        PlayerEvent.VARIANT_CHANGED
    ),
    PlayerEvent.SEEKING to setOf(
        PlayerEvent.SEEKED,
        PlayerEvent.ENDED,
        PlayerEvent.ERROR,
        PlayerEvent.VARIANT_CHANGED
    ),
    PlayerEvent.SEEKED to setOf(PlayerEvent.PLAY, PlayerEvent.ENDED, PlayerEvent.ERROR,  PlayerEvent.VARIANT_CHANGED,
        PlayerEvent.PLAYING, PlayerEvent.SEEKING),
    PlayerEvent.ENDED to setOf(
        PlayerEvent.PLAY,
        PlayerEvent.PAUSE,
        PlayerEvent.ERROR,
        PlayerEvent.VARIANT_CHANGED,
    ),
    PlayerEvent.ERROR to setOf(PlayerEvent.PLAYING, PlayerEvent.PLAY, PlayerEvent.PAUSE,PlayerEvent.BUFFERED)
)