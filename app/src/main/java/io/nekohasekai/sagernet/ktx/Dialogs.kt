package io.nekohasekai.sagernet.ktx

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R

fun Context.alert(text: String): AlertDialog {
    return MaterialAlertDialogBuilder(this).setTitle(R.string.error_title)
        .setMessage(text)
        .setPositiveButton(android.R.string.ok, null)
        .create()
}

fun Fragment.alert(text: String) = requireContext().alert(text)

fun AlertDialog.tryToShow() {
    try {
        val activity = context as Activity
        if (!activity.isFinishing) {
            show()
        }
    } catch (e: Exception) {
        Logs.e(e)
    }
}

/**
 * Wrap a dialog content view with Material-recommended horizontal padding.
 * Bare EditText / TextInputLayout set via setView() otherwise sits flush against
 * dialog edges, which looks especially cramped under original (FilledBox) style.
 */
fun Context.wrapDialogContent(
    content: View,
    horizontalDp: Int = 20,
    verticalDp: Int = 8,
): FrameLayout {
    val h = dp2px(horizontalDp)
    val v = dp2px(verticalDp)
    return FrameLayout(this).apply {
        setPadding(h, v, h, v)
        addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }
}
