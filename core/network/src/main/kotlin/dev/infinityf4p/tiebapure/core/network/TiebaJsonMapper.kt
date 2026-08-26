package dev.infinityf4p.tiebapure.core.network

import dev.infinityf4p.tiebapure.core.model.ContentBlock
import dev.infinityf4p.tiebapure.core.model.Forum
import dev.infinityf4p.tiebapure.core.model.ForumInfo
import dev.infinityf4p.tiebapure.core.model.ForumPage
import dev.infinityf4p.tiebapure.core.model.ImageContent
import dev.infinityf4p.tiebapure.core.model.SearchPage
import dev.infinityf4p.tiebapure.core.model.SearchThreadResult
import dev.infinityf4p.tiebapure.core.model.SearchUserResult
import dev.infinityf4p.tiebapure.core.model.ThreadSummary
import dev.infinityf4p.tiebapure.core.model.UserSummary
import dev.infinityf4p.tiebapure.core.model.VideoContent
import dev.infinityf4p.tiebapure.core.model.VoiceContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object TiebaJsonMapper {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun forum(payload: String, forumName: String, page: Int): ForumPage {
        val root = parseObject(payload)
        validate(root.int("error_code"), root.string("error_msg"))
        val users = root.array("user_list").mapNotNull(::asObject)
            .associate { it.long("id") to user(it) }
        val raw = root.array("thread_list")
        val threads = raw.mapNotNull(::asObject).mapNotNull { item ->
            val id = item.long("id").takeIf { it != 0L } ?: item.long("tid")
            val title = item.string("title")
            if (id == 0L || item.int("is_deleted") == 1 || item.containsKey("ala_info") ||
                item.containsKey("twzhibo_info") || item.containsKey("ad_info") || item.int("is_ad") == 1
            ) return@mapNotNull null
            val authorId = item.long("author_id")
            val blocks = mutableListOf<ContentBlock>()
            val abstract = item.textOrJoinedArray("abstract")
            if (abstract.isNotBlank()) blocks += ContentBlock.Text(abstract)
            item.array("media").mapNotNull(::asObject).mapNotNull(::forumImage).forEach(blocks::add)
            item.obj("video_info")?.let(::forumVideo)?.let(blocks::add)
            val voiceElements = when (val voice = item["voice_info"]) {
                is JsonArray -> voice
                is JsonObject -> JsonArray(listOf(voice))
                else -> JsonArray(emptyList())
            }
            voiceElements.mapNotNull(::asObject).forEach { voice ->
                VoiceContent.create(voice.string("voice_md5"), voice.int("during_time"))?.let {
                    if (blocks.filterIsInstance<ContentBlock.Voice>().none { block -> block.value.md5 == it.md5 }) {
                        blocks += ContentBlock.Voice(it)
                    }
                }
            }
            ThreadSummary(
                id = id,
                title = title,
                author = users[authorId] ?: UserSummary(authorId, "", "", ""),
                forumName = forumName,
                replyCount = item.int("reply_num"),
                viewCount = item.int("view_num"),
                likeCount = item.intAny("agree_num", "agreeNum"),
                createdAtEpochSeconds = item.long("create_time").takeIf { it != 0L },
                lastReplyAtEpochSeconds = item.long("last_time_int").takeIf { it != 0L },
                blocks = blocks,
                isTop = item.int("is_top") == 1,
                isGood = item.int("is_good") == 1,
                hasVideo = blocks.any { it is ContentBlock.Video },
            )
        }
        return ForumPage(
            forum = Forum(0, forumName, if (forumName.endsWith("吧")) forumName else "${forumName}吧"),
            threads = threads,
            currentPage = page,
            hasMore = raw.isNotEmpty(),
        )
    }

    fun forumInfo(payload: String): ForumInfo {
        val root = parseObject(payload)
        validate(root.int("error_code"), root.string("error_msg"))
        val forum = root.obj("forum")
            ?: throw TiebaNetworkException.Decode(IllegalStateException("Missing forum information"))
        return ForumInfo(
            forumId = forum.long("id").coerceAtLeast(0),
            memberCount = forum.long("member_num").coerceAtLeast(0),
            postCount = forum.long("post_num").coerceAtLeast(0),
            threadCount = forum.long("thread_num").coerceAtLeast(0),
            introduction = forum.string("slogan").trim(),
            primaryCategory = forum.string("first_class").trim().takeIf(String::isNotEmpty),
            secondaryCategory = forum.string("second_class").trim().takeIf(String::isNotEmpty),
        )
    }

    fun searchThreads(payload: String): SearchPage<SearchThreadResult> {
        val root = parseObject(payload)
        validate(root.int("no"), root.string("error"))
        val data = root.obj("data") ?: JsonObject(emptyMap())
        val results = data.array("post_list").mapNotNull(::asObject).mapNotNull { item ->
            val id = item.long("tid")
            if (id == 0L) return@mapNotNull null
            val user = item.obj("user")?.let(::searchUser) ?: UserSummary(0, "", "", "")
            val forum = item.obj("forum_info")
            val main = item.obj("main_post")
            val post = item.obj("post_info")
            val matchedPostId = item.ulongOrNull("pid")
            val explicitFirstPostId = item.ulongAnyOrNull("first_post_id", "first_pid")
                ?: main?.ulongAnyOrNull("pid", "post_id", "first_post_id")
            val firstPostId = explicitFirstPostId
                ?: matchedPostId.takeIf { main != null && post == null }
            val title = firstNonBlank(item.string("title"), main?.string("title"), post?.string("title"))
            val matched = firstNonBlank(item.string("content"), post?.string("content"), main?.string("content"))
            val blocks = mutableListOf<ContentBlock>()
            if (matched.isNotBlank()) blocks += ContentBlock.Text(matched)
            item.array("media").mapNotNull(::asObject).mapNotNull(::searchMedia).forEach(blocks::add)
            SearchThreadResult(
                thread = ThreadSummary(
                    id = id,
                    forumId = item.long("forum_id").takeIf { it != 0L },
                    title = title,
                    author = user,
                    forumName = firstNonBlank(item.string("forum_name"), forum?.string("forum_name")),
                    forumAvatarUrl = TiebaRemoteUrl.normalize(forum?.string("avatar")),
                    replyCount = item.int("post_num"),
                    viewCount = 0,
                    likeCount = item.int("like_num"),
                    firstPostId = firstPostId,
                    createdAtEpochSeconds = item.long("time").takeIf { it != 0L },
                    blocks = blocks,
                    hasVideo = blocks.any { it is ContentBlock.Video },
                ),
                matchedText = matched,
                matchedPostId = matchedPostId,
            )
        }
        return SearchPage(results, data.int("current_page").takeIf { it > 0 } ?: 1, data.int("has_more") == 1)
    }

    fun searchUsers(payload: String): SearchPage<SearchUserResult> {
        val root = parseObject(payload)
        validate(root.int("no"), root.string("error"))
        val data = root.obj("data") ?: JsonObject(emptyMap())
        val exact = candidates(data["exactMatch"])
        val fuzzy = candidates(data["fuzzyMatch"])
        val results = buildList {
            exact.forEach { add(SearchUserResult(searchUser(it), true)) }
            fuzzy.forEach { add(SearchUserResult(searchUser(it), false)) }
        }.distinctBy { it.user.id.takeIf { id -> id != 0L } ?: it.user.resolvedDisplayName }
        return SearchPage(results, 1, false)
    }

    private fun candidates(element: JsonElement?): List<JsonObject> = when (element) {
        is JsonArray -> element.mapNotNull(::asObject)
        is JsonObject -> if (element.keys.any { it in USER_KEYS }) listOf(element) else element.values.mapNotNull(::asObject)
        else -> emptyList()
    }

    private fun user(item: JsonObject): UserSummary = UserSummary(
        id = item.long("id"),
        name = item.string("name"),
        displayName = firstNonBlank(item.string("name_show"), item.string("nick"), item.string("name")),
        portrait = TiebaRemoteUrl.portrait(item.string("portrait")),
    )

    private fun searchUser(item: JsonObject): UserSummary {
        val name = firstNonBlank(item.string("user_name"), item.string("name"))
        return UserSummary(
            id = item.longAny("user_id", "uid", "id"),
            name = name,
            displayName = firstNonBlank(item.string("show_nickname"), item.string("user_nickname"), name),
            portrait = TiebaRemoteUrl.portrait(item.string("portrait")),
        )
    }

    private fun forumImage(item: JsonObject): ContentBlock.Image? {
        val thumb = TiebaRemoteUrl.normalize(firstNonBlank(item.string("src_pic"), item.string("big_pic"), item.string("dynamic_pic")))
        val original = TiebaRemoteUrl.normalize(firstNonBlank(item.string("origin_pic"), item.string("big_pic"), item.string("src_pic")))
        if (thumb == null && original == null) return null
        return ContentBlock.Image(ImageContent(thumb, original, 1, 1, item.int("show_original_btn") == 1))
    }

    private fun forumVideo(item: JsonObject): ContentBlock.Video? {
        val url = TiebaRemoteUrl.normalize(firstNonBlank(item.string("origin_video_url"), item.string("video_url")))
        val cover = TiebaRemoteUrl.normalize(item.string("thumbnail_url"))
        if (url == null && cover == null) return null
        return ContentBlock.Video(VideoContent(url, cover, null, 16, 9, 0))
    }

    private fun searchMedia(item: JsonObject): ContentBlock? {
        val width = item.int("width").coerceAtLeast(1)
        val height = item.int("height").coerceAtLeast(1)
        if (item.string("type") == "flash") return ContentBlock.Video(VideoContent(
            videoUrl = TiebaRemoteUrl.normalize(firstNonBlank(item.string("vhsrc"), item.string("vsrc"))),
            coverUrl = TiebaRemoteUrl.normalize(firstNonBlank(item.string("vpic"), item.string("big_pic"), item.string("small_pic"))),
            webUrl = null, width = width, height = height, durationSeconds = 0,
        ))
        if (item.string("type") != "pic") return null
        val thumb = TiebaRemoteUrl.normalize(firstNonBlank(item.string("big_pic"), item.string("small_pic"), item.string("water_pic"), item.string("src")))
        val original = TiebaRemoteUrl.normalize(firstNonBlank(item.string("src"), item.string("big_pic"), item.string("small_pic")))
        if (thumb == null && original == null) return null
        return ContentBlock.Image(ImageContent(thumb, original, width, height, true))
    }

    private fun parseObject(payload: String): JsonObject = try {
        asObject(json.parseToJsonElement(payload)) ?: throw IllegalArgumentException("JSON root is not an object")
    } catch (error: Exception) {
        throw TiebaNetworkException.Decode(error)
    }

    private fun validate(code: Int, message: String) = TiebaResponseValidator.validate(code, message)
    private fun asObject(element: JsonElement): JsonObject? = element as? JsonObject
    private fun JsonObject.obj(key: String) = get(key) as? JsonObject
    private fun JsonObject.array(key: String) = get(key) as? JsonArray ?: JsonArray(emptyList())
    private fun JsonObject.string(key: String): String = primitive(key)?.contentOrNull.orEmpty()
    private fun JsonObject.int(key: String): Int = string(key).toIntOrNull() ?: 0
    private fun JsonObject.long(key: String): Long = string(key).toLongOrNull() ?: 0
    private fun JsonObject.longAny(vararg keys: String): Long = keys.firstNotNullOfOrNull { string(it).toLongOrNull() } ?: 0
    private fun JsonObject.intAny(vararg keys: String): Int = keys.firstNotNullOfOrNull { string(it).toIntOrNull() } ?: 0
    private fun JsonObject.ulongOrNull(key: String): ULong? = string(key).toULongOrNull()?.takeIf { it != 0uL }
    private fun JsonObject.ulongAnyOrNull(vararg keys: String): ULong? =
        keys.firstNotNullOfOrNull { ulongOrNull(it) }
    private fun JsonObject.primitive(key: String) = get(key) as? JsonPrimitive
    private fun JsonObject.textOrJoinedArray(key: String): String = when (val value = get(key)) {
        is JsonPrimitive -> value.contentOrNull.orEmpty()
        is JsonArray -> value.mapNotNull(::asObject).joinToString("") { it.string("text") }
        else -> ""
    }

    private fun firstNonBlank(vararg values: String?): String = values.firstOrNull { !it.isNullOrBlank() }.orEmpty()
    private val USER_KEYS = setOf("id", "uid", "user_id", "name", "user_name", "portrait")
}
