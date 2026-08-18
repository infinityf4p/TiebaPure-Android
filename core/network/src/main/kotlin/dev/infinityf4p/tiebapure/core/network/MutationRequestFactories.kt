package dev.infinityf4p.tiebapure.core.network

import dev.infinityf4p.tiebapure.core.model.Account
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionKind
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionRequest
import dev.infinityf4p.tiebapure.core.model.OwnThreadDeletionTarget
import dev.infinityf4p.tiebapure.core.model.TiebaLikeObjectType
import dev.infinityf4p.tiebapure.core.model.UserProfileEditRequest
import dev.infinityf4p.tiebapure.core.model.UserSummary

object TiebaMutationRequestFactory {
    fun followUser(account: Account, user: UserSummary, tbs: String = account.tbs): Map<String, String> {
        val portrait = TiebaRemoteUrl.portraitToken(user.portrait).orEmpty()
        if (portrait.isEmpty()) throw TiebaMutationException.MissingPortrait
        return mapOf(
            "BDUSS" to account.bduss,
            "portrait" to portrait,
            "tbs" to requireTbs(tbs),
        )
    }

    fun forumMembership(account: Account, forumId: Long): Map<String, String> {
        requireForumId(forumId)
        return mapOf(
            "BDUSS" to account.bduss,
            "_client_version" to TiebaClientVersion.V22.value,
            "forum_id" to forumId.toString(),
            "friend_portrait" to account.portrait,
        )
    }

    fun followForum(account: Account, forumId: Long, tbs: String): Map<String, String> {
        requireForumId(forumId)
        return mapOf("BDUSS" to account.bduss, "fid" to forumId.toString(), "tbs" to requireTbs(tbs))
    }

    fun like(
        account: Account,
        tbs: String,
        threadId: Long,
        postId: ULong,
        objectType: TiebaLikeObjectType,
        targetLiked: Boolean,
        builder: TiebaRequestBuilder,
    ): Map<String, String> {
        if (threadId <= 0) throw TiebaMutationException.InvalidThreadId
        if (postId == 0uL) throw TiebaMutationException.InvalidPostId
        return mapOf(
            "BDUSS" to account.bduss,
            "_client_version" to TiebaClientVersion.V22.value,
            "agree_type" to "2",
            "cuid" to builder.device.miniCuid,
            "obj_type" to objectType.protocolValue.toString(),
            "op_type" to if (targetLiked) "0" else "1",
            "post_id" to if (objectType == TiebaLikeObjectType.Thread) "0" else postId.toString(),
            "tbs" to requireTbs(tbs),
            "thread_id" to threadId.toString(),
        )
    }

    fun profileEdit(account: Account, request: UserProfileEditRequest): Map<String, String> {
        val nickname = request.normalizedNickname
        if (nickname.isEmpty()) throw TiebaMutationException.MissingNickname
        return buildMap {
            put("BDUSS", account.bduss)
            put("intro", request.introduction)
            put("nick_name", nickname)
            request.sex.mutationProtocolValue?.let { put("sex", it.toString()) }
        }
    }

    fun deleteOwnThread(
        account: Account,
        tbs: String,
        target: OwnThreadDeletionTarget,
        builder: TiebaRequestBuilder,
        timestamp: Long,
    ): Map<String, String> {
        requireForumId(target.forumId)
        if (target.forumName.isBlank()) throw TiebaMutationException.InvalidForumName
        if (target.threadId <= 0) throw TiebaMutationException.InvalidThreadId
        if (target.firstPostId == 0uL) throw TiebaMutationException.InvalidPostId
        return builder.officialCommonFields(account, "12.25.1.0", timestamp) + mapOf(
            "delete_my_thread" to "1",
            "fid" to target.forumId.toString(),
            "is_frs_mask" to "0",
            "is_vipdel" to "0",
            "src" to "1",
            "tbs" to requireTbs(tbs),
            "word" to target.forumName.trim(),
            "z" to target.threadId.toString(),
        )
    }

    fun addThreadStore(account: Account, threadId: Long, postId: ULong, tbs: String): Map<String, String> {
        if (threadId <= 0) throw TiebaMutationException.InvalidThreadId
        val data = "[{\"tid\":\"$threadId\",\"pid\":\"$postId\",\"status\":1}]"
        return mapOf("BDUSS" to account.bduss, "data" to data, "tbs" to requireTbs(tbs))
    }

