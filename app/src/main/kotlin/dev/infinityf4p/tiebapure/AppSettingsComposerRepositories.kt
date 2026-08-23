package dev.infinityf4p.tiebapure

import dev.infinityf4p.tiebapure.core.data.AccountMutationRepository
import dev.infinityf4p.tiebapure.core.data.AccountRepository
import dev.infinityf4p.tiebapure.core.data.AppAppearance
import dev.infinityf4p.tiebapure.core.data.AppSettingsStore
import dev.infinityf4p.tiebapure.core.data.BlocklistEntity
import dev.infinityf4p.tiebapure.core.data.ContentDraftEntity
import dev.infinityf4p.tiebapure.core.data.TiebaPureDatabase
import dev.infinityf4p.tiebapure.core.model.Account
import dev.infinityf4p.tiebapure.core.model.BlocklistEntry
import dev.infinityf4p.tiebapure.core.model.BlocklistEntryKind
import dev.infinityf4p.tiebapure.core.model.BlocklistPolicy
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionImage
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionKind
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionPolicy
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionRequest
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionReceipt
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionTarget
import dev.infinityf4p.tiebapure.core.model.ReadingPreferences
import dev.infinityf4p.tiebapure.core.model.UserSummary
import dev.infinityf4p.tiebapure.core.network.ContentSubmissionException
import dev.infinityf4p.tiebapure.core.network.TiebaApiException
import dev.infinityf4p.tiebapure.core.network.TiebaWriteException
import dev.infinityf4p.tiebapure.feature.composer.ComposerDraft
import dev.infinityf4p.tiebapure.feature.composer.ComposerRepository
import dev.infinityf4p.tiebapure.feature.composer.ComposerSubmissionResult
import dev.infinityf4p.tiebapure.feature.settings.SettingsAccountActions
import dev.infinityf4p.tiebapure.feature.settings.SettingsAppearance
import dev.infinityf4p.tiebapure.feature.settings.SettingsRepository
import dev.infinityf4p.tiebapure.feature.settings.SettingsValues
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.security.MessageDigest
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

