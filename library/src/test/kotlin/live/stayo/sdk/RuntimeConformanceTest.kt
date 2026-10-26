package live.stayo.sdk

import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real network conformance for two dedicated disposable projects; skipped without explicit opt-in.
 * Configuration credentials, response bodies and access tokens never appear in diagnostics.
 * JVM memory storage is deliberate: Android Keystore execution remains a separate device gate.
 */
class RuntimeConformanceTest {
    private val table = "stayo_sdk_probe"
    private val bucket = "stayo-sdk-conformance"
    private val function = "sdk-conformance"
    private val rawHttp = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(20, TimeUnit.SECONDS).build()
    private class Failure(val check: String) : Exception(check)
    private data class Project(val url: String, val publicKey: String, val users: List<Pair<String, String>>)
    private class Identity(val client: StayoClient, val store: MemorySessionStore, val namespace: String,
                           val refreshes: AtomicInteger, val functions: AtomicInteger) {
        lateinit var id: String
        fun hasIdentity() = ::id.isInitialized
        var rowId = UUID.randomUUID().toString()
        val events = ConcurrentLinkedQueue<RealtimeChange>()
        var subscription: RealtimeSubscription? = null
        var collector: Job? = null
    }
    private data class State(val project: Project, val identities: MutableList<Identity>, val anonymous: StayoClient)
    private fun requireCheck(value: Boolean, check: String) { if (!value) throw Failure(check) }
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
    private fun origin(url: String): String {
        val uri = URI(url)
        val host = uri.host ?: throw Failure("valid_project_origin_required")
        val loopback = host in setOf("localhost", "127.0.0.1", "::1", "[::1]")
        requireCheck(uri.scheme == "https" || uri.scheme == "http" && loopback, "https_or_loopback_required")
        requireCheck(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.rawPath in setOf("", "/"), "valid_project_origin_required")
        return "${uri.scheme}://${host.lowercase()}:${if (uri.port >= 0) uri.port else if (uri.scheme == "https") 443 else 80}"
    }
    private suspend fun rawRequest(url: String, project: Project? = null, token: String? = null, body: JsonObject? = null): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
        if (project != null) request.header("apikey", project.publicKey).header("Authorization", "Bearer ${token ?: project.publicKey}")
        if (body != null) request.post(body.toString().toRequestBody("application/json".toMediaType()))
        rawHttp.newCall(request.build()).execute().use {
            if (!it.isSuccessful) throw StayoException(it.code, "probe_http_error")
            it.body?.bytes() ?: byteArrayOf()
        }
    }
    private fun row(id: String, owner: String, run: String, title: String) = buildJsonObject {
        put("id", id); put("owner_id", owner); put("run_id", run); put("title", title)
    }

    private fun configuration(path: String): List<Project> {
        requireCheck(System.getenv("STAYO_ALLOW_DISPOSABLE_RUNTIME_WRITES") == "I_APPROVE_DISPOSABLE_PROJECT_WRITES", "explicit_disposable_write_approval_required")
        val file = Path.of(path)
        requireCheck(file.isAbsolute && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS), "private_configuration_file_required")
        requireCheck(Files.size(file) <= 65_536 && Files.getPosixFilePermissions(file) == PosixFilePermissions.fromString("rw-------"), "configuration_must_be_0600")
        requireCheck(Files.getOwner(file).name == System.getProperty("user.name"), "configuration_owner_mismatch")
        val config = Json.parseToJsonElement(Files.readAllBytes(file).toString(Charsets.UTF_8)).jsonObject
        requireCheck(config.string("runtime_version") == "1.3.0-compatible", "pinned_runtime_required")
        val fixtureRevision = config["fixture_generator_revision"] ?: config["candidate_revision"]
        requireCheck(fixtureRevision?.jsonPrimitive?.content?.matches(Regex("[a-f0-9]{40}")) == true, "fixture_generator_revision_required")
        requireCheck(config.getValue("image_refs").jsonArray.isNotEmpty() && config.getValue("image_refs").jsonArray.all { it.jsonPrimitive.content.contains("@sha256:") }, "image_pins_required")
        val projects = config.getValue("projects").jsonArray.map { value ->
            val project = value.jsonObject
            val url = project.string("url")
            origin(url)
            val loopback = URI(url).host in setOf("localhost", "127.0.0.1", "::1", "[::1]")
            requireCheck(loopback || System.getenv("STAYO_ALLOW_REMOTE_DISPOSABLE_WRITES") == "I_APPROVE_REMOTE_DISPOSABLE_PROJECT_WRITES", "remote_disposable_write_approval_required")
            val users = project.getValue("users").jsonArray.map { it.jsonObject.let { user -> user.string("email") to user.string("password") } }
            requireCheck(users.size == 2 && users[0].first != users[1].first && users.all { '@' in it.first && it.second.length >= 6 }, "two_dedicated_users_required")
            Project(url, project.string("publicKey"), users)
        }
        requireCheck(projects.size == 2 && projects.map { origin(it.url) }.toSet().size == 2, "two_independent_projects_required")
        return projects
    }

    private fun identity(project: Project, namespace: String): Identity {
        val store = MemorySessionStore()
        val refreshes = AtomicInteger(); val functions = AtomicInteger()
        val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).addInterceptor { chain ->
            val url = chain.request().url
            if (url.encodedPath == "/auth/v1/token" && url.queryParameter("grant_type") == "refresh_token") refreshes.incrementAndGet()
            if (url.encodedPath == "/functions/v1/$function") functions.incrementAndGet()
            chain.proceed(chain.request())
        }.build()
        return Identity(StayoClient(project.url, project.publicKey, StayoOptions(store, namespace, httpClient = http)), store, namespace, refreshes, functions)
    }
    private suspend fun denied(check: String, statuses: Set<Int> = setOf(400, 401, 403, 404), operation: suspend () -> Unit) {
        try { operation() }
        catch (error: StayoException) { requireCheck(error.status in statuses, "${check}_unexpected_status"); return }
        throw Failure(check)
    }
    private suspend fun until(check: String, predicate: () -> Boolean) {
        try { withTimeout(20_000) { while (!predicate()) delay(30) } }
        catch (_: TimeoutCancellationException) { throw Failure(check) }
    }

    @Test fun twoPinnedProjectsWithTwoUsersExerciseTheRuntime() = runBlocking {
        val path = System.getenv("STAYO_RUNTIME_CONFIG")
        assumeTrue("Enable only for dedicated disposable runtime projects", path != null)
        val states = mutableListOf<State>()
        val run = UUID.randomUUID().toString()
        var stage = "configuration"
        try {
            withTimeout(180_000) {
                for ((index, project) in configuration(path!!).withIndex()) {
                    stage = "project-${index + 1}-auth"
                    val state = State(project, mutableListOf(), StayoClient(project.url, project.publicKey))
                    states += state
                    for ((userIndex, credentials) in project.users.withIndex()) {
                        val user = identity(project, "conformance-$run-$index-$userIndex")
                        state.identities += user
                        user.client.auth.signInWithPassword(credentials.first, credentials.second)
                        user.id = user.client.auth.getUser().string("id")
                        val before = user.refreshes.get()
                        val first = async(start = CoroutineStart.UNDISPATCHED) { user.client.auth.refreshSession() }
                        val second = async(start = CoroutineStart.UNDISPATCHED) { user.client.auth.refreshSession() }
                        requireCheck(first.await()?.accessToken == second.await()?.accessToken && user.refreshes.get() == before + 1, "refresh_single_flight")
                        val restored = StayoClient(project.url, project.publicKey, StayoOptions(user.store, user.namespace))
                        try { requireCheck(restored.auth.getUser().string("id") == user.id, "memory_session_restart") }
                        finally { restored.close() }
                    }
                    requireCheck(state.identities[0].id != state.identities[1].id, "distinct_auth_users")
                    stage = "project-${index + 1}-capabilities"
                    val capabilities = state.anonymous.capabilities()
                    requireCheck(capabilities.getValue("protocol_version").jsonPrimitive.int == 1 && capabilities.string("runtime_version") == "1.3.0-compatible", "runtime_marker")
                    requireCheck(listOf("auth", "database", "storage", "functions", "realtime").all { capabilities.getValue("capabilities").jsonObject.getValue(it).jsonPrimitive.boolean }, "runtime_features")
                    requireCheck(capabilities.getValue("realtime_events").jsonArray.map { it.jsonPrimitive.content }.toSet() == setOf("INSERT", "UPDATE"), "realtime_event_policy")
                    val limits = capabilities.getValue("limits").jsonObject
                    requireCheck(limits.getValue("function_timeout_ms").jsonPrimitive.int == 60_000 && limits.getValue("function_memory_mb").jsonPrimitive.int == 150 && limits.getValue("function_concurrency").jsonPrimitive.int == 2, "worker_limits")
                    val publication = state.identities[0].client.rpc("stayo_sdk_probe_publication").jsonObject
                    requireCheck(listOf("published", "insert", "update").all { publication.getValue(it).jsonPrimitive.boolean } && !publication.getValue("delete").jsonPrimitive.boolean, "physical_delete_publication_disabled")
                }
                stage = "four-user-realtime-subscribe"
                for (state in states) for (user in state.identities) {
                    val subscription = user.client.realtime.subscribe(table)
                    user.subscription = subscription
                    user.collector = launch(start = CoroutineStart.UNDISPATCHED) { subscription.changes.collect { if (it.record["run_id"]?.jsonPrimitive?.content == run) user.events.add(it) } }
                    withTimeout(20_000) { subscription.status.first { it == RealtimeStatus.SUBSCRIBED } }
                }
                for ((index, state) in states.withIndex()) {
                    stage = "project-${index + 1}-crud-rls-realtime"
                    for ((u, user) in state.identities.withIndex()) {
                        val inserted = user.client.from(table).insert(row(user.rowId, user.id, run, "kotlin-$u-before")).execute().data.jsonArray
                        requireCheck(inserted.size == 1 && inserted.single().jsonObject.string("id") == user.rowId, "insert_representation")
                    }
                    until("realtime_insert") { state.identities.all { user -> user.events.any { it.event == RowChange.INSERT && it.record["id"]?.jsonPrimitive?.content == user.rowId } } }
                    for ((u, user) in state.identities.withIndex()) {
                        val other = state.identities[1 - u]
                        val rows = user.client.from(table).select("id,owner_id,title").eq("run_id", run).order("id").range(0, 9).execute().data.jsonArray
                        requireCheck(rows.size == 1 && rows.single().jsonObject.string("id") == user.rowId, "owner_read_rls")
                        requireCheck(user.client.rpc("stayo_sdk_probe_count", buildJsonObject { put("probe_run_id", run) }).jsonPrimitive.int == 1, "owner_rpc_rls")
                        denied("foreign_insert_allowed") { user.client.from(table).insert(row(UUID.randomUUID().toString(), other.id, run, "forbidden")).execute() }
                        requireCheck(user.client.from(table).update(buildJsonObject { put("title", "forbidden") }).eq("id", other.rowId).execute().data.jsonArray.isEmpty(), "foreign_update_allowed")
                        requireCheck(user.client.from(table).delete().eq("id", other.rowId).execute().data.jsonArray.isEmpty(), "foreign_delete_allowed")
                        val updated = user.client.from(table).update(buildJsonObject { put("title", "kotlin-$u-after") }).eq("id", user.rowId).execute().data.jsonArray
                        requireCheck(updated.size == 1, "owner_update")
                    }
                    until("realtime_update") { state.identities.withIndex().all { (u, user) -> user.events.any { it.event == RowChange.UPDATE && it.record["id"]?.jsonPrimitive?.content == user.rowId && it.record["title"]?.jsonPrimitive?.content == "kotlin-$u-after" } } }
                    requireCheck(state.anonymous.from(table).select().eq("run_id", run).execute().data.jsonArray.isEmpty(), "anonymous_read_allowed")
                    denied("anonymous_insert_allowed") { state.anonymous.from(table).insert(row(UUID.randomUUID().toString(), state.identities[0].id, run, "forbidden")).execute() }
                    val owner = state.identities[0]; val other = state.identities[1]
                    stage = "project-${index + 1}-storage"
                    val objectPath = "${owner.id}/$run/file.txt"; val bytes = "synthetic-kotlin-$run".toByteArray()
                    val storage = owner.client.storage.from(bucket)
                    storage.upload(objectPath, bytes, "text/plain")
                    requireCheck(storage.download(objectPath).contentEquals(bytes), "storage_roundtrip")
                    denied("foreign_storage_read_allowed") { other.client.storage.from(bucket).download(objectPath) }
                    denied("foreign_storage_signing_allowed") { other.client.storage.from(bucket).createSignedUrl(objectPath) }
                    denied("foreign_storage_upload_allowed") { other.client.storage.from(bucket).upload("${owner.id}/$run/forbidden.txt", bytes) }
                    denied("anonymous_storage_read_allowed") { state.anonymous.storage.from(bucket).download(objectPath) }
                    val signed = storage.createSignedUrl(objectPath)
                    val response = rawRequest(signed)
                    requireCheck(response.contentEquals(bytes), "signed_download")
                    stage = "project-${index + 1}-functions"
                    for (user in state.identities) {
                        val before = user.functions.get()
                        val echoed = user.client.functions.invoke(function, buildJsonObject { put("nonce", run) }).jsonObject
                        requireCheck(echoed.string("probe") == "stayo-sdk-conformance-v1" && echoed.string("nonce") == run && echoed.string("user_id") == user.id && echoed.getValue("secret_present").jsonPrimitive.boolean, "function_identity_and_project_secret")
                        requireCheck(user.functions.get() == before + 1, "function_single_dispatch")
                    }
                    denied("anonymous_function_allowed", setOf(401)) {
                        rawRequest(state.project.url.trimEnd('/') + "/functions/v1/$function", state.project, body = buildJsonObject { put("nonce", run) })
                    }
                    denied("missing_function_allowed", setOf(404)) { owner.client.functions.invoke("sdk-missing", buildJsonObject { put("nonce", run) }) }
                    val before = owner.functions.get()
                    val pending = async { owner.client.functions.invoke(function, buildJsonObject { put("nonce", run); put("delay_ms", 500) }) }
                    until("function_cancel_dispatch") { owner.functions.get() == before + 1 }
                    pending.cancelAndJoin()
                    requireCheck(pending.isCancelled && owner.functions.get() == before + 1, "function_cancellation_or_retry")
                    delay(600)
                    stage = "project-${index + 1}-cross-project"
                    val otherProject = states[1 - index]
                    val token = owner.client.auth.session.value!!.accessToken
                    denied("cross_project_auth_token_allowed", setOf(401, 403)) { rawRequest(otherProject.project.url.trimEnd('/') + "/auth/v1/user", otherProject.project, token) }
                    denied("cross_project_rest_token_allowed", setOf(401, 403)) { rawRequest(otherProject.project.url.trimEnd('/') + "/rest/v1/$table", otherProject.project, token) }
                    denied("cross_project_function_token_allowed", setOf(401, 403)) { rawRequest(otherProject.project.url.trimEnd('/') + "/functions/v1/$function", otherProject.project, token, buildJsonObject { put("nonce", run) }) }
                    requireCheck(otherProject.identities[0].client.from(table).select().eq("id", owner.rowId).execute().data.jsonArray.isEmpty(), "cross_project_row_visible")
                    denied("cross_project_storage_read_allowed") { otherProject.identities[0].client.storage.from(bucket).download(objectPath) }
                    stage = "project-${index + 1}-refresh-active-realtime"
                    owner.client.auth.refreshSession()
                    owner.client.from(table).update(buildJsonObject { put("title", "kotlin-after-refresh") }).eq("id", owner.rowId).execute()
                    until("realtime_after_refresh") { owner.events.any { it.event == RowChange.UPDATE && it.record["id"]?.jsonPrimitive?.content == owner.rowId && it.record["title"]?.jsonPrimitive?.content == "kotlin-after-refresh" } }
                    requireCheck(state.identities.all { user -> user.events.all { it.record["owner_id"]?.jsonPrimitive?.content == user.id } }, "foreign_realtime_row_delivered")
                    storage.remove(listOf(objectPath))
                }
                stage = "delete-owned-run-and-logout"
                for (state in states) for (user in state.identities) {
                    requireCheck(user.client.from(table).delete().eq("run_id", run).eq("owner_id", user.id).execute().data.jsonArray.size == 1, "owner_delete")
                    user.client.auth.signOut()
                    requireCheck(user.client.auth.session.value == null && user.subscription?.status?.value == RealtimeStatus.CLOSED, "logout_session_and_subscription")
                    user.collector?.cancelAndJoin()
                }
            }
        } catch (error: Throwable) {
            val safe = when (error) { is Failure -> error.check; is StayoException -> "http_${error.status}_${error.code}"; is TimeoutCancellationException -> "deadline"; else -> "operation_failed" }
            throw AssertionError("Android runtime conformance failed at $stage: $safe")
        } finally {
            withContext(NonCancellable) {
                for (state in states) {
                    for (user in state.identities) {
                        user.collector?.cancelAndJoin()
                        user.subscription?.close()
                        if (user.hasIdentity() && user.client.auth.session.value != null) {
                            withTimeoutOrNull(5_000) {
                                runCatching { user.client.storage.from(bucket).remove(listOf("${user.id}/$run/file.txt", "${user.id}/$run/forbidden.txt")) }
                                runCatching { user.client.from(table).delete().eq("run_id", run).eq("owner_id", user.id).execute() }
                                runCatching { user.client.auth.signOut() }
                            }
                        }
                        user.client.close()
                    }
                    state.anonymous.close()
                }
                rawHttp.dispatcher.cancelAll()
                rawHttp.connectionPool.evictAll()
                rawHttp.dispatcher.executorService.shutdown()
            }
        }
    }
}
