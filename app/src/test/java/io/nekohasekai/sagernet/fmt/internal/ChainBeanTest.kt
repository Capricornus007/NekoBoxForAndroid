package io.nekohasekai.sagernet.fmt.internal

import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.KryoConverters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ChainBeanTest {

    @Test
    fun roundTripsAllStrategies() {
        for (strategy in listOf(
            ChainBean.STRATEGY_CHAIN,
            ChainBean.STRATEGY_WATERFALL,
            ChainBean.STRATEGY_FASTEST,
        )) {
            val source = ChainBean().apply {
                initializeDefaultValues()
                name = "group-$strategy"
                this.strategy = strategy
                proxies = listOf(11L, 22L, 33L)
            }

            val restored = KryoConverters.chainDeserialize(KryoConverters.serialize(source))

            assertEquals(strategy, restored.strategy)
            assertEquals(source.name, restored.name)
            assertEquals(source.proxies, restored.proxies)
        }
    }

    @Test
    fun roundTripsRegexCandidateSettings() {
        val source = ChainBean().apply {
            initializeDefaultValues()
            strategy = ChainBean.STRATEGY_FASTEST
            candidateMode = ChainBean.CANDIDATE_MODE_REGEX
            sourceGroupId = 42L
            nameRegex = "(?:Германия|США)"
            ignoreCase = false
            proxies = listOf(11L, 22L)
        }

        val restored = KryoConverters.chainDeserialize(KryoConverters.serialize(source))

        assertEquals(ChainBean.CANDIDATE_MODE_REGEX, restored.candidateMode)
        assertEquals(42L, restored.sourceGroupId)
        assertEquals("(?:Германия|США)", restored.nameRegex)
        assertEquals(false, restored.ignoreCase)
        assertEquals(listOf(11L, 22L), restored.proxies)
    }

    @Test
    fun versionOneDataDefaultsToChainStrategy() {
        val output = ByteBufferOutput(128)
        output.writeInt(1)
        output.writeInt(2)
        output.writeLong(7L)
        output.writeLong(8L)
        output.writeInt(1)
        output.writeString("legacy")
        output.writeString("")
        output.writeString("")

        val restored = KryoConverters.chainDeserialize(output.toBytes())

        assertEquals(ChainBean.STRATEGY_CHAIN, restored.strategy)
        assertEquals(listOf(7L, 8L), restored.proxies)
        assertEquals("legacy", restored.name)
    }

    @Test
    fun versionTwoDataDefaultsToManualCandidates() {
        val output = ByteBufferOutput(128)
        output.writeInt(2)
        output.writeInt(ChainBean.STRATEGY_FASTEST)
        output.writeInt(1)
        output.writeLong(7L)
        output.writeInt(1)
        output.writeString("legacy-fastest")
        output.writeString("")
        output.writeString("")

        val restored = KryoConverters.chainDeserialize(output.toBytes())

        assertEquals(ChainBean.STRATEGY_FASTEST, restored.strategy)
        assertEquals(ChainBean.CANDIDATE_MODE_MANUAL, restored.candidateMode)
        assertEquals(0L, restored.sourceGroupId)
        assertEquals("", restored.nameRegex)
        assertEquals(true, restored.ignoreCase)
        assertEquals(listOf(7L), restored.proxies)
    }

    @Test
    fun strategiesMapToDistinctProfileTypes() {
        val expectedTypes = mapOf(
            ChainBean.STRATEGY_CHAIN to ProxyEntity.TYPE_CHAIN,
            ChainBean.STRATEGY_WATERFALL to ProxyEntity.TYPE_WATERFALL,
            ChainBean.STRATEGY_FASTEST to ProxyEntity.TYPE_FASTEST,
        )

        for ((strategy, expectedType) in expectedTypes) {
            val bean = ChainBean().apply {
                initializeDefaultValues()
                this.strategy = strategy
                proxies = listOf(1L)
            }
            val entity = ProxyEntity().putBean(bean)

            assertEquals(expectedType, entity.type)
            assertSame(bean, entity.chainBean)
        }
    }

    @Test
    fun regexCandidatesSupportExamplesAndCaseToggle() {
        val candidates = listOf(
            namedProxy("🇩🇪 ⚡ Германия"),
            namedProxy("🇵🇱 ⚡ Польша"),
            namedProxy("🇺🇸 США Денвер"),
            namedProxy("🇧🇬 ⚡ Болгария"),
            namedProxy("🇷🇺 Россия Санкт-Петербург"),
            namedProxy("ГЕРМАНИЯ"),
        )

        assertEquals(
            listOf("🇩🇪 ⚡ Германия", "🇵🇱 ⚡ Польша", "🇺🇸 США Денвер", "🇧🇬 ⚡ Болгария", "ГЕРМАНИЯ"),
            FastestCandidateResolver.filterRegexCandidates(
                candidates,
                "^(?!.*(?:Россия|🇷🇺)).*$",
                ignoreCase = true,
            ).map { it.displayName() },
        )
        assertEquals(
            listOf("🇩🇪 ⚡ Германия", "🇺🇸 США Денвер", "🇧🇬 ⚡ Болгария", "ГЕРМАНИЯ"),
            FastestCandidateResolver.filterRegexCandidates(
                candidates,
                "(?:🇩🇪|Германия|🇺🇸|США|🇧🇬|Болгария)",
                ignoreCase = true,
            ).map { it.displayName() },
        )
        assertEquals(
            listOf("ГЕРМАНИЯ"),
            FastestCandidateResolver.filterRegexCandidates(
                candidates,
                "германия",
                ignoreCase = true,
            ).map { it.displayName() },
        )
        assertEquals(
            emptyList<String>(),
            FastestCandidateResolver.filterRegexCandidates(
                candidates,
                "германия",
                ignoreCase = false,
            ).map { it.displayName() },
        )
    }

    @Test
    fun invalidRegexReportsValidationError() {
        val error = assertThrows(FastestCandidateResolutionException::class.java) {
            FastestCandidateResolver.filterRegexCandidates(emptyList(), "(", ignoreCase = true)
        }

        assertEquals(FastestCandidateResolutionError.INVALID_REGEX, error.error)
    }

    private fun namedProxy(name: String): ProxyEntity {
        return ProxyEntity(
            type = ProxyEntity.TYPE_CHAIN,
            chainBean = ChainBean().apply {
                initializeDefaultValues()
                this.name = name
            },
        )
    }
}
