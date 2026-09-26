package io.nekohasekai.sagernet.fmt.juicity

import com.google.common.io.BaseEncoding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JuicityFmtTest {

    @Test
    fun convertsHexCertificatePinToPaddedUrlSafeBase64() {
        val bytes = ByteArray(32) { 0xff.toByte() }
        val hex = bytes.joinToString("") { "%02x".format(it) }

        val bean = parseJuicity(
            "juicity://user:password@example.com:443?pinned_certchain_sha256=$hex"
        )

        assertEquals(BaseEncoding.base64Url().encode(bytes), bean.pinnedCertchainSha256)
        assertTrue(bean.pinnedCertchainSha256.endsWith('='))
    }
}
