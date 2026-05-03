package top.rootu.dddplayer.bridge

data class BridgeConfig(
    val enabled: Boolean = false,
    val sessionId: String? = null,
    val mode: BridgeMode = BridgeMode.BROADCAST,
    val emitPosition: Boolean = true,
    val emitUserActions: Boolean = true,
    val positionIntervalMs: Long = 1000L,
    val client: String = "lampa",
    val eventAction: String = BroadcastTransport.DEFAULT_ACTION_EVENT,
    val receiverPackage: String? = null,
    val schemaVersion: Int = 1,
    val localPort: Int = 39677,
    val localToken: String? = null
)

enum class BridgeMode {
    BROADCAST,
    LOCAL,
    BOTH
}
