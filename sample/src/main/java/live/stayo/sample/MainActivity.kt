package live.stayo.sample

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import live.stayo.sdk.*

/** Use a test project with RLS on public.notes; no service-role or external API secret belongs here. */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var client: StayoClient? = null
    private lateinit var result: TextView
    private var pendingCallback: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 48, 24, 24) }
        val origin = EditText(this).apply { hint = "https://project.example.com" }
        val publicKey = EditText(this).apply { hint = "Public project key" }
        val configuration = getSharedPreferences("public-project", MODE_PRIVATE)
        origin.setText(configuration.getString("origin", ""))
        publicKey.setText(configuration.getString("publicKey", ""))
        val email = EditText(this).apply { hint = "Email"; inputType = 33 }
        val password = EditText(this).apply { hint = "Password"; inputType = 129 }
        result = TextView(this)
        listOf(origin, publicKey, email, password).forEach { box.addView(it) }
        fun sdk(): StayoClient = client ?: StayoClient(origin.text.toString(), publicKey.text.toString(), this).also {
            client = it
            configuration.edit().putString("origin", origin.text.toString()).putString("publicKey", publicKey.text.toString()).apply()
        }
        fun action(label: String, block: suspend () -> Unit) {
            box.addView(Button(this).apply { text = label; setOnClickListener {
                scope.launch { try { block() } catch (e: StayoException) { result.text = "${e.status}: ${e.code}" } catch (_: Exception) { result.text = "Check project settings and connection" } }
            } })
        }
        action("Connect project") {
            sdk()
            pendingCallback?.let { sdk().auth.handleOAuthCallback(it); pendingCallback = null }
            result.text = "Connected"
        }
        action("Register") { sdk().auth.signUp(email.text.toString(), password.text.toString()); result.text = "Check email for confirmation" }
        action("Sign in") { sdk().auth.signInWithPassword(email.text.toString(), password.text.toString()); result.text = "Signed in" }
        action("Google") { sdk().auth.launchOAuth(this, OAuthProvider.GOOGLE, "stayo-example://auth/callback") }
        action("Apple") { sdk().auth.launchOAuth(this, OAuthProvider.APPLE, "stayo-example://auth/callback") }
        action("Read notes") { result.text = sdk().from("notes").select().order("created_at").limit(10).execute().data.toString() }
        action("Add note") { sdk().from("notes").insert(buildJsonObject { put("title", "Hello from Android") }).execute(); result.text = "Saved" }
        action("Call hello function") { result.text = sdk().functions.invoke("hello", buildJsonObject { put("message", "Android") }).toString() }
        action("Watch notes") {
            val subscription = sdk().realtime.subscribe("notes")
            scope.launch { subscription.changes.collect { result.text = "${it.event}: ${it.record}" } }
            scope.launch { subscription.resyncVersion.collect { if (it > 0) result.text = sdk().from("notes").select().execute().data.toString() } }
            result.text = "Subscribed"
        }
        action("Sign out") { sdk().auth.signOut(); result.text = "Signed out" }
        box.addView(result)
        setContentView(ScrollView(this).apply { addView(box) })
        if (origin.text.isNotBlank() && publicKey.text.isNotBlank()) runCatching { sdk() }
        callback(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); callback(intent) }
    private fun callback(intent: Intent?) {
        val uri = intent?.dataString ?: return
        pendingCallback = uri
        scope.launch { try {
            val sdk = client
            if (sdk == null) result.text = "Connect the same project to finish OAuth"
            else { sdk.auth.handleOAuthCallback(uri); pendingCallback = null; result.text = "Signed in" }
        } catch (e: StayoException) { pendingCallback = null; result.text = e.code } }
    }
    override fun onDestroy() { client?.close(); scope.cancel(); super.onDestroy() }
}
