package io.nekohasekai.sagernet.root

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * 用 root 叫 **sidecar** 跑 `ebpf-probe`，問這台機器的內核到底能不能跑 eBPF 入站。
 *
 * 為什麼不走 aar（`Libcore.EBPFProbeKernel`）：
 *   1. aar 的 build tags（libcore/build.sh）**沒有 with_ebpf**，Kotlin 那側拿到的只會是
 *      experimental/libbox/ebpf_stub.go 的回錯版本；
 *   2. 更要緊的是「說支援的」與「真要跑的」必須是同一顆 binary。eBPF 模式實際執行的是
 *      nativeLibraryDir 裡的 libsingbox.so（由 buildScript/lib/singbox-sidecar.sh 帶
 *      with_ebpf 編出，並在 CI 用 cilium/ebpf 字串驗產物）。讓那顆自己去答能力問題，
 *      才不會出現「aar 說行、sidecar 沒編進去」這種假訊號。
 *   3. 第三層理由：JNI 橋是在 app 自己的行程裡跑的，那個行程是 app uid，不是 root。
 *      ProbeKernel 的權限檢查（kernel_probe.go 的 probeCommonCapabilities）在無特權時回
 *      UNKNOWN，結果就變成 inconclusive —— 換句話說 aar 那條路**天生答不出能力問題**，
 *      只有 su 起的 sidecar 才有答案。
 *
 * 為什麼要逐 mode 問：`local` 要 cgroup/connect4（4.17+），`shared-network` 只要
 * TC classifier（4.16+），兩者會分開成敗——「只能共網、不能本機」是真實存在的中間態。
 * 所以這裡不提供「一次問完」的捷徑，呼叫方要哪個模式就問哪個。
 *
 * JSON 契約（改這裡之前先看來源，兩邊不同步的話 UI 會靜默把能用的人講成不能用）：
 *   CLI 形（現行走的就是這條）：cmd/sing-box/cmd_ebpf_probe.go → common/ebpf/
 *     kernel_probe_render.go 的 WriteKernelProbeReportJSON，**縮排多行**、snake_case，
 *     欄位是 result / kernel_release / findings[] / summary.required_failures。
 *   橋形（目前 aar 拿不到，留著以防哪天改走 JNI）：sing-box 的
 *     experimental/libbox/ebpf.go，單行 camelCase，欄位是 supported / kernelRelease /
 *     failures[] / warnings[]。
 * 兩種形都認，判準同一個：required_failures > 0（= Go 側 RequiredFailures()）。
 */
/**
 * eBPF 探針 detail 的繁中對照表。
 *
 * 為什麼要這張表：detail 是 Go 側 kernel_probe.go 產生的英文診斷句，會直接顯示在設定頁
 * 那一行（例如 `all  ✗  Stores redirect and flow state.`）。使用者看不懂英文，但這些是
 * 核心回傳的技術資料、不是 app 自己的文案；塞進 strings.xml 會讓 19 個語系都要維護同一批
 * 技術句，翻譯品質也難保證。
 *
 * 策略：
 *   - 只在中文語系（zh-*）套用；其他語系保留英文原文（技術診斷資料本來就以英文為準）。
 *   - 精確比對不到就用前綴／樣式比對（含內核版本、位元組數那些動態句）。
 *   - 全都對不到就原樣回傳——寧可顯示英文，也不要顯示錯誤的翻譯。
 *   - 之後新增 detail 只要往 TABLE 補一行，不必動任何 res/values-*。
 */
internal object EbpfDetailZh {

