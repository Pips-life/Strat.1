package life.pips.strat1.network

object BackendDefaults {
    /** Ordered candidates; the release can replace this list without changing the discovery API. */
    val candidates: List<String> = listOf(
        "https://strat-1.vercel.app"
    )
}
