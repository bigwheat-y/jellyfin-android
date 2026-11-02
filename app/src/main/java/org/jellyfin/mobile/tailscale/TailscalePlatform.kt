package org.jellyfin.mobile.tailscale

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import libtailscale.AppContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface
import java.util.Collections

/** 为官方 Go 引擎提供 Android 网络及加密存储接口，不修改系统代理和 DNS。 */
internal class TailscalePlatform(private val context: Context) : AppContext {
    private val store = TailscaleStore(context)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override fun log(tag: String, logLine: String) = Unit
    override fun encryptToPref(key: String, value: String) = store.write(key, value)
    override fun decryptFromPref(key: String): String = store.read(key)
    override fun getStateStoreKeysJSON(): String = store.stateKeys()
    override fun getOSVersion(): String = Build.VERSION.RELEASE
    override fun getSDKInt(): Long = Build.VERSION.SDK_INT.toLong()
    override fun getDeviceName(): String = "Jellyfin-${Build.MODEL}"
    override fun getInstallSource(): String = context.packageName
    override fun shouldUseGoogleDNSFallback(): Boolean = false
    override fun isChromeOS(): Boolean = false
    override fun isClientLoggingEnabled(): Boolean = false

    override fun getInterfacesAsJson(): String {
        val result = JSONArray()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return result.toString()
        for (network in Collections.list(interfaces)) {
            runCatching {
                val addresses = JSONArray()
                network.interfaceAddresses.forEach { address ->
                    address.address?.hostAddress?.let { ip ->
                        addresses.put(JSONObject().put("ip", ip).put("prefixLen", address.networkPrefixLength.toInt()))
                    }
                }
                result.put(
                    JSONObject().put("name", network.name).put("index", network.index).put("mtu", network.mtu)
                        .put("up", network.isUp).put("broadcast", network.supportsMulticast())
                        .put("loopback", network.isLoopback).put("pointToPoint", network.isPointToPoint)
                        .put("multicast", network.supportsMulticast()).put("addrs", addresses),
                )
            }
        }
        return result.toString()
    }

    // API 23 也需要在默认网络为 VPN 时找到底层网络，因此保留接口枚举回退。
    @Suppress("DEPRECATION")
    internal fun underlyingNetwork() = connectivity.activeNetwork?.takeIf { network ->
        connectivity.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true
    } ?: connectivity.allNetworks.firstOrNull { network ->
        val capabilities = connectivity.getNetworkCapabilities(network)
        capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun bindSocketToNetwork(fd: Int): Boolean = runCatching {
        val network = underlyingNetwork() ?: return false
        // 复制描述符后绑定，避免关闭 Go 引擎拥有的原始套接字。
        ParcelFileDescriptor.fromFd(fd).use { network.bindSocket(it.fileDescriptor) }
        true
    }.getOrDefault(false)

    override fun getPlatformDNSConfig(): String {
        val network = underlyingNetwork() ?: return ""
        val properties = connectivity.getLinkProperties(network) ?: return ""
        return properties.dnsServers.mapNotNull { it.hostAddress }.joinToString(" ")
    }

    // 本客户端不读取 MDM 策略；必须返回“未配置”，不能将缺省值误报为显式策略。
    override fun getSyspolicyStringValue(key: String): String = throw Exception("no such key")
    override fun getSyspolicyBooleanValue(key: String): Boolean = throw Exception("no such key")
    override fun getSyspolicyStringArrayJSONValue(key: String): String = throw Exception("no such key")

    // 设备身份由 Keystore 加密保存；第一版不启用可选的硬件证明功能。
    override fun hardwareAttestationKeySupported(): Boolean = false
    override fun hardwareAttestationKeyCreate(): String = error("Hardware attestation is disabled")
    override fun hardwareAttestationKeyRelease(id: String) = Unit
    override fun hardwareAttestationKeyPublic(id: String): ByteArray = error("Hardware attestation is disabled")
    override fun hardwareAttestationKeySign(id: String, data: ByteArray): ByteArray = error("Hardware attestation is disabled")
    override fun hardwareAttestationKeyLoad(id: String) = error("Hardware attestation is disabled")
    override fun getUserCACertsPEM(): ByteArray = byteArrayOf()
}
