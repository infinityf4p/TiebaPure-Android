package dev.infinityf4p.tiebapure.core.network

import dev.infinityf4p.tiebapure.core.model.Account
import dev.infinityf4p.tiebapure.core.model.ForumThreadCategory
import dev.infinityf4p.tiebapure.core.model.ThreadReplySort
import dev.infinityf4p.tiebapure.core.model.UserSummary
import tieba.AppPosInfoOuterClass.AppPosInfo
import tieba.CommonRequestOuterClass.CommonRequest
import tieba.Personalized
import tieba.frsPage.FrsPage
import tieba.pbFloor.PbFloorRequestOuterClass
import tieba.pbPage.PbPageRequestOuterClass
import tiebapure.profile.UserProfile as ProfileProtocol

class TiebaProtoRequestFactory(
    private val requestBuilder: TiebaRequestBuilder,
    private val clock: EpochMillisecondsClock = EpochMillisecondsClock.System,
) {
    fun common(account: Account?): CommonRequest = CommonRequest.newBuilder()
        .setBDUSS(account?.bduss.orEmpty())
        .setClientId(requestBuilder.device.clientId)
        .setClientType(2)
        .setClientVersion(TiebaClientVersion.V12.value)
        .setOsVersion(requestBuilder.device.osVersion)
        .setTimestamp(clock.now())
        .setBrand(requestBuilder.device.brand)
        .setCuid(requestBuilder.device.clientId)
        .setCuidGalaxy2(requestBuilder.device.clientId)
        .setCuidGid("")
        .setFrom("1020031h")
        .setIsTeenager(0)
        .setModel(requestBuilder.device.model)
        .setNetType(1)
        .setPversion("1.0.3")
        .setPersonalizedRecSwitch(1)
        .setQType(0)
        .setScrDip(requestBuilder.device.screenDensity)
        .setScrW(requestBuilder.device.screenWidthPixels)
        .setScrH(requestBuilder.device.screenHeightPixels)
        .setStoken(account?.stoken.orEmpty())
        .setUserAgent("tieba/${TiebaClientVersion.V12.value}")
        .build()

    fun personalized(account: Account?, page: Int, loadType: Int): Personalized.PersonalizedRequest {
        val data = Personalized.PersonalizedRequestData.newBuilder()
            .setAppPos(AppPosInfo.getDefaultInstance())
            .setCommon(common(account))
            .setLoadType(loadType)
            .setPn(TiebaRequestValuePolicy.page(page))
            .setNeedTags(0)
            .setPageThreadCount(11)
            .setPreAdThreadCount(0)
            .setSugCount(0)
            .setTagCode(0)
            .setQType(1)
            .setNeedForumlist(0)
            .setNewNetType(1)
            .setNewInstall(0)
            .setRequestTimes(0)
            .setInvokeSource("")
            .setScrDip(requestBuilder.device.screenDensity)
            .setScrH(requestBuilder.device.screenHeightPixels)
            .setScrW(requestBuilder.device.screenWidthPixels)
            .build()
        return Personalized.PersonalizedRequest.newBuilder().setData(data).build()
    }

    fun forumThreads(
        account: Account,
        forumName: String,
        page: Int,
        category: ForumThreadCategory,
    ): FrsPage.FrsPageRequest {
        val data = FrsPage.FrsPageRequestData.newBuilder()
            .setAdParam(FrsPage.AdParam.getDefaultInstance())
            .setAppPos(AppPosInfo.getDefaultInstance())
            .setCommon(common(account))
            .setKw(forumName)
            .setLoadType(if (page == 1) 1 else 2)
            .setPn(TiebaRequestValuePolicy.page(page))
            .setQType(2)
            .setRn(90)
            .setRnNeed(30)
            .setScrDip(requestBuilder.device.screenDensity)
            .setScrH(requestBuilder.device.screenHeightPixels)
            .setScrW(requestBuilder.device.screenWidthPixels)
            .setStType("recom_flist")
            .setWithGroup(1)
            .setSortType(category.sortType)
            .apply {
                category.goodClassifyId?.let {
                    setIsGood(1)
                    setCid(it)
                }
            }
            .build()
        return FrsPage.FrsPageRequest.newBuilder().setData(data).build()
    }

    fun threadPage(
        account: Account?,
        threadId: Long,
        page: Int,
        forumId: Long? = null,
        postId: ULong? = null,
        onlyThreadAuthor: Boolean = false,
        sort: ThreadReplySort = ThreadReplySort.Ascending,
    ): PbPageRequestOuterClass.PbPageRequest {
        if (threadId <= 0) throw TiebaMutationException.InvalidThreadId
        val data = PbPageRequestOuterClass.PbPageRequestData.newBuilder()
            .setCommon(common(account))
            .setKz(threadId)
            .setPn(TiebaRequestValuePolicy.page(page))
            .setR(sort.protocolValue)
            .setLz(if (onlyThreadAuthor) 1 else 0)
            .setForumId(forumId ?: 0)
            .setMark(0)
            .setFloorRn(4)
            .setFloorSortType(1)
            .setQType(2)
            .setRn(15)
            .setScrDip(requestBuilder.device.screenDensity)
            .setScrH(requestBuilder.device.screenHeightPixels)
            .setScrW(requestBuilder.device.screenWidthPixels)
            .setSourceType(2)
            .setWithFloor(1)
            .apply {
                postId?.let {
                    setPid(TiebaRequestValuePolicy.signedIdentifier(it))
                    if (page <= 1) setPn(0)
                }
            }
            .build()
        return PbPageRequestOuterClass.PbPageRequest.newBuilder().setData(data).build()
    }

    fun subposts(
        account: Account?,
        threadId: Long,
        postId: ULong,
        forumId: Long,
        page: Int,
        subpostId: ULong = 0uL,
    ): PbFloorRequestOuterClass.PbFloorRequest {
        if (threadId <= 0) throw TiebaMutationException.InvalidThreadId
        if (forumId <= 0) throw TiebaMutationException.InvalidForumId
        if (postId == 0uL) throw TiebaMutationException.InvalidPostId
        val data = PbFloorRequestOuterClass.PbFloorRequestData.newBuilder()
            .setCommon(common(account))
            .setForumId(forumId)
            .setKz(threadId)
            .setPid(TiebaRequestValuePolicy.signedIdentifier(postId))
            .setPn(TiebaRequestValuePolicy.page(page))
            .setSpid(TiebaRequestValuePolicy.signedIdentifier(subpostId))
            .setScrDip(requestBuilder.device.screenDensity)
            .setScrH(requestBuilder.device.screenHeightPixels)
            .setScrW(requestBuilder.device.screenWidthPixels)
            .setIsCommReverse(0)
            .setOriUgcType(0)
            .build()
        return PbFloorRequestOuterClass.PbFloorRequest.newBuilder().setData(data).build()
    }

    fun userProfile(
        account: Account?,
        user: UserSummary,
    ): UserProfileRequestContext {
        val accountId = account?.uid?.toLongOrNull()
        val isCurrentUser = accountId != null && accountId == user.id
        val data = ProfileProtocol.UserProfileRequestData.newBuilder()
            .setNeedPostCount(1)
            .setIsGuest(if (isCurrentUser) 0 else 1)
            .setPn(1)
            .setRn(20)
            .setHasPlist(1)
            .setCommon(common(account))
            .setScrW(requestBuilder.device.screenWidthPixels)
            .setScrH(requestBuilder.device.screenHeightPixels)
            .setQType(0)
            .setScrDip(requestBuilder.device.screenDensity)
            .setIsFromUsercenter(1)
            .setPage(1)
            .apply {
                accountId?.let(::setUid)
                if (!isCurrentUser) {
                    if (user.id != 0L) setFriendUid(user.id) else setFriendUidPortrait(user.portrait)
                }
            }
            .build()
        val message = ProfileProtocol.UserProfileRequest.newBuilder().setData(data).build()
        return UserProfileRequestContext(message, isCurrentUser)
    }

    fun userThreads(account: Account?, userId: Long, page: Int): ProfileProtocol.UserThreadsRequest {
        if (userId <= 0) throw TiebaMutationException.InvalidUserId
        val data = ProfileProtocol.UserThreadsRequestData.newBuilder()
            .setUid(userId)
            .setRn(20)
            .setIsThread(1)
            .setNeedContent(1)
            .setPn(TiebaRequestValuePolicy.page(page))
            .setCommon(common(account))
            .setScrW(requestBuilder.device.screenWidthPixels)
            .setScrH(requestBuilder.device.screenHeightPixels)
            .setScrDip(requestBuilder.device.screenDensity)
            .setQType(1)
            .setIsViewCard(1)
            .build()
        return ProfileProtocol.UserThreadsRequest.newBuilder().setData(data).build()
    }
}

data class UserProfileRequestContext(
    val message: ProfileProtocol.UserProfileRequest,
    val isCurrentUser: Boolean,
)
