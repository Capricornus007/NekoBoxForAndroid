package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveOutboundTrackerTest {

    @Test
    fun testGetStrategyDisplayName() {
        val balancer = ProxyEntity().apply {
            type = ProxyEntity.TYPE_BALANCER
            putBean(BalancerBean().apply {
                strategy = BalancerBean.STRATEGY_LEAST_PING
            })
        }
        assertEquals("最低延迟", ActiveOutboundTracker.getStrategyDisplayName(balancer))

        val balancerLoad = ProxyEntity().apply {
            type = ProxyEntity.TYPE_BALANCER
            putBean(BalancerBean().apply {
                strategy = BalancerBean.STRATEGY_LEAST_LOAD
            })
        }
        assertEquals("最低负载", ActiveOutboundTracker.getStrategyDisplayName(balancerLoad))

        val balancerRR = ProxyEntity().apply {
            type = ProxyEntity.TYPE_BALANCER
            putBean(BalancerBean().apply {
                strategy = BalancerBean.STRATEGY_ROUND_ROBIN
            })
        }
        assertEquals("轮询", ActiveOutboundTracker.getStrategyDisplayName(balancerRR))
    }

    @Test
    fun testFormatNotificationTitleSingleNode() {
        val singleNode = ProxyEntity().apply {
            id = 100L
            type = 0 // SS
            putBean(ShadowsocksBean().apply {
                name = "香港 01"
            })
        }
        val ruleTitle = ActiveOutboundTracker.formatNotificationTitle(singleNode, isGlobalMode = false)
        assertTrue(ruleTitle.contains("[规则]"))
        assertTrue(ruleTitle.contains("香港 01"))

        val globalTitle = ActiveOutboundTracker.formatNotificationTitle(singleNode, isGlobalMode = true)
        assertTrue(globalTitle.contains("[全局]"))
        assertTrue(globalTitle.contains("香港 01"))
    }

    @Test
    fun testFormatNotificationTitleBalancer() {
        val balancer = ProxyEntity().apply {
            id = 1L
            type = ProxyEntity.TYPE_BALANCER
            putBean(BalancerBean().apply {
                name = "自动选择"
                strategy = BalancerBean.STRATEGY_LEAST_PING
            })
        }
        val leafNode = ProxyEntity().apply {
            id = 101L
            type = 0
            putBean(ShadowsocksBean().apply {
                name = "日本 02"
            })
        }

        val titleWithLeaf = ActiveOutboundTracker.formatNotificationTitle(balancer, leafNode)
        assertEquals("自动选择(最低延迟) ➔ 日本 02", titleWithLeaf)

        val titleWithoutLeaf = ActiveOutboundTracker.formatNotificationTitle(balancer, null)
        assertEquals("自动选择 · 最低延迟", titleWithoutLeaf)
    }

    @Test
    fun testReset() {
        ActiveOutboundTracker.reset()
        assertEquals(0L, ActiveOutboundTracker.activeLeafProfileId)
        assertEquals("", ActiveOutboundTracker.activeLeafProfileName)
    }
}
