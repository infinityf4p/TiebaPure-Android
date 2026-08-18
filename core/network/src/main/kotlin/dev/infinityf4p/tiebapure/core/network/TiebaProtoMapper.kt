package dev.infinityf4p.tiebapure.core.network

import dev.infinityf4p.tiebapure.core.model.ContentBlock
import dev.infinityf4p.tiebapure.core.model.Forum
import dev.infinityf4p.tiebapure.core.model.ForumPage
import dev.infinityf4p.tiebapure.core.model.ImageContent
import dev.infinityf4p.tiebapure.core.model.OwnThreadDeletionTarget
import dev.infinityf4p.tiebapure.core.model.Post
import dev.infinityf4p.tiebapure.core.model.Subpost
import dev.infinityf4p.tiebapure.core.model.SubpostPage
import dev.infinityf4p.tiebapure.core.model.ThreadPage
import dev.infinityf4p.tiebapure.core.model.ThreadSummary
import dev.infinityf4p.tiebapure.core.model.UserContentVisibility
import dev.infinityf4p.tiebapure.core.model.UserProfile
import dev.infinityf4p.tiebapure.core.model.UserProfileSex
import dev.infinityf4p.tiebapure.core.model.UserSummary
import dev.infinityf4p.tiebapure.core.model.UserThreadsPage
import dev.infinityf4p.tiebapure.core.model.VideoContent
import dev.infinityf4p.tiebapure.core.model.VoiceContent
import java.net.URI
import okhttp3.HttpUrl.Companion.toHttpUrl
import tieba.ErrorOuterClass.Error
import tieba.MediaOuterClass.Media
import tieba.PbContentOuterClass.PbContent
import tieba.PostOuterClass.Post as ProtoPost
import tieba.SubPostListOuterClass.SubPostList
import tieba.ThreadInfoOuterClass.ThreadInfo
import tieba.UserOuterClass.User
import tieba.VideoInfoOuterClass.VideoInfo
import tieba.VoiceOuterClass.Voice
import tieba.frsPage.FrsPage
import tieba.pbFloor.PbFloorResponseOuterClass
import tieba.pbPage.PbPageResponseOuterClass
import tiebapure.profile.UserProfile as ProfileProtocol

object TiebaProtoMapper {
    fun personalized(response: tieba.Personalized.PersonalizedResponse): List<ThreadSummary> {
        validate(response.error)
        if (!response.hasData()) throw TiebaApiException.EmptyResponse
        return response.data.threadListList.filter(::isVisibleThread).map { thread(it, emptyMap()) }
    }

    fun forum(
        response: FrsPage.FrsPageResponse,
        forumName: String,
        page: Int,
    ): ForumPage {
        validate(response.error)
        if (!response.hasData()) throw TiebaApiException.EmptyResponse
        val users = response.data.userListList.associateBy(User::getId)
        val threads = response.data.threadListList.filter(::isVisibleThread).map { thread(it, users) }
        val resolved = threads.firstOrNull()
        return ForumPage(
            forum = Forum(
                id = resolved?.forumId ?: 0,
                name = forumName,
                displayName = displayForumName(forumName),
                avatarUrl = resolved?.forumAvatarUrl,
            ),
            threads = threads,
            currentPage = page,
            hasMore = response.data.threadListCount > 0,
        )
    }

