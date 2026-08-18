package dev.infinityf4p.tiebapure.core.model

data class TiebaBlocklistSnapshot(
    val keywords: Set<String> = emptySet(),
    val userIds: Set<Long> = emptySet(),
    val userNames: Set<String> = emptySet(),
    val forumIds: Set<Long> = emptySet(),
    val forumNames: Set<String> = emptySet(),
) {
    fun containsKeyword(text: String): Boolean = text.isNotEmpty() &&
        keywords.any { keyword -> text.contains(keyword, ignoreCase = true) }

    fun blocksUser(user: UserSummary): Boolean =
        (user.id > 0 && user.id in userIds) ||
            sequenceOf(user.name, user.displayName)
                .map(::normalizeIdentity)
                .filter(String::isNotEmpty)
                .any(userNames::contains)

    fun blocksForum(forum: Forum): Boolean = blocksForum(
        id = forum.id,
        names = listOf(forum.name, forum.displayName),
    )

    fun blocksForum(id: Long?, names: List<String>): Boolean =
        (id != null && id > 0 && id in forumIds) ||
            names.asSequence()
                .map(::normalizeForumName)
                .filter(String::isNotEmpty)
                .any(forumNames::contains)

    companion object {
        fun from(entries: List<BlocklistEntry>): TiebaBlocklistSnapshot {
            val normalized = entries.mapNotNull(BlocklistPolicy::normalize)
            return TiebaBlocklistSnapshot(
                keywords = normalized.filter { it.kind == BlocklistEntryKind.Keyword }
                    .mapTo(linkedSetOf()) { it.value.lowercase() },
                userIds = normalized.filter { it.kind == BlocklistEntryKind.User }
                    .mapNotNullTo(linkedSetOf()) { it.numericId?.takeIf { id -> id > 0 } },
                userNames = normalized.filter { it.kind == BlocklistEntryKind.User }
                    .mapTo(linkedSetOf()) { normalizeIdentity(it.value) },
                forumIds = normalized.filter { it.kind == BlocklistEntryKind.Forum }
                    .mapNotNullTo(linkedSetOf()) { it.numericId?.takeIf { id -> id > 0 } },
                forumNames = normalized.filter { it.kind == BlocklistEntryKind.Forum }
                    .mapTo(linkedSetOf()) { normalizeForumName(it.value) },
            )
        }
    }
}

object TiebaContentFilterPolicy {
    fun shouldKeep(thread: ThreadSummary, blocklist: TiebaBlocklistSnapshot): Boolean =
        !blocklist.blocksUser(thread.author) &&
            !blocklist.blocksForum(thread.forumId, listOfNotNull(thread.forumName)) &&
            !blocklist.containsKeyword(thread.title) &&
            !blocklist.containsKeyword(thread.textPreview)

    fun shouldKeep(post: Post, blocklist: TiebaBlocklistSnapshot): Boolean =
        !blocklist.blocksUser(post.author) && !blocklist.containsKeyword(post.contentPreview)

    fun shouldKeep(subpost: Subpost, blocklist: TiebaBlocklistSnapshot): Boolean =
        !blocklist.blocksUser(subpost.author) &&
            !blocklist.containsKeyword(subpost.blocks.mapNotNull(ContentBlock::plainText).joinToString(""))

    fun shouldKeep(message: TiebaMessage, blocklist: TiebaBlocklistSnapshot): Boolean =
        !blocklist.blocksUser(message.sender) &&
            !blocklist.blocksForum(0, listOfNotNull(message.forumName)) &&
            !blocklist.containsKeyword(message.text) &&
            !blocklist.containsKeyword(message.threadTitle)

    fun shouldKeep(user: UserSummary, blocklist: TiebaBlocklistSnapshot): Boolean =
        !blocklist.blocksUser(user)

    fun shouldKeep(forum: Forum, blocklist: TiebaBlocklistSnapshot): Boolean =
        !blocklist.blocksForum(forum)

    fun shouldKeep(favorite: AccountThreadFavorite, blocklist: TiebaBlocklistSnapshot): Boolean =
        !blocklist.blocksForum(favorite.forumId, listOf(favorite.forumName)) &&
            !blocklist.containsKeyword(favorite.title) &&
            normalizeIdentity(favorite.authorDisplayName) !in blocklist.userNames

    fun filter(page: ThreadPage, blocklist: TiebaBlocklistSnapshot): ThreadPage {
        val main = page.mainPost?.filterPreviewSubposts(blocklist)
        val posts = page.posts.mapNotNull { post ->
            val isStructuralMainPost = post.floor == 1 || post.id == page.mainPost?.id
            if (!isStructuralMainPost && !shouldKeep(post, blocklist)) null
            else post.filterPreviewSubposts(blocklist)
        }
        return page.copy(mainPost = main, posts = posts)
    }

    fun filter(page: SubpostPage, blocklist: TiebaBlocklistSnapshot): SubpostPage = page.copy(
        parentPost = page.parentPost.filterPreviewSubposts(blocklist),
        subposts = page.subposts.filter { shouldKeep(it, blocklist) },
    )
}

private fun Post.filterPreviewSubposts(blocklist: TiebaBlocklistSnapshot): Post = copy(
    previewSubposts = previewSubposts.filter { TiebaContentFilterPolicy.shouldKeep(it, blocklist) },
)

private fun normalizeIdentity(value: String): String = value.trim().lowercase()

private fun normalizeForumName(value: String): String = value.trim().removeSuffix("吧").trim().lowercase()
