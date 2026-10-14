package io.nekohasekai.sagernet.bg.proto

// Native queries return and reset deltas. Read each tag once, even when a
// profile appears in several chains or several profiles share one tag.
class TrafficUpdater(
    val items: List<TrafficLooperData>,
    private val query: (String, String) -> Long,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    class TrafficLooperData(
        val tag: String,
        var tx: Long = 0,
        var rx: Long = 0,
        var txRate: Long = 0,
        var rxRate: Long = 0,
    )

    private var lastUpdate = clock()

    fun updateAll() {
        val now = clock()
        val interval = now - lastUpdate
        lastUpdate = now
        val deltas = mutableMapOf<String, Pair<Long, Long>>()
        items.forEach { item ->
            val (tx, rx) = deltas.getOrPut(item.tag) {
                query(item.tag, "uplink") to query(item.tag, "downlink")
            }
            item.tx += tx
            item.rx += rx
            item.txRate = if (interval > 0) tx * 1000 / interval else 0
            item.rxRate = if (interval > 0) rx * 1000 / interval else 0
        }
    }

    companion object {
        fun pollingInterval(speedInterval: Long) = speedInterval.takeIf { it > 0 } ?: 1000L

        // Session totals are supplied separately by the native ingress tracker.
        // Group and leaf counters intentionally overlap and must never be summed
        // into session totals. A profile's shared tag is counted only once.
        fun profileTotals(tags: Collection<TrafficLooperData>): Pair<Long, Long> {
            val unique = tags.distinctBy { it.tag }
            return unique.sumOf { it.tx } to unique.sumOf { it.rx }
        }
    }
}
