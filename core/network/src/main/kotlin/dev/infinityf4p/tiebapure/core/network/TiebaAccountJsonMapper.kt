package dev.infinityf4p.tiebapure.core.network

import dev.infinityf4p.tiebapure.core.model.AccountThreadFavorite
import dev.infinityf4p.tiebapure.core.model.AccountThreadFavoritesPage
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionReceipt
import dev.infinityf4p.tiebapure.core.model.Forum
import dev.infinityf4p.tiebapure.core.model.ForumMembership
import dev.infinityf4p.tiebapure.core.model.ForumSignResult
import dev.infinityf4p.tiebapure.core.model.MessageKind
import dev.infinityf4p.tiebapure.core.model.MessagePage
import dev.infinityf4p.tiebapure.core.model.SubmissionVerificationChallenge
import dev.infinityf4p.tiebapure.core.model.TiebaMessage
import dev.infinityf4p.tiebapure.core.model.UserRelationshipKind
import dev.infinityf4p.tiebapure.core.model.UserRelationshipPage
import dev.infinityf4p.tiebapure.core.model.UserSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

internal object TiebaAccountJsonMapper {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class FollowedForumsPage(
        val forums: List<Forum>,
        val hasMore: Boolean,
    )

    data class ClientLogin(
        val uid: String,
        val name: String,
        val portrait: String,
        val tbs: String,
        val code: Int?,
        val message: String,
    )

    data class WebIdentity(
        val uid: String,
        val name: String,
        val displayName: String,
        val portrait: String,
        val tbs: String,
        val isLogin: Boolean?,
    )

    fun clientLogin(payload: String): ClientLogin {
        val root = root(payload)
        val user = root.obj("user")
        return ClientLogin(
            uid = user?.string("id").orEmpty(),
            name = user?.string("name").orEmpty(),
            portrait = TiebaRemoteUrl.portrait(user?.string("portrait")),
            tbs = root.obj("anti")?.string("tbs").orEmpty(),
            code = root.intOrNull("error_code"),
            message = root.string("error_msg"),
        )
    }

    fun nickname(payload: String): String = root(payload).obj("user_info")?.let {
        firstNonBlank(it.string("name_show"), it.string("user_nickname"), it.string("user_name"))
    }.orEmpty()

    fun webIdentity(payload: String): WebIdentity {
        val data = root(payload).obj("data") ?: JsonObject(emptyMap())
        return WebIdentity(
            uid = firstNonBlank(data.string("uid"), data.string("id")),
            name = firstNonBlank(data.string("name"), data.string("name_show")),
            displayName = firstNonBlank(data.string("name_show"), data.string("name")),
            portrait = TiebaRemoteUrl.portrait(firstNonBlank(data.string("portrait"), data.string("portrait_url"))),
            tbs = firstNonBlank(data.string("tbs"), data.string("itb_tbs")),
            isLogin = data.boolOrNull("is_login"),
        )
    }

    fun webTbs(payload: String): String {
        val root = root(payload)
        if (root.boolOrNull("is_login") != true) throw ContentSubmissionException.SessionExpired
        return root.string("tbs").trim().ifEmpty { throw TiebaMutationException.MissingTbs }
    }

