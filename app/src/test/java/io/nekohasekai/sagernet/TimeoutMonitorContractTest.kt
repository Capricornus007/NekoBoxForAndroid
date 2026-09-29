package io.nekohasekai.sagernet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 死節點重試路徑的契約：看門狗（連續三次失敗就斷線、不自動重連）已整个删除，
 * 它原本顺带充当「无限重连」的闸门。闸门搬到这里 —— 重试路径必须自带退避，
 * 否则删掉看门狗之后发烫问题就从这扇门回来。
 */
class TimeoutMonitorContractTest {

    private val baseService by lazy { source("main/java/io/nekohasekai/sagernet/bg/BaseService.kt") }
    private val monitor by lazy {
        baseService.substringAfter("fun startTimeoutMonitor()").substringBefore("fun switchToNextProxy()")
    }

    @Test
    fun monitorLaunchesOffTheMainDispatcher() {
        // binder 的作用域是 Dispatchers.Main.immediate，阻塞 native 探测留在上面会冻住界面。
        assertTrue(monitor.contains("data.binder.launch(Dispatchers.IO)"))
        assertFalse("监控不得再跑在 binder 的 Main 作用域上", monitor.contains("data.binder.launch {"))
        assertFalse(
            "reload() 不必再绕 Main 排程",
            monitor.contains("runOnMainDispatcher { reload() }"),
        )
    }

    @Test
    fun failedProbesBackOffInsteadOfHammering() {
        assertTrue(monitor.contains("backoffSeconds("))
        assertTrue(monitor.contains("consecutiveFailures"))
        assertTrue(monitor.contains("delay(waitSeconds * 1000L)"))
        assertTrue(baseService.contains("AUTO_SWITCH_MAX_BACKOFF_SECONDS"))
        // 探测本身要有硬期限：掐不断的 native 呼叫不能把这条监控永久卡死。
        assertTrue(monitor.contains("withTimeoutOrNull("))
        // 每轮只试有限个节点，不再整组 reload 一遍（旧代码是 do/while 扫完整组）。
        assertTrue(monitor.contains("tried < AUTO_SWITCH_MAX_PROBES_PER_ROUND"))
        assertFalse("不得恢复无上限整组轮询", monitor.contains("} while (tried < proxyCount)"))
        // 失败不再 break 掉整个监控，否则网络恢复后不会自愈；改由退避压制频率。
        assertFalse(monitor.contains("所有代理均不可用，停止超时自动切换监控"))
    }

    @Test
    fun watchdogIsRemovedFromCodeSettingsAndStrings() {
        assertFalse(File("src/main/java/io/nekohasekai/sagernet/bg/VpnWatchdog.kt").exists())
        listOf(
            "main/java/io/nekohasekai/sagernet/database/DataStore.kt",
            "main/java/io/nekohasekai/sagernet/Constants.kt",
            "main/java/io/nekohasekai/sagernet/ui/SettingsPreferenceFragment.kt",
            "main/java/io/nekohasekai/sagernet/bg/VpnService.kt",
            "main/res/xml/global_preferences.xml",
            "main/res/xml/neko_preferences.xml",
            "main/res/values/strings.xml",
            "main/res/values-zh-rTW/strings.xml",
        ).forEach { path ->
            val content = source(path)
            assertFalse("$path 還留著看門狗", "vpnWatchdog" in content || "vpn_watchdog" in content)
        }
    }

    private fun source(relativePath: String): String = File("src/$relativePath").readText()
}
