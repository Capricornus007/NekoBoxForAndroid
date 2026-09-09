package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.ActivityIpPurityBinding
import io.nekohasekai.sagernet.ktx.USER_AGENT
import io.nekohasekai.sagernet.ktx.tryProxyOutbound
import io.nekohasekai.sagernet.utils.LandingIpManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject

class IpPurityActivity : ThemedActivity() {

    private lateinit var binding: ActivityIpPurityBinding
    private var currentIpAddress: String = ""

    private val cloudKeywords = listOf(
        "Cloudflare" to "Cloudflare",
        "Amazon" to "AWS (Amazon Web Services)",
        "AWS" to "AWS (Amazon Web Services)",
        "DigitalOcean" to "DigitalOcean",
        "Google" to "Google Cloud Platform",
        "Microsoft" to "Microsoft Azure",
        "Azure" to "Microsoft Azure",
        "Alibaba" to "Alibaba Cloud",
        "Aliyun" to "Alibaba Cloud",
        "Tencent" to "Tencent Cloud",
        "Hetzner" to "Hetzner Online",
        "OVH" to "OVHcloud",
        "Linode" to "Linode / Akamai",
        "Akamai" to "Akamai",
        "Vultr" to "Vultr / Choopa",
        "Choopa" to "Vultr / Choopa",
        "Oracle" to "Oracle Cloud",
        "Hostinger" to "Hostinger",
        "Contabo" to "Contabo",
        "Datacamp" to "DataCamp / CDN77",
        "Leaseweb" to "Leaseweb",
        "Fastly" to "Fastly",
        "M247" to "M247 Ltd",
        "Zenlayer" to "Zenlayer",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIpPurityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.appbar.updatePadding(top = statusBars.top)
            binding.root.updatePadding(bottom = navBars.bottom)
            insets
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(R.string.ip_purity_title)
        }

        binding.refreshLayout.setOnRefreshListener {
            startCheck()
        }

        binding.btnRetest.setOnClickListener {
            startCheck()
        }

        binding.btnScamalytics.setOnClickListener {
            openExternalReport("https://scamalytics.com/ip/$currentIpAddress")
        }

        binding.btnIpinfo.setOnClickListener {
            openExternalReport("https://ipinfo.io/$currentIpAddress")
        }

        binding.btnAbuseipdb.setOnClickListener {
            openExternalReport("https://www.abuseipdb.com/check/$currentIpAddress")
        }

