package io.nekohasekai.sagernet.bg

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import androidx.annotation.RequiresApi
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import android.service.quicksettings.TileService as BaseTileService

@RequiresApi(24)
class TileService : BaseTileService(), SagerConnection.Callback {
    private val iconIdle by lazy { Icon.createWithResource(this, R.drawable.ic_service_idle) }
    private val iconBusy by lazy { Icon.createWithResource(this, R.drawable.ic_service_busy) }
    private val iconConnected by lazy {
        Icon.createWithResource(this, R.drawable.ic_service_active)
    }
    private var tapPending = false

    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_TILE)
    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) =
        updateTile(state, profileName)

    override fun onServiceConnected(service: ISagerNetService) {
        updateTile(BaseService.State.values()[service.state], service.profileName)
        if (tapPending) {
            tapPending = false
            onClick()
        }
    }

    override fun cbSelectorUpdate(id: Long) {
        runOnDefaultDispatcher {
            val profileName = SagerDatabase.proxyDao.getById(id)?.displayName()
                ?: return@runOnDefaultDispatcher
            runOnMainDispatcher {
                updateTile(BaseService.State.Connected, profileName)
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        connection.connect(this, this)
    }

    override fun onStopListening() {
        connection.disconnect(this)
        super.onStopListening()
    }

    override fun onClick() {
        if (isLocked) unlockAndRun(this::toggle) else toggle()
    }

    private fun updateTile(serviceState: BaseService.State, profileName: String?) {
        // Ported from own/8a483574e: defensive blank / literal "null" filtering, and on
        // Android 14+ the profile name moves to the tile subtitle so the label no longer
        // repeats it. (Their guard used SDK Q; Tile.setSubtitle is API 34, so it is U here.)
        val validProfileName = profileName?.trim()?.takeIf {
            it.isNotEmpty() && !it.equals("null", ignoreCase = true)
        }
        val hasSubtitle = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        qsTile?.apply {
            label = null
            when (serviceState) {
                BaseService.State.Idle -> error("serviceState")
                BaseService.State.Connecting -> {
                    icon = iconBusy
                    state = Tile.STATE_ACTIVE
                }

                BaseService.State.Connected -> {
                    icon = iconConnected
                    label = if (hasSubtitle) getString(R.string.connection_status_connected) else validProfileName
                    state = Tile.STATE_ACTIVE
                }

                BaseService.State.Stopping -> {
                    icon = iconBusy
                    state = Tile.STATE_UNAVAILABLE
                }

                BaseService.State.Stopped -> {
                    icon = iconIdle
                    state = Tile.STATE_INACTIVE
                }
            }
            label = label ?: getString(R.string.app_name)
            if (hasSubtitle) {
                setSubtitle(when (serviceState) {
                    BaseService.State.Connected, BaseService.State.Connecting -> validProfileName
                    BaseService.State.Stopped -> getString(R.string.not_connected)
                    else -> null
                })
            }
            updateTile()
        }
    }

    private fun toggle() {
        val service = connection.service
        if (service == null) {
            tapPending =
                true
        } else {
            BaseService.State.values()[service.state].let { state ->
                when {
                    state.canStop -> SagerNet.stopService()
                    state == BaseService.State.Stopped -> SagerNet.startService()
                }
            }
        }
    }
}
