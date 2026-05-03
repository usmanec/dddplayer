package top.rootu.dddplayer.bridge

data class BridgeEnvelope(
    val schema: Int = 1,
    val type: String,
    val client: String,
    val sessionId: String?,
    val ts: Long,
    val payload: BridgeEvent
)

data class BridgeMediaItem(
    val uri: String?,
    val title: String?,
    val filename: String? = null,
    val externalId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val source: String? = null
)

sealed class BridgeEvent {
    abstract val sessionId: String?
    abstract val ts: Long
    abstract val uri: String?

    data class SessionStarted(
        override val sessionId: String?,
        override val ts: Long,
        override val uri: String?,
        val title: String?,
        val playlistSize: Int,
        val startIndex: Int,
        val startPosition: Long? = null,
        val currentItem: BridgeMediaItem? = null
    ) : BridgeEvent()

    data class PlaybackStateChanged(
        override val sessionId: String?,
        override val ts: Long,
        override val uri: String?,
        val isPlaying: Boolean,
        val isBuffering: Boolean,
        val position: Long?,
        val duration: Long?,
        val windowIndex: Int? = null,
        val title: String? = null
    ) : BridgeEvent()

    data class PositionTick(
        override val sessionId: String?,
        override val ts: Long,
        override val uri: String?,
        val position: Long?,
        val duration: Long?,
        val bufferedPosition: Long?,
        val bufferedPercentage: Int?,
        val windowIndex: Int? = null,
        val title: String? = null
    ) : BridgeEvent()

    data class SeekCompleted(
        override val sessionId: String?,
        override val ts: Long,
        override val uri: String?,
        val fromPosition: Long?,
        val toPosition: Long?,
        val windowIndex: Int? = null
    ) : BridgeEvent()

    data class PlaylistItemChanged(
        override val sessionId: String?,
        override val ts: Long,
        override val uri: String?,
        val windowIndex: Int,
        val playlistSize: Int,
        val title: String?,
        val reason: String,
        val position: Long?,
        val duration: Long?,
        val hasPrevious: Boolean,
        val hasNext: Boolean,
        val currentItem: BridgeMediaItem? = null
    ) : BridgeEvent()

    data class PlaybackEnded(
        override val sessionId: String?,
        override val ts: Long,
        override val uri: String?,
        val windowIndex: Int,
        val playlistSize: Int,
        val title: String?,
        val position: Long?,
        val duration: Long?
    ) : BridgeEvent()

    data class SessionFinished(
        override val sessionId: String?,
        override val ts: Long,
        override val uri: String?,
        val position: Long?,
        val duration: Long?,
        val endBy: String,
        val windowIndex: Int? = null,
        val playlistSize: Int? = null,
        val title: String? = null
    ) : BridgeEvent()

    data class Error(
        override val sessionId: String?,
        override val ts: Long,
        override val uri: String?,
        val code: String?,
        val message: String?,
        val windowIndex: Int? = null
    ) : BridgeEvent()

    data class UserAction(
        override val sessionId: String?,
        override val ts: Long,
        override val uri: String?,
        val action: String,
        val payload: Map<String, String> = emptyMap(),
        val windowIndex: Int? = null
    ) : BridgeEvent()
}
