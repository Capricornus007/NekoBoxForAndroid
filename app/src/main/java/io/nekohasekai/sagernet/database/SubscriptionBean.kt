package io.nekohasekai.sagernet.database

import android.os.Parcelable
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.Serializable

class SubscriptionBean : Serializable() {
    @JvmField
    var type: Int? = null

    @JvmField
    var link: String? = null

    @JvmField
    var token: String? = null

    @JvmField
    var forceResolve: Boolean? = null

    @JvmField
    var deduplication: Boolean? = null

    @JvmField
    var updateWhenConnectedOnly: Boolean? = null

    @JvmField
    var customUserAgent: String? = null

    @JvmField
    var autoUpdate: Boolean? = null

    @JvmField
    var autoUpdateDelay: Int? = null

    @JvmField
    var lastUpdated: Int? = null

    @JvmField
    var filterMode: Int? = null

    @JvmField
    var filterRegex: String? = null

    @JvmField
    var customDnsResolver: String? = null

    // Only resolve the domains of this subscription's own servers; blank falls back to global DNS.
    // Kept from our fork so per-subscription DNS resolution survives the merge onto the newer model.
    @JvmField
    var serverDnsResolver: String? = null

    @JvmField
    var bytesUsed: Long? = null

    @JvmField
    var bytesRemaining: Long? = null

    @JvmField
    var username: String? = null

    @JvmField
    var expiryDate: Int? = null

    @JvmField
    var protocols: MutableList<String>? = null

    @JvmField
    var subscriptionUserinfo: String? = null

    // Provider metadata from the subscription response (support-url, profile-web-page-url,
    // announce headers or `#key: value` preamble lines). Cleared when the next fetch omits them.
    @JvmField
    var supportUrl: String? = null

    @JvmField
    var webPageUrl: String? = null

    @JvmField
    var announce: String? = null

    // Epoch seconds of the last expiry reminder, 0 when none was posted yet.
    @JvmField
    var expiryNotifiedAt: Int? = null

    // Last `profile-update-interval` applied, in minutes (0 when the provider sends none). A new
    // provider value replaces the auto-update settings; an unchanged one leaves user edits alone.
    @JvmField
    var providerUpdateInterval: Int? = null

    // Send the per-install device identifier headers (X-HWID and friends) with every fetch, for
    // panels that count devices per subscription.
    @JvmField
    var sendDeviceId: Boolean? = null

    // SubscriptionFormat value: which response format to request, or Auto.
    @JvmField
    var outputFormat: Int? = null

    // What the last accepted update could not import, empty when it took everything.
    @JvmField
    var importWarning: String? = null

    // User-approved URLs tried in order when the main link fails, one per line.
    @JvmField
    var backupLinks: String? = null

    // Provider-proposed replacement and backup URLs (new-url / new-domain, fallback-url). Never
    // fetched until the user approves them; cleared when the next update omits them.
    @JvmField
    var offeredLink: String? = null

    @JvmField
    var offeredBackupLink: String? = null

    // The SubscriptionFormat request the stored profiles came from, set by each accepted update:
    // 0 for the default request (and before any update), SubscriptionFormat.UNKNOWN for a custom
    // User-Agent or a file. Auto requests it first; another request needs the user's agreement
    // before it replaces stored profiles.
    @JvmField
    var negotiatedFormat: Int? = null

    // Version 6 fields are written only when one holds a value, so subscriptions that do not use
    // them keep their version 5 bytes. Version 7 adds serverDnsResolver the same way.
    private fun hasVersion6Fields() = (outputFormat ?: 0) != 0 || !importWarning.isNullOrEmpty() ||
        !backupLinks.isNullOrEmpty() || !offeredLink.isNullOrEmpty() || !offeredBackupLink.isNullOrEmpty() ||
        (negotiatedFormat ?: 0) != 0

    override fun serializeToBuffer(output: ByteBufferOutput) {
        val version = when {
            !serverDnsResolver.isNullOrEmpty() -> 7
            hasVersion6Fields() -> 6
            else -> 5
        }
        output.writeInt(version)
        output.writeInt(type!!)
        output.writeString(link)
        output.writeBoolean(forceResolve!!)
        output.writeBoolean(deduplication!!)
        output.writeBoolean(updateWhenConnectedOnly!!)
        output.writeString(customUserAgent)
        output.writeBoolean(autoUpdate!!)
        output.writeInt(autoUpdateDelay!!)
        output.writeInt(lastUpdated!!)
        output.writeString(subscriptionUserinfo)
        output.writeInt(filterMode!!)
        output.writeString(filterRegex)
        output.writeString(customDnsResolver)
        output.writeString(supportUrl)
        output.writeString(webPageUrl)
        output.writeString(announce)
        output.writeInt(expiryNotifiedAt!!)
        output.writeInt(providerUpdateInterval!!)
        output.writeBoolean(sendDeviceId!!)
        if (version >= 6) {
            output.writeInt(outputFormat ?: 0)
            output.writeString(importWarning)
            output.writeString(backupLinks)
            output.writeString(offeredLink)
            output.writeString(offeredBackupLink)
            output.writeInt(negotiatedFormat ?: 0)
        }
        if (version >= 7) {
            output.writeString(serverDnsResolver)
        }
    }

