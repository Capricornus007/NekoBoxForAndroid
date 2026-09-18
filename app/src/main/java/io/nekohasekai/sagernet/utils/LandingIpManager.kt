package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.USER_AGENT
import io.nekohasekai.sagernet.ktx.tryProxyOutbound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject
import java.util.Locale

data class LandingIpInfo(
    val ip: String,
    val country: String,
    val countryCode: String,
    val countryFlag: String,
    val city: String,
    val region: String,
    val isp: String,
    val org: String,
    val asn: String,
    val durationMs: Long,
    val queryTimestamp: Long = System.currentTimeMillis(),
) {

    val briefText: String
        get() = if (durationMs > 0) {
            "$countryFlag $countryCode $ip · HTTP ${durationMs}ms".trim()
        } else {
            "$countryFlag $countryCode $ip".trim()
        }

    val locationText: String
        get() {
            val parts = mutableListOf<String>()
            if (country.isNotBlank()) parts.add(country)
            // Ported from own/2f0ec0d7b: many GeoIP answers repeat the country in the city
            // field ("Tokyo · Tokyo"); drop the duplicate segment.
            if (city.isNotBlank() && city != country) parts.add(city)
            val base = parts.joinToString(" · ")
            return if (countryCode.isNotBlank()) "$countryFlag $base ($countryCode)" else "$countryFlag $base"
        }
}

object LandingIpManager {

    // Ported from own/2f0ec0d7b: the landing IP goes stale as soon as the outbound
    // changes, so keep a short TTL instead of caching for the whole session.
    private const val CACHE_TTL_MS = 180_000L // 3 minutes

    @Volatile
    private var currentCache: LandingIpInfo? = null

    @Volatile
    private var cachedProfileId: Long = -1L

    @Volatile
    private var isQuerying: Boolean = false

    fun clearCache() {
        currentCache = null
        cachedProfileId = -1L
    }

    fun getCachedInfo(): LandingIpInfo? = currentCache

    fun updateCachedDuration(duration: Long) {
        currentCache = currentCache?.copy(durationMs = duration)
    }

    fun countryCodeToFlagEmoji(countryCode: String?): String {
        if (countryCode == null || countryCode.length != 2) return "🌐"
        val code = countryCode.uppercase()
        if (!code[0].isLetter() || !code[1].isLetter()) return "🌐"
        val firstChar = Character.codePointAt(code, 0) - 0x41 + 0x1F1E6
        val secondChar = Character.codePointAt(code, 1) - 0x41 + 0x1F1E6
        return String(Character.toChars(firstChar)) + String(Character.toChars(secondChar))
    }

    // Country name in the phone's language; fall back to whatever the API returned.
    private fun localizeCountry(countryCode: String, fallbackName: String): String {
        if (countryCode.length != 2) return fallbackName
        return runCatching {
            Locale("", countryCode.uppercase()).getDisplayCountry(Locale.getDefault())
                .takeIf { it.isNotBlank() }
        }.getOrNull() ?: fallbackName
    }

