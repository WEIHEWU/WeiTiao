package com.weitiao.tuning.core

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

object LanguageManager {

    private const val PREFS = "lang_prefs"
    private const val KEY_LANG = "locale"

    fun current(context: Context): String? {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LANG, null)
    }

    fun toggle(context: Context): String {
        val active = current(context)
            ?: context.resources.configuration.locales[0].language
        val next = if (active.startsWith("zh")) "en" else "zh"
        set(context, next)
        return next
    }

    fun set(context: Context, lang: String) {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANG, lang).commit()
    }

    fun apply(context: Context): Context {
        val lang = current(context) ?: return context
        val locale = if (lang == "zh") Locale.SIMPLIFIED_CHINESE else Locale.US
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        if (Build.VERSION.SDK_INT >= 24) {
            config.setLocales(LocaleList(locale))
        }
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
        return context.createConfigurationContext(config)
    }

    fun isEnglish(context: Context): Boolean = current(context) == "en"

    fun recreateActivity(context: Context) {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) {
                current.recreate()
                return
            }
            current = current.baseContext
        }
    }
}
