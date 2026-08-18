package dev.infinityf4p.tiebapure.feature.account

import dev.infinityf4p.tiebapure.core.model.Account
import dev.infinityf4p.tiebapure.core.model.MessageKind
import dev.infinityf4p.tiebapure.core.model.MessagePage
import dev.infinityf4p.tiebapure.core.model.TiebaMessage
import dev.infinityf4p.tiebapure.core.model.UserSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessagesViewModelTest {
    @Test
    fun changingKindSupersedesBusyRequestAndIgnoresOldResult() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repository = ControllableMessagesRepository()
            val viewModel = MessagesViewModel(account(), repository, scope)
            val oldCall = repository.calls.single()

            viewModel.selectKind(MessageKind.Mention)
            val newCall = repository.calls.last()

            assertEquals(2, repository.calls.size)
            assertEquals(MessageKind.Mention, newCall.kind)
            assertTrue(oldCall.cancelled.isCompleted)
            newCall.result.complete(page(MessageKind.Mention, "new"))
            oldCall.result.complete(page(MessageKind.Reply, "old"))

            assertEquals(MessageKind.Mention, viewModel.uiState.value.kind)
            assertEquals(listOf("new"), viewModel.uiState.value.messages.map(TiebaMessage::id))
            assertFalse(viewModel.uiState.value.isBusy)
        } finally {
            scope.cancel()
        }
    }
}

private class ControllableMessagesRepository : MessagesRepository {
    data class Call(
        val kind: MessageKind,
        val page: Int,
        val result: CompletableDeferred<MessagePage> = CompletableDeferred(),
        val cancelled: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    val calls = mutableListOf<Call>()

    override suspend fun loadMessages(kind: MessageKind, page: Int): MessagePage {
        val call = Call(kind, page)
        calls += call
        return try {
            call.result.await()
        } catch (_: CancellationException) {
            call.cancelled.complete(Unit)
            withContext(NonCancellable) { call.result.await() }
        }
    }
}

private fun account() = Account(
    uid = "1",
    name = "tester",
    displayName = "测试用户",
    portrait = "",
    bduss = "bduss",
    stoken = "stoken",
    tbs = "tbs",
)

private fun page(kind: MessageKind, id: String) = MessagePage(
    messages = listOf(
        TiebaMessage(
            id = id,
            kind = kind,
            sender = UserSummary(1, "tester", "测试用户", ""),
            threadId = 1,
            postId = 1uL,
            text = id,
            createdAtEpochSeconds = null,
            isRead = false,
        ),
    ),
    currentPage = 1,
    hasMore = false,
)
