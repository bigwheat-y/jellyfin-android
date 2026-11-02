package org.jellyfin.mobile.tailscale

import android.content.Context
import android.system.Os
import libtailscale.InputStream
import libtailscale.Libtailscale
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 每个应用进程仅启动一次官方引擎，LocalAPI 只通过 JNI 调用，不开放监听端口。 */
internal class TailscaleEngine private constructor(context: Context) {
    private val directory = File(context.noBackupFilesDir, "tailscale").apply { mkdirs() }
    init {
        // 必须在加载 Go 动态库前设置，官方功能开关在原生包初始化时读取。
        Os.setenv("TS_DISABLE_FEATURE", "ssh,taildrop,taildrive,serve", true)
        Os.setenv("TS_DISABLE_TAILDROP", "true", true)
        Os.setenv("TS_DISABLE_SSH_SERVER", "true", true)
    }

    private val application = Libtailscale.start(
        directory.absolutePath,
        directory.absolutePath,
        false,
        TailscalePlatform(context.applicationContext),
    )

    fun connect(controlUrl: String) {
        require(TailscaleConfig.isValidControlUrl(controlUrl)) { "Invalid control server URL" }
        // 即使曾保存过其他配置，也不接受 DNS、子网路由、出口节点和入站服务。
        val preferences = request(
            "PATCH",
            "prefs",
            JSONObject().put("ControlURL", controlUrl).put("ControlURLSet", true)
                .put("CorpDNS", false).put("CorpDNSSet", true)
                .put("RouteAll", false).put("RouteAllSet", true)
                .put("ExitNodeID", "").put("ExitNodeIDSet", true)
                .put("ExitNodeIP", "").put("ExitNodeIPSet", true)
                .put("ShieldsUp", true).put("ShieldsUpSet", true)
                .put("AdvertiseRoutes", JSONArray()).put("AdvertiseRoutesSet", true),
        )
        // 通过 start 一次性恢复配置，避免先修改 WantRunning 导致登录请求被重置。
        preferences.put("WantRunning", true)
        request("POST", "start", JSONObject().put("UpdatePrefs", preferences))
    }

    fun login() { request("POST", "login-interactive") }

    fun status(): JSONObject = request("GET", "status")

    fun stop() {
        request("PATCH", "prefs", JSONObject().put("WantRunning", false).put("WantRunningSet", true))
    }

    fun logout() { request("POST", "logout") }

    private fun request(method: String, endpoint: String, body: JSONObject? = null): JSONObject {
        val stream = body?.toString()?.toByteArray(Charsets.UTF_8)?.let { bytes ->
            object : InputStream {
                private var consumed = false
                override fun read(): ByteArray? = if (consumed) null else bytes.also { consumed = true }
                override fun close() = Unit
            }
        }
        val response = application.callLocalAPI(15_000, method, "/localapi/v0/$endpoint", stream)
        val data = response.bodyBytes().toString(Charsets.UTF_8)
        // 错误响应可能包含授权信息，不把原始正文写入日志或界面。
        check(response.statusCode() in 200..299) { "Tailscale API $endpoint: HTTP ${response.statusCode()}" }
        return if (data.trim().startsWith("{")) JSONObject(data) else JSONObject()
    }

    companion object {
        @Volatile private var instance: TailscaleEngine? = null

        fun get(context: Context): TailscaleEngine = instance ?: synchronized(this) {
            instance ?: TailscaleEngine(context.applicationContext).also { instance = it }
        }
    }
}
