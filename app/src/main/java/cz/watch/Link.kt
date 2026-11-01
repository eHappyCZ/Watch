package cz.watch

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class Link(
    context: Context,
    private val code: String,
    private val role: String,
    private val onMessage: (JSONObject) -> Unit,
    private val onOpen: () -> Unit = {},
) {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val client = OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS).build()
    private val handler = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    @Volatile private var open = false
    private var stopped = true
    private var retry = 0

    private val reconnect = Runnable { if (!stopped) connect() }

    private val netCb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post { if (!stopped && !open) { retry = 0; handler.removeCallbacks(reconnect); connect() } }
        }
    }

    fun start() {
        stopped = false
        try { cm.registerDefaultNetworkCallback(netCb) } catch (_: Exception) {}
        connect()
    }

    fun stop() {
        stopped = true
        open = false
        handler.removeCallbacks(reconnect)
        try { cm.unregisterNetworkCallback(netCb) } catch (_: Exception) {}
        ws?.close(1000, null)
        ws = null
    }

    val isOpen: Boolean get() = open

    fun send(msg: JSONObject): Boolean = open && ws?.send(msg.toString()) == true

    private fun connect() {
        ws?.cancel()
        val req = Request.Builder().url(BuildConfig.SERVER_URL).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                handler.post {
                    if (ws !== webSocket) return@post
                    open = true
                    retry = 0
                    webSocket.send(
                        JSONObject().put("t", "join").put("code", code).put("role", role).toString()
                    )
                    onOpen()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val obj = try { JSONObject(text) } catch (_: Exception) { return }
                handler.post { if (ws === webSocket) onMessage(obj) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(webSocket)
        })
    }

    private fun lost(webSocket: WebSocket) {
        handler.post {
            if (ws !== webSocket || stopped) return@post
            open = false
            val delay = minOf(30_000L, 1000L shl minOf(retry, 5))
            retry++
            handler.removeCallbacks(reconnect)
            handler.postDelayed(reconnect, delay)
        }
    }
}

object ParentLink {
    val links = java.util.concurrent.ConcurrentHashMap<String, Link>()
}

object ChildLink {
    @Volatile var current: Link? = null

    @Volatile var lastBlocked: Boolean? = null
    @Volatile var lastAt: Long = 0

    @Volatile var vpnErr = false

    @Volatile var onVpnChanged: (() -> Unit)? = null

    fun flushPending(context: Context) {
        val at = Prefs.pendingAdminOff(context)
        if (at > 0) {
            current?.send(
                JSONObject().put("t", "adminOff").put("ago", System.currentTimeMillis() - at)
            )
        }
    }
}