    fun threadPage(response: PbPageResponseOuterClass.PbPageResponse): ThreadPage {
        validate(response.error)
        if (!response.hasData()) throw TiebaApiException.EmptyResponse
        val data = response.data
        val users = data.userListList.associateBy(User::getId)
        val mappedThread = thread(data.thread, users)
        val forum = forum(data.forum).let {
            if (it.name.isBlank() && !mappedThread.forumName.isNullOrBlank()) {
                it.copy(
                    id = mappedThread.forumId ?: it.id,
                    name = mappedThread.forumName.orEmpty(),
                    displayName = displayForumName(mappedThread.forumName.orEmpty()),
                    avatarUrl = mappedThread.forumAvatarUrl,
                )
            } else it
        }
        val rawMain = when {
            data.hasFirstFloorPost() && data.firstFloorPost.id != 0L -> data.firstFloorPost
            else -> data.postListList.firstOrNull { it.floor == 1 && it.id != 0L }
        }
        val posts = data.postListList
            .filter(::isVisiblePost)
            .map { post(it, users, mappedThread.id) }
            .map { enrichAuthorIp(it, mappedThread) }
        val mainPost = rawMain?.let { enrichAuthorIp(post(it, users, mappedThread.id), mappedThread) }
        return ThreadPage(
            thread = mappedThread,
            forum = forum,
            mainPost = mainPost,
            posts = posts,
            currentPage = data.page.currentPage,
            totalPage = data.page.totalPage,
            hasMore = data.page.currentPage < data.page.totalPage || data.page.hasMore != 0,
            isCollected = data.thread.collectStatus != 0,
        )
    }

    fun subposts(
        response: PbFloorResponseOuterClass.PbFloorResponse,
        requestedThreadId: Long,
        requestedPage: Int,
    ): SubpostPage {
        validate(response.error)
        if (!response.hasData() || !response.data.hasPost()) throw TiebaApiException.EmptyResponse
        val data = response.data
        val users = buildMap {
            data.subpostListList.forEach { if (it.hasAuthor() && it.author.id > 0) put(it.author.id, it.author) }
            if (data.post.hasAuthor() && data.post.author.id > 0) put(data.post.author.id, data.post.author)
        }
        val threadId = data.thread.id.takeIf { it > 0 } ?: requestedThreadId
        return SubpostPage(
            parentPost = post(data.post, users, threadId),
            subposts = data.subpostListList.map { subpost(it, users) },
            currentPage = data.page.currentPage.takeIf { it > 0 } ?: requestedPage,
            totalPage = data.page.totalPage,
            hasMore = data.page.currentPage < data.page.totalPage || data.page.hasMore != 0,
        )
    }

    fun userProfile(
        response: ProfileProtocol.UserProfileResponse,
        fallback: UserSummary,
        isCurrentUser: Boolean,
    ): UserProfile {
        validate(response.error)
        if (!response.hasData() || !response.data.hasUser()) throw TiebaApiException.EmptyResponse
        val proto = response.data.user
        val mapped = user(proto, fallback.id)
        val resolved = mapped.copy(
            name = firstNonBlank(mapped.name, fallback.name),
            displayName = firstNonBlank(mapped.displayName, fallback.displayName, mapped.name),
            portrait = TiebaRemoteUrl.portrait(firstNonBlank(mapped.portrait, fallback.portrait)),
            level = mapped.level ?: fallback.level,
            levelName = mapped.levelName ?: fallback.levelName,
            ipAddress = mapped.ipAddress ?: fallback.ipAddress,
        )
        val forums = proto.likeForumList.mapNotNull { item ->
            val name = item.forumName.trim()
            if (name.isEmpty()) null else Forum(
                id = item.forumId.toLong(),
                name = name,
                displayName = displayForumName(name),
            )
        }.distinctBy { if (it.id != 0L) "id:${it.id}" else "name:${it.name.lowercase()}" }
        val declaredForumCount = maxOf(proto.myLikeNum, forums.size)
        val privacyValue = if (proto.hasPrivSets()) proto.privSets.like else 0
        return UserProfile(
            user = resolved,
            isCurrentUser = isCurrentUser,
            isFollowed = proto.hasConcerned != 0,
            tiebaId = firstNonBlank(proto.tiebaUid, proto.id.takeIf { it != 0L }?.toString().orEmpty()),
            tiebaAge = proto.tbAge,
            sex = sex(proto.sex.takeIf { it != 0 } ?: proto.gender),
            location = firstNonBlankOrNull(proto.ipAddress, proto.ip, fallback.ipAddress),
            intro = firstNonBlank(proto.displayIntro, proto.intro),
            backgroundUrl = TiebaRemoteUrl.normalize(proto.bgPic),
            agreeCount = maxOf(proto.totalAgreeNum.toInt(), proto.agreeNum),
            followingCount = proto.concernNum.coerceAtLeast(0),
            followerCount = proto.fansNum.coerceAtLeast(0),
            threadCount = proto.threadNum.coerceAtLeast(0),
            followedForumCount = declaredForumCount.coerceAtLeast(0),
            followedForums = forums,
            followedForumsVisibility = if (isCurrentUser || privacyValue == 0 || declaredForumCount == 0 || forums.isNotEmpty()) {
                UserContentVisibility.Visible
            } else {
                UserContentVisibility.Private
            },
        )
    }

