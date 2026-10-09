package io.nekohasekai.sagernet.fmt.proton

import java.util.Locale

/**
 * 城市的中文名。國家那欄是兩位碼、系統內建的區域名單（CLDR）直接就能唸成本機語，
 * 城市沒有這種表：Proton 只給英文原名，實測 195 個名字得自己列。
 *
 * 對不上的名字（Proton 日後新增的城市）照原樣顯示，不猜、不硬翻。
 */
fun cityName(city: String): String {
    if (city.isEmpty()) return city
    val locale = Locale.getDefault()
    if (locale.language != "zh") return city
    return (if (wantsSimplified(locale)) CITY_ZH_HANS else CITY_ZH_HANT)[city] ?: city
}

// 只有繁中／簡中之間要分表，其他中文變體（含只帶 script 的 tag）都走繁中基準。
private fun wantsSimplified(locale: Locale): Boolean = locale.script == "Hans" || locale.country == "CN"

private val CITY_ZH_HANT: Map<String, String> by lazy { CITY_ROWS.associate { (city, hant, _) -> city to hant } }

private val CITY_ZH_HANS: Map<String, String> by lazy { CITY_ROWS.associate { (city, _, hans) -> city to hans } }

// 直式表格：`原名|繁|簡`。放在一個字串常量裡而不是寫成兩個 map，是因為兩張表必須
// 一個名字一個名字對照著看才會漏翻；拆兩處就會只補到其中一邊。
private val CITY_ROWS: List<Array<String>> by lazy {
    CITY_TABLE.trimIndent().lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { line -> line.split('|').toTypedArray() }
        .toList()
}