class AppSettingsRepository(
    private val settingsStore: AppSettingsStore,
    database: TiebaPureDatabase,
    private val readerFontStore: AppReaderFontStore,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : SettingsRepository {
    private val blocklistDao = database.blocklistDao()

    override val settings: Flow<SettingsValues> = settingsStore.values.map { value ->
        SettingsValues(
            appearance = value.appearance.toSettingsAppearance(),
            postingEnabled = value.postingEnabled,
            replyingEnabled = value.replyingEnabled,
            likingEnabled = value.likingEnabled,
            automaticSignEnabled = value.autoSignEnabled,
            submissionRiskAcknowledged = value.submissionRiskAcknowledged,
            reading = value.reading,
        )
    }

    override val blocklist: Flow<List<BlocklistEntry>> = blocklistDao.observeAll().map { entities ->
        entities.mapNotNull { entity ->
            val kind = BlocklistEntryKind.entries.firstOrNull { it.name == entity.kind } ?: return@mapNotNull null
            BlocklistPolicy.normalize(BlocklistEntry(kind, entity.value, entity.numericId))
        }
    }

    override val readerFonts = readerFontStore.entries

    override suspend fun setAppearance(value: SettingsAppearance) =
        settingsStore.setAppearance(value.toAppAppearance())

    override suspend fun setPostingEnabled(value: Boolean) = settingsStore.setPostingEnabled(value)

    override suspend fun setReplyingEnabled(value: Boolean) = settingsStore.setReplyingEnabled(value)

    override suspend fun setLikingEnabled(value: Boolean) = settingsStore.setLikingEnabled(value)

    override suspend fun setAutomaticSignEnabled(value: Boolean) = settingsStore.setAutoSignEnabled(value)

    override suspend fun acknowledgeSubmissionRisk() = settingsStore.acknowledgeSubmissionRisk()

    override suspend fun setReadingPreferences(value: ReadingPreferences) =
        settingsStore.setReadingPreferences(value)

    override suspend fun importReaderFont(uri: String) {
        readerFontStore.import(uri)
    }

    override suspend fun removeReaderFont(id: String) {
        readerFontStore.remove(id)
    }

    override suspend fun addBlocklistEntry(value: BlocklistEntry) {
        val normalized = requireNotNull(BlocklistPolicy.normalize(value)) { "屏蔽内容不能为空。" }
        blocklistDao.upsert(
            BlocklistEntity(
                kind = normalized.kind.name,
                identity = normalized.identity,
                value = normalized.value,
                numericId = normalized.numericId,
                createdAtMilliseconds = nowEpochMillis(),
            ),
        )
    }

    override suspend fun removeBlocklistEntry(value: BlocklistEntry) {
        val normalized = BlocklistPolicy.normalize(value) ?: return
        blocklistDao.remove(normalized.kind.name, normalized.identity)
    }

    override suspend fun clearBlocklist(kind: BlocklistEntryKind) = blocklistDao.clear(kind.name)
}

class AppSettingsAccountActions(
    private val account: StateFlow<Account?>,
    private val accountRepository: AccountRepository,
    private val mutationRepository: AccountMutationRepository,
    private val onLogout: suspend () -> Unit,
    private val automaticSignStore: AutomaticSignStore,
) : SettingsAccountActions {
    private val signMutex = Mutex()

    override suspend fun signAllFollowedForums(): String = signMutex.withLock {
        val activeAccount = requireCurrentAccount(account)
        runSign(activeAccount).message
    }

    suspend fun signAutomaticallyIfNeeded(): String? = signMutex.withLock {
        val activeAccount = account.value ?: return@withLock null
        if (automaticSignStore.hasCompletedToday(activeAccount.id)) return@withLock null
        runSign(activeAccount).message
    }

    private suspend fun runSign(activeAccount: Account): SignRunResult {
        val forums = accountRepository.followedForums(activeAccount)
        ensureCurrentSession(account, activeAccount)
        if (forums.isEmpty()) return SignRunResult("暂无已关注的贴吧。", completed = false)

        var succeeded = 0
        var alreadySigned = 0
        var failed = 0
        var outcomeUnknown = 0
        forums.distinctBy { it.id.takeIf { id -> id > 0 }?.toString() ?: it.name }.forEachIndexed { index, forum ->
            if (index > 0) delay(SIGN_REQUEST_SPACING_MILLISECONDS)
            ensureCurrentSession(account, activeAccount)
            try {
                val result = mutationRepository.signForum(activeAccount, forum)
                if (result.wasAlreadySigned) alreadySigned += 1 else succeeded += 1
            } catch (error: CancellationException) {
                throw error
            } catch (error: ContentSubmissionException.NotLoggedIn) {
                throw error
            } catch (error: ContentSubmissionException.SessionExpired) {
                throw error
            } catch (error: TiebaApiException.SessionExpired) {
                throw error
            } catch (_: ContentSubmissionException.OutcomeUnknown) {
                outcomeUnknown += 1
            } catch (_: TiebaWriteException.OutcomeUnknown) {
                outcomeUnknown += 1
            } catch (_: Throwable) {
                failed += 1
            }
            ensureCurrentSession(account, activeAccount)
        }
        ensureCurrentSession(account, activeAccount)
        val completed = failed == 0 && outcomeUnknown == 0 && succeeded + alreadySigned > 0
        if (completed) automaticSignStore.markCompletedToday(activeAccount.id)
        return SignRunResult(buildString {
            append("签到完成：成功 $succeeded 个，已签到 $alreadySigned 个，失败 $failed 个")
            if (outcomeUnknown > 0) append("，待确认 $outcomeUnknown 个，请先刷新后再决定是否重试")
            append('。')
        }, completed)
    }

    override suspend fun logOut() {
        val activeAccount = requireCurrentAccount(account)
        mutationRepository.invalidateAndDrain(activeAccount)
        ensureCurrentSession(account, activeAccount)
        onLogout()
    }

    private data class SignRunResult(val message: String, val completed: Boolean)

    private companion object {
        const val SIGN_REQUEST_SPACING_MILLISECONDS = 350L
    }
}

class AutomaticSignStore(
    context: android.content.Context,
    private val nowEpochMilliseconds: () -> Long = System::currentTimeMillis,
) {
    private val preferences = context.getSharedPreferences("automatic_forum_sign", android.content.Context.MODE_PRIVATE)

    @Synchronized
    fun hasCompletedToday(accountId: String): Boolean =
        accountId.isNotBlank() && preferences.getString(accountKey(accountId), null) ==
            automaticSignDayStamp(nowEpochMilliseconds())

    @Synchronized
    fun markCompletedToday(accountId: String) {
        require(accountId.isNotBlank())
        check(
            preferences.edit()
                .putString(accountKey(accountId), automaticSignDayStamp(nowEpochMilliseconds()))
                .commit(),
        )
    }

    private fun accountKey(accountId: String): String = "completed-day-${accountId.sha256()}"
}

internal fun automaticSignDayStamp(epochMilliseconds: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(epochMilliseconds))

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

class AppComposerRepository private constructor(
    private val account: StateFlow<Account?>,
    private val draftDao: dev.infinityf4p.tiebapure.core.data.ContentDraftDao,
    private val submitContent: suspend (Account, ContentSubmissionRequest) -> ContentSubmissionReceipt,
    private val fileStore: AppDraftFileStore,
    private val submissionAllowed: (ContentSubmissionKind) -> Boolean,
) : ComposerRepository {
    private val storageMutex = Mutex()
    private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal constructor(
        account: StateFlow<Account?>,
        database: TiebaPureDatabase,
        mutationRepository: AccountMutationRepository,
        fileStore: AppDraftFileStore,
        submissionAllowed: (ContentSubmissionKind) -> Boolean,
    ) : this(
        account,
        database.contentDraftDao(),
        { activeAccount, request -> mutationRepository.submitContent(activeAccount, request) },
        fileStore,
        submissionAllowed,
    )

    override val drafts: Flow<List<ComposerDraft>> = combine(draftDao.observeAll(), account) { entities, current ->
        if (current == null) {
            emptyList()
        } else {
            entities.asSequence()
                .filter { it.accountId == current.id }
                .mapNotNull { entity -> runCatching { DraftBlobCodec.summary(entity) }.getOrNull() }
                .sortedByDescending(ComposerDraft::updatedAtEpochMillis)
                .toList()
        }
    }

    override suspend fun saveDraft(value: ComposerDraft): Unit = withContext(Dispatchers.IO) { storageMutex.withLock {
        val activeAccount = requireCurrentAccount(account)
        require(value.accountId == activeAccount.id) { "该草稿不属于当前账号。" }
        val targetMetadata = DraftBlobCodec.encodeTargetMetadata(value.target)
        val staged = fileStore.stage { DraftBlobCodec.write(value, it) }
        try {
            ensureCurrentSession(account, activeAccount)
            draftDao.upsert(
                ContentDraftEntity(
                    accountId = value.accountId,
                    targetKey = value.targetKey,
                    title = value.title,
                    body = value.body,
                    targetMetadata = targetMetadata,
                    attachmentFileName = staged.fileName,
                    attachmentByteCount = staged.byteCount,
                    attachmentSHA256 = staged.sha256,
                    imageCount = value.images.size,
                    updatedAtMilliseconds = value.updatedAtEpochMillis,
                ),
            )
        } finally {
            withContext(NonCancellable) {
                reconcileStagedAttachment(value.accountId, value.targetKey, staged.fileName)
            }
        }
        Unit
    } }

    override suspend fun loadDraft(accountId: String, targetKey: String): ComposerDraft? =
        withContext(Dispatchers.IO) { storageMutex.withLock {
        val activeAccount = requireCurrentAccount(account)
        require(accountId == activeAccount.id) { "该草稿不属于当前账号。" }
        val entity = draftDao.load(accountId, targetKey) ?: return@withLock null
        ensureCurrentSession(account, activeAccount)
        fileStore.read(entity) { DraftBlobCodec.decode(entity, it) }.also {
            ensureCurrentSession(account, activeAccount)
        }
    } }

    override suspend fun deleteDraft(accountId: String, targetKey: String) =
        withContext(Dispatchers.IO) { storageMutex.withLock {
        val activeAccount = requireCurrentAccount(account)
        require(accountId == activeAccount.id) { "该草稿不属于当前账号。" }
        val previous = draftDao.load(accountId, targetKey)
        ensureCurrentSession(account, activeAccount)
        draftDao.remove(accountId, targetKey)
        previous?.let { fileStore.delete(it.attachmentFileName) }
        withContext(NonCancellable) { cleanupUnreferencedFiles() }
    } }

    override fun scheduleDraftCleanup(accountId: String, targetKey: String, storageRevision: String?) {
        maintenanceScope.launch {
            repeat(3) { attempt ->
                if (attempt > 0) delay((attempt + 1) * 1_000L)
                val removed = runCatching {
                    cleanupDraftIfRevisionMatches(accountId, targetKey, storageRevision)
                }.getOrDefault(false)
                if (removed) return@launch
            }
        }
    }

    internal suspend fun cleanupDraftIfRevisionMatches(
        accountId: String,
        targetKey: String,
        storageRevision: String?,
    ): Boolean = withContext(Dispatchers.IO) { storageMutex.withLock {
        val current = draftDao.load(accountId, targetKey) ?: return@withLock true
        if (storageRevision == null || current.storageRevision() != storageRevision) return@withLock true
        draftDao.remove(accountId, targetKey)
        fileStore.delete(current.attachmentFileName)
        cleanupUnreferencedFiles()
        draftDao.load(accountId, targetKey) == null
    } }

    suspend fun repairStorage() = withContext(Dispatchers.IO) {
        storageMutex.withLock {
            draftDao.loadAll()
                .filterNot(DraftBlobCodec::hasValidTargetMetadata)
                .forEach { draftDao.remove(it.accountId, it.targetKey) }
            cleanupUnreferencedFiles()
        }
    }

    private suspend fun cleanupUnreferencedFiles() {
        fileStore.cleanup(draftDao.loadAll().mapTo(mutableSetOf(), ContentDraftEntity::attachmentFileName))
    }

    private suspend fun reconcileStagedAttachment(accountId: String, targetKey: String, stagedFileName: String) {
        val current = runCatching { draftDao.load(accountId, targetKey) }
        if (current.isSuccess && current.getOrNull()?.attachmentFileName != stagedFileName) {
            fileStore.delete(stagedFileName)
        }
        runCatching { cleanupUnreferencedFiles() }
    }

    override suspend fun submit(request: ContentSubmissionRequest): ComposerSubmissionResult {
        check(submissionAllowed(request.target.kind)) {
            if (request.target.kind == ContentSubmissionKind.NewThread) {
                "请先在设置中开启允许发帖。"
            } else {
                "请先在设置中开启允许回帖。"
            }
        }
        val activeAccount = requireCurrentAccount(account)
        ensureCurrentSession(account, activeAccount)
        return try {
            val receipt = submitContent(activeAccount, request)
            ComposerSubmissionResult.Success(receipt)
        } catch (error: CancellationException) {
            throw error
        } catch (error: ContentSubmissionException.VerificationRequired) {
            ensureCurrentSession(account, activeAccount)
            ComposerSubmissionResult.VerificationRequired(error.challenge)
        } catch (error: ContentSubmissionException.OutcomeUnknown) {
            ComposerSubmissionResult.OutcomeUnknown(error.message ?: OUTCOME_UNKNOWN_MESSAGE)
        } catch (error: TiebaWriteException.OutcomeUnknown) {
            ComposerSubmissionResult.OutcomeUnknown(error.message ?: OUTCOME_UNKNOWN_MESSAGE)
        }
    }

    companion object {
        const val OUTCOME_UNKNOWN_MESSAGE =
            "请求可能已经送达，但未能确认操作结果，请先刷新页面再决定是否重试。"

        internal fun forTests(
            account: StateFlow<Account?>,
            draftDao: dev.infinityf4p.tiebapure.core.data.ContentDraftDao,
            fileStore: AppDraftFileStore,
            submitContent: suspend (Account, ContentSubmissionRequest) -> ContentSubmissionReceipt = { _, _ ->
                error("Test submitter was not configured")
            },
            submissionAllowed: (ContentSubmissionKind) -> Boolean = { true },
        ) = AppComposerRepository(account, draftDao, submitContent, fileStore, submissionAllowed)
    }
}

internal object DraftBlobCodec {
    private const val MAGIC = 0x54504452 // TPDR
    private const val METADATA_MAGIC = 0x5450444D // TPDM
    private const val VERSION = 1
    private const val MAX_BLOB_BYTES = 96 * 1_024 * 1_024
    private const val MAX_STRING_BYTES = 16 * 1_024
    private const val MAX_MIME_BYTES = 256
    private val allowedMimeTypes = setOf(
        "image/gif", "image/heic", "image/heif", "image/jpeg", "image/png", "image/tiff", "image/webp",
    )

    fun write(draft: ComposerDraft, output: OutputStream) {
        require(draft.title.length <= ContentSubmissionPolicy.maximumTitleCharacters) { "草稿标题过长。" }
        require(draft.body.length <= ContentSubmissionPolicy.maximumBodyCharacters) { "草稿正文过长。" }
        require(draft.images.size <= ContentSubmissionPolicy.maximumImages) { "草稿图片数量过多。" }
        require(draft.target.kind != ContentSubmissionKind.NewThread || draft.images.isEmpty()) {
            "发布新主题暂不支持图片。"
        }
        validateDraftTarget(draft.target)
        require(storageTargetKey(draft.target) == draft.targetKey) { "草稿目标信息不一致。" }

        DataOutputStream(output).also { data ->
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeTarget(draft.target)
            data.writeInt(draft.images.size)
            for (image in draft.images) {
                require(image.bytes.size in 1..ContentSubmissionPolicy.maximumImageBytes) { "草稿图片大小无效。" }
                val mimeType = image.mimeType.lowercase()
                require(mimeType in allowedMimeTypes) { "草稿图片格式不受支持。" }
                data.writeBoundedString(mimeType, MAX_MIME_BYTES)
                data.writeInt(image.bytes.size)
                data.write(image.bytes)
            }
            data.flush()
        }
    }

    fun encodeTargetMetadata(target: ContentSubmissionTarget): ByteArray {
        validateDraftTarget(target)
        return ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(METADATA_MAGIC)
                data.writeInt(VERSION)
                data.writeTarget(target)
            }
        }.toByteArray().also { require(it.size <= 64 * 1_024) { "草稿目标信息过大。" } }
    }

    fun summary(entity: ContentDraftEntity): ComposerDraft {
        val target = decodeTargetMetadata(entity)
        validateEntityText(entity)
        require(entity.imageCount in 0..ContentSubmissionPolicy.maximumImages) { "草稿图片数量无效。" }
        require(entity.attachmentFileName.matches(Regex("[0-9a-f-]{36}\\.tpdr"))) { "草稿附件文件名无效。" }
        require(entity.attachmentByteCount in 8..MAX_BLOB_BYTES.toLong()) { "草稿附件大小无效。" }
        require(entity.attachmentSHA256.matches(Regex("[0-9a-f]{64}"))) { "草稿附件校验值无效。" }
        return ComposerDraft(
            accountId = entity.accountId,
            target = target,
            title = entity.title,
            body = entity.body,
            images = emptyList(),
            updatedAtEpochMillis = entity.updatedAtMilliseconds,
            storedImageCount = entity.imageCount,
            storageRevision = entity.storageRevision(),
        )
    }

    fun hasValidTargetMetadata(entity: ContentDraftEntity): Boolean =
        runCatching {
            validateEntityText(entity)
            decodeTargetMetadata(entity)
        }.isSuccess

    fun decode(entity: ContentDraftEntity, input: InputStream): ComposerDraft {
        validateEntityText(entity)
        require(entity.title.length <= ContentSubmissionPolicy.maximumTitleCharacters) { "草稿标题过长。" }
        require(entity.body.length <= ContentSubmissionPolicy.maximumBodyCharacters) { "草稿正文过长。" }
        val metadataTarget = decodeTargetMetadata(entity)
        val target: ContentSubmissionTarget
        val images: List<ContentSubmissionImage>
        try {
            DataInputStream(input).use { data ->
                require(data.readInt() == MAGIC) { "草稿附件格式无效。" }
                require(data.readInt() == VERSION) { "草稿附件版本不受支持。" }
                target = data.readTarget()
                validateDraftTarget(target)
                require(target == metadataTarget) { "草稿目标信息与附件不一致。" }
                require(storageTargetKey(target) == entity.targetKey) { "草稿目标信息校验失败。" }
                val count = data.readInt()
                require(count in 0..ContentSubmissionPolicy.maximumImages) { "草稿图片数量无效。" }
                require(count == entity.imageCount) { "草稿图片数量校验失败。" }
                images = List(count) {
                    val mimeType = data.readBoundedString(MAX_MIME_BYTES)
                    val byteCount = data.readInt()
                    require(mimeType in allowedMimeTypes) { "草稿图片格式不受支持。" }
                    require(byteCount in 1..ContentSubmissionPolicy.maximumImageBytes) { "草稿图片大小无效。" }
                    require(byteCount <= data.available()) { "草稿图片数据不完整。" }
                    ContentSubmissionImage(ByteArray(byteCount).also(data::readFully), mimeType)
                }
                require(target.kind != ContentSubmissionKind.NewThread || images.isEmpty()) {
                    "发布新主题草稿包含不支持的图片。"
                }
                require(data.read() == -1) { "草稿附件包含多余数据。" }
            }
        } catch (error: EOFException) {
            throw IllegalArgumentException("草稿附件数据不完整。", error)
        } catch (error: IOException) {
            throw IllegalArgumentException("草稿附件读取失败。", error)
        }
        return ComposerDraft(
            accountId = entity.accountId,
            target = target,
            title = entity.title,
            body = entity.body,
            images = images,
            updatedAtEpochMillis = entity.updatedAtMilliseconds,
            storedImageCount = images.size,
            storageRevision = entity.storageRevision(),
        )
    }

    private fun decodeTargetMetadata(entity: ContentDraftEntity): ContentSubmissionTarget {
        require(entity.targetMetadata.size in 8..64 * 1_024) { "草稿目标信息大小无效。" }
        val input = ByteArrayInputStream(entity.targetMetadata)
        val target = try {
            DataInputStream(input).use { data ->
                require(data.readInt() == METADATA_MAGIC) { "草稿目标信息格式无效。" }
                require(data.readInt() == VERSION) { "草稿目标信息版本不受支持。" }
                data.readTarget().also { require(input.available() == 0) { "草稿目标信息包含多余数据。" } }
            }
        } catch (error: EOFException) {
            throw IllegalArgumentException("草稿目标信息不完整。", error)
        }
        validateDraftTarget(target)
        require(storageTargetKey(target) == entity.targetKey) { "草稿目标信息校验失败。" }
        return target
    }

    private fun validateEntityText(entity: ContentDraftEntity) {
        require(entity.accountId.isNotBlank()) { "草稿账号信息无效。" }
        require(entity.title.length <= ContentSubmissionPolicy.maximumTitleCharacters) { "草稿标题过长。" }
        require(entity.body.length <= ContentSubmissionPolicy.maximumBodyCharacters) { "草稿正文过长。" }
    }

    private fun DataOutputStream.writeTarget(target: ContentSubmissionTarget) {
        writeBoundedString(target.kind.name)
        writeLong(target.forumId)
        writeBoundedString(target.forumName)
        writeNullableLong(target.threadId)
        writeNullableULong(target.parentPostId)
        writeNullableInt(target.parentFloor)
        writeNullableULong(target.subpostId)
        writeNullableUser(target.replyUser)
    }

    private fun DataInputStream.readTarget(): ContentSubmissionTarget {
        val kindName = readBoundedString()
        val kind = ContentSubmissionKind.entries.firstOrNull { it.name == kindName }
            ?: throw IllegalArgumentException("草稿发布类型无效。")
        return ContentSubmissionTarget(
            kind = kind,
            forumId = readLong(),
            forumName = readBoundedString(),
            threadId = readNullableLong(),
            parentPostId = readNullableULong(),
            parentFloor = readNullableInt(),
            subpostId = readNullableULong(),
            replyUser = readNullableUser(),
        )
    }

    private fun validateDraftTarget(target: ContentSubmissionTarget) {
        require(target.forumId > 0 && target.forumName.isNotBlank()) { "草稿贴吧信息无效。" }
        val parentFloor = target.parentFloor
        require(parentFloor == null || parentFloor > 0) { "草稿楼层信息无效。" }
        when (target.kind) {
            ContentSubmissionKind.NewThread -> Unit
            ContentSubmissionKind.ThreadReply -> require((target.threadId ?: 0) > 0) { "草稿帖子信息无效。" }
            ContentSubmissionKind.PostReply -> {
                require((target.threadId ?: 0) > 0) { "草稿帖子信息无效。" }
                require((target.parentPostId ?: 0u) > 0u) { "草稿回复信息无效。" }
            }
            ContentSubmissionKind.SubpostReply -> {
                require((target.threadId ?: 0) > 0) { "草稿帖子信息无效。" }
                require((target.parentPostId ?: 0u) > 0u) { "草稿回复信息无效。" }
                require((target.subpostId ?: 0u) > 0u) { "草稿楼中楼信息无效。" }
            }
        }
    }

    private fun DataOutputStream.writeBoundedString(value: String, maximum: Int = MAX_STRING_BYTES) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= maximum) { "草稿文本字段过长。" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readBoundedString(maximum: Int = MAX_STRING_BYTES): String {
        val size = readInt()
        require(size in 0..maximum) { "草稿文本字段大小无效。" }
        require(size <= available()) { "草稿文本字段数据不完整。" }
        return ByteArray(size).also(::readFully).toString(Charsets.UTF_8)
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeBoundedString(value)
    }

    private fun DataInputStream.readNullableString(): String? =
        if (readBoolean()) readBoundedString() else null

    private fun DataOutputStream.writeNullableLong(value: Long?) {
        writeBoolean(value != null)
        if (value != null) writeLong(value)
    }

    private fun DataInputStream.readNullableLong(): Long? = if (readBoolean()) readLong() else null

    private fun DataOutputStream.writeNullableULong(value: ULong?) {
        writeBoolean(value != null)
        if (value != null) writeLong(value.toLong())
    }

    private fun DataInputStream.readNullableULong(): ULong? = if (readBoolean()) readLong().toULong() else null

    private fun DataOutputStream.writeNullableInt(value: Int?) {
        writeBoolean(value != null)
        if (value != null) writeInt(value)
    }

    private fun DataInputStream.readNullableInt(): Int? = if (readBoolean()) readInt() else null

    private fun DataOutputStream.writeNullableUser(value: UserSummary?) {
        writeBoolean(value != null)
        if (value == null) return
        writeLong(value.id)
        writeBoundedString(value.name)
        writeBoundedString(value.displayName)
        writeBoundedString(value.portrait)
        writeNullableInt(value.level)
        writeNullableString(value.levelName)
        writeNullableString(value.ipAddress)
    }

    private fun DataInputStream.readNullableUser(): UserSummary? {
        if (!readBoolean()) return null
        return UserSummary(
            id = readLong(),
            name = readBoundedString(),
            displayName = readBoundedString(),
            portrait = readBoundedString(),
            level = readNullableInt(),
            levelName = readNullableString(),
            ipAddress = readNullableString(),
        )
    }
}

