package top.rootu.dddplayer.bridge

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

object LocalBridgeStore {
    private val states = ConcurrentHashMap<String, BridgeEnvelope>()
    private val events = ConcurrentHashMap<String, MutableList<BridgeEnvelope>>()

    fun put(envelope: BridgeEnvelope) {
        val sid = envelope.sessionId ?: return
        states[sid] = envelope
        val list = events.computeIfAbsent(sid) { Collections.synchronizedList(mutableListOf()) }
        synchronized(list) {
            list.add(envelope)
            while (list.size > 200) list.removeAt(0)
        }
    }

    fun getState(sessionId: String): BridgeEnvelope? = states[sessionId]

    fun getEvents(sessionId: String): List<BridgeEnvelope> {
        val list = events[sessionId] ?: return emptyList()
        return synchronized(list) { list.toList() }
    }

    fun clear(sessionId: String) {
        states.remove(sessionId)
        events.remove(sessionId)
    }

    fun clearAll() {
        states.clear()
        events.clear()
    }
}
