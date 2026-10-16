package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

class TrafficLooper(val data: BaseService.Data) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var updater: TrafficUpdater? = null
    private val powerChanged = Channel<Unit>(Channel.CONFLATED)
    fun onPowerChanged() { powerChanged.trySend(Unit) }
    private val profiles = mutableMapOf<Long, ProxyEntity>()
    private val profileTags = mutableMapOf<Long, MutableList<TrafficUpdater.TrafficLooperData>>()
    private val bases = mutableMapOf<Long, Pair<Long, Long>>()
    private val session = TrafficUpdater.TrafficLooperData("session-proxy")
    private val direct = TrafficUpdater.TrafficLooperData("session-direct")

    fun start() {
        val proxy = data.proxy ?: return
        val tags = proxy.config.trafficMap.mapValues { (tag, entities) ->
            TrafficUpdater.TrafficLooperData(tag).also { item ->
                entities.distinctBy { it.id }.forEach { entity ->
                    profiles.putIfAbsent(entity.id, entity)
                    bases.putIfAbsent(entity.id, entity.tx to entity.rx)
                    profileTags.getOrPut(entity.id) { mutableListOf() }.add(item)
                }
            }
        }
        updater = TrafficUpdater(tags.values.toList() + session + direct, { tag, direction ->
            when (tag) {
                session.tag -> proxy.box.querySessionStats(false, direction)
                direct.tag -> proxy.box.querySessionStats(true, direction)
                else -> proxy.box.queryStats(tag, direction)
            }
        })
        job = scope.launch { loop() }
    }

    private fun traffic(): List<TrafficData> = profileTags.map { (id, tags) ->
        val (tx, rx) = TrafficUpdater.profileTotals(tags)
        val (txBase, rxBase) = bases.getValue(id)
        TrafficData(id = id, tx = txBase + tx, rx = rxBase + rx)
    }

    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        scope.cancel()
        // Flush the final native deltas while the box is still alive.
        updater?.updateAll()
        if (!DataStore.profileTrafficStatistics) return
        val finalTraffic = traffic()
        finalTraffic.forEach { item ->
            profiles[item.id]?.apply { tx = item.tx; rx = item.rx }
            ProfileManager.updateTraffic(item.id, item.rx, item.tx)
        }
        data.binder.broadcast { binder -> finalTraffic.forEach(binder::cbTrafficUpdate) }
    }

    private suspend fun loop() {
        val speedInterval = DataStore.speedInterval.toLong()
        while (currentCoroutineContext().isActive) {
            withTimeoutOrNull(TrafficUpdater.pollingInterval(speedInterval, SagerNet.power.isInteractive)) { powerChanged.receive() }
            updater?.updateAll()
            val showSpeed = speedInterval > 0
            val speed = SpeedDisplayData(
                if (showSpeed) session.txRate else 0,
                if (showSpeed) session.rxRate else 0,
                if (showSpeed && DataStore.showDirectSpeed) direct.txRate else 0,
                if (showSpeed && DataStore.showDirectSpeed) direct.rxRate else 0,
                session.tx,
                session.rx,
            )
            if (data.state == BaseService.State.Connected &&
                data.binder.callbackIdMap.containsValue(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
            ) {
                val profileTraffic = if (DataStore.profileTrafficStatistics) traffic() else emptyList()
                data.binder.broadcast { binder ->
                    if (data.binder.callbackIdMap[binder] == SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND) {
                        binder.cbSpeedUpdate(speed)
                        profileTraffic.forEach(binder::cbTrafficUpdate)
                    }
                }
            }
            data.notification?.apply { if (listenPostSpeed) postNotificationSpeedUpdate(speed) }
        }
    }
}