    private val TABLE: Map<String, String> = mapOf(
        // ── common scope ──
        "The process has UID 0. Direct probes below still detect capability, LSM, or seccomp restrictions." to
            "行程以 UID 0 執行。下方的直接探測仍會檢查 capability、LSM 與 seccomp 限制。",
        "Run as root or grant the BPF, system-administration, and network-administration capabilities required by the selected data path." to
            "請以 root 執行，或授予所選資料路徑所需的 BPF、系統管理與網路管理 capability。",
        "Stores redirect and flow state." to
            "儲存轉發與連線流程狀態。",
        "Stores runtime controls and counters." to
            "儲存執行期控制項與計數器。",
        "Stores bounded socket, UDP, fragment, and bypass caches." to
            "儲存有上限的 socket、UDP、分片與繞過快取。",
        "Stores UID and CIDR policies. Linux 6.6.0-6.6.46 policy updates remain blocked separately unless the upstream fix is detected." to
            "儲存 UID 與 CIDR 策略。在偵測到上游修正前，Linux 6.6.0–6.6.46 的策略更新仍會被單獨阻擋。",
        "RLIMIT_MEMLOCK is unlimited after automatic adjustment." to
            "自動調整後 RLIMIT_MEMLOCK 已無上限。",
        // ── local scope ──
        "Implements TCP connect interception and UDP sendmsg/recvmsg address translation." to
            "實作 TCP connect 攔截與 UDP sendmsg/recvmsg 位址轉譯。",
        "Required for local TCP." to
            "本機 TCP 必需。",
        "Required for connected local UDP." to
            "已連線的本機 UDP 必需。",
        "Required for local TCP. Also covers IPv4-mapped dual-stack sockets." to
            "本機 TCP 必需，同時涵蓋 IPv4-mapped 雙棧 socket。",
        "Required for connected local UDP. Also covers IPv4-mapped dual-stack sockets." to
            "已連線的本機 UDP 必需，同時涵蓋 IPv4-mapped 雙棧 socket。",
        "Required to redirect unconnected IPv4 UDP." to
            "轉發未連線的 IPv4 UDP 必需。",
        "Required to redirect unconnected IPv6 and IPv4-mapped UDP." to
            "轉發未連線的 IPv6 與 IPv4-mapped UDP 必需。",
        "Required to restore the original IPv4 UDP peer. Upstream Linux added recvmsg hooks in 5.2." to
            "還原原始 IPv4 UDP 對端必需。上游 Linux 5.2 才加入 recvmsg hook。",
        "Required to restore the original IPv6 or IPv4-mapped UDP peer. Upstream Linux added recvmsg hooks in 5.2." to
            "還原原始 IPv6 或 IPv4-mapped UDP 對端必需。上游 Linux 5.2 才加入 recvmsg hook。",
        "Reads policy, protection, redirect, and UDP state." to
            "讀取策略、自我保護、轉發與 UDP 狀態。",
        "Creates redirect, token, peer, and flow state." to
            "建立轉發、token、對端與連線流程狀態。",
        "Reclaims or replaces UDP state." to
            "回收或取代 UDP 狀態。",
        "Identifies UDP sockets and provides self-protection fallback." to
            "辨識 UDP socket，並提供自我保護的備援路徑。",
        "Enforces UID and Android package policy when configured." to
            "設定後強制執行 UID 與 Android 套件策略。",
        "Timestamps TCP redirect state." to
            "為 TCP 轉發狀態打時間戳。",
        "Provides the fast sing-box self-bypass path; socket-cookie protection is the fallback." to
            "提供 sing-box 自身繞過的快速路徑；socket-cookie 保護是備援。",
        "Enables exact connected-UDP cleanup. Bounded LRU maps and a reduced UDP cache are used when unavailable." to
            "啟用已連線 UDP 的精確清理。不可用時改用有上限的 LRU map 與縮減的 UDP 快取。",
        // ── shared-network scope ──
        "Implements TC ingress and egress interception, policy, token rewriting, and reply restoration." to
            "實作 TC 入口／出口攔截、策略、token 改寫與回覆還原。",
        "Provides lock-free per-CPU packet parsing scratch space." to
            "提供免鎖的 per-CPU 封包解析暫存區。",
        "Reads controls, policy, and flow state." to
            "讀取控制項、策略與連線流程狀態。",
        "Creates proxy, reply, bypass, and fragment state." to
            "建立代理、回覆、繞過與分片狀態。",
        "Removes expired or conflicting flow state." to
            "移除過期或衝突的連線流程狀態。",
        "Applies UDP, TCP, fragment, and bypass cache lifetimes." to
            "套用 UDP、TCP、分片與繞過快取的生命週期。",
        "Makes packet headers linear and writable." to
            "讓封包標頭連續且可寫。",
        "Rewrites token and original addresses and ports." to
            "改寫 token 與原始位址、埠號。",
        "Calculates IPv4 and IPv6 checksum deltas." to
            "計算 IPv4 與 IPv6 校驗和差值。",
        "Updates the IPv4 header checksum." to
            "更新 IPv4 標頭校驗和。",
        "Updates TCP and UDP checksums." to
            "更新 TCP 與 UDP 校驗和。",
        "Used on modern kernels for qdisc-independent ingress attachment; clsact is the fallback." to
            "較新內核用於不依賴 qdisc 的入口掛載；clsact 是備援。",
        "Used on modern kernels for qdisc-independent egress attachment; clsact is the fallback." to
            "較新內核用於不依賴 qdisc 的出口掛載；clsact 是備援。",
        // ── cgroup 路徑 ──
        "The configured path is not a directory." to
            "設定的路徑不是目錄。",
    )