    fun userThreads(
        response: ProfileProtocol.UserThreadsResponse,
        page: Int,
    ): UserThreadsPage {
        validate(response.error)
        if (!response.hasData()) throw TiebaApiException.EmptyResponse
        val raw = response.data.postListList
        val mapped = raw.mapNotNull(::userThread)
        val targets = raw.mapNotNull { item ->
            if (item.forumId == 0L || item.threadId == 0L || item.postId == 0L) null else {
                item.threadId to OwnThreadDeletionTarget(
                    forumId = item.forumId.toLong(),
                    forumName = item.forumName,
                    threadId = item.threadId.toLong(),
                    firstPostId = item.postId.toULong(),
                )
            }
        }.toMap()
        return UserThreadsPage(
            threads = mapped,
            currentPage = page,
            hasMore = response.data.hidePost == 0 && raw.isNotEmpty(),
            visibility = if (response.data.hidePost == 0) UserContentVisibility.Visible else UserContentVisibility.Private,
            deletionTargetsByThreadId = targets,
        )
    }

    fun thread(proto: ThreadInfo, usersById: Map<Long, User>): ThreadSummary {
        val author = user(if (proto.hasAuthor()) proto.author else User.getDefaultInstance(), proto.authorId, usersById[proto.authorId])
        val blocks = blocks(proto.firstPostContentList).toMutableList()
        appendUniqueVoices(blocks, proto.voiceInfoList)
        proto.mediaList.mapNotNull(::image).forEach { candidate ->
            if (blocks.none { sameImage(it, candidate) }) blocks += candidate
        }
        if (proto.hasVideoInfo()) {
            video(proto.videoInfo)?.let { candidate ->
                val index = blocks.indexOfFirst { it is ContentBlock.Video }
                if (index < 0) blocks += candidate else blocks[index] = mergeVideo(blocks[index], candidate)
            }
        }
        val forumId = proto.forumId.takeIf { it != 0L }
            ?: proto.forumInfo.id.takeIf { proto.hasForumInfo() && it != 0L }
        return ThreadSummary(
            id = proto.id.takeIf { it != 0L } ?: proto.threadId,
            forumId = forumId,
            title = proto.title,
            author = author,
            forumName = firstNonBlankOrNull(proto.forumName, proto.forumInfo.name.takeIf { proto.hasForumInfo() }),
            forumAvatarUrl = TiebaRemoteUrl.normalize(proto.forumInfo.avatar.takeIf { proto.hasForumInfo() }),
            replyCount = proto.replyNum,
            viewCount = proto.viewNum,
            likeCount = if (proto.agree.agreeNum != 0L) proto.agree.agreeNum.toInt() else proto.agreeNum,
            firstPostId = proto.firstPostId.takeIf { it > 0 }?.toULong(),
            isLiked = proto.agree.hasAgree != 0,
            createdAtEpochSeconds = proto.createTime.takeIf { it != 0 }?.toLong(),
            lastReplyAtEpochSeconds = proto.lastTimeInt.takeIf { it != 0 }?.toLong(),
            blocks = blocks,
            isTop = proto.isTop != 0,
            isGood = proto.isGood != 0,
            hasVideo = proto.hasVideoInfo() || blocks.any { it is ContentBlock.Video },
        )
    }

