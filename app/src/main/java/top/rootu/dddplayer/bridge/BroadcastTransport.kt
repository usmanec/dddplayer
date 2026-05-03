package top.rootu.dddplayer.bridge

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.gson.Gson

class BroadcastTransport(
    private val context: Context,
    private val config: BridgeConfig
) : BridgeTransport {

    private val gson = Gson()

    override fun send(event: BridgeEvent) {
        val type = event::class.simpleName ?: "Unknown"
        val envelope = BridgeEnvelope(
            schema = config.schemaVersion,
            type = type,
            client = config.client,
            sessionId = event.sessionId,
            ts = event.ts,
            payload = event
        )

        val intent = Intent(config.eventAction)
        config.receiverPackage?.takeIf { it.isNotBlank() }?.let { intent.setPackage(it) }
        intent.putExtra(EXTRA_SCHEMA, config.schemaVersion)
        intent.putExtra(EXTRA_CLIENT, config.client)
        intent.putExtra(EXTRA_SESSION_ID, event.sessionId)
        intent.putExtra(EXTRA_EVENT_TYPE, type)
        val json = gson.toJson(envelope)
        Log.d("DDDPlayerBridge", json)
        intent.putExtra(EXTRA_EVENT_JSON, json)
        context.sendBroadcast(intent)
    }

    companion object {
        const val DEFAULT_ACTION_EVENT = "top.rootu.dddplayer.bridge.EVENT"
        const val EXTRA_SCHEMA = "schema"
        const val EXTRA_CLIENT = "client"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_EVENT_TYPE = "event_type"
        const val EXTRA_EVENT_JSON = "event_json"
    }
}