    /** 動態句的前綴對照：值是「英文前綴 → 中文前綴」，後面的原文照原樣接上。 */
    private val PREFIX: List<Pair<String, String>> = listOf(
        "The running kernel version could not be read: " to "讀不到執行中的內核版本：",
        "The path is not below the detected cgroup v2 mount " to "此路徑不在偵測到的 cgroup v2 掛載點之下：",
        "The process limit could not be read: " to "讀不到行程的記憶體上限：",
        "Automatic adjustment left RLIMIT_MEMLOCK at " to "自動調整後 RLIMIT_MEMLOCK 為 ",
    )

    fun translate(detail: String): String {
        if (detail.isEmpty()) return detail
        // 只有中文語系套用；其他語系保留核心原文（技術診斷資料以英文為準）。
        if (!Locale.getDefault().language.equals("zh", ignoreCase = true)) return detail

        TABLE[detail]?.let { return it }

        for ((en, zh) in PREFIX) {
            if (detail.startsWith(en)) return zh + detail.removePrefix(en)
        }

        // 「執行中的內核為 X；本實作要求 Linux 4.19 以上」／「…以各項功能探測為準」兩種尾句。
        val kernelPrefix = "The running kernel reports "
        if (detail.startsWith(kernelPrefix)) {
            val rest = detail.removePrefix(kernelPrefix)
            val version = rest.substringBefore(';').substringBefore(". ")
            return if (rest.contains("4.19 or newer")) {
                "執行中的內核為 $version；本實作要求 Linux 4.19 以上。"
            } else {
                "執行中的內核為 $version。對廠商自訂內核，仍以各項功能探測結果為準。"
            }
        }

        // 對不到就原樣回傳——寧可顯示英文，也不要給錯的翻譯。
        return detail
    }
}

object EbpfProbe {

    enum class Mode(val wire: String) {
        ALL("all"),
        LOCAL("local"),
        SHARED_NETWORK("shared-network"),
    }

    /** 探針問不到明確答案時的原因，UI 靠它決定該說哪一句話。 */
    enum class Verdict {
        // 內核過得去（required_failures == 0 且 result=supported）
        OK,

        // 內核缺必要特性（required_failures > 0，附 failures 明細）
        UNSUPPORTED,

        // 沒被證明不能用、但有些檢查回 UNKNOWN（權限不足或要看實跑）
        INCONCLUSIVE,

        // 沒有 su／被拒／卡在授權窗（細分見 RootManager.Status）
        NO_ROOT,

        // 包裡沒有 libsingbox.so（這台機的 ABI 沒被建置覆蓋）
        NO_SIDECAR,

        // sidecar 在，但探針回不來（非零退出、吐不出 JSON、或建置少 with_ebpf tag）
        PROBE_FAILED,
    }