    private fun userThread(item: ProfileProtocol.UserThreadItem): ThreadSummary? {
        if (item.threadId == 0L) return null
        val content = blocks(item.firstPostContentList).ifEmpty { blocks(item.richAbstractList) }.toMutableList()
        appendUniqueVoices(content, item.voiceInfoList)
        if (content.isEmpty()) {
            val fallback = item.abstractThreadList.joinToString("") { it.text }.ifBlank { item.contentThread }
            if (fallback.isNotBlank()) content += ContentBlock.Text(fallback)
        }
        item.mediaList.mapNotNull(::image).forEach { candidate ->
            if (content.none { sameImage(it, candidate) }) content += candidate
        }
        val richTitle = blocks(item.richTitleList).joinToString("") { plainText(it) }
        val authorName = firstNonBlank(item.nameShow, item.userName, item.userId.takeIf { it != 0L }?.let { "用户$it" }.orEmpty())
        return ThreadSummary(
            id = item.threadId.toLong(),
            forumId = item.forumId.takeIf { it != 0L }?.toLong(),
            title = firstNonBlank(item.title, richTitle),
            author = UserSummary(
                id = item.userId,
                name = firstNonBlank(item.userName, authorName),
                displayName = authorName,
                portrait = TiebaRemoteUrl.portrait(item.userPortrait),
                ipAddress = firstNonBlankOrNull(item.ip),
            ),
            forumName = firstNonBlankOrNull(item.forumName),
            replyCount = item.replyNum,
            viewCount = item.viewNum.coerceAtLeast(0),
            likeCount = item.agreeNum.coerceAtLeast(0),
            firstPostId = item.postId.takeIf { it != 0L }?.toULong(),
            createdAtEpochSeconds = item.createTime.takeIf { it != 0 }?.toLong(),
            blocks = content,
        )
    }

    private fun post(proto: ProtoPost, usersById: Map<Long, User>, threadId: Long): Post {
        val author = user(if (proto.hasAuthor()) proto.author else User.getDefaultInstance(), proto.authorId, usersById[proto.authorId])
        return Post(
            id = proto.id.toULong(),
            threadId = threadId.takeIf { it != 0L } ?: proto.tid,
            floor = proto.floor,
            author = author,
            ipAddress = firstNonBlankOrNull(author.ipAddress, proto.lbsInfo.name),
            createdAtEpochSeconds = proto.time.takeIf { it != 0 }?.toLong(),
            blocks = blocks(proto.contentList),
            subpostCount = proto.subPostNumber,
            likeCount = when {
                proto.agree.agreeNum != 0L -> proto.agree.agreeNum.toInt()
                proto.postZan.zanNum != 0L -> proto.postZan.zanNum.toInt()
                else -> proto.zan.num
            },
            isLiked = proto.agree.hasAgree != 0,
            previewSubposts = proto.subPostList.subPostListList.filter(::isVisibleSubpost).map { subpost(it, usersById) },
        )
    }

    private fun subpost(proto: SubPostList, usersById: Map<Long, User>): Subpost {
        val author = user(if (proto.hasAuthor()) proto.author else User.getDefaultInstance(), proto.authorId, usersById[proto.authorId])
        return Subpost(
            id = proto.id.toULong(),
            floor = proto.floor,
            author = author,
            ipAddress = firstNonBlankOrNull(author.ipAddress, proto.location.name),
            blocks = resolveReplyTarget(blocks(proto.contentList), usersById),
            createdAtEpochSeconds = proto.time.takeIf { it != 0 }?.toLong(),
            likeCount = proto.agree.agreeNum.toInt(),
            isLiked = proto.agree.hasAgree != 0,
        )
    }

    private fun blocks(contents: List<PbContent>): List<ContentBlock> {
        val seenVoices = mutableSetOf<String>()
        return contents.flatMap(::blocks).filter { block ->
            block !is ContentBlock.Voice || seenVoices.add(block.value.md5)
        }
    }

