package life.pips.strat1

import android.app.Application

/**
 * Application bootstrap only.
 *
 * Release checking and installation are owned by AppUpdateCard/ReleaseUpdateManager,
 * which talk directly to GitHub's public Releases API. Keeping update ownership in
 * one place prevents duplicate prompts and prevents a protected backend deployment
 * from breaking release checks.
 */
class PipsLifeApplication : Application()
