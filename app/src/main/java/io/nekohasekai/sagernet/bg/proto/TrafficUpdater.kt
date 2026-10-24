package io.nekohasekai.sagernet.bg.proto

import android.os.SystemClock
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeoutOrNull

// isai 併入後的統一版：兩條活路徑共用一個類——
//   · fork 主路徑（TrafficLooper:315）：主構造吃 libcore.BoxInstance，
//     updateOne 走 box.queryStats（單調時鐘按 item 記 lastUpdate）。
//   · isai 診斷/測試路徑：次構造吃注入的 query lambda 與 clock
//     （lambda 自己分派 session/direct/queryStats），共用同一套
//     資料類與按 tag 去重的 updateAll 語意。
// TrafficLooperData 保留 fork 的完整欄位集（lifetimeFlushed*/rxBase/txBase/
// hasTrafficDelta/ignore——TrafficLooper 的終身流量冪等累加依賴它們）。
class TrafficUpdater(
    private val box: libcore.BoxInstance?,
    val items: List<TrafficLooperData>, // contain "bypass"
    private val queryOverride: ((String, String) -> Long)? = null,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {

    // isai 語意：上一輪時鐘取樣，構造當下初始化（首輪 interval=0）。
    private var lastClock: Long = clock()

    // isai 路徑構造：不持 BoxInstance，注入查詢與時鐘（單元測試用注入 clock 取 interval）。
    constructor(
        items: List<TrafficLooperData>,
        query: (String, String) -> Long,
        clock: () -> Long = { System.nanoTime() / 1_000_000 },
    ) : this(null, items, query, clock)

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

    private fun updateOne(item: TrafficLooperData, calculateRates: Boolean = true): TrafficLooperData {
        // last update
        // 用單調時鐘：NTP 校正、時區/DST 跳動或使用者手動改系統時間都會讓牆鐘倒退或暴增，
        // 那會讓 interval 變成負數或巨大值，速度顯示跟著歸零或跳出天文數字。
        // isai：時鐘可注入（fork 主路徑預設 SystemClock 單調時鐘，行為不變）。
        // isai 語意：updater 構造當下即起算——首輪輪詢沒有 prior 對照，interval=0，
        // 只累計 delta、不計速率；其後以 item.lastUpdate / lastClock 較大者為基準。
        val now = clock()
        val base = maxOf(item.lastUpdate, lastClock)
        val interval = now - base
        item.lastUpdate = now
        lastClock = now

        // query（fork 主路徑走 box；isai 路徑走注入的 lambda）。
        // interval<=0（同一 tick 內第二次輪詢）仍要讀：原生計數器是「讀即重置」，
        // 跳過讀取會讓 delta 滯留到下一輪，速率瞬間暴衝；此時只壓速率不壓總量。
        val tx: Long
        val rx: Long
        if (queryOverride != null) {
            tx = queryOverride.invoke(item.tag, "uplink")
            rx = queryOverride.invoke(item.tag, "downlink")
        } else {
            tx = box!!.queryStats(item.tag, "uplink")
            rx = box!!.queryStats(item.tag, "downlink")
        }

        // add diff
        item.rx += rx
        item.tx += tx
        // isai：鎖屏暫停期間只讀總量不計速率（calculateRates=false → 速率歸零）。
        item.rxRate = if (calculateRates && interval > 0) rx * 1000 / interval else 0
        item.txRate = if (calculateRates && interval > 0) tx * 1000 / interval else 0

        // return diff
        return TrafficLooperData(
            tag = item.tag,
            rx = rx,
            tx = tx,
            rxRate = item.rxRate,
            txRate = item.txRate,
        )
    }

    fun updateAll(calculateRates: Boolean = true) {
        val updated = mutableMapOf<String, TrafficLooperData>() // diffs
        items.forEach { item ->
            item.hasTrafficDelta = false
            if (item.ignore) return@forEach
            val diff = updated[item.tag]
            // query a tag only once
            if (diff == null) {
                val newDiff = updateOne(item, calculateRates)
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
    }

    companion object {
        // isai：速度模式關閉時仍以 1s 輪詢總量（通知列的累計流量依賴它）。
        fun pollingInterval(speedInterval: Long): Long = speedInterval.takeIf { it > 0 } ?: 1000L

        // isai：The native counters accumulate until read; screen-off needs no polling timer.
        // 鎖屏/省電限制中不讀計數器，等電源狀態變化（或逾時後再查一次）才返回；
        // 返回 true 表示「曾受限」，呼叫端據此以 calculateRates=false 補一輪。
        internal suspend fun awaitUpdate(
            speedInterval: Long,
            powerRestricted: () -> Boolean,
            powerChanged: ReceiveChannel<Unit>,
        ): Boolean {
            var resumed = false
            do {
                if (powerRestricted()) {
                    resumed = true
                    powerChanged.receive()
                } else {
                    withTimeoutOrNull(pollingInterval(speedInterval)) { powerChanged.receive() }
                }
            } while (powerRestricted())
            return resumed
        }

        // isai：會話總量由原生 ingress tracker 另供；group 與 leaf 計數刻意重疊，
        // 絕不能加總進會話總量。profile 的共享 tag 只計一次。
        fun profileTotals(tags: Collection<TrafficLooperData>): Pair<Long, Long> {
            val unique = tags.distinctBy { it.tag }
            return unique.sumOf { it.tx } to unique.sumOf { it.rx }
        }
    }
}
