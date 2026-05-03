package top.rootu.dddplayer.bridge

interface BridgeTransport {
    fun send(event: BridgeEvent)
}
