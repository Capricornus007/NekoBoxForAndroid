package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.Logs

class UrlTest(private val service: () -> ISagerNetService? = { null }) {

    val link = DataStore.connectionTestURL
    private val timeout = DataStore.connectionTestTimeout

    suspend fun doTest(profile: ProxyEntity): Int {
        Logs.d("URLTest ${profile.displayName()}: start, link=$link, timeout=${timeout}ms")
        // The test config is what the probe would run, including group front and landing hops.
        val prepared = buildConfig(profile, forTest = true)
        val nodes = prepared.tailscaleEndpoints.keys
        if (nodes.isEmpty()) return TestInstance(profile, link, timeout, prepared).doTest()
        return TailscaleAccess.run(service, nodes, { it.urlTestProfile(profile.id) }) {
            TestInstance(profile, link, timeout, prepared).doTest()
        }
    }
}
