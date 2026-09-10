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
private val WATCHLIST = stringPreferencesKey("watchlist")

private fun secretKey(): SecretKey {
    val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    val existing = store.getKey(KEY_NAME, null)
    if (existing is SecretKey) return existing
    val generator = KeyGenerator.getInstance("AES", KEYSTORE)
    generator.init(android.security.keystore.KeyGenParameterSpec.Builder(KEY_NAME, android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).build())
    return generator.generateKey()
}
private fun encrypt(value: String): String { if (value.isBlank()) return ""; val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, secretKey()); return Base64.encodeToString(c.iv + c.doFinal(value.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP) }
private fun decrypt(value: String?): String = if (value.isNullOrBlank()) "" else runCatching { val b = Base64.decode(value, Base64.NO_WRAP); Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, b.copyOfRange(0, 12))) }.doFinal(b.copyOfRange(12, b.size)).toString(StandardCharsets.UTF_8) }.getOrDefault("")

data class SavedConnection(val metaApiToken: String, val accountId: String, val login: String, val password: String, val server: String, val flashAlphaKey: String, val watchlist: String = "XAUUSD,NAS100,EURUSD,GBPUSD,US30")

suspend fun Context.loadSavedConnection(): SavedConnection { val p = secretStore.data.first(); return SavedConnection(decrypt(p[META_TOKEN]), decrypt(p[ACCOUNT_ID]), decrypt(p[LOGIN]), decrypt(p[PASSWORD]), decrypt(p[SERVER]), decrypt(p[FLASH_KEY]), decrypt(p[WATCHLIST]).ifBlank { "XAUUSD,NAS100,EURUSD,GBPUSD,US30" }) }
suspend fun Context.saveConnection(value: SavedConnection) { secretStore.edit { it[META_TOKEN] = encrypt(value.metaApiToken); it[ACCOUNT_ID] = encrypt(value.accountId); it[LOGIN] = encrypt(value.login); it[PASSWORD] = encrypt(value.password); it[SERVER] = encrypt(value.server); it[FLASH_KEY] = encrypt(value.flashAlphaKey); it[WATCHLIST] = encrypt(value.watchlist) } }
