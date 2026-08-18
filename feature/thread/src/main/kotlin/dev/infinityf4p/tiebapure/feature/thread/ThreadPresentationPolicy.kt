package dev.infinityf4p.tiebapure.feature.thread

import dev.infinityf4p.tiebapure.core.model.Forum

internal data class ThreadActionVisibility(
    val showReplyActions: Boolean,
    val showLikeActions: Boolean,
    val showCollectAction: Boolean,
)

internal fun ThreadCapabilities.actionVisibility(hasPage: Boolean): ThreadActionVisibility =
    ThreadActionVisibility(
        showReplyActions = hasPage && canReply,
        showLikeActions = hasPage && canLike,
        showCollectAction = canCollect,
    )

internal enum class ThreadFooterContent {
    Loading,
    LoadMore,
    End,
}

internal fun threadFooterContent(
    hasPage: Boolean,
    isLoadingMore: Boolean,
    hasMore: Boolean,
): ThreadFooterContent? = when {
    !hasPage -> null
    isLoadingMore -> ThreadFooterContent.Loading
    hasMore -> ThreadFooterContent.LoadMore
    else -> ThreadFooterContent.End
}

internal const val SUBPOST_OPEN_ALL_TOUCH_HEIGHT_DP = 48
internal const val SUBPOST_OPEN_ALL_VISUAL_HEIGHT_DP = 30

internal fun normalizedThreadForumRoute(forum: Forum): Forum? {
    val routeName = forum.name.trim().ifEmpty {
        forum.displayName.trim().removeSuffix("吧").trim()
    }
    return routeName.takeIf(String::isNotEmpty)?.let { forum.copy(name = it) }
}
