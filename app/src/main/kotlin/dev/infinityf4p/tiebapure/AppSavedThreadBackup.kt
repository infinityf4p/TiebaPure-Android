package dev.infinityf4p.tiebapure

import android.content.Context
import android.net.Uri
import dev.infinityf4p.tiebapure.core.data.SavedThreadDao
import dev.infinityf4p.tiebapure.core.data.SavedThreadEntity
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class SavedThreadImportMode { Merge, Replace }

data class SavedThreadBackupImportResult(
    val importedThreads: Int,
    val skippedThreads: Int,
)

internal class AppSavedThreadBackupService(
    context: Context,
    private val dao: SavedThreadDao,
    private val mediaStore: AppSavedThreadMediaStore,
) {
    private val appContext = context.applicationContext

    suspend fun export(uriValue: String): Int = withContext(Dispatchers.IO) {
        val entities = dao.loadAll().take(MAXIMUM_THREADS)
        check(entities.isNotEmpty()) { "没有可导出的本地帖子。" }
        val snapshots = entities.associate { entity ->
            entity.threadId to SavedThreadBlobCodec.decode(entity.snapshotBlob).validated().also {
                check(it.thread.id == entity.threadId)
            }
        }
        var sourceBytes = entities.sumOf { it.snapshotBlob.size.toLong() }
        snapshots.values.forEach { snapshot ->
            val directory = mediaStore.threadDirectory(snapshot.thread.id).canonicalFile
            snapshot.mediaAssets.forEach { asset ->
                val file = File(directory, asset.fileName).canonicalFile
                check(file.parentFile == directory && file.isFile && file.length() == asset.byteCount && sha256(file) == asset.sha256) {
                    "帖子 ${snapshot.thread.id} 的离线媒体不完整，未导出。"
                }
                sourceBytes += file.length()
                check(sourceBytes <= MAXIMUM_ARCHIVE_BYTES) { "备份内容超过 128 MB，请减少完全离线帖子后重试。" }
            }
        }

        val manifest = encodeManifest(entities)
        sourceBytes += manifest.size
        check(sourceBytes <= MAXIMUM_ARCHIVE_BYTES)
        val uri = Uri.parse(uriValue)
        val output = appContext.contentResolver.openOutputStream(uri, "w") ?: error("无法创建备份文件。")
        output.use { raw ->
            val counting = CountingOutputStream(BufferedOutputStream(raw))
            ZipOutputStream(counting).use { zip ->
                zip.setLevel(6)
                zip.writeBytes("manifest.bin", manifest)
                entities.forEach { entity ->
                    zip.writeBytes("Threads/${entity.threadId}.blob", entity.snapshotBlob)
                    val snapshot = checkNotNull(snapshots[entity.threadId])
                    val directory = mediaStore.threadDirectory(entity.threadId)
                    snapshot.mediaAssets.sortedBy(SavedThreadMediaAsset::fileName).forEach { asset ->
                        zip.writeFile("Media/${entity.threadId}/${asset.fileName}", File(directory, asset.fileName))
                    }
                }
            }
            check(counting.count <= MAXIMUM_ARCHIVE_BYTES) { "生成的备份超过 128 MB。" }
        }
        entities.size
    }

    suspend fun import(uriValue: String, mode: SavedThreadImportMode): SavedThreadBackupImportResult =
        withContext(Dispatchers.IO) {
            val temporaryRoot = File(appContext.cacheDir, "saved-thread-import-${UUID.randomUUID()}")
            check(temporaryRoot.mkdir()) { "无法创建备份导入暂存目录。" }
            try {
                val extractedNames = extractArchive(Uri.parse(uriValue), temporaryRoot)
                val manifestFile = File(temporaryRoot, "manifest.bin")
                val manifest = decodeManifest(manifestFile.readBytes())
                val imported = manifest.map { item ->
                    val blobFile = File(temporaryRoot, "Threads/${item.threadId}.blob")
                    check(blobFile.isFile && blobFile.length() in 1..MAXIMUM_SNAPSHOT_BYTES) { "备份帖子快照缺失。" }
                    val blob = blobFile.readBytes()
                    check(sha256(blob) == item.blobSha256) { "备份帖子快照校验失败。" }
                    val snapshot = SavedThreadBlobCodec.decode(blob).validated()
                    check(snapshot.thread.id == item.threadId && snapshot.savedAtMilliseconds == item.savedAtMilliseconds) {
                        "备份清单与帖子快照不一致。"
                    }
                    ImportedThread(item, blob, snapshot)
                }
                val expectedNames = buildSet {
                    add("manifest.bin")
                    imported.forEach { value ->
                        add("Threads/${value.item.threadId}.blob")
                        value.snapshot.mediaAssets.forEach { asset ->
                            add("Media/${value.item.threadId}/${asset.fileName}")
                        }
                    }
                }
                check(extractedNames == expectedNames) { "备份包含缺失或无法识别的文件。" }

                val localById = dao.loadAll().associateBy(SavedThreadEntity::threadId)
                val selected = imported.filter { candidate ->
                    when (mode) {
                        SavedThreadImportMode.Replace -> true
                        SavedThreadImportMode.Merge -> {
                            val local = localById[candidate.item.threadId]
                            local == null || candidate.item.savedAtMilliseconds > local.savedAtMilliseconds
                        }
                    }
                }.sortedByDescending { it.item.savedAtMilliseconds }.take(MAXIMUM_THREADS)

                val prepared = mutableListOf<PreparedSavedThreadMedia>()
                try {
                    selected.forEach { value ->
                        val mediaDirectory = File(temporaryRoot, "Media/${value.item.threadId}").apply { mkdirs() }
                        prepared += mediaStore.prepareImported(value.snapshot, mediaDirectory)
                    }
                } catch (error: Throwable) {
                    prepared.asReversed().forEach { runCatching { it.transaction.rollback() } }
                    throw error
                }
                val transactions = prepared.map(PreparedSavedThreadMedia::transaction)
                try {
                    transactions.forEach(SavedThreadMediaTransaction::commit)
                    val entities = selected.map { value -> value.snapshot.toBackupEntity(value.blob) }
                    dao.importEntries(entities, replace = mode == SavedThreadImportMode.Replace)
                } catch (error: Throwable) {
                    transactions.asReversed().forEach { runCatching { it.rollback() } }
                    throw error
                }
                transactions.forEach { runCatching { it.finish() } }
                val stored = dao.loadAll()
                val snapshots = stored.mapNotNull { entity ->
                    runCatching { SavedThreadBlobCodec.decode(entity.snapshotBlob).validated() }
                        .getOrNull()?.let { entity.threadId to it }
                }.toMap()
                mediaStore.repair(
                    validThreadIds = stored.map(SavedThreadEntity::threadId).toSet(),
                    snapshots = snapshots,
                    textOnlyThreadIds = snapshots
                        .filterValues { it.mediaMode == SavedThreadMediaMode.TextOnly }
                        .keys,
                )
                SavedThreadBackupImportResult(
                    importedThreads = selected.size,
                    skippedThreads = imported.size - selected.size,
                )
            } finally {
                temporaryRoot.deleteRecursively()
            }
        }

    private fun extractArchive(uri: Uri, root: File): Set<String> {
        val declaredLength = appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        check(declaredLength < 0 || declaredLength <= MAXIMUM_ARCHIVE_BYTES) { "备份文件超过 128 MB。" }
        val input = appContext.contentResolver.openInputStream(uri) ?: error("无法读取备份文件。")
        val names = linkedSetOf<String>()
        var totalBytes = 0L
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                check(!entry.isDirectory && entry.name.matches(ENTRY_PATTERN)) { "备份文件路径无效。" }
                check(names.add(entry.name) && names.size <= MAXIMUM_ENTRIES) { "备份文件条目重复或过多。" }
                val destination = File(root, entry.name).canonicalFile
                check(destination.path.startsWith(root.canonicalPath + File.separator)) { "备份文件路径越界。" }
                check(destination.parentFile?.mkdirs() != false || destination.parentFile?.isDirectory == true)
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        totalBytes += count
                        check(totalBytes <= MAXIMUM_ARCHIVE_BYTES) { "备份解压后超过 128 MB。" }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
                zip.closeEntry()
            }
        }
        check(names.isNotEmpty() && totalBytes > 0) { "备份文件为空。" }
        return names
    }

    private fun encodeManifest(entities: List<SavedThreadEntity>): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(MANIFEST_MAGIC)
            output.writeInt(MANIFEST_VERSION)
            output.writeInt(entities.size)
            entities.forEach { entity ->
                output.writeLong(entity.threadId)
                output.writeLong(entity.savedAtMilliseconds)
                output.writeUTF(sha256(entity.snapshotBlob))
            }
        }
        bytes.toByteArray()
    }

    private fun decodeManifest(bytes: ByteArray): List<ManifestItem> {
        check(bytes.size in 12..MAXIMUM_MANIFEST_BYTES) { "备份清单大小无效。" }
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            check(input.readInt() == MANIFEST_MAGIC && input.readInt() == MANIFEST_VERSION) { "备份版本不受支持。" }
            val count = input.readInt()
            check(count in 1..MAXIMUM_THREADS) { "备份帖子数量无效。" }
            List(count) {
                ManifestItem(input.readLong(), input.readLong(), input.readUTF()).also { item ->
                    check(item.threadId > 0 && item.savedAtMilliseconds > 0 && item.blobSha256.matches(DIGEST_PATTERN))
                }
            }.also { values ->
                check(values.map(ManifestItem::threadId).distinct().size == values.size && input.available() == 0) {
                    "备份清单重复或包含多余数据。"
                }
            }
        }
    }

    private data class ManifestItem(val threadId: Long, val savedAtMilliseconds: Long, val blobSha256: String)
    private data class ImportedThread(val item: ManifestItem, val blob: ByteArray, val snapshot: SavedThreadSnapshot)

    private companion object {
        const val MANIFEST_MAGIC = 0x5450424B
        const val MANIFEST_VERSION = 1
        const val MAXIMUM_THREADS = 100
        const val MAXIMUM_ENTRIES = 5_000
        const val MAXIMUM_MANIFEST_BYTES = 256 * 1_024
        const val MAXIMUM_SNAPSHOT_BYTES = 16L * 1_024 * 1_024
        const val MAXIMUM_ARCHIVE_BYTES = 128L * 1_024 * 1_024
        val DIGEST_PATTERN = Regex("^[0-9a-f]{64}$")
        val ENTRY_PATTERN = Regex(
            "^(manifest\\.bin|Threads/[1-9][0-9]*\\.blob|Media/[1-9][0-9]*/[a-z0-9_-]{1,100}\\.[a-z0-9]{1,8})$",
        )
    }
}

