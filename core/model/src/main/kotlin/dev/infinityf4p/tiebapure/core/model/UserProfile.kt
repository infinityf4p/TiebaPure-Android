package dev.infinityf4p.tiebapure.core.model

enum class UserContentVisibility { Visible, Private }

enum class UserProfileSex(val mutationProtocolValue: Int?) {
    Male(1),
    Female(2),
    Unspecified(null),
}

data class UserProfileEditRequest(
    val nickname: String,
    val introduction: String,
    val sex: UserProfileSex,
) {
    val normalizedNickname: String
        get() = nickname.trim()
}

data class UserProfile(
    val user: UserSummary,
    val isCurrentUser: Boolean,
    val isFollowed: Boolean,
    val tiebaId: String,
    val tiebaAge: String,
    val sex: UserProfileSex,
    val location: String?,
    val intro: String,
    val backgroundUrl: String?,
    val agreeCount: Int,
    val followingCount: Int,
    val followerCount: Int,
    val threadCount: Int,
    val followedForumCount: Int,
    val followedForums: List<Forum>,
    val followedForumsVisibility: UserContentVisibility,
)

data class OwnThreadDeletionTarget(
    val forumId: Long,
    val forumName: String,
    val threadId: Long,
    val firstPostId: ULong,
)

data class UserThreadsPage(
    val threads: List<ThreadSummary>,
    val currentPage: Int,
    val hasMore: Boolean,
    val visibility: UserContentVisibility,
    val deletionTargetsByThreadId: Map<Long, OwnThreadDeletionTarget> = emptyMap(),
)

enum class UserRelationshipKind { Following, Followers }

data class UserRelationshipPage(
    val users: List<UserSummary>,
    val currentPage: Int,
    val totalCount: Int,
    val hasMore: Boolean,
)
