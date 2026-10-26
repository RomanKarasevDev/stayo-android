package live.stayo.sdk

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class StayoStorage internal constructor(private val client: StayoClient) {
    fun from(bucket: String): StorageBucket {
        require(bucket.isNotBlank() && pathSegments(bucket).size == 1)
        return StorageBucket(client, bucket)
    }
}
class StorageBucket internal constructor(private val client: StayoClient, private val bucket: String) {
    suspend fun upload(path: String, data: ByteArray, contentType: String = "application/octet-stream", upsert: Boolean = false): JsonElement =
        client.request("POST", listOf("storage", "v1", "object", bucket) + pathSegments(path),
            headers = mapOf("x-upsert" to upsert.toString()), bytes = data, mime = contentType).asJson()
    suspend fun download(path: String): ByteArray = client.request("GET", listOf("storage", "v1", "object", bucket) + pathSegments(path)).bytes
    suspend fun remove(paths: List<String>): JsonElement {
        require(paths.isNotEmpty())
        paths.forEach { pathSegments(it) }
        return client.request("DELETE", listOf("storage", "v1", "object", bucket), buildJsonObject {
            put("prefixes", JsonArray(paths.map { JsonPrimitive(it) }))
        }).asJson()
    }
    suspend fun createSignedUrl(path: String, expiresSeconds: Int = 60): String {
        require(expiresSeconds in 1..604800)
        val response = client.request("POST", listOf("storage", "v1", "object", "sign", bucket) + pathSegments(path),
            buildJsonObject { put("expiresIn", expiresSeconds) }).asJson().jsonObject
        val signed = (response["signedURL"] ?: response["signedUrl"])?.jsonPrimitive?.content ?: throw StayoException(0, "invalid_signed_url")
        // Compatible storage returns /object/sign/... relative to /storage/v1, or an absolute same-origin URL.
        val full = when {
            signed.startsWith("/object/") -> client.origin.resolve("/storage/v1$signed")
            signed.startsWith("/storage/v1/") -> client.origin.resolve(signed)
            else -> signed.toHttpUrlOrNull()
        } ?: throw StayoException(0, "invalid_signed_url")
        if (full.scheme != client.origin.scheme || full.host != client.origin.host || full.port != client.origin.port ||
            full.username.isNotEmpty() || full.password.isNotEmpty() || full.fragment != null || !full.encodedPath.startsWith("/storage/v1/object/sign/")) {
            throw StayoException(0, "invalid_signed_url")
        }
        return full.toString()
    }
}
class StayoFunctions internal constructor(private val client: StayoClient) {
    /** Side effects are never retried. Coroutine cancellation cancels the underlying HTTP call. */
    suspend fun invoke(name: String, body: JsonElement = buildJsonObject { }): JsonElement {
        require(name.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,63}"))) { "Invalid function name" }
        val capabilities = client.capabilities()
        if (capabilities["protocol_version"]?.jsonPrimitive?.intOrNull != 1 ||
            capabilities["runtime_version"]?.jsonPrimitive?.content != "1.3.0-compatible" ||
            capabilities["capabilities"]?.jsonObject?.get("functions")?.jsonPrimitive?.booleanOrNull != true) {
            throw StayoException(503, "functions_unavailable")
        }
        val token = client.auth.accessToken() ?: throw StayoException(401, "session_required")
        return client.request("POST", listOf("functions", "v1", name), body, token = token).asJson()
    }
}
