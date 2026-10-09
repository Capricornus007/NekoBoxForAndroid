package io.nekohasekai.sagernet.fmt.proton

import java.io.File

/**
 * 伺服器清單的磁碟快取：退出頁面再進來應該還看得到上一次抓到的東西，而不是空白加一個
 * 「請按重新整理」。存 sidecar 回來的原始 JSON（不是解析後的物件），這樣讀回來的路徑跟
 * 網路路徑是同一支 parseNodes，不會出現兩套解析結果不一致。
 *
 * 時間戳直接用檔案的 lastModified，所以不需要额外的信封格式；代價是備份／還原檔案會把
 * 時間弄成「剛剛」，那種情況寧願顯示錯的時間也比不顯示清單好。
 */
object ProtonNodeCache {

    const val FILE_NAME = "proton_servers.json"

    fun file(cacheDir: File): File = File(cacheDir, FILE_NAME)

    fun save(cacheDir: File, payload: String): Boolean = runCatching {
        val target = file(cacheDir)
        target.writeText(payload)
        target.setLastModified(System.currentTimeMillis())
    }.getOrDefault(false)

    // 只有「解析成功且至少有一條可用節點」才算有效快取；壞檔一律當沒有，
    // 讓呼叫端回到「去網路抓一次」的路徑，而不是顯示一個永遠空著的清單。
    fun load(cacheDir: File): ProtonNodesState? {
        val target = file(cacheDir)
        if (!target.isFile) return null
        val text = runCatching { target.readText() }.getOrNull() ?: return null
        if (text.isBlank()) return null
        return ProtonJson.parseNodes(text).takeIf { it.ok && it.nodes.isNotEmpty() }
    }

    fun savedAt(cacheDir: File): Long = file(cacheDir).lastModified().let { if (it <= 0L) 0L else it }

    fun clear(cacheDir: File) {
        file(cacheDir).delete()
    }

    // sidecar 的 payload 是機器產出的 JSON；寫之前先確認它是一個物件，避免把
    // 錯誤訊息或截斷的輸出當成「可用的快取」留下來。判法直接沿用 ProtonJson 那一条，
    // 兩套標準遲早會不一致。
    fun looksLikePayload(text: String): Boolean = ProtonJson.looksLikeJson(text)
}
