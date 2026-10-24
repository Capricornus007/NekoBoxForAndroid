package io.nekohasekai.sagernet.database

import android.util.AtomicFile
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import java.io.*
import java.security.MessageDigest

/** One private undo point per subscription. Never included in diagnostic exports or backups. */
object SubscriptionHistory {
    private const val LIMIT = 64 * 1024 * 1024
    private fun file(id: Long) = AtomicFile(File(SagerNet.application.noBackupFilesDir, "subscription-$id.previous"))

    @Synchronized fun available(id: Long) = runCatching {
        file(id).openRead().use { }
        true
    }.getOrDefault(false)

    @Synchronized fun delete(id: Long) = file(id).delete()

    @Synchronized fun capture(group: ProxyGroup, profiles: List<ProxyEntity>) {
        val bytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(1)
                fun write(value: ByteArray) {
                    require(buffer.size().toLong() + value.size + 4 <= LIMIT)
                    output.writeInt(value.size)
                    output.write(value)
                }
                write(KryoConverters.serialize(group))
                output.writeInt(profiles.size)
                profiles.forEach { write(KryoConverters.serialize(it)) }
            }
        }.toByteArray()
        require(bytes.size <= LIMIT) { "Subscription undo snapshot is too large" }
        val target = file(group.id)
        val stream = target.startWrite()
        try {
            stream.write(bytes)
            target.finishWrite(stream)
        } catch (e: Exception) {
            target.failWrite(stream)
            throw e
        }
    }

    fun revision(profiles: List<ProxyEntity>): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        profiles.sortedBy { it.id }.forEach {
            // Traffic and health may change while the confirmation is open.
            digest.update("${it.id}:${it.userOrder}:${it.type}:".toByteArray())
            digest.update(KryoConverters.serialize(it.requireBean()))
        }
        return digest.digest()
    }

    fun referencedProfiles(): Set<Long> = buildSet {
        add(DataStore.selectedProxy)
        if (DataStore.serviceState.started) add(DataStore.currentProfile)
        NetworkProfiles.policies().forEach { add(it.profileId) }
        SagerDatabase.rulesDao.allRules().forEach {
            add(it.outbound)
            add(it.fallbackProfile)
        }
        SagerDatabase.groupDao.allGroups().forEach {
            add(it.frontProxy)
            add(it.landingProxy)
        }
        SagerDatabase.proxyDao.getAll().forEach { (it.requireBean() as? ChainBean)?.proxies?.let(::addAll) }
    }

    /** Caller owns the Room transaction; recheck the revision after the user confirms. */
    @Synchronized fun restore(id: Long, expected: ByteArray) {
        val current = SagerDatabase.proxyDao.getByGroup(id)
        check(revision(current).contentEquals(expected)) { SagerNet.application.getString(R.string.subscription_changed_during_update) }
        val target = file(id)
        val bytes = target.openRead().use { input ->
            require(input.available() <= LIMIT)
            input.readBytes()
        }
        val (oldGroup, oldProfiles) = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == 1)
            fun read(): ByteArray {
                val size = input.readInt()
                require(size in 1..LIMIT && size <= input.available())
                return ByteArray(size).also(input::readFully)
            }
            val group = KryoConverters.deserialize(ProxyGroup(), read())
            val count = input.readInt()
            require(count in 0..100000)
            val profiles = List(count) { KryoConverters.deserialize(ProxyEntity(), read()) }
            require(input.available() == 0 && group.id == id && profiles.all { it.groupId == id && it.id > 0 })
            require(profiles.map { it.id }.distinct().size == profiles.size)
            group to profiles
        }
        val oldIds = oldProfiles.map { it.id }.toSet()
        val removedIds = current.map { it.id }.toSet() - oldIds
        check(removedIds.intersect(referencedProfiles()).isEmpty()) { SagerNet.application.getString(R.string.subscription_undo_referenced) }
        check(SagerDatabase.proxyDao.getEntities(oldIds.toList()).all { it.groupId == id }) { "Profile ID conflict" }
        val currentById = current.associateBy { it.id }
        oldProfiles.forEach { profile ->
            currentById[profile.id]?.let {
                profile.tx = it.tx
                profile.rx = it.rx
            }
        }
        SagerDatabase.proxyDao.deleteByGroup(id)
        SagerDatabase.proxyDao.insert(oldProfiles)
        SagerDatabase.groupDao.getById(id)?.let { group ->
            group.subscription?.lastUpdated = oldGroup.subscription?.lastUpdated ?: 0
            group.subscription?.subscriptionUserinfo = oldGroup.subscription?.subscriptionUserinfo
            SagerDatabase.groupDao.updateGroup(group)
        }
    }
}
