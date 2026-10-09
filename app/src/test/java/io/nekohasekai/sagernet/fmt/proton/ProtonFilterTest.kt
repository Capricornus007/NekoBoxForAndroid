package io.nekohasekai.sagernet.fmt.proton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proton 頁的篩選是純資料操作，放在 JVM 單測裡釘住：頁面上那些下拉選單一旦
 * 悄悄改了語意（例如「選了日本還列出美國的城市」），這裡會先紅。
 */
class ProtonFilterTest {

    private fun node(
        id: String,
        name: String,
        country: String,
        city: String,
        penalty: Double = 0.5,
        ipv6: Boolean = false,
    ) = ProtonNode(
        id = id,
        name = name,
        penalty = penalty,
        tier = 2,
        supportsIPv6 = ipv6,
        country = country,
        city = city,
        endpoint = "10.0.0.$id",
        publicKey = "key-$id",
        port = 51820,
    )

    private val nodes = listOf(
        node("1", "JP-FREE#1", "jp", "Tokyo", penalty = 0.9),
        node("2", "US-PLUS#7", "US", "Los Angeles", penalty = 0.1, ipv6 = true),
        node("3", "JP-PLUS#2", "JP", "Osaka", penalty = 0.4),
        node("4", "US-FREE#2", "us", "Los Angeles", penalty = 0.2),
    )

    private fun ids(list: List<ProtonNode>) = list.map { it.id }

    @Test
    fun `default filter keeps everything and orders by Proton's own penalty`() {
        assertEquals(listOf("2", "4", "3", "1"), ids(ProtonFilter().apply(nodes)))
    }

    @Test
    fun `country codes are matched regardless of how Proton cased them`() {
        // 同一個國家在回應裡可能是 "JP"、"jp"、"JP"，不能因為大小寫漏掉一半節點。
        assertEquals(listOf("3", "1"), ids(ProtonFilter(country = "JP").apply(nodes)))
        assertEquals(listOf("3", "1"), ids(ProtonFilter(country = "jp").apply(nodes)))
    }

    @Test
    fun `city filter is matched case-insensitively against the same label the list shows`() {
        assertEquals(listOf("2", "4"), ids(ProtonFilter(city = "los angeles").apply(nodes)))
    }

    @Test
    fun `ipv6 only keeps the nodes the sidecar flagged`() {
        assertEquals(listOf("2"), ids(ProtonFilter(ipv6Only = true).apply(nodes)))
    }

    @Test
    fun `text query matches name, city and country`() {
        assertEquals(listOf("1"), ids(ProtonFilter(query = "FREE#1").apply(nodes)))
        assertEquals(listOf("2", "4"), ids(ProtonFilter(query = "angeles").apply(nodes)))
        assertEquals(listOf("3", "1"), ids(ProtonFilter(query = " jp ").apply(nodes)))
    }

    @Test
    fun `filters combine instead of overwriting each other`() {
        val filter = ProtonFilter(query = "PLUS", country = "US", ipv6Only = true)
        assertEquals(listOf("2"), ids(filter.apply(nodes)))
    }

    @Test
    fun `sorting by name, country and city is stable and never leaves the filtered set`() {
        // jp-free#1 < jp-plus#2 < us-free#2 < us-plus#7
        assertEquals(
            listOf("1", "3", "4", "2"),
            ids(ProtonFilter(sort = ProtonFilter.Sort.NAME).apply(nodes)),
        )
        // 國家碼先分組（JP 群再依城市：Osaka < Tokyo），US 群同城市则回到 penalty 順序
        assertEquals(
            listOf("3", "1", "2", "4"),
            ids(ProtonFilter(sort = ProtonFilter.Sort.COUNTRY).apply(nodes)),
        )
        assertEquals(
            listOf("2", "4", "3", "1"),
            ids(ProtonFilter(sort = ProtonFilter.Sort.CITY).apply(nodes)),
        )
    }

    @Test
    fun `country options carry real counts and are not scoped by the country already picked`() {
        val options = ProtonFilter.countries(nodes)
        assertEquals(setOf("JP", "US"), options.map { it.key }.toSet())
        assertEquals(listOf(2, 2), options.map { it.count })
    }

    @Test
    fun `city options follow the picked country`() {
        assertEquals(listOf("los angeles"), ProtonFilter.cities(nodes, "US").map { it.key })
        assertEquals(listOf("osaka", "tokyo"), ProtonFilter.cities(nodes, "JP").map { it.key })
        // 沒選國家時城市是全清單的去重結果（Los Angeles 有兩台，只列一次）。
        assertEquals(3, ProtonFilter.cities(nodes, ProtonFilter.ALL).size)
    }

    @Test
    fun `an unknown country or empty list filters to nothing rather than everything`() {
        assertTrue(ProtonFilter(country = "TW").apply(nodes).isEmpty())
        assertTrue(ProtonFilter().apply(emptyList()).isEmpty())
    }
}