@Suppress("MaxLineLength")
private const val CITY_TABLE = """
    #
    Abuja|阿布賈|阿布贾
    Accra|阿克拉|阿克拉
    Addis Ababa|亞的斯亞貝巴|亚的斯亚贝巴
    Adelaide|阿德雷德|阿德莱德
    Algiers|阿爾吉爾|阿尔及尔
    Amman|安曼|安曼
    Amsterdam|阿姆斯特丹|阿姆斯特丹
    Andorra la Vella|安道爾拉貝亞|安道尔拉贝亚
    Ashburn|阿什本|阿什本
    Ashgabat|阿什哈巴德|阿什哈巴德
    Asmara|阿斯馬拉|阿斯马拉
    Astana|阿斯塔納|阿斯塔纳
    Asunción|亞松森|亚松森
    Athens|雅典|雅典
    Atlanta|亞特蘭大|亚特兰大
    Auckland|奧克蘭|奥克兰
    Baghdad|巴格達|巴格达
    Baku|巴庫|巴库
    Bandar Seri Begawan|斯里巴加灣|斯里巴加湾
    Bangkok|曼谷|曼谷
    Barcelona|巴塞隆納|巴塞罗那
    Beirut|貝魯特|贝鲁特
    Belfast|貝爾法斯特|贝尔法斯特
    Belgrade|貝爾格萊德|贝尔格莱德
    Berlin|柏林|柏林
    Bishkek|比什凱克|比什凯克
    Bogotá|波哥大|波哥大
    Boston|波士頓|波士顿
    Bratislava|布拉提斯拉瓦|布拉迪斯拉发
    Brisbane|布里斯本|布里斯班
    Brussels|布魯塞爾|布鲁塞尔
    Bucharest|布加勒斯特|布加勒斯特
    Budapest|布達佩斯|布达佩斯
    Buenos Aires|布宜諾斯艾利斯|布宜诺斯艾利斯
    Cairo|開羅|开罗
    Caracas|卡拉卡斯|加拉加斯
    Cardiff|卡地夫|卡迪夫
    Casablanca|卡薩布蘭卡|卡萨布兰卡
    Charlotte|夏洛特|夏洛特
    Chicago|芝加哥|芝加哥
    Chisinau|基希訥烏|基希讷乌
    Colombo|可倫坡|科伦坡
    Columbus|哥倫布|哥伦布
    Conakry|柯納克里|科纳克里
    Copenhagen|哥本哈根|哥本哈根
    Dakar|達喀爾|达喀尔
    Dallas|達拉斯|达拉斯
    Damascus|大馬士革|大马士革
    Denver|丹佛|丹佛
    Detroit|底特律|底特律
    Dhaka|達卡|达卡
    Dodoma|杜杜馬|杜杜马
    Doha|杜哈|多哈
    Dubai|杜拜|迪拜
    Dublin|都柏林|都柏林
    Dushanbe|杜尚貝|杜尚别
    Edinburgh|愛丁堡|爱丁堡
    Frankfurt|法蘭克福|法兰克福
    Fujairah|富查伊拉|富查伊拉
    Glasgow|格拉斯哥|格拉斯哥
    Guatemala City|瓜地馬拉市|危地马拉市
    Hanoi|河內|河内
    Harare|哈拉雷|哈拉雷
    Havana|哈瓦那|哈瓦那
    Helsinki|赫爾辛基|赫尔辛基
    Hong Kong|香港|香港
    Houston|休士頓|休斯敦
    Istanbul|伊斯坦堡|伊斯坦布尔
    Jakarta|雅加達|雅加达
    Johannesburg|約翰尼斯堡|约翰内斯堡
    Johor Bahru|新山|新山
    Juba|朱巴|朱巴
    Kabul|喀布爾|喀布尔
    Kampala|坎帕拉|坎帕拉
    Karachi|喀拉蚩|卡拉奇
    Kathmandu|加德滿都|加德满都
    Khartoum|喀土穆|喀土穆
    Kigali|基加利|基加利
    Kingston|京斯敦|金斯敦
    Kinshasa|金夏沙|金沙萨
    Kuala Lumpur|吉隆坡|吉隆坡
    Kuwait City|科威特市|科威特市
    Kyiv|基輔|基辅
    La Paz|拉巴斯|拉巴斯
    Lagos|拉哥斯|拉各斯
    Libreville|利柏維爾|利伯维尔
    Lima|利馬|利马
    Limassol|利瑪索爾|利马索尔
    Lisbon|里斯本|里斯本
    Ljubljana|盧比安納|卢布尔雅那
    Lomé|洛梅|洛梅
    London|倫敦|伦敦
    Los Angeles|洛杉磯|洛杉矶
    Luanda|羅安達|罗安达
    Luxembourg|盧森堡|卢森堡
    Macau|澳門|澳门
    Madrid|馬德里|马德里
    Managua|馬納瓜|马那瓜
    Manama|麥納瑪|麦纳麦
    Manchester|曼徹斯特|曼彻斯特
    Manila|馬尼拉|马尼拉
    Maputo|馬普托|马普托
    Marseille|馬賽|马赛
    McAllen|麥卡倫|麦卡伦
    Melbourne|墨爾本|墨尔本
    Memphis|曼菲斯|孟菲斯
    Mexico City|墨西哥市|墨西哥城
    Miami|邁阿密|迈阿密
    Milan|米蘭|米兰
    Minsk|明斯克|明斯克
    Mogadishu|摩加迪休|摩加迪沙
    Monaco|摩納哥|摩纳哥
    Montevideo|蒙特維多|蒙得维的亚
    Montreal|蒙特婁|蒙特利尔
    Moroni|摩洛尼|莫罗尼
    Moscow|莫斯科|莫斯科
    Mumbai|孟買|孟买
    Muscat|馬斯喀特|马斯喀特
    N'Djamena|恩加梅納|恩贾梅纳
    Nairobi|奈洛比|内罗毕
    Nablus|納布盧斯|纳布卢斯
    New York|紐約|纽约
    Nouakchott|努瓦克肖特|努瓦克肖特
    Novi Travnik|諾維特拉夫尼克|诺维特拉夫尼克
    Nuuk|努克|努克
    Osaka|大阪|大阪
    Oslo|奧斯陸|奥斯陆
    Palermo|巴勒摩|巴勒莫
    Panama City|巴拿馬市|巴拿马城
    Paris|巴黎|巴黎
    Perth|柏斯|珀斯
    Philadelphia|費城|费城
    Phnom Penh|金邊|金边
    Phoenix|鳳凰城|菲尼克斯
    Podgorica|波德戈里察|波德戈里察
    Port Louis|路易港|路易港
    Port Moresby|摩士比港|莫尔斯比港
    Port-au-Prince|太子港|太子港
    Prague|布拉格|布拉格
    Pristina|普里什蒂納|普里什蒂纳
    Querétaro|克雷塔羅|克雷塔罗
    Quito|基多|基多
    Rabat|拉巴特|拉巴特
    Ramallah|拉馬拉|拉马拉
    Reykjavik|雷克雅維克|雷克雅未克
    Riga|里加|里加
    Riyadh|利雅德|利雅得
    Salt Lake City|鹽湖城|盐湖城
    San Jose|聖荷西|圣何塞
    San José|聖荷西|圣何塞
    San Juan|聖胡安|圣胡安
    San Salvador|聖薩爾瓦多|圣萨尔瓦多
    Sana'a|薩那|萨那
    Santiago|聖地牙哥|圣地亚哥
    Santo Domingo|聖多明哥|圣多明各
    São Paulo|聖保羅|圣保罗
    Sarajevo|塞拉耶佛|萨拉热窝
    Schaan|蕭恩|萧恩
    Seattle|西雅圖|西雅图
    Secaucus|西考克斯|西考克斯
    Seoul|首爾|首尔
    Shenzhen|深圳|深圳
    Siauliai|希奧利艾|希奥利艾
    Singapore|新加坡|新加坡
    Skopje|史高比耶|斯科普里
    Sofia|索菲亞|索菲亚
    Stockholm|斯德哥爾摩|斯德哥尔摩
    Sydney|雪梨|悉尼
    Taichung|臺中|台中
    Taipei|臺北|台北
    Tallinn|塔林|塔林
    Tashkent|塔什干|塔什干
    Tbilisi|第比利斯|第比利斯
    Tegucigalpa|德古西加巴|德古西加巴
    Tel Aviv|特拉維夫|特拉维夫
    Thimphu|廷布|廷布
    Tirana|地拉那|地拉那
    Tokyo|東京|东京
    Toronto|多倫多|多伦多
    Tripoli|的黎波里|的黎波里
    Tunis|突尼斯|突尼斯
    Ulaanbaatar|烏蘭巴托|乌兰巴托
    Valletta|瓦勒他|瓦莱塔
    Vancouver|溫哥華|温哥华
    Vienna|維也納|维也纳
    Vientiane|永珍|万象
    Vilnius|維爾紐斯|维尔纽斯
    Warsaw|華沙|华沙
    Washington|華盛頓|华盛顿
    Yamoussoukro|亞穆蘇克羅|亚穆苏克罗
    Yangon|仰光|仰光
    Yaoundé|雅溫得|雅温得
    Yerevan|葉里溫|叶里温
    Zagreb|札格雷布|萨格勒布
    Zurich|蘇黎世|苏黎世
    """
