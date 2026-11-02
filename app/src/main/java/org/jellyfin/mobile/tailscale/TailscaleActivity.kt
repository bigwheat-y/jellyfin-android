package org.jellyfin.mobile.tailscale

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.jellyfin.mobile.R

/** 在客户端内完成服务器配置和 VPN 授权；账号登录使用系统浏览器。 */
class TailscaleActivity : AppCompatActivity() {
    private lateinit var config: TailscaleConfig
    private lateinit var controlInput: EditText
    private lateinit var serverInput: EditText
    private lateinit var statusText: TextView
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var loginButton: Button
    private var pendingServerIp: String? = null
    private var pendingControlUrl: String? = null

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) startConnection()
        else statusText.setText(R.string.tailscale_permission_denied)
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        config = TailscaleConfig(this)
        pendingServerIp = savedInstanceState?.getString("pending_server_ip")
        pendingControlUrl = savedInstanceState?.getString("pending_control_url")
        val padding = (20 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        val scroll = ScrollView(this).apply { addView(content) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        fun text(resource: Int, size: Float = 16f) = TextView(this).apply {
            setText(resource)
            textSize = size
            setPadding(0, padding / 2, 0, padding / 2)
            content.addView(this)
        }
        fun button(resource: Int, action: () -> Unit) = Button(this).apply {
            setText(resource)
            setOnClickListener { action() }
            content.addView(this)
        }
        text(R.string.tailscale_title, 24f)
        text(R.string.tailscale_description)
        text(R.string.tailscale_control_url)
        controlInput = EditText(this).apply {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(savedInstanceState?.getString("control_url_input") ?: pendingControlUrl ?: config.controlUrl)
            contentDescription = getString(R.string.tailscale_control_url)
            content.addView(this)
        }
        text(R.string.tailscale_server_ip)
        serverInput = EditText(this).apply {
            id = View.generateViewId()
            setSingleLine()
            inputType = InputType.TYPE_CLASS_PHONE
            hint = "100.x.x.x"
            setText(savedInstanceState?.getString("server_ip_input") ?: pendingServerIp ?: config.serverIp)
            contentDescription = getString(R.string.tailscale_server_ip)
            content.addView(this)
        }
        statusText = text(R.string.tailscale_stopped)
        connectButton = button(R.string.tailscale_connect) {
            val ip = serverInput.text.toString().trim()
            val controlUrl = controlInput.text.toString().trim()
            if (!TailscaleConfig.isValidControlUrl(controlUrl)) {
                controlInput.error = getString(R.string.tailscale_invalid_control_url)
            } else if (!TailscaleConfig.isValidServerIp(ip)) {
                serverInput.error = getString(R.string.tailscale_invalid_ip)
            } else {
                pendingServerIp = ip
                pendingControlUrl = controlUrl
                AlertDialog.Builder(this)
                    .setTitle(R.string.tailscale_title)
                    .setMessage(R.string.tailscale_vpn_notice)
                    .setPositiveButton(R.string.tailscale_connect) { _, _ ->
                        val permission = VpnService.prepare(this)
                        if (permission != null) vpnPermission.launch(permission) else startConnection()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        loginButton = button(R.string.tailscale_login) {
            val url = TailscaleState.status.value.authUrl
            val uri = url.toUri()
            val host = uri.host.orEmpty()
            val control = config.controlUrl.toUri()
            val controlPort = if (control.port == -1) 443 else control.port
            val authPort = if (uri.port == -1) 443 else uri.port
            val sameControlOrigin = host.equals(control.host, ignoreCase = true) && authPort == controlPort
            if (uri.scheme == "https" && uri.userInfo == null &&
                (sameControlOrigin || host == "tailscale.com" || host.endsWith(".tailscale.com"))
            ) {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    .onFailure { statusText.setText(R.string.tailscale_no_browser) }
            }
        }
        disconnectButton = button(R.string.tailscale_disconnect) { TailscaleVpnService.stop(this) }
        button(R.string.tailscale_logout) {
            AlertDialog.Builder(this).setMessage(R.string.tailscale_logout_notice)
                .setPositiveButton(R.string.tailscale_logout) { _, _ -> TailscaleVpnService.stop(this, logout = true) }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
        button(R.string.tailscale_back) { finish() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TailscaleState.status.collect { status ->
                    statusText.text = when {
                        status.error.isNotBlank() -> status.error
                        status.connected -> getString(R.string.tailscale_connected, config.serverIp)
                        status.authUrl.isNotBlank() -> getString(R.string.tailscale_needs_login)
                        status.backend == "NeedsMachineAuth" -> getString(R.string.tailscale_needs_approval)
                        config.enabled -> getString(R.string.tailscale_connecting)
                        else -> getString(R.string.tailscale_stopped)
                    }
                    controlInput.isEnabled = status.backend == "Stopped"
                    serverInput.isEnabled = status.backend == "Stopped"
                    connectButton.isEnabled = status.backend == "Stopped"
                    disconnectButton.isEnabled = config.enabled || status.backend != "Stopped"
                    loginButton.visibility = if (status.authUrl.isNotBlank()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun startConnection() {
        val ip = pendingServerIp ?: return
        val controlUrl = pendingControlUrl ?: return
        config.controlUrl = controlUrl
        config.serverIp = ip
        config.enabled = true
        TailscaleState.update { TailscaleState.Status(backend = "Starting") }
        runCatching { TailscaleVpnService.start(this) }.onFailure {
            config.enabled = false
            TailscaleState.update { TailscaleState.Status(error = getString(R.string.tailscale_error_engine)) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_control_url", pendingControlUrl)
        outState.putString("control_url_input", controlInput.text.toString())
        outState.putString("pending_server_ip", pendingServerIp)
        outState.putString("server_ip_input", serverInput.text.toString())
        super.onSaveInstanceState(outState)
    }
}