private fun ContentDraftEntity.storageRevision(): String = listOf(
    attachmentFileName,
    attachmentByteCount,
    attachmentSHA256,
    updatedAtMilliseconds,
).joinToString(":")

private fun requireCurrentAccount(account: StateFlow<Account?>): Account =
    account.value?.takeIf { it.id.isNotBlank() && it.bduss.isNotBlank() && it.stoken.isNotBlank() }
        ?: throw ContentSubmissionException.NotLoggedIn

private fun ensureCurrentSession(account: StateFlow<Account?>, expected: Account) {
    val current = requireCurrentAccount(account)
    if (current.sessionIdentity() != expected.sessionIdentity()) throw ContentSubmissionException.NotLoggedIn
}

private fun storageTargetKey(target: ContentSubmissionTarget): String = listOf(
    target.kind.name,
    target.forumId,
    target.threadId ?: 0,
    target.parentPostId ?: 0u,
    target.subpostId ?: 0u,
    target.replyUser?.id ?: 0,
).joinToString(":")

private fun AppAppearance.toSettingsAppearance(): SettingsAppearance = when (this) {
    AppAppearance.System -> SettingsAppearance.System
    AppAppearance.Light -> SettingsAppearance.Light
    AppAppearance.Dark -> SettingsAppearance.Dark
}

private fun SettingsAppearance.toAppAppearance(): AppAppearance = when (this) {
    SettingsAppearance.System -> AppAppearance.System
    SettingsAppearance.Light -> AppAppearance.Light
    SettingsAppearance.Dark -> AppAppearance.Dark
}