private class CountingOutputStream(output: OutputStream) : FilterOutputStream(output) {
    var count: Long = 0
        private set

    override fun write(value: Int) {
        out.write(value)
        count += 1
        check(count <= 128L * 1_024 * 1_024) { "备份文件超过 128 MB。" }
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        out.write(buffer, offset, length)
        count += length
        check(count <= 128L * 1_024 * 1_024) { "备份文件超过 128 MB。" }
    }
}

private fun ZipOutputStream.writeBytes(name: String, bytes: ByteArray) {
    putNextEntry(ZipEntry(name).apply { time = 0L })
    write(bytes)
    closeEntry()
}

private fun ZipOutputStream.writeFile(name: String, file: File) {
    putNextEntry(ZipEntry(name).apply { time = 0L })
    file.inputStream().use { it.copyTo(this) }
    closeEntry()
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun SavedThreadSnapshot.toBackupEntity(blob: ByteArray) = SavedThreadEntity(
    threadId = thread.id,
    title = thread.title.ifBlank { thread.textPreview },
    authorName = thread.author.resolvedDisplayName,
    forumName = forum.displayName.ifBlank { forum.name },
    savedAtMilliseconds = savedAtMilliseconds,
    snapshotBlob = blob,
    mediaMode = mediaMode.name,
    mediaByteCount = mediaAssets.sumOf(SavedThreadMediaAsset::byteCount),
    newReplyCount = newReplyCount,
    lastCheckedAtMilliseconds = lastCheckedAtMilliseconds,
    metadataVersion = 1,
)