        startCheck()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun openExternalReport(url: String) {
        if (currentIpAddress.isBlank()) {
            Toast.makeText(this, getString(R.string.ip_purity_no_ip), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.ip_purity_no_browser), Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCheck() {
        if (!DataStore.serviceState.connected) {
            binding.refreshLayout.isRefreshing = false
            Toast.makeText(this, getString(R.string.vpn_not_connected_warning), Toast.LENGTH_LONG).show()
            showNotConnectedState()
            return
        }

        binding.refreshLayout.isRefreshing = true
        binding.tvStatusTitle.text = getString(R.string.ip_purity_checking)
        binding.tvStatusDesc.text = getString(R.string.ip_purity_checking_desc)
        binding.cardStatus.setCardBackgroundColor("#475569".toColorInt())

        lifecycleScope.launch {
            val result = queryPurityData()
            binding.refreshLayout.isRefreshing = false

            result.onSuccess { data ->
                renderResult(data)
            }.onFailure { e ->
                renderError(e.message ?: getString(R.string.ip_purity_failed))
            }
        }
    }

    private fun showNotConnectedState() {
        binding.cardStatus.setCardBackgroundColor("#64748B".toColorInt())
        binding.tvStatusTitle.text = getString(R.string.ip_purity_vpn_off)
        binding.tvStatusDesc.text = getString(R.string.vpn_not_connected_warning)
    }

    private suspend fun queryPurityData(): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val client = Libcore.newHttpClient().apply {
                modernTLS()
                tryProxyOutbound()
            }
            val req = client.newRequest().apply {
                setURL(
                    "http://ip-api.com/json/?fields=status,message,country,countryCode,regionName,city,isp,org,as,asname,reverse,mobile,proxy,hosting,query",
                )
                setUserAgent(USER_AGENT)
            }
            val resp = req.execute()
            val body = Util.getStringBox(resp.contentString)
            val json = JSONObject(body)
            if (json.optString("status") == "success") {
                Result.success(json)
            } else {
                Result.failure(Exception(json.optString("message", getString(R.string.ip_purity_api_error))))
            }
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    private fun renderResult(json: JSONObject) {
        val ip = json.optString("query")
        currentIpAddress = ip
        val country = json.optString("country")
        val countryCode = json.optString("countryCode")
        val city = json.optString("city")
        val flag = LandingIpManager.countryCodeToFlagEmoji(countryCode)
        val isp = json.optString("isp")
        val org = json.optString("org")
        val asn = json.optString("as")
        val reverse = json.optString("reverse")
        val isHosting = json.optBoolean("hosting", false)
        val isProxy = json.optBoolean("proxy", false)
        val isMobile = json.optBoolean("mobile", false)

        binding.tvDeviceNetwork.text = getLocalNetworkDescription()
        binding.tvDetailIp.text = ip
        binding.tvDetailLocation.text = "$flag $country · $city ($countryCode)"
        binding.tvDetailHosting.text = if (isHosting) {
            getString(
                R.string.ip_purity_hosting_yes,
            )
        } else {
            getString(R.string.ip_purity_hosting_no)
        }
        binding.tvDetailProxy.text = if (isProxy) {
            getString(
                R.string.ip_purity_proxy_yes,
            )
        } else {
            getString(R.string.ip_purity_proxy_no)
        }
        binding.tvDetailMobile.text = if (isMobile) {
            getString(
                R.string.ip_purity_mobile_yes,
            )
        } else {
            getString(R.string.ip_purity_mobile_no)
        }
        binding.tvDetailIsp.text = isp.ifBlank { getString(R.string.ip_purity_field_unknown) }
        binding.tvDetailAsn.text = asn.ifBlank { getString(R.string.ip_purity_field_unknown) }
        binding.tvDetailReverse.text = reverse.ifBlank { getString(R.string.ip_purity_no_reverse) }

        // Find matched cloud provider
        val combinedText = "$asn $org $isp".uppercase()
        var matchedCloud: String? = null
        for ((keyword, name) in cloudKeywords) {
            if (combinedText.contains(keyword.uppercase())) {
                matchedCloud = name
                break
            }
        }

        when {
            isHosting || isProxy || matchedCloud != null -> {
                // Datacenter / Cloud / Proxy IP
                binding.cardStatus.setCardBackgroundColor("#E11D48".toColorInt()) // Rose Red
                binding.tvStatusTitle.text = getString(R.string.ip_purity_status_datacenter)
                val cloudDesc = matchedCloud?.let { getString(R.string.ip_purity_cloud_suffix_fmt, it) } ?: ""
                binding.tvStatusDesc.text = getString(R.string.ip_purity_datacenter_desc_fmt, cloudDesc)
                binding.tvDetailFraud.text = getString(R.string.ip_purity_risk_high)
                binding.tvDetailFraud.setTextColor("#E11D48".toColorInt())
            }
            json.has("hosting") && !isHosting && !isProxy -> {
                // Pure Residential
                binding.cardStatus.setCardBackgroundColor("#059669".toColorInt()) // Emerald Green
                binding.tvStatusTitle.text = getString(R.string.ip_purity_status_pure)
                binding.tvStatusDesc.text = getString(R.string.ip_purity_pure_desc)
                binding.tvDetailFraud.text = getString(R.string.ip_purity_risk_low)
                binding.tvDetailFraud.setTextColor("#059669".toColorInt())
            }
            else -> {
                // Unknown
                binding.cardStatus.setCardBackgroundColor("#64748B".toColorInt()) // Slate Gray
                binding.tvStatusTitle.text = getString(R.string.ip_purity_status_unknown)
                binding.tvStatusDesc.text = getString(R.string.ip_purity_unknown_desc)
                binding.tvDetailFraud.text = getString(R.string.ip_purity_risk_mid)
                binding.tvDetailFraud.setTextColor("#F59E0B".toColorInt())
            }
        }
    }

    private fun renderError(error: String) {
        binding.cardStatus.setCardBackgroundColor("#DC2626".toColorInt())
        binding.tvStatusTitle.text = getString(R.string.ip_purity_failed)
        binding.tvStatusDesc.text = error
    }

    private fun getLocalNetworkDescription(): String {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return getString(R.string.unknown)
            val network = cm.activeNetwork ?: return getString(R.string.ip_purity_device_offline)
            val caps = cm.getNetworkCapabilities(network) ?: return getString(R.string.unknown)
            when {
                caps.hasTransport(
                    NetworkCapabilities.TRANSPORT_CELLULAR,
                ) -> getString(R.string.ip_purity_device_cellular) + getString(R.string.ip_purity_device_current_suffix)
                caps.hasTransport(
                    NetworkCapabilities.TRANSPORT_WIFI,
                ) -> getString(R.string.ip_purity_device_wifi) + getString(R.string.ip_purity_device_current_suffix)
                caps.hasTransport(
                    NetworkCapabilities.TRANSPORT_ETHERNET,
                ) -> getString(R.string.ip_purity_device_other) + getString(R.string.ip_purity_device_ethernet_suffix)
                else -> getString(R.string.ip_purity_device_other)
            }
        } catch (_: Exception) {
            getString(R.string.unknown)
        }
    }
}
