package io.nekohasekai.sagernet.fmt.internal

import org.junit.Assert.assertThrows
import org.junit.Test

class HealthCheckConfigTest {
    @Test fun acceptsHttpsAndRejectsCredentialsFragmentsAndInvalidPorts() {
        validateHealthUrl("https://example.com/check?value=1")
        for (url in listOf("http://example.com", "https://", "https://example.com:0", "https://user:secret@example.com", "https://example.com/#fragment", "bad url")) {
            assertThrows(IllegalArgumentException::class.java) { validateHealthUrl(url) }
        }
    }
}
