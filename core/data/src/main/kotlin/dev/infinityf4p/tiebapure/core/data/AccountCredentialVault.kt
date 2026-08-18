package dev.infinityf4p.tiebapure.core.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.infinityf4p.tiebapure.core.model.Account
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AccountCredentialVault(context: Context) {
    private val preferences = context.getSharedPreferences("encrypted_account", Context.MODE_PRIVATE)

    @Synchronized
    fun load(): Account? {
        val payload = preferences.getString(PAYLOAD_KEY, null)?.decodeHex() ?: return null
        if (payload.size <= IV_BYTES) return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, payload, 0, IV_BYTES))
            decode(cipher.doFinal(payload, IV_BYTES, payload.size - IV_BYTES))
        }.getOrNull()
    }

    @Synchronized
    fun save(account: Account) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(encode(account))
        val payload = cipher.iv + encrypted
        check(preferences.edit().putString(PAYLOAD_KEY, payload.toHex()).commit())
    }

    @Synchronized
    fun clear() {
        check(preferences.edit().remove(PAYLOAD_KEY).commit())
        val store = keyStore()
        if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
    }

    private fun key(): SecretKey {
        val keyStore = keyStore()
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun encode(account: Account): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(FORMAT_VERSION)
            listOf(
                account.uid,
                account.name,
                account.displayName,
                account.portrait,
                account.bduss,
                account.stoken,
                account.baiduId.orEmpty(),
                account.tbs,
            ).forEach(output::writeUTF)
        }
        bytes.toByteArray()
    }

    private fun decode(payload: ByteArray): Account = DataInputStream(ByteArrayInputStream(payload)).use { input ->
        require(input.readInt() == FORMAT_VERSION)
        val values = List(8) { input.readUTF().also { require(it.length <= MAX_FIELD_LENGTH) } }
        require(input.available() == 0)
        Account(
            uid = values[0],
            name = values[1],
            displayName = values[2],
            portrait = values[3],
            bduss = values[4],
            stoken = values[5],
            baiduId = values[6].ifBlank { null },
            tbs = values[7],
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun String.decodeHex(): ByteArray? {
        if (length % 2 != 0 || length > MAX_HEX_LENGTH) return null
        return runCatching { ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull()
    }

    private companion object {
        const val KEY_ALIAS = "dev.infinityf4p.tiebapure.account.v1"
        const val PAYLOAD_KEY = "payload"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val FORMAT_VERSION = 1
        const val MAX_FIELD_LENGTH = 4_096
        const val MAX_HEX_LENGTH = 80_000
    }
}
