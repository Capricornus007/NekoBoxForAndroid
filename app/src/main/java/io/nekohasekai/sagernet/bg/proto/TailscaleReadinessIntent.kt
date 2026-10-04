package io.nekohasekai.sagernet.bg.proto

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal class TailscaleReadinessIntent {
    val generation = generations.incrementAndGet()
    private val overrides = ConcurrentHashMap<Long, Boolean>()

    fun get(nodeId: Long, configured: Boolean): Boolean = overrides[nodeId] ?: configured
    fun set(nodeId: Long, enabled: Boolean) {
        overrides[nodeId] = enabled
    }
    fun clear() = overrides.clear()

    companion object {
        private val generations = AtomicLong()
    }
}
