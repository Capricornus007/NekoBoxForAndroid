package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.bg.byedpi.ByeDpiConfig
import io.nekohasekai.sagernet.bg.byedpi.ByeDpiStatus
import io.nekohasekai.sagernet.bg.byedpi.EmbeddedBackend
import io.nekohasekai.sagernet.database.AppTrafficRule
import io.nekohasekai.sagernet.database.AppTrafficRuleValidationReport
import io.nekohasekai.sagernet.database.TrafficMode
import io.nekohasekai.sagernet.database.TrafficRoutingDiagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedTunnelControllerTest {

    @Test
    fun `starts and stops ByeDPI backend in dpi bypass mode`() {
        val backend = FakeByeDpiBackend(startResult = true)
        val controller = UnifiedTunnelController(
            byeDpiBackend = backend,
            byeDpiErrorProvider = { backend.lastError },
            ruleReportProvider = { AppTrafficRuleValidationReport() },
            routingDiagnosticsProvider = { mode, report ->
                TrafficRoutingDiagnostics(defaultMode = mode, ruleCount = report.normalizedRules.size, remoteRuleCount = 0, dpiBypassRuleCount = 0)
            }
        )

        val started = controller.start(TrafficMode.DPI_BYPASS, remote = null)

        assertTrue(started)
        assertEquals(1, backend.startCalls)
        assertEquals(ByeDpiStatus.RUNNING, controller.localStatus.value.byeDpiStatus)
        assertEquals(TrafficMode.DPI_BYPASS, controller.localStatus.value.activeMode)

        controller.stop()

        assertEquals(1, backend.stopCalls)
        assertEquals(ByeDpiStatus.IDLE, controller.localStatus.value.byeDpiStatus)
        assertEquals(null, controller.localStatus.value.activeMode)
    }

    @Test
    fun `returns error when ByeDPI backend fails to start`() {
        val backend = FakeByeDpiBackend(
            startResult = false,
            lastError = "probe failed"
        )
        val controller = UnifiedTunnelController(
            byeDpiBackend = backend,
            byeDpiErrorProvider = { backend.lastError },
            ruleReportProvider = { AppTrafficRuleValidationReport() },
            routingDiagnosticsProvider = { mode, report ->
                TrafficRoutingDiagnostics(defaultMode = mode, ruleCount = report.normalizedRules.size, remoteRuleCount = 0, dpiBypassRuleCount = 0)
            }
        )

        val started = controller.start(TrafficMode.DPI_BYPASS, remote = null)

        assertFalse(started)
        assertEquals("probe failed", controller.lastErrorMessage())
        assertEquals(1, backend.startCalls)
        assertEquals(0, backend.stopCalls)
    }

    @Test
    fun `split startup fails fast when remote runtime is unavailable`() {
        val backend = FakeByeDpiBackend(startResult = true)
        val controller = UnifiedTunnelController(
            byeDpiBackend = backend,
            byeDpiErrorProvider = { backend.lastError },
            ruleReportProvider = {
                AppTrafficRuleValidationReport(
                    normalizedRules = listOf(
                        AppTrafficRule("com.example.split", TrafficMode.DPI_BYPASS)
                    )
                )
            },
            routingDiagnosticsProvider = { mode, report ->
                TrafficRoutingDiagnostics(defaultMode = mode, ruleCount = report.normalizedRules.size, remoteRuleCount = 0, dpiBypassRuleCount = report.normalizedRules.size)
            }
        )

        val started = controller.start(TrafficMode.REMOTE_VPN, remote = null)

        assertFalse(started)
        assertEquals(0, backend.startCalls)
        assertEquals("Remote runtime is unavailable", controller.lastErrorMessage())
    }

    private class FakeByeDpiBackend(
        private val startResult: Boolean,
        var lastError: String? = null,
    ) : EmbeddedBackend<ByeDpiConfig, ByeDpiStatus> {

        private val mutableStatus = MutableStateFlow(ByeDpiStatus.IDLE)
        override val status: StateFlow<ByeDpiStatus> = mutableStatus

        var startCalls = 0
        var stopCalls = 0

        override fun start(config: ByeDpiConfig): Boolean {
            startCalls += 1
            mutableStatus.value = if (startResult) ByeDpiStatus.RUNNING else ByeDpiStatus.FAILED
            return startResult
        }

        override fun stop(force: Boolean): Boolean {
            stopCalls += 1
            mutableStatus.value = ByeDpiStatus.IDLE
            return true
        }
    }
}
