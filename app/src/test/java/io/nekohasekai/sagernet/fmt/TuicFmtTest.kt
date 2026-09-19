package io.nekohasekai.sagernet.fmt

import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.tuic.buildSingBoxOutboundTuicBean
import io.nekohasekai.sagernet.fmt.tuic.parseTuic
import io.nekohasekai.sagernet.fmt.tuic.toUri
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * TUIC profiles expose sing-box's DialerOptions.udp_fragment. The core enables UDP
 * fragmentation for the tuic outbound by default, so "profile did not choose" has to stay
 * distinguishable from an explicit "off" through the link, the generated config and the
 * stored profile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TuicFmtTest {

    private fun tuicBean() = TuicBean().apply {
        serverAddress = "example.com"
        serverPort = 443
        uuid = "cf890019-f81d-4f1c-43c5-4e652bb4f1c4"
        token = "password"
        initializeDefaultValues()
    }

    @Test
    fun unset_keepsCoreDefaultAndStaysOutOfLink() {
        val bean = tuicBean()

        assertNull(bean.udpFragment)
        assertNull(buildSingBoxOutboundTuicBean(bean).udp_fragment)
        assertFalse(bean.toUri().contains("udp_fragment"))
    }

    @Test
    fun explicitStates_areWrittenIntoTheOutboundAndRoundTripThroughTheLink() {
        listOf(true, false).forEach { state ->
            val bean = tuicBean().apply { udpFragment = state }

            assertEquals(state, buildSingBoxOutboundTuicBean(bean).udp_fragment)

            val exported = bean.toUri()
            assertTrue(exported.contains("udp_fragment=${if (state) "1" else "0"}"))
            assertEquals(state, parseTuic(exported).apply { initializeDefaultValues() }.udpFragment)
        }
    }

    @Test
    fun parseTuicUri_readsUdpFragmentSpellings() {
        val base = "tuic://cf890019-f81d-4f1c-43c5-4e652bb4f1c4:password@example.com:443/"

        assertEquals(false, parseTuic("$base?udp_fragment=0").udpFragment)
        assertEquals(true, parseTuic("$base?udp_fragment=1").udpFragment)
        assertEquals(false, parseTuic("$base?udp_fragment=false").udpFragment)
        assertNull(parseTuic("$base?udp_fragment=auto").udpFragment)
        assertNull(parseTuic(base).udpFragment)
    }

    @Test
    fun serialization_roundTripsEveryState() {
        listOf<Boolean?>(null, true, false).forEach { state ->
            val bean = tuicBean().apply { udpFragment = state }

            val encoded = KryoConverters.serialize(bean)
            val decoded = KryoConverters.tuicDeserialize(encoded)!!

            assertEquals(state, decoded.udpFragment)
            assertArrayEquals(encoded, KryoConverters.serialize(decoded))
        }
    }

    @Test
    fun version2Profile_leavesUdpFragmentUnset() {
        val decoded = KryoConverters.tuicDeserialize(legacyVersion2Profile())!!

        assertNull(decoded.udpFragment)
        assertEquals("password", decoded.token)
        assertEquals(5, decoded.protocolVersion)
    }

    /** Writes the exact field order used by TuicBean schema version 2. */
    private fun legacyVersion2Profile(): ByteArray {
        val bytes = ByteArrayOutputStream()
        ByteBufferOutput(bytes).use { output ->
            output.writeInt(2)

            // AbstractBean fields.
            output.writeString("example.com")
            output.writeInt(443)

            output.writeString("password")
            output.writeString("")
            output.writeString("native")
            output.writeString("cubic")
            output.writeString("")
            output.writeBoolean(false)
            output.writeBoolean(false)
            output.writeInt(1400)
            output.writeString("")
            output.writeBoolean(false)
            output.writeBoolean(false)
            output.writeString("")
            output.writeInt(5)
            output.writeString("cf890019-f81d-4f1c-43c5-4e652bb4f1c4")

            // AbstractBean trailing fields written by serializeToBuffer().
            output.writeInt(1)
            output.writeString("")
            output.writeString("")
            output.writeString("")
            output.flush()
        }
        return bytes.toByteArray()
    }
}
