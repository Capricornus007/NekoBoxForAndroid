package io.nekohasekai.sagernet.fmt.v2ray

import com.google.gson.JsonObject
import com.google.gson.JsonParser

private val SING_BOX_XHTTP_RANGE_FIELDS = arrayOf(
    "x_padding_bytes",
    "sc_max_each_post_bytes",
    "sc_min_posts_interval_ms",
    "sc_stream_up_server_secs",
)

/**
 * Older Xray subscriptions may encode XHTTP ranges as JSON numbers. The sing-box
 * version used by the app accepts these values as strings or {from, to} objects.
 */
internal fun normalizeSingBoxXhttpRanges(extra: String): String {
    if (extra.isBlank()) return extra
    return try {
        val root = JsonParser.parseString(extra)
        if (!root.isJsonObject) return extra

        val json = root.asJsonObject
        var changed = normalizeRangeFields(json)
        json.get("download")?.takeIf { it.isJsonObject }?.asJsonObject?.let { download ->
            changed = normalizeRangeFields(download) || changed
        }
        if (changed) json.toString() else extra
    } catch (_: Exception) {
        extra
    }
}

private fun normalizeRangeFields(json: JsonObject): Boolean {
    var changed = false
    SING_BOX_XHTTP_RANGE_FIELDS.forEach { key ->
        val value = json.get(key)
        if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
            val normalized = value.asJsonPrimitive.asBigDecimal
                .stripTrailingZeros()
                .toPlainString()
            json.addProperty(key, normalized)
            changed = true
        }
    }
    return changed
}
