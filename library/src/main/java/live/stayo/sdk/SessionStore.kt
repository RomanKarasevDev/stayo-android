package live.stayo.sdk

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Implementations must never log plaintext. Keys include normalized origin and application namespace. */
interface SessionStore { fun read(key: String): String?; fun write(key: String, value: String?) }
class MemorySessionStore : SessionStore {
    private val values = mutableMapOf<String, String>()
    @Synchronized override fun read(key: String) = values[key]
    @Synchronized override fun write(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
}

/** AES-GCM keys stay in Android Keystore; ciphertext resides exclusively in noBackupFilesDir. */
class KeystoreSessionStore(context: Context) : SessionStore {
    private val directory = File(context.applicationContext.noBackupFilesDir, "stayo-sessions")
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun key(alias: String): SecretKey = synchronized(KeystoreSessionStore::class.java) {
        (keyStore.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setKeySize(256).build())
        }.generateKey()
    }
    @Synchronized override fun read(key: String): String? {
        val file = File(directory, sha256(key))
        if (!file.exists()) return null
        return try {
            val payload = file.readBytes()
            check(payload.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key("stayo.${sha256(key)}"), GCMParameterSpec(128, payload.copyOfRange(0, 12)))
            cipher.updateAAD(key.toByteArray())
            cipher.doFinal(payload.copyOfRange(12, payload.size)).decodeToString()
        } catch (_: Exception) { throw StayoException(0, "session_storage_unavailable") }
    }
    @Synchronized override fun write(key: String, value: String?) {
        try {
            check(directory.isDirectory || directory.mkdirs() || directory.isDirectory)
            val file = File(directory, sha256(key))
            if (value == null) { check(!file.exists() || file.delete()); return }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key("stayo.${sha256(key)}"))
            cipher.updateAAD(key.toByteArray())
            val tmp = File.createTempFile(".stayo-", ".tmp", directory)
            try {
                tmp.setReadable(false, false); tmp.setWritable(false, false)
                check(tmp.setReadable(true, true) && tmp.setWritable(true, true))
                FileOutputStream(tmp).use { stream -> stream.write(cipher.iv + cipher.doFinal(value.toByteArray())); stream.fd.sync() }
                check(tmp.renameTo(file))
            } finally { tmp.delete() }
        } catch (_: Exception) { throw StayoException(0, "session_storage_unavailable") }
    }
}
