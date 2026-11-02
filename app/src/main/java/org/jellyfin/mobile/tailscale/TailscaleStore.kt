package org.jellyfin.mobile.tailscale

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 使用 Android Keystore 加密设备身份，密钥不导出，密文不参与系统备份。 */
internal class TailscaleStore(context: Context) {
    private val preferences = context.getSharedPreferences("tailscale_identity", Context.MODE_PRIVATE)
    private val alias = "${context.packageName}.tailscale.identity"
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        ).apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    // 必须检查同步提交结果；KTX edit 不返回提交是否成功，不能替换此处写入。
    @SuppressLint("UseKtx")
    @Synchronized
    fun write(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val record = encode(cipher.iv) + ":" + encode(encrypted)
        check(preferences.edit().putString(name, record).commit()) { "Unable to save Tailscale identity" }
    }

    @Synchronized
    fun read(name: String): String {
        val record = preferences.getString(name, null) ?: return ""
        val parts = record.split(':', limit = 2)
        check(parts.size == 2) { "Invalid encrypted Tailscale identity" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, decode(parts[0])))
        return String(cipher.doFinal(decode(parts[1])), Charsets.UTF_8)
    }

    fun stateKeys(): String = JSONArray(
        preferences.all.keys.filter { it.startsWith("statestore-") }.map { it.removePrefix("statestore-") },
    ).toString()

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)
}
