package dev.infinityf4p.tiebapure.core.network

import dev.infinityf4p.tiebapure.core.model.Account
import dev.infinityf4p.tiebapure.core.model.AccountThreadFavoritesPage
import dev.infinityf4p.tiebapure.core.model.BaiduWebCredentials
import dev.infinityf4p.tiebapure.core.model.Forum
import dev.infinityf4p.tiebapure.core.model.ForumMembership
import dev.infinityf4p.tiebapure.core.model.MessageKind
import dev.infinityf4p.tiebapure.core.model.MessagePage
import dev.infinityf4p.tiebapure.core.model.UserRelationshipKind
import dev.infinityf4p.tiebapure.core.model.UserRelationshipPage
import java.util.concurrent.CancellationException

interface TiebaAuthenticationService {
    suspend fun validateLogin(credentials: BaiduWebCredentials): Account
}

interface TiebaAccountReadService {
    suspend fun followedForums(account: Account): List<Forum>
    suspend fun relationships(
        account: Account?,
        userId: Long,
        kind: UserRelationshipKind,
        page: Int,
    ): UserRelationshipPage
    suspend fun messages(account: Account, kind: MessageKind, page: Int): MessagePage
    suspend fun threadFavorites(account: Account, page: Int): AccountThreadFavoritesPage
    suspend fun resolveForumId(forumName: String): Long
    suspend fun forumMembership(account: Account, forumId: Long): ForumMembership
}

interface TiebaSessionService {
    suspend fun refreshedClientTbs(account: Account, allowsStoredFallback: Boolean = true): String
    suspend fun strictlyRefreshedWebTbs(account: Account): String
}

