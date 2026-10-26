package live.stayo.sdk

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Positive protocol flows plus identity transitions that fixtures can verify locally. */
class FeatureFlowsTest {
    private lateinit var server: MockWebServer
    private lateinit var client: StayoClient

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = StayoClient(server.url("/").toString(), "public-fixture-key", StayoOptions(
            httpClient = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
        ))
    }
    @After fun tearDown() { client.close(); server.shutdown() }

    private fun response(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun session(access: String) = response("""
        {"access_token":"$access","refresh_token":"fixture-refresh-$access","expires_at":4102444800,"user":{"id":"fixture-user"}}
    """.trimIndent())
    private fun takeRequest(): RecordedRequest = server.takeRequest(2, TimeUnit.SECONDS)
        ?: throw AssertionError("Expected fixture request")
    private fun body(request: RecordedRequest) = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
    private suspend fun signIn() {
        server.enqueue(session("fixture-user-a"))
        client.auth.signInWithPassword("a@example.invalid", "fixture-password")
        assertEquals("/auth/v1/token?grant_type=password", takeRequest().path)
    }

    @Test fun pendingNewLoginClearsPreviousIdentityAndCannotRefreshIt() = runBlocking {
        signIn()
        server.enqueue(session("fixture-user-b").setBodyDelay(500, TimeUnit.MILLISECONDS))
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            client.auth.signInWithPassword("b@example.invalid", "fixture-password")
        }
        withContext(Dispatchers.IO) {
            val request = takeRequest()
            assertEquals("/auth/v1/token?grant_type=password", request.path)
            assertEquals("b@example.invalid", body(request).getValue("email").jsonPrimitive.content)
        }
        assertNull(client.auth.session.value)
        assertNull(client.auth.refreshSession())
        assertEquals(2, server.requestCount)
        assertEquals("fixture-user-b", withTimeout(3_000) { pending.await() }.accessToken)
        assertEquals("fixture-user-b", client.auth.session.value?.accessToken)
    }

    @Test fun emailAndRecoveryCodesInstallScopedSessionWithoutRetries() = runBlocking {
        for (type in listOf("email", "recovery")) {
            server.enqueue(session("fixture-$type"))
            assertEquals("fixture-$type", client.auth.verifyOtp("member@example.invalid", "123456", type)?.accessToken)
            val request = takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/auth/v1/verify", request.path)
            assertEquals(buildJsonObject { put("email", "member@example.invalid"); put("token", "123456"); put("type", type) }, body(request))
        }
        server.enqueue(response("{\"code\":\"otp_expired\"}").setResponseCode(403))
        try {
            client.auth.verifyOtp("member@example.invalid", "123456", "recovery")
            fail("Rejected code installed a session")
        } catch (error: StayoException) { assertEquals(403, error.status) }
        assertEquals("/auth/v1/verify", takeRequest().path)
        assertNull(client.auth.session.value)
        assertEquals(3, server.requestCount)
    }

    @Test fun nestedFunctionErrorsKeepCodesWithoutMessagesOrRetries() = runBlocking {
        for ((code, expected) in listOf("FUNCTION_CONCURRENCY_LIMIT" to "FUNCTION_CONCURRENCY_LIMIT", "private provider value" to "http_error")) {
            server.enqueue(session("fixture-user"))
            client.auth.signInWithPassword("member@example.invalid", "fixture-password")
            takeRequest()
            server.enqueue(response("""{"protocol_version":1,"runtime_version":"1.3.0-compatible","capabilities":{"functions":true}}"""))
            server.enqueue(response("""{"error":{"code":"$code","message":"fixture-provider-secret"}}""").setResponseCode(429))
            try { client.functions.invoke("echo", buildJsonObject { put("value", 1) }); fail("Expected overload") }
            catch (error: StayoException) {
                assertEquals(429, error.status); assertEquals(expected, error.code)
                assertFalse(error.toString().contains("fixture-provider-secret"))
            }
            assertEquals("/.well-known/stayo.json", takeRequest().path)
            assertEquals("/functions/v1/echo", takeRequest().path)
        }
        assertEquals(6, server.requestCount)
    }

    @Test fun signupConfirmationRecoveryAndUserUpdateUseAuthRoutes() = runBlocking {
        server.enqueue(response("{\"user\":{\"id\":\"fixture-user\"},\"session\":null}"))
        val signup = client.auth.signUp("new@example.invalid", "fixture-password", buildJsonObject { put("name", "Fixture") })
        assertEquals("fixture-user", signup.getValue("user").jsonObject.getValue("id").jsonPrimitive.content)
        assertNull(client.auth.session.value)
        var request = takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/auth/v1/signup", request.path)
        assertEquals("public-fixture-key", request.getHeader("apikey"))
        assertEquals("Fixture", body(request).getValue("data").jsonObject.getValue("name").jsonPrimitive.content)

        server.enqueue(session("fixture-confirmed-user"))
        assertEquals("fixture-confirmed-user", client.auth.verifyOtp("new@example.invalid", "123456")?.accessToken)
        request = takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/auth/v1/verify", request.path)
        assertEquals(buildJsonObject { put("email", "new@example.invalid"); put("token", "123456"); put("type", "signup") }, body(request))

        server.enqueue(response("{}"))
        client.auth.resetPasswordForEmail("new@example.invalid", "fixture://auth/reset")
        request = takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/auth/v1/recover", request.requestUrl!!.encodedPath)
        assertEquals("fixture://auth/reset", request.requestUrl!!.queryParameter("redirect_to"))
        assertEquals("new@example.invalid", body(request).getValue("email").jsonPrimitive.content)

        server.enqueue(response("{\"id\":\"fixture-user\",\"user_metadata\":{\"name\":\"Updated\"}}"))
        client.auth.updateUser("fixture-new-password", buildJsonObject { put("name", "Updated") })
        request = takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/auth/v1/user", request.path)
        assertEquals("Bearer fixture-confirmed-user", request.getHeader("Authorization"))
        assertEquals(buildJsonObject { put("password", "fixture-new-password"); put("data", buildJsonObject { put("name", "Updated") }) }, body(request))

        server.enqueue(response("{\"id\":\"fixture-user\"}"))
        assertEquals("fixture-user", client.auth.getUser().getValue("id").jsonPrimitive.content)
        request = takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/auth/v1/user", request.path)
        assertEquals("Bearer fixture-confirmed-user", request.getHeader("Authorization"))
    }

    @Test fun databaseMutationsAndRpcPreserveFiltersSchemaAndBodies() = runBlocking {
        signIn()
        server.enqueue(response("[{\"id\":7,\"body\":\"Updated\"}]"))
        val values = buildJsonObject { put("body", "Updated") }
        val updated = client.from("notes", "app").update(values).eq("id", 7).execute()
        var request = takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/rest/v1/notes", request.requestUrl!!.encodedPath)
        assertEquals("eq.7", request.requestUrl!!.queryParameter("id"))
        assertEquals("app", request.getHeader("Content-Profile"))
        assertEquals("app", request.getHeader("Accept-Profile"))
        assertEquals("return=representation", request.getHeader("Prefer"))
        assertEquals(values, body(request))
        assertEquals("Updated", updated.data.jsonArray.single().jsonObject.getValue("body").jsonPrimitive.content)

        server.enqueue(response("[{\"id\":7}]"))
        client.from("notes", "app").delete().eq("id", 7).execute()
        request = takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("eq.7", request.requestUrl!!.queryParameter("id"))
        assertEquals("app", request.getHeader("Content-Profile"))
        assertEquals("Bearer fixture-user-a", request.getHeader("Authorization"))

        server.enqueue(response("{\"count\":2}"))
        val parameters = buildJsonObject { put("owner", "fixture-user"); put("minimum", 1) }
        val result = client.rpc("count_notes", parameters, "app")
        request = takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/rest/v1/rpc/count_notes", request.path)
        assertEquals("app", request.getHeader("Content-Profile"))
        assertEquals(parameters, body(request))
        assertEquals(2, result.jsonObject.getValue("count").jsonPrimitive.int)
    }

    @Test fun storageUploadRemoveAndProjectSignedUrlsUseCompatibleWireFormat() = runBlocking {
        signIn()
        val bucket = client.storage.from("photos")
        val bytes = byteArrayOf(0, 1, 2, 3, -1)
        server.enqueue(response("{\"Key\":\"photos/albums/photo.png\"}"))
        bucket.upload("albums/photo.png", bytes, "image/png")
        var request = takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/storage/v1/object/photos/albums/photo.png", request.path)
        assertEquals("image/png", request.getHeader("Content-Type"))
        assertEquals("false", request.getHeader("x-upsert"))
        assertArrayEquals(bytes, request.body.readByteArray())
        assertEquals("Bearer fixture-user-a", request.getHeader("Authorization"))

        server.enqueue(response("[]"))
        bucket.remove(listOf("albums/photo.png", "albums/other.png"))
        request = takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/storage/v1/object/photos", request.path)
        assertEquals(buildJsonObject { put("prefixes", JsonArray(listOf(JsonPrimitive("albums/photo.png"), JsonPrimitive("albums/other.png")))) }, body(request))

        server.enqueue(response("{\"signedURL\":\"/object/sign/photos/albums/photo.png?token=fixture-download-token\"}"))
        val signed = bucket.createSignedUrl("albums/photo.png", 120)
        request = takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/storage/v1/object/sign/photos/albums/photo.png", request.path)
        assertEquals(120, body(request).getValue("expiresIn").jsonPrimitive.int)
        assertEquals(server.url("/storage/v1/object/sign/photos/albums/photo.png?token=fixture-download-token").toString(), signed)
    }
    @Test fun lateUserResponseCannotAppearAsTheNewlySignedInAccount() = runBlocking {
        signIn()
        server.enqueue(response("{\"id\":\"fixture-user-a\"}").setBodyDelay(400, TimeUnit.MILLISECONDS))
        val oldUser = async(start = CoroutineStart.UNDISPATCHED) { runCatching { client.auth.getUser() } }
        withContext(Dispatchers.IO) { assertEquals("/auth/v1/user", takeRequest().path) }
        server.enqueue(session("fixture-user-b"))
        client.auth.signInWithPassword("b@example.invalid", "fixture-password")
        takeRequest()
        val error = withTimeout(3_000) { oldUser.await() }.exceptionOrNull() as? StayoException
        assertEquals("auth_superseded", error?.code)
        assertEquals("fixture-user-b", client.auth.session.value?.accessToken)
    }

    @Test fun cancelOAuthInvalidatesAnAlreadyDispatchedCodeExchange() = runBlocking {
        client.auth.beginOAuth(OAuthProvider.GOOGLE, "fixture://auth/callback")
        server.enqueue(session("fixture-delayed-oauth").setBodyDelay(300, TimeUnit.MILLISECONDS))
        val exchange = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { client.auth.handleOAuthCallback("fixture://auth/callback?code=fixture-code") }
        }
        withContext(Dispatchers.IO) { assertEquals("/auth/v1/token?grant_type=pkce", takeRequest().path) }
        client.auth.cancelOAuth()
        val error = withTimeout(3_000) { exchange.await() }.exceptionOrNull() as? StayoException
        assertEquals("auth_superseded", error?.code)
        assertNull(client.auth.session.value)
    }

}
