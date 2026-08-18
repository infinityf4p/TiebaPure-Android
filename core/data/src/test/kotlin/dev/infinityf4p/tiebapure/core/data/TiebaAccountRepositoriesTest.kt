package dev.infinityf4p.tiebapure.core.data

import dev.infinityf4p.tiebapure.core.model.Account
import dev.infinityf4p.tiebapure.core.model.AccountThreadFavoritesPage
import dev.infinityf4p.tiebapure.core.model.BaiduWebCredentials
import dev.infinityf4p.tiebapure.core.model.ForumMembership
import dev.infinityf4p.tiebapure.core.model.MessageKind
import dev.infinityf4p.tiebapure.core.model.MessagePage
import dev.infinityf4p.tiebapure.core.model.UserRelationshipKind
import dev.infinityf4p.tiebapure.core.model.UserRelationshipPage
import dev.infinityf4p.tiebapure.core.network.TiebaAccountReadService
import dev.infinityf4p.tiebapure.core.network.TiebaAuthenticationService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TiebaAccountRepositoriesTest {
    @Test
    fun authenticationRepositoryForwardsWebCredentials() = runBlocking {
        val service = FakeAuthenticationService()
        val repository = NetworkAuthenticationRepository(service)
        val credentials = BaiduWebCredentials("bduss", "stoken", "baiduid")

        repository.validateLogin(credentials)

        assertEquals(credentials, service.credentials)
    }

    @Test
    fun accountRepositoryPreservesRelationshipAndMessageKinds() = runBlocking {
        val service = FakeAccountReadService()
        val repository = NetworkAccountRepository(service)

        repository.relationships(account, 77, UserRelationshipKind.Followers, 3)
        repository.messages(account, MessageKind.Mention, 4)

        assertEquals(RelationshipCall(77, UserRelationshipKind.Followers, 3), service.relationshipCall)
        assertEquals(MessageCall(MessageKind.Mention, 4), service.messageCall)
    }

    private class FakeAuthenticationService : TiebaAuthenticationService {
        var credentials: BaiduWebCredentials? = null
        override suspend fun validateLogin(credentials: BaiduWebCredentials): Account {
            this.credentials = credentials
            return account
        }
    }

    private class FakeAccountReadService : TiebaAccountReadService {
        var relationshipCall: RelationshipCall? = null
        var messageCall: MessageCall? = null
        override suspend fun followedForums(account: Account) = emptyList<dev.infinityf4p.tiebapure.core.model.Forum>()
        override suspend fun relationships(
            account: Account?, userId: Long, kind: UserRelationshipKind, page: Int,
        ): UserRelationshipPage {
            relationshipCall = RelationshipCall(userId, kind, page)
            return UserRelationshipPage(emptyList(), page, 0, false)
        }
        override suspend fun messages(account: Account, kind: MessageKind, page: Int): MessagePage {
            messageCall = MessageCall(kind, page)
            return MessagePage(emptyList(), page, false)
        }
        override suspend fun threadFavorites(account: Account, page: Int) =
            AccountThreadFavoritesPage(emptyList(), page, false)
        override suspend fun resolveForumId(forumName: String) = 1L
        override suspend fun forumMembership(account: Account, forumId: Long) = ForumMembership(forumId, false)
    }

    private data class RelationshipCall(val userId: Long, val kind: UserRelationshipKind, val page: Int)
    private data class MessageCall(val kind: MessageKind, val page: Int)

    private companion object {
        val account = Account("42", "raw", "Display", "portrait", "bduss", "stoken", "baiduid", "tbs")
    }
}
