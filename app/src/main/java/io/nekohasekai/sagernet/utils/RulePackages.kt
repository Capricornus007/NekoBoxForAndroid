package io.nekohasekai.sagernet.utils

import moe.matsuri.nb4a.SingBoxOptions.Rule_DefaultOptions
import java.util.regex.PatternSyntaxException

/**
 * 分應用規則的「套件」清單除了真實的 Android 套件名之外，還接受一種以 [REGEX_PREFIX] 開頭
 * 的特殊條目，它代表「用正則表達式比對套件名」，一條就能蓋住一批套件（例如
 * `re:^com.miHoYo`）。
 *
 * 為什麼用前綴而不是另開一個資料庫欄位：`re:` 不是合法的 Android 套件名，兩者不會撞車；
 * 舊存檔與舊備份裡沒有這種條目，讀進來就是沒有，行為跟從前一模一樣，所以不需要 Room 版本
 * 遷移，也不需要動匯入匯出格式。
 *
 * 限制：清單存進資料庫時是用逗號串起來的（見 StringCollectionConverter），因此正則內容不允許
 * 出現逗號，否則存進去再讀出來會被拆爛（`^\d{2,4}$` 這種寫法要用 `[0-9]{2}([0-9])?` 之類的
 * 替代寫法）。UI 那層由 [checkPattern] 擋住。
 */
object RulePackages {

    const val REGEX_PREFIX = "re:"

    // 正則長度上限：核心編譯 RE2 要時間，超長樣式多半是貼錯東西，而且清單會被它淹沒。
    const val MAX_PATTERN_LENGTH = 512

    // Go 的 RE2 只認這四個內聯旗標（(?i) (?m) (?s) (?U) 或 (?flags:…)）。
    private val RE2_INLINE_FLAGS = charArrayOf('i', 'm', 's', 'U')

    /** 這條「套件」條目是不是正則條目。 */
    fun isRegexEntry(entry: String) = entry.startsWith(REGEX_PREFIX)

    /** 拿掉 [REGEX_PREFIX] 之後的正則內容（前後空白一併去掉）。 */
    fun patternOf(entry: String) = entry.removePrefix(REGEX_PREFIX).trim()

    /** 把一條條目的原文（含前綴）組回清單用的形式。 */
    fun regexEntry(pattern: String) = REGEX_PREFIX + pattern.trim()

    data class Split(val plain: List<String>, val regex: List<String>)

    /**
     * 把條目套到已經建好的路由規則物件上：通過檢核的正則條目去前綴後寫進 package_name_regex，
     * 其餘條目不碰（由呼叫端翻成 UID，維持從前的做法）。
     *
     * 沒有任何正則條目時這個函式不碰傳進來的物件，輸出的 JSON 跟從前逐位元組相同 —— 包括開關
     * 被開啟、但規則裡只有普通套件名的情況。
     */
    fun applyRegexToRule(rule: Rule_DefaultOptions, entries: Collection<String>, regexEnabled: Boolean) {
        val regex = split(entries, regexEnabled).regex
        if (regex.isNotEmpty()) {
            rule.package_name_regex = regex
        }
    }

    /**
     * 把規則裡的條目分成「一般套件名」與「正則」兩組，順序維持原樣。
     *
     * [regexEnabled] 為 false（預設）時，正則條目整個丟掉：既不進 user_id，也不進
     * package_name_regex，生成的配置跟沒有這功能時逐位元組相同。為 true 時去掉前綴寫進
     * package_name_regex，通過不了 [checkPattern] 的條目一律略過（寧可不套，也不要讓核心
     * 載入配置時整個失敗）。
     */
    fun split(entries: Collection<String>, regexEnabled: Boolean): Split {
        val plain = mutableListOf<String>()
        val regex = mutableListOf<String>()
        for (entry in entries) {
            if (entry.isBlank()) continue
            if (isRegexEntry(entry)) {
                if (!regexEnabled) continue
                val pattern = patternOf(entry)
                if (pattern.isNotEmpty() && checkPattern(pattern) == PatternError.NONE && !regex.contains(pattern)) {
                    regex.add(pattern)
                }
            } else {
                plain.add(entry)
            }
        }
        return Split(plain, regex)
    }

    /** 清單在設定頁的暫存區裡是「一行一條」的字串，這裡取出其中的一般套件名。 */
    fun plainLines(value: String) = value.lineSequence().filter { it.isNotBlank() && !isRegexEntry(it) }.toList()