    data class Finding(
        val status: String,
        val scope: String,
        val importance: String,
        val feature: String,
        val detail: String,
    ) {
        // 只有「required 等級的 FAIL」才是不能用；performance／fallback 級的 WARN
        // （例如沒 BPF JIT、沒 TCX）只顯示不擋，否則會把能跑的內核講成不能跑。
        val isRequiredFailure: Boolean get() = status == STATUS_FAIL && importance == IMPORTANCE_REQUIRED
        val worthShowing: Boolean
            get() = !isRequiredFailure &&
                (status == STATUS_FAIL || status == STATUS_WARN || status == STATUS_UNKNOWN)

        // 顯示用：feature 是上游的穩定標識，detail 才是要給使用者看的那句。
        // 中文語系走繁中對照表（見 EbpfDetailZh），對不到就保留核心回傳的英文原文。
        val display: String get() = EbpfDetailZh.translate(detail).ifBlank { feature }
    }

    data class Report(
        val verdict: Verdict,
        val mode: Mode,
        val supported: Boolean,
        val kernelRelease: String,
        val platform: String,
        val architecture: String,
        val error: String,
        val failures: List<Finding>,
        val warnings: List<Finding>,
        val raw: String,
    ) {
        /** ✓ 能用、? 問不定、✗ 用不了或問不到。UI 三種符號要分得開。 */
        val mark: String
            get() = when (verdict) {
                Verdict.OK -> "✓"
                Verdict.INCONCLUSIVE -> "?"
                else -> "✗"
            }

        /**
         * 給 UI 的一行說明：優先講「為什麼不行」，行得通就講內核。
         * 這是**技術面**的文字（核心回的東西本身就是英文診斷資料，跟日誌同類）；
         * 我方自己的三種失敗（root／sidecar／問不到）在 UI 走在地化字串，
         * 呼叫方用 line(reason) 覆寫那三句。
         */
        fun summary(): String = when (verdict) {
            Verdict.OK -> kernelRelease.ifBlank { "supported" }

            Verdict.UNSUPPORTED -> failures.firstOrNull()?.display
                ?: error.ifBlank { "required feature missing" }

            Verdict.INCONCLUSIVE -> error.ifBlank { "some checks unanswered" }

            Verdict.NO_ROOT -> "root unavailable"

            Verdict.NO_SIDECAR -> "sing-box sidecar missing"

            Verdict.PROBE_FAILED -> error.ifBlank { "probe returned no report" }
        }

        /**
         * 設定頁的一行：`local  ✗ BPF_CGROUP_INET4_CONNECT …`。
         * 模式名（all／local／shared-network）與符號是核心側的穩定標識，當專有名詞處理、不譯。
         * reason 傳空字串＝「這層沒有我方自己的話說，沿用技術面預設」，
         * 呼叫方不必先判 verdict 再決定要不要傳。
         */
        fun line(reason: String = summary()): String = "${mode.wire}  $mark  ${reason.ifBlank { summary() }}"

        /**
         * 「一鍵複製診斷」的內容：判定＋逐條 finding＋原始 JSON。
         * 外部用戶只能把這種東西貼回來，所以這裡要包含足以判讀的細節，
         * 而不是只有一行結論（一行結論對我們查根因沒有幫助）。
         */
        fun diagnosticBlock(reason: String = summary()): String = buildString {
            appendLine(line(reason))
            if (platform.isNotBlank() || architecture.isNotBlank() || kernelRelease.isNotBlank()) {
                appendLine("    platform=$platform arch=$architecture kernel=$kernelRelease")
            }
            failures.forEach { appendLine("    ${it.status} [${it.scope}] ${it.importance} ${it.feature}: ${it.detail}") }
            warnings.forEach { appendLine("    ${it.status} [${it.scope}] ${it.importance} ${it.feature}: ${it.detail}") }
            if (error.isNotBlank()) appendLine("    error=$error")
            val body = raw.trim()
            if (body.isNotBlank()) {
                appendLine("    ----- raw -----")
                appendLine(body)
            }
        }
    }

    fun sidecarPresent(): Boolean = sidecarFile() != null

    fun sidecarFile(): File? {
        val dir = SagerNet.application.applicationInfo?.nativeLibraryDir ?: return null
        val exe = File(dir, SIDECAR_NAME)
        return if (exe.canRead()) exe else null
    }

