package io.nekohasekai.sagernet.bg.proto

import android.os.SystemClock

class TrafficUpdater(
    private val box: libcore.BoxInstance,
    val items: List<TrafficLooperData>, // contain "bypass"
) {

    class TrafficLooperData(
        // Don't associate proxyEntity
        var tag: String,
        var tx: Long = 0,
        var rx: Long = 0,
        var txBase: Long = 0,
        var rxBase: Long = 0,
        var txRate: Long = 0,
        var rxRate: Long = 0,
        var lastUpdate: Long = 0,
        var ignore: Boolean = false,
        var hasTrafficDelta: Boolean = false,
        // How much of THIS session's cumulative delta (rx - rxBase / tx - txBase) has already
        // been added to the persistent lifetime columns. Advancing it on every flush makes
        // lifetime accumulation idempotent across re-entrant persist()/applySelect calls.
        var lifetimeFlushedRx: Long = 0,
        var lifetimeFlushedTx: Long = 0,
    )

    private fun updateOne(item: TrafficLooperData): TrafficLooperData {
        // last update
        // 用單調時鐘：NTP 校正、時區/DST 跳動或使用者手動改系統時間都會讓牆鐘倒退或暴增，
        // 那會讓 interval 變成負數或巨大值，速度顯示跟著歸零或跳出天文數字。
        val now = SystemClock.elapsedRealtime()
        val interval = now - item.lastUpdate
        item.lastUpdate = now
        if (interval <= 0) {
            return item.apply {
                rxRate = 0
                txRate = 0
            }
        }

        // query
        val tx = box.queryStats(item.tag, "uplink")
        val rx = box.queryStats(item.tag, "downlink")

        // add diff
        item.rx += rx
        item.tx += tx
        item.rxRate = rx * 1000 / interval
        item.txRate = tx * 1000 / interval

        // return diff
        return TrafficLooperData(
            tag = item.tag,
            rx = rx,
            tx = tx,
            rxRate = item.rxRate,
            txRate = item.txRate,
        )
    }

    fun updateAll() {
        val updated = mutableMapOf<String, TrafficLooperData>() // diffs
        items.forEach { item ->
            item.hasTrafficDelta = false
            if (item.ignore) return@forEach
            val diff = updated[item.tag]
            // query a tag only once
            if (diff == null) {
                val newDiff = updateOne(item)
                updated[item.tag] = newDiff
                item.hasTrafficDelta = newDiff.rx != 0L || newDiff.tx != 0L
            } else {
                item.rx += diff.rx
                item.tx += diff.tx
                item.rxRate = diff.rxRate
                item.txRate = diff.txRate
                item.hasTrafficDelta = diff.rx != 0L || diff.tx != 0L
            }
        }
//        Logs.d(JavaUtil.gson.toJson(items))
//        Logs.d(JavaUtil.gson.toJson(updated))
    }
}
