package io.nekohasekai.sagernet.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import io.nekohasekai.sagernet.bg.BaseService.State
import io.nekohasekai.sagernet.ui.ShareServiceSync.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ShareServiceSyncTest {
    private val sync = ShareServiceSync()

    private fun change(state: State, startIfStopped: Boolean, allowAccess: Boolean): Action {
        sync.onChange(state, startIfStopped, allowAccess)
        return sync.nextAction(state, allowAccess)
    }

    private fun report(state: State, allowAccess: Boolean): Action {
        sync.onState(state)
        return sync.nextAction(state, allowAccess)
    }

    private fun toggleOn(state: State) = change(state, startIfStopped = true, allowAccess = true)
    private fun toggleOff(state: State) = change(state, startIfStopped = false, allowAccess = false)
    private fun regenerate(state: State) = change(state, startIfStopped = false, allowAccess = true)

    @Test
    fun runningService_reloads() {
        assertEquals(Action.Reload, toggleOn(State.Connected))
        assertEquals(Action.Reload, regenerate(State.Connecting))
    }

    @Test
    fun stoppedService_launchesOnceAndReloadsOnlyWhenChangedSinceLaunch() {
        assertEquals(Action.Launch, toggleOn(State.Stopped))
        assertEquals(Action.None, report(State.Connecting, allowAccess = true))
        assertEquals(Action.None, report(State.Connected, allowAccess = true))

        assertEquals(Action.None, toggleOff(State.Stopped))
        assertEquals(Action.Launch, toggleOn(State.Stopped))
        assertEquals(Action.None, regenerate(State.Stopped))
        assertEquals(Action.Reload, report(State.Connecting, allowAccess = true))
        assertEquals(Action.None, report(State.Connected, allowAccess = true))
    }

    @Test
    fun reloadWhileConnecting_needsNoSecondReloadOnConnected() {
        assertEquals(Action.Launch, toggleOn(State.Stopped))
        assertEquals(Action.Reload, regenerate(State.Connecting))
        assertEquals(Action.None, report(State.Connected, allowAccess = true))
    }

    @Test
    fun failedStart_isNotRetriedAndNextToggleLaunchesAgain() {
        assertEquals(Action.Launch, toggleOn(State.Stopped))
        assertEquals(Action.None, report(State.Stopped, allowAccess = true))
        assertEquals(Action.None, toggleOff(State.Stopped))
        assertEquals(Action.Launch, toggleOn(State.Stopped))
    }

    @Test
    fun deniedPermission_letsNextToggleLaunchAgain() {
        assertEquals(Action.Launch, toggleOn(State.Stopped))
        sync.onLaunchFailed()
        assertEquals(Action.None, report(State.Stopped, allowAccess = true))
        assertEquals(Action.None, toggleOff(State.Stopped))
        assertEquals(Action.Launch, toggleOn(State.Stopped))
    }

    @Test
    fun toggleDuringShutdown_launchesOnceAtStopped() {
        assertEquals(Action.None, toggleOn(State.Stopping))
        assertEquals(Action.Launch, report(State.Stopped, allowAccess = true))
        assertEquals(Action.None, report(State.Stopped, allowAccess = true))
    }

    @Test
    fun toggleDuringShutdown_serviceComesBackOnItsOwn_reloadsOnceAndLaterStopSticks() {
        assertEquals(Action.None, toggleOn(State.Stopping))
        assertEquals(Action.Reload, report(State.Connecting, allowAccess = true))
        assertEquals(Action.None, report(State.Connected, allowAccess = true))
        assertEquals(Action.None, report(State.Stopped, allowAccess = true))
    }

    @Test
    fun stopDuringKillSwitchRecovery_doesNotLaunchEvenBeforeWritesDrain() {
        sync.onChange(State.Stopping, startIfStopped = true, allowAccess = true)
        sync.onState(State.Connecting)
        sync.onState(State.Stopping)
        assertEquals(Action.None, report(State.Stopped, allowAccess = true))
    }

    @Test
    fun changeWhileConnecting_doesNotOverrideAnExplicitStop() {
        sync.onChange(State.Connecting, startIfStopped = true, allowAccess = true)
        sync.onState(State.Stopping)
        assertEquals(Action.None, report(State.Stopped, allowAccess = true))
    }

    @Test
    fun toggleBeforeFirstReport_resolvesOnIt() {
        assertEquals(Action.None, toggleOn(State.Idle))
        assertEquals(Action.Reload, report(State.Connected, allowAccess = true))

        assertEquals(Action.None, toggleOn(State.Idle))
        assertEquals(Action.Launch, report(State.Stopped, allowAccess = true))
    }

    @Test
    fun disableOrRotateBeforeBinding_reloadsButNeverStarts() {
        for (allowAccess in listOf(false, true)) {
            val pending = ShareServiceSync()
            pending.onChange(State.Idle, startIfStopped = false, allowAccess = allowAccess)
            assertEquals(Action.None, pending.nextAction(State.Idle, allowAccess))
            pending.onState(State.Stopped)
            assertEquals(Action.None, pending.nextAction(State.Stopped, allowAccess))
            pending.onState(State.Connected)
            assertEquals(Action.Reload, pending.nextAction(State.Connected, allowAccess))
            assertEquals(Action.None, pending.nextAction(State.Connected, allowAccess))
        }
    }

    @Test
    fun toggleDuringShutdownThenOff_doesNotLaunch() {
        assertEquals(Action.None, toggleOn(State.Stopping))
        assertEquals(Action.None, toggleOff(State.Stopping))
        assertEquals(Action.None, report(State.Stopped, allowAccess = false))
    }

    @Test
    fun recreation_preservesDeferredStartAndUnflushedRevocation() {
        for (start in listOf(false, true)) {
            Robolectric.buildActivity(SyncHostActivity::class.java).setup().use { controller ->
                val previous = controller.get()
                val pending = previous.sync
                pending.onChange(State.Stopping, startIfStopped = start, allowAccess = start)
                controller.recreate()
                val recreated = controller.get()
                assertNotSame(previous, recreated)
                assertSame(pending, recreated.sync)
                val state = if (start) State.Stopped else State.Connected
                recreated.sync.onState(state)
                assertEquals(if (start) Action.Launch else Action.Reload, recreated.sync.nextAction(state, start))
                assertEquals(Action.None, recreated.sync.nextAction(state, start))
            }
        }
    }

    class SyncHostActivity : ComponentActivity() {
        val sync by viewModels<ShareServiceSync>()
    }
}
