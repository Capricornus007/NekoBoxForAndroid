package io.nekohasekai.sagernet.database

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Google Play 規則的落地契約。Play 客戶端只畫介面與派工，真正拉 APK 的是下載管理器與 GMS/GSF，
 * CDN 也不在 `googleapis.cn` 那條域上：缺 domains 那半安裝會卡在 0%，缺 packages 那半規則會變成
 * 對全機生效、白吃代理流量（own 1a27397dd 的修法，補齊範圍後復用我方常量）。
 *
 * 既有安裝不會因為改首建清單而重生規則，所以 [ProfileManager.getRules] 還必須就地補一次；
 * 白名單模式（分應用代理）下也是：只有 com.android.vending 進不了隧道時，下載永遠走直連。
 */
class GooglePlayRouteRuleTest {

    private val profileManager by lazy { source("main/java/io/nekohasekai/sagernet/database/ProfileManager.kt") }
    private val vpnService by lazy { source("main/java/io/nekohasekai/sagernet/bg/VpnService.kt") }

    private val playRuleBlock by lazy {
        profileManager.substringAfter("private val PLAY_STORE_DOMAINS").substringBefore("suspend fun iterator")
    }

    @Test
    fun firstRunRuleCarriesCdnDomainsAndDownloaderPackages() {
        listOf(
            "domain:gvt1.com",
            "domain:gvt2.com",
            "domain:gvt3.com",
            "domain:gvt5.com",
            "domain:gvt6.com",
            "domain:gvt7.com",
            "domain:gvt9.com",
            "domain:gvt1-cn.com",
            "domain:gvt2-cn.com",
            "domain:android.clients.google.com",
            "domain:play.googleapis.com",
            "domain:googleusercontent.com",
            "domain:playstoregatewayadapter-pa.googleapis.com",
            "domain:firebaselogging-pa.googleapis.com",
            "domain:ggpht.com",
        ).forEach {
            assertTrue("首建規則缺 $it", playRuleBlock.contains(it))
        }
        listOf(
            "com.android.vending",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.providers.downloads",
            "com.android.providers.downloads.ui",
        ).forEach {
            assertTrue("首建規則缺套件 $it", playRuleBlock.contains(it))
        }
    }

    @Test
    fun existingInstallRulesGetEnrichedInPlace() {
        val getRules = profileManager.substringAfter("suspend fun getRules()")
        assertTrue("既有規則必須走就地補齊，不能只改首建清單", getRules.contains("enrichPlayStoreRules(rules)"))
        val enrich = profileManager.substringAfter("private suspend fun enrichPlayStoreRules")
        // 套用同一份常量，避免兩邊清單分岔。
        assertTrue(enrich.contains("PLAY_STORE_DOMAINS"))
        assertTrue(enrich.contains("PLAY_STORE_PACKAGES"))
        assertTrue(enrich.contains("SagerDatabase.rulesDao.updateRule(rule)"))
        // 只動走代理的 Play 規則：使用者自己寫的 googleapis.cn 直連/封鎖規則不該被改壞。
        assertTrue(enrich.contains("rule.outbound != 0L"))
        assertTrue(enrich.contains("\"domain:googleapis.cn\""))
        assertTrue(enrich.contains("\"com.android.vending\""))
    }

    @Test
    fun whitelistModePullsDownloadManagerIntoTun() {
        val marker = "if (proxyApps && !bypass && individual.contains(\"com.android.vending\"))"
        val whitelist = vpnService.substringAfter(marker)
        listOf(
            "com.android.providers.downloads",
            "com.android.providers.downloads.ui",
            "com.google.android.gsf",
            "com.google.android.gms",
        ).forEach {
            assertTrue("Play 白名單沒帶上 $it", whitelist.contains(it))
        }
    }

    private fun source(relativePath: String): String = File("src/$relativePath").readText()
}
