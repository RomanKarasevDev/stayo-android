package live.stayo.sdk

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

/** Only INSERT/UPDATE are available: deleted-row authorization cannot be assumed. */
enum class RowChange { INSERT, UPDATE }
enum class RealtimeStatus { CONNECTING, SUBSCRIBED, RESYNC_REQUIRED, CLOSED }
class RealtimeChange(val event: RowChange, val record: JsonObject, val oldRecord: JsonObject?)

class StayoRealtime internal constructor(private val client: StayoClient) : Closeable {
    private val subscriptions = mutableSetOf<RealtimeSubscription>()
    private var closed = false
    @Synchronized fun subscribe(table: String, schema: String = "public", events: Set<RowChange> = RowChange.entries.toSet(), filter: String? = null): RealtimeSubscription {
        check(!closed) { "Realtime client is closed" }
        require(events.isNotEmpty())
        return RealtimeSubscription(client, identifier(table), identifier(schema), events, filter) { subscription ->
            synchronized(this) { subscriptions.remove(subscription) }
        }.also { subscriptions.add(it); it.start() }
    }
    internal fun updateToken(token: String?) {
        val active = synchronized(this) { subscriptions.toList() }
        if (token == null) active.forEach { it.close() } else active.forEach { it.updateToken(token) }
    }
    override fun close() {
        val active = synchronized(this) { closed = true; subscriptions.toList() }
        active.forEach { it.close() }
    }
}

