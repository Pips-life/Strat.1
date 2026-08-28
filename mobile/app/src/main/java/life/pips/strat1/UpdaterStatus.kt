package life.pips.strat1

/** Lightweight release status model retained for compatibility with the updater UI. */
enum class UpdaterStatus { CHECKING, UP_TO_DATE, UPDATE_AVAILABLE, CHECK_FAILED }

data class UpdaterState(
    val status: UpdaterStatus,
    val installedVersion: String,
    val latestVersion: String? = null
)
