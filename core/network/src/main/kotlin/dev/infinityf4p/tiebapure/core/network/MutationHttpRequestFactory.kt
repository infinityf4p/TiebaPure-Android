package dev.infinityf4p.tiebapure.core.network

import dev.infinityf4p.tiebapure.core.model.Account
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionRequest
import dev.infinityf4p.tiebapure.core.model.OwnThreadDeletionTarget
import dev.infinityf4p.tiebapure.core.model.TiebaLikeObjectType
import dev.infinityf4p.tiebapure.core.model.UserProfileEditRequest
import dev.infinityf4p.tiebapure.core.model.UserSummary
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okio.ByteString.Companion.toByteString

object TiebaMutationHttpRequestFactory {
    fun userFollow(
        account: Account,
        user: UserSummary,
        currentlyFollowed: Boolean,
        tbs: String,
        builder: TiebaRequestBuilder,
    ): Request = builder.formRequest(
        if (currentlyFollowed) TiebaEndpoint.UnfollowUser else TiebaEndpoint.FollowUser,
        TiebaMutationRequestFactory.followUser(account, user, tbs),
        headers = mapOf("User-Agent" to "tieba/${TiebaClientVersion.V22.value}"),
        signingSecret = TiebaFormSigner.DEFAULT_SECRET,
    )

    fun forumFollow(
        account: Account,
        forumId: Long,
        currentlyFollowed: Boolean,
        tbs: String,
        builder: TiebaRequestBuilder,
    ): Request = builder.formRequest(
        if (currentlyFollowed) TiebaEndpoint.UnfollowForum else TiebaEndpoint.FollowForum,
        TiebaMutationRequestFactory.followForum(account, forumId, tbs),
        headers = mapOf("User-Agent" to "tieba/${TiebaClientVersion.V22.value}"),
        signingSecret = TiebaFormSigner.DEFAULT_SECRET,
    )

    fun like(
        account: Account,
        tbs: String,
        threadId: Long,
        postId: ULong,
        objectType: TiebaLikeObjectType,
        currentlyLiked: Boolean,
        builder: TiebaRequestBuilder,
    ): Request = builder.formRequest(
        TiebaEndpoint.AgreePost,
        TiebaMutationRequestFactory.like(
            account,
            tbs,
            threadId,
            postId,
            objectType,
            currentlyLiked,
            builder,
        ),
        headers = mapOf(
            "Pragma" to "no-cache",
            "User-Agent" to "tieba/${TiebaClientVersion.V22.value}",
        ),
        signingSecret = TiebaFormSigner.DEFAULT_SECRET,
    )

    fun modifyProfile(
        account: Account,
        edit: UserProfileEditRequest,
        builder: TiebaRequestBuilder,
    ): Request = builder.formRequest(
        TiebaEndpoint.ModifyProfile,
        TiebaMutationRequestFactory.profileEdit(account, edit),
        headers = mapOf("User-Agent" to "tieba/${TiebaClientVersion.V22.value}"),
        signingSecret = TiebaFormSigner.DEFAULT_SECRET,
    )

    fun deleteOwnThread(
        account: Account,
        tbs: String,
        target: OwnThreadDeletionTarget,
        builder: TiebaRequestBuilder,
        timestamp: Long,
    ): Request = builder.formRequest(
        TiebaEndpoint.DeleteOwnThread,
        TiebaMutationRequestFactory.deleteOwnThread(account, tbs, target, builder, timestamp),
        headers = builder.officialHeaders(account.baiduId, "12.25.1.0", timestamp),
        signingSecret = TiebaFormSigner.DEFAULT_SECRET,
    )

    fun threadStore(
        account: Account,
        threadId: Long,
        postId: ULong,
        currentlyStored: Boolean,
        tbs: String,
        builder: TiebaRequestBuilder,
    ): Request = if (currentlyStored) {
        builder.formRequest(
            TiebaEndpoint.RemoveThreadStore,
            TiebaMutationRequestFactory.removeThreadStore(account, threadId, tbs),
            headers = builder.officialHeaders(account.baiduId, TiebaClientVersion.V12.value),
            signingSecret = TiebaFormSigner.DEFAULT_SECRET,
        )
    } else {
        builder.formRequest(
            TiebaEndpoint.AddThreadStore,
            TiebaMutationRequestFactory.addThreadStore(account, threadId, postId, tbs),
            headers = builder.officialHeaders(account.baiduId, TiebaClientVersion.V12.value),
            signingSecret = TiebaFormSigner.DEFAULT_SECRET,
        )
    }

    fun signForum(
        account: Account,
        forumId: Long,
        forumName: String,
        tbs: String,
        builder: TiebaRequestBuilder,
        timestamp: Long,
    ): Request = builder.formRequest(
        TiebaEndpoint.SignForum,
        TiebaMutationRequestFactory.signForum(account, forumId, forumName, tbs, builder, timestamp),
        headers = builder.officialHeaders(account.baiduId, TiebaClientVersion.V12.value, timestamp),
        signingSecret = TiebaFormSigner.DEFAULT_SECRET,
    )

    fun webNewThread(
        account: Account,
        tbs: String,
        submission: ContentSubmissionRequest,
        builder: TiebaRequestBuilder,
        timestamp: Long,
    ): Request = builder.formRequest(
        TiebaEndpoint.WebAddThread,
        ContentSubmissionRequestFactory.webThreadFields(account, tbs, submission, timestamp),
        headers = webHeaders(
            account,
            "https://tieba.baidu.com/f".toHttpUrl().newBuilder()
                .addQueryParameter("kw", submission.target.forumName)
                .build()
                .toString(),
        ),
    )

    fun webReply(
        account: Account,
        tbs: String,
        submission: ContentSubmissionRequest,
        uploadedImageInfo: String,
        builder: TiebaRequestBuilder,
        timestamp: Long,
    ): Request {
        val threadId = submission.target.threadId?.takeIf { it > 0 }
            ?: throw TiebaMutationException.InvalidThreadId
        return builder.formRequest(
            TiebaEndpoint.webAddPost(timestamp),
            ContentSubmissionRequestFactory.webReplyFields(
                account,
                tbs,
                submission,
                uploadedImageInfo,
                timestamp,
            ),
            headers = webHeaders(account, webThreadReferer(threadId)),
        )
    }

    fun webUploadPicture(
        account: Account,
        threadId: Long,
        imageBytes: ByteArray,
        builder: TiebaRequestBuilder,
        nonce: String,
    ): Request {
        if (threadId <= 0) throw TiebaMutationException.InvalidThreadId
        return builder.formRequest(
            TiebaEndpoint.webUploadPicture(nonce),
            fields = mapOf("pic" to imageBytes.toByteString().base64()),
            headers = webHeaders(account, webThreadReferer(threadId)),
        )
    }

    private fun webHeaders(account: Account, referer: String): Map<String, String> = mapOf(
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "zh-CN,zh;q=0.9",
        "Cache-Control" to "no-cache",
        // Web writes require only the two authenticated cookies. Keep BAIDUID
        // out of mutation requests to match the iOS boundary.
        "Cookie" to TiebaHeaderPolicy.minimalCookieHeader(account, includeBaiduId = false),
        "Origin" to "https://tieba.baidu.com",
        "Pragma" to "no-cache",
        "Referer" to referer,
        "User-Agent" to TIEBA_WEB_POSTING_USER_AGENT,
        "X-Requested-With" to "XMLHttpRequest",
    )

    private fun webThreadReferer(threadId: Long): String =
        "https://tieba.baidu.com/p/$threadId?lp=5028&mo_device=1&is_jingpost=0&pn=1&"

}
