package io.nekohasekai.sagernet.fmt.internal

import android.os.Parcelable
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.Serializable
import io.nekohasekai.sagernet.ktx.app
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.utils.JavaUtil

/**
 * Hops of [entity] in dial order: the first hop is dialed directly, the last hop reaches the
 * destination. Nested chains are inlined in their own order. A chain without hops, a hop whose
 * profile was deleted or cannot be dialed, or a chain that contains itself fails with a readable
 * message instead of silently shortening the chain.
 */
fun chainHops(entity: ProxyEntity, visiting: MutableSet<Long> = linkedSetOf()): List<ProxyEntity> {
    val bean = entity.requireBean()
    if (bean !is ChainBean) {
        // A full custom config replaces the generated config as a whole; inlined as one outbound
        // the core rejects it.
        require(entity.canBuild() && (bean !is ConfigBean || bean.type != 0)) {
            app.getString(R.string.chain_hop_unsupported, entity.displayName())
        }
        return listOf(entity)
    }
    require(visiting.add(entity.id)) { app.getString(R.string.chain_circular, entity.displayName()) }
    val ids = bean.proxies.orEmpty()
    require(ids.isNotEmpty()) { app.getString(R.string.chain_no_hops, entity.displayName()) }
    val byId = SagerDatabase.proxyDao.getEntities(ids).associateBy { it.id }
    val hops = ids.flatMap { id ->
        val hop = requireNotNull(byId[id]) { app.getString(R.string.chain_missing_hop, entity.displayName()) }
        chainHops(hop, visiting)
    }
    visiting.remove(entity.id)
    return hops
}

/** Whether [entity] is [profileId] or contains it at any nesting depth. */
fun chainContains(entity: ProxyEntity, profileId: Long, visited: MutableSet<Long> = hashSetOf()): Boolean {
    if (entity.id == profileId) return true
    val bean = entity.requireBean() as? ChainBean ?: return false
    if (!visited.add(entity.id)) return false
    return SagerDatabase.proxyDao.getEntities(bean.proxies.orEmpty()).any { chainContains(it, profileId, visited) }
}

class ChainBean : InternalBean() {
    @JvmField
    var proxies: List<Long>? = null

    override fun displayName(): String {
        if (JavaUtil.isNotBlank(name)) {
            return name!!
        } else {
            return ("Chain " + kotlin.math.abs(hashCode()))
        }
    }

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        name = name ?: ""
        proxies = proxies ?: mutableListOf()
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(1)
        output.writeInt(proxies!!.size)
        for (proxy in proxies!!) {
            output.writeLong(proxy)
        }
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        if (version < 1) {
            input.readString()
            input.readInt()
        }
        val length = input.readInt()
        val decoded = mutableListOf<Long>()
        proxies = decoded
        repeat(length) {
            decoded.add(input.readLong())
        }
    }

    override fun clone(): ChainBean = KryoConverters.deserialize(ChainBean(), KryoConverters.serialize(this))

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<ChainBean> = object : Serializable.CREATOR<ChainBean>() {
            override fun newInstance() = ChainBean()
            override fun newArray(size: Int): Array<ChainBean?> = arrayOfNulls(size)
        }
    }
}
