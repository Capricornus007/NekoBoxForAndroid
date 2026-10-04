package io.nekohasekai.sagernet.fmt.v2ray

import io.nekohasekai.sagernet.ktx.Logs
import org.json.JSONObject

object XhttpExtraConverter {
    private val fields = mapOf(
        "headers" to "headers",
        "xPaddingBytes" to "x_padding_bytes",
        "noGRPCHeader" to "no_grpc_header",
        "noSSEHeader" to "no_sse_header",
        "scMaxEachPostBytes" to "sc_max_each_post_bytes",
        "scMinPostsIntervalMs" to "sc_min_posts_interval_ms",
        "scMaxBufferedPosts" to "sc_max_buffered_posts",
        "scStreamUpServerSecs" to "sc_stream_up_server_secs",
        "serverMaxHeaderBytes" to "server_max_header_bytes",
        "xPaddingObfsMode" to "x_padding_obfs_mode",
        "xPaddingKey" to "x_padding_key",
        "xPaddingHeader" to "x_padding_header",
        "xPaddingPlacement" to "x_padding_placement",
        "xPaddingMethod" to "x_padding_method",
        "uplinkHTTPMethod" to "uplink_http_method",
        "sessionIDPlacement" to "session_placement",
        "sessionIDKey" to "session_key",
        "seqPlacement" to "seq_placement",
        "seqKey" to "seq_key",
        "uplinkDataPlacement" to "uplink_data_placement",
        "uplinkDataKey" to "uplink_data_key",
        "uplinkChunkSize" to "uplink_chunk_size",
    )

    // Preserve these for sharing, but the pinned core cannot generate custom session IDs.
    private val shareOnlyFields = mapOf(
        "sessionIDTable" to "session_id_table",
        "sessionIDLength" to "session_id_length",
    )
    private val legacyAliases = mapOf(
        "uplinkHttpMethod" to "uplinkHTTPMethod",
        "sessionIdPosition" to "sessionIDPlacement",
        "sessionIdName" to "sessionIDKey",
        "seqPosition" to "seqPlacement",
        "seqName" to "seqKey",
        "dataUpPlacement" to "uplinkDataPlacement",
        "dataUpName" to "uplinkDataKey",
        "dataUpSplitSize" to "uplinkChunkSize",
    )
    private val xmuxFields = mapOf(
        "maxConcurrency" to "max_concurrency",
        "maxConnections" to "max_connections",
        "cMaxReuseTimes" to "c_max_reuse_times",
        "hMaxRequestTimes" to "h_max_request_times",
        "hMaxReusableSecs" to "h_max_reusable_secs",
        "hKeepAlivePeriod" to "h_keep_alive_period",
    )
    private val tlsFields = mapOf(
        "serverName" to "server_name",
        "alpn" to "alpn",
        "allowInsecure" to "insecure",
    )
    private val baseKeys = listOf("host", "path", "mode")

    fun xrayToSingBox(xrayExtra: String): String {
        if (xrayExtra.isBlank()) return ""
        return try {
            toSingBox(JSONObject(xrayExtra)).toString(2).replace("\\/", "/")
        } catch (_: Exception) {
            Logs.w("XHTTP extra conversion rejected input")
            xrayExtra
        }
    }

    fun singBoxToXray(singBoxExtra: String): String {
        if (singBoxExtra.isBlank()) return ""
        return try {
            toXray(toSingBox(JSONObject(singBoxExtra))).toString(2).replace("\\/", "/")
        } catch (_: Exception) {
            Logs.w("XHTTP extra conversion rejected input")
            singBoxExtra
        }
    }

