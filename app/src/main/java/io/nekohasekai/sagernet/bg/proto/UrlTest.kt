package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.sync.withLock

class UrlTest(private val service: () -> ISagerNetService? = { null }) {

    val link = DataStore.connectionTestURL
    private val timeout = DataStore.connectionTestTimeout

    suspend fun doTest(profile: ProxyEntity): Int {
        Logs.d("URLTest ${profile.displayName()}: start, link=$link, timeout=${timeout}ms")
        // The test config is what the probe would run, including group front and landing hops.
        val nodes = buildConfig(profile, forTest = true).tailscaleEndpoints.keys
        if (nodes.isEmpty()) return TestInstance(profile, link, timeout).doTest()
        return TailscaleAccess.run(service, nodes, { it.urlTestProfile(profile.id) }) {
            TailscaleAccess.probeLock.withLock { TestInstance(profile, link, timeout).doTest() }
        }
    }
}
