package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.content.pm.ShortcutManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.getSystemService

class QuickClearCacheShortcut : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 25) {
            getSystemService<ShortcutManager>()!!.reportShortcutUsed("clear-cache-restart")
        }

        android.app.AlertDialog.Builder(this).setMessage(io.nekohasekai.sagernet.R.string.confirm_clear_cache)
            .setPositiveButton(android.R.string.ok) { _, _ -> recoverVlessTlsStateAndRestart(this) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }.show()
    }
}
