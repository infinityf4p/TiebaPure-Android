package dev.infinityf4p.tiebapure

import dev.infinityf4p.tiebapure.core.model.Account
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionKind
import dev.infinityf4p.tiebapure.core.model.Forum
import dev.infinityf4p.tiebapure.core.model.ThreadSummary
import dev.infinityf4p.tiebapure.core.model.UserSummary
import dev.infinityf4p.tiebapure.feature.search.SearchItem
import dev.infinityf4p.tiebapure.feature.thread.ThreadInitialDestination
import java.security.MessageDigest

internal fun sessionViewModelKey(account: Account?): String {
    if (account == null) return "guest"
    val material = listOf(account.uid, account.bduss, account.stoken, account.baiduId.orEmpty())
        .joinToString(separator = "") { "${it.length}:$it" }
    return MessageDigest.getInstance("SHA-256")
        .digest(material.toByteArray(Charsets.UTF_8))
        .take(16)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

internal fun buildThreadRoute(
    threadId: Long,
    postId: ULong? = null,
    initialDestination: ThreadInitialDestination? = null,
): String = "thread/$threadId?postId=${postId?.takeIf { it > 0uL } ?: 0uL}" +
    "&initialDestination=${if (initialDestination == ThreadInitialDestination.Replies) "replies" else ""}"

internal fun buildUserRoute(user: UserSummary, encode: (String) -> String): String {
    val routeName = user.resolvedDisplayName.trim().ifEmpty {
        if (user.id > 0L) "用户${user.id}" else "未知用户"
    }
    return "user/${user.id}/${encode(routeName)}"
}

internal fun buildForumRoute(forum: Forum, encode: (String) -> String): String? {
    val routeName = forum.name.removeSuffix("吧").trim()
        .ifEmpty { forum.displayName.removeSuffix("吧").trim() }
        .takeIf(String::isNotEmpty)
        ?: return null
    return "forum/${encode(routeName)}"
}

internal fun contentSubmissionEnabled(
    kind: ContentSubmissionKind,
    postingEnabled: Boolean,
    replyingEnabled: Boolean,
): Boolean = if (kind == ContentSubmissionKind.NewThread) postingEnabled else replyingEnabled

internal fun isComposerDestinationRoute(route: String?): Boolean =
    route?.startsWith("compose/") == true

internal fun shouldShowCompactRootNavigation(route: String?): Boolean =
    route?.startsWith("thread/") != true && !isComposerDestinationRoute(route)

internal fun shouldRequestHomeRefresh(
    selectedRootRoute: String,
    tappedRootRoute: String,
    primaryRoute: String?,
    detailRoute: String? = null,
): Boolean = selectedRootRoute == "home" &&
    tappedRootRoute == "home" &&
    primaryRoute == "home" &&
    (detailRoute == null || detailRoute == "home")

internal fun dispatchSearchThreadNavigation(
    result: SearchItem.ThreadResult,
    rememberThreadSummary: (ThreadSummary) -> Unit,
    navigate: (String) -> Unit,
) {
    val matchedPostId = result.postId?.takeIf { it > 0uL }
    if (matchedPostId != null && matchedPostId == result.thread.firstPostId) {
        rememberThreadSummary(result.thread)
    }
    navigate(buildThreadRoute(result.thread.id, result.postId))
}
