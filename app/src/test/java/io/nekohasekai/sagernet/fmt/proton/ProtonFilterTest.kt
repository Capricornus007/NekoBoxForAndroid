package io.nekohasekai.sagernet.fmt.proton

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * Proton 頁的篩選是純資料操作，放在 JVM 單測裡釘住：頁面上那些下拉選單一旦
 * 悄悄改了語意（例如「選了日本還列出美國的城市」），這裡會先紅。
 */
class ProtonFilterTest {

    private lateinit var savedLocale: Locale

    // 城市排序現在吃 cityName()，而它看預設語系。這台機器的系統語系是中文，不釘死
    // 的話「按城市排序」會变成中文筆畫序，斷言就變成跟著環境飄。
    @Before
    fun pinLocale() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(savedLocale)
    }

    // 拼音序要用實物釘，不能假設：Collator 的 zh 規則在 JVM 與 Android 上實作不同。
    // 四個國家挑「阿、比、德、日」開頭：拼音是 a < bi < de < ri，碼位卻是 德(U+5FB0) <
    // 日(U+65E5) < 比(U+6BD4) < 阿(U+963F)，剛好完全相反，照碼位排一定紅。
    // 這四個字繁簡同形，是因为 JDK 內建的 zh 表對「只有繁體才有」的字（愛、韓）沒收拼音權重，
    // 用它们當斷言會在 JVM 假紅——但 Android 用的是 ICU，繁體一樣走拼音，所以繁體那一條
    // 另外在實機驗。斷言用兩位碼而不是譯名，避免被 CLDR 版本的字串差異假紅。
    @Test
    fun countriesFollowPinyinInChineseLocales() {
        val saved = Locale.getDefault()
        try {
            val places = listOf(
                node("a1", "JP#1", "JP", "Tokyo"),
                node("a2", "DE#1", "DE", "Berlin"),
                node("a3", "BE#1", "BE", "Brussels"),
                node("a4", "AL#1", "AL", "Tirana"),
            )
            val expected = listOf("AL", "BE", "DE", "JP")
            for (tag in listOf("zh-TW", "zh-CN")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                val options = ProtonFilter.countries(places)
                assertEquals("tag=$tag keys", expected, options.map { it.key })
                // 順帶確認這個語系下真的有譯名：國名若還是兩位大寫碼，上面那條就會退化成
                // 排英文、測不到拼音。（不能只比長度，「日本」本身就只有兩格。）
                val labels = options.map { it.label }
                assertTrue("tag=$tag labels=$labels", labels.none { it.matches(Regex("^[A-Z]{2}$")) })
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    // 非中文語系不能被他牽動：英文下還是字母序。
    @Test
    fun countriesStayAlphabeticOutsideChinese() {
        val places = listOf(
            node("b1", "IE#1", "IE", "Dublin"),
            node("b2", "AL#1", "AL", "Tirana"),
            node("b3", "BE#1", "BE", "Brussels"),
        )
        assertEquals(listOf("AL", "BE", "IE"), ProtonFilter.countries(places).map { it.key })
    }

    // 下拉選完之後，控件會拿條目的 toString() 回填輸入框。data class 的預設
    // toString 是 `Option(key=…, count=…, label=…)`，實測城市那格就顯示成
    // `label=倫敦)`（前面被欄位寬度截掉）。這條釘住：toString 必須就是顯示名。
    @Test
    fun optionToStringIsTheDisplayLabel() {
        val option = ProtonFilter.Option("london", 8, "倫敦")
        assertEquals("倫敦", option.toString())
    }

    private fun node(
        id: String,
        name: String,
        country: String,
        city: String,
        penalty: Double = 0.5,
        load: Int = -1,
        score: Double = -1.0,
        ipv6: Boolean = false,
    ) = ProtonNode(
        id = id,
        name = name,
        penalty = penalty,
        load = load,
        score = score,
        tier = 2,
        supportsIPv6 = ipv6,
        country = country,
        city = city,
        endpoint = "10.0.0.$id",
        publicKey = "key-$id",
        port = 51820,
    )

    // 負載與 penalty 刻意排成相反的兩組：預設排序若偷偷退回去吃 penalty，這裡會紅。
    private val nodes = listOf(
        node("1", "JP-FREE#1", "jp", "Tokyo", penalty = 0.9, load = 20),
        node("2", "US-PLUS#7", "US", "Los Angeles", penalty = 0.1, load = 80, ipv6 = true),
        node("3", "JP-PLUS#2", "JP", "Osaka", penalty = 0.4, load = 55),
        node("4", "US-FREE#2", "us", "Los Angeles", penalty = 0.2, load = 80),
    )

    private fun ids(list: List<ProtonNode>) = list.map { it.id }

    @Test
    fun `default filter keeps everything and orders by idle capacity, not penalty`() {
        assertEquals(listOf("1", "3", "2", "4"), ids(ProtonFilter().apply(nodes)))
    }

    @Test
    fun `a node without a load number sorts last instead of looking perfectly idle`() {
        // 舊快取沒有 load（-1）。當成 0 會把這台排到最前面，等於叫用戶先挑到資訊最少的一台。
        val withUnknown = nodes + node("5", "OLD-CACHE#1", "JP", "Fukuoka", load = -1)
        assertEquals(listOf("1", "3", "2", "4", "5"), ids(ProtonFilter().apply(withUnknown)))
    }

    @Test
    fun `servers with the same idle capacity are separated by Proton's own score`() {
        // 實測 Load=0 那 61 台的 Score 從 2.979 到 5.970 全不重複，所以同空閒度時
        // 這個欄位分得出高低；Proton 自己的客戶端就是按它把最好的排最前。
        val same = listOf(
            node("a", "UG#1", "UG", "Kampala", load = 0, score = 2.99),
            node("b", "UG#3", "UG", "Kampala", load = 0, score = 2.98),
            node("c", "UG#9", "UG", "Kampala", load = 0, score = 5.97),
            // 舊快取沒有 score：不能當成 2.98 那種「最好」，要排在有分數的後面。
            node("d", "UG#0", "UG", "Kampala", load = 0),
        )
        assertEquals(listOf("b", "a", "c", "d"), ids(ProtonFilter().apply(same)))
    }

    @Test
    fun `country codes are matched regardless of how Proton cased them`() {
        // 同一個國家在回應裡可能是 "JP"、"jp"、"JP"，不能因為大小寫漏掉一半節點。
        assertEquals(listOf("1", "3"), ids(ProtonFilter(country = "JP").apply(nodes)))
        assertEquals(listOf("1", "3"), ids(ProtonFilter(country = "jp").apply(nodes)))
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
        assertEquals(listOf("1", "3"), ids(ProtonFilter(query = " jp ").apply(nodes)))
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

    @Test
    fun `idle percent is the mirror of load and absent for old payloads`() {
        assertEquals(80, node("9", "X", "JP", "Tokyo", load = 20).idlePercent)
        assertEquals(0, node("9", "X", "JP", "Tokyo", load = 100).idlePercent)
        assertNull(node("9", "X", "JP", "Tokyo", load = -1).idlePercent)
    }

    @Test
    fun `chinese locales read chinese city names and the search box follows`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("zh-TW"))
            assertEquals("洛杉磯", cityName("Los Angeles"))
            assertEquals("雪梨", cityName("Sydney"))
            assertEquals("金夏沙", cityName("Kinshasa"))
            // 清單上唸的是中文，打字卻只能打英文是強人所難。
            assertEquals(listOf("2", "4"), ids(ProtonFilter(query = "洛杉磯").apply(nodes)))

            Locale.setDefault(Locale.forLanguageTag("zh-CN"))
            assertEquals("洛杉矶", cityName("Los Angeles"))
            // 繁簡不只是字不同：雪梨／悉尼 兩邊各有慣用名。
            assertEquals("悉尼", cityName("Sydney"))

            Locale.setDefault(Locale.ENGLISH)
            assertEquals("Los Angeles", cityName("Los Angeles"))
            // 表裡沒有的名字（Proton 日後新增的城市）照原樣，不猜、不硬翻。
            assertEquals("Springfield", cityName("Springfield"))
        } finally {
            Locale.setDefault(saved)
        }
    }
}
