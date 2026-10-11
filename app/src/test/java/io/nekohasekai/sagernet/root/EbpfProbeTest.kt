package io.nekohasekai.sagernet.root

import io.nekohasekai.sagernet.ktx.Logs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 探針報告的解析。這些案例守的是同一件事：**「不能跑」與「問不到」不能混為一談**——
 * 把後者顯示成「你的內核不支援 eBPF」會直接把使用者趕去升級內核，而真正的原因
 * 可能是我們的建置少了 tag、或 sidecar 那顆根本沒跑起來。
 *
 * 主要驗的是 **CLI 形**（現行走的路）：sing-box 的 cmd_ebpf_probe.go →
 * common/ebpf/kernel_probe_render.go 的 WriteKernelProbeReportJSON，縮排多行、snake_case。
 * 另外也驗 libbox 橋形（sing-box 的 experimental/libbox/ebpf.go，單行 camelCase）：那條路
 * 現在被 libcore/build.sh 少了 with_ebpf 擋死，哪天補上 tag 就改走 JNI，只認一種形的解析器
 * 會在毫無錯誤訊息的情況下把每份報告都讀成空報告。
 *
 * org.json 是 Android 框架類，裸 JVM 上是 stub，所以要跑在 Robolectric 下
 * （同 ProtonSidecarParseTest 的處理）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class EbpfProbeTest {

    private val originalLogSink = Logs.sink

    @Before
    fun muteLogs() {
        // parse() 的失敗路徑會寫日誌，預設 sink 是 Libcore 的 native 呼叫，測試行程裡載不進來。
        Logs.sink = {}
    }

    @After
    fun restoreLogs() {
        Logs.sink = originalLogSink
    }

    // libbox 橋（單行 camelCase）的報告。
    private val bridgeSupported = """
        {"supported":true,"platform":"linux","kernelRelease":"6.6.30-android15",
         "architecture":"arm64","mode":"all","failures":[],"warnings":[],
         "counts":{"pass":12,"warn":1}}
    """.trimIndent().replace("\n", "")

    // CLI（sidecar 現行產出）的報告：WriteKernelProbeReportJSON 用的是縮排 encoder。
    private val cliSupported = """
        {
          "platform": "android",
          "kernel_release": "6.6.30-android15",
          "architecture": "arm64",
          "mode": "all",
          "network": ["tcp", "udp"],
          "findings": [
            {"status":"PASS","scope":"common","importance":"required",
             "feature":"privileged process","detail":"The process has UID 0."},
            {"status":"WARN","scope":"common","importance":"performance",
             "feature":"BPF JIT","detail":"bpf_jit_enable is 0; programs run interpreted."}
          ],
          "active_programs": [],
          "summary": {"pass":1,"warn":1,"fail":0,"unknown":0,"required_failures":0},
          "result": "supported"
        }
    """.trimIndent()

    @Test
    fun cliSupportedReportYieldsOkWithKernelString() {
        val r = EbpfProbe.parse(cliSupported, EbpfProbe.Mode.ALL, cliSupported)
        assertEquals(EbpfProbe.Verdict.OK, r.verdict)
        assertTrue(r.supported)
        assertEquals("✓", r.mark)
        assertEquals("6.6.30-android15", r.kernelRelease)
        assertEquals("arm64", r.architecture)
        assertEquals("all  ✓  6.6.30-android15", r.line())
    }

    @Test
    fun cliRequiredFailureIsTheOnlyThingThatSaysUnsupported() {
        val json = """
            {
              "platform": "android",
              "kernel_release": "5.4.241",
              "mode": "local",
              "findings": [
                {"status":"FAIL","scope":"local","importance":"required",
                 "feature":"cgroup/connect4 TCP",
                 "detail":"BPF_CGROUP_INET4_CONNECT is not attachable: Operation not supported"},
                {"status":"PASS","scope":"common","importance":"required",
                 "feature":"map type HASH","detail":"ok"}
              ],
              "summary": {"pass":1,"warn":0,"fail":1,"unknown":0,"required_failures":1},
              "result": "unsupported"
            }
        """.trimIndent()
        val r = EbpfProbe.parse(json, EbpfProbe.Mode.LOCAL, json)
        assertEquals(EbpfProbe.Verdict.UNSUPPORTED, r.verdict)
        assertEquals(1, r.failures.size)
        assertEquals("cgroup/connect4 TCP", r.failures[0].feature)
        // 顯示的是那句人話（detail），不是 feature id。
        assertEquals(
            "local  ✗  BPF_CGROUP_INET4_CONNECT is not attachable: Operation not supported",
            r.line()
        )
    }

    /**
     * performance／fallback 級的 WARN 只顯示不擋：少了 BPF JIT 只是慢，
     * 把它講成「內核不支持」會讓能用的人跑去刷內核。
     */
    @Test
    fun cliWarnOnlyPerformanceFindingDoesNotBlock() {
        val json = """
            {
              "kernel_release": "6.1.0",
              "findings": [
                {"status":"FAIL","scope":"common","importance":"fallback",
                 "feature":"TCX","detail":"tcx attach unsupported, falling back to classic TC"},
                {"status":"WARN","scope":"common","importance":"performance",
                 "feature":"BPF JIT","detail":"jit disabled"}
              ],
              "summary": {"pass":9,"warn":1,"fail":1,"unknown":0,"required_failures":0},
              "result": "supported"
            }
        """.trimIndent()
        val r = EbpfProbe.parse(json, EbpfProbe.Mode.SHARED_NETWORK, json)
        assertEquals(EbpfProbe.Verdict.OK, r.verdict)
        assertTrue("required_failures 是 0 就不該有 failures", r.failures.isEmpty())
        assertEquals("兩條都該被列出來給使用者看", 2, r.warnings.size)
    }

    /** UNKNOWN（權限不足、或要看實跑）不是「不支持」，也不能當成「支援」。 */
    @Test
    fun cliInconclusiveStaysSeparateFromUnsupported() {
        val json = """
            {
              "kernel_release": "5.15.0",
              "findings": [
                {"status":"UNKNOWN","scope":"common","importance":"required",
                 "feature":"BPF and network administration privileges",
                 "detail":"Run as root or grant the required capabilities."}
              ],
              "summary": {"pass":8,"warn":0,"fail":0,"unknown":1,"required_failures":0},
              "result": "inconclusive"
            }
        """.trimIndent()
        val r = EbpfProbe.parse(json, EbpfProbe.Mode.ALL, json)
        assertEquals(EbpfProbe.Verdict.INCONCLUSIVE, r.verdict)
        assertEquals("?", r.mark)
        assertFalse(r.supported)
        assertEquals(1, r.warnings.size)
    }

    /**
     * cmd_ebpf_probe_stub.go（這支 binary 沒帶 with_ebpf）回的就是這形：result 寫的是
     * unsupported，但它講的是**我方建置**，不是使用者的內核。判成 UNSUPPORTED 就會叫人升級內核。
     */
    @Test
    fun cliStubReportIsProbeFailureNotKernelVerdict() {
        val json = """
            {
              "result": "unsupported",
              "error": "this sing-box binary was built without the with_ebpf tag or for a platform other than linux/android"
            }
        """.trimIndent()
        val r = EbpfProbe.parse(json, EbpfProbe.Mode.ALL, json)
        assertEquals(EbpfProbe.Verdict.PROBE_FAILED, r.verdict)
        assertTrue(r.failures.isEmpty())
        assertTrue(r.summary().contains("with_ebpf"))
    }

    /** 不認識的 result 字串（上游加了新狀態）一律算「問不到」，不許替使用者的內核下判斷。 */
    @Test
    fun unknownResultStringDegradesToProbeFailure() {
        val json = """{"result":"maybe","findings":[],"summary":{"required_failures":0}}"""
        val r = EbpfProbe.parse(json, EbpfProbe.Mode.LOCAL, json)
        assertEquals(EbpfProbe.Verdict.PROBE_FAILED, r.verdict)
    }

    /**
     * 「只能共網、不能本機」這個中間態是真實存在的（local 要 cgroup/connect4 4.17+，
     * shared-network 只要 TC 4.16+）。兩條線索要分開留著，UI 才有東西逐 mode 講。
     */
    @Test
    fun sharedNetworkOnlyStaysVisiblePerMode() {
        val local = EbpfProbe.parse(
            """
                {
                  "kernel_release": "4.16.0",
                  "mode": "local",
                  "findings": [
                    {"status":"FAIL","scope":"local","importance":"required",
                     "feature":"cgroup/connect4","detail":"BPF_CGROUP_INET4_CONNECT missing"}
                  ],
                  "summary": {"pass":6,"warn":0,"fail":1,"unknown":0,"required_failures":1},
                  "result": "unsupported"
                }
            """.trimIndent(),
            EbpfProbe.Mode.LOCAL,
            "",
        )
        val shared = EbpfProbe.parse(
            """
                {
                  "kernel_release": "4.16.0",
                  "mode": "shared-network",
                  "findings": [],
                  "summary": {"pass":7,"warn":0,"fail":0,"unknown":0,"required_failures":0},
                  "result": "supported"
                }
            """.trimIndent(),
            EbpfProbe.Mode.SHARED_NETWORK,
            "",
        )
        assertEquals(EbpfProbe.Verdict.UNSUPPORTED, local.verdict)
        assertEquals(EbpfProbe.Verdict.OK, shared.verdict)
        assertEquals("local  ✗  BPF_CGROUP_INET4_CONNECT missing", local.line())
        assertEquals("shared-network  ✓  4.16.0", shared.line())
    }

    @Test
    fun bridgeShapeSupportedStillParses() {
        val r = EbpfProbe.parse(bridgeSupported, EbpfProbe.Mode.ALL, bridgeSupported)
        assertEquals(EbpfProbe.Verdict.OK, r.verdict)
        assertTrue(r.supported)
        assertEquals("6.6.30-android15", r.kernelRelease)
        assertEquals("6.6.30-android15", r.summary())
    }

    @Test
    fun bridgeShapeRequiredFailureStillParses() {
        val json = """{"supported":false,"mode":"local","failures":[
            {"status":"fail","scope":"cgroup","importance":"required",
             "feature":"connect4","detail":"BPF_CGROUP_INET4_CONNECT not supported by kernel"}
          ],"warnings":[]}"""
        val r = EbpfProbe.parse(json, EbpfProbe.Mode.LOCAL, json)
        assertEquals(EbpfProbe.Verdict.UNSUPPORTED, r.verdict)
        assertEquals(1, r.failures.size)
        assertEquals("connect4", r.failures[0].feature)
        assertEquals("BPF_CGROUP_INET4_CONNECT not supported by kernel", r.summary())
    }

    /** libbox 的 ebpf_stub.go 在沒帶 with_ebpf 時回的就是這一路（同上，是建置問題）。 */
    @Test
    fun bridgeShapeStubReplyIsProbeFailureNotKernelUnsupported() {
        val json = """{"supported":false,"error":"eBPF requires the with_ebpf build tag on linux/android"}"""
        val r = EbpfProbe.parse(json, EbpfProbe.Mode.ALL, json)
        assertEquals(EbpfProbe.Verdict.PROBE_FAILED, r.verdict)
        assertTrue(r.summary().contains("with_ebpf"))
    }

    @Test
    fun malformedJsonFailsInsteadOfThrowing() {
        val r = EbpfProbe.parse("{not json", EbpfProbe.Mode.ALL, "{not json")
        assertEquals(EbpfProbe.Verdict.PROBE_FAILED, r.verdict)
        assertTrue(r.failures.isEmpty())
    }

    @Test
    fun findingWithoutDetailFallsBackToTheFeatureId() {
        val json = """{"supported":false,"failures":[
            {"status":"FAIL","scope":"tc","importance":"required","feature":"clsact","detail":""}
          ]}"""
        val r = EbpfProbe.parse(json, EbpfProbe.Mode.SHARED_NETWORK, json)
        assertEquals("clsact", r.failures[0].display)
    }

    /** 探針前若有 log 行會混在同一串輸出裡（RootShell 把 stderr 併進來了）。 */
    @Test
    fun logNoiseAroundTheIndentedReportStillFindsTheReport() {
        val output = listOf(
            "INFO[0000] starting eBPF probe",
            "WARN[0000] cgroup v1 fallback attempted",
            cliSupported,
            "trailing note without braces",
        ).joinToString("\n")
        val json = EbpfProbe.extractJsonObject(output)
        assertNotNull(json)
        assertTrue(json!!.trim().startsWith("{"))
        assertTrue(json.trim().endsWith("}"))
        val report = EbpfProbe.parse(json, EbpfProbe.Mode.ALL, output)
        assertEquals(EbpfProbe.Verdict.OK, report.verdict)
    }

    /** 單行（compact）也要找得到——libbox 橋與舊行為都走這條。 */
    @Test
    fun compactSingleLineReportStillFindsTheReport() {
        val output = "some log\n$bridgeSupported\nbye"
        val json = EbpfProbe.extractJsonObject(output)
        assertNotNull(json)
        assertTrue(json!!.startsWith("{\"supported\":true"))
    }

    @Test
    fun outputWithoutAnyJsonObjectIsReportedAsMissing() {
        assertNull(EbpfProbe.extractJsonObject("permission denied\n"))
    }

    /**
     * 「一鍵複製診斷」的內容要足以判根因：逐條 finding 與原始 JSON 都得在裡面，
     * 否則外部用戶貼回來的只有一行結論，我們什麼也查不了。
     */
    @Test
    fun diagnosticBlockCarriesFindingsAndRawOutput() {
        val r = EbpfProbe.parse(cliSupported, EbpfProbe.Mode.ALL, cliSupported)
        val block = r.diagnosticBlock()
        assertTrue(block.lines().first().startsWith("all  ✓"))
        assertTrue(block.contains("bpf_jit_enable is 0"))
        assertTrue(block.contains("----- raw -----"))
        assertTrue(block.contains("\"result\": \"supported\""))
    }

    /** UI 那三句自己的失敗（root／sidecar／問不到）要能覆寫掉技術面預設值。 */
    @Test
    fun localizedReasonOverridesTheTechnicalFallback() {
        val r = EbpfProbe.parse(
            """{"result":"unsupported","error":"built without the with_ebpf tag"}""",
            EbpfProbe.Mode.LOCAL,
            "",
        )
        assertEquals(EbpfProbe.Verdict.PROBE_FAILED, r.verdict)
        assertTrue(r.line().contains("built without"))
        assertEquals("local  ✗  探針沒有回應", r.line("探針沒有回應"))
        // 傳空字串＝沿用技術面預設（UI 只有我方那三層才有自己的話，OK／UNSUPPORTED 傳的就是空）。
        assertEquals(r.line(), r.line(""))
    }
}
