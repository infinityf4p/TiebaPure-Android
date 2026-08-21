package dev.infinityf4p.tiebapure.core.network

import dev.infinityf4p.tiebapure.core.model.Account
import dev.infinityf4p.tiebapure.core.model.ForumThreadCategory
import dev.infinityf4p.tiebapure.core.model.ThreadReplySort
import dev.infinityf4p.tiebapure.core.model.UserRelationshipKind
import okhttp3.Request

private const val MESSAGE_CLIENT_VERSION = "8.2.2"

object TiebaReadRequestFactory {
    fun followedForums(account: Account, builder: TiebaRequestBuilder): Request = builder.formRequest(
        endpoint = TiebaEndpoint.FollowedForums,
        fields = mapOf(
            "BDUSS" to account.bduss,
            "stoken" to account.stoken,
            "user_id" to account.uid,
            "_client_version" to "11.10.8.6",
        ),
        headers = mapOf("User-Agent" to "bdtb for Android 11.10.8.6"),
    )

    fun forumThreads(
        forumName: String,
        page: Int,
        category: ForumThreadCategory,
        builder: TiebaRequestBuilder,
    ): Request {
        val requestedPage = TiebaRequestValuePolicy.page(page)
        val fields = builder.miniCommonFields().toMutableMap().apply {
            put("kw", forumName)
            put("pn", requestedPage.toString())
            put("sort_type", category.sortType.toString())
            category.goodClassifyId?.let {
                put("is_good", "1")
                put("cid", it.toString())
            }
            put("q_type", "2")
            put("st_type", "tb_forumlist")
            put("with_group", "0")
            put("rn", "20")
            put("scr_dip", builder.device.screenDensity.toString())
            put("scr_h", builder.device.screenHeightPixels.toString())
            put("scr_w", builder.device.screenWidthPixels.toString())
        }
        val cuid = builder.device.miniCuid
        return builder.formRequest(
            endpoint = TiebaEndpoint.ForumPageForm,
            fields = fields,
            headers = mapOf(
                "User-Agent" to "bdtb for Android ${TiebaClientVersion.Mini.value}",
                "Cookie" to "ka=open",
                "Pragma" to "no-cache",
                "cuid" to cuid,
                "cuid_galaxy2" to cuid,
            ),
            signingSecret = TiebaFormSigner.DEFAULT_SECRET,
        )
    }

    fun searchThreads(
        keyword: String,
        page: Int,
        builder: TiebaRequestBuilder,
        sortType: Int = 5,
        filterType: Int = 2,
        forumName: String? = null,
        pageSize: Int = 30,
    ): Request {
        val word = keyword.trim()
        require(word.isNotEmpty()) { "Search keyword must not be empty" }
        val query = buildMap {
            put("word", word)
            put("pn", TiebaRequestValuePolicy.page(page).toString())
            put("st", sortType.toString())
            put("tt", filterType.toString())
            put("ct", if (forumName.isNullOrBlank()) "1" else "2")
            put("cv", if (forumName.isNullOrBlank()) "99.9.101" else TiebaClientVersion.V12.value)
            forumName?.takeIf(String::isNotBlank)?.let {
                put("fname", it)
                put("rn", pageSize.toString())
            }
        }
        val referer = if (forumName.isNullOrBlank()) {
            "https://tieba.baidu.com/mo/q/hybrid/search?keyword=${TiebaFormCodec.escape(word).replace("+", "%20")}"
        } else {
            "https://tieba.baidu.com/mo/q/hybrid-usergrow-search/searchGlobal?entryPage=frs&forumName=" +
                TiebaFormCodec.escape(forumName).replace("+", "%20")
        }
        return builder.getRequest(
            TiebaEndpoint.SearchThread,
            query,
            mapOf("User-Agent" to "tieba/${TiebaClientVersion.V12.value} skin/default", "Referer" to referer),
        )
    }

