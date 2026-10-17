package com.custom.astrion

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.core.content.edit
import java.util.Locale
import kotlin.system.exitProcess

/**
 * The app's own language (Settings › Language), independent of Android's: "" follows the system,
 * otherwise one of [CHOICES]. Applied by wrapping the base context of the Application and the
 * activity ([wrap]), so a change needs a restart ([restart]) — a HOME launcher activity can't
 * rely on Activity.recreate() on Android 8.1.
 */
object AppLanguage {
    /** Language tag → its own name (shown as is, whatever the current language). */
    val CHOICES = linkedMapOf("en" to "English", "fr" to "Français", "it" to "Italiano", "es" to "Español")

    private const val PREFS = "astrion_settings"
    private const val KEY = "app_language"

    fun get(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty().takeIf { it in CHOICES }.orEmpty()

    fun set(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) { putString(KEY, tag) }
    }

    /** [base] with the chosen language applied (unchanged when following the system). */
    fun wrap(base: Context): Context {
        val tag = get(base)
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    /** Restarts the app's process on MainActivity — for a language change, or Settings › About › Restart. */
    fun restart(activity: Activity) {
        val intent = Intent.makeRestartActivityTask(ComponentName(activity, MainActivity::class.java))
        activity.startActivity(intent)
        exitProcess(0)
    }
}
