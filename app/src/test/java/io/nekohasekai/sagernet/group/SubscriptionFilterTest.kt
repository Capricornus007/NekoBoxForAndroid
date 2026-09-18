package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.SubscriptionFilterMode
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression lock for the subscription name filter (OwnBox 7fe530afd "filter include does not
 * work"): an include filter has to actually narrow the list, it has to survive a pattern the
 * regex engine rejects, and the delete circuit breaker must not read the smaller result as a
 * truncated subscription response.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SubscriptionFilterTest {

    private val names = listOf("Hong Kong 01", "HK Premium", "Tokyo 01", "Singapore 01", "US West")

    private fun nodes(): List<AbstractBean> = names.map { name ->
        SOCKSBean().apply {
            serverAddress = "192.0.2.1"
            serverPort = 1080
            this.name = name
            initializeDefaultValues()
        }
    }

    private fun filter(mode: Int, regex: String) =
        RawUpdater.applySubscriptionFilter(nodes(), mode, regex).map { it.displayName() }

    @Test
    fun includeKeepsMatchingNodes() {
        assertEquals(
            listOf("Hong Kong 01", "HK Premium"),
            filter(SubscriptionFilterMode.INCLUDE, "hk|hong kong"),
        )
    }

    @Test
    fun includeIgnoresCaseAndSurroundingWhitespace() {
        assertEquals(
            listOf("Hong Kong 01", "HK Premium"),
            filter(SubscriptionFilterMode.INCLUDE, "  HK|HONG KONG  "),
        )
    }

    @Test
    fun excludeDropsMatchingNodes() {
        assertEquals(
            listOf("Tokyo 01", "Singapore 01", "US West"),
            filter(SubscriptionFilterMode.EXCLUDE, "hk|hong kong"),
        )
    }

    @Test
    fun brokenPatternKeepsTheWholeListInsteadOfFailingTheUpdate() {
        assertEquals(names, filter(SubscriptionFilterMode.INCLUDE, "hk("))
    }

    @Test
    fun disabledOrBlankFilterIsANoOp() {
        assertEquals(names, filter(SubscriptionFilterMode.INCLUDE, ""))
        assertEquals(names, filter(SubscriptionFilterMode.DISABLED, "hk"))
    }

    @Test
    fun filterAloneIsNotTreatedAsATruncatedResponse() {
        assertFalse(RawUpdater.subscriptionFilterActive(SubscriptionFilterMode.DISABLED, "hk"))
        assertFalse(RawUpdater.subscriptionFilterActive(SubscriptionFilterMode.INCLUDE, "  "))
        assertTrue(RawUpdater.subscriptionFilterActive(SubscriptionFilterMode.INCLUDE, "hk"))

        // 50 stored nodes, an include filter leaves 5: deleting 45 is what the user asked for.
        assertFalse(RawUpdater.deletionCircuitBreak(50, 5, filterActive = true))
        // Same numbers without a filter means the endpoint served a truncated list: keep the rows.
        assertTrue(RawUpdater.deletionCircuitBreak(50, 5, filterActive = false))
        // Groups below the breaker's minimum size are never protected.
        assertFalse(RawUpdater.deletionCircuitBreak(5, 1, filterActive = false))
    }
}
