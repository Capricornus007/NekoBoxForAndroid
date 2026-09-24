package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TCP Ping 只能量到有 TCP 監聽的節點。純 UDP/QUIC 協定被誤當成「可測」時，結果會是
 * 3 秒超時＋「失敗」，而節點其實是好的；反過來說，把有 TCP 監聽的協定誤列進排除清單
 * 會藏掉可用的測量結果。兩邊都要釘住。
 */
class TcpPingReachabilityTest {

    private fun entity(bean: AbstractBean): ProxyEntity = ProxyEntity().apply {
        bean.initializeDefaultValues()
        putBean(bean)
    }

    private fun hysteria(version: Int, protocol: Int) = HysteriaBean().apply {
        initializeDefaultValues()
        protocolVersion = version
        this.protocol = protocol
    }

    @Test
    fun udpOnlyTransportsAreNotTcpReachable() {
        assertFalse(TcpPing.isTcpReachable(entity(WireGuardBean())))
        assertFalse(TcpPing.isTcpReachable(entity(TuicBean())))
    }

    @Test
    fun hysteria2IsQuicOnly() {
        val bean = hysteria(2, HysteriaBean.PROTOCOL_UDP)
        assertFalse(TcpPing.isTcpReachable(entity(bean)))
    }

    @Test
    fun hysteria1UdpIsNotReachableButFakeTcpIs() {
        assertFalse(
            TcpPing.isTcpReachable(entity(hysteria(1, HysteriaBean.PROTOCOL_UDP))),
        )
        assertTrue(
            TcpPing.isTcpReachable(entity(hysteria(1, HysteriaBean.PROTOCOL_FAKETCP))),
        )
        assertTrue(
            TcpPing.isTcpReachable(entity(hysteria(1, HysteriaBean.PROTOCOL_WECHAT_VIDEO))),
        )
    }

    @Test
    fun tcpTransportsStayReachable() {
        assertTrue(TcpPing.isTcpReachable(entity(TrojanBean())))
    }
}
