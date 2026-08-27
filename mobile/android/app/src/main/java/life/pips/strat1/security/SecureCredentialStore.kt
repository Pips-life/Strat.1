package life.pips.strat1.security

/**
 * Credential boundary for MT5 secrets.
 *
 * Implementation will use Android Keystore-backed encryption. Plaintext passwords
 * must never be written to SharedPreferences, DataStore, logs, analytics, or
 * crash reports. Broker/server/account preference data may be stored separately.
 */
interface SecureCredentialStore {
    suspend fun savePassword(accountKey: String, password: String)
    suspend fun readPassword(accountKey: String): String?
    suspend fun deletePassword(accountKey: String)
}
