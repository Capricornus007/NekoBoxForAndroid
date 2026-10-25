package io.nekohasekai.sagernet.bg.proto

// isai 的探針／診斷族在 libcore（Go）側依賴他們自家 sing-box fork 的核心增量
// （group.URLTest.SetProbesPaused、session-stats tracker、runtime 診斷後端）。
// 本倉 libcore 取 fork 架構、未攜帶那些核心改動，直接移植會跨倉級聯。
// 這裡以 Kotlin 擴展樁保持 isai 的 UI／AIDL 面完整：功能自報不支援，
// 不誤導、不崩潰。哪天把 isai 的核心增量移植進 libcore，刪掉這些擴展、
// 換成對真實 Go 方法的委託即可。

/**
 * isai：電源受限時暫停 URLTest 探針。核心未攜帶對應鉤子，安全無操作。
 */
fun libcore.BoxInstance.setProbesPaused(paused: Boolean) {
}

/**
 * isai：重置探針健康狀態（冷卻／失敗計數）。核心未攜帶，安全無操作。
 */
fun libcore.BoxInstance.resetProbeHealth() {
}

/**
 * isai：isai：取消進行中的核心啟動。fork 的 BoxInstance 沒有可取消的
 * 啟動協程句柄（b.cancel），安全無操作——stopRunner 的既有流程已覆蓋。
 */
fun libcore.BoxInstance.cancelStart() {
}

/**
 * isai：執行期診斷快照（連線／規則／DNS 對照）。核心未攜帶，回報不支援。
 */
fun libcore.BoxInstance.runtimeDiagnostics(historyQuery: String?): String = "{\"supported\":false,\"reason\":\"isai diagnostics core not ported\"}"

/**
 * isai：真實網路探測（直連／代理路徑的 DNS＋HTTPS 檢查）。核心未攜帶，回報不支援。
 */
fun libcore.BoxInstance.networkProbe(tag: String, domain: String, dnsTarget: String): String = "{\"supported\":false,\"reason\":\"isai diagnostics core not ported\"}"

/**
 * isai：按配置參數執行一次探測。核心未攜帶，回報不支援。
 */
fun libcore.BoxInstance.configuredProbe(tag: String, dnsTag: String, httpsUrl: String): String = "{\"supported\":false,\"reason\":\"isai diagnostics core not ported\"}"
