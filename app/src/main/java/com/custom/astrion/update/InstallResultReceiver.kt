package com.custom.astrion.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.Toast

/**
 * What the system's package installer says about an update committed by
 * [UpdateChecker.promptInstall] (1.5.7): asks for the user's confirmation (its screen is opened
 * here), or the outcome, logged — the reason too when it fails, which the old "open the APK" way
 * never told (a test build sometimes needed two taps: its installer closed by itself).
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Log.i(TAG, "asking for the user's confirmation")
                confirmation(intent)?.let {
                    context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i(TAG, "installed")
            else -> {
                Log.w(TAG, "install failed: status $status, $message")
                Toast.makeText(context, "Update not installed: ${message ?: status}", Toast.LENGTH_LONG).show()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmation(intent: Intent): Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
    } else {
        intent.getParcelableExtra(Intent.EXTRA_INTENT)
    }

    private companion object {
        const val TAG = "Update"
    }
}