class DefaultTiebaAccountService(
    private val transport: TiebaTransport,
    private val requestBuilder: TiebaRequestBuilder,
    private val clock: EpochMillisecondsClock = EpochMillisecondsClock.System,
) : TiebaAuthenticationService, TiebaAccountReadService, TiebaSessionService {
    override suspend fun validateLogin(credentials: BaiduWebCredentials): Account {
        validateCredentials(credentials)
        val login = runCatchingPreservingCancellation {
            TiebaAccountJsonMapper.clientLogin(transport.text(TiebaAuthRequestFactory.login(
                credentials.bduss, credentials.stoken, credentials.baiduId, requestBuilder, clock.now(),
            )))
        }
        val nickname = runCatchingPreservingCancellation {
            TiebaAccountJsonMapper.nickname(transport.text(TiebaAuthRequestFactory.initNickname(
                credentials.bduss, credentials.stoken, credentials.baiduId, requestBuilder, clock.now(),
            )))
        }
        login.getOrNull()?.let { client ->
            if (client.uid.isNotBlank() && client.tbs.isNotBlank()) {
                val displayName = nickname.getOrNull().orEmpty().ifBlank { client.name }.ifBlank { client.uid }
                return Account(
                    uid = client.uid,
                    name = client.name.ifBlank { displayName },
                    displayName = displayName,
                    portrait = client.portrait,
                    bduss = credentials.bduss,
                    stoken = credentials.stoken,
                    baiduId = credentials.baiduId,
                    tbs = client.tbs.trim(),
                )
            }
        }

        val provisional = Account(
            uid = "", name = "", displayName = "", portrait = "",
            bduss = credentials.bduss, stoken = credentials.stoken,
            baiduId = credentials.baiduId, tbs = "",
        )
        val web = TiebaAccountJsonMapper.webIdentity(
            transport.text(TiebaAuthRequestFactory.webMyInfo(provisional, requestBuilder)),
        )
        val tbs = login.getOrNull()?.tbs?.trim().orEmpty().ifBlank { web.tbs.trim() }
        if (web.isLogin == false || web.uid.isBlank() || tbs.isBlank()) {
            throw TiebaAuthenticationException.MissingAccountInfo
        }
        val name = web.name.ifBlank { web.displayName }.ifBlank { web.uid }
        return Account(
            web.uid, name, web.displayName.ifBlank { name }, web.portrait,
            credentials.bduss, credentials.stoken, credentials.baiduId, tbs,
        )
    }

    override suspend fun followedForums(account: Account): List<Forum> = TiebaAccountJsonMapper.followedForums(
        transport.text(TiebaReadRequestFactory.followedForums(account, requestBuilder)),
    )

    override suspend fun relationships(
        account: Account?, userId: Long, kind: UserRelationshipKind, page: Int,
    ): UserRelationshipPage = TiebaAccountJsonMapper.relationships(
        transport.text(TiebaReadRequestFactory.relationships(account, userId, page, kind, requestBuilder)),
        kind,
        page,
    )

    override suspend fun messages(account: Account, kind: MessageKind, page: Int): MessagePage {
        require(kind != MessageKind.Agree) { "贴吧当前只提供回复和提及消息列表" }
        return TiebaAccountJsonMapper.messages(
            transport.text(TiebaReadRequestFactory.messages(account, page, kind == MessageKind.Mention, requestBuilder)),
            kind,
            page,
        )
    }

    override suspend fun threadFavorites(account: Account, page: Int): AccountThreadFavoritesPage =
        TiebaAccountJsonMapper.favorites(
            transport.text(TiebaReadRequestFactory.threadStore(account, page, requestBuilder)),
            page,
        )

    override suspend fun resolveForumId(forumName: String): Long = TiebaAccountJsonMapper.forumId(
        transport.text(TiebaReadRequestFactory.resolveForumId(forumName, requestBuilder)),
    )

    override suspend fun forumMembership(account: Account, forumId: Long): ForumMembership =
        TiebaAccountJsonMapper.membership(
            transport.text(TiebaReadRequestFactory.forumMembership(account, forumId, requestBuilder)),
            forumId,
        )

    override suspend fun refreshedClientTbs(account: Account, allowsStoredFallback: Boolean): String {
        var clientFailure: Throwable? = null
        try {
            val response = TiebaAccountJsonMapper.clientLogin(transport.text(TiebaAuthRequestFactory.login(
                account.bduss, account.stoken, account.baiduId, requestBuilder, clock.now(),
            )))
            val code = response.code ?: 0
            if (code == 0 && response.tbs.isNotBlank()) return response.tbs.trim()
            TiebaResponseValidator.validate(code, response.message)
            clientFailure = TiebaMutationException.MissingTbs
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            clientFailure = error
        }

        var webFailure: Throwable? = null
        try {
            val web = TiebaAccountJsonMapper.webIdentity(
                transport.text(TiebaAuthRequestFactory.webMyInfo(account, requestBuilder)),
            )
            if (web.isLogin == false) throw TiebaApiException.SessionExpired(4, "网页登录状态已失效")
            if (web.tbs.isNotBlank()) return web.tbs.trim()
        } catch (error: CancellationException) {
            throw error
        } catch (error: TiebaApiException.SessionExpired) {
            throw error
        } catch (error: Throwable) {
            webFailure = error
        }

        if (allowsStoredFallback && account.tbs.isNotBlank()) return account.tbs.trim()
        throw clientFailure ?: webFailure ?: TiebaMutationException.MissingTbs
    }

    override suspend fun strictlyRefreshedWebTbs(account: Account): String = TiebaAccountJsonMapper.webTbs(
        transport.text(TiebaAuthRequestFactory.webTbs(account, requestBuilder, clock.now())),
    )

    private fun validateCredentials(credentials: BaiduWebCredentials) {
        if (credentials.bduss.isBlank() || credentials.stoken.isBlank()) {
            throw TiebaAuthenticationException.InvalidCredentials
        }
        try {
            TiebaHeaderPolicy.requireSafeCookieValue("BDUSS", credentials.bduss)
            TiebaHeaderPolicy.requireSafeCookieValue("STOKEN", credentials.stoken)
            credentials.baiduId?.takeIf(String::isNotBlank)?.let {
                TiebaHeaderPolicy.requireSafeCookieValue("BAIDUID", it)
            }
        } catch (_: TiebaNetworkException.InvalidRequest) {
            throw TiebaAuthenticationException.InvalidCredentials
        }
    }

    private suspend fun <T> runCatchingPreservingCancellation(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
}
