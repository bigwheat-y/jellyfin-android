package org.jellyfin.mobile.tailscale

import android.content.Context
import androidx.core.content.edit
import java.net.URI

/** 保存非敏感连接配置；启动服务前同步落盘，设备私钥由独立的加密存储保存。 */
class TailscaleConfig(context: Context) {
    private val preferences = context.getSharedPreferences("tailscale_config", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = preferences.getBoolean("enabled", false)
        set(value) { preferences.edit(commit = true) { putBoolean("enabled", value) } }

    var serverIp: String
        get() = preferences.getString("server_ip", DEFAULT_SERVER_IP).orEmpty()
        set(value) { preferences.edit(commit = true) { putString("server_ip", value) } }

    var controlUrl: String
        get() = preferences.getString("control_url", DEFAULT_CONTROL_URL).orEmpty()
        set(value) { preferences.edit(commit = true) { putString("control_url", value) } }

    companion object {
        const val DEFAULT_CONTROL_URL = "https://vnet.yangxian.cloud/"
        const val DEFAULT_SERVER_IP = "100.64.0.4"

        /** 控制服务器限定为 HTTPS 根地址，禁止夹带账号、查询参数或片段。 */
        fun isValidControlUrl(value: String): Boolean = runCatching {
            val uri = URI(value)
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null &&
                (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") &&
                (uri.port == -1 || uri.port in 1..65535)
        }.getOrDefault(false)

        /** 仅接收 Tailscale IPv4 单机地址，禁止域名、网段和默认路由。 */
        fun isValidServerIp(value: String): Boolean {
            val parts = value.split('.')
            if (parts.size != 4) return false
            val octets = parts.map { part ->
                if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return false
                val number = part.toIntOrNull() ?: return false
                if (number !in 0..255 || number.toString() != part) return false
                number
            }
            return octets[0] == 100 && octets[1] in 64..127 && value != "100.100.100.100"
        }
    }
}
