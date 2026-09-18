package io.nekohasekai.sagernet.fmt.v2ray

import org.json.JSONObject

object XhttpExtraConverter {

    // Xray SplitHTTPConfig (camelCase) <-> sing-box V2RayXHTTPBaseOptions (snake_case)
    private val FIELD_MAPPINGS = arrayOf(
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
        "sessionIDTable" to "session_id_table",
        "sessionIDLength" to "session_id_length",
        "seqPlacement" to "seq_placement",
        "seqKey" to "seq_key",
        "uplinkDataPlacement" to "uplink_data_placement",
        "uplinkDataKey" to "uplink_data_key",
        "uplinkChunkSize" to "uplink_chunk_size",
    )

    private val XMUX_MAPPINGS = arrayOf(
        "maxConcurrency" to "max_concurrency",
        "maxConnections" to "max_connections",
        "cMaxReuseTimes" to "c_max_reuse_times",
        "hMaxRequestTimes" to "h_max_request_times",
        "hMaxReusableSecs" to "h_max_reusable_secs",
        "hKeepAlivePeriod" to "h_keep_alive_period",
    )

    // mihomo / Clash Meta "xhttp-opts" (kebab-case) -> sing-box V2RayXHTTPBaseOptions
    private val CLASH_FIELD_MAPPINGS = arrayOf(
        "headers" to "headers",
        "x-padding-bytes" to "x_padding_bytes",
        "no-grpc-header" to "no_grpc_header",
        "no-sse-header" to "no_sse_header",
        "sc-max-each-post-bytes" to "sc_max_each_post_bytes",
        "sc-min-posts-interval-ms" to "sc_min_posts_interval_ms",
        "sc-max-buffered-posts" to "sc_max_buffered_posts",
        "sc-stream-up-server-secs" to "sc_stream_up_server_secs",
        "server-max-header-bytes" to "server_max_header_bytes",
        "x-padding-obfs-mode" to "x_padding_obfs_mode",
        "x-padding-key" to "x_padding_key",
        "x-padding-header" to "x_padding_header",
        "x-padding-placement" to "x_padding_placement",
        "x-padding-method" to "x_padding_method",
        "uplink-http-method" to "uplink_http_method",
        "session-placement" to "session_placement",
        "session-key" to "session_key",
        "session-id-table" to "session_id_table",
        "session-id-length" to "session_id_length",
        "seq-placement" to "seq_placement",
        "seq-key" to "seq_key",
        "uplink-data-placement" to "uplink_data_placement",
        "uplink-data-key" to "uplink_data_key",
        "uplink-chunk-size" to "uplink_chunk_size",
    )

    private val CLASH_XMUX_MAPPINGS = arrayOf(
        "max-concurrency" to "max_concurrency",
        "max-connections" to "max_connections",
        "c-max-reuse-times" to "c_max_reuse_times",
        "h-max-request-times" to "h_max_request_times",
        "h-max-reusable-secs" to "h_max_reusable_secs",
        "h-keep-alive-period" to "h_keep_alive_period",
    )