    private fun toSingBox(source: JSONObject, withDownload: Boolean = true): JSONObject {
        // Xray's extra replaces advanced settings, even when empty. Host/path/mode
        // belong to the enclosing settings object and cannot be overridden by extra.
        val effective = if (source.has("extra")) {
            val extra = if (source.isNull("extra")) JSONObject() else copy(source.getJSONObject("extra"))
            extra.remove("extra")
            baseKeys.forEach { key ->
                extra.remove(key)
                if (source.has(key)) extra.put(key, source.get(key))
            }
            extra
        } else {
            copy(source)
        }
        for ((alias, canonical) in legacyAliases) {
            if (effective.has(alias) && !effective.has(canonical)) effective.put(canonical, effective.get(alias))
            effective.remove(alias)
        }
        val result = toNativeFields(effective, fields + shareOnlyFields)
        if (!result.isNull("xmux")) result.put("xmux", toNativeFields(result.getJSONObject("xmux"), xmuxFields))
        if (!withDownload) return result
        if (result.has("download")) {
            if (!result.isNull("download")) result.put("download", toSingBox(result.getJSONObject("download"), withDownload = false))
        } else if (result.has("downloadSettings")) {
            result.put("download", if (result.isNull("downloadSettings")) JSONObject.NULL else downloadToSingBox(result.getJSONObject("downloadSettings")))
        }
        result.remove("downloadSettings")
        return result
    }

    private fun downloadToSingBox(source: JSONObject): JSONObject {
        val supported = setOf("address", "port", "method", "network", "security", "tlsSettings", "realitySettings", "xhttpSettings", "splithttpSettings", "detour")
        require(source.keys().asSequence().all { it in supported || source.isNull(it) }) { "Unsupported download settings" }
        val network = source.optString("method", source.optString("network", "xhttp"))
        require(network in listOf("xhttp", "splithttp")) { "Unsupported download transport" }
        val settingsKey = if (!source.isNull("xhttpSettings")) "xhttpSettings" else "splithttpSettings"
        val settings = if (source.isNull(settingsKey)) JSONObject() else source.getJSONObject(settingsKey)
        val result = toSingBox(settings, withDownload = false)
        copyField(source, result, "address", "server")
        copyField(source, result, "port", "server_port")
        copyField(source, result, "detour", "detour")
        val security = source.optString("security", "")
        require(security in listOf("", "none", "tls", "reality")) { "Unsupported download security" }
        if (source.has("security") || source.has("tlsSettings") || source.has("realitySettings")) {
            val tlsKey = if (security == "reality") "realitySettings" else "tlsSettings"
            val tlsSource = if (source.isNull(tlsKey)) JSONObject() else source.getJSONObject(tlsKey)
            val tls = toNativeFields(tlsSource, tlsFields)
            tls.put("enabled", security == "tls" || security == "reality")
            if (tls.has("fingerprint")) {
                val fingerprint = tls.remove("fingerprint")
                tls.put("utls", JSONObject().put("enabled", fingerprint is String && fingerprint.isNotBlank()).put("fingerprint", fingerprint))
            }
            if (security == "reality") {
                val reality = JSONObject().put("enabled", true)
                copyField(tls, reality, "publicKey", "public_key")
                copyField(tls, reality, "shortId", "short_id")
                tls.remove("publicKey")
                tls.remove("shortId")
                tls.put("reality", reality)
            }
            result.put("tls", tls)
        }
        return result
    }

    private fun toXray(source: JSONObject, withDownload: Boolean = true): JSONObject {
        val result = toXrayFields(source, fields + shareOnlyFields)
        result.optJSONObject("xmux")?.let { result.put("xmux", toXrayFields(it, xmuxFields)) }
        if (withDownload && result.has("download")) {
            result.put("downloadSettings", if (result.isNull("download")) JSONObject.NULL else downloadToXray(result.getJSONObject("download")))
            result.remove("download")
        }
        return result
    }

