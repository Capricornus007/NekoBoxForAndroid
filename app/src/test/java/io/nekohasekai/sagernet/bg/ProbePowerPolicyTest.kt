package io.nekohasekai.sagernet.bg

import org.junit.Assert.*
import org.junit.Test

class ProbePowerPolicyTest {
    @Test fun screenOnDoesNotResumeBeforeUnlockOrWhileIdle() {
        assertTrue(pauseHealthChecks(false, false, false))
        assertTrue(pauseHealthChecks(true, true, false))
        assertTrue(pauseHealthChecks(true, false, true))
        assertFalse(pauseHealthChecks(true, false, false))
    }
}