    /**
     * 同步探針。呼叫方必須在 IO 執行緒上用它（su 一條命令動輒數秒，授權視窗還可能等人按）。
     */
    fun probe(mode: Mode): Report {
        val exe = sidecarFile()
        if (exe == null) {
            return Report(
                Verdict.NO_SIDECAR, mode, false, "", "", "",
                "no $SIDECAR_NAME in nativeLibraryDir", emptyList(), emptyList(), "",
            )
        }

        if (!RootManager.isRootAvailable()) {
            return Report(
                Verdict.NO_ROOT, mode, false, "", "", "",
                "su unavailable", emptyList(), emptyList(), "",
            )
        }

        // 路徑要引號包住：nativeLibraryDir 理論上不會有空格，但這是 root 命令列，
        // 拼錯一次就是拿錯檔案或執行到別的東西。
        val result = RootShell.exec(
            "${exe.absolutePath.quotepath()} ebpf-probe --mode ${mode.wire.quotepath()}",
        )
        // RootShell 把 stderr 併進來了（探針前若有 log 行會混在裡面）。
        // WriteKernelProbeReportJSON 用的是縮排 encoder，所以報告是**多行**的，
        // 找「第一個以 { 開頭的行」到「最後一個以 } 結尾的行」才是整份報告。
        val json = extractJsonObject(result.output)
        if (json == null) {
            Logs.w("EbpfProbe: ${mode.wire} 沒拿到 JSON（rc=${result.code}）：${result.output.trim()}")
            return Report(
                Verdict.PROBE_FAILED, mode, false, "", "", "",
                "rc=${result.code} ${result.output.trim().take(MAX_ERROR_CHARS)}",
                emptyList(), emptyList(), result.output,
            )
        }
        return parse(json, mode, result.output)
    }

    // internal 只為讓純 JVM 測試能直接餵 JSON（probe() 需要 Android context 與 su，
    // 測試裡拿不到；而「契約漂移會靜默變成不支援」這件事恰恰只有解析層需要守）。
    internal fun extractJsonObject(output: String): String? {
        val lines = output.lines()
        val start = lines.indexOfFirst { it.trimStart().startsWith("{") }
        if (start < 0) return null
        val end = lines.indexOfLast { it.trimEnd().endsWith("}") }
        if (end < start) return null
        return lines.subList(start, end + 1).joinToString("\n")
    }

    internal fun parse(json: String, mode: Mode, raw: String): Report = try {
        val o = JSONObject(json)
        // 認形：有 result／summary／findings 任一欄就是 CLI 形（sidecar 現行產出）；
        // 否則走 libbox 橋的 supported／failures／warnings 形。
        // 兩邊都留是因為 aar 一旦補上 with_ebpf，呼叫點隨時可能改走 JNI；
        // 到那時候只認一種形的話，報告會解析成「空報告」→ 判 PROBE_FAILED，
        // 而使用者看到的是「探針壞了」，實際上只是欄位名換了。
        if (o.has(KEY_RESULT) || o.has(KEY_SUMMARY) || o.has(KEY_FINDINGS)) {
            parseCliShape(o, mode, raw)
        } else {
            parseBridgeShape(o, mode, raw)
        }
    } catch (e: Exception) {
        Logs.e("EbpfProbe: JSON 解析失敗", e)
        Report(
            Verdict.PROBE_FAILED, mode, false, "", "", "",
            e.message ?: e.javaClass.simpleName, emptyList(), emptyList(), raw,
        )
    }

