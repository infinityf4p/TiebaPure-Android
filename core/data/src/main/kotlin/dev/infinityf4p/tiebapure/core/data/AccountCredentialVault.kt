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
    fun load(): AccountCredentialState {
        val payload = preferences.getString(PAYLOAD_KEY, null)?.decodeHex() ?: return AccountCredentialState()
        if (payload.size <= IV_BYTES) return AccountCredentialState()
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, payload, 0, IV_BYTES))
            AccountCredentialCodec.decode(cipher.doFinal(payload, IV_BYTES, payload.size - IV_BYTES))
        }.getOrDefault(AccountCredentialState())
    }

    @Synchronized
    fun save(state: AccountCredentialState) {
        if (state.accounts.isEmpty()) {
            clear()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(AccountCredentialCodec.encode(state))
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
        const val MAX_HEX_LENGTH = 160_000
    }
}

internal object AccountCredentialCodec {
    private const val LEGACY_FORMAT_VERSION = 1
    private const val FORMAT_VERSION = 2
    private const val ACCOUNT_FIELD_COUNT = 8
    private const val MAX_FIELD_LENGTH = 4_096

    fun encode(state: AccountCredentialState): ByteArray = ByteArrayOutputStream().use { bytes ->
        require(state.accounts.isNotEmpty())
        DataOutputStream(bytes).use { output ->
            output.writeInt(FORMAT_VERSION)
            output.writeUTF(state.activeAccountId.orEmpty())
            output.writeInt(state.accounts.size)
            state.accounts.forEach { account -> writeAccount(output, account) }
        }
        bytes.toByteArray()
    }

    fun decode(payload: ByteArray): AccountCredentialState = DataInputStream(ByteArrayInputStream(payload)).use { input ->
        val state = when (input.readInt()) {
            LEGACY_FORMAT_VERSION -> readAccount(input).let { AccountCredentialState(listOf(it), it.id) }
            FORMAT_VERSION -> {
                val activeAccountId = input.readLimitedUtf()
                val accountCount = input.readInt().also {
                    require(it in 1..AccountCredentialState.MAX_ACCOUNT_COUNT)
                }
                AccountCredentialState(
                    accounts = List(accountCount) { readAccount(input) },
                    activeAccountId = activeAccountId,
                )
            }
            else -> error("不支持的账号凭据格式。")
        }
        require(input.available() == 0)
        state
    }

    private fun writeAccount(output: DataOutputStream, account: Account) {
        listOf(
            account.uid,
            account.name,
            account.displayName,
            account.portrait,
            account.bduss,
            account.stoken,
            account.baiduId.orEmpty(),
            account.tbs,
        ).forEach { value ->
            require(value.length <= MAX_FIELD_LENGTH)
            output.writeUTF(value)
        }
    }

    private fun readAccount(input: DataInputStream): Account {
        val values = List(ACCOUNT_FIELD_COUNT) { input.readLimitedUtf() }
        return Account(
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

    private fun DataInputStream.readLimitedUtf(): String = readUTF().also {
        require(it.length <= MAX_FIELD_LENGTH)
    }
}
