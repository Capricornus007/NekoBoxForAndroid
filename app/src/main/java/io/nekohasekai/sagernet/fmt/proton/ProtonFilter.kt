package io.nekohasekai.sagernet.fmt.proton

import java.util.Locale

/**
 * Proton 節點清單的過濾／排序，刻意不碰 Android 型別：1576 條要能即時筛，
 * 也要能在 JVM 單測裡直接驗，不必開 Activity。
 */
data class ProtonFilter(
    val query: String = "",
    val country: String = ALL,
    val city: String = ALL,
    val ipv6Only: Boolean = false,
    val sort: Sort = Sort.BALANCER,
) {
    enum class Sort { BALANCER, NAME, COUNTRY, CITY }

    fun apply(nodes: List<ProtonNode>): List<ProtonNode> {
        val q = query.trim().lowercase()
        // 鍵一律正規化再比：Proton 的 ExitCountry 大小寫不固定，選單的 key 又是從
        // 同一份資料算出來的，任何一處沒對齊就會篩出半份清單。
        val wantCountry = country.trim().uppercase(Locale.ROOT)
        val wantCity = city.trim().lowercase()
        val matched = nodes.filter { node ->
            (wantCountry == ALL || node.countryKey == wantCountry) &&
                (wantCity == ALL || node.city.lowercase() == wantCity) &&
                (!ipv6Only || node.supportsIPv6) &&
                (
                    q.isEmpty() || node.name.lowercase().contains(q) ||
                        node.city.lowercase().contains(q) || node.country.lowercase().contains(q)
                    )
        }
        return when (sort) {
            // Proton 自己的排程分數（越低越空）；這是它客戶端用的同一個排序依據。
            Sort.BALANCER -> matched.sortedBy { it.penalty }

            Sort.NAME -> matched.sortedWith(compareBy({ it.name.lowercase() }, { it.penalty }))

            Sort.COUNTRY -> matched.sortedWith(
                compareBy({ it.countryKey }, { it.city.lowercase() }, { it.penalty }),
            )

            Sort.CITY -> matched.sortedWith(
                compareBy({ it.city.lowercase() }, { it.countryKey }, { it.penalty }),
            )
        }
    }

    companion object {
        const val ALL = ""

        fun countries(nodes: List<ProtonNode>): List<Option> = nodes.groupingBy { it.countryKey }
            .eachCount()
            .map { (key, count) -> Option(key, count, countryName(key)) }
            .sortedWith(compareBy({ it.label }, { -it.count }))

        // 城市要跟著國家走，否則選了日本卻列出美國的城市。
        fun cities(nodes: List<ProtonNode>, country: String): List<Option> {
            val want = country.trim().uppercase(Locale.ROOT)
            return nodes.filter { want == ALL || it.countryKey == want }
                .filter { it.city.isNotEmpty() }
                .groupingBy { it.city.lowercase() }
                .eachCount()
                .map { (key, count) ->
                    Option(key, count, nodes.first { it.city.lowercase() == key }.city)
                }
                .sortedWith(compareBy({ it.label }, { -it.count }))
        }

        fun hasCountry(nodes: List<ProtonNode>, key: String) = nodes.any { it.countryKey == key.trim().uppercase(Locale.ROOT) }

        fun hasCity(nodes: List<ProtonNode>, key: String) = nodes.any { it.city.lowercase() == key.trim().lowercase() }
    }

    data class Option(val key: String, val count: Int, val label: String)
}

private val ProtonNode.countryKey: String get() = country.trim().uppercase(Locale.ROOT)

/**
 * 國家碼轉成本機語言的國名（Proton 只給 `ExitCountry` 兩位碼）。
 * 非 ISO 碼或空碼時 `Locale.Builder` 會丟例外，那種情況照原碼顯示，不猜。
 */
fun countryName(code: String): String = runCatching {
    if (code.length != 2) return@runCatching code
    Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.getDefault())
}.getOrNull()?.takeIf { it.isNotEmpty() } ?: code

/** 清單列與匯入後的節點名稱共用同一個拼法，兩邊長得不一樣會讓人對不上號。 */
fun ProtonNode.placeLabel(): String {
    val place = listOfNotNull(
        city.takeIf { it.isNotEmpty() },
        country.takeIf { it.isNotEmpty() }?.let { countryName(it) },
    ).joinToString(" · ")
    return if (place.isEmpty()) name else "$name   $place"
}
