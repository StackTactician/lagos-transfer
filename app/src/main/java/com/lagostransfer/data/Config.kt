package com.lagostransfer.data

import com.lagostransfer.BuildConfig

/**
 * Point this at your own licence server. It only validates activation codes
 * and tells the app whether this phone already owns a licence.
 */
object Config {
    val LICENCE_SERVER: String = BuildConfig.LICENCE_SERVER

    /** Cumulative value the trial allows, in naira, across all accounts of this app. */
    const val TRIAL_LIMIT = 100_000_000L
}
