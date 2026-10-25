package io.nekohasekai.sagernet.fmt.internal

import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import org.junit.Assert.*
import org.junit.Test

class FastestProtocolFilterTest {
    private val hy1 = hysteria(1, 1)
    private val hy2 = hysteria(2, 2)
    private val vless = ProxyEntity(
        id = 3,
        type = ProxyEntity.TYPE_VMESS,
        vmessBean = VMessBean().apply {
            alterId = -1
            initializeDefaultValues()
            name = "Hysteria 2 misleading name"
        },
    )
    private val candidates = listOf(hy2, vless, hy1)

    private fun hysteria(id: Long, version: Int) = ProxyEntity(
        id = id,
        type = ProxyEntity.TYPE_HYSTERIA,
        hysteriaBean = HysteriaBean().apply {
            initializeDefaultValues()
            protocolVersion = version
            name = "VLESS misleading name"
        },
    )

    @Test fun defaultsPreserveOrderWithoutLookingUpAnyHops() {
        assertSame(candidates, FastestProtocolFilter.filter(candidates, 0, emptySet(), { error("unexpected lookup") }))
    }

    @Test fun excludingHysteria2DoesNotExcludeHysteria1OrVless() {
        assertEquals(listOf(vless, hy1), FastestProtocolFilter.filter(candidates, 0, setOf("hysteria2"), { null }))
        assertEquals(listOf(vless), FastestProtocolFilter.filter(candidates, 0, setOf("hysteria", "hysteria2"), { null }))
    }

    @Test fun allowListUsesRealProtocolAndCanBeEmpty() {
        assertEquals(listOf(vless), FastestProtocolFilter.filter(candidates, 1, setOf("vless"), { null }))
        assertTrue(FastestProtocolFilter.filter(candidates, 1, emptySet(), { null }).isEmpty())
        assertEquals("vmess", FastestProtocolFilter.protocol(vless.copy(vmessBean = VMessBean().apply { initializeDefaultValues() })))
        assertEquals("custom", FastestProtocolFilter.protocol(ProxyEntity(type = ProxyEntity.TYPE_CONFIG)))
    }

    @Test fun regexAndProtocolFiltersAreCombined() {
        val names = FastestCandidateResolver.filterRegexCandidates(candidates, "misleading", true)
        assertEquals(listOf(vless), FastestProtocolFilter.filter(names, 1, setOf("vless"), { null }))
    }

    @Test fun filtersAllChainHopsAndGroupFrontLandingHopsWithoutChangingChain() {
        val chain = ProxyEntity(
            id = 4,
            type = ProxyEntity.TYPE_CHAIN,
            chainBean = ChainBean().apply {
                initializeDefaultValues()
                proxies = listOf(vless.id, hy2.id)
            },
        )
        val byId = (candidates + chain).associateBy { it.id }
        assertTrue(FastestProtocolFilter.filter(listOf(chain), 0, setOf("hysteria2"), byId::get).isEmpty())
        assertEquals(listOf(vless.id, hy2.id), chain.chainBean!!.proxies)
        assertTrue(FastestProtocolFilter.filter(listOf(vless), 1, setOf("vless"), byId::get) { listOf(hy2) }.isEmpty())
        chain.chainBean!!.proxies = listOf(vless.id)
        assertEquals(listOf(chain), FastestProtocolFilter.filter(listOf(chain), 1, setOf("vless"), byId::get))
        chain.chainBean!!.proxies = listOf(chain.id)
        assertTrue(FastestProtocolFilter.filter(listOf(chain), 0, setOf("hysteria2"), byId::get).isEmpty())
        chain.chainBean!!.proxies = listOf(999L)
        assertTrue(FastestProtocolFilter.filter(listOf(chain), 0, setOf("hysteria2"), byId::get).isEmpty())
    }

    @Test fun invalidImportedFilterIsRejected() {
        for ((mode, keys) in listOf(2 to emptySet(), 0 to setOf("typo"))) {
            assertEquals(
                FastestCandidateResolutionError.INVALID_PROTOCOL_FILTER,
                assertThrows(FastestCandidateResolutionException::class.java) {
                    FastestProtocolFilter.filter(candidates, mode, keys, { null })
                }.error,
            )
        }
    }

    @Test fun oldVersionThreeProfilesKeepAllProtocols() {
        val output = ByteBufferOutput(256)
        output.writeInt(3)
        output.writeInt(ChainBean.STRATEGY_FASTEST)
        output.writeInt(ChainBean.CANDIDATE_MODE_REGEX)
        output.writeLong(42)
        output.writeString(".*")
        output.writeBoolean(true)
        output.writeInt(1)
        output.writeLong(3)
        output.writeInt(1)
        output.writeString("legacy-fastest")
        output.writeString("")
        output.writeString("")
        val restored = KryoConverters.chainDeserialize(output.toBytes())
        assertEquals(listOf(3L), restored.proxies)
        assertEquals("legacy-fastest", restored.name)
        assertEquals(0, restored.protocolFilterMode)
        assertTrue(restored.protocols.isEmpty())
        assertSame(candidates, FastestProtocolFilter.filter(candidates, restored.protocolFilterMode, restored.protocols.toSet(), { null }))
    }

    @Test fun cloningKeepsExclusionsAndIsIndependent() {
        val source = ChainBean().apply {
            initializeDefaultValues()
            strategy = ChainBean.STRATEGY_FASTEST
            protocols = arrayListOf("hysteria", "hysteria2")
        }
        val clone = source.clone()
        assertEquals(0, clone.protocolFilterMode)
        assertEquals(source.protocols, clone.protocols)
        clone.protocols.clear()
        assertEquals(listOf("hysteria", "hysteria2"), source.protocols)
    }
}
