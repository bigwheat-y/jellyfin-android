package org.jellyfin.mobile.tailscale

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 只有引擎在线且系统隧道建立成功，才允许客户端加载已保存的服务器。 */
object TailscaleState {
    data class Status(
        val backend: String = "Stopped",
        val tunnelActive: Boolean = false,
        val authUrl: String = "",
        val error: String = "",
    ) {
        val connected: Boolean get() = backend == "Running" && tunnelActive && error.isEmpty()
    }

    private val mutableStatus = MutableStateFlow(Status())
    val status = mutableStatus.asStateFlow()

    @Synchronized
    fun update(transform: (Status) -> Status) {
        mutableStatus.value = transform(mutableStatus.value)
    }
}