    fun serializeForShare(output: ByteBufferOutput) {
        output.writeInt(0)
        output.writeInt(type!!)
        output.writeString(link)
        output.writeBoolean(forceResolve!!)
        output.writeBoolean(deduplication!!)
        output.writeBoolean(updateWhenConnectedOnly!!)
        output.writeString(customUserAgent)
    }

    override fun deserializeFromBuffer(input: ByteBufferInput) {
        val version = input.readInt()
        type = input.readInt()
        link = input.readString()
        forceResolve = input.readBoolean()
        deduplication = input.readBoolean()
        updateWhenConnectedOnly = input.readBoolean()
        customUserAgent = input.readString()
        autoUpdate = input.readBoolean()
        autoUpdateDelay = input.readInt()
        lastUpdated = input.readInt()
        subscriptionUserinfo = input.readString()
        if (version >= 2) {
            filterMode = input.readInt()
            filterRegex = input.readString()
        }
        if (version >= 3) {
            customDnsResolver = input.readString()
        }
        if (version >= 4) {
            supportUrl = input.readString()
            webPageUrl = input.readString()
            announce = input.readString()
            expiryNotifiedAt = input.readInt()
            providerUpdateInterval = input.readInt()
        }
        if (version >= 5) {
            sendDeviceId = input.readBoolean()
        }
        if (version >= 6) {
            outputFormat = input.readInt()
            importWarning = input.readString()
            backupLinks = input.readString()
            offeredLink = input.readString()
            offeredBackupLink = input.readString()
            negotiatedFormat = input.readInt()
        }
        if (version >= 7) {
            serverDnsResolver = input.readString()
        }
    }

    fun deserializeFromShare(input: ByteBufferInput) {
        val version = input.readInt()
        type = input.readInt()
        link = input.readString()
        forceResolve = input.readBoolean()
        deduplication = input.readBoolean()
        updateWhenConnectedOnly = input.readBoolean()
        customUserAgent = input.readString()
    }

    override fun initializeDefaultValues() {
        type = type ?: 0
        link = link ?: ""
        token = token ?: ""
        forceResolve = forceResolve ?: false
        deduplication = deduplication ?: false
        updateWhenConnectedOnly = updateWhenConnectedOnly ?: false
        customUserAgent = customUserAgent ?: ""
        autoUpdate = autoUpdate ?: false
        autoUpdateDelay = autoUpdateDelay ?: 1440
        lastUpdated = lastUpdated ?: 0
        filterMode = filterMode ?: 0
        filterRegex = filterRegex ?: ""
        customDnsResolver = customDnsResolver ?: ""
        serverDnsResolver = serverDnsResolver ?: ""
        bytesUsed = bytesUsed ?: 0L
        bytesRemaining = bytesRemaining ?: 0L
        username = username ?: ""
        expiryDate = expiryDate ?: 0
        protocols = protocols ?: mutableListOf()
        supportUrl = supportUrl ?: ""
        webPageUrl = webPageUrl ?: ""
        announce = announce ?: ""
        expiryNotifiedAt = expiryNotifiedAt ?: 0
        providerUpdateInterval = providerUpdateInterval ?: 0
        sendDeviceId = sendDeviceId ?: false
        outputFormat = outputFormat ?: 0
        importWarning = importWarning ?: ""
        backupLinks = backupLinks ?: ""
        offeredLink = offeredLink ?: ""
        offeredBackupLink = offeredBackupLink ?: ""
        negotiatedFormat = negotiatedFormat ?: 0
    }

    /**
     * Expiry from `subscription-userinfo` (`expire=` epoch seconds). Null when absent or `0`,
     * which panels such as 3x-ui send for subscriptions without an expiry.
     */
    fun expiry(): Long? = subscriptionUserinfo?.let { EXPIRE.find(it)?.groupValues?.get(1)?.toLongOrNull() }?.takeIf { it > 0 }

    companion object {
        private val EXPIRE = "expire=([0-9]+)".toRegex()

        @JvmField
        val CREATOR: Parcelable.Creator<SubscriptionBean> = object : Serializable.CREATOR<SubscriptionBean>() {
            override fun newInstance() = SubscriptionBean()
            override fun newArray(size: Int): Array<SubscriptionBean?> = arrayOfNulls(size)
        }
    }
}