    private fun parseCliShape(o: JSONObject, mode: Mode, raw: String): Report {
        val findings = findingsOf(o.optJSONArray(KEY_FINDINGS))
        val failures = findings.filter { it.isRequiredFailure }
        val warnings = findings.filter { it.worthShowing }
        val summary = o.optJSONObject(KEY_SUMMARY)
        // required_failures 是 Go 側 RequiredFailures() 的序列化值：判準同一條，不另起爐灶。
        // summary 缺席時退回自己數 findings，兩條路都要比「看 result 字串」更早下定論。
        val requiredFailures = summary?.optInt(KEY_REQUIRED_FAILURES, -1)
            ?.takeIf { it >= 0 } ?: failures.size
        val result = o.optString(KEY_RESULT)
        val error = o.optString(KEY_ERROR).ifBlank { o.optString(KEY_ACTIVE_STATE_ERROR) }
        val verdict = when {
            requiredFailures > 0 -> Verdict.UNSUPPORTED

            result == RESULT_SUPPORTED -> Verdict.OK

            result == RESULT_INCONCLUSIVE -> Verdict.INCONCLUSIVE

            // 含 cmd_ebpf_probe_stub.go 的 {"result":"unsupported","error":"…without the
            // with_ebpf tag…"}：required_failures 是 0、findings 是空的，那「unsupported」
            // 講的是我方建置少了 tag，不是使用者的內核不行——兩者絕不能混。
            else -> Verdict.PROBE_FAILED
        }
        return Report(
            verdict = verdict,
            mode = mode,
            supported = verdict == Verdict.OK,
            kernelRelease = o.optString(KEY_KERNEL_RELEASE),
            platform = o.optString(KEY_PLATFORM),
            architecture = o.optString(KEY_ARCHITECTURE),
            error = error,
            failures = failures,
            warnings = warnings,
            raw = raw,
        )
    }

    private fun parseBridgeShape(o: JSONObject, mode: Mode, raw: String): Report {
        val failures = findingsOf(o.optJSONArray(KEY_FAILURES))
        val warnings = findingsOf(o.optJSONArray(KEY_WARNINGS))
        val error = o.optString(KEY_ERROR)
        val supported = o.optBoolean(KEY_SUPPORTED)
        return Report(
            verdict = when {
                supported && failures.isEmpty() -> Verdict.OK
                failures.isNotEmpty() -> Verdict.UNSUPPORTED
                else -> Verdict.PROBE_FAILED
            },
            mode = mode,
            supported = supported && failures.isEmpty(),
            kernelRelease = o.optString(KEY_KERNEL_RELEASE_CAMEL),
            platform = o.optString(KEY_PLATFORM),
            architecture = o.optString(KEY_ARCHITECTURE),
            error = error,
            failures = failures,
            warnings = warnings,
            raw = raw,
        )
    }

    private fun findingsOf(array: JSONArray?): List<Finding> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { i ->
            val o = array.optJSONObject(i) ?: return@map Finding("", "", "", "", "")
            Finding(
                status = o.optString(KEY_STATUS),
                scope = o.optString(KEY_SCOPE),
                importance = o.optString(KEY_IMPORTANCE),
                feature = o.optString(KEY_FEATURE),
                detail = o.optString(KEY_DETAIL),
            )
        }
    }

    // 單引號包住並把內嵌單引號還原成 '\''：這是 shell 層唯一穩的轉義寫法。
    private fun String.quotepath(): String = "'" + replace("'", "'\\''") + "'"

    private const val SIDECAR_NAME = "libsingbox.so"
    private const val MAX_ERROR_CHARS = 400

    private const val KEY_RESULT = "result"
    private const val KEY_SUMMARY = "summary"
    private const val KEY_FINDINGS = "findings"
    private const val KEY_REQUIRED_FAILURES = "required_failures"
    private const val KEY_KERNEL_RELEASE = "kernel_release"
    private const val KEY_KERNEL_RELEASE_CAMEL = "kernelRelease"
    private const val KEY_PLATFORM = "platform"
    private const val KEY_ARCHITECTURE = "architecture"
    private const val KEY_ERROR = "error"
    private const val KEY_ACTIVE_STATE_ERROR = "active_state_error"
    private const val KEY_SUPPORTED = "supported"
    private const val KEY_FAILURES = "failures"
    private const val KEY_WARNINGS = "warnings"
    private const val KEY_STATUS = "status"
    private const val KEY_SCOPE = "scope"
    private const val KEY_IMPORTANCE = "importance"
    private const val KEY_FEATURE = "feature"
    private const val KEY_DETAIL = "detail"

    const val STATUS_FAIL = "FAIL"
    const val STATUS_WARN = "WARN"
    const val STATUS_UNKNOWN = "UNKNOWN"
    private const val IMPORTANCE_REQUIRED = "required"

    private const val RESULT_SUPPORTED = "supported"
    private const val RESULT_INCONCLUSIVE = "inconclusive"
}
