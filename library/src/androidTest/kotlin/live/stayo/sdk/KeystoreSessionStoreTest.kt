package live.stayo.sdk

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs against Android's real Keystore and application-private no-backup directory. */
@RunWith(AndroidJUnit4::class)
class KeystoreSessionStoreTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val createdKeys = mutableListOf<String>()
    private val fixture = "fixture-access-never-plaintext-${UUID.randomUUID()}"

    private fun fixtureKey() = "stayo.instrumentation.${UUID.randomUUID()}".also { createdKeys.add(it) }
    private fun digest(key: String) = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        .joinToString("") { "%02x".format(it) }
    private fun ciphertext(key: String) = File(File(context.noBackupFilesDir, "stayo-sessions"), digest(key))

    @After fun deleteOnlyFixtureMaterial() {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        for (key in createdKeys) {
            ciphertext(key).delete()
            val alias = "stayo.${digest(key)}"
            if (keystore.containsAlias(alias)) keystore.deleteEntry(alias)
        }
    }

    @Test fun encryptedSessionPersistsAcrossStoreInstancesAndDeletion() {
        val key = fixtureKey()
        KeystoreSessionStore(context).write(key, fixture)
        assertEquals(fixture, KeystoreSessionStore(context).read(key))
        KeystoreSessionStore(context).write(key, null)
        assertNull(KeystoreSessionStore(context).read(key))
        assertFalse(ciphertext(key).exists())
    }

    @Test fun independentKeysAreIsolatedAndCannotDecryptCopiedCiphertext() {
        val first = fixtureKey()
        val second = fixtureKey()
        val store = KeystoreSessionStore(context)
        store.write(first, "$fixture-first")
        store.write(second, "$fixture-second")
        assertEquals("$fixture-first", store.read(first))
        assertEquals("$fixture-second", store.read(second))
        assertFalse(ciphertext(first).readBytes().contentEquals(ciphertext(second).readBytes()))
        ciphertext(second).writeBytes(ciphertext(first).readBytes())
        assertStorageUnavailable { KeystoreSessionStore(context).read(second) }
        assertEquals("$fixture-first", store.read(first))
    }

    @Test fun onlyCiphertextIsStoredInNoBackupAndEachWriteUsesFreshIv() {
        val key = fixtureKey()
        val store = KeystoreSessionStore(context)
        store.write(key, fixture)
        val file = ciphertext(key)
        val first = file.readBytes()
        assertTrue(file.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + File.separator))
        assertFalse(File(context.filesDir, "stayo-sessions/${digest(key)}").exists())
        assertTrue(first.size > fixture.toByteArray().size)
        assertFalse(first.toString(Charsets.ISO_8859_1).contains(fixture))
        store.write(key, fixture)
        val second = file.readBytes()
        assertFalse(first.copyOfRange(0, 12).contentEquals(second.copyOfRange(0, 12)))
        assertEquals(fixture, store.read(key))
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertNull(keystore.getKey("stayo.${digest(key)}", null).encoded)
    }

    @Test fun tamperingFailsClosedAndNeverReturnsPartialPlaintext() {
        val key = fixtureKey()
        val store = KeystoreSessionStore(context)
        store.write(key, fixture)
        val bytes = ciphertext(key).readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        ciphertext(key).writeBytes(bytes)
        assertStorageUnavailable { store.read(key) }
        store.write(key, "$fixture-replacement")
        assertEquals("$fixture-replacement", store.read(key))
    }

    private fun assertStorageUnavailable(read: () -> String?) {
        try {
            read()
            fail("Unauthenticated ciphertext must never produce a session")
        } catch (error: StayoException) {
            assertEquals("session_storage_unavailable", error.code)
            assertFalse(error.toString().contains(fixture))
        }
    }
}