    fun removeThreadStore(account: Account, threadId: Long, tbs: String): Map<String, String> {
        if (threadId <= 0) throw TiebaMutationException.InvalidThreadId
        return mapOf("BDUSS" to account.bduss, "tid" to threadId.toString(), "tbs" to requireTbs(tbs))
    }

    fun signForum(
        account: Account,
        forumId: Long,
        forumName: String,
        tbs: String,
        builder: TiebaRequestBuilder,
        timestamp: Long,
    ): Map<String, String> {
        val name = forumName.trim()
        if (name.isEmpty()) throw TiebaMutationException.InvalidForumName
        return builder.officialCommonFields(account, TiebaClientVersion.V12.value, timestamp) + buildMap {
            put("BDUSS", account.bduss)
            put("kw", name)
            put("tbs", requireTbs(tbs))
            if (forumId > 0) put("fid", forumId.toString())
        }
    }

    internal fun requireTbs(value: String): String = value.trim().ifEmpty { throw TiebaMutationException.MissingTbs }

    private fun requireForumId(value: Long) {
        if (value <= 0) throw TiebaMutationException.InvalidForumId
    }
}

object ContentSubmissionRequestFactory {
    fun webThreadFields(
        account: Account,
        tbs: String,
        request: ContentSubmissionRequest,
        timestamp: Long,
    ): Map<String, String> {
        if (request.target.kind != ContentSubmissionKind.NewThread) {
            throw ContentSubmissionException.Unsupported("回复不能使用网页发帖协议。")
        }
        if (request.images.isNotEmpty()) {
            throw ContentSubmissionException.Unsupported("网页发帖协议暂不支持图片。")
        }
        return mapOf(
            "ie" to "utf-8",
            "fid" to request.target.forumId.toString(),
            "kw" to request.target.forumName,
            "tbs" to TiebaMutationRequestFactory.requireTbs(tbs),
            "title" to request.title,
            "content" to request.body,
            "nick_name" to account.resolvedDisplayName,
            "bsk" to timestamp.toString(),
        )
    }

    fun webReplyFields(
        account: Account,
        tbs: String,
        request: ContentSubmissionRequest,
        uploadedImageInfo: String,
        timestamp: Long,
    ): Map<String, String> {
        val threadId = request.target.threadId?.takeIf { it > 0 }
            ?: throw TiebaMutationException.InvalidThreadId
        if (request.target.kind == ContentSubmissionKind.NewThread) {
            throw ContentSubmissionException.Unsupported("新主题不能使用网页回帖协议。")
        }
        if (uploadedImageInfo.any { it == '\r' || it == '\n' || it.code < 0x20 }) {
            throw TiebaNetworkException.InvalidRequest("Invalid uploaded image metadata")
        }
        return buildMap {
            put("co", replyBody(request))
            put("_t", timestamp.toString())
            put("tag", "11")
            put("upload_img_info", uploadedImageInfo.trim())
            put("fid", request.target.forumId.toString())
            put("src", "1")
            put("word", request.target.forumName)
            put("tbs", TiebaMutationRequestFactory.requireTbs(tbs))
            put("z", threadId.toString())
            put("lp", "6026")
            put("nick_name", account.resolvedDisplayName)
            put("_BSK", timestamp.toString())
            if (request.target.kind == ContentSubmissionKind.PostReply ||
                request.target.kind == ContentSubmissionKind.SubpostReply
            ) {
                val parentId = request.target.parentPostId?.takeIf { it > 0uL }
                    ?: throw TiebaMutationException.InvalidPostId
                put("pid", parentId.toString())
                put("floor", (request.target.parentFloor ?: 1).coerceAtLeast(1).toString())
            }
            if (request.target.kind == ContentSubmissionKind.SubpostReply) {
                val subpostId = request.target.subpostId?.takeIf { it > 0uL }
                    ?: throw TiebaMutationException.InvalidPostId
                put("lzl_id", subpostId.toString())
            }
        }
    }

    private fun replyBody(request: ContentSubmissionRequest): String {
        if (request.target.kind != ContentSubmissionKind.SubpostReply) return request.body
        val user = request.target.replyUser ?: return request.body
        return "回复 ${user.resolvedDisplayName} : ${request.body}"
    }
}
