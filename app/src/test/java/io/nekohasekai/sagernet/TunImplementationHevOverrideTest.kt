package io.nekohasekai.sagernet

import androidx.preference.ListPreference
import androidx.preference.Preference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Regression for the mod-25 settings-page crash: androidx 的 [Preference.setSummary]
 * 在已有 SummaryProvider 的偏好上会直接抛 [IllegalStateException]（TUN 实作在 XML 里
 * 挂着 useSimpleSummaryProvider，用户开启 Hev TUN 后进入设置页即崩）。接管提示必须
 * 走 SummaryProvider 交换，恢复时放回原 provider。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TunImplementationHevOverrideTest {

    @Test
    fun setSummaryWithProviderThrows_butProviderSwapIsSafe() {
        val pref = ListPreference(RuntimeEnvironment.getApplication())
        pref.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()

        // mod-25 崩溃路径：SummaryProvider 已设时调用 setSummary 必炸
        assertThrows(IllegalStateException::class.java) { pref.summary = "已由 Hev TUN 接管" }

        // 修复路径：换 provider（接管提示），再还原原 provider
        val original = pref.summaryProvider
        pref.summaryProvider = Preference.SummaryProvider<ListPreference> { "已由 Hev TUN 接管" }
        assertEquals("已由 Hev TUN 接管", pref.summary)
        pref.summaryProvider = original
    }
}