    /**
     * mihomo "xhttp-opts" mapping block -> sing-box XHTTP transport options JSON.
     *
     * Values are kept in their YAML type (number stays number, boolean stays boolean): the
     * core reads most of these as "number or range string", while e.g.
     * sc_max_buffered_posts is a plain int64 and would be rejected as a string.
     * Returns "" when nothing mapped, so the caller can leave the field untouched.
     */
    fun clashToSingBox(xhttpOpts: Map<String, Any?>): String {
        if (xhttpOpts.isEmpty()) return ""
        return try {
            val singBox = JSONObject()

            convertClashFields(xhttpOpts, singBox, CLASH_FIELD_MAPPINGS)
            convertClashXmux(xhttpOpts["reuse-settings"], singBox)

            (xhttpOpts["download-settings"] as? Map<*, *>)?.let { downloadSettings ->
                val download = JSONObject()

                copyClashValue(downloadSettings, download, "mode", "mode") { normalizeXhttpMode(it) }
                copyClashValue(downloadSettings, download, "host", "host")
                copyClashValue(downloadSettings, download, "path", "path")
                convertClashFields(downloadSettings, download, CLASH_FIELD_MAPPINGS)
                convertClashXmux(downloadSettings["reuse-settings"], download)
                copyClashValue(downloadSettings, download, "server", "server")
                copyClashValue(downloadSettings, download, "port", "server_port")

                val realityOptions = downloadSettings["reality-opts"] as? Map<*, *>
                val tlsEnabled = downloadSettings["tls"]?.toString()?.toBooleanStrictOrNull() == true ||
                    realityOptions != null
                if (tlsEnabled) {
                    val tls = JSONObject()
                    tls.put("enabled", true)
                    copyClashValue(downloadSettings, tls, "servername", "server_name")
                    copyClashValue(downloadSettings, tls, "alpn", "alpn")
                    copyClashValue(downloadSettings, tls, "skip-cert-verify", "insecure")
                    val fingerprint = downloadSettings["client-fingerprint"]?.toString()
                        ?.takeIf { it.isNotBlank() }
                    if (fingerprint != null) {
                        val utls = JSONObject()
                        utls.put("enabled", true)
                        utls.put("fingerprint", fingerprint)
                        tls.put("utls", utls)
                    }
                    if (realityOptions != null) {
                        val realityJson = JSONObject()
                        realityJson.put("enabled", true)
                        copyClashValue(realityOptions, realityJson, "public-key", "public_key")
                        copyClashValue(realityOptions, realityJson, "short-id", "short_id")
                        tls.put("reality", realityJson)
                    }
                    download.put("tls", tls)
                }

                if (download.length() > 0) singBox.put("download", download)
            }

            if (singBox.length() > 0) singBox.toString(2).replace("\\/", "/") else ""
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    private fun convertClashFields(
        from: Map<*, *>,
        to: JSONObject,
        mappings: Array<Pair<String, String>>,
    ) {
        for ((fromKey, toKey) in mappings) {
            copyClashValue(from, to, fromKey, toKey)
        }
    }

    private fun convertClashXmux(value: Any?, to: JSONObject) {
        val clashXmux = value as? Map<*, *> ?: return
        val singBoxXmux = JSONObject()
        convertClashFields(clashXmux, singBoxXmux, CLASH_XMUX_MAPPINGS)
        if (singBoxXmux.length() > 0) to.put("xmux", singBoxXmux)
    }

    private fun copyClashValue(
        from: Map<*, *>,
        to: JSONObject,
        fromKey: String,
        toKey: String,
        transform: ((String) -> String)? = null,
    ) {
        val value = from[fromKey] ?: return
        val wrapped = (
            if (transform == null) {
                JSONObject.wrap(value)
            } else {
                transform(value.toString())
            }
            ) ?: return
        to.put(toKey, wrapped)
    }

    fun xrayToSingBox(xrayExtra: String): String {
        if (xrayExtra.isBlank()) return ""
        return try {
            val xray = JSONObject(xrayExtra)
            if (isSingBoxFormat(xray)) return xrayExtra
            val singBox = JSONObject()

            convertFields(xray, singBox, FIELD_MAPPINGS)
            if (xray.has("xmux")) {
                convertXmux(xray, singBox)
            }

            if (xray.has("downloadSettings")) {
                val xrayDown = xray.getJSONObject("downloadSettings")
                val singBoxDown = JSONObject()

                xrayDown.optJSONObject("xhttpSettings")?.let { xhttpSettings ->
                    convertField(xhttpSettings, singBoxDown, "mode", "mode")
                    convertField(xhttpSettings, singBoxDown, "host", "host")
                    convertField(xhttpSettings, singBoxDown, "path", "path")
                    convertFields(xhttpSettings, singBoxDown, FIELD_MAPPINGS)
                    if (xhttpSettings.has("xmux")) {
                        convertXmux(xhttpSettings, singBoxDown)
                    }
                    // Xray: if "extra" is present it overrides the whole settings
                    // object (except host/path/mode), so let it win on conflicts
                    xhttpSettings.optJSONObject("extra")?.let { extra ->
                        convertFields(extra, singBoxDown, FIELD_MAPPINGS)
                        if (extra.has("xmux")) {
                            convertXmux(extra, singBoxDown)
                        }
                    }
                }
                convertField(xrayDown, singBoxDown, "address", "server")
                convertField(xrayDown, singBoxDown, "port", "server_port")

                if (xrayDown.has("security")) {
                    val tls = JSONObject().apply { put("enabled", true) }

                    when (xrayDown.getString("security")) {
                        "tls" -> {
                            xrayDown.optJSONObject("tlsSettings")?.let { tlsSettings ->
                                convertField(tlsSettings, tls, "serverName", "server_name")
                                convertField(tlsSettings, tls, "alpn", "alpn")
                                convertField(tlsSettings, tls, "allowInsecure", "insecure")
                                tlsSettings.optString("fingerprint")?.let { fp ->
                                    if (fp.isNotBlank()) {
                                        val utls = JSONObject().apply {
                                            put("enabled", true)
                                            put("fingerprint", fp)
                                        }
                                        tls.put("utls", utls)
                                    }
                                }
                            }
                        }
                        "reality" -> {
                            xrayDown.optJSONObject("realitySettings")?.let { realitySettings ->
                                convertField(realitySettings, tls, "serverName", "server_name")
                                val reality = JSONObject().apply {
                                    put("enabled", true)
                                    convertField(realitySettings, this, "publicKey", "public_key")
                                    convertField(realitySettings, this, "shortId", "short_id")
                                }
                                tls.put("reality", reality)
                                realitySettings.optString("fingerprint")?.let { fp ->
                                    if (fp.isNotBlank()) {
                                        val utls = JSONObject().apply {
                                            put("enabled", true)
                                            put("fingerprint", fp)
                                        }
                                        tls.put("utls", utls)
                                    }
                                }
                            }
                        }
                    }
                    singBoxDown.put("tls", tls)
                }

                if (singBoxDown.length() > 0) singBox.put("download", singBoxDown)
            }

            singBox.toString(2).replace("\\/", "/")
        } catch (e: Exception) {
            e.printStackTrace()
            xrayExtra
        }
    }

    fun singBoxToXray(singBoxExtra: String): String {
        if (singBoxExtra.isBlank()) return ""
        return try {
            val singBox = JSONObject(singBoxExtra)
            if (isXrayFormat(singBox)) return singBoxExtra
            val xray = JSONObject()

            convertFieldsReverse(singBox, xray, FIELD_MAPPINGS)
            if (singBox.has("xmux")) {
                convertXmuxReverse(singBox, xray)
            }

            if (singBox.has("download")) {
                val singBoxDown = singBox.getJSONObject("download")
                val xrayDown = JSONObject()

                convertField(singBoxDown, xrayDown, "server", "address")
                convertField(singBoxDown, xrayDown, "server_port", "port")
                xrayDown.put("network", "xhttp")

                if (singBoxDown.has("tls")) {
                    val tls = singBoxDown.getJSONObject("tls")

                    if (tls.has("reality") && tls.getJSONObject("reality").optBoolean("enabled", false)) {
                        xrayDown.put("security", "reality")
                        val reality = tls.getJSONObject("reality")
                        val realitySettings = JSONObject()
                        convertField(tls, realitySettings, "server_name", "serverName")
                        convertField(reality, realitySettings, "public_key", "publicKey")
                        convertField(reality, realitySettings, "short_id", "shortId")
                        if (tls.has("utls")) {
                            val utls = tls.getJSONObject("utls")
                            convertField(utls, realitySettings, "fingerprint", "fingerprint")
                        }
                        xrayDown.put("realitySettings", realitySettings)
                    } else {
                        xrayDown.put("security", "tls")
                        val tlsSettings = JSONObject()
                        convertField(tls, tlsSettings, "server_name", "serverName")
                        convertField(tls, tlsSettings, "alpn", "alpn")
                        convertField(tls, tlsSettings, "insecure", "allowInsecure")
                        if (tls.has("utls")) {
                            val utls = tls.getJSONObject("utls")
                            convertField(utls, tlsSettings, "fingerprint", "fingerprint")
                        }
                        xrayDown.put("tlsSettings", tlsSettings)
                    }
                }

                // fields go to xhttpSettings top level: in Xray, "extra" replaces the
                // whole settings object, so wrapping there would drop the rest
                val xhttpSettings = JSONObject()
                convertField(singBoxDown, xhttpSettings, "mode", "mode")
                convertField(singBoxDown, xhttpSettings, "host", "host")
                convertField(singBoxDown, xhttpSettings, "path", "path")
                convertFieldsReverse(singBoxDown, xhttpSettings, FIELD_MAPPINGS)
                if (singBoxDown.has("xmux")) {
                    convertXmuxReverse(singBoxDown, xhttpSettings)
                }
                xrayDown.put("xhttpSettings", xhttpSettings)

                if (xrayDown.length() > 0) xray.put("downloadSettings", xrayDown)
            }

            xray.toString(2).replace("\\/", "/")
        } catch (e: Exception) {
            e.printStackTrace()
            singBoxExtra
        }
    }

    private fun isSingBoxFormat(json: JSONObject): Boolean {
        return json.has("x_padding_bytes") || json.has("sc_max_each_post_bytes") ||
            json.has("sc_min_posts_interval_ms") || json.has("sc_stream_up_server_secs") ||
            json.has("session_id_table") || json.has("session_id_length") ||
            json.has("download")
    }

    private fun isXrayFormat(json: JSONObject): Boolean {
        return json.has("xPaddingBytes") || json.has("scMaxEachPostBytes") ||
            json.has("scMinPostsIntervalMs") || json.has("scStreamUpServerSecs") ||
            json.has("sessionIDTable") || json.has("sessionIDLength") ||
            json.has("downloadSettings")
    }

    private fun convertField(from: JSONObject, to: JSONObject, fromKey: String, toKey: String) {
        if (from.has(fromKey)) {
            to.put(toKey, from.get(fromKey))
        }
    }

    private fun convertFields(from: JSONObject, to: JSONObject, mappings: Array<Pair<String, String>>) {
        for ((fromKey, toKey) in mappings) {
            convertField(from, to, fromKey, toKey)
        }
    }

    private fun convertFieldsReverse(from: JSONObject, to: JSONObject, mappings: Array<Pair<String, String>>) {
        for ((fromKey, toKey) in mappings) {
            convertField(from, to, toKey, fromKey)
        }
    }

    private fun convertXmux(from: JSONObject, to: JSONObject) {
        val xrayXmux = from.getJSONObject("xmux")
        val singBoxXmux = JSONObject()
        convertFields(xrayXmux, singBoxXmux, XMUX_MAPPINGS)
        if (singBoxXmux.length() > 0) to.put("xmux", singBoxXmux)
    }

    private fun convertXmuxReverse(from: JSONObject, to: JSONObject) {
        val singBoxXmux = from.getJSONObject("xmux")
        val xrayXmux = JSONObject()
        convertFieldsReverse(singBoxXmux, xrayXmux, XMUX_MAPPINGS)
        if (xrayXmux.length() > 0) to.put("xmux", xrayXmux)
    }
}
