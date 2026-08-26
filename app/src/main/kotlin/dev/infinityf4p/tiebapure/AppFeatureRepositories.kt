package dev.infinityf4p.tiebapure

import dev.infinityf4p.tiebapure.core.data.AccountMutationRepository
import dev.infinityf4p.tiebapure.core.data.AccountRepository
import dev.infinityf4p.tiebapure.core.data.AppSettings
import dev.infinityf4p.tiebapure.core.data.BrowsingHistoryEntity
import dev.infinityf4p.tiebapure.core.data.ReadingPositionEntity
import dev.infinityf4p.tiebapure.core.data.RecentForumEntity
import dev.infinityf4p.tiebapure.core.data.SearchHistoryEntity
import dev.infinityf4p.tiebapure.core.data.TiebaPureDatabase
import dev.infinityf4p.tiebapure.core.data.TiebaRepositories
import dev.infinityf4p.tiebapure.core.data.ThreadRepository as DataThreadRepository
import dev.infinityf4p.tiebapure.core.model.Account
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionKind
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionTarget
import dev.infinityf4p.tiebapure.core.model.Forum
import dev.infinityf4p.tiebapure.core.model.ForumMembership
import dev.infinityf4p.tiebapure.core.model.ThreadPage
import dev.infinityf4p.tiebapure.core.model.ThreadReplySort
import dev.infinityf4p.tiebapure.core.model.ThreadSummary
import dev.infinityf4p.tiebapure.core.model.TiebaBlocklistSnapshot
import dev.infinityf4p.tiebapure.core.model.TiebaContentFilterPolicy
import dev.infinityf4p.tiebapure.feature.forum.ForumHubRepository
import dev.infinityf4p.tiebapure.feature.forum.ForumFollowAvailability
import dev.infinityf4p.tiebapure.feature.forum.ForumInfoRepository
import dev.infinityf4p.tiebapure.feature.forum.ForumInteractionPort
import dev.infinityf4p.tiebapure.feature.forum.ForumThreadsRepository
import dev.infinityf4p.tiebapure.feature.home.HomeFeedPage
import dev.infinityf4p.tiebapure.feature.home.HomeRepository
import dev.infinityf4p.tiebapure.feature.search.SearchFilter
import dev.infinityf4p.tiebapure.feature.search.SearchItem
import dev.infinityf4p.tiebapure.feature.search.SearchPage
import dev.infinityf4p.tiebapure.feature.search.SearchRepository
import dev.infinityf4p.tiebapure.feature.search.SearchScope
import dev.infinityf4p.tiebapure.feature.search.SearchSort
import dev.infinityf4p.tiebapure.feature.thread.ThreadRepository
import dev.infinityf4p.tiebapure.feature.thread.ThreadLikeTarget
import dev.infinityf4p.tiebapure.feature.thread.ThreadMainPostFallback
import dev.infinityf4p.tiebapure.feature.thread.ThreadReadingPosition
import dev.infinityf4p.tiebapure.feature.thread.ThreadReplyTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class AppFeatureRepositories(
    private val repositories: TiebaRepositories,
    private val database: TiebaPureDatabase,
    private val account: () -> Account?,
    private val accountRepository: AccountRepository,
    private val mutationRepository: AccountMutationRepository,
    private val settings: () -> AppSettings,
    private val applicationScope: CoroutineScope,
) {
    private val forumIdsByThread = ConcurrentHashMap<Long, Long>()
    private val threadPagesById = ConcurrentHashMap<Long, dev.infinityf4p.tiebapure.core.model.ThreadPage>()
    private val threadSummariesById = ConcurrentHashMap<Long, ThreadSummary>()
    private val readingPositionMutex = Mutex()
    private val readingPositionGenerations = ConcurrentHashMap<Long, Long>()

    val home: HomeRepository = object : HomeRepository {
        override suspend fun loadFeed(page: Int): HomeFeedPage {
            var requestedPage = page.coerceAtLeast(1)
            repeat(MAXIMUM_FILTERED_PAGE_SCAN) {
                val raw = repositories.home.threads(
                    account = account(),
                    page = requestedPage,
                    loadType = if (requestedPage == 1) 1 else 2,
                )
                raw.forEach(::rememberThreadSummary)
                val filtered = filterThreads(raw, blockedEntries())
                if (filtered.isNotEmpty() || raw.isEmpty()) {
                    return HomeFeedPage(filtered, requestedPage, raw.isNotEmpty())
                }
                requestedPage += 1
            }
            return HomeFeedPage(emptyList(), requestedPage - 1, true)
        }
    }

    val forumHub: ForumHubRepository = object : ForumHubRepository {
        override suspend fun followedForums(): List<Forum> {
            val blocklist = blockedEntries()
            return account()?.let { accountRepository.followedForums(it) }
                .orEmpty()
                .filter { TiebaContentFilterPolicy.shouldKeep(it, blocklist) }
        }

        override fun recentForums() = combine(
            database.recentForumDao().observeAll(),
            database.blocklistDao().observeAll(),
        ) { entities, blocked ->
            val blocklist = blocked.toBlocklistSnapshot()
            entities
                .map(RecentForumEntity::toForum)
                .filter { TiebaContentFilterPolicy.shouldKeep(it, blocklist) }
        }

        override suspend fun recordRecent(forum: Forum) {
            val dao = database.recentForumDao()
            val initial = recentForumEntity(forum, visitedAtMilliseconds = System.currentTimeMillis()) ?: return
            val existing = dao.load(initial.normalizedName)
            dao.upsert(recentForumEntity(forum, existing, initial.visitedAtMilliseconds) ?: return)
        }

        override suspend fun removeRecent(forum: Forum) {
            database.recentForumDao().remove(forum.name.trim().removeSuffix("吧").trim().lowercase())
        }

        override suspend fun clearRecent() = database.recentForumDao().clear()
    }

    val forumInteraction: ForumInteractionPort = object : ForumInteractionPort {
        override val followAvailability: ForumFollowAvailability
            get() = if (account() == null) ForumFollowAvailability.LoginRequired else ForumFollowAvailability.Available

        override suspend fun resolveForumId(forum: Forum): Long =
            forum.id.takeIf { it > 0 }
                ?: accountRepository.resolveForumId(forum.name.trim().removeSuffix("吧").trim())

        override suspend fun forumMembership(forum: Forum): ForumMembership {
            val forumId = resolveForumId(forum)
            return withRequiredAccount { current -> accountRepository.forumMembership(current, forumId) }
        }

        override suspend fun setForumFollowed(forum: Forum, followed: Boolean): ForumMembership {
            val resolved = forum.copy(id = resolveForumId(forum))
            return withRequiredAccount { current ->
                mutationRepository.setForumFollowed(current, resolved, followed)
            }
        }
    }

    val forumThreads: ForumThreadsRepository = ForumThreadsRepository { forum, page, category ->
        var requestedPage = page.coerceAtLeast(1)
        repeat(MAXIMUM_FILTERED_PAGE_SCAN) {
            val result = repositories.forum.threads(forum.name, requestedPage, category, account())
            result.threads.forEach(::rememberThreadSummary)
            val filtered = filterThreads(result.threads, blockedEntries())
            if (filtered.isNotEmpty() || result.threads.isEmpty() || !result.hasMore) {
                return@ForumThreadsRepository result.copy(threads = filtered, currentPage = requestedPage)
            }
            requestedPage += 1
        }
        repositories.forum.threads(forum.name, requestedPage, category, account()).let {
            it.copy(threads = filterThreads(it.threads, blockedEntries()), currentPage = requestedPage)
        }
    }

    val forumInfo: ForumInfoRepository = ForumInfoRepository { forum ->
        repositories.forum.info(forum.name)
    }

    val search: SearchRepository = object : SearchRepository {
        override suspend fun search(
            keyword: String,
            scope: SearchScope,
            filter: SearchFilter,
            sort: SearchSort,
            page: Int,
        ): SearchPage {
            val forumName = (scope as? SearchScope.ForumOnly)?.forum?.name
            val blocked = blockedEntries()
            if (filter == SearchFilter.Forums) {
                check(scope == SearchScope.Global) { "Forum search is only available globally" }
                val forumPage = repositories.search.forums(keyword, page)
                return SearchPage(
                    items = forumPage.results
                        .filter { TiebaContentFilterPolicy.shouldKeep(it.forum, blocked) }
                        .map { SearchItem.ForumResult(it) },
                    currentPage = forumPage.currentPage,
                    hasMore = forumPage.hasMore,
                )
            }
            val threadPage = repositories.search.threads(
                keyword = keyword,
                page = page,
                sortType = sort.protocolValue,
                filterType = requireNotNull(filter.protocolValue),
                forumName = forumName,
            )
            val threads = filterThreads(threadPage.results.map { it.thread }, blocked)
            val allowedThreadIds = threads.mapTo(hashSetOf(), ThreadSummary::id)
            val items = buildList {
                if (filter == SearchFilter.All && page == 1 && forumName == null) {
                    repositories.search.users(keyword).results
                        .filter { TiebaContentFilterPolicy.shouldKeep(it.user, blocked) }
                        .forEach { add(SearchItem.UserResult(it.user)) }
                }
                threadPage.results
                    .filter { it.thread.id in allowedThreadIds }
                    .forEach { add(SearchItem.ThreadResult(it.thread, it.matchedPostId)) }
            }
            return SearchPage(items, threadPage.currentPage, threadPage.hasMore)
        }

        override suspend fun history(): List<String> = database.searchHistoryDao()
            .observeAll()
            .first()
            .map(SearchHistoryEntity::keyword)

        override suspend fun recordHistory(keyword: String) {
            val normalized = keyword.trim()
            if (normalized.isNotEmpty()) {
                database.searchHistoryDao().upsert(SearchHistoryEntity(normalized, System.currentTimeMillis()))
            }
        }

        override suspend fun removeHistory(keyword: String) = database.searchHistoryDao().remove(keyword)

        override suspend fun clearHistory() = database.searchHistoryDao().clear()
    }

    val thread: ThreadRepository = object : ThreadRepository {
        override fun mainPostFallback(threadId: Long): ThreadMainPostFallback? =
            ThreadMainPostFallback.from(threadSummariesById[threadId])

        override suspend fun threadPage(
            threadId: Long,
            page: Int,
            sort: dev.infinityf4p.tiebapure.core.model.ThreadReplySort,
            onlyThreadAuthor: Boolean,
        ): dev.infinityf4p.tiebapure.core.model.ThreadPage {
            val result = TiebaContentFilterPolicy.filter(
                repositories.thread.page(
                    threadId = threadId,
                    page = page,
                    onlyThreadAuthor = onlyThreadAuthor,
                    sort = sort,
                    account = account(),
                ),
                blockedEntries(),
            )
            rememberThreadSummary(result.thread)
            result.forum.id.takeIf { it > 0 }?.let { forumIdsByThread[threadId] = it }
            threadPagesById[threadId] = result
            if (page == 1) {
                database.browsingHistoryDao().upsert(
                    BrowsingHistoryEntity(
                        threadId = result.thread.id,
                        title = result.thread.title.ifBlank { result.thread.textPreview },
                        authorName = result.thread.author.resolvedDisplayName,
                        forumName = result.forum.name.ifBlank { result.thread.forumName },
                        visitedAtMilliseconds = System.currentTimeMillis(),
                    ),
                )
            }
            return result
        }

        override suspend fun subpostPage(
            parentPost: dev.infinityf4p.tiebapure.core.model.Post,
            page: Int,
        ) = TiebaContentFilterPolicy.filter(
            repositories.thread.subposts(
                threadId = parentPost.threadId,
                postId = parentPost.id,
                forumId = forumIdsByThread[parentPost.threadId]
                    ?: error("帖子所属贴吧尚未加载，请刷新后重试"),
                page = page,
                account = account(),
            ),
            blockedEntries(),
        )

        override suspend fun threadPageAround(
            threadId: Long,
            postId: ULong,
            sort: dev.infinityf4p.tiebapure.core.model.ThreadReplySort,
            onlyThreadAuthor: Boolean,
        ): dev.infinityf4p.tiebapure.core.model.ThreadPage {
            val result = TiebaContentFilterPolicy.filter(
                repositories.thread.pageAroundPost(
                    threadId = threadId,
                    postId = postId,
                    onlyThreadAuthor = onlyThreadAuthor,
                    sort = sort,
                    account = account(),
                ),
                blockedEntries(),
            )
            rememberThreadSummary(result.thread)
            result.forum.id.takeIf { it > 0 }?.let { forumIdsByThread[threadId] = it }
            threadPagesById[threadId] = result
            return result
        }

        override suspend fun loadReadingPosition(threadId: Long): ThreadReadingPosition? =
            database.readingPositionDao().load(threadId)?.let { entity ->
                val postId = entity.postId?.toULongOrNull() ?: return@let null
                val floor = entity.floor ?: return@let null
                if (postId == 0uL || floor <= 1) null else ThreadReadingPosition(postId, floor)
            }

        override suspend fun saveReadingPosition(threadId: Long, position: ThreadReadingPosition) {
            require(threadId > 0 && position.postId > 0uL && position.floor > 1) {
                "阅读位置无效。"
            }
            val generation = nextReadingPositionGeneration(threadId)
            persistReadingPositionIfCurrent(threadId, position, generation)
        }

        override fun scheduleReadingPositionSave(threadId: Long, position: ThreadReadingPosition) {
            if (threadId <= 0 || position.postId == 0uL || position.floor <= 1) return
            val generation = nextReadingPositionGeneration(threadId)
            applicationScope.launch {
                runCatching { persistReadingPositionIfCurrent(threadId, position, generation) }
            }
        }

        override suspend fun removeReadingPosition(threadId: Long) {
            require(threadId > 0) { "帖子 ID 无效。" }
            val generation = nextReadingPositionGeneration(threadId)
            readingPositionMutex.withLock {
                if (readingPositionGenerations[threadId] == generation) {
                    database.readingPositionDao().remove(threadId)
                }
            }
        }

        override suspend fun setLiked(threadId: Long, target: ThreadLikeTarget, liked: Boolean) {
            check(settings().likingEnabled) { "请先在设置中开启允许点赞。" }
            withRequiredAccount { current ->
                mutationRepository.setPostLiked(
                    account = current,
                    threadId = threadId,
                    postId = target.postId,
                    objectType = target.objectType,
                    liked = liked,
                )
            }
        }

        override suspend fun setCollected(threadId: Long, markedPostId: ULong, collected: Boolean) {
            withRequiredAccount { current ->
                mutationRepository.setThreadFavorite(current, threadId, markedPostId, collected)
            }
        }
    }

    fun rememberThreadSummary(thread: ThreadSummary) {
        if (thread.id <= 0) return
        synchronized(threadSummariesById) {
            val previous = threadSummariesById[thread.id]
            threadSummariesById[thread.id] = when {
                previous == null -> thread
                previous.blocks.isNotEmpty() && thread.blocks.isEmpty() -> previous
                else -> thread
            }
        }
    }

    fun contentSubmissionTarget(threadId: Long, reply: ThreadReplyTarget): ContentSubmissionTarget? {
        val page = threadPagesById[threadId] ?: return null
        val forumId = page.forum.id.takeIf { it > 0 } ?: forumIdsByThread[threadId] ?: return null
        val forumName = page.forum.name.trim().removeSuffix("吧").trim().takeIf(String::isNotEmpty) ?: return null
        return when (reply) {
            ThreadReplyTarget.Thread -> ContentSubmissionTarget(
                kind = ContentSubmissionKind.ThreadReply,
                forumId = forumId,
                forumName = forumName,
                threadId = threadId,
            )
            is ThreadReplyTarget.Floor -> ContentSubmissionTarget(
                kind = ContentSubmissionKind.PostReply,
                forumId = forumId,
                forumName = forumName,
                threadId = threadId,
                parentPostId = reply.post.id,
                parentFloor = reply.post.floor,
                replyUser = reply.post.author,
            )
            is ThreadReplyTarget.Nested -> ContentSubmissionTarget(
                kind = ContentSubmissionKind.SubpostReply,
                forumId = forumId,
                forumName = forumName,
                threadId = threadId,
                parentPostId = reply.parent.id,
                parentFloor = reply.parent.floor,
                subpostId = reply.subpost.id,
                replyUser = reply.subpost.author,
            )
        }
    }

    private suspend fun <T> withRequiredAccount(block: suspend (Account) -> T): T {
        val snapshot = account() ?: error("请先登录贴吧账号后再执行此操作。")
        val result = block(snapshot)
        check(account()?.sessionIdentity() == snapshot.sessionIdentity()) {
            "登录账号已切换，请重试当前操作。"
        }
        return result
    }

    private suspend fun blockedEntries(): TiebaBlocklistSnapshot =
        database.blocklistDao().observeAll().first().toBlocklistSnapshot()

    private fun nextReadingPositionGeneration(threadId: Long): Long = synchronized(readingPositionGenerations) {
        val next = (readingPositionGenerations[threadId] ?: 0L) + 1L
        readingPositionGenerations[threadId] = next
        next
    }

    private suspend fun persistReadingPositionIfCurrent(
        threadId: Long,
        position: ThreadReadingPosition,
        generation: Long,
    ) {
        readingPositionMutex.withLock {
            if (readingPositionGenerations[threadId] != generation) return
            database.readingPositionDao().upsert(
                ReadingPositionEntity(
                    threadId = threadId,
                    postId = position.postId.toString(),
                    floor = position.floor,
                    updatedAtMilliseconds = System.currentTimeMillis(),
                ),
            )
        }
    }

    private companion object {
        const val MAXIMUM_FILTERED_PAGE_SCAN = 5
    }
}

