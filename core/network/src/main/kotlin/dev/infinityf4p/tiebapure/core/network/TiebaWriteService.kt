package dev.infinityf4p.tiebapure.core.network

import dev.infinityf4p.tiebapure.core.model.Account
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionKind
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionPolicy
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionReceipt
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionRequest
import dev.infinityf4p.tiebapure.core.model.Forum
import dev.infinityf4p.tiebapure.core.model.ForumMembership
import dev.infinityf4p.tiebapure.core.model.ForumSignResult
import dev.infinityf4p.tiebapure.core.model.OwnThreadDeletionTarget
import dev.infinityf4p.tiebapure.core.model.TiebaLikeObjectType
import dev.infinityf4p.tiebapure.core.model.UserProfileEditRequest
import dev.infinityf4p.tiebapure.core.model.UserSummary
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request

interface TiebaWriteService {
    /** Reopens admission after the same credentials have been validated again. */
    suspend fun activateSession(account: Account)
    /** Rejects new writes and waits until every already-admitted write has finished. */
    suspend fun invalidateAndDrain(account: Account)
    suspend fun setUserFollowed(account: Account, user: UserSummary, followed: Boolean)
    suspend fun setForumFollowed(account: Account, forum: Forum, followed: Boolean): ForumMembership
    suspend fun setPostLiked(
        account: Account,
        threadId: Long,
        postId: ULong,
        objectType: TiebaLikeObjectType,
        liked: Boolean,
    )
    suspend fun setThreadFavorite(account: Account, threadId: Long, postId: ULong, favorited: Boolean)
    suspend fun signForum(account: Account, forum: Forum): ForumSignResult
    suspend fun updateOwnProfile(account: Account, request: UserProfileEditRequest)
    suspend fun deleteOwnThread(account: Account, target: OwnThreadDeletionTarget)
    suspend fun submitContent(account: Account, request: ContentSubmissionRequest): ContentSubmissionReceipt
}

