package io.nekohasekai.sagernet.group

import android.app.AlertDialog
import android.content.DialogInterface
import android.os.Looper
import androidx.activity.ComponentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.ref.WeakReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AwaitDialogTest {
    // Dialogs and the code waiting for them run on the test thread, which is Robolectric's main thread.
    private val main = UnconfinedTestDispatcher()
    private var shown: AlertDialog? = null

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun build(activity: ComponentActivity, answer: (String) -> Unit) = AlertDialog.Builder(activity)
        .setMessage("Apply?")
        .setPositiveButton("Yes") { _, _ -> answer("yes") }
        .create()
        .also { shown = it }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun anAnswerResumesTheCaller() = runTest(main) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val result = async { awaitDialog(WeakReference(controller.get()), "dismissed", ::build) }
        assertTrue(shown!!.isShowing)
        shown!!.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals("yes", result.await())
        controller.pause().stop().destroy()
    }

    @Test
    fun destroyingTheActivityResumesTheCallerAndAllowsTheNextDialog() = runTest(main) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val result = async { awaitDialog(WeakReference(controller.get()), "dismissed", ::build) }
        assertTrue(shown!!.isShowing)
        controller.pause().stop().destroy()
        idle()
        assertFalse(shown!!.isShowing)
        assertEquals("dismissed", result.await())

        // A gone activity answers at once instead of waiting for a dialog nobody can see.
        assertEquals("dismissed", awaitDialog(WeakReference(controller.get()), "dismissed", ::build))
        val next = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val second = async { awaitDialog(WeakReference(next.get()), "dismissed", ::build) }
        shown!!.cancel()
        idle()
        assertEquals("dismissed", second.await())
        next.pause().stop().destroy()
    }

    @Test
    fun cancellingTheCallerClosesTheDialog() = runTest(main) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val result = async { awaitDialog(WeakReference(controller.get()), "dismissed", ::build) }
        assertTrue(shown!!.isShowing)
        result.cancel()
        idle()
        assertFalse(shown!!.isShowing)
        controller.pause().stop().destroy()
    }
}
