package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import io.nekohasekai.sagernet.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TailscaleLoginConfirmationTest {
    @Test
    fun httpCancelAndDismissNeverOpenAndOnlyOriginIsDisplayed() = withContext { context ->
        for (cancelButton in listOf(true, false)) {
            var opens = 0
            val link = TailscaleLoginLink.parse("http://login.example.test:8080/private-path?token=secret#fragment")!!
            val dialog = confirmTailscaleLogin(context, link) { opens++ }!!
            try {
                val message = dialog.findViewById<TextView>(android.R.id.message)!!.text.toString()
                assertTrue(message.contains(link.origin))
                assertTrue(message.contains(context.getString(R.string.tailscale_login_http_warning)))
                assertFalse(message.contains("private-path"))
                assertFalse(message.contains("secret"))
                assertFalse(message.contains("fragment"))
                assertEquals(0, opens)
                if (cancelButton) dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick() else dialog.cancel()
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(0, opens)
            } finally {
                dialog.dismiss()
            }
        }
    }

    @Test
    fun httpContinueOpensExactlyOnceAfterConfirmation() = withContext { context ->
        var opens = 0
        val dialog = confirmTailscaleLogin(context, TailscaleLoginLink.parse("http://login.example.test/login")!!) { opens++ }!!
        try {
            assertEquals(0, opens)
            assertEquals(context.getString(R.string.tailscale_login_continue), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, opens)
        } finally {
            dialog.dismiss()
        }
    }

    @Test
    fun httpsKeepsDirectExplicitOpenWithoutNewConfirmation() = withContext { context ->
        var opens = 0
        assertNull(confirmTailscaleLogin(context, TailscaleLoginLink.parse("https://login.example.test/login")!!) { opens++ })
        assertEquals(1, opens)
    }

    private fun withContext(action: (Context) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            action(ContextThemeWrapper(controller.get(), R.style.Theme_SagerNet))
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