class DefaultTiebaWriteService(
    private val transport: TiebaTransport,
    private val sessions: TiebaSessionService,
    private val requestBuilder: TiebaRequestBuilder,
    private val clock: EpochMillisecondsClock = EpochMillisecondsClock.System,
    private val gate: TiebaAccountWriteGate = TiebaAccountWriteGate(),
) : TiebaWriteService {
    override suspend fun activateSession(account: Account) = gate.activate(account)

    override suspend fun invalidateAndDrain(account: Account) = gate.invalidateAndDrain(account)

    override suspend fun setUserFollowed(account: Account, user: UserSummary, followed: Boolean) = serialized(account) {
        val tbs = sessions.refreshedClientTbs(account)
        executeMutation(TiebaMutationHttpRequestFactory.userFollow(account, user, !followed, tbs, requestBuilder))
    }

    override suspend fun setForumFollowed(account: Account, forum: Forum, followed: Boolean): ForumMembership = serialized(account) {
        val forumId = forum.id.takeIf { it > 0 } ?: throw TiebaMutationException.InvalidForumId
        val tbs = sessions.refreshedClientTbs(account)
        executeMutation(TiebaMutationHttpRequestFactory.forumFollow(account, forumId, !followed, tbs, requestBuilder))
        ForumMembership(forumId, followed)
    }

    override suspend fun setPostLiked(
        account: Account, threadId: Long, postId: ULong, objectType: TiebaLikeObjectType, liked: Boolean,
    ) = serialized(account) {
        val tbs = sessions.refreshedClientTbs(account)
        executeMutation(TiebaMutationHttpRequestFactory.like(
            account, tbs, threadId, postId, objectType, liked, requestBuilder,
        ))
    }

    override suspend fun setThreadFavorite(
        account: Account, threadId: Long, postId: ULong, favorited: Boolean,
    ) = serialized(account) {
        val tbs = sessions.refreshedClientTbs(account)
        executeMutation(TiebaMutationHttpRequestFactory.threadStore(
            account, threadId, postId, !favorited, tbs, requestBuilder,
        ))
    }

    override suspend fun signForum(account: Account, forum: Forum): ForumSignResult = serialized(account) {
        val tbs = sessions.refreshedClientTbs(account)
        val payload = executeRawWrite(TiebaMutationHttpRequestFactory.signForum(
            account, forum.id, forum.name, tbs, requestBuilder, clock.now(),
        ))
        try {
            TiebaAccountJsonMapper.sign(payload, forum)
        } catch (error: Throwable) {
            throw classifyPostResponse(error)
        }
    }

    override suspend fun updateOwnProfile(account: Account, request: UserProfileEditRequest) = serialized(account) {
        executeMutation(TiebaMutationHttpRequestFactory.modifyProfile(account, request, requestBuilder))
    }

    override suspend fun deleteOwnThread(account: Account, target: OwnThreadDeletionTarget) = serialized(account) {
        val tbs = sessions.refreshedClientTbs(account)
        executeMutation(TiebaMutationHttpRequestFactory.deleteOwnThread(
            account, tbs, target, requestBuilder, clock.now(),
        ))
    }

    override suspend fun submitContent(
        account: Account,
        request: ContentSubmissionRequest,
    ): ContentSubmissionReceipt = serialized(account) {
        val validated = ContentSubmissionPolicy.validate(request)
        if (validated.target.kind == ContentSubmissionKind.NewThread) {
            submitThread(account, validated)
        } else {
            submitReply(account, validated)
        }
    }

    private suspend fun submitThread(account: Account, request: ContentSubmissionRequest): ContentSubmissionReceipt {
        val tbs = sessions.strictlyRefreshedWebTbs(account)
        val mutation = TiebaMutationHttpRequestFactory.webNewThread(account, tbs, request, requestBuilder, clock.now())
        return decodeFinalSubmission(mutation, targetThreadId = null)
    }

    private suspend fun submitReply(account: Account, request: ContentSubmissionRequest): ContentSubmissionReceipt {
        val threadId = request.target.threadId ?: throw TiebaMutationException.InvalidThreadId
        val imageInfo = request.images.mapIndexed { index, image ->
            val nonce = "${clock.now()}_$index"
            try {
                TiebaAccountJsonMapper.uploadedImageInfo(transport.text(
                    TiebaMutationHttpRequestFactory.webUploadPicture(
                        account, threadId, image.bytes, requestBuilder, nonce,
                    ),
                ))
            } catch (error: CancellationException) {
                throw error
            } catch (error: ContentSubmissionException) {
                throw error
            } catch (error: Throwable) {
                throw ContentSubmissionException.Business(-1, "图片上传失败，请检查网络后重试。")
            }
        }.joinToString("|")
        val tbs = sessions.strictlyRefreshedWebTbs(account)
        val timestamp = clock.now()
        val mutation = TiebaMutationHttpRequestFactory.webReply(
            account, tbs, request, imageInfo, requestBuilder, timestamp,
        )
        return decodeFinalSubmission(mutation, targetThreadId = threadId)
    }

    private suspend fun decodeFinalSubmission(request: Request, targetThreadId: Long?): ContentSubmissionReceipt {
        val payload = try {
            executeRawWrite(request)
        } catch (error: TiebaWriteException.OutcomeUnknown) {
            throw ContentSubmissionException.OutcomeUnknown(error.original)
        }
        return try {
            TiebaAccountJsonMapper.submissionReceipt(payload, targetThreadId)
        } catch (error: ContentSubmissionException) {
            throw error
        } catch (error: Throwable) {
            throw ContentSubmissionException.OutcomeUnknown(error)
        }
    }

    private suspend fun executeMutation(request: Request) {
        val payload = executeRawWrite(request)
        try {
            TiebaAccountJsonMapper.validateMutation(payload)
        } catch (error: Throwable) {
            throw classifyPostResponse(error)
        }
    }

    private suspend fun executeRawWrite(request: Request): String = transport.writeText(request)

    private fun classifyPostResponse(error: Throwable): Throwable = when (error) {
        is TiebaApiException, is TiebaMutationException, is ContentSubmissionException.VerificationRequired -> error
        is TiebaWriteException.OutcomeUnknown -> error
        else -> TiebaWriteException.OutcomeUnknown(error)
    }

    private suspend fun <T> serialized(account: Account, block: suspend () -> T): T =
        gate.withAccount(account, block)
}

class TiebaAccountWriteGate {
    private val sessions = ConcurrentHashMap<String, SessionState>()

    suspend fun <T> withAccount(account: Account, block: suspend () -> T): T {
        val state = state(account)
        state.admission.withLock {
            if (state.invalidated) throw ContentSubmissionException.NotLoggedIn
            if (state.active == 0) state.drained = CompletableDeferred()
            state.active += 1
        }
        return try {
            state.operation.withLock { block() }
        } finally {
            state.admission.withLock {
                state.active -= 1
                if (state.active == 0) state.drained.complete(Unit)
            }
        }
    }

    suspend fun invalidateAndDrain(account: Account) {
        val state = state(account)
        val drained = state.admission.withLock {
            state.invalidated = true
            state.drained
        }
        drained.await()
    }

    suspend fun activate(account: Account) {
        val state = state(account)
        state.admission.withLock {
            check(state.active == 0) { "Cannot activate a session while writes are still draining" }
            state.invalidated = false
        }
    }

    private fun state(account: Account): SessionState {
        val key = sessionKey(account)
        sessions[key]?.let { return it }
        val candidate = SessionState()
        return sessions.putIfAbsent(key, candidate) ?: candidate
    }

    private fun sessionKey(account: Account): String {
        if (account.uid.isBlank() || account.bduss.isBlank() || account.stoken.isBlank()) {
            throw ContentSubmissionException.NotLoggedIn
        }
        val material = listOf(account.uid, account.bduss, account.stoken)
            .joinToString(separator = "") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(material.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private class SessionState {
        val admission = Mutex()
        val operation = Mutex()
        var invalidated = false
        var active = 0
        var drained = CompletableDeferred(Unit)
    }
}
