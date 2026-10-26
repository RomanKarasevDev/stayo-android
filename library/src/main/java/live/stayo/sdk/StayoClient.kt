package live.stayo.sdk

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.Closeable
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Error intentionally excludes server bodies, headers and tokens. [code] is a bounded service code. */
class StayoException(val status: Int, val code: String) : Exception("Stayo request failed ($status, $code)")

class StayoOptions(
    val sessionStore: SessionStore = MemorySessionStore(),
    val namespace: String = "default",
    val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    val httpClient: OkHttpClient? = null,
)

/** Independent client for a single project origin. Close when its owning app scope ends. */
class StayoClient(url: String, val publicKey: String, internal val options: StayoOptions = StayoOptions()) : Closeable {
    internal val origin: HttpUrl = validateOrigin(url)
    internal val storageKey = "stayo." + sha256("${origin}|${options.namespace}")
    internal val json = Json { ignoreUnknownKeys = true }
    internal val http = (options.httpClient ?: OkHttpClient()).newBuilder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .dispatcher(Dispatcher()).connectionPool(ConnectionPool())
        .apply { if (options.httpClient == null) callTimeout(java.time.Duration.ofSeconds(65)) }.build()
    val auth: StayoAuth
    val storage = StayoStorage(this)
    val functions = StayoFunctions(this)
    val realtime: StayoRealtime
    @Volatile private var closed = false

    init {
        require(options.namespace.isNotBlank()) { "A session namespace is required" }
        requirePublicKey(publicKey)
        auth = StayoAuth(this)
        realtime = StayoRealtime(this)
        auth.onSessionChange = { session -> realtime.updateToken(session?.accessToken) }
    }

    constructor(url: String, publicKey: String, context: Context, namespace: String = "default") : this(
        url, publicKey, StayoOptions(KeystoreSessionStore(context.applicationContext), namespace),
    )

    fun from(table: String, schema: String = "public"): Table = Table(this, identifier(table), identifier(schema))

    suspend fun rpc(name: String, params: JsonObject = buildJsonObject { }, schema: String = "public"): JsonElement =
        request("POST", listOf("rest", "v1", "rpc", identifier(name)), params,
            headers = mapOf("Content-Profile" to identifier(schema))).asJson()

    /** The server advertises protocol/capabilities at this public discovery endpoint. */
    suspend fun capabilities(): JsonObject = request("GET", listOf(".well-known", "stayo.json"), authenticated = false).asJson().jsonObject

    internal fun url(parts: List<String>, query: List<Pair<String, String>> = emptyList()): HttpUrl = origin.newBuilder().apply {
        parts.forEach { addPathSegment(it) }
        query.forEach { (k, v) -> addQueryParameter(k, v) }
    }.build()

    internal suspend fun request(
        method: String, parts: List<String>, body: JsonElement? = null,
        query: List<Pair<String, String>> = emptyList(), headers: Map<String, String> = emptyMap(),
        authenticated: Boolean = true, token: String? = null, bytes: ByteArray? = null, mime: String = "application/json",
    ): HttpResult {
        check(!closed) { "StayoClient is closed" }
        // A request that uses the session token is bound to the identity it
        // started with: a response that arrives after sign-out or an account
        // switch is rejected, never delivered to the next user. A request the
        // server already received is not undone and is never retried.
        val bound = token == null && authenticated
        val started = if (bound) auth.currentGeneration() else 0L
        fun ensureSameIdentity() {
            if (bound && auth.currentGeneration() != started) throw StayoException(0, "auth_superseded")
        }
        val bearer = token ?: if (authenticated) auth.accessToken() else null
        ensureSameIdentity()
        val request = Request.Builder().url(url(parts, query)).header("apikey", publicKey)
            .header("Authorization", "Bearer ${bearer ?: publicKey}")
        headers.forEach { (key, value) -> request.header(key, value) }
        val requestBody = bytes?.toRequestBody(mime.toMediaType()) ?: body?.toString()?.toRequestBody("application/json".toMediaType())
            ?: if (method in setOf("POST", "PUT", "PATCH", "DELETE")) ByteArray(0).toRequestBody(null) else null
        // Mark side effects one-shot: OkHttp also follows 503/421 independent of connection retry configuration.
        val dispatchBody = if (requestBody != null && method in setOf("POST", "PUT", "PATCH", "DELETE")) object : RequestBody() {
            override fun contentType() = requestBody.contentType()
            override fun contentLength() = requestBody.contentLength()
            override fun writeTo(sink: okio.BufferedSink) = requestBody.writeTo(sink)
            override fun isOneShot() = true
        } else requestBody
        request.method(method, dispatchBody)
        val result = try { execute(request.build()) } catch (error: StayoException) { ensureSameIdentity(); throw error }
        ensureSameIdentity()
        return result
    }

    internal suspend fun execute(request: Request): HttpResult = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(StayoException(0, "network_error"))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val data = response.body?.bytes() ?: ByteArray(0)
                        if (response.isSuccessful) {
                            if (continuation.isActive) continuation.resume(HttpResult(data, response.header("Content-Range")))
                        } else {
                            val code = runCatching { json.parseToJsonElement(data.decodeToString()).jsonObject.let {
                                val error = it["error"]
                                (it["code"] ?: it["error_code"] ?: (error as? JsonObject)?.get("code")
                                    ?: error?.takeIf { value -> value is JsonPrimitive })?.jsonPrimitive?.content
                            } }.getOrNull()?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,80}")) } ?: "http_error"
                            if (continuation.isActive) continuation.resumeWithException(StayoException(response.code, code))
                        }
                    } catch (_: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(StayoException(0, "invalid_response"))
                    }
                }
            }
        })
    }

    override fun close() {
        closed = true
        auth.close()
        realtime.close()
        http.dispatcher.cancelAll()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }
}

internal class HttpResult(val bytes: ByteArray, val contentRange: String?) {
    fun asJson(): JsonElement = if (bytes.isEmpty()) JsonNull else runCatching { Json.parseToJsonElement(bytes.decodeToString()) }
        .getOrElse { throw StayoException(0, "invalid_json") }
}

internal fun validateOrigin(raw: String): HttpUrl {
    val uri = runCatching { URI(raw) }.getOrElse { throw IllegalArgumentException("Invalid project origin") }
    require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.rawPath in listOf("", "/")) { "Use only the project origin" }
    val parsed = runCatching { raw.toHttpUrl() }.getOrElse { throw IllegalArgumentException("Invalid project origin") }
    require(parsed.isHttps || parsed.host in setOf("localhost", "127.0.0.1", "::1")) { "HTTPS is required outside loopback development" }
    return parsed
}

internal fun identifier(value: String): String {
    require(value.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,127}"))) { "Invalid identifier" }
    return value
}
internal fun pathSegments(path: String): List<String> {
    require(path.isNotEmpty() && '\\' !in path) { "Invalid object path" }
    return path.split('/').also { parts -> require(parts.none { it.isEmpty() || it == "." || it == ".." }) { "Invalid object path" } }
}
internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
internal fun requirePublicKey(key: String) {
    require(key.length in 1..16384 && key.all { it.code in 33..126 } && !key.startsWith("sb_secret_") && !key.startsWith("st_secret_")) { "Use a public project key" }
    val payload = key.split('.').getOrNull(1)
    val role = payload?.let { runCatching { Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(it))).jsonObject["role"]?.jsonPrimitive?.content }.getOrNull() }
    require(role !in setOf("service_role", "supabase_admin", "postgres")) { "Privileged keys cannot be used in public clients" }
}
