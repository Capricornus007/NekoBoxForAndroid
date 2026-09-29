package io.nekohasekai.sagernet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 連線中切換節點的行為契約：使用者 2026-09-29 明示「切換節點之後就得斷開再連接，
 * 不管上一個在測速還是在幹什麼」。以前有一條「切換器群組只 call box.selectOutbound(tag)」
 * 的 in-place 快速路徑，它不重建核心、也不重置舊節點上已建立的連線，換完常常還是走舊出口，
 * 所以整條拿掉。這裡釘住「不得回魂」。
 */
class NodeSwitchContractTest {

    private val baseService by lazy { source("main/java/io/nekohasekai/sagernet/bg/BaseService.kt") }
    private val reloadInner by lazy {
        baseService.substringAfter("private fun reloadInner(").substringBefore("fun startTimeoutMonitor()")
    }

    @Test
    fun nodeSwitchAlwaysTearsDownAndRestarts() {
        assertFalse(
            "不得再用 in-place selectOutbound 取代重連",
            reloadInner.contains("selectOutbound"),
        )
        assertFalse(baseService.contains("resolveSelectorReloadTag"))
        assertFalse(baseService.contains("canReloadSelector"))
        assertTrue("連線中切換必須走 stopRunner(restart = true)", reloadInner.contains("stopRunner(true)"))
        assertTrue("停止狀態則直接啟動", reloadInner.contains("startRunner()"))
    }

    private fun source(relativePath: String): String = File("src/$relativePath").readText()
}