    private fun blocks(content: PbContent): List<ContentBlock> = when (content.type) {
        0, 9, 27 -> content.text.takeIf(String::isNotBlank)?.let { listOf(ContentBlock.Text(it)) }.orEmpty()
        1 -> listOf(ContentBlock.Link(content.text, TiebaRemoteUrl.normalize(content.link)))
        2 -> firstNonBlank(content.c, content.text).takeIf(String::isNotBlank)?.let { listOf(ContentBlock.Emoticon(it)) }.orEmpty()
        3, 20 -> image(content)?.let(::listOf).orEmpty()
        4 -> listOf(ContentBlock.Mention(content.uid.takeIf { it != 0L }, content.text))
        5 -> listOf(ContentBlock.Video(VideoContent(
            videoUrl = TiebaRemoteUrl.normalize(content.link),
            coverUrl = TiebaRemoteUrl.normalize(firstNonBlank(content.src, content.cdnSrc)),
            webUrl = TiebaRemoteUrl.normalize(content.text),
            width = parseSize(content.bsize, content.width, content.height).first,
            height = parseSize(content.bsize, content.width, content.height).second,
            durationSeconds = content.duringTime,
        )))
        10 -> VoiceContent.create(content.voiceMD5, content.duringTime)?.let { listOf(ContentBlock.Voice(it)) }.orEmpty()
        else -> content.text.takeIf(String::isNotBlank)?.let { listOf(ContentBlock.Text(it)) }.orEmpty()
    }

    private fun image(content: PbContent): ContentBlock.Image? {
        val size = parseSize(content.bsize, content.width, content.height)
        val thumbnail = TiebaRemoteUrl.normalize(firstNonBlank(
            content.cdnSrc, content.cdnSrcActive, content.bigCdnSrc, content.bigSrc,
            content.dynamic, content.src, content.originSrc,
        ))
        val original = TiebaRemoteUrl.normalize(firstNonBlank(
            content.originSrc, content.bigCdnSrc, content.bigSrc, content.cdnSrc, content.src,
        ))
        if (thumbnail == null && original == null) return null
        return ContentBlock.Image(ImageContent(
            thumbnailUrl = thumbnail,
            originalUrl = original,
            width = size.first,
            height = size.second,
            showOriginalButton = content.showOriginalBtn == 1,
            originalSizeBytes = content.originSize.toUnsignedLongOrNull(),
        ))
    }

    private fun image(media: Media): ContentBlock.Image? {
        val thumbnail = TiebaRemoteUrl.normalize(firstNonBlank(media.bigPic, media.dynamicPic, media.srcPic, media.originPic))
        val original = TiebaRemoteUrl.normalize(firstNonBlank(media.originPic, media.bigPic, media.dynamicPic, media.srcPic))
        if (thumbnail == null && original == null) return null
        return ContentBlock.Image(ImageContent(
            thumbnailUrl = thumbnail,
            originalUrl = original,
            width = media.width,
            height = media.height,
            showOriginalButton = media.showOriginalBtn == 1,
            originalSizeBytes = media.originSize.toUnsignedLongOrNull(),
        ))
    }

    private fun video(proto: VideoInfo): ContentBlock.Video? {
        val url = TiebaRemoteUrl.normalize(proto.videoUrl)
        val cover = TiebaRemoteUrl.normalize(proto.thumbnailUrl)
        if (url == null && cover == null) return null
        return ContentBlock.Video(VideoContent(url, cover, null, proto.videoWidth, proto.videoHeight, proto.videoDuration))
    }

    private fun user(proto: User, fallbackId: Long = 0, fallback: User? = null): UserSummary {
        val id = proto.id.takeIf { it != 0L } ?: fallback?.id?.takeIf { it != 0L } ?: fallbackId
        val name = firstNonBlank(proto.name, fallback?.name, if (id == 0L) "未知用户" else "用户$id")
        val displayName = firstNonBlank(proto.nameShow, fallback?.nameShow, name)
        return UserSummary(
            id = id,
            name = name,
            displayName = displayName,
            portrait = TiebaRemoteUrl.portrait(
                firstNonBlank(proto.portrait, proto.portraith, fallback?.portrait, fallback?.portraith),
            ),
            level = firstNonZero(proto.levelId, fallback?.levelId),
            levelName = firstNonBlankOrNull(proto.levelName, fallback?.levelName),
            ipAddress = firstNonBlankOrNull(proto.ipAddress, proto.ip, fallback?.ipAddress, fallback?.ip),
        )
    }

