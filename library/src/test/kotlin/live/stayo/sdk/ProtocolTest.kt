package live.stayo.sdk

import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Independent transport checks against a local fixture; no hosted credentials. */
class ProtocolTest {
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }

    @Before fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After fun stopServer() { server.shutdown() }

    private fun client() = StayoClient(server.url("/").toString(), "public-fixture-key", StayoOptions(
        httpClient = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
    ))

    private fun sessionResponse(access: String = "fixture-user-access", refresh: String = "fixture-user-refresh") =
        MockResponse().setHeader("Content-Type", "application/json").setBody("""
            {"access_token":"$access","refresh_token":"$refresh","expires_in":3600,
             "expires_at":4102444800,"token_type":"bearer","user":{"id":"fixture-user"}}
        """.trimIndent())

    private suspend fun signIn(client: StayoClient) {
        server.enqueue(sessionResponse())
        client.auth.signInWithPassword("fixture@example.invalid", "fixture-password")
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/auth/v1/token?grant_type=password", request.path)
        assertEquals("public-fixture-key", request.getHeader("apikey"))
        assertEquals("fixture@example.invalid", (json.parseToJsonElement(request.body.readUtf8()) as JsonObject)
            ["email"]?.jsonPrimitive?.contentOrNull)
    }

    @Test fun publicClientsRejectKnownSecretKeysBeforeAnyNetworkRequest() {
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"role\":\"service_role\"}".toByteArray())
        for (key in listOf("sb_secret_fixture", "eyJhbGciOiJIUzI1NiJ9.$payload.fixture")) {
            try {
                StayoClient(server.url("/").toString(), key, StayoOptions())
                fail("Known privileged key was accepted")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun remoteHttpAndUrlCredentialsAreRejected() {
        for (url in listOf("http://project.example.invalid", "https://user:password@project.example.invalid")) {
            try {
                StayoClient(url, "public-fixture-key", StayoOptions())
                fail("Unsafe project origin was accepted")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun tableReadsCarryTheUserSessionAndPreserveContentRange() = runBlocking {
        val client = client()
        signIn(client)
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setHeader("Content-Range", "0-1/2").setBody("[{\"id\":1},{\"id\":2}]"))
        val response = client.from("notes").select().eq("owner_id", "fixture-user").order("id").range(0, 1).execute()
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals("Bearer fixture-user-access", request.getHeader("Authorization"))
        assertEquals("public-fixture-key", request.getHeader("apikey"))
        assertEquals("0-1", request.getHeader("Range"))
        assertEquals("eq.fixture-user", request.requestUrl!!.queryParameter("owner_id"))
        assertEquals("0-1/2", response.contentRange)
        assertEquals(json.parseToJsonElement("[{\"id\":1},{\"id\":2}]"), response.data)
    }

    @Test fun objectTraversalAndBackslashesAreRejectedBeforeDispatch() = runBlocking {
        val client = client()
        for (path in listOf("../private.txt", "a/../private.txt", "a//file", "a\\file", "/absolute", "a/./file")) {
            try {
                client.storage.from("avatars").download(path)
                fail("Unsafe object path was accepted: $path")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun storageEncodesIndividualObjectSegments() = runBlocking {
        val client = client()
        server.enqueue(MockResponse().setBody("fixture-image"))
        val result = client.storage.from("avatars").download("a folder/фото.png")
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/storage/v1/object/avatars/a%20folder/%D1%84%D0%BE%D1%82%D0%BE.png", request.path)
        assertArrayEquals("fixture-image".toByteArray(), result)
    }

    @Test fun signedDownloadCannotEscapeTheProjectOrigin() = runBlocking {
        val client = client()
        for (signed in listOf("https://attacker.example.invalid/steal", "//attacker.example.invalid/steal")) {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"signedURL\":\"$signed\"}"))
            try {
                client.storage.from("avatars").createSignedUrl("photo.png", 60)
                fail("Signed URL escaped the configured project origin")
            } catch (_: Exception) { }
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun mutationFailureIsDispatchedExactlyOnce() = runBlocking {
        val client = client()
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setHeader("Content-Type", "application/json")
            .setBody("{\"code\":\"temporarily_unavailable\",\"message\":\"fixture unavailable\"}"))
        try {
            client.from("notes").insert(json.parseToJsonElement("{\"body\":\"fixture\"}")).execute()
            fail("Mutation should propagate the service failure")
        } catch (_: Exception) { }
        delay(150)
        assertEquals(1, server.requestCount)
        assertEquals("POST", server.takeRequest(2, TimeUnit.SECONDS)!!.method)
    }

    @Test fun simultaneousRefreshesShareOneRequest() = runBlocking {
        val client = client()
        signIn(client)
        server.enqueue(sessionResponse("fixture-rotated-access", "fixture-rotated-refresh")
            .setBodyDelay(200, TimeUnit.MILLISECONDS))
        val results = (1..8).map { async { client.auth.refreshSession() } }.awaitAll()
        assertTrue(results.all { it != null })
        assertEquals(2, server.requestCount)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/auth/v1/token?grant_type=refresh_token", request.path)
        assertEquals("fixture-rotated-access", client.auth.session.value?.accessToken)
    }

    @Test fun sessionPersistenceIsPartitionedByProjectAndNamespace() = runBlocking {
        val store = MemorySessionStore()
        val original = StayoClient(server.url("/").toString(), "public-fixture-key", StayoOptions(sessionStore = store))
        signIn(original)
        val restored = StayoClient(server.url("/").toString().removeSuffix("/"), "public-fixture-key", StayoOptions(sessionStore = store))
        val namespace = StayoClient(server.url("/").toString(), "public-fixture-key", StayoOptions(sessionStore = store, namespace = "other-account"))
        val project = StayoClient("https://other-project.example.invalid", "public-fixture-key", StayoOptions(sessionStore = store))
        assertEquals("fixture-user-access", restored.auth.session.value?.accessToken)
        assertNull(namespace.auth.session.value)
        assertNull(project.auth.session.value)
        assertFalse(original.auth.session.value.toString().contains("fixture-user-access"))
        assertFalse(original.auth.session.value.toString().contains("fixture-user-refresh"))
    }

    @Test fun lateTableResponseAfterLogoutIsRejectedWithoutRetry() = runBlocking {
        val client = client()
        signIn(client)
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("[{\"id\":1}]").setBodyDelay(500, TimeUnit.MILLISECONDS))
        val read = async { runCatching { client.from("notes").select().execute() } }
        delay(50) // Let the read dispatch before this thread blocks in takeRequest.
        val dispatched = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("Bearer fixture-user-access", dispatched.getHeader("Authorization"))
        server.enqueue(MockResponse().setResponseCode(204))
        client.auth.signOut()
        val failure = withTimeout(3_000) { read.await() }.exceptionOrNull()
        assertEquals("auth_superseded", (failure as? StayoException)?.code)
        assertEquals(3, server.requestCount) // sign-in, one read, logout: the read is never retried
    }

    @Test fun logoutInvalidatesAnInFlightRefresh() = runBlocking {
        val client = client()
        signIn(client)
        server.enqueue(sessionResponse("fixture-late-access", "fixture-late-refresh").setBodyDelay(500, TimeUnit.MILLISECONDS))
        val refresh = async { runCatching { client.auth.refreshSession() } }
        delay(50)
        val refreshRequest = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/auth/v1/token?grant_type=refresh_token", refreshRequest.path)
        server.enqueue(MockResponse().setResponseCode(204))
        client.auth.signOut()
        withTimeout(3_000) { refresh.await() }
        assertNull(client.auth.session.value)
    }

    @Test fun oldHealthDispatcherDoesNotQualifyAsFunctionsCapability() = runBlocking {
        val client = client()
        signIn(client)
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("{\"status\":\"ok\",\"service\":\"edge-runtime\"}"))
        try {
            client.functions.invoke("echo", json.parseToJsonElement("{\"fixture\":true}"))
            fail("An old health-only runtime was accepted for function invocation")
        } catch (_: Exception) { }
        assertEquals(2, server.requestCount)
        assertEquals("/.well-known/stayo.json", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
    }

    @Test fun functionInvocationDoesNotRetryAfterServiceError() = runBlocking {
        val client = client()
        signIn(client)
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("{\"protocol_version\":1,\"runtime_version\":\"1.3.0-compatible\",\"capabilities\":{\"functions\":true}}"))
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Content-Type", "application/json")
            .setBody("{\"code\":\"function_overloaded\",\"message\":\"fixture-user-access\"}"))
        try {
            client.functions.invoke("echo", json.parseToJsonElement("{\"fixture\":true}"))
            fail("Function overload must propagate to the caller")
        } catch (error: StayoException) {
            assertEquals(429, error.status)
            assertEquals("function_overloaded", error.code)
            assertFalse(error.toString().contains("fixture-user-access"))
        }
        delay(150)
        assertEquals(3, server.requestCount)
        assertEquals("/.well-known/stayo.json", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/functions/v1/echo", request.path)
        assertEquals("Bearer fixture-user-access", request.getHeader("Authorization"))
    }

    @Test fun cancellationAbortsTheFunctionRequest() = runBlocking {
        val canceled = AtomicBoolean(false)
        val transport = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callFailed(call: Call, ioe: IOException) {
                if (call.isCanceled()) canceled.set(true)
            }
        }).build()
        val client = StayoClient(server.url("/").toString(), "public-fixture-key", StayoOptions(httpClient = transport))
        signIn(client)
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("{\"protocol_version\":1,\"runtime_version\":\"1.3.0-compatible\",\"capabilities\":{\"functions\":true}}"))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val requestJob = async { client.functions.invoke("echo", json.parseToJsonElement("{\"fixture\":true}")) }
        withContext(Dispatchers.IO) {
            assertEquals("/.well-known/stayo.json", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
            assertEquals("/functions/v1/echo", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
        }
        requestJob.cancelAndJoin()
        withTimeout(2_000) { while (!canceled.get()) delay(20) }
        assertEquals(3, server.requestCount)
    }

    @Test fun redirectsCannotSendCredentialsToAnotherOrigin() = runBlocking {
        val client = client()
        signIn(client)
        val other = MockWebServer()
        other.start()
        try {
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/steal")))
            try {
                client.from("notes").insert(json.parseToJsonElement("{\"body\":\"fixture\"}")).execute()
                fail("The redirect must be returned as a failure")
            } catch (error: StayoException) { assertEquals(307, error.status) }
            assertNull(other.takeRequest(200, TimeUnit.MILLISECONDS))
        } finally { other.shutdown() }
    }

    @Test fun oauthExchangesMatchingCallbackOnceUsingThePkceVerifier() = runBlocking {
        val client = client()
        val authorize = client.auth.beginOAuth(OAuthProvider.GOOGLE, "fixture://auth/callback?source=login").toHttpUrl()
        val redirect = authorize.queryParameter("redirect_to")!!
        assertEquals("/auth/v1/authorize", authorize.encodedPath)
        assertEquals("google", authorize.queryParameter("provider"))
        assertEquals("s256", authorize.queryParameter("code_challenge_method")!!.lowercase())
        server.enqueue(sessionResponse())
        client.auth.handleOAuthCallback("$redirect&code=fixture-code")
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/auth/v1/token?grant_type=pkce", request.path)
        val body = json.parseToJsonElement(request.body.readUtf8()) as JsonObject
        assertEquals("fixture-code", body["auth_code"]?.jsonPrimitive?.contentOrNull)
        val verifier = body["code_verifier"]!!.jsonPrimitive.content
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        assertEquals(authorize.queryParameter("code_challenge"), challenge)
        try {
            client.auth.handleOAuthCallback("$redirect&code=fixture-code")
            fail("A callback verifier was reused")
        } catch (error: StayoException) { assertEquals("oauth_no_pending_request", error.code) }
        assertEquals(1, server.requestCount)
    }

    @Test fun oauthRejectsMismatchedOrCanceledCallbacksBeforeDispatch() = runBlocking {
        val client = client()
        client.auth.beginOAuth(OAuthProvider.APPLE, "fixture://auth/callback")
        try {
            client.auth.handleOAuthCallback("fixture://attacker/callback?code=fixture-code")
            fail("Mismatched callback was accepted")
        } catch (error: StayoException) { assertEquals("oauth_invalid_callback", error.code) }
        client.auth.beginOAuth(OAuthProvider.APPLE, "fixture://auth/callback")
        client.auth.cancelOAuth()
        try {
            client.auth.handleOAuthCallback("fixture://auth/callback?code=fixture-code")
            fail("Canceled callback was accepted")
        } catch (error: StayoException) { assertEquals("oauth_no_pending_request", error.code) }
        assertNull(client.auth.session.value)
        assertEquals(0, server.requestCount)
    }

    @Test fun oauthRejectsDuplicateCodeAndExpiredCallbacks() = runBlocking {
        var now = 2_000L
        val client = StayoClient(server.url("/").toString(), "public-fixture-key", StayoOptions(clock = { now }))
        val authorize = client.auth.beginOAuth(OAuthProvider.GOOGLE, "fixture://auth/callback").toHttpUrl()
        val redirect = authorize.queryParameter("redirect_to")!!
        val separator = if ('?' in redirect) "&" else "?"
        try {
            client.auth.handleOAuthCallback("$redirect${separator}code=one&code=two")
            fail("Duplicate callback codes were accepted")
        } catch (error: StayoException) { assertEquals("oauth_invalid_callback", error.code) }
        val second = client.auth.beginOAuth(OAuthProvider.GOOGLE, "fixture://auth/callback").toHttpUrl().queryParameter("redirect_to")!!
        now += 601
        try {
            client.auth.handleOAuthCallback("$second${if ('?' in second) "&" else "?"}code=fixture-code")
            fail("Expired callback was accepted")
        } catch (error: StayoException) { assertEquals("oauth_invalid_callback", error.code) }
        assertEquals(0, server.requestCount)
    }

    @Test fun queuedRefreshCannotAcquireCredentialsFromANewerLogin() = runBlocking {
        val client = client()
        signIn(client)
        server.enqueue(sessionResponse("fixture-obsolete-access", "fixture-obsolete-refresh").setBodyDelay(600, TimeUnit.MILLISECONDS))
        val first = async(start = CoroutineStart.UNDISPATCHED) { runCatching { client.auth.refreshSession() } }
        withContext(Dispatchers.IO) {
            assertEquals("/auth/v1/token?grant_type=refresh_token", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
        }
        val queued = async(start = CoroutineStart.UNDISPATCHED) { runCatching { client.auth.refreshSession() } }
        server.enqueue(sessionResponse("fixture-other-account-access", "fixture-other-account-refresh"))
        client.auth.signInWithPassword("other@example.invalid", "fixture-password")
        assertEquals("/auth/v1/token?grant_type=password", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
        val firstResult = withTimeout(3_000) { first.await() }
        val queuedResult = withTimeout(3_000) { queued.await() }
        assertEquals("auth_superseded", (firstResult.exceptionOrNull() as? StayoException)?.code)
        assertEquals("auth_superseded", (queuedResult.exceptionOrNull() as? StayoException)?.code)
        assertEquals("fixture-other-account-access", client.auth.session.value?.accessToken)
    }

    @Test fun pendingOAuthCanResumeAfterClientRestartUsingPersistentStore() = runBlocking {
        val store = MemorySessionStore()
        val original = StayoClient(server.url("/").toString(), "public-fixture-key", StayoOptions(sessionStore = store))
        val authorize = original.auth.beginOAuth(OAuthProvider.APPLE, "fixture://auth/callback").toHttpUrl()
        val redirect = authorize.queryParameter("redirect_to")!!
        assertEquals("fixture://auth/callback", redirect)
        original.close()
        val restored = StayoClient(server.url("/").toString(), "public-fixture-key", StayoOptions(sessionStore = store))
        server.enqueue(sessionResponse())
        val result = restored.auth.handleOAuthCallback("$redirect?code=fixture-code")
        assertEquals("fixture-user-access", result.accessToken)
        assertEquals(1, server.requestCount)
        restored.close()
    }

    @Test fun logoutClearsSessionEvenIfServiceFails() = runBlocking {
        val client = client()
        signIn(client)
        server.enqueue(MockResponse().setResponseCode(503).setBody("fixture failure"))
        try { client.auth.signOut() } catch (_: Exception) { }
        assertNull(client.auth.session.value)
    }
}
