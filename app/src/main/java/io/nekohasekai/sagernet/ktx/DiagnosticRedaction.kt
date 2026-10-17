package io.nekohasekai.sagernet.ktx

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

private val secretUri = Regex("""(?i)\b(?:https?|ssr?|vmess|vless|trojan(?:-go)?|socks[45a]?|naive\+https?|hysteria2?|tuic|juicity|anytls|vpn|sn)://[^\s"'<>]+""")
private val privatePem = Regex("""-----BEGIN ([A-Z ]*PRIVATE KEY)-----.*?-----END \1-----""", RegexOption.DOT_MATCHES_ALL)
private val secretField = Regex("""(?i)(["']?(?:password|passwd|private[_-]?key|preshared[_-]?key|pre_shared_key|header[_-]?protection[_-]?key|uuid|token|secret|authorization|cookie|(?:x-)?hwid|customHwidParams|customHeaders)["']?\s*[:=]\s*)(?:"(?:\\.|[^"\\])*"|'[^'\r\n]*'|[^,;}\r\n]+)""")

internal fun redactDiagnostic(message: String): String = secretField.replace(
    secretUri.replace(privatePem.replace(message, "[redacted private key]"), "[redacted URI]")
) { "${it.groupValues[1]}[redacted]" }

internal fun redactConfigJson(config: String): String {
    val secrets = setOf("password", "passwd", "privatekey", "presharedkey", "headerprotectionkey", "uuid", "token", "secret", "authorization", "cookie", "hwid", "customhwidparams", "customheaders", "headers")
    fun scrub(value: JsonElement) {
        when {
            value.isJsonObject -> value.asJsonObject.entrySet().forEach { entry ->
                val key = entry.key.lowercase().replace("_", "").replace("-", "")
                if (key in secrets) entry.setValue(JsonPrimitive("[redacted]"))
                else if (entry.value.isJsonPrimitive && entry.value.asJsonPrimitive.isString) entry.setValue(JsonPrimitive(redactDiagnostic(entry.value.asString)))
                else scrub(entry.value)
            }
            value.isJsonArray -> value.asJsonArray.forEachIndexed { index, child ->
                if (child.isJsonPrimitive && child.asJsonPrimitive.isString) value.asJsonArray.set(index, JsonPrimitive(redactDiagnostic(child.asString))) else scrub(child)
            }
        }
    }
    val json = JsonParser.parseString(config)
    scrub(json)
    return GsonBuilder().setPrettyPrinting().create().toJson(json)
}
