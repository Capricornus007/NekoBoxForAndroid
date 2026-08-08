package io.nekohasekai.sagernet.fmt.v2ray

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class XhttpRangeNormalizerTest {

    @Test
    fun normalizesEverySupportedNumericRangeField() {
        val converted = JsonParser.parseString(
            normalizeSingBoxXhttpRanges(
                """{
                    "x_padding_bytes": 100,
                    "sc_max_each_post_bytes": 3000000.0,
                    "sc_min_posts_interval_ms": 5,
                    "sc_stream_up_server_secs": 20
                }""".trimIndent()
            )
        ).asJsonObject

        mapOf(
            "x_padding_bytes" to "100",
            "sc_max_each_post_bytes" to "3000000",
            "sc_min_posts_interval_ms" to "5",
            "sc_stream_up_server_secs" to "20"
        ).forEach { (key, expected) ->
            assertEquals(expected, converted[key].asString)
            assertEquals(true, converted[key].asJsonPrimitive.isString)
        }
    }

    @Test
    fun normalizesNumericRangesInExistingSingBoxExtra() {
        val converted = JsonParser.parseString(
            normalizeSingBoxXhttpRanges(
                """{
                    "x_padding_bytes": "50-150",
                    "sc_max_each_post_bytes": 3000000,
                    "sc_min_posts_interval_ms": "5-10",
                    "download": {
                        "x_padding_bytes": 100
                    }
                }""".trimIndent()
            )
        ).asJsonObject

        assertEquals("50-150", converted["x_padding_bytes"].asString)
        assertEquals("3000000", converted["sc_max_each_post_bytes"].asString)
        assertEquals("5-10", converted["sc_min_posts_interval_ms"].asString)
        assertEquals("100", converted["download"].asJsonObject["x_padding_bytes"].asString)
    }

    @Test
    fun preservesAlreadyCompatibleRangesAndOtherFields() {
        val original = """{
            "sc_max_each_post_bytes": {"from": 1000000, "to": 3000000},
            "sc_min_posts_interval_ms": "5-10",
            "no_grpc_header": true
        }""".trimIndent()

        assertEquals(original, normalizeSingBoxXhttpRanges(original))
    }
}
