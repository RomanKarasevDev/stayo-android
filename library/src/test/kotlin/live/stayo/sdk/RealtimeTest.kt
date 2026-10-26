package live.stayo.sdk

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Exercises the wire protocol with a real loopback websocket and synthetic records. */
class RealtimeTest {
    private lateinit var server: MockWebServer
    private lateinit var client: StayoClient
    private val json = Json

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = StayoClient(server.url("/").toString(), "public-fixture-key")
    }
    @After fun tearDown() { client.close(); server.shutdown() }

    private fun sessionResponse(access: String) = MockResponse().setHeader("Content-Type", "application/json").setBody("""
        {"access_token":"$access","refresh_token":"fixture-refresh","expires_at":4102444800,"user":{"id":"fixture-user"}}
    """.trimIndent())

    private suspend fun signIn() {
        server.enqueue(sessionResponse("fixture-user-access"))
        client.auth.signInWithPassword("fixture@example.invalid", "fixture-password")
        server.takeRequest(2, TimeUnit.SECONDS)!!
    }

    private inner class PhoenixPeer(private val autoAcknowledgeJoin: Boolean = true) : WebSocketListener() {
        @Volatile private var pendingJoin: JsonObject? = null
        private val incoming = LinkedBlockingQueue<JsonObject>()
        @Volatile lateinit var socket: WebSocket
        @Volatile var topic = ""
        override fun onOpen(webSocket: WebSocket, response: Response) { socket = webSocket }
        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = json.parseToJsonElement(text).jsonObject
            incoming.offer(message)
            when (message["event"]?.jsonPrimitive?.content) {
                "phx_join" -> {
                    topic = message.getValue("topic").jsonPrimitive.content
                    pendingJoin = message
                    if (autoAcknowledgeJoin) acknowledgeJoin()
                }
                "heartbeat" -> webSocket.send(buildJsonObject {
                    put("topic", "phoenix"); put("event", "phx_reply"); put("ref", message.getValue("ref"))
                    put("payload", buildJsonObject { put("status", "ok") })
                }.toString())
            }
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        suspend fun event(name: String, timeoutMillis: Long = 3_000): JsonObject = withContext(Dispatchers.IO) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (System.nanoTime() < deadline) {
                val next = incoming.poll(50, TimeUnit.MILLISECONDS) ?: continue
                if (next["event"]?.jsonPrimitive?.content == name) return@withContext next
            }
            throw AssertionError("Missing Phoenix event: $name")
        }
        fun acknowledgeJoin(ready: Boolean = true) {
            val message = pendingJoin ?: throw AssertionError("No pending join")
            socket.send(buildJsonObject {
                put("topic", message.getValue("topic")); put("event", "phx_reply")
                put("ref", message.getValue("ref")); put("join_ref", message.getValue("join_ref"))
                put("payload", buildJsonObject { put("status", "ok"); put("response", buildJsonObject { }) })
            }.toString())
            if (ready) systemReady()
        }
        fun systemReady(status: String = "ok") {
            socket.send(buildJsonObject {
                put("topic", topic); put("event", "system")
                put("payload", buildJsonObject { put("extension", "postgres_changes"); put("status", status) })
            }.toString())
        }
        fun change(type: String, value: Int) {
            socket.send(buildJsonObject {
                put("topic", topic); put("event", "postgres_changes")
                put("payload", buildJsonObject { put("data", buildJsonObject {
                    put("schema", "public"); put("table", "notes"); put("type", type); put("record", buildJsonObject { put("id", value) })
                    put("old_record", buildJsonObject { })
                }) })
            }.toString())
        }
    }

    private fun peer(autoAcknowledgeJoin: Boolean = true): PhoenixPeer = PhoenixPeer(autoAcknowledgeJoin).also {
        server.enqueue(MockResponse().withWebSocketUpgrade(it))
    }

    @Test fun joinsOnlyInsertAndUpdateAndDeliversRecords() = runBlocking {
        signIn()
        val peer = peer()
        val subscription = client.realtime.subscribe("notes", filter = "owner_id=eq.fixture-user")
        val join = peer.event("phx_join")
        val payload = join.getValue("payload").jsonObject
        assertEquals("fixture-user-access", payload.getValue("access_token").jsonPrimitive.content)
        val changes = payload.getValue("config").jsonObject.getValue("postgres_changes").jsonArray
        assertEquals(setOf("INSERT", "UPDATE"), changes.map { it.jsonObject.getValue("event").jsonPrimitive.content }.toSet())
        assertTrue(changes.all { it.jsonObject.getValue("filter").jsonPrimitive.content == "owner_id=eq.fixture-user" })
        withTimeout(3_000) { subscription.status.first { it == RealtimeStatus.SUBSCRIBED } }
        val first = async(start = CoroutineStart.UNDISPATCHED) { subscription.changes.first() }
        peer.change("DELETE", -1)
        peer.change("INSERT", 10)
        val inserted = withTimeout(3_000) { first.await() }
        assertEquals(RowChange.INSERT, inserted.event)
        assertEquals(10, inserted.record.getValue("id").jsonPrimitive.int)
        val second = async(start = CoroutineStart.UNDISPATCHED) { subscription.changes.first() }
        peer.change("UPDATE", 11)
        assertEquals(RowChange.UPDATE, withTimeout(3_000) { second.await() }.event)
        subscription.close()
        assertEquals(RealtimeStatus.CLOSED, subscription.status.value)
        assertEquals("phx_leave", peer.event("phx_leave").getValue("event").jsonPrimitive.content)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/realtime/v1/websocket", request.requestUrl!!.encodedPath)
        assertEquals("public-fixture-key", request.requestUrl!!.queryParameter("apikey"))
        assertEquals("1.0.0", request.requestUrl!!.queryParameter("vsn"))
    }

    @Test fun refreshUpdatesSocketAuthorizationAndLogoutClosesSubscriptions() = runBlocking {
        signIn()
        val peer = peer()
        val subscription = client.realtime.subscribe("notes")
        peer.event("phx_join")
        withTimeout(3_000) { subscription.status.first { it == RealtimeStatus.SUBSCRIBED } }
        server.enqueue(sessionResponse("fixture-rotated-access"))
        client.auth.refreshSession()
        val update = peer.event("access_token")
        assertEquals("fixture-rotated-access", update.getValue("payload").jsonObject.getValue("access_token").jsonPrimitive.content)
        server.enqueue(MockResponse().setResponseCode(204))
        client.auth.signOut()
        assertEquals(RealtimeStatus.CLOSED, subscription.status.value)
        peer.event("phx_leave")
        assertNull(client.auth.session.value)
    }

    @Test fun serverChannelFailureReconnectsAndSignalsSnapshotResync() = runBlocking {
        signIn()
        val first = peer()
        val subscription = client.realtime.subscribe("notes")
        first.event("phx_join")
        withTimeout(3_000) { subscription.status.first { it == RealtimeStatus.SUBSCRIBED } }
        val second = peer()
        first.socket.send(buildJsonObject {
            put("topic", first.topic); put("event", "phx_error"); put("payload", buildJsonObject { })
        }.toString())
        withTimeout(3_000) { subscription.resyncVersion.first { it > 0 } }
        val rejoin = second.event("phx_join", timeoutMillis = 5_000)
        assertEquals("fixture-user-access", rejoin.getValue("payload").jsonObject.getValue("access_token").jsonPrimitive.content)
        withTimeout(3_000) { subscription.status.first { it == RealtimeStatus.SUBSCRIBED } }
        assertTrue("A snapshot must be refetched after the gap closes", subscription.resyncVersion.value >= 2)
        subscription.close()
    }

    @Test fun refreshWhileJoinIsPendingUpdatesTokenAfterSubscriptionIsReady() = runBlocking {
        signIn()
        val peer = peer(autoAcknowledgeJoin = false)
        val subscription = client.realtime.subscribe("notes")
        val join = peer.event("phx_join")
        assertEquals("fixture-user-access", join.getValue("payload").jsonObject.getValue("access_token").jsonPrimitive.content)
        server.enqueue(sessionResponse("fixture-rotated-access"))
        client.auth.refreshSession()
        peer.acknowledgeJoin()
        withTimeout(3_000) { subscription.status.first { it == RealtimeStatus.SUBSCRIBED } }
        val token = peer.event("access_token")
        assertEquals("fixture-rotated-access", token.getValue("payload").jsonObject.getValue("access_token").jsonPrimitive.content)
        subscription.close()
    }

    @Test fun gracefulServerCloseReconnectsWithoutWaitingForSocketTimeout() = runBlocking {
        signIn()
        val first = peer()
        val subscription = client.realtime.subscribe("notes")
        first.event("phx_join")
        withTimeout(3_000) { subscription.status.first { it == RealtimeStatus.SUBSCRIBED } }
        val second = peer()
        first.socket.close(1012, "fixture restart")
        withTimeout(3_000) { subscription.resyncVersion.first { it > 0 } }
        second.event("phx_join", timeoutMillis = 5_000)
        withTimeout(3_000) { subscription.status.first { it == RealtimeStatus.SUBSCRIBED } }
        subscription.close()
    }

    @Test fun phoenixHeartbeatIsAcknowledgedWithoutDroppingSubscription() = runBlocking {
        signIn()
        val peer = peer()
        val subscription = client.realtime.subscribe("notes")
        peer.event("phx_join")
        withTimeout(3_000) { subscription.status.first { it == RealtimeStatus.SUBSCRIBED } }
        val heartbeat = peer.event("heartbeat", timeoutMillis = 28_000)
        assertEquals("phoenix", heartbeat.getValue("topic").jsonPrimitive.content)
        assertEquals(RealtimeStatus.SUBSCRIBED, subscription.status.value)
        assertEquals(0L, subscription.resyncVersion.value)
        subscription.close()
    }
    @Test fun joinReplyAloneDoesNotAdvertiseARegisteredPostgresSubscription() = runBlocking {
        signIn()
        val peer = peer(autoAcknowledgeJoin = false)
        val subscription = client.realtime.subscribe("notes")
        peer.event("phx_join")
        peer.acknowledgeJoin(ready = false)
        delay(100)
        assertEquals(RealtimeStatus.CONNECTING, subscription.status.value)
        peer.systemReady()
        withTimeout(3_000) { subscription.status.first { it == RealtimeStatus.SUBSCRIBED } }
        subscription.close()
    }

    @Test fun systemReadyBeforeJoinReplyStillWaitsForTheAcknowledgement() = runBlocking {
        signIn()
        val peer = peer(autoAcknowledgeJoin = false)
        val subscription = client.realtime.subscribe("notes")
        peer.event("phx_join")
        peer.systemReady()
        delay(100)
        assertEquals(RealtimeStatus.CONNECTING, subscription.status.value)
        peer.acknowledgeJoin(ready = false)
        withTimeout(3_000) { subscription.status.first { it == RealtimeStatus.SUBSCRIBED } }
        subscription.close()
    }

    @Test fun postgresSystemErrorRequiresACompleteRejoin() = runBlocking {
        signIn()
        val peer = peer(autoAcknowledgeJoin = false)
        val subscription = client.realtime.subscribe("notes")
        peer.event("phx_join")
        peer.acknowledgeJoin(ready = false)
        val reconnected = peer()
        peer.systemReady("error")
        withTimeout(3_000) { subscription.resyncVersion.first { it > 0 } }
        reconnected.event("phx_join", 5_000)
        withTimeout(3_000) { subscription.status.first { it == RealtimeStatus.SUBSCRIBED } }
        assertTrue(subscription.resyncVersion.value >= 2)
        subscription.close()
    }

    @Test fun missingPostgresReadyTimesOutInsteadOfAdvertisingSubscribed() = runBlocking {
        signIn()
        val peer = peer(autoAcknowledgeJoin = false)
        val subscription = client.realtime.subscribe("notes")
        peer.event("phx_join")
        peer.acknowledgeJoin(ready = false)
        withTimeout(17_000) { subscription.resyncVersion.first { it > 0 } }
        assertEquals(RealtimeStatus.RESYNC_REQUIRED, subscription.status.value)
        subscription.close()
    }

}
