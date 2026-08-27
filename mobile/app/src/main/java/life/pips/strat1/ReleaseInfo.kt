package life.pips.strat1

data class ReleaseInfo(
    val versionName: String,
    val versionCode: Int,
    val releaseName: String,
    val notes: String,
    val apkUrl: String
)