    private fun forum(proto: tieba.SimpleForumOuterClass.SimpleForum): Forum = Forum(
        id = proto.id,
        name = proto.name,
        displayName = displayForumName(proto.name),
        avatarUrl = TiebaRemoteUrl.normalize(proto.avatar),
        memberCount = proto.memberNum,
        threadCount = proto.postNum,
    )

    private fun validate(error: Error) {
        TiebaResponseValidator.validate(error.errorCode, firstNonBlank(error.userMsg, error.errorMsg))
    }

    private fun isVisibleThread(thread: ThreadInfo): Boolean =
        (thread.id != 0L || thread.threadId != 0L) && thread.isDeleted == 0 && !thread.hasAlaInfo() && !thread.hasTwzhiboInfo()

    private fun isVisiblePost(post: ProtoPost): Boolean = post.id != 0L && post.isFold == 0 && !post.hasAdvertisement()

    private fun isVisibleSubpost(post: SubPostList): Boolean = post.id != 0L

    private fun enrichAuthorIp(post: Post, thread: ThreadSummary): Post =
        if (post.ipAddress.isNullOrBlank() && post.author.id != 0L && post.author.id == thread.author.id && !thread.author.ipAddress.isNullOrBlank()) {
            post.copy(ipAddress = thread.author.ipAddress)
        } else post

    private fun appendUniqueVoices(blocks: MutableList<ContentBlock>, voices: List<Voice>) {
        val seen = blocks.filterIsInstance<ContentBlock.Voice>().mapTo(mutableSetOf()) { it.value.md5 }
        voices.forEach { voice ->
            VoiceContent.create(voice.voiceMd5, voice.duringTime)?.let {
                if (seen.add(it.md5)) blocks += ContentBlock.Voice(it)
            }
        }
    }

    private fun sameImage(left: ContentBlock, right: ContentBlock): Boolean {
        if (left !is ContentBlock.Image || right !is ContentBlock.Image) return false
        val a = listOfNotNull(left.value.thumbnailUrl, left.value.originalUrl)
        val b = listOfNotNull(right.value.thumbnailUrl, right.value.originalUrl)
        return a.any(b::contains)
    }

    private fun mergeVideo(existing: ContentBlock, new: ContentBlock.Video): ContentBlock.Video {
        val old = (existing as? ContentBlock.Video)?.value ?: return new
        val value = new.value
        return ContentBlock.Video(VideoContent(
            videoUrl = value.videoUrl ?: old.videoUrl,
            coverUrl = old.coverUrl ?: value.coverUrl,
            webUrl = old.webUrl ?: value.webUrl,
            width = firstPositive(old.width, value.width),
            height = firstPositive(old.height, value.height),
            durationSeconds = firstPositive(old.durationSeconds, value.durationSeconds),
        ))
    }

    private fun resolveReplyTarget(blocks: List<ContentBlock>, users: Map<Long, User>): List<ContentBlock> {
        val first = blocks.firstOrNull() as? ContentBlock.Text ?: return blocks
        val match = REPLY_PREFIX.find(first.value) ?: return blocks
        val shown = match.groupValues[1].trim()
        if (shown.isEmpty()) return blocks
        val normalized = normalizeName(shown)
        val matches = users.values.filter { normalizeName(it.nameShow) == normalized || normalizeName(it.name) == normalized }
            .map(User::getId).filter { it > 0 }.distinct()
        val replacement = buildList {
            if (match.range.first > 0) add(ContentBlock.Text(first.value.substring(0, match.range.first)))
            add(ContentBlock.Text(match.value.substringBefore(shown)))
            add(ContentBlock.Mention(matches.singleOrNull(), shown))
            val delimiterAndRest = match.value.substring(match.value.indexOf(shown) + shown.length)
            add(ContentBlock.Text(delimiterAndRest + first.value.substring(match.range.last + 1)))
        }
        return replacement + blocks.drop(1)
    }

