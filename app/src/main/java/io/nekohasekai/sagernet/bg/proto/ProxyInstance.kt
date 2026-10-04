package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.bg.runRequiredCompletion
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.runBlocking

class ProxyInstance(profile: ProxyEntity, var service: BaseService.Interface? = null) : BoxInstance(profile) {

    internal val tailscaleReadiness = TailscaleReadinessIntent()

    var lastSelectorGroupId = -1L
    var lastAutoSelect = false
    var displayProfileName = ServiceNotification.genTitle(profile)

    // for TrafficLooper
    var looper: TrafficLooper? = null

    override fun buildConfig() {
        super.buildConfig()
        lastSelectorGroupId = super.config.selectorGroupId
        lastAutoSelect = super.config.autoSelect
    }

    override suspend fun init() {
        super.init()
        Logs.d(safeConfigDiagnostics(config, pluginConfigs.size))
    }

    override fun launch() {
        box.setAsMain()
        super.launch() // start box
        // ponytail: fixed cap; make it a setting if anyone needs a longer history.
        box.setConnectionHistory(if (DataStore.connectionDiagnostics) 300 else 0)
        // Assign the looper synchronously so close() always observes it (no
        // launch/close race). GlobalScope matches the previous scope semantics:
        // runOnDefaultDispatcher was GlobalScope.launch(Dispatchers.Default), and
        // TrafficLooper.start() launches its own loop on this scope.
        looper = service?.let { TrafficLooper(it.data, GlobalScope) }
        looper?.start()
    }

    suspend fun closeAndPersist() = runRequiredCompletion(
        after = {
            try {
                looper?.stop()
            } finally {
                looper = null
                tailscaleReadiness.clear()
            }
        },
    ) {
        super.close()
    }

    // Synchronous compatibility path for Closeable callers. Service teardown uses
    // closeAndPersist() through runServiceTeardown instead of blocking its caller.
    override fun close() = runBlocking(Dispatchers.Default) {
        closeAndPersist()
    }
}
