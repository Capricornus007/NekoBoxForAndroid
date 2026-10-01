package moe.matsuri.nb4a.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class JavaUtilTest {
    @Test
    fun jsonNumbersPreserveJavaBehavior() {
        val values = JavaUtil.gson.fromJson("{whole: 9007199254740993, fraction: 1.5}", Map::class.java)
        assertEquals(9007199254740993L, values["whole"])
        assertEquals(1.5, values["fraction"])
        assertEquals("\"<value>\"", JavaUtil.gson.toJson("<value>"))
    }
}