    /** 同上，取出其中的正則條目（連前綴一起，保存時要原樣寫回）。 */
    fun regexLines(value: String) = value.lineSequence().filter { it.isNotBlank() && isRegexEntry(it) }.toList()

    /** 組回「一行一條」：一般套件名在前、正則條目在後，順序固定才好比對差異。 */
    fun join(plain: Collection<String>, regex: Collection<String>): String = (plain.filter { it.isNotBlank() && !isRegexEntry(it) } + regex.filter { isRegexEntry(it) })
        .distinct().joinToString("\n")

    /**
     * 應用程式清單那頁每次勾選都把整包 routePackages 重寫一遍（它只看得到已安裝的 App），
     * 直接覆寫會把正則條目一起抹掉。用它把既有的正則條目撿回來。
     * 手上一條正則都沒有時原樣回傳，行為跟從前完全相同。
     */
    fun preserveRegex(existing: String, rewritten: String): String {
        val regex = (regexLines(existing) + regexLines(rewritten)).distinct()
        if (regex.isEmpty()) return rewritten
        return join(plainLines(rewritten), regex)
    }

    enum class PatternError {
        NONE,
        BLANK,
        SEPARATOR,
        UNSUPPORTED,
        INVALID,
    }

    /**
     * 加入正則條目前的檢核，回 NONE 才算可用。這裡只做「一定會出事」的檢查：空字串、含逗號
     * （資料庫的分隔符）、含換行、用了核心不支援的語法、以及連 Java 都編不過的亂碼。
     * 目的就是把崩潰擋在 UI 層，不要留給核心載入配置的時候。
     */
    fun checkPattern(pattern: String): PatternError {
        val value = pattern.trim()
        if (value.isEmpty()) return PatternError.BLANK
        if (value.length > MAX_PATTERN_LENGTH) return PatternError.INVALID
        if (value.contains(',') || value.contains('\n') || value.contains('\r')) return PatternError.SEPARATOR
        if (usesUnsupportedSyntax(value)) return PatternError.UNSUPPORTED
        return try {
            Regex(value)
            PatternError.NONE
        } catch (_: PatternSyntaxException) {
            PatternError.INVALID
        } catch (_: IllegalArgumentException) {
            PatternError.INVALID
        }
    }

    /**
     * 核心用的是 Go 的 RE2，沒有回溯引擎那套向前看／向後看、回溯參照、所有格量詞與原子組。
     * 這些寫法 Java 的正則編譯得過、丟進核心卻會讓整份配置載入失敗，所以在這裡先擋。
     * 字元組 `[...]` 內部與跳脫序列不當語法檢查，避免誤殺。
     */
    private fun usesUnsupportedSyntax(pattern: String): Boolean {
        var index = 0
        var inClass = false
        while (index < pattern.length) {
            val char = pattern[index]
            if (char == '\\') {
                val next = pattern.getOrNull(index + 1)
                // \1 .. \9 是回溯參照，RE2 沒有；結尾孤立的反斜線留給編譯器報錯。
                if (next != null && next in '1'..'9') return true
                index += 2
                continue
            }
            if (inClass) {
                if (char == ']') inClass = false
                index++
                continue
            }
            when (char) {
                '[' -> inClass = true

                '(' -> if (pattern.startsWith("(?", index)) {
                    if (!groupSupportedAt(pattern, index)) return true
                }

                '*', '+', '?', '}' -> if (pattern.getOrNull(index + 1) == '+') return true
            }
            index++
        }
        return false
    }

    /**
     * `(?…` 開頭的群組只有 RE2 认得的寫法才算支援：`(?:…)`、`(?P<name>…)`、`(?flags)`、
     * `(?flags:…)`。其餘 `(?=` `(?!` `(?<` `(?>` `(?<name>` `(?1)` 全是回溯引擎專屬。
     */
    private fun groupSupportedAt(pattern: String, index: Int): Boolean {
        var cursor = index + 2
        if (pattern.getOrNull(cursor) == 'P') return pattern.getOrNull(cursor + 1) == '<'
        while (true) {
            val char = pattern.getOrNull(cursor) ?: return false
            if (char in RE2_INLINE_FLAGS) {
                cursor++
                continue
            }
            return char == ':' || char == ')'
        }
    }
}
