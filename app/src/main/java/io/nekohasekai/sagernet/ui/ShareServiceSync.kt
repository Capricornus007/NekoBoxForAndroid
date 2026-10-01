package io.nekohasekai.sagernet.ui

import androidx.lifecycle.ViewModel
import io.nekohasekai.sagernet.bg.BaseService

/** Retains unapplied sharing changes across configuration changes. Called on the main thread. */
class ShareServiceSync : ViewModel() {
    enum class Action { None, Reload, Launch }

    private var changed = false
    private var startRequested = false
    private var launching = false

    /** Whether [nextAction] can return anything but [Action.None]. */
    val pending get() = changed || startRequested

    /** Record the intent before waiting for its settings write, so recreation cannot lose it. */
    fun onChange(state: BaseService.State, startIfStopped: Boolean, allowAccess: Boolean) {
        changed = true
        startRequested = allowAccess && !state.canStop && (startRequested || startIfStopped)
    }

    /** Observing a start consumes a deferred launch even if recovery never reaches Connected. */
    fun onState(state: BaseService.State) {
        if (state.canStop || (state == BaseService.State.Stopped && launching)) {
            startRequested = false
            launching = false
        }
    }

    /** Call only after pending settings writes have committed. */
    fun nextAction(state: BaseService.State, allowAccess: Boolean): Action = when {
        state.canStop -> {
            startRequested = false
            launching = false
            if (changed) {
                changed = false
                Action.Reload
            } else {
                Action.None
            }
        }

        state == BaseService.State.Stopped && !launching && startRequested -> {
            startRequested = false
            if (allowAccess) {
                changed = false
                launching = true
                Action.Launch
            } else {
                Action.None
            }
        }

        // Keep a disable/rotation pending until a running service reports back. Neither may
        // start a stopped service, but an in-flight start may still be using the previous value.
        else -> Action.None
    }

    fun onLaunchFailed() {
        launching = false
        startRequested = false
    }
}