    fun followedForums(payload: String): FollowedForumsPage {
        val root = root(payload)
        validate(root.int("error_code"), root.string("error_msg"))
        val forumList = root.obj("forum_list")
        val items = forumList?.let { it.array("non-gconforum") + it.array("gconforum") }.orEmpty()
        val forums = items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.long("forum_id")
                .takeIf { it > 0 }
                ?: item.long("id").takeIf { it > 0 }
                ?: return@mapNotNull null
            val name = firstNonBlank(item.string("forum_name"), item.string("name"))
            if (name.isBlank()) null else Forum(id, name, name, TiebaRemoteUrl.normalize(item.string("avatar")))
        }
        return FollowedForumsPage(forums, root.int("has_more") != 0)
    }

    fun relationships(payload: String, kind: UserRelationshipKind, requestedPage: Int): UserRelationshipPage {
        val root = root(payload)
        validate(root.requiredInt("error_code"), root.string("error_msg"))
        val page = root.obj("page")
        val items = root.array(if (kind == UserRelationshipKind.Following) "follow_list" else "user_list")
            .objects().map(::user)
        val current = if (kind == UserRelationshipKind.Following) root.int("pn") else page?.int("current_page") ?: 0
        val total = if (kind == UserRelationshipKind.Following) root.int("total_follow_num") else page?.int("total_count") ?: items.size
        val more = if (kind == UserRelationshipKind.Following) root.int("has_more") != 0 else page?.int("has_more") != 0
        return UserRelationshipPage(items, maxOf(current, requestedPage), total.coerceAtLeast(0), more)
    }

    fun forumId(payload: String): Long {
        val root = root(payload)
        validate(root.requiredInt("no"), root.string("error"))
        return root.obj("data")?.long("fid")?.takeIf { it > 0 }
            ?: throw TiebaNetworkException.Decode(IllegalStateException("Missing forum id"))
    }

    fun membership(payload: String, forumId: Long): ForumMembership {
        val root = root(payload)
        validate(root.requiredInt("error_code"), firstNonBlank(root.string("error_msg"), root.string("error"), root.string("errmsg")))
        val followed = root.obj("data")?.obj("user_forum_info")?.requiredInt("is_follow") != 0
        return ForumMembership(forumId, followed)
    }

    fun favorites(payload: String, requestedPage: Int): AccountThreadFavoritesPage {
        val root = root(payload)
        validate(root.int("error_code"), root.string("error_msg"))
        val data = root.obj("data") ?: JsonObject(emptyMap())
        val items = data.array("thread_list").objects().mapNotNull { item ->
            val id = item.long("tid").takeIf { it > 0 } ?: item.long("id").takeIf { it > 0 } ?: return@mapNotNull null
            val author = item.obj("author")
            AccountThreadFavorite(
                threadId = id,
                forumId = item.long("fid"),
                forumName = item.string("fname"),
                title = item.string("title").ifBlank { "帖子 $id" },
                authorDisplayName = firstNonBlank(author?.string("name_show"), author?.string("name"), "未知用户"),
                replyCount = item.int("reply_num"),
                lastReplyAtEpochSeconds = item.long("last_time_int").takeIf { it > 0 },
                markedPostId = item.string("collect_mark_pid").toULongOrNull()?.takeIf { it > 0uL },
            )
        }
        return AccountThreadFavoritesPage(items, requestedPage, data.int("has_more") != 0)
    }

    fun messages(payload: String, kind: MessageKind, requestedPage: Int): MessagePage {
        require(kind != MessageKind.Agree) { "Agree notifications are not exposed by this endpoint" }
        val root = root(payload)
        validate(root.int("error_code"), root.string("error_msg"))
        val key = if (kind == MessageKind.Reply) "reply_list" else "at_list"
        val items = (root[key] as? JsonArray)?.objects().orEmpty().mapNotNull { item ->
            val threadId = item.long("thread_id").takeIf { it > 0 } ?: return@mapNotNull null
            val sender = item.obj("replyer")?.let(::user) ?: UserSummary(0, "", "", "")
            val isFloor = item.int("is_floor") == 1
            val postId = item.string(if (isFloor) "quote_pid" else "post_id").toULongOrNull()?.takeIf { it > 0uL }
            val time = item.long("time").takeIf { it > 0 }
            TiebaMessage(
                id = "${kind.name}-$threadId-${item.string("post_id")}-${sender.id}-${item.string("time")}",
                kind = kind,
                sender = sender,
                threadId = threadId,
                postId = postId,
                text = item.string("content"),
                createdAtEpochSeconds = time,
                isRead = false,
                threadTitle = item.string("title"),
                forumName = item.string("fname").ifBlank { null },
                isFloorReply = isFloor,
            )
        }.orEmpty()
        val page = root.obj("page")
        return MessagePage(items, maxOf(page?.int("current_page") ?: 0, requestedPage), page?.int("has_more") != 0)
    }

    fun validateMutation(payload: String) {
        val root = root(payload)
        val topCode = root.requiredInt("error_code")
        val nested = root.obj("error")
        val code = if (topCode != 0) topCode else nested?.intOrNull("errno") ?: 0
        val message = if (topCode != 0) root.string("error_msg") else firstNonBlank(nested?.string("errmsg"), root.string("error_msg"))
        validate(code, message)
    }

    fun sign(payload: String, forum: Forum): ForumSignResult {
        val root = root(payload)
        val code = root.requiredInt("error_code")
        val info = root.obj("user_info")
        if (code != 160002) validate(code, root.string("error_msg"))
        return ForumSignResult(
            forum.id,
            forum.name,
            code == 160002 || (info?.int("is_sign_in") == 1 && info.int("sign_bonus_point") == 0),
            if (code == 160002) 0 else info?.int("sign_bonus_point") ?: 0,
            info?.int("cont_sign_num") ?: 0,
            info?.int("user_sign_rank") ?: 0,
        )
    }

    fun uploadedImageInfo(payload: String): String {
        val root = root(payload)
        val code = consistentCode(root, listOf("error_code", "err_code"))
        val message = firstNonBlank(root.string("error_msg"), root.string("errorMsg"), root.string("err_msg"), root.string("errMsg"))
        if (code != null && code != 0) validate(code, message)
        val value = firstNonBlank(root.string("image_info"), root.string("imageInfo")).trim()
        if (value.isEmpty() || value.toByteArray().size > 16_384 || value.any { it == '\r' || it == '\n' }) {
            throw ContentSubmissionException.Business(-1, "贴吧没有返回有效的图片信息。")
        }
        return value
    }

    fun submissionReceipt(payload: String, targetThreadId: Long?): ContentSubmissionReceipt {
        val root = root(payload)
        val nested = root.obj("data")
        val challenge = challenge(root, nested)
        val codes = listOf("error_code", "err_code", "no", "errno", "error_no")
            .flatMap { key -> listOfNotNull(root.intOrNull(key), nested?.intOrNull(key)) }
            .distinct()
        if (codes.size > 1) throw unknown("Conflicting submission status codes")
        val resultValues = listOfNotNull(root.intOrNull("result"), nested?.intOrNull("result")).distinct()
        if (resultValues.size > 1) throw unknown("Conflicting submission results")
        val code = codes.singleOrNull()
        val result = resultValues.singleOrNull()
        val messages = listOf(
            root.string("error_msg"), root.string("err_msg"), root.string("error"), root.string("msg"),
            nested?.string("error_msg"), nested?.string("err_msg"), nested?.string("error"), nested?.string("msg"),
        ).filterNotNull().filter(String::isNotBlank)
        val message = messages.firstOrNull().orEmpty()
        if (code != null && TiebaResponseValidator.isSessionExpired(code, message)) throw ContentSubmissionException.SessionExpired
        if (challenge != null) throw ContentSubmissionException.VerificationRequired(challenge)
        if (code != null && code != 0) throw ContentSubmissionException.Business(code, message.ifBlank { "发布失败。" })
        if (result != null && result != 1) throw ContentSubmissionException.Business(code, message.ifBlank { "发布失败。" })
        if (code != 0 && result != 1) throw unknown("Missing explicit submission success")

        val tids = listOf(root.string("tid"), nested?.string("tid")).filterNotNull().filter(String::isNotBlank).distinct()
        val returnedThread = tids.singleOrNull()?.toLongOrNull()
        val resolvedThread = targetThreadId ?: returnedThread
        if (resolvedThread == null || resolvedThread <= 0 || (returnedThread != null && returnedThread != resolvedThread)) {
            throw unknown("Invalid returned thread id")
        }
        val pids = listOf(
            root.string("pid"), root.string("post_id"), nested?.string("pid"), nested?.string("post_id"),
        ).filterNotNull().filter(String::isNotBlank).distinct()
        if (pids.size > 1) throw unknown("Conflicting returned post ids")
        val postId = pids.singleOrNull()?.toULongOrNull()
        if (pids.isNotEmpty() && (postId == null || postId == 0uL)) throw unknown("Invalid returned post id")
        return ContentSubmissionReceipt(resolvedThread, postId)
    }

    private fun challenge(vararg objects: JsonObject?): SubmissionVerificationChallenge? {
        val all = objects.filterNotNull()
        val messages = all.flatMap { listOf(it.string("error_msg"), it.string("err_msg"), it.string("msg"), it.string("error")) }
        val needs = all.any {
            it.boolOrNull("need_vcode") == true || it.int("need_vcode") == 1 ||
                it.string("vcode_md5").isNotBlank() || it.string("vcode_type").isNotBlank()
        } || messages.any { message -> listOf("验证码", "验证", "captcha", "vcode").any(message.lowercase()::contains) }
        if (!needs) return null
        fun value(vararg keys: String) = all.firstNotNullOfOrNull { objectValue ->
            keys.firstNotNullOfOrNull { key -> objectValue.string(key).takeIf(String::isNotBlank) }
        }.orEmpty()
        return SubmissionVerificationChallenge(
            verificationType = value("vcode_type", "verification_type", "vcode_prev_type"),
            md5 = value("vcode_md5", "md5"),
            imageUrl = value("vcode_pic_url", "vcode_url", "pic_url").ifBlank { null },
            message = messages.firstOrNull(String::isNotBlank) ?: "贴吧要求完成验证码后再发布。",
        )
    }

    private fun user(item: JsonObject): UserSummary {
        val portrait = TiebaRemoteUrl.portrait(item.string("portrait"))
        return UserSummary(
            item.long("id"), item.string("name"),
            firstNonBlank(item.string("name_show"), item.string("name")), portrait,
        )
    }

    private fun root(payload: String): JsonObject = try {
        json.parseToJsonElement(payload) as? JsonObject
            ?: throw IllegalArgumentException("JSON root is not an object")
    } catch (error: TiebaApiException) {
        throw error
    } catch (error: Exception) {
        throw TiebaNetworkException.Decode(error)
    }

    private fun validate(code: Int, message: String) = TiebaResponseValidator.validate(code, message)
    private fun JsonObject.obj(key: String) = get(key) as? JsonObject
    private fun JsonObject.array(key: String) = get(key) as? JsonArray ?: JsonArray(emptyList())
    private fun JsonArray.objects() = mapNotNull { it as? JsonObject }
    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.intOrNull(key: String) = string(key).toIntOrNull()
    private fun JsonObject.requiredInt(key: String) = intOrNull(key)
        ?: throw TiebaNetworkException.Decode(IllegalArgumentException("Missing numeric $key"))
    private fun JsonObject.int(key: String) = intOrNull(key) ?: 0
    private fun JsonObject.long(key: String) = string(key).toLongOrNull() ?: 0
    private fun JsonObject.boolOrNull(key: String): Boolean? {
        val primitive = get(key) as? JsonPrimitive ?: return null
        primitive.booleanOrNull?.let { return it }
        return when (primitive.contentOrNull?.lowercase()) { "1", "true" -> true; "0", "false" -> false; else -> null }
    }
    private fun consistentCode(root: JsonObject, keys: List<String>): Int? {
        val values = keys.mapNotNull { key -> root.intOrNull(key) }.distinct()
        if (values.size > 1) throw ContentSubmissionException.Business(-1, "图片上传响应状态无效。")
        return values.singleOrNull()
    }
    private fun firstNonBlank(vararg values: String?): String = values.firstOrNull { !it.isNullOrBlank() }.orEmpty()
    private fun unknown(message: String) = ContentSubmissionException.OutcomeUnknown(IllegalStateException(message))
}
