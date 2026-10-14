package io.nekohasekai.sagernet.ktx

private val secretUri = Regex("""(?i)\b(?:https?|ssr?|vmess|vless|trojan(?:-go)?|socks[45a]?|naive\+https?|hysteria2?|tuic|juicity|anytls|vpn|sn)://[^\s"'<>]+""")
private val privatePem = Regex("""-----BEGIN ([A-Z ]*PRIVATE KEY)-----.*?-----END \1-----""", RegexOption.DOT_MATCHES_ALL)
private val secretField = Regex("""(?i)(["']?(?:password|passwd|private[_-]?key|preshared[_-]?key|pre_shared_key|uuid|token|secret|authorization|cookie|(?:x-)?hwid|customHwidParams)["']?\s*[:=]\s*)(?:"[^"\r\n]*"|'[^'\r\n]*'|[^,;}\r\n]+)""")

internal fun redactDiagnostic(message: String): String = privatePem.replace(
    secretField.replace(secretUri.replace(message, "[redacted URI]")) { "${it.groupValues[1]}[redacted]" },
    "[redacted private key]"
)
