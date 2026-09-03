package life.pips.strat1.data

/**
 * Null-safe adapter for UI polling paths that intentionally accept a nullable
 * backend session. A missing session becomes a failed Result instead of a
 * compile-time nullable-argument error; non-null sessions delegate to the
 * existing BackendApiClient member implementation.
 */
suspend fun BackendApiClient.botStatus(session: BackendSession?): Result<BotState> =
    if (session == null) {
        Result.failure(IllegalStateException("No backend session"))
    } else {
        this.botStatus(session)
    }