    suspend fun queryLandingIp(
        profileId: Long,
        forceRefresh: Boolean = false,
    ): Result<LandingIpInfo> = withContext(Dispatchers.IO) {
        if (!DataStore.serviceState.connected) {
            return@withContext Result.failure(IllegalStateException("VPN not connected"))
        }

        val now = System.currentTimeMillis()
        if (forceRefresh) {
            clearCache()
        } else {
            val cache = currentCache
            if (cache != null && cachedProfileId == profileId && now - cache.queryTimestamp < CACHE_TTL_MS) {
                return@withContext Result.success(cache)
            }
        }

        if (isQuerying && !forceRefresh) {
            currentCache?.let { return@withContext Result.success(it) }
        }

        isQuerying = true
        val startTime = System.currentTimeMillis()
        var client: libcore.HTTPClient? = null

        try {
            val c = Libcore.newHttpClient().apply {
                modernTLS()
                tryProxyOutbound()
            }
            client = c

            val ua = USER_AGENT.takeIf { it.isNotBlank() }
                ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

            var info: LandingIpInfo? = null

            // 1. api.ip.sb - proxy-friendly, high precision
            try {
                val req = client.newRequest().apply {
                    setURL("https://api.ip.sb/geoip")
                    setUserAgent(ua)
                }
                val resp = req.execute()
                val body = Util.getStringBox(resp.contentString)
                val json = JSONObject(body)
                val ip = json.optString("ip")
                if (ip.isNotBlank()) {
                    val countryCode = json.optString("country_code").uppercase()
                    val country = localizeCountry(countryCode, json.optString("country"))
                    val city = json.optString("city")
                    val isp = json.optString("isp")
                    val asnNum = json.optInt("asn", 0)
                    val asnOrg = json.optString("asn_organization")
                    val asn = if (asnNum > 0) "AS$asnNum $asnOrg".trim() else asnOrg

                    info = LandingIpInfo(
                        ip = ip,
                        country = country,
                        countryCode = countryCode,
                        countryFlag = countryCodeToFlagEmoji(countryCode),
                        city = city,
                        region = json.optString("region"),
                        isp = isp,
                        org = json.optString("organization"),
                        asn = asn,
                        durationMs = System.currentTimeMillis() - startTime,
                    )
                }
            } catch (_: Throwable) {
            }

            // 2. ipwho.is
            if (info == null) {
                try {
                    val req = client.newRequest().apply {
                        setURL("https://ipwho.is/")
                        setUserAgent(ua)
                    }
                    val resp = req.execute()
                    val body = Util.getStringBox(resp.contentString)
                    val json = JSONObject(body)
                    if (json.optBoolean("success", false)) {
                        val ip = json.optString("ip")
                        if (ip.isNotBlank()) {
                            val countryCode = json.optString("country_code").uppercase()
                            val country = localizeCountry(countryCode, json.optString("country"))
                            val city = json.optString("city")
                            val conn = json.optJSONObject("connection")
                            val isp = conn?.optString("isp").orEmpty()
                            val org = conn?.optString("org").orEmpty()
                            val asnNum = conn?.optInt("asn", 0) ?: 0
                            val asn = if (asnNum > 0) "AS$asnNum $org".trim() else org

                            info = LandingIpInfo(
                                ip = ip,
                                country = country,
                                countryCode = countryCode,
                                countryFlag = countryCodeToFlagEmoji(countryCode),
                                city = city,
                                region = json.optString("region"),
                                isp = isp,
                                org = org,
                                asn = asn,
                                durationMs = System.currentTimeMillis() - startTime,
                            )
                        }
                    }
                } catch (_: Throwable) {
                }
            }

            // 3. cloudflare trace - the authoritative edge actually serving this outbound
            if (info == null) {
                try {
                    val req = client.newRequest().apply {
                        setURL("https://cloudflare.com/cdn-cgi/trace")
                        setUserAgent(ua)
                    }
                    val resp = req.execute()
                    val body = Util.getStringBox(resp.contentString)
                    var cfIp = ""
                    var cfLoc = ""
                    var cfColo = ""
                    for (line in body.lines()) {
                        val trimmed = line.trim()
                        if (trimmed.startsWith("ip=")) cfIp = trimmed.substring(3).trim()
                        else if (trimmed.startsWith("loc=")) cfLoc = trimmed.substring(4).trim().uppercase()
                        else if (trimmed.startsWith("colo=")) cfColo = trimmed.substring(5).trim().uppercase()
                    }
                    if (cfIp.isNotBlank()) {
                        info = LandingIpInfo(
                            ip = cfIp,
                            country = localizeCountry(cfLoc, cfLoc),
                            countryCode = cfLoc,
                            countryFlag = countryCodeToFlagEmoji(cfLoc),
                            city = if (cfColo.isNotBlank()) "Cloudflare ($cfColo)" else "",
                            region = "",
                            isp = "Cloudflare Edge",
                            org = "Cloudflare Anycast",
                            asn = if (cfColo.isNotBlank()) "Cloudflare $cfColo" else "Cloudflare",
                            durationMs = System.currentTimeMillis() - startTime,
                        )
                    }
                } catch (_: Throwable) {
                }
            }

            // 4. legacy fallback: ip-api.com
            if (info == null) {
                try {
                    val req = client.newRequest().apply {
                        setURL(
                            "http://ip-api.com/json?fields=status,message,country,countryCode,regionName,city,isp,org,as,query",
                        )
                        setUserAgent(ua)
                    }
                    val resp = req.execute()
                    val body = Util.getStringBox(resp.contentString)
                    val json = JSONObject(body)
                    if (json.optString("status") == "success") {
                        val ip = json.optString("query")
                        val countryCode = json.optString("countryCode").uppercase()
                        val country = localizeCountry(countryCode, json.optString("country"))

                        info = LandingIpInfo(
                            ip = ip,
                            country = country,
                            countryCode = countryCode,
                            countryFlag = countryCodeToFlagEmoji(countryCode),
                            city = json.optString("city"),
                            region = json.optString("regionName"),
                            isp = json.optString("isp"),
                            org = json.optString("org"),
                            asn = json.optString("as"),
                            durationMs = System.currentTimeMillis() - startTime,
                        )
                    }
                } catch (_: Throwable) {
                }
            }

            if (info != null) {
                currentCache = info
                cachedProfileId = profileId
                Result.success(info)
            } else {
                Result.failure(Exception("failed to fetch landing IP"))
            }
        } catch (e: Throwable) {
            Result.failure(e)
        } finally {
            isQuerying = false
            runCatching { client?.close() }
        }
    }
}
