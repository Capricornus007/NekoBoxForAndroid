package io.nekohasekai.sagernet.utils

import moe.matsuri.nb4a.SingBoxOptions.Rule_DefaultOptions
import moe.matsuri.nb4a.utils.JavaUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分應用規則裡「re:」正則條目的行為驗證。走的是 ConfigBuilder 生配置時同一條路
 * （[RulePackages.applyRegexToRule]），驗的是真正要進 sing-box 的那份 JSON：
 *
 *   1. 開關關著（預設）時，正則條目整個不出現在輸出裡；
 *   2. 開關打開時，去掉前綴的正則進 package_name_regex，而且不會混進 package_name；
 *   3. 沒有 re: 前綴的條目兩種情況下都照舊走原本的路徑（交回呼叫端翻成 UID），JSON 一個字都不動。
 */
class RulePackagesRegexTest {

    private val plainPackage = "com.android.vending"
    private val regexPattern = "^com\\.miHoYo.*"

    private fun treeFor(entries: Collection<String>, regexEnabled: Boolean) = Rule_DefaultOptions().let { rule ->
        // 一般套件名走的是原本那條「先翻成 UID」的路（要用 PackageCache，那是 Android 的東西），
        // 這裡補一個 UID 當代表，讓 JSON 不是空的，否則「沒有殘留」那幾條斷言會假過。
        rule.user_id = listOf(10123)
        RulePackages.applyRegexToRule(rule, entries, regexEnabled)
        JavaUtil.gson.toJsonTree(rule).asJsonObject
    }

    @Test
    fun switchOffDropsRegexEntriesEntirely() {
        val json = JavaUtil.gson.toJson(
            treeFor(listOf(plainPackage, RulePackages.regexEntry(regexPattern)), regexEnabled = false),
        )

        assertFalse(json.contains("package_name_regex"))
        assertFalse(json.contains("package_name"))
        // 前綴與內容都不能殘留：留著就會被核心當成套件名，或讓整份配置多出一個條目
        // （比對用 miHoYo 這段，正則裡的反斜線在 JSON 裡會被轉義，整串比對會假過）
        assertFalse(json.contains("miHoYo"))
        assertFalse(json.contains(RulePackages.REGEX_PREFIX))
    }

    @Test
    fun switchOnMovesRegexEntriesIntoPackageNameRegex() {
        val tree = treeFor(listOf(plainPackage, RulePackages.regexEntry(regexPattern)), regexEnabled = true)
        val emitted = tree.getAsJsonArray("package_name_regex").map { it.asString }

        assertEquals(listOf(regexPattern), emitted)
        assertFalse(tree.has("package_name"))
        // 一般套件名不該被一起塞進正則欄位，它走的是原本的 UID 路徑
        assertFalse(emitted.contains(plainPackage))
    }

    @Test
    fun plainEntriesKeepTheOldPathNoMatterWhatTheSwitchSays() {
        val entries = listOf(plainPackage, "com.google.android.gms")
        val switchOff = treeFor(entries, regexEnabled = false)
        val switchOn = treeFor(entries, regexEnabled = true)

        assertFalse(switchOff.has("package_name"))
        assertFalse(switchOff.has("package_name_regex"))
        // 既有的規則就算使用者把開關轉開，輸出也必須逐位元組相同
        assertEquals(JavaUtil.gson.toJson(switchOff), JavaUtil.gson.toJson(switchOn))
        // 兩邊都把一般套件名原封不動交回呼叫端去翻 UID
        assertEquals(entries, RulePackages.split(entries, regexEnabled = false).plain)
        assertEquals(entries, RulePackages.split(entries, regexEnabled = true).plain)
    }

    @Test
    fun invalidRegexNeverReachesTheCore() {
        val entries = listOf(RulePackages.regexEntry("^com(?=\\.miHoYo)"), plainPackage)

        assertEquals(emptyList<String>(), RulePackages.split(entries, regexEnabled = true).regex)
        assertEquals(listOf(plainPackage), RulePackages.split(entries, regexEnabled = true).plain)
        assertFalse(JavaUtil.gson.toJson(treeFor(entries, regexEnabled = true)).contains("package_name_regex"))
    }

    @Test
    fun checkPatternBlocksWhatWouldBreakStorageOrCore() {
        assertEquals(RulePackages.PatternError.BLANK, RulePackages.checkPattern("   "))
        // 清單在資料庫裡用逗號串接，正則帶逗號會被拆爛（^\d{2,4}$ 那種寫法因此不能用）
        assertEquals(RulePackages.PatternError.SEPARATOR, RulePackages.checkPattern("^a{2,4}$"))
        // 回溯引擎那套語法核心（Go RE2）不認，會整份配置載入失敗
        assertEquals(RulePackages.PatternError.UNSUPPORTED, RulePackages.checkPattern("^com(?=\\.foo)"))
        assertEquals(RulePackages.PatternError.UNSUPPORTED, RulePackages.checkPattern("^(a)\\1$"))
        assertEquals(RulePackages.PatternError.UNSUPPORTED, RulePackages.checkPattern("a*+b"))
        assertEquals(RulePackages.PatternError.INVALID, RulePackages.checkPattern("^("))
        // RE2 认得這些：一般萬用字元、旗標群組、非捕獲群組
        assertEquals(RulePackages.PatternError.NONE, RulePackages.checkPattern(regexPattern))
        assertEquals(RulePackages.PatternError.NONE, RulePackages.checkPattern("^(?i)com\\."))
        assertEquals(RulePackages.PatternError.NONE, RulePackages.checkPattern("^(?:com|net)\\."))
        assertEquals(RulePackages.PatternError.NONE, RulePackages.checkPattern("^[a-z.]+"))
    }

    @Test
    fun appListRewriteKeepsRegexEntriesButLeavesLegacyUntouched() {
        val legacy = "$plainPackage\ncom.google.android.gms"
        // 沒有正則條目時原樣回傳，Application 清單那頁的行為跟從前一模一樣
        assertEquals("com.google.android.gms", RulePackages.preserveRegex(legacy, "com.google.android.gms"))

        val withRegex = "$legacy\n${RulePackages.regexEntry(regexPattern)}"
        val rewritten = RulePackages.preserveRegex(withRegex, "com.google.android.gms")

        assertEquals(listOf("com.google.android.gms"), RulePackages.plainLines(rewritten))
        assertEquals(listOf(RulePackages.regexEntry(regexPattern)), RulePackages.regexLines(rewritten))
        assertTrue(rewritten.endsWith(RulePackages.regexEntry(regexPattern)))
    }
}
