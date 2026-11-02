package org.jellyfin.mobile.tailscale

import android.net.VpnService
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** 直接检查交给 Android 的配置，防止后续引擎配置悄悄扩大接管范围。 */
class TailscaleVpnBuilderTest {
    @Test
    fun `engine routes and DNS cannot widen the selected app and host policy`() {
        val androidBuilder = mockk<VpnService.Builder>(relaxed = true)
        val adapter = TailscaleVpnBuilder(androidBuilder, "org.jellyfin.mobile.debug", "100.101.102.103") { false }
        adapter.addRoute("0.0.0.0", 0)
        adapter.addRoute("::", 0)
        adapter.addRoute("100.64.0.0", 10)
        adapter.addDNSServer("100.100.100.100")
        adapter.addSearchDomain("tailnet.ts.net")
        adapter.excludeRoute("192.168.0.0", 16)

        verify(exactly = 1) { androidBuilder.addAllowedApplication("org.jellyfin.mobile.debug") }
        verify(exactly = 1) { androidBuilder.addRoute(any<String>(), any()) }
        verify(exactly = 1) { androidBuilder.addRoute("100.101.102.103", 32) }
        verify(exactly = 0) { androidBuilder.addDisallowedApplication(any()) }
        verify(exactly = 0) { androidBuilder.addDnsServer(any<String>()) }
        verify(exactly = 0) { androidBuilder.addSearchDomain(any()) }
    }

    @Test
    fun `closed service cannot establish another tunnel`() {
        val androidBuilder = mockk<VpnService.Builder>(relaxed = true)
        val adapter = TailscaleVpnBuilder(androidBuilder, "org.jellyfin.mobile", "100.101.102.103") { true }
        assertThrows(IllegalStateException::class.java) { adapter.establish() }
        verify(exactly = 0) { androidBuilder.establish() }
    }

    @Test
    fun `invalid destination does not mutate Android VPN configuration`() {
        val androidBuilder = mockk<VpnService.Builder>(relaxed = true)
        assertThrows(IllegalArgumentException::class.java) {
            TailscaleVpnBuilder(androidBuilder, "org.jellyfin.mobile", "0.0.0.0/0") { false }
        }
        verify(exactly = 0) { androidBuilder.addRoute(any<String>(), any()) }
        verify(exactly = 0) { androidBuilder.addAllowedApplication(any()) }
    }
}
