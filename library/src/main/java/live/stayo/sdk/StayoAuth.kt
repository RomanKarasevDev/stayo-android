package live.stayo.sdk

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class Session(val accessToken: String, val refreshToken: String, val expiresAt: Long, val user: JsonObject?) {
    override fun toString() = "Session(expiresAt=$expiresAt, tokens=[redacted])"
    internal fun json() = buildJsonObject {
        put("access_token", accessToken); put("refresh_token", refreshToken); put("expires_at", expiresAt)
        user?.let { put("user", it) }
    }
}

enum class OAuthProvider(val wireValue: String) { GOOGLE("google"), APPLE("apple") }

/** GoTrue handles OAuth provider state; the SDK binds callback code to its one-use PKCE verifier. */
class StayoAuth internal constructor(private val client: StayoClient) {
    private val lock = Any()
    private val refreshMutex = Mutex()
    private var generation = 0L
    private var closed = false
    private val sessionKey = client.storageKey + ".session"
    private val pendingKey = client.storageKey + ".pkce"
    private val mutableSession = MutableStateFlow(client.options.sessionStore.read(sessionKey)?.let {
        runCatching { parseSession(Json.parseToJsonElement(it).jsonObject) }.getOrElse { throw StayoException(0, "invalid_stored_session") }
    })
    val session: StateFlow<Session?> = mutableSession.asStateFlow()
    internal var onSessionChange: (Session?) -> Unit = { }
    init {
        // A pending browser flow may outlive the app process. Successful intervening auth removes it.
        client.options.sessionStore.read(pendingKey)?.let { pending ->
            generation = runCatching { Json.parseToJsonElement(pending).jsonObject["generation"]?.jsonPrimitive?.long }.getOrNull() ?: 0
        }
    }

    suspend fun signUp(email: String, password: String, data: JsonObject = buildJsonObject { }): JsonObject {
        val current = begin()
        val response = client.request("POST", listOf("auth", "v1", "signup"), buildJsonObject {
            put("email", email); put("password", password); put("data", data)
        }, authenticated = false).asJson().jsonObject
        if (response["access_token"] != null) install(current, parseSession(response))
        ensureGeneration(current)
        return response
    }
    suspend fun signInWithPassword(email: String, password: String): Session {
        val current = begin()
        val result = client.request("POST", listOf("auth", "v1", "token"), buildJsonObject {
            put("email", email); put("password", password)
        }, query = listOf("grant_type" to "password"), authenticated = false).asJson().jsonObject
        return install(current, parseSession(result))
    }
    suspend fun verifyOtp(email: String, token: String, type: String = "signup"): Session? {
        require(type in setOf("signup", "recovery", "email", "magiclink", "email_change"))
        return verify(buildJsonObject { put("email", email); put("token", token); put("type", type) })
    }
    suspend fun verifyTokenHash(tokenHash: String, type: String): Session? {
        require(type in setOf("signup", "recovery", "email", "magiclink", "email_change"))
        return verify(buildJsonObject { put("token_hash", tokenHash); put("type", type) })
    }
    private suspend fun verify(body: JsonObject): Session? {
        val current = begin()
        val result = client.request("POST", listOf("auth", "v1", "verify"), body, authenticated = false).asJson().jsonObject
        return result["access_token"]?.let { install(current, parseSession(result)) }
    }
    suspend fun resetPasswordForEmail(email: String, redirectTo: String? = null) {
        client.request("POST", listOf("auth", "v1", "recover"), buildJsonObject { put("email", email) },
            query = redirectTo?.let { listOf("redirect_to" to it) } ?: emptyList(), authenticated = false)
    }
    suspend fun getUser(): JsonObject {
        val current = synchronized(lock) { checkOpen(); generation }
        val response = client.request("GET", listOf("auth", "v1", "user")).asJson().jsonObject
        ensureGeneration(current)
        return response
    }
    suspend fun updateUser(password: String? = null, data: JsonObject? = null): JsonObject {
        require(password != null || data != null)
        val current = synchronized(lock) { checkOpen(); generation }
        val response = client.request("PUT", listOf("auth", "v1", "user"), buildJsonObject {
            password?.let { put("password", it) }; data?.let { put("data", it) }
        }).asJson().jsonObject
        ensureGeneration(current)
        return response
    }
    internal suspend fun accessToken(): String? {
        val value = session.value ?: return null
        return if (value.expiresAt <= client.options.clock() + 30) refreshSession()?.accessToken else value.accessToken
    }
    /** All concurrent refresh calls share one exchange, including rotating refresh tokens. */
    suspend fun refreshSession(): Session? {
        val (observed, observedGeneration) = synchronized(lock) { checkOpen(); session.value to generation }
        if (observed == null) return null
        return refreshMutex.withLock {
            val current = synchronized(lock) {
                checkOpen()
                if (generation != observedGeneration) throw StayoException(0, "auth_superseded")
                generation
            }
            val existing = session.value ?: return@withLock null
            if (existing !== observed) return@withLock existing
            try {
                val result = client.request("POST", listOf("auth", "v1", "token"), buildJsonObject {
                    put("refresh_token", existing.refreshToken)
                }, query = listOf("grant_type" to "refresh_token"), authenticated = false).asJson().jsonObject
                install(current, parseSession(result), expectedSession = existing)
            } catch (error: StayoException) {
                if (error.status == 400 || error.status == 401) synchronized(lock) {
                    if (generation == current && session.value === existing) clearLocal()
                }
                throw error
            }
        }
    }
    /** Local logout is immediate. A late refresh/code exchange cannot restore the session. */
    suspend fun signOut() {
        val old = synchronized(lock) { checkOpen(); val token = session.value?.accessToken; clearLocal(); token }
        if (old != null) client.request("POST", listOf("auth", "v1", "logout"), query = listOf("scope" to "local"), authenticated = false, token = old)
    }

