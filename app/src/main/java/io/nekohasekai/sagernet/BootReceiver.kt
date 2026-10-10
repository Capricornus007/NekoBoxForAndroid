package io.nekohasekai.sagernet

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.nekohasekai.sagernet.bg.NetworkAutomation
import io.nekohasekai.sagernet.bg.NetworkAutomationService
import io.nekohasekai.sagernet.bg.RuleAssetUpdater
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher

class BootReceiver : BroadcastReceiver() {
    companion object {
        private val componentName by lazy { ComponentName(app, BootReceiver::class.java) }
        var enabled: Boolean
            get() = app.packageManager.getComponentEnabledSetting(
                componentName,
            ) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            set(value) = app.packageManager.setComponentEnabledSetting(
                componentName,
                if (value) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                },
                PackageManager.DONT_KILL_APP,
            )

        // Boot and app updates restart the service or the network automation watcher.
        val wanted get() = DataStore.persistAcrossReboot || DataStore.networkAutomation

        /**
         * After boot or an app update; not a manual start. A pause a manual stop set stays, and the VPN
         * stays off without its permission or while Android reports another VPN, which can also belong
         * to another user or a work profile.
         */
        fun restore(context: Context) {
            if (DataStore.persistAcrossReboot && DataStore.selectedProxy > 0) {
                val blocked = NetworkAutomation.unattendedStartBlocked()
                if (blocked == null) SagerNet.startService(byUser = false) else Logs.w("start at boot skipped: $blocked")
            }
            NetworkAutomationService.sync(context)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        runOnDefaultDispatcher {
            SubscriptionUpdater.reconfigureUpdater()
            RuleAssetUpdater.reconfigure()
        }

        if (!wanted) { // sanity check
            enabled = false
            return
        }

        val unlocked = when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> false
            else -> Build.VERSION.SDK_INT < 24 || SagerNet.user.isUserUnlocked
        }
        if (!unlocked) return

        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED &&
            DataStore.persistAcrossReboot && DataStore.selectedProxy > 0
        ) {
            // The update replaced the process, so an existing tunnel has to be torn down and
            // started again - something a plain restore cannot do. Neither step is a user action,
            // so network automation keeps its pause and hold state.
            runOnDefaultDispatcher {
                SagerNet.stopService(byUser = false)
                Thread.sleep(750)
                SagerNet.startService(byUser = false)
            }
            NetworkAutomationService.sync(context)
            return
        }

        restore(context)
    }
}