    private fun downloadToXray(source: JSONObject): JSONObject {
        val settings = toXray(source, withDownload = false)
        val result = JSONObject().put("network", "xhttp")
        copyField(settings, result, "server", "address")
        copyField(settings, result, "server_port", "port")
        copyField(settings, result, "detour", "detour")
        settings.remove("server")
        settings.remove("server_port")
        settings.remove("detour")
        val tls = settings.remove("tls") as? JSONObject
        if (tls != null) {
            val reality = tls.optJSONObject("reality")
            val security = when {
                !tls.optBoolean("enabled", false) -> "none"
                reality?.optBoolean("enabled", false) == true -> "reality"
                else -> "tls"
            }
            result.put("security", security)
            val tlsSettings = toXrayFields(tls, tlsFields)
            tlsSettings.remove("enabled")
            if (security == "reality") {
                tlsSettings.remove("reality")
                copyField(reality!!, tlsSettings, "public_key", "publicKey")
                copyField(reality, tlsSettings, "short_id", "shortId")
            }
            tls.optJSONObject("utls")?.takeIf { it.optBoolean("enabled", false) }?.let {
                tlsSettings.remove("utls")
                copyField(it, tlsSettings, "fingerprint", "fingerprint")
            }
            result.put(if (security == "reality") "realitySettings" else "tlsSettings", tlsSettings)
        }
        // Export one settings object so Xray does not discard fields through an extra override.
        result.put("xhttpSettings", settings)
        return result
    }

    /**
     * 3x-ui places the VMess XHTTP `extra` keys directly on the share object instead of nesting
     * them. Collect the known ones so padding, xmux and download settings survive the import.
     */
    fun flattenedExtra(vmess: JSONObject): JSONObject? {
        val keys = fields.keys + fields.values + shareOnlyFields.keys + legacyAliases.keys + listOf("xmux", "downloadSettings")
        return select(vmess, keys).takeIf { it.length() > 0 }
    }

    internal fun forCore(source: JSONObject): JSONObject {
        val normalized = try {
            toSingBox(source)
        } catch (_: Exception) {
            error("Invalid XHTTP extra settings")
        }
        for (options in listOfNotNull(normalized, normalized.optJSONObject("download"))) {
            val unsupported = shareOnlyFields.values + listOf("congestion_controller", "cwnd") +
                if (options === normalized) emptyList() else listOf("download", "downloadSettings")
            for (key in unsupported) {
                val value = options.opt(key)
                val unset = value == null || value == JSONObject.NULL || value == "" || (value is Number && value.toDouble() == 0.0)
                require(unset) { "XHTTP setting is unsupported by the current core: $key" }
            }
        }
        val result = select(normalized, fields.values + listOf("xmux", "download"))
        result.optJSONObject("xmux")?.let { result.put("xmux", select(it, xmuxFields.values)) }
        result.optJSONObject("download")?.let { download ->
            val filtered = select(download, fields.values + baseKeys + listOf("xmux", "server", "server_port", "tls", "detour", "domain_strategy"))
            filtered.optJSONObject("xmux")?.let { filtered.put("xmux", select(it, xmuxFields.values)) }
            result.put("download", filtered)
        }
        return result
    }

    private fun copy(source: JSONObject) = JSONObject(source.toString())

    private fun select(source: JSONObject, keys: Collection<String>) = JSONObject().apply {
        keys.forEach { key -> copyField(source, this, key, key) }
    }

    private fun toNativeFields(source: JSONObject, mappings: Map<String, String>) = copy(source).apply {
        for ((xray, native) in mappings) {
            // Explicit native values win in mixed input; canonical Xray names beat legacy aliases.
            if (!has(native)) copyField(source, this, xray, native)
            if (xray != native) remove(xray)
        }
    }

    private fun toXrayFields(source: JSONObject, mappings: Map<String, String>) = copy(source).apply {
        for ((xray, native) in mappings) {
            copyField(source, this, native, xray)
            if (xray != native) remove(native)
        }
    }

    private fun copyField(from: JSONObject, to: JSONObject, fromKey: String, toKey: String) {
        if (from.has(fromKey)) to.put(toKey, from.get(fromKey))
    }
}
