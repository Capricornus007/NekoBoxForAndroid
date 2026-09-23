package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.trojan_go.toUri
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TrojanGoFmtTest {

    @Test
    fun emptyEncryptionIsNotExported() {
        for (value in listOf(null, "", "none")) {
            val bean = TrojanGoBean().apply {
                initializeDefaultValues()
                serverAddress = "example.com"
                serverPort = 443
                password = "secret"
                encryption = value
            }

            val uri = bean.toUri().replaceFirst("trojan-go://", "https://").toHttpUrl()

            assertNull(uri.queryParameter("encryption"))
        }
    }

    @Test
    fun encryptionDoesNotDependOnTransportType() {
        val bean = TrojanGoBean().apply {
            initializeDefaultValues()
            serverAddress = "example.com"
            serverPort = 443
            password = "secret"
            type = "none"
            encryption = "ss;aes-128-gcm:example-key"
        }

        val uri = bean.toUri().replaceFirst("trojan-go://", "https://").toHttpUrl()

        assertEquals(bean.encryption, uri.queryParameter("encryption"))
    }
}
