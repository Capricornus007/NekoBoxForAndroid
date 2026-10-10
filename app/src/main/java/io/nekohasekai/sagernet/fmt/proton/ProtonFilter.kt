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
    val sort: Sort = Sort.IDLE,
) {
    enum class Sort { IDLE, NAME, COUNTRY, CITY }

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
                        node.city.lowercase().contains(q) ||
                        // 清單上看到的是「洛杉磯」，打字卻要猜 Los Angeles 是強人所難：
                        // 本地化的名字也要能搜到。
                        cityName(node.city).lowercase().contains(q) ||
                        node.country.lowercase().contains(q)
                    )
        }
        return when (sort) {
            // 空閒度（Proton 的 Load 反過來）優先，同分再用 Proton 自己的 Score 拉開
            // （實測 Load=0 那 61 台的 Score 從 2.979 到 5.970 全不重複，正好分得出來）。
            // penalty 只當最後一鍵：實測 /vpn/logicals 對每一台都不回 StatusReference，
            // 也就是 penalty 恆為 0，單靠它等於沒排。
            Sort.IDLE -> matched.sortedWith(compareBy({ it.loadRank }, { it.scoreRank }, { it.penalty }))

            Sort.NAME -> matched.sortedWith(compareBy({ it.name.lowercase() }, { it.loadRank }, { it.scoreRank }))

            // 「按國家／按城市」排節點也要用同一條比較器：否則下拉裡看到的是拼音序，
            // 清單本身卻還是碼位序，同一個名字在兩個地方排兩樣，比不排更難解釋。
            Sort.COUNTRY -> matched.sortedWith(
                byPlaceName<ProtonNode> { countryName(it.countryKey) }
                    .thenComparator { a, b -> comparePlaceNames(cityName(a.city), cityName(b.city)) }
                    .thenBy { it.loadRank }
                    .thenBy { it.scoreRank },
            )

            Sort.CITY -> matched.sortedWith(
                byPlaceName<ProtonNode> { cityName(it.city) }
                    .thenComparator { a, b -> comparePlaceNames(countryName(a.countryKey), countryName(b.countryKey)) }
                    .thenBy { it.loadRank }
                    .thenBy { it.scoreRank },
            )
        }
    }

    companion object {
        const val ALL = ""

        fun countries(nodes: List<ProtonNode>): List<Option> = nodes.groupingBy { it.countryKey }
            .eachCount()
            .map { (key, count) -> Option(key, count, countryName(key)) }
            .sortedWith(placeNameOrder())

        // 城市要跟著國家走，否則選了日本卻列出美國的城市。
        fun cities(nodes: List<ProtonNode>, country: String): List<Option> {
            val want = country.trim().uppercase(Locale.ROOT)
            return nodes.filter { want == ALL || it.countryKey == want }
                .filter { it.city.isNotEmpty() }
                .groupingBy { it.city.lowercase() }
                .eachCount()
                .map { (key, count) ->
                    Option(key, count, cityName(nodes.first { it.city.lowercase() == key }.city))
                }
                .sortedWith(placeNameOrder())
        }

        // 下拉的排序鍵：名稱用上面那條比較器，同名（不該發生，但防呆）再按台數多的排前面。
        private fun placeNameOrder(): Comparator<Option> = byPlaceName<Option> { it.label }.thenByDescending { it.count }

        fun hasCountry(nodes: List<ProtonNode>, key: String) = nodes.any { it.countryKey == key.trim().uppercase(Locale.ROOT) }

        fun hasCity(nodes: List<ProtonNode>, key: String) = nodes.any { it.city.lowercase() == key.trim().lowercase() }
    }

    data class Option(val key: String, val count: Int, val label: String)
}

private val ProtonNode.countryKey: String get() = country.trim().uppercase(Locale.ROOT)

// 按顯示名排序：中文走拼音序、其他語系走大小寫不敏感字母序。抽成一個函式是因為下拉與
// 清單必須共用同一套，否則同一個名字在兩個地方會排出兩種順序。
private fun <T> byPlaceName(key: (T) -> String): Comparator<T> = Comparator { a, b -> comparePlaceNames(key(a), key(b)) }

// 沒有 load 的舊快取要排在最後，而不是被當成「最空」排到第一。
private val ProtonNode.loadRank: Int get() = load.takeIf { it >= 0 } ?: Int.MAX_VALUE

// 同理：沒有 score 的舊快取不能當成 Proton 眼中的最佳機台。
private val ProtonNode.scoreRank: Double get() = if (score >= 0) score else Double.MAX_VALUE

/**
 * 國家碼轉成本機語言的國名（Proton 只給 `ExitCountry` 兩位碼）。
 * 非 ISO 碼或空碼時 `Locale.Builder` 會丟例外，那種情況照原碼顯示，不猜。
 */
fun countryName(code: String): String = runCatching {
    if (code.length != 2) return@runCatching code
    Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.getDefault())
}.getOrNull()?.takeIf { it.isNotEmpty() } ?: code

/**
 * 清單列與匯入後的節點名稱共用同一個拼法，兩邊長得不一樣會讓人對不上號。
 * 刻意不列國家：Proton 的節點名本身就帶國家碼（RW#10、US-FREE#2），城市又已經含國家，
 * 三個都塞進一顆按鈕就會撐成兩行、整頁高度被拉開。城市缺資料時才退回國家碼。
 */
fun ProtonNode.placeLabel(): String {
    val place = cityName(city).ifEmpty { country.takeIf { it.isNotEmpty() }?.let { countryName(it) }.orEmpty() }
    return if (place.isEmpty()) name else "$name $place"
}
