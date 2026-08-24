package dev.infinityf4p.tiebapure.core.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

enum class EndpointAccess {
    AnonymousRead,
    AuthenticatedRead,
    Authentication,
    AuthenticatedWrite,
}

data class TiebaEndpoint(
    val url: HttpUrl,
    val access: EndpointAccess,
) {
    companion object {
        private val WEB = "https://tieba.baidu.com".toHttpUrl()
        private val APP = "https://c.tieba.baidu.com".toHttpUrl()
        private val PROTOBUF = "https://tiebac.baidu.com".toHttpUrl()

        val Login = endpoint(APP, "/c/s/login", EndpointAccess.Authentication)
        val PostingLogin = endpoint(PROTOBUF, "/c/s/login", EndpointAccess.Authentication)
        val InitNickname = endpoint(APP, "/c/s/initNickname", EndpointAccess.Authentication)
        val WebMyInfo = endpoint(WEB, "/mo/q/newmoindex", EndpointAccess.AuthenticatedRead)
        val FollowedForums = endpoint(APP, "/c/f/forum/like", EndpointAccess.AuthenticatedRead)
        val ForumPageForm = endpoint(APP, "/c/f/frs/page", EndpointAccess.AnonymousRead)
        val Personalized = endpoint(WEB, "/c/f/excellent/personalized", EndpointAccess.AnonymousRead, "cmd" to "309264")
        val ForumPage = endpoint(PROTOBUF, "/c/f/frs/page", EndpointAccess.AuthenticatedRead, "cmd" to "301001")
        val ThreadPage = endpoint(
            WEB,
            "/c/f/pb/page",
            EndpointAccess.AnonymousRead,
            "cmd" to "302001",
            "format" to "protobuf",
        )
        val Subposts = endpoint(
            WEB,
            "/c/f/pb/floor",
            EndpointAccess.AnonymousRead,
            "cmd" to "302002",
            "format" to "protobuf",
        )
        val SearchThread = endpoint(WEB, "/mo/q/search/thread", EndpointAccess.AnonymousRead)
        val SearchUser = endpoint(WEB, "/mo/q/search/user", EndpointAccess.AnonymousRead)
        val UserProfile = endpoint(
            PROTOBUF,
            "/c/u/user/profile",
            EndpointAccess.AnonymousRead,
            "cmd" to "303012",
            "format" to "protobuf",
        )
        val UserThreads = endpoint(
            PROTOBUF,
            "/c/u/feed/userpost",
            EndpointAccess.AnonymousRead,
            "cmd" to "303002",
            "format" to "protobuf",
        )
        val ModifyProfile = endpoint(PROTOBUF, "/c/c/profile/modify", EndpointAccess.AuthenticatedWrite)
        val DeleteOwnThread = endpoint(APP, "/c/c/bawu/delthread", EndpointAccess.AuthenticatedWrite)
        val FollowUser = endpoint(PROTOBUF, "/c/c/user/follow", EndpointAccess.AuthenticatedWrite)
        val UnfollowUser = endpoint(PROTOBUF, "/c/c/user/unfollow", EndpointAccess.AuthenticatedWrite)
        val FollowedUsers = endpoint(PROTOBUF, "/c/u/follow/followList", EndpointAccess.AuthenticatedRead)
        val Followers = endpoint(PROTOBUF, "/c/u/fans/page", EndpointAccess.AnonymousRead)
        val ResolveForumId = endpoint(APP, "/f/commit/share/fnameShareApi", EndpointAccess.AnonymousRead)
        val ForumMembership = endpoint(PROTOBUF, "/c/f/forum/getUserForumLevelInfo", EndpointAccess.AuthenticatedRead)
        val FollowForum = endpoint(PROTOBUF, "/c/c/forum/like", EndpointAccess.AuthenticatedWrite)
        val UnfollowForum = endpoint(PROTOBUF, "/c/c/forum/unfavolike", EndpointAccess.AuthenticatedWrite)
        val SignForum = endpoint(APP, "/c/c/forum/sign", EndpointAccess.AuthenticatedWrite)
        val ThreadStoreList = endpoint(APP, "/c/u/feed/threadStoreList", EndpointAccess.AuthenticatedRead)
        val AddThreadStore = endpoint(APP, "/c/c/post/addstore", EndpointAccess.AuthenticatedWrite)
        val RemoveThreadStore = endpoint(APP, "/c/c/post/rmstore", EndpointAccess.AuthenticatedWrite)
        val AgreePost = endpoint(PROTOBUF, "/c/c/agree/opAgree", EndpointAccess.AuthenticatedWrite)
        val ReplyMessages = endpoint(APP, "/c/u/feed/replyme", EndpointAccess.AuthenticatedRead)
        val MentionMessages = endpoint(APP, "/c/u/feed/atme", EndpointAccess.AuthenticatedRead)
        val WebTbs = endpoint(WEB, "/dc/common/tbs", EndpointAccess.AuthenticatedRead)
        val WebAddThread = endpoint(WEB, "/f/commit/thread/add", EndpointAccess.AuthenticatedWrite)
        val AddPost = endpoint(PROTOBUF, "/c/c/post/add", EndpointAccess.AuthenticatedWrite, "cmd" to "309731")
        val UploadPicture = endpoint(PROTOBUF, "/c/s/uploadPicture", EndpointAccess.AuthenticatedWrite)

        fun webAddPost(timestamp: Long) = endpoint(
            WEB,
            "/mo/q/apubpost",
            EndpointAccess.AuthenticatedWrite,
            "_t" to timestamp.toString(),
        )

        fun webUploadPicture(nonce: String) = endpoint(
            WEB,
            "/mo/q/cooluploadpic",
            EndpointAccess.AuthenticatedWrite,
            "type" to "ajax",
            "r" to nonce,
        )

        val All: List<TiebaEndpoint> = listOf(
            Login,
            PostingLogin,
            InitNickname,
            WebMyInfo,
            FollowedForums,
            ForumPageForm,
            Personalized,
            ForumPage,
            ThreadPage,
            Subposts,
            SearchThread,
            SearchUser,
            UserProfile,
            UserThreads,
            ModifyProfile,
            DeleteOwnThread,
            FollowUser,
            UnfollowUser,
            FollowedUsers,
            Followers,
            ResolveForumId,
            ForumMembership,
            FollowForum,
            UnfollowForum,
            SignForum,
            ThreadStoreList,
            AddThreadStore,
            RemoveThreadStore,
            AgreePost,
            ReplyMessages,
            MentionMessages,
            WebTbs,
            WebAddThread,
            AddPost,
            UploadPicture,
        )

        private fun endpoint(
            base: HttpUrl,
            path: String,
            access: EndpointAccess,
            vararg query: Pair<String, String>,
        ): TiebaEndpoint {
            val builder = base.newBuilder().encodedPath(path)
            query.forEach { (name, value) -> builder.addQueryParameter(name, value) }
            return TiebaEndpoint(builder.build(), access)
        }
    }
}