    private fun parseSize(value: String, fallbackWidth: Int, fallbackHeight: Int): Pair<Int, Int> {
        val parts = value.split(',').mapNotNull { it.trim().toIntOrNull() }
        return (parts.getOrNull(0) ?: fallbackWidth) to (parts.getOrNull(1) ?: fallbackHeight)
    }

    private fun plainText(block: ContentBlock): String = when (block) {
        is ContentBlock.Text -> block.value
        is ContentBlock.Link -> block.title
        is ContentBlock.Mention -> block.text
        is ContentBlock.Emoticon -> block.code
        is ContentBlock.Voice -> "[语音]"
        else -> ""
    }

    private fun sex(value: Int): UserProfileSex = when (value) {
        1 -> UserProfileSex.Male
        2 -> UserProfileSex.Female
        else -> UserProfileSex.Unspecified
    }

    private fun displayForumName(name: String) = if (name.endsWith("吧")) name else "${name}吧"
    private fun firstNonBlank(vararg values: String?): String = values.firstOrNull { !it.isNullOrBlank() }.orEmpty()
    private fun firstNonBlankOrNull(vararg values: String?): String? = firstNonBlank(*values).ifBlank { null }
    private fun firstNonZero(vararg values: Int?): Int? = values.firstOrNull { it != null && it != 0 }
    private fun firstPositive(vararg values: Int): Int = values.firstOrNull { it > 0 } ?: 0
    private fun normalizeName(value: String) = value.trim().trimStart('@').trim().lowercase()
    private fun Int.toUnsignedLongOrNull(): Long? = (toLong() and 0xffffffffL).takeIf { it > 0 }

    private val REPLY_PREFIX = Regex("^回复\\s*([^:：]+)[:：]")
}

internal object TiebaRemoteUrl {
    private val portraitBase = "https://himg.bdimg.com/sys/portrait/item/".toHttpUrl()

    fun normalize(raw: String?): String? {
        var value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (value.startsWith("//")) value = "https:$value"
        if (value.startsWith("http://", ignoreCase = true)) value = "https://${value.substring(7)}"
        return try {
            val uri = URI(value)
            val host = uri.host?.trimEnd('.')?.lowercase().orEmpty()
            if (uri.scheme?.lowercase() != "https" || host.isEmpty() || uri.userInfo != null || isPrivateHost(host)) null else uri.toASCIIString()
        } catch (_: Exception) {
            null
        }
    }

    fun portrait(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || value.any { it == '\r' || it == '\n' || it.code < 0x20 || it.code == 0x7f }) {
            return ""
        }
        if (value.startsWith("https://", ignoreCase = true) ||
            value.startsWith("http://", ignoreCase = true) || value.startsWith("//")
        ) {
            val normalized = normalize(value) ?: return ""
            return normalized.replace(
                oldValue = "https://tb.himg.baidu.com/",
                newValue = "https://himg.bdimg.com/",
                ignoreCase = true,
            )
        }
        val token = value.substringBefore('?').trim()
        if (token.isEmpty()) return ""
        return portraitBase.newBuilder().addPathSegment(token).build().toString()
    }

    fun portraitToken(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || value.any { it == '\r' || it == '\n' || it.code < 0x20 || it.code == 0x7f }) return null
        if (!value.contains("://") && !value.startsWith("//")) return value.substringBefore('?').takeIf(String::isNotBlank)
        val normalized = normalize(value) ?: return null
        return try {
            val uri = URI(normalized)
            if ((uri.host.equals("tb.himg.baidu.com", ignoreCase = true) ||
                    uri.host.equals("himg.bdimg.com", ignoreCase = true)) &&
                uri.path.startsWith("/sys/portrait/item/")
            ) {
                uri.path.removePrefix("/sys/portrait/item/").takeIf { it.isNotBlank() && '/' !in it }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isPrivateHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) return true
        val parts = host.split('.').mapNotNull(String::toIntOrNull)
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        return parts[0] == 0 || parts[0] == 10 || parts[0] == 127 ||
            (parts[0] == 169 && parts[1] == 254) || (parts[0] == 172 && parts[1] in 16..31) ||
            (parts[0] == 192 && parts[1] == 168) || parts[0] >= 224
    }
}