    /** Returns an authorization URL for use with the system browser. The redirect must be allowlisted in Auth. */
    fun beginOAuth(provider: OAuthProvider, redirectTo: String): String = synchronized(lock) {
        checkOpen()
        val redirect = URI(redirectTo)
        require(redirect.isAbsolute && redirect.rawFragment == null && redirect.rawUserInfo == null && redirect.host != null) { "Use an absolute callback URL" }
        require(redirect.scheme != "http" || redirect.host in setOf("127.0.0.1", "localhost", "[::1]")) { "Insecure callback" }
        val existingQuery = query(redirect.rawQuery)
        require(existingQuery.keys.none { it in setOf("code", "error", "error_code", "error_description") })
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        clearLocal() // A new identity flow must not share a generation with the previous user's refresh.
        client.options.sessionStore.write(pendingKey, buildJsonObject {
            put("verifier", verifier); put("redirect", redirectTo); put("created", client.options.clock()); put("generation", generation)
        }.toString())
        client.url(listOf("auth", "v1", "authorize"), listOf("provider" to provider.wireValue, "redirect_to" to redirectTo,
            "code_challenge" to challenge, "code_challenge_method" to "s256")).toString()
    }
    fun launchOAuth(context: Context, provider: OAuthProvider, redirectTo: String) {
        val authorizationUrl = beginOAuth(provider, redirectTo)
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authorizationUrl)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) { cancelOAuth(); throw StayoException(0, "browser_unavailable") }
    }
    fun cancelOAuth() = synchronized(lock) {
        checkOpen(); generation++; client.options.sessionStore.write(pendingKey, null)
    }
    suspend fun handleOAuthCallback(callbackUrl: String): Session {
        val pending = synchronized(lock) {
            checkOpen()
            val stored = client.options.sessionStore.read(pendingKey) ?: throw StayoException(0, "oauth_no_pending_request")
            client.options.sessionStore.write(pendingKey, null) // Consume before validation and network dispatch.
            runCatching { Json.parseToJsonElement(stored).jsonObject }.getOrElse { throw StayoException(0, "oauth_invalid_pending_request") }
        }
        val expected = URI(pending.getValue("redirect").jsonPrimitive.content)
        val actual = runCatching { URI(callbackUrl) }.getOrElse { throw StayoException(0, "oauth_invalid_callback") }
        val expectedQuery = query(expected.rawQuery)
        val values = query(actual.rawQuery)
        val created = pending.getValue("created").jsonPrimitive.long
        if (actual.scheme != expected.scheme || actual.rawAuthority != expected.rawAuthority || actual.rawPath != expected.rawPath ||
            actual.rawFragment != null || expectedQuery.any { (key, value) -> values[key] != value } ||
            client.options.clock() - created !in 0..600 || values["error"] != null || values["code"]?.size != 1) {
            throw StayoException(0, "oauth_invalid_callback")
        }
        val current = pending.getValue("generation").jsonPrimitive.long
        synchronized(lock) { if (generation != current) throw StayoException(0, "auth_superseded") }
        val result = client.request("POST", listOf("auth", "v1", "token"), buildJsonObject {
            put("auth_code", values.getValue("code").single()); put("code_verifier", pending.getValue("verifier"))
        }, query = listOf("grant_type" to "pkce"), authenticated = false).asJson().jsonObject
        return install(current, parseSession(result))
    }
    private fun begin(): Long = synchronized(lock) {
        checkOpen(); clearLocal(); generation
    }
    private fun install(expectedGeneration: Long, value: Session, expectedSession: Session? = null): Session = synchronized(lock) {
        checkOpen()
        if (generation != expectedGeneration || (expectedSession != null && session.value !== expectedSession)) throw StayoException(0, "auth_superseded")
        client.options.sessionStore.write(sessionKey, value.json().toString())
        mutableSession.value = value
        onSessionChange(value)
        value
    }
    private fun clearLocal() {
        generation++
        mutableSession.value = null
        onSessionChange(null)
        // Attempt both removals, then fail closed if persistent storage is unavailable.
        var failure: Exception? = null
        try { client.options.sessionStore.write(sessionKey, null) } catch (error: Exception) { failure = error }
        try { client.options.sessionStore.write(pendingKey, null) } catch (error: Exception) { failure = error }
        failure?.let { throw it }
    }
    private fun ensureGeneration(expected: Long) = synchronized(lock) {
        checkOpen()
        if (generation != expected) throw StayoException(0, "auth_superseded")
    }
    private fun checkOpen() { check(!closed) { "Auth client is closed" } }
    /** Identity lifetime: changes on sign-in, sign-out and account switch, not on refresh. */
    internal fun currentGeneration(): Long = synchronized(lock) { generation }
    internal fun close() = synchronized(lock) { generation++; closed = true }
    private fun parseSession(data: JsonObject): Session {
        val access = data["access_token"]?.jsonPrimitive?.content?.takeIf { token -> token.length in 1..16384 && token.all { it.code in 33..126 } } ?: throw StayoException(0, "invalid_session")
        val refresh = data["refresh_token"]?.jsonPrimitive?.content?.takeIf { token -> token.length in 1..16384 && token.all { it.code in 33..126 } } ?: throw StayoException(0, "invalid_session")
        val expiry = data["expires_at"]?.jsonPrimitive?.longOrNull
            ?: (client.options.clock() + (data["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600))
        return Session(access, refresh, expiry, data["user"] as? JsonObject)
    }
    private fun query(raw: String?): Map<String, List<String>> = raw?.split('&')?.filter { it.isNotEmpty() }?.map {
        val parts = it.split('=', limit = 2)
        URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
    }?.groupBy({ it.first }, { it.second }) ?: emptyMap()
}