    fun searchUser(name: String, builder: TiebaRequestBuilder): Request {
        val word = name.trim().trimStart('@').trim()
        require(word.isNotEmpty()) { "User name must not be empty" }
        return builder.getRequest(
            TiebaEndpoint.SearchUser,
            mapOf("word" to word, "_client_version" to "8.0.8.0", "cuid_gid" to ""),
            mapOf(
                "User-Agent" to "bdtb for Android 8.0.8.0",
                "Referer" to "https://tieba.baidu.com/mo/q/hybrid/search?keyword=" +
                    TiebaFormCodec.escape(word).replace("+", "%20"),
            ),
        )
    }

    fun relationships(
        account: Account?,
        userId: Long,
        page: Int,
        kind: UserRelationshipKind,
        builder: TiebaRequestBuilder,
    ): Request {
        if (userId <= 0) throw TiebaMutationException.InvalidUserId
        return builder.formRequest(
            endpoint = if (kind == UserRelationshipKind.Following) TiebaEndpoint.FollowedUsers else TiebaEndpoint.Followers,
            fields = mapOf(
                "BDUSS" to account?.bduss.orEmpty(),
                "_client_version" to TiebaClientVersion.V22.value,
                "pn" to TiebaRequestValuePolicy.page(page).toString(),
                "uid" to userId.toString(),
            ),
            headers = v22Headers(),
            signingSecret = TiebaFormSigner.DEFAULT_SECRET,
        )
    }

    fun forumMembership(account: Account, forumId: Long, builder: TiebaRequestBuilder): Request =
        builder.formRequest(
            TiebaEndpoint.ForumMembership,
            TiebaMutationRequestFactory.forumMembership(account, forumId),
            headers = mapOf("User-Agent" to "tieba/${TiebaClientVersion.V22.value}"),
            signingSecret = TiebaFormSigner.DEFAULT_SECRET,
        )

    fun resolveForumId(forumName: String, builder: TiebaRequestBuilder): Request {
        val name = forumName.trim()
        if (name.isEmpty()) throw TiebaMutationException.InvalidForumName
        return builder.getRequest(
            TiebaEndpoint.ResolveForumId,
            query = mapOf("fname" to name, "ie" to "utf-8"),
            headers = mapOf("User-Agent" to "TiebaPure"),
        )
    }

    fun threadStore(account: Account, page: Int, builder: TiebaRequestBuilder): Request =
        builder.formRequest(
            TiebaEndpoint.ThreadStoreList,
            mapOf(
                "BDUSS" to account.bduss,
                "stoken" to account.stoken,
                "pn" to TiebaRequestValuePolicy.page(page).toString(),
                "rn" to "20",
            ),
            headers = builder.officialHeaders(account.baiduId, TiebaClientVersion.V12.value),
            signingSecret = TiebaFormSigner.DEFAULT_SECRET,
        )

    fun messages(account: Account, page: Int, mention: Boolean, builder: TiebaRequestBuilder): Request {
        val fields = builder.miniCommonFields().toMutableMap().apply {
            remove("subapp_type")
            remove("cuid_galaxy2")
            put("_client_version", MESSAGE_CLIENT_VERSION)
            put("from", "baidu_appstore")
            put("BDUSS", account.bduss)
            put("pn", TiebaRequestValuePolicy.page(page).toString())
            put("stErrorNums", "0")
        }
        return builder.formRequest(
            endpoint = if (mention) TiebaEndpoint.MentionMessages else TiebaEndpoint.ReplyMessages,
            fields = fields,
            headers = mapOf(
                "Cookie" to "ka=open",
                "Pragma" to "no-cache",
                "User-Agent" to "bdtb for Android $MESSAGE_CLIENT_VERSION",
                "cuid" to builder.device.miniCuid,
            ),
            signingSecret = TiebaFormSigner.DEFAULT_SECRET,
        )
    }

    @Suppress("UNUSED_PARAMETER")
    fun threadSortProtocolValue(sort: ThreadReplySort): Int = sort.protocolValue

    private fun v22Headers(): Map<String, String> = mapOf(
        "Pragma" to "no-cache",
        "User-Agent" to "tieba/${TiebaClientVersion.V22.value}",
    )
}
