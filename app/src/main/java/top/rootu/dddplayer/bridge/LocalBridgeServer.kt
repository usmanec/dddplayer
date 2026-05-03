package top.rootu.dddplayer.bridge

import android.util.Log
import com.google.gson.Gson
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

object LocalBridgeServer {
    private const val TAG = "DDDPlayerLocalServer"

    private val gson = Gson()
    private val clientExecutor = Executors.newCachedThreadPool()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running: Boolean = false
    @Volatile private var currentPort: Int = 39677
    @Volatile private var currentToken: String? = null
    @Volatile private var stopFuture: ScheduledFuture<*>? = null

    @Synchronized
    fun ensureStarted(port: Int = 39677, token: String? = null) {
        stopFuture?.cancel(false)
        stopFuture = null

        if (running && serverSocket != null && currentPort == port) {
            currentToken = token
            Log.d(TAG, "already running on 127.0.0.1:$port")
            return
        }

        stop()
        currentPort = port
        currentToken = token

        try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
            serverSocket = socket
            running = true

            Thread { acceptLoop(socket) }.apply {
                name = "DDDPlayerLocalBridgeServer"
                isDaemon = true
                start()
            }

            Log.d(TAG, "started on http://127.0.0.1:$port")
        } catch (e: Exception) {
            running = false
            serverSocket = null
            Log.e(TAG, "failed to start local bridge server", e)
        }
    }

    @Synchronized
    fun scheduleStopAfter(delayMs: Long) {
        stopFuture?.cancel(false)
        stopFuture = scheduler.schedule({ stop() }, delayMs, TimeUnit.MILLISECONDS)
        Log.d(TAG, "scheduled stop after ${delayMs}ms")
    }

    @Synchronized
    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        Log.d(TAG, "stopped")
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running && !socket.isClosed) {
            try {
                val client = socket.accept()
                clientExecutor.execute { handleClient(client) }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "accept failed", e)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            try {
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
                val requestLine = reader.readLine()
                if (requestLine.isNullOrBlank()) {
                    writeJson(client, 400, "{\"error\":\"empty_request\"}")
                    return
                }

                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) break
                }

                val parts = requestLine.split(" ")
                if (parts.size < 2) {
                    writeJson(client, 400, "{\"error\":\"bad_request\"}")
                    return
                }

                val method = parts[0].uppercase()
                val target = parts[1]

                if (method == "OPTIONS") {
                    writeJson(client, 204, "")
                    return
                }
                if (method != "GET") {
                    writeJson(client, 405, "{\"error\":\"method_not_allowed\"}")
                    return
                }

                val uri = parseTarget(target)
                val path = uri.path ?: "/"
                val query = parseQuery(uri.rawQuery)

                when (path) {
                    "/ping" -> writeJson(client, 200, "{\"ok\":true,\"service\":\"dddplayer-local-bridge\"}")
                    "/state" -> {
                        if (!authorized(query)) {
                            writeJson(client, 403, "{\"error\":\"forbidden\"}")
                            return
                        }
                        val sid = query["sid"]
                        if (sid.isNullOrBlank()) {
                            writeJson(client, 400, "{\"error\":\"missing_sid\"}")
                            return
                        }
                        val state = LocalBridgeStore.getState(sid)
                        if (state == null) {
                            writeJson(client, 404, "{\"error\":\"not_found\"}")
                            return
                        }
                        writeJson(client, 200, gson.toJson(state))
                    }
                    "/events" -> {
                        if (!authorized(query)) {
                            writeJson(client, 403, "{\"error\":\"forbidden\"}")
                            return
                        }
                        val sid = query["sid"]
                        if (sid.isNullOrBlank()) {
                            writeJson(client, 400, "{\"error\":\"missing_sid\"}")
                            return
                        }
                        writeJson(client, 200, gson.toJson(LocalBridgeStore.getEvents(sid)))
                    }
                    else -> writeJson(client, 404, "{\"error\":\"not_found\"}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "client handling failed", e)
                try { writeJson(socket, 500, "{\"error\":\"internal_error\"}") } catch (_: Exception) {}
            }
        }
    }

    private fun authorized(query: Map<String, String>): Boolean {
        val token = currentToken
        if (token.isNullOrBlank()) return true
        return query["token"] == token
    }

    private fun parseTarget(target: String): URI {
        return try {
            if (target.startsWith("http://") || target.startsWith("https://")) URI(target)
            else URI("http://127.0.0.1:$currentPort$target")
        } catch (_: Exception) {
            URI("http://127.0.0.1:$currentPort/")
        }
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split("&").mapNotNull { pair ->
            val idx = pair.indexOf("=")
            if (idx <= 0) null else {
                val key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8.name())
                val value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8.name())
                key to value
            }
        }.toMap()
    }

    private fun writeJson(socket: Socket, status: Int, body: String) {
        val reason = when (status) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            500 -> "Internal Server Error"
            else -> "OK"
        }

        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: *\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("\r\n")
        }

        val out = socket.getOutputStream()
        out.write(headers.toByteArray(StandardCharsets.UTF_8))
        if (bytes.isNotEmpty()) out.write(bytes)
        out.flush()
    }
}
