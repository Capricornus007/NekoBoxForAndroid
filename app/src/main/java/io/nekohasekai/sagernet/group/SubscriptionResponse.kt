package io.nekohasekai.sagernet.group

internal enum class SubscriptionResponseProblem { EMPTY, HTML, JSON_ERROR, UNSUPPORTED }

internal class SubscriptionResponseException(val problem: SubscriptionResponseProblem, message: String) : IllegalArgumentException(message)

internal fun subscriptionResponseProblem(text: String): SubscriptionResponseProblem? {
    val start = text.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
    if (start.isEmpty()) return SubscriptionResponseProblem.EMPTY
    if (Regex("^<(?:!doctype\\s+html|html|head|body)(?:\\s|>)", RegexOption.IGNORE_CASE).containsMatchIn(start)) {
        return SubscriptionResponseProblem.HTML
    }
    if (start.startsWith("{")) {
        val json = runCatching { com.google.gson.JsonParser.parseString(start).asJsonObject }.getOrNull()
        if (json != null && (json.has("error") || json.has("message")) &&
            !json.has("outbounds") && !json.has("proxies") && !json.has("configs") && !json.has("server")
        ) {
            return SubscriptionResponseProblem.JSON_ERROR
        }
    }
    return null
}
