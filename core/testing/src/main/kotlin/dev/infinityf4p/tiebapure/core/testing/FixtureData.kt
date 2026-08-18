package dev.infinityf4p.tiebapure.core.testing

import dev.infinityf4p.tiebapure.core.model.ContentBlock
import dev.infinityf4p.tiebapure.core.model.ThreadSummary
import dev.infinityf4p.tiebapure.core.model.UserSummary

object FixtureData {
    val user = UserSummary(88, "fixture_user", "测试用户", "")

    val thread = ThreadSummary(
        id = 765_432,
        forumId = 66,
        forumName = "测试",
        title = "用于 Android 离线界面测试的主题",
        author = user,
        replyCount = 9,
        viewCount = 120,
        blocks = listOf(ContentBlock.Text("这是一条不需要真实账号和网络的确定性测试数据。")),
    )
}
