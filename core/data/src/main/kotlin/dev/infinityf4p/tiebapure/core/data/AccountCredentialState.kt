package dev.infinityf4p.tiebapure.core.data

import dev.infinityf4p.tiebapure.core.model.Account

data class AccountCredentialState(
    val accounts: List<Account> = emptyList(),
    val activeAccountId: String? = null,
) {
    init {
        require(accounts.size <= MAX_ACCOUNT_COUNT) { "最多只能保存 $MAX_ACCOUNT_COUNT 个账号。" }
        require(accounts.map(Account::id).distinct().size == accounts.size) { "账号列表包含重复账号。" }
        require(accounts.all { it.id.isNotBlank() && it.bduss.isNotBlank() && it.stoken.isNotBlank() }) {
            "账号登录凭据不完整。"
        }
        require(
            (accounts.isEmpty() && activeAccountId == null) ||
                (accounts.isNotEmpty() && accounts.any { it.id == activeAccountId }),
        ) { "当前账号不在已保存账号列表中。" }
    }

    val activeAccount: Account?
        get() = accounts.firstOrNull { it.id == activeAccountId }

    fun addOrReplace(account: Account): AccountCredentialState {
        val exists = accounts.any { it.id == account.id }
        check(exists || accounts.size < MAX_ACCOUNT_COUNT) {
            "最多只能保存 $MAX_ACCOUNT_COUNT 个账号，请先移除一个账号。"
        }
        return AccountCredentialState(
            accounts = listOf(account) + accounts.filterNot { it.id == account.id },
            activeAccountId = account.id,
        )
    }

    fun switchTo(accountId: String): AccountCredentialState {
        val target = accounts.firstOrNull { it.id == accountId }
            ?: error("找不到要切换的账号。")
        if (target.id == activeAccountId) return this
        return AccountCredentialState(
            accounts = listOf(target) + accounts.filterNot { it.id == target.id },
            activeAccountId = target.id,
        )
    }

    fun remove(accountId: String): AccountCredentialState {
        check(accounts.any { it.id == accountId }) { "找不到要移除的账号。" }
        val remaining = accounts.filterNot { it.id == accountId }
        val nextActiveId = when {
            remaining.isEmpty() -> null
            activeAccountId == accountId -> remaining.first().id
            else -> activeAccountId
        }
        return AccountCredentialState(remaining, nextActiveId)
    }

    companion object {
        const val MAX_ACCOUNT_COUNT = 2
    }
}
