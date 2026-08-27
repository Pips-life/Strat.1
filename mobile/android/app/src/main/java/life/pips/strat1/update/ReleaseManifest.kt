package life.pips.strat1.update

data class ReleaseManifest(
    val versionName: String,
    val versionCode: Long,
    val minimumSupportedVersionCode: Long,
    val mandatory: Boolean,
    val releaseNotes: List<String>,
    val apkUrl: String,
    val sha256: String
)

fun ReleaseManifest.isUpgrade(currentVersionCode: Long): Boolean = versionCode > currentVersionCode