/** Collect changes for records, status for connection state, resyncVersion to refetch after any gap. */
class RealtimeSubscription internal constructor(
    private val client: StayoClient, private val table: String, private val schema: String,
    private val events: Set<RowChange>, private val filter: String?, private val onClose: (RealtimeSubscription) -> Unit,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val references = AtomicLong()
    private val topic = "realtime:$schema:$table:${java.util.UUID.randomUUID()}"
    private val mutableStatus = MutableStateFlow(RealtimeStatus.CONNECTING)
    val status = mutableStatus.asStateFlow()
    private val mutableChanges = MutableSharedFlow<RealtimeChange>(extraBufferCapacity = 64)
    val changes = mutableChanges.asSharedFlow()
    private val mutableResync = MutableStateFlow(0L)
    val resyncVersion = mutableResync.asStateFlow()
    private val monitor = Any()
    private var socket: WebSocket? = null
    private var socketEpoch = 0L
    private var joinRef = ""
    private var heartbeatRef: String? = null
    private var heartbeat: Job? = null
    private var reconnect: Job? = null
    private var failures = 0
    private var stopped = false
    private var needsResync = false
    private var joinAcknowledged = false
    private var postgresReady = false

    internal fun start() { scope.launch { connect() } }
    private suspend fun connect() {
        val token = try { client.auth.accessToken() ?: client.publicKey } catch (_: Exception) { scheduleReconnect(); return }
        val epoch = synchronized(monitor) {
            if (stopped) return
            socketEpoch++
            heartbeatRef = null
            joinAcknowledged = false
            postgresReady = false
            joinRef = references.incrementAndGet().toString()
            socketEpoch
        }
        val url = client.url(listOf("realtime", "v1", "websocket"), listOf("apikey" to client.publicKey, "vsn" to "1.0.0"))
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(monitor) {
                    if (stopped || epoch != socketEpoch) { webSocket.cancel(); return }
                    socket = webSocket
                    send("phx_join", buildJsonObject {
                        put("access_token", token)
                        put("config", buildJsonObject {
                            put("broadcast", buildJsonObject { put("self", false) })
                            put("presence", buildJsonObject { put("key", "") })
                            put("postgres_changes", JsonArray(events.map { event -> buildJsonObject {
                                put("event", event.name); put("schema", schema); put("table", table); filter?.let { put("filter", it) }
                            } }))
                        })
                    }, ref = joinRef)
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                synchronized(monitor) {
                    if (stopped || epoch != socketEpoch) return
                    val message = runCatching { client.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                    val event = message["event"]?.jsonPrimitive?.content
                    val ref = message["ref"]?.jsonPrimitive?.contentOrNull
                    val payload = message["payload"] as? JsonObject ?: return
                    if (event == "phx_reply" && ref == heartbeatRef) heartbeatRef = null
                    if (event == "phx_reply" && ref == joinRef) {
                        if (payload["status"]?.jsonPrimitive?.content != "ok") { disconnect(epoch); return }
                        if (message["topic"]?.jsonPrimitive?.content != topic) return
                        joinAcknowledged = true
                        becomeReady(epoch, token)
                    } else if (event == "system") {
                        if (message["topic"]?.jsonPrimitive?.content != topic || payload["extension"]?.jsonPrimitive?.content != "postgres_changes") return
                        if (payload["status"]?.jsonPrimitive?.content != "ok") { disconnect(epoch); return }
                        postgresReady = true
                        becomeReady(epoch, token)
                    } else if (event == "postgres_changes") {
                        if (message["topic"]?.jsonPrimitive?.content != topic) return
                        val data = payload["data"] as? JsonObject ?: return
                        if (data["schema"]?.jsonPrimitive?.content != schema || data["table"]?.jsonPrimitive?.content != table) return
                        val change = runCatching { RowChange.valueOf(data["type"]?.jsonPrimitive?.content ?: "") }.getOrNull() ?: return
                        if (change !in events) return
                        val record = data["record"] as? JsonObject ?: return
                        if (!mutableChanges.tryEmit(RealtimeChange(change, record, data["old_record"] as? JsonObject))) markResync()
                    } else if (event == "phx_error" || event == "phx_close") disconnect(epoch)
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { disconnect(epoch) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason); disconnect(epoch) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { disconnect(epoch) }
        }
        synchronized(monitor) {
            if (!stopped && epoch == socketEpoch) socket = client.http.newWebSocket(Request.Builder().url(url).build(), listener)
        }
        // Join acknowledgement alone does not mean the Postgres subscription is registered.
        // Bound both missing acknowledgement and missing system-ready to the same deadline.
        scope.launch { delay(15_000); synchronized(monitor) { if (!stopped && epoch == socketEpoch && status.value != RealtimeStatus.SUBSCRIBED) disconnect(epoch) } }
    }
    private fun becomeReady(epoch: Long, token: String) {
        if (!joinAcknowledged || !postgresReady || status.value == RealtimeStatus.SUBSCRIBED) return
        failures = 0
        if (needsResync) { markResync(); needsResync = false }
        mutableStatus.value = RealtimeStatus.SUBSCRIBED
        // Refresh may have completed while the join was in flight.
        client.auth.session.value?.accessToken?.takeIf { it != token }?.let { updateToken(it) }
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (isActive) {
                delay(25_000)
                try { client.auth.accessToken() } catch (_: CancellationException) { throw CancellationException() } catch (_: Exception) { disconnect(epoch); break }
                synchronized(monitor) {
                    if (stopped || epoch != socketEpoch) return@launch
                    if (heartbeatRef != null) { disconnect(epoch); return@launch }
                    heartbeatRef = references.incrementAndGet().toString()
                    send("heartbeat", buildJsonObject { }, "phoenix", heartbeatRef!!)
                }
            }
        }
    }
    private fun disconnect(epoch: Long) = synchronized(monitor) {
        if (stopped || epoch != socketEpoch) return
        socketEpoch++
        socket?.cancel(); socket = null
        heartbeat?.cancel(); heartbeat = null
        needsResync = true
        markResync()
        scheduleReconnect()
    }
    private fun scheduleReconnect() = synchronized(monitor) {
        if (stopped || reconnect?.isActive == true) return
        val waitMillis = minOf(30_000L, 1000L shl minOf(failures++, 5))
        reconnect = scope.launch { delay(waitMillis); synchronized(monitor) { reconnect = null }; connect() }
    }
    private fun markResync() { mutableResync.value++; mutableStatus.value = RealtimeStatus.RESYNC_REQUIRED }
    internal fun updateToken(token: String) = synchronized(monitor) { if (!stopped && status.value == RealtimeStatus.SUBSCRIBED) send("access_token", buildJsonObject { put("access_token", token) }) }
    private fun send(event: String, payload: JsonObject, channel: String = topic, ref: String = references.incrementAndGet().toString()) {
        socket?.send(buildJsonObject { put("topic", channel); put("event", event); put("payload", payload); put("ref", ref); put("join_ref", joinRef) }.toString())
    }
    override fun close() {
        synchronized(monitor) {
            if (stopped) return
            stopped = true; socketEpoch++
            send("phx_leave", buildJsonObject { })
            socket?.close(1000, "unsubscribe"); socket = null
            scope.cancel(); mutableStatus.value = RealtimeStatus.CLOSED
        }
        onClose(this)
    }
}
