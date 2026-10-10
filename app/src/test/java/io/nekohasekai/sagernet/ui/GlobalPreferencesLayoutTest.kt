package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * global_preferences.xml groups its settings into hub rows that SettingsPreferenceFragment renders
 * either as a keyed nested screen opened by key or as a titled collapsible category. This guards the
 * shape that navigation relies on: every hub row is titled, and moving items between pages never
 * drops a setting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class GlobalPreferencesLayoutTest {

    @Test
    fun hubRowsAreKeyedScreensAndNoSettingIsLost() {
        val parser = RuntimeEnvironment.getApplication().resources.getXml(R.xml.global_preferences)
        val screens = mutableListOf<String>()
        val leafKeys = mutableListOf<String>()
        var depth = 0
        try {
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        depth++
                        val key = parser.getAttributeValue(APP_NAMESPACE, "key")
                        when {
                            depth == 1 -> assertEquals("PreferenceScreen", parser.name)

                            depth == 2 -> {
                                // A hub row is either a keyed nested screen or a titled collapsible category.
                                val isScreen = parser.name == "PreferenceScreen"
                                assertTrue(
                                    "hub row $key is neither a screen nor a category",
                                    isScreen || parser.name.endsWith("CollapsibleCategory"),
                                )
                                if (isScreen) {
                                    assertTrue("hub row without key", key.orEmpty().startsWith("screen_"))
                                    screens += requireNotNull(key)
                                }
                                assertTrue(
                                    "hub row without title",
                                    parser.getAttributeResourceValue(APP_NAMESPACE, "title", 0) != 0,
                                )
                            }

                            parser.name != "PreferenceCategory" -> {
                                assertFalse("nested screens deeper than one level", parser.name == "PreferenceScreen")
                                leafKeys += requireNotNull(key) { "${parser.name} without key at depth $depth" }
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> depth--
                }
                parser.next()
            }
        } finally {
            parser.close()
        }

        assertEquals(screens, screens.distinct())
        assertEquals(leafKeys.sorted(), leafKeys.distinct().sorted())
        assertEquals(EXPECTED_KEYS, leafKeys.toSet())
    }

    private companion object {
        const val APP_NAMESPACE = "http://schemas.android.com/apk/res-auto"

        /** Every leaf control in global_preferences.xml, mirroring the order the layout declares them. */
        val EXPECTED_KEYS = setOf(
            "acquireWakeLock", "adaptiveTrafficPolling", "allowInsecureOnRequest", "alwaysShowAddress", "appLanguage",
            "appTLSVersion", "appendHttpProxy", "autoSwitchTimeoutDuration", "autoUpdateRuleAssets", "bypassLan",
            "bypassLanInCore", "clearCache", "concurrentDial", "confirmProfileDelete", "connectionTestURL",
            "createClearCacheShortcut", "defaultSubscriptionUserAgent", "directDns", "dnsHosts",
            "domain_strategy_for_direct", "domain_strategy_for_remote", "domain_strategy_for_server", "dynamicColors",
            "enableAutoSwitchTimeout", "enableDnsRouting", "enableFakeDns", "enableHevTun", "enableTLSFragment",
            "fragmentInterval", "fragmentLength", "globalAllowInsecure", "globalCustomConfig", "gpsCoordinates",
            "gpsLookupUrl", "gpsSpoofing", "gpsSpoofingNotice", "gpsSpoofingRemove", "hideFromRecentApps",
            "httpProxyBypass", "ipv6Mode", "isAutoConnect", "killSwitch", "lanSharing", "localControlApi",
            "localProxySettings", "logLevel", "maskAddress", "meteredNetwork", "mtu", "networkAutomation",
            "networkAutomationRules", "networkChangeResetConnections", "nightTheme", "profileTrafficStatistics",
            "protectionAlwaysOn", "protectionBattery", "protectionLockdown", "protectionNotes", "proxyApps", "remoteDns",
            "resetSettings", "resolveDestination", "restartProfileOnNetworkChange", "rulesGeoipUrl", "rulesGeositeUrl",
            "rulesProvider", "rulesUpdateInterval", "serviceMode", "showBottomBar", "showDirectSpeed",
            "showGroupInNotification", "speedInterval", "speedTestMode", "speedTestTimeoutMs", "spoofUserAgent",
            "strictRoute", "subscriptionDirectFallback", "trafficSniffing", "tunImplementation", "wakeResetConnections",
            "webrtcLeakProtection",
        )
    }
}