private fun RecentForumEntity.toForum(): Forum = Forum(
    id = forumId,
    name = name,
    displayName = displayName,
    avatarUrl = avatarUrl,
)

internal fun recentForumEntity(
    forum: Forum,
    existing: RecentForumEntity? = null,
    visitedAtMilliseconds: Long,
): RecentForumEntity? {
    val name = forum.name.trim().removeSuffix("吧").trim()
        .ifEmpty { forum.displayName.trim().removeSuffix("吧").trim() }
        .takeIf(String::isNotEmpty)
        ?: return null
    return RecentForumEntity(
        normalizedName = name.lowercase(),
        forumId = forum.id.takeIf { it > 0 } ?: existing?.forumId ?: 0,
        name = name,
        displayName = forum.displayName.takeIf(String::isNotBlank)
            ?: existing?.displayName?.takeIf(String::isNotBlank)
            ?: "${name}吧",
        avatarUrl = forum.avatarUrl?.takeIf(String::isNotBlank) ?: existing?.avatarUrl,
        visitedAtMilliseconds = visitedAtMilliseconds,
    )
}

internal suspend fun DataThreadRepository.pageAroundPost(
    threadId: Long,
    postId: ULong,
    onlyThreadAuthor: Boolean,
    sort: ThreadReplySort,
    account: Account?,
): ThreadPage = page(
    threadId = threadId,
    page = 1,
    postId = postId,
    onlyThreadAuthor = onlyThreadAuthor,
    sort = sort,
    account = account,
)

private fun filterThreads(
    threads: List<ThreadSummary>,
    entries: TiebaBlocklistSnapshot,
): List<ThreadSummary> = threads.filter { TiebaContentFilterPolicy.shouldKeep(it, entries) }
