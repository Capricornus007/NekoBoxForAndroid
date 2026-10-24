package io.nekohasekai.sagernet.bg

import org.junit.Assert.*
import org.junit.Test

class SubscriptionScheduleTest {
    @Test fun overdueSubscriptionStartsNow() { assertEquals(0L, subscriptionDelaySeconds(2000, 0, 15)) }
    @Test fun calculatesTimeUntilDue() { assertEquals(600L, subscriptionDelaySeconds(1300, 1000, 15)) }
    @Test fun clampsToWorkManagerMinimum() { assertEquals(900L, subscriptionDelaySeconds(1000, 1000, 1)) }
    @Test fun usesEachSubscriptionsOwnInterval() { assertEquals(3300L, subscriptionDelaySeconds(1300, 1000, 60)) }
}
