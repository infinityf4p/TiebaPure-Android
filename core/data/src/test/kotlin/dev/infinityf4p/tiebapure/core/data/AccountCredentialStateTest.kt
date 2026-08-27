package dev.infinityf4p.tiebapure.core.data

import dev.infinityf4p.tiebapure.core.model.Account
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AccountCredentialStateTest {
    @Test
    fun supportsAddingSwitchingAndRemovingTwoAccounts() {
        val first = account("1")
        val second = account("2")

        val withTwo = AccountCredentialState().addOrReplace(first).addOrReplace(second)
        assertEquals(listOf(second, first), withTwo.accounts)
        assertEquals(second, withTwo.activeAccount)

        val switched = withTwo.switchTo(first.id)
        assertEquals(listOf(first, second), switched.accounts)
        assertEquals(first, switched.activeAccount)

        val removed = switched.remove(first.id)
        assertEquals(listOf(second), removed.accounts)
        assertEquals(second, removed.activeAccount)
    }

    @Test
    fun replacingSavedAccountDoesNotUseAnotherSlot() {
        val first = account("1")
        val refreshed = first.copy(displayName = "Refreshed", bduss = "new-bduss")

        val state = AccountCredentialState().addOrReplace(first).addOrReplace(refreshed)

        assertEquals(listOf(refreshed), state.accounts)
        assertEquals(refreshed, state.activeAccount)
    }

    @Test
    fun rejectsThirdDistinctAccount() {
        val state = AccountCredentialState().addOrReplace(account("1")).addOrReplace(account("2"))

        val error = assertThrows(IllegalStateException::class.java) {
            state.addOrReplace(account("3"))
        }

        assertEquals("最多只能保存 2 个账号，请先移除一个账号。", error.message)
    }

    @Test
    fun currentFormatRoundTripsTwoAccounts() {
        val original = AccountCredentialState().addOrReplace(account("1")).addOrReplace(account("2"))

        val decoded = AccountCredentialCodec.decode(AccountCredentialCodec.encode(original))

        assertEquals(original, decoded)
    }

    @Test
    fun legacySingleAccountPayloadMigratesToActiveAccount() {
        val legacy = account("1")

        val decoded = AccountCredentialCodec.decode(legacyPayload(legacy))

        assertEquals(AccountCredentialState(listOf(legacy), legacy.id), decoded)
    }

    private fun legacyPayload(account: Account): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(1)
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

    private fun account(uid: String) = Account(
        uid = uid,
        name = "name-$uid",
        displayName = "display-$uid",
        portrait = "portrait-$uid",
        bduss = "bduss-$uid",
        stoken = "stoken-$uid",
        baiduId = "baiduid-$uid",
        tbs = "tbs-$uid",
    )
}
