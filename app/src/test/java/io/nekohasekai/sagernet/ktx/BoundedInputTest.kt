package io.nekohasekai.sagernet.ktx

import org.junit.Assert.*
import org.junit.Test

class BoundedInputTest {
    @Test fun acceptsExactLimit() {
        assertArrayEquals(byteArrayOf(1, 2), byteArrayOf(1, 2).inputStream().readBytesLimited(2))
    }

    @Test fun rejectsOneByteOverLimit() {
        assertThrows(IllegalArgumentException::class.java) { byteArrayOf(1, 2, 3).inputStream().readBytesLimited(2) }
    }

    @Test fun handlesEmptyInputAndZeroLimit() {
        assertEquals(0, byteArrayOf().inputStream().readBytesLimited(0).size)
    }

    @Test fun handlesMaximumIntegerLimit() {
        assertEquals(1, byteArrayOf(1).inputStream().readBytesLimited(Int.MAX_VALUE).size)
    }
}
