package dev.infinityf4p.tiebapure.feature.settings

import dev.infinityf4p.tiebapure.core.model.BlocklistEntry
import dev.infinityf4p.tiebapure.core.model.BlocklistEntryKind
import dev.infinityf4p.tiebapure.core.model.BlocklistPolicy
import dev.infinityf4p.tiebapure.core.model.ReadingPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsPolicyTest {
    @Test fun writeActionsRequireExplicitOptIn() {
        val settings = SettingsValues()

        assertFalse(settings.postingEnabled)
        assertFalse(settings.replyingEnabled)
        assertFalse(settings.likingEnabled)
    }

    @Test fun labelsRemainStable() {
        assertEquals("跟随系统", appearanceLabel(SettingsAppearance.System))
    }

    @Test fun blocklistNormalizationTrimsAndRejectsEmpty() {
        assertEquals("foo", BlocklistPolicy.normalize(BlocklistEntry(BlocklistEntryKind.Keyword, " foo "))?.value)
        assertNull(BlocklistPolicy.normalize(BlocklistEntry(BlocklistEntryKind.User, "  ")))
    }

    @Test fun blocklistRejectsDuplicateIdentityAndPerKindOverflow() {
        val entry = BlocklistEntry(BlocklistEntryKind.Keyword, "foo")
        assertEquals(false, canAddBlocklistEntry(listOf(entry), BlocklistEntry(BlocklistEntryKind.Keyword, " FOO ")))
        val full = (0 until BlocklistPolicy.maximumEntriesPerKind).map {
            BlocklistEntry(BlocklistEntryKind.User, "user-$it")
        }
        assertEquals(false, canAddBlocklistEntry(full, BlocklistEntry(BlocklistEntryKind.User, "another")))
    }

    @Test fun repositoryPortKeepsFeatureIndependentFromStorageTechnology() {
        val repository: SettingsRepository = FixtureSettingsRepository()
        assertEquals(SettingsValues(), (repository.settings as MutableStateFlow).value)
        assertEquals(emptyList<BlocklistEntry>(), (repository.blocklist as MutableStateFlow).value)
    }

    @Test fun aboutInfoLinksToAndroidRepository() {
        assertEquals(
            "https://github.com/infinityf4p/TiebaPure-Android",
            SettingsAboutInfo(versionName = "test").projectUrl,
        )
    }

    @Test fun settingsAvatarUsesOnlyCanonicalTiebaPortraitHost() {
        assertEquals("https://himg.bdimg.com/sys/portrait/item/token", settingsPortraitUrl("token"))
        assertEquals("https://himg.bdimg.com/sys/portrait/item/token", settingsPortraitUrl("token?t=1"))
        assertEquals(
            "https://himg.bdimg.com/sys/portrait/item/legacy",
            settingsPortraitUrl("http://tb.himg.baidu.com/sys/portrait/item/legacy"),
        )
        assertNull(settingsPortraitUrl("https://evil.example/sys/portrait/item/token"))
        assertNull(settingsPortraitUrl("https://user@himg.bdimg.com/sys/portrait/item/token"))
    }

    @Test fun blocklistFootersExplainEveryMatchingRule() {
        assertEquals(
            "标题或内容包含关键词的帖子和楼层会被隐藏，不区分大小写。",
            blocklistFooter(BlocklistEntryKind.Keyword),
        )
        assertEquals(
            "可直接输入用户名；在用户主页中屏蔽可精确匹配账号。",
            blocklistFooter(BlocklistEntryKind.User),
        )
        assertEquals("填写吧名，无需带“吧”字后缀。", blocklistFooter(BlocklistEntryKind.Forum))
    }
}

private class FixtureSettingsRepository : SettingsRepository {
    override val settings = MutableStateFlow(SettingsValues())
    override val blocklist = MutableStateFlow<List<BlocklistEntry>>(emptyList())

    override suspend fun setAppearance(value: SettingsAppearance) { settings.value = settings.value.copy(appearance = value) }
    override suspend fun setPostingEnabled(value: Boolean) { settings.value = settings.value.copy(postingEnabled = value) }
    override suspend fun setReplyingEnabled(value: Boolean) { settings.value = settings.value.copy(replyingEnabled = value) }
    override suspend fun setLikingEnabled(value: Boolean) { settings.value = settings.value.copy(likingEnabled = value) }
    override suspend fun setAutomaticSignEnabled(value: Boolean) { settings.value = settings.value.copy(automaticSignEnabled = value) }
    override suspend fun acknowledgeSubmissionRisk() { settings.value = settings.value.copy(submissionRiskAcknowledged = true) }
    override suspend fun setReadingPreferences(value: ReadingPreferences) { settings.value = settings.value.copy(reading = value) }
    override suspend fun addBlocklistEntry(value: BlocklistEntry) { blocklist.value += value }
    override suspend fun removeBlocklistEntry(value: BlocklistEntry) { blocklist.value -= value }
    override suspend fun clearBlocklist(kind: BlocklistEntryKind) { blocklist.value = blocklist.value.filterNot { it.kind == kind } }
}
