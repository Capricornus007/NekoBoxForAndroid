package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.CircularProgressIndicator
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.databinding.ActivityMediaUnlockBinding
import io.nekohasekai.sagernet.ktx.USER_AGENT
import io.nekohasekai.sagernet.ktx.tryProxyOutbound
import io.nekohasekai.sagernet.utils.LandingIpManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import java.util.regex.Pattern

class MediaUnlockActivity : ThemedActivity() {

    private lateinit var binding: ActivityMediaUnlockBinding
    private lateinit var adapter: MediaUnlockAdapter
    private var testJob: Job? = null

    enum class TestState {
        TESTING,
        UNLOCKED,
        PARTIAL,
        BLOCKED,
        TIMEOUT,
        NOT_CONNECTED,
    }

    data class MediaItem(
        val id: String,
        val name: String,
        val category: String,
        val iconRes: Int,
        var state: TestState = TestState.TESTING,
        var statusText: String = "",
        var description: String = "",
        var region: String? = null,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaUnlockBinding.inflate(layoutInflater)
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
            setTitle(R.string.media_unlock_title)
        }

        adapter = MediaUnlockAdapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.btnRetest.setOnClickListener {
            startAllTests()
        }

        startAllTests()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        testJob?.cancel()
    }

    private fun startAllTests() {
        testJob?.cancel()

        val currentProfile = ProfileManager.getProfile(DataStore.selectedProxy)
        binding.tvCurrentNode.text = currentProfile?.displayName() ?: getString(R.string.not_connected)

        val cachedIp = LandingIpManager.getCachedInfo()
        binding.tvCurrentIp.text = if (cachedIp != null) {
            getString(
                R.string.media_exit_ip_fmt,
                cachedIp.briefText,
            )
        } else {
            getString(R.string.media_exit_ip_querying)
        }

        if (!DataStore.serviceState.connected) {
            Toast.makeText(this, getString(R.string.vpn_not_connected_warning), Toast.LENGTH_LONG).show()
            val initialList = createDefaultItems(TestState.NOT_CONNECTED)
            adapter.submitList(initialList)
            return
        }

        val items = createDefaultItems(TestState.TESTING)
        adapter.submitList(items)

        testJob = lifecycleScope.launch {
            if (cachedIp == null) {
                val ipRes = LandingIpManager.queryLandingIp(DataStore.selectedProxy)
                ipRes.onSuccess {
                    binding.tvCurrentIp.text = getString(R.string.media_exit_ip_fmt, it.briefText)
                }
            }

            // Launch detection concurrently
            items.forEachIndexed { index, item ->
                launch {
                    val tested = runTestForItem(item)
                    withContext(Dispatchers.Main) {
                        items[index] = tested
                        adapter.notifyItemChanged(index)
                    }
                }
            }
        }
    }

    private fun createDefaultItems(initialState: TestState): MutableList<MediaItem> {
        val notConnected = initialState == TestState.NOT_CONNECTED
        fun defDesc(name: String) = if (notConnected) {
            getString(
                R.string.media_desc_vpn_off,
            )
        } else {
            getString(R.string.media_desc_probing_fmt, name)
        }
        fun defStatus() = if (notConnected) {
            getString(
                R.string.media_status_not_connected,
            )
        } else {
            getString(R.string.media_status_testing)
        }

        return mutableListOf(
            MediaItem(
                "netflix",
                "Netflix",
                getString(R.string.media_category_streaming),
                R.drawable.ic_platform_netflix,
                initialState,
                defStatus(),
                defDesc("Netflix"),
            ),
            MediaItem(
                "disney",
                "Disney+",
                getString(R.string.media_category_streaming),
                R.drawable.ic_platform_disney,
                initialState,
                defStatus(),
                defDesc("Disney+"),
            ),
            MediaItem(
                "max",
                "Max (HBO)",
                getString(R.string.media_category_streaming),
                R.drawable.ic_platform_max,
                initialState,
                defStatus(),
                defDesc("Max"),
            ),
            MediaItem(
                "prime",
                "Prime Video",
                getString(R.string.media_category_streaming),
                R.drawable.ic_platform_prime,
                initialState,
                defStatus(),
                defDesc("Amazon Prime Video"),
            ),
            MediaItem(
                "youtube",
                "YouTube Premium",
                getString(R.string.media_category_streaming),
                R.drawable.ic_platform_youtube,
                initialState,
                defStatus(),
                defDesc("YouTube Premium"),
            ),
            MediaItem(
                "tiktok",
                "TikTok",
                getString(R.string.media_category_streaming),
                R.drawable.ic_platform_tiktok,
                initialState,
                defStatus(),
                defDesc("TikTok"),
            ),
            MediaItem(
                "spotify",
                "Spotify",
                getString(R.string.media_category_music),
                R.drawable.ic_platform_spotify,
                initialState,
                defStatus(),
                defDesc("Spotify"),
            ),
            MediaItem(
                "chatgpt",
                "ChatGPT (OpenAI)",
                getString(R.string.media_category_ai),
                R.drawable.ic_platform_chatgpt,
                initialState,
                defStatus(),
                defDesc("ChatGPT"),
            ),
            MediaItem(
                "claude",
                "Claude (Anthropic)",
                getString(R.string.media_category_ai),
                R.drawable.ic_platform_claude,
                initialState,
                defStatus(),
                defDesc("Claude"),
            ),
            MediaItem(
                "gemini",
                "Google Gemini",
                getString(R.string.media_category_ai),
                R.drawable.ic_platform_gemini,
                initialState,
                defStatus(),
                defDesc("Gemini"),
            ),
        )
    }

    private suspend fun runTestForItem(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        try {
            when (item.id) {
                "netflix" -> testNetflix(item)
                "disney" -> testDisney(item)
                "max" -> testMax(item)
                "prime" -> testPrime(item)
                "youtube" -> testYouTube(item)
                "tiktok" -> testTikTok(item)
                "spotify" -> testSpotify(item)
                "chatgpt" -> testChatGpt(item)
                "claude" -> testClaude(item)
                "gemini" -> testGemini(item)
                else -> item
            }
        } catch (e: CancellationException) {
            item
        } catch (e: Throwable) {
            item.copy(
                state = TestState.TIMEOUT,
                statusText = getString(R.string.media_status_timeout),
                description = getString(R.string.media_err_fmt, e.message ?: getString(R.string.err_unknown)),
            )
        }
    }

    // --- Specific Tests ---

    private suspend fun testNetflix(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        val client = Libcore.newHttpClient().apply {
            modernTLS()
            tryProxyOutbound()
        }

        // Test licensed non-original: 81280792 (Breaking Bad)
        var body1 = ""
        var region = ""
        try {
            val req1 = client.newRequest().apply {
                setURL("https://www.netflix.com/title/81280792")
                setUserAgent(USER_AGENT)
            }
            val resp1 = req1.execute()
            body1 = Util.getStringBox(resp1.contentString)

            // Extract region from body or url
            val matcher = Pattern.compile("geolocation_country.*?([A-Za-z]{2})").matcher(body1)
            if (matcher.find()) {
                region = matcher.group(1)?.uppercase() ?: ""
            }
        } catch (_: Throwable) {
        }

        val flag = if (region.isNotBlank()) LandingIpManager.countryCodeToFlagEmoji(region) + " " + region else ""

        if (body1.isNotBlank() && !body1.contains(
                "page-404",
            ) && (body1.contains("title/81280792") || body1.contains("watch") || body1.contains("Breaking Bad"))
        ) {
            return@withContext item.copy(
                state = TestState.UNLOCKED,
                statusText = if (flag.isNotBlank()) {
                    getString(
                        R.string.media_nf_full_fmt,
                        flag,
                    )
                } else {
                    getString(R.string.media_nf_full_native)
                },
                description = getString(R.string.media_nf_full_desc),
                region = region,
            )
        }

        // Test Netflix original: 80018499 (House of Cards)
        var body2 = ""
        try {
            val req2 = client.newRequest().apply {
                setURL("https://www.netflix.com/title/80018499")
                setUserAgent(USER_AGENT)
            }
            val resp2 = req2.execute()
            body2 = Util.getStringBox(resp2.contentString)
        } catch (_: Throwable) {
        }

        if (body2.isNotBlank() && (
                body2.contains(
                    "title/80018499",
                ) || body2.contains("watch") || body2.contains("House of Cards")
                )
        ) {
            item.copy(
                state = TestState.PARTIAL,
                statusText = getString(R.string.media_nf_partial),
                description = getString(R.string.media_nf_partial_desc),
            )
        } else {
            item.copy(
                state = TestState.BLOCKED,
                statusText = getString(R.string.media_status_unsupported),
                description = getString(R.string.media_nf_blocked_desc),
            )
        }
    }

    private suspend fun testDisney(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        val client = Libcore.newHttpClient().apply {
            modernTLS()
            tryProxyOutbound()
        }
        val req = client.newRequest().apply {
            setURL("https://www.disneyplus.com/")
            setUserAgent(USER_AGENT)
        }
        val resp = req.execute()
        val body = Util.getStringBox(resp.contentString)

        if (!body.contains(
                "not available in your region",
            ) && !body.contains("restricted") && !body.contains("disneyplus.com/unavailable")
        ) {
            item.copy(
                state = TestState.UNLOCKED,
                statusText = getString(R.string.media_status_supported),
                description = getString(R.string.media_disney_desc),
            )
        } else {
            item.copy(
                state = TestState.BLOCKED,
                statusText = getString(R.string.media_status_region_limited),
                description = getString(R.string.media_disney_blocked_desc),
            )
        }
    }

    private suspend fun testMax(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        val client = Libcore.newHttpClient().apply {
            modernTLS()
            tryProxyOutbound()
        }
        val req = client.newRequest().apply {
            setURL("https://auth.max.com/")
            setUserAgent(USER_AGENT)
        }
        val resp = req.execute()
        val body = Util.getStringBox(resp.contentString)

        if (!body.contains("not available in your region") && !body.contains("unsupported_location")) {
            item.copy(
                state = TestState.UNLOCKED,
                statusText = getString(R.string.media_status_supported),
                description = getString(R.string.media_max_desc),
            )
        } else {
            item.copy(
                state = TestState.BLOCKED,
                statusText = getString(R.string.media_status_unsupported),
                description = getString(R.string.media_max_blocked_desc),
            )
        }
    }

    private suspend fun testPrime(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        val client = Libcore.newHttpClient().apply {
            modernTLS()
            tryProxyOutbound()
        }
        val req = client.newRequest().apply {
            setURL("https://www.primevideo.com/")
            setUserAgent(USER_AGENT)
        }
        val resp = req.execute()
        val body = Util.getStringBox(resp.contentString)

        if (!body.contains("georestricted") && !body.contains("not-available-in-your-country")) {
            item.copy(
                state = TestState.UNLOCKED,
                statusText = getString(R.string.media_status_supported),
                description = getString(R.string.media_prime_desc),
            )
        } else {
            item.copy(
                state = TestState.BLOCKED,
                statusText = getString(R.string.media_status_unsupported),
                description = getString(R.string.media_prime_blocked_desc),
            )
        }
    }

    private suspend fun testYouTube(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        val client = Libcore.newHttpClient().apply {
            modernTLS()
            tryProxyOutbound()
        }
        val req = client.newRequest().apply {
            setURL("https://www.youtube.com/premium")
            setUserAgent(USER_AGENT)
        }
        val resp = req.execute()
        val body = Util.getStringBox(resp.contentString)

        val matcher = Pattern.compile("\"countryCode\"\\s*:\\s*\"([A-Za-z]{2})\"").matcher(body)
        var countryCode = ""
        if (matcher.find()) {
            countryCode = matcher.group(1)?.uppercase() ?: ""
        }

        if (countryCode.isNotBlank()) {
            val flag = LandingIpManager.countryCodeToFlagEmoji(countryCode)
            item.copy(
                state = TestState.UNLOCKED,
                statusText = getString(R.string.media_yt_fmt, flag, countryCode),
                description = getString(R.string.media_yt_desc),
                region = countryCode,
            )
        } else if (body.contains("Premium") && !body.contains("not available in your country")) {
            item.copy(
                state = TestState.UNLOCKED,
                statusText = getString(R.string.media_status_supported),
                description = getString(R.string.media_yt_desc2),
            )
        } else {
            item.copy(
                state = TestState.BLOCKED,
                statusText = getString(R.string.media_status_unsupported),
                description = getString(R.string.media_yt_blocked_desc),
            )
        }
    }

    private suspend fun testTikTok(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        val client = Libcore.newHttpClient().apply {
            modernTLS()
            tryProxyOutbound()
        }
        val req = client.newRequest().apply {
            setURL("https://www.tiktok.com/")
            setUserAgent(USER_AGENT)
        }
        val resp = req.execute()
        val body = Util.getStringBox(resp.contentString)

        val matcher = Pattern.compile("\"region\"\\s*:\\s*\"([A-Za-z]{2})\"").matcher(body)
        var region = ""
        if (matcher.find()) {
            region = matcher.group(1)?.uppercase() ?: ""
        }

        if (!body.contains("tiktok-verify-page")) {
            val flag = if (region.isNotBlank()) LandingIpManager.countryCodeToFlagEmoji(region) + " " + region else ""
            item.copy(
                state = TestState.UNLOCKED,
                statusText = if (flag.isNotBlank()) {
                    getString(
                        R.string.media_tiktok_fmt,
                        flag,
                    )
                } else {
                    getString(R.string.media_status_supported)
                },
                description = getString(R.string.media_tiktok_desc),
                region = region,
            )
        } else {
            item.copy(
                state = TestState.BLOCKED,
                statusText = getString(R.string.media_status_unsupported),
                description = getString(R.string.media_tiktok_blocked_desc),
            )
        }
    }

    private suspend fun testSpotify(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        val client = Libcore.newHttpClient().apply {
            modernTLS()
            tryProxyOutbound()
        }
        val req = client.newRequest().apply {
            setURL("https://www.spotify.com/")
            setUserAgent(USER_AGENT)
        }
        val resp = req.execute()
        val body = Util.getStringBox(resp.contentString)

        if (!body.contains("not available in your country")) {
            item.copy(
                state = TestState.UNLOCKED,
                statusText = getString(R.string.media_status_supported),
                description = getString(R.string.media_spotify_desc),
            )
        } else {
            item.copy(
                state = TestState.BLOCKED,
                statusText = getString(R.string.media_status_unsupported),
                description = getString(R.string.media_spotify_blocked_desc),
            )
        }
    }

    private suspend fun testChatGpt(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        val client = Libcore.newHttpClient().apply {
            modernTLS()
            tryProxyOutbound()
        }
        val req = client.newRequest().apply {
            setURL("https://chatgpt.com/")
            setUserAgent(USER_AGENT)
        }
        val resp = req.execute()
        val body = Util.getStringBox(resp.contentString)

        if (!body.contains("cf-mitigated") && !body.contains("Attention Required") && !body.contains("1020")) {
            item.copy(
                state = TestState.UNLOCKED,
                statusText = getString(R.string.media_status_supported),
                description = getString(R.string.media_chatgpt_desc),
            )
        } else {
            item.copy(
                state = TestState.BLOCKED,
                statusText = getString(R.string.media_status_cf_blocked),
                description = getString(R.string.media_chatgpt_blocked_desc),
            )
        }
    }

    private suspend fun testClaude(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        val client = Libcore.newHttpClient().apply {
            modernTLS()
            tryProxyOutbound()
        }
        val req = client.newRequest().apply {
            setURL("https://claude.ai/login")
            setUserAgent(USER_AGENT)
        }
        val resp = req.execute()
        val body = Util.getStringBox(resp.contentString)

        if (!body.contains("App unavailable in your region") && !body.contains("403 Forbidden")) {
            item.copy(
                state = TestState.UNLOCKED,
                statusText = getString(R.string.media_status_supported),
                description = getString(R.string.media_claude_desc),
            )
        } else {
            item.copy(
                state = TestState.BLOCKED,
                statusText = getString(R.string.media_status_region_limited),
                description = getString(R.string.media_claude_blocked_desc),
            )
        }
    }

    private suspend fun testGemini(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        val client = Libcore.newHttpClient().apply {
            modernTLS()
            tryProxyOutbound()
        }
        val req = client.newRequest().apply {
            setURL("https://gemini.google.com/")
            setUserAgent(USER_AGENT)
        }
        val resp = req.execute()
        val body = Util.getStringBox(resp.contentString)

        if (!body.contains("not supported in your country") && !body.contains("unavailable in your territory")) {
            item.copy(
                state = TestState.UNLOCKED,
                statusText = getString(R.string.media_status_supported),
                description = getString(R.string.media_gemini_desc),
            )
        } else {
            item.copy(
                state = TestState.BLOCKED,
                statusText = getString(R.string.media_status_not_open),
                description = getString(R.string.media_gemini_blocked_desc),
            )
        }
    }

    // --- Adapter ---

    class MediaUnlockAdapter : ListAdapter<MediaItem, MediaUnlockAdapter.VH>(DiffCallback) {

        object DiffCallback : DiffUtil.ItemCallback<MediaItem>() {
            override fun areItemsTheSame(oldItem: MediaItem, newItem: MediaItem) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: MediaItem, newItem: MediaItem) = oldItem == newItem
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.iv_platform_icon)
            val name: TextView = view.findViewById(R.id.tv_platform_name)
            val category: TextView = view.findViewById(R.id.tv_platform_category)
            val progress: CircularProgressIndicator = view.findViewById(R.id.progress_indicator)
            val badge: TextView = view.findViewById(R.id.tv_status_badge)
            val desc: TextView = view.findViewById(R.id.tv_description)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_media_unlock, parent, false)
            return VH(view)
        }

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = getItem(position)
            holder.icon.setImageResource(item.iconRes)
            holder.name.text = item.name
            holder.category.text = item.category
            holder.desc.text = item.description

            when (item.state) {
                TestState.TESTING -> {
                    holder.progress.visibility = View.VISIBLE
                    holder.badge.visibility = View.GONE
                }
                TestState.UNLOCKED -> {
                    holder.progress.visibility = View.GONE
                    holder.badge.visibility = View.VISIBLE
                    holder.badge.text = item.statusText
                    holder.badge.setTextColor("#10B981".toColorInt()) // Green
                }
                TestState.PARTIAL -> {
                    holder.progress.visibility = View.GONE
                    holder.badge.visibility = View.VISIBLE
                    holder.badge.text = item.statusText
                    holder.badge.setTextColor("#F59E0B".toColorInt()) // Amber
                }
                TestState.BLOCKED -> {
                    holder.progress.visibility = View.GONE
                    holder.badge.visibility = View.VISIBLE
                    holder.badge.text = item.statusText
                    holder.badge.setTextColor("#EF4444".toColorInt()) // Red
                }
                TestState.TIMEOUT -> {
                    holder.progress.visibility = View.GONE
                    holder.badge.visibility = View.VISIBLE
                    holder.badge.text = item.statusText
                    holder.badge.setTextColor("#F97316".toColorInt()) // Orange
                }
                TestState.NOT_CONNECTED -> {
                    holder.progress.visibility = View.GONE
                    holder.badge.visibility = View.VISIBLE
                    holder.badge.text = item.statusText
                    holder.badge.setTextColor("#94A3B8".toColorInt()) // Slate Gray
                }
            }
        }
    }
}
