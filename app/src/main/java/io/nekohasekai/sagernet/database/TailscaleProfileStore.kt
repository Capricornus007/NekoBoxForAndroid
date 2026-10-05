package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.fmt.tailscale.TailscaleBean
import java.util.concurrent.Callable

/** Synchronous, off-main, field-only saves. No native work belongs in these transactions. */
internal object TailscaleProfileStore {
    fun read(profileId: Long): ProxyEntity = SagerDatabase.proxyDao.getById(profileId)
        ?.takeIf { it.type == ProxyEntity.TYPE_TAILSCALE && it.tailscaleBean != null }
        ?: error("tailscale:conflict")

    fun compareAndSetExit(profileId: Long, expectedIdentity: String, expectedExit: String, newExit: String): ProxyEntity = compareAndSetRuntimeExit(profileId, expectedIdentity, expectedExit, newExit, null)

    internal fun compareAndSetRuntimeExit(
        profileId: Long,
        expectedIdentity: String,
        expectedExit: String,
        newExit: String,
        runtimeBean: TailscaleBean?,
    ): ProxyEntity = SagerDatabase.instance.runInTransaction(
        Callable {
            val current = read(profileId)
            check(current.uuid == expectedIdentity && current.tailscaleBean!!.exitNode.orEmpty() == expectedExit) { "tailscale:conflict" }
            if (runtimeBean != null) check(sameRuntime(current.tailscaleBean!!, runtimeBean)) { "tailscale:conflict" }
            write(current, current.tailscaleBean!!.clone().apply { exitNode = newExit })
        },
    )

    fun saveEditor(
        profileId: Long,
        expectedIdentity: String,
        expectedExit: String,
        proposedBean: TailscaleBean,
        exitEdited: Boolean,
    ): ProxyEntity = SagerDatabase.instance.runInTransaction(
        Callable {
            val current = read(profileId)
            check(current.uuid == expectedIdentity) { "tailscale:conflict" }
            val savedExit = current.tailscaleBean!!.exitNode.orEmpty()
            check(!exitEdited || savedExit == expectedExit) { "tailscale:conflict" }
            write(current, proposedBean.clone().apply { if (!exitEdited) exitNode = savedExit })
        },
    )

    internal fun sameRuntime(a: TailscaleBean, b: TailscaleBean): Boolean = a.clone().apply {
        exitNode = ""
        name = ""
    } == b.clone().apply {
        exitNode = ""
        name = ""
    }

    private fun write(current: ProxyEntity, bean: TailscaleBean): ProxyEntity {
        check(SagerDatabase.proxyDao.updateTailscaleBean(current.id, current.uuid, bean) == 1) { "tailscale:conflict" }
        return read(current.id)
    }
}
