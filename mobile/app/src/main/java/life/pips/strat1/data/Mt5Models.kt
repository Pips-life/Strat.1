package life.pips.strat1.data

data class Mt5Server(
    val id: String,
    val brokerName: String,
    val serverName: String,
    val environment: String,
) {
    // Compatibility aliases for older mobile UI code.
    val broker: String get() = brokerName
    val name: String get() = serverName
}

data class Mt5AccountPreference(
    val brokerName: String,
    val serverId: String,
    val accountNumber: String,
)

data class Mt5ConnectionRequest(
    val serverId: String,
    val accountNumber: String,
    val password: String,
)

data class ReleaseManifest(
    val versionName: String,
    val versionCode: Long,
    val minimumSupportedVersionCode: Long,
    val downloadUrl: String,
    val sha256: String,
    val releaseNotes: List<String> = emptyList(),
)
