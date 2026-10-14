package io.nekohasekai.sagernet.bg

internal fun subscriptionDelaySeconds(now: Long, lastUpdated: Long, delayMinutes: Int): Long =
    (lastUpdated + maxOf(15L, delayMinutes.toLong()) * 60 - now).coerceAtLeast(0)
