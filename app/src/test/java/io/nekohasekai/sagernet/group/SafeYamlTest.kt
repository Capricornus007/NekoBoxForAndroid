package io.nekohasekai.sagernet.group

import org.junit.Assert.*
import org.junit.Test

class SafeYamlTest {
    class Marker {
        init {
            constructed = true
        }
        companion object {
            var constructed = false
        }
    }

    @Test fun acceptsAnchorsAndOrdinaryClashData() {
        val result = parseClashYaml("defaults: &a {type: socks5, port: 1080}\nproxies: [*a]")
        assertEquals(1080, ((result["proxies"] as List<*>)[0] as Map<*, *>)["port"])
    }

    @Test fun refusesJavaTagsWithoutConstructingClasses() {
        Marker.constructed = false
        assertThrows(Exception::class.java) { parseClashYaml("proxies: [!!io.nekohasekai.sagernet.group.SafeYamlTest\$Marker {}]") }
        assertFalse(Marker.constructed)
    }

    @Test fun refusesDuplicateKeys() {
        assertThrows(Exception::class.java) { parseClashYaml("proxies: []\nproxies: []") }
    }

    @Test fun refusesDeepNesting() {
        assertThrows(Exception::class.java) { parseClashYaml("proxies: " + "[".repeat(60) + "0" + "]".repeat(60)) }
    }
}
