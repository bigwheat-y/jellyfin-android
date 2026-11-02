package org.jellyfin.mobile.tailscale

import android.net.VpnService
import android.system.OsConstants
import libtailscale.ParcelFileDescriptor
import libtailscale.VPNServiceBuilder

/** 路由只由客户端指定，禁止引擎更新时扩大到默认路由或其他应用。 */
internal class TailscaleVpnBuilder(
    private val builder: VpnService.Builder,
    packageName: String,
    serverIp: String,
    private val isClosed: () -> Boolean,
) : VPNServiceBuilder {
    init {
        require(TailscaleConfig.isValidServerIp(serverIp)) { "Invalid Tailscale server IP" }
        builder.addAllowedApplication(packageName)
        builder.addRoute(serverIp, 32)
        // 显式允许未匹配的 IPv4/IPv6 流量绕过，避免另一地址族被 VPN 阻断。
        builder.allowFamily(OsConstants.AF_INET)
        builder.allowFamily(OsConstants.AF_INET6)
    }

    override fun setMTU(value: Int) { builder.setMtu(value) }
    override fun addAddress(address: String, prefix: Int) { builder.addAddress(address, prefix) }
    override fun addRoute(address: String, prefix: Int) = Unit
    override fun excludeRoute(address: String, prefix: Int) = Unit
    override fun addDNSServer(address: String) = Unit
    override fun addSearchDomain(domain: String) = Unit

    override fun establish(): ParcelFileDescriptor {
        check(!isClosed()) { "VPN service has stopped" }
        val descriptor = checkNotNull(builder.establish()) { "VPN permission revoked" }
        return object : ParcelFileDescriptor {
            override fun detach(): Int = descriptor.detachFd()
        }
    }
}
