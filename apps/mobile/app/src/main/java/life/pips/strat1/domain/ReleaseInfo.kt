package life.pips.strat1.domain

data class ReleaseInfo(
    val versionName: String,
    val versionCode: Long,
    val notes: String,
    val mandatory: Boolean,
    val apkUrl: String? = null,
)

object ReleasePolicy {
    fun isNewer(currentVersionCode: Long, release: ReleaseInfo): Boolean =
        release.versionCode > currentVersionCode
}
