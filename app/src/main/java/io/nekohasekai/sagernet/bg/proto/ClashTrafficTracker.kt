package io.nekohasekai.sagernet.bg.proto

import android.os.SystemClock
import io.nekohasekai.sagernet.ktx.Logs
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * eBPF 模式的 core 是 root 外置進程，拿不到 in-process libcore 的 per-tag 計數器，
 * 只能輪詢它自己開的 Clash API。/connections 只列出「還活著」的連線，連線一關掉就整筆
 * 從清單消失，所以每條連線自己記住最後樣本、消失時結轉進該 tag 的總量；不然短連線
 * （DNS 查詢、快取命中的一次性請求）的位元組會全部不見，通知欄的累計流量就會偏小。
 *
 * 交還給 TrafficUpdater 的是「距上次讀取之後的增量」，語意對齊 in-process 那條
 * box.queryStats（讀即重置），TrafficLooper 那边的累計與速率算式不用改。
 */
class ClashTrafficTracker(
    private val port: Int,
    private val secret: () -> String,
    private val snapshotTtlMs: Long = 150L,
) {

    private class Sample(var tag: String, var up: Long, var down: Long)

    private val settled = HashMap<String, LongArray>()
    private val perTag = HashMap<String, LongArray>()
    private val live = HashMap<String, Sample>()
    private val returned = HashMap<String, LongArray>()
    private var snapshotAt = 0L

    @Synchronized
    fun queryStats(tag: String, direct: String): Long {
        val index = if (direct == DOWNLINK) 1 else 0
        refresh()
        val total = perTag[tag]?.get(index) ?: 0L
        val seen = returned.getOrPut(tag) { LongArray(2) }
        val delta = total - seen[index]
        seen[index] = total
        return if (delta > 0L) delta else 0L
    }

    /** 停止連線時把該次會話還沒結轉的活連線併入總量，讓最後一次 persist 拿到完整數字。 */
    @Synchronized
    fun flush() {
        settleMissing(emptySet())
        rebuildPerTag()
        snapshotAt = SystemClock.elapsedRealtime()
    }

    @Synchronized
    fun reset() {
        settled.clear()
        perTag.clear()
        live.clear()
        returned.clear()
        snapshotAt = 0L
    }

    private fun refresh() {
        val now = SystemClock.elapsedRealtime()
        if (now - snapshotAt < snapshotTtlMs) return
        snapshotAt = now
        val connections = fetchConnections() ?: return
        val alive = HashSet<String>(connections.length())
        for (i in 0 until connections.length()) {
            val entry = connections.optJSONObject(i) ?: continue
            val id = entry.optString("id")
            if (id.isEmpty()) continue
            val tag = entry.optJSONArray("chains").let { chain ->
                // chain 由路由依「群組→成員」順序建立，最後一個才是真正承載位元組的出口。
                if (chain == null || chain.length() == 0) null else chain.optString(chain.length() - 1)
            } ?: continue
            val up = entry.optLong("upload")
            val down = entry.optLong("download")
            val previous = live[id]
            if (previous == null) {
                live[id] = Sample(tag, up, down)
            } else {
                previous.up = maxOf(previous.up, up)
                previous.down = maxOf(previous.down, down)
            }
            alive.add(id)
        }
        settleMissing(alive)
        rebuildPerTag()
    }

    private fun settleMissing(alive: Set<String>) {
        val iterator = live.entries.iterator()
        while (iterator.hasNext()) {
            val (id, sample) = iterator.next()
            if (id in alive) continue
            val total = settled.getOrPut(sample.tag) { LongArray(2) }
            total[0] += sample.up
            total[1] += sample.down
            iterator.remove()
        }
    }

    private fun rebuildPerTag() {
        perTag.clear()
        settled.forEach { (tag, values) ->
            perTag.getOrPut(tag) { LongArray(2) }.let { target ->
                target[0] += values[0]
                target[1] += values[1]
            }
        }
        live.values.forEach { sample ->
            perTag.getOrPut(sample.tag) { LongArray(2) }.let { target ->
                target[0] += sample.up
                target[1] += sample.down
            }
        }
    }

    private fun fetchConnections(): JSONArray? {
        val conn = try {
            URL("http://127.0.0.1:$port/connections").openConnection() as HttpURLConnection
        } catch (e: Exception) {
            Logs.d("ClashTrafficTracker: bad url: ${e.message}")
            return null
        }
        return try {
            conn.connectTimeout = REQUEST_TIMEOUT_MS
            conn.readTimeout = REQUEST_TIMEOUT_MS
            conn.requestMethod = "GET"
            val token = secret()
            if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body).optJSONArray("connections")
        } catch (e: Exception) {
            // 核心剛起、正在重啟或已被收掉都會走到這裡；輪詢不是關鍵路徑，靜音處理，
            // 下一輪自然會再試（把錯誤壓成一行 log，避免每 250ms 刷滿 logcat）。
            Logs.d("ClashTrafficTracker: query failed: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val DOWNLINK = "downlink"
        private const val REQUEST_TIMEOUT_MS = 1000
    }
}
