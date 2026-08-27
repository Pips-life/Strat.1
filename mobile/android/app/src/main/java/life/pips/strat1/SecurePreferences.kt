package life.pips.strat1

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecurePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("strat1_secure", Context.MODE_PRIVATE)
    private val keyAlias = "strat1_local_preferences"

    init {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(keyAlias)) {
            val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
            generator.init(256)
            generator.generateKey()
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (ks.getEntry(keyAlias, null) as KeyStore.SecretKeyEntry).secretKey
    }

    fun put(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
        prefs.edit().putString(name, payload).apply()
    }

    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        val parts = stored.split(":", limit = 2)
        if (parts.size != 2) return null
        return runCatching {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val data = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    fun clearAll() = prefs.edit().clear().apply()
}
