package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.ktx.MAX_SUBSCRIPTION_BYTES
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

internal fun parseClashYaml(text: String): Map<*, *> {
    require(text.length <= MAX_SUBSCRIPTION_BYTES) { "Subscription is too large" }
    val options = LoaderOptions().apply {
        codePointLimit = MAX_SUBSCRIPTION_BYTES
        maxAliasesForCollections = 50
        nestingDepthLimit = 50
        isAllowDuplicateKeys = false
    }
    return Yaml(SafeConstructor(options)).load<Any>(text) as? Map<*, *>
        ?: error("Expected a YAML mapping")
}
