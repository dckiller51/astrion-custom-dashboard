package com.custom.astrion.tvhelper

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/** [HelperDevice] on the real TV: opening apps, and media control through [MediaListener]. */
class AndroidHelperDevice(context: Context) : HelperDevice {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    override val mediaAccess: Boolean get() = MediaListener.connected

    override val canOpenApps: Boolean get() = Settings.canDrawOverlays(appContext)

    override fun launch(packageName: String): Boolean {
        val pm = appContext.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(packageName) ?: pm.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        main.post { runCatching { appContext.startActivity(intent) } }
        return true
    }

    override fun media(action: String, positionMs: Long?): Boolean = MediaListener.control(action, positionMs)
}
