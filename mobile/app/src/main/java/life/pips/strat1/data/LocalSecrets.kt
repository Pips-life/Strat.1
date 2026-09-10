package life.pips.strat1.data

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.KeyStore

private val Context.secretStore by preferencesDataStore("pips_life_secure_keys")
private const val KEYSTORE = "AndroidKeyStore"
private const val KEY_NAME = "PipsLifeLocalSecrets"
private val META_TOKEN = stringPreferencesKey("metaapi_token")
private val ACCOUNT_ID = stringPreferencesKey("metaapi_account_id")
private val LOGIN = stringPreferencesKey("broker_login")
private val PASSWORD = stringPreferencesKey("broker_password")
private val SERVER = stringPreferencesKey("broker_server")
private val FLASH_KEY = stringPreferencesKey("flashalpha_key")

private fun secretKey(): SecretKey {
    val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    val existing = store.getKey(KEY_NAME, null)
    if (existing is SecretKey) return existing
    val generator = KeyGenerator.getInstance("AES", KEYSTORE)
    generator.init(android.security.keystore.KeyGenParameterSpec.Builder(KEY_NAME, android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
        .build())
    return generator.generateKey()
}

private fun encrypt(value: String): String {
    if (value.isBlank()) return ""
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, secretKey())
    val combined = cipher.iv + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
    return Base64.encodeToString(combined, Base64.NO_WRAP)
}

private fun decrypt(value: String?): String {
    if (value.isNullOrBlank()) return ""
    return runCatching {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, 12)
        val payload = bytes.copyOfRange(12, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(payload), StandardCharsets.UTF_8)
    }.getOrDefault("")
}

data class SavedConnection(val metaApiToken: String, val accountId: String, val login: String, val password: String, val server: String, val flashAlphaKey: String)

suspend fun Context.loadSavedConnection(): SavedConnection {
    val p = secretStore.data.first()
    return SavedConnection(decrypt(p[META_TOKEN]), decrypt(p[ACCOUNT_ID]), decrypt(p[LOGIN]), decrypt(p[PASSWORD]), decrypt(p[SERVER]), decrypt(p[FLASH_KEY]))
}

suspend fun Context.saveConnection(value: SavedConnection) {
    secretStore.edit {
        it[META_TOKEN] = encrypt(value.metaApiToken)
        it[ACCOUNT_ID] = encrypt(value.accountId)
        it[LOGIN] = encrypt(value.login)
        it[PASSWORD] = encrypt(value.password)
        it[SERVER] = encrypt(value.server)
        it[FLASH_KEY] = encrypt(value.flashAlphaKey)
    }
}
