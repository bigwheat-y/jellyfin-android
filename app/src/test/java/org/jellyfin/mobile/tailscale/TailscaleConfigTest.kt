package org.jellyfin.mobile.tailscale

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 防止错误输入扩大 VPN 路由范围，或把域名交给系统 DNS 解析。 */
class TailscaleConfigTest {
    @Test
    fun `control server must be an explicit HTTPS origin`() {
        listOf("https://vnet.yangxian.cloud/", "https://controlplane.tailscale.com", "https://control.example:8443/").forEach {
            assertTrue(TailscaleConfig.isValidControlUrl(it), it)
        }
        listOf(
            "http://control.example/", "https://user:password@control.example/", "https://control.example/path",
            "https://control.example/?key=secret", "https://control.example/#fragment", "https:///", "control.example",
            "https://control.example:0/", "https://control.example:65536/",
        ).forEach { assertFalse(TailscaleConfig.isValidControlUrl(it), it) }
    }

    @Test
    fun `accept only canonical tailnet IPv4 hosts`() {
        listOf("100.64.0.1", "100.101.102.103", "100.127.255.254").forEach {
            assertTrue(TailscaleConfig.isValidServerIp(it), it)
        }
    }

    @Test
    fun `reject routes names URLs and reserved DNS endpoint`() {
        listOf(
            "", "0.0.0.0", "0.0.0.0/0", "100.64.0.0/10", "100.101.102.103/32",
            "100.100.100.100", "100.63.255.255", "100.128.0.0", "192.168.1.10",
            "server.tailnet.ts.net", "http://100.101.102.103:8096", "100.101.102.103:8096",
            "::1", "fd7a:115c:a1e0::1", "100.064.0.1", "100.64.0.256", "100.64.0.-1",
            "100.64.0.1 ", " 100.64.0.1", "100.64.0.1\n", "100.64.0.1.2", "100.64.0",
        ).forEach { assertFalse(TailscaleConfig.isValidServerIp(it), it) }
    }
}
