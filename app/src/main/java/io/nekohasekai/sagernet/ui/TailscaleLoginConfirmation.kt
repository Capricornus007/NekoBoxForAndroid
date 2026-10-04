package io.nekohasekai.sagernet.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R

internal fun confirmTailscaleLogin(context: Context, link: TailscaleLoginLink, open: () -> Unit): AlertDialog? {
    if (!link.isHttp) {
        open()
        return null
    }
    return MaterialAlertDialogBuilder(context)
        .setTitle(R.string.tailscale_login_required)
        .setMessage(
            context.getString(R.string.tailscale_status_login_origin, link.origin) + "\n\n" +
                context.getString(R.string.tailscale_login_http_warning),
        )
        .setPositiveButton(R.string.tailscale_login_continue) { _, _ -> open() }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}
