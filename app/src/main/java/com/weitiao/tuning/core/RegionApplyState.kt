package com.weitiao.tuning.core

import android.content.Context

/** UI-only state indicating that Galaxy Store still has cached data from the previous region. */
object RegionApplyState {
    private const val PREFS = "weitiao_region_apply_state"
    private const val KEY_PENDING = "galaxy_store_cleanup_pending"
    private const val KEY_FORCE_SIM_121_MIGRATED = "force_sim_121_migrated"

    fun isPending(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PENDING, false)

    fun markPending(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PENDING, true).apply()
    }

    fun markApplied(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PENDING, false).apply()
    }

    /** Returns true when 1.2.1 first forces an existing CSC configuration to include SIM identity. */
    fun migrateForceSim121(context: Context, hasRegionConfig: Boolean): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_FORCE_SIM_121_MIGRATED, false)) return false
        prefs.edit()
            .putBoolean(KEY_FORCE_SIM_121_MIGRATED, true)
            .putBoolean(KEY_PENDING, hasRegionConfig || prefs.getBoolean(KEY_PENDING, false))
            .apply()
        return hasRegionConfig
    }
}
