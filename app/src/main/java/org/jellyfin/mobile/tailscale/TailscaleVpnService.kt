package org.jellyfin.mobile.tailscale

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libtailscale.IPNService
import libtailscale.Libtailscale
import libtailscale.VPNServiceBuilder
import org.jellyfin.mobile.R
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** 只承载本应用到指定 Tailscale 主机的流量，系统中其他应用不加入隧道。 */
class TailscaleVpnService : VpnService(), IPNService {
    private val serviceId = UUID.randomUUID().toString()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private var started = false
    @Volatile private var attached = false
    private var callbackRegistered = false
    private lateinit var target: String
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = networkChanged()
        override fun onLost(network: Network) = networkChanged()
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = networkChanged()
    }

    override fun id(): String = serviceId

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || intent?.action == ACTION_LOGOUT) {
            val logout = intent.action == ACTION_LOGOUT
            TailscaleConfig(this).enabled = false
            scope.launch {
                TailscaleState.update { TailscaleState.Status(backend = "Stopping") }
                try {
                    nativeLifecycle.withLock {
                        TailscaleEngine.get(this@TailscaleVpnService).stop()
                        if (logout) TailscaleEngine.get(this@TailscaleVpnService).logout()
                    }
                } catch (_: Exception) {
                    TailscaleState.update { it.copy(error = getString(R.string.tailscale_error_engine)) }
                } finally {
                    close()
                }
            }
            return START_NOT_STICKY
        }
        val config = TailscaleConfig(this)
        if (!config.enabled || !TailscaleConfig.isValidServerIp(config.serverIp) ||
            !TailscaleConfig.isValidControlUrl(config.controlUrl) || prepare(this) != null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_STICKY
        started = true
        target = config.serverIp
        showNotification()
        TailscaleState.update { TailscaleState.Status(backend = "Starting") }
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),
            networkCallback,
        )
        callbackRegistered = true
        scope.launch {
            try {
                val engine = TailscaleEngine.get(this@TailscaleVpnService)
                nativeLifecycle.withLock {
                    if (closed.get() || !config.enabled) return@launch
                    Libtailscale.requestVPN(this@TailscaleVpnService)
                    attached = true
                    engine.connect(config.controlUrl)
                }
                var loginRequested = false
                while (isActive && !closed.get() && config.enabled) {
                    val status = engine.status()
                    if (status.optString("BackendState") == "NeedsLogin" && !loginRequested) {
                        engine.login()
                        loginRequested = true
                    }
                    if (closed.get() || !config.enabled) break
                    TailscaleState.update {
                        it.copy(backend = status.optString("BackendState"), authUrl = status.optString("AuthURL"), error = "")
                    }
                    delay(2_000)
                }
            } catch (_: Exception) {
                if (!closed.get()) {
                    TailscaleState.update { it.copy(error = getString(R.string.tailscale_error_engine), tunnelActive = false) }
                    close()
                }
            }
        }
        return START_STICKY
    }

    private fun networkChanged() {
        if (!attached || closed.get()) return
        scope.launch {
            // 网络切换后让官方引擎重新读取接口、DNS 并触发连接重绑定。
            Libtailscale.onDNSConfigChanged("")
        }
    }

    override fun updateVpnStatus(status: Boolean) {
        TailscaleState.update { it.copy(tunnelActive = status) }
    }

    override fun newBuilder(): VPNServiceBuilder {
        check(!closed.get()) { "VPN service has stopped" }
        val builder = Builder()
            .setSession("Jellyfin Tailscale")
            .setConfigureIntent(configurationIntent())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        return TailscaleVpnBuilder(builder, packageName, target) { closed.get() }
    }

    private fun configurationIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, TailscaleActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun showNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.tailscale_title), NotificationManager.IMPORTANCE_LOW))
        }
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, TailscaleVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.tailscale_title))
            .setContentText(getString(R.string.tailscale_notification, target))
            .setContentIntent(configurationIntent())
            .setOngoing(true)
            .addAction(0, getString(R.string.tailscale_disconnect), stopIntent)
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else 0,
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        TailscaleState.update { it.copy(backend = "Stopping", tunnelActive = false, authUrl = "") }
        // 独立清理作用域不受 Service.onDestroy 取消，确保先关闭旧 TUN，再允许新连接。
        CoroutineScope(Dispatchers.IO).launch {
            nativeLifecycle.withLock {
                if (attached) Libtailscale.serviceDisconnect(this@TailscaleVpnService)
            }
            TailscaleState.update { it.copy(backend = "Stopped", tunnelActive = false, authUrl = "") }
            stopSelf()
        }
    }

    override fun disconnectVPN() = close()

    override fun onRevoke() {
        TailscaleConfig(this).enabled = false
        CoroutineScope(Dispatchers.IO).launch {
            nativeLifecycle.withLock { runCatching { TailscaleEngine.get(this@TailscaleVpnService).stop() } }
        }
        close()
        super.onRevoke()
    }

    override fun onDestroy() {
        close()
        if (callbackRegistered) connectivity.unregisterNetworkCallback(networkCallback)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private val nativeLifecycle = Mutex()
        private const val CHANNEL = "tailscale_vpn"
        private const val NOTIFICATION_ID = 7100
        private const val ACTION_STOP = "org.jellyfin.mobile.tailscale.STOP"
        private const val ACTION_LOGOUT = "org.jellyfin.mobile.tailscale.LOGOUT"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TailscaleVpnService::class.java))
        }

        fun stop(context: Context, logout: Boolean = false) {
            context.startService(Intent(context, TailscaleVpnService::class.java).setAction(if (logout) ACTION_LOGOUT else ACTION_STOP))
        }
    }
}
