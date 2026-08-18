package dev.infinityf4p.tiebapure

import android.content.Context
import dev.infinityf4p.tiebapure.core.data.ContentDraftEntity
import java.io.File
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal data class StoredDraftAttachment(
    val fileName: String,
    val byteCount: Long,
    val sha256: String,
)

/** Stores immutable draft attachment blobs outside SQLite; Room is the commit point. */
internal class AppDraftFileStore private constructor(
    rawDirectory: File,
) {
    private val directory = rawDirectory.canonicalFile

    constructor(context: Context) : this(File(context.filesDir, "content-drafts/v1"))

    init {
        prepareDirectory()
    }

    suspend fun stage(write: (OutputStream) -> Unit): StoredDraftAttachment {
        coroutineContext.ensureActive()
        prepareDirectory()
        val identifier = UUID.randomUUID().toString().lowercase()
        val finalFile = checkedFile("$identifier.tpdr")
        val temporaryFile = checkedFile(".$identifier.tmp")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val byteCount: Long
            FileOutputStream(temporaryFile).use { fileOutput ->
                val limited = DigestingLimitedOutputStream(fileOutput, digest, MAX_ATTACHMENT_BYTES.toLong())
                val buffered = BufferedOutputStream(limited)
                write(buffered)
                buffered.flush()
                byteCount = limited.byteCount
                require(byteCount >= 8) { "草稿附件数据无效。" }
                fileOutput.fd.sync()
            }
            hardenFile(temporaryFile)
            coroutineContext.ensureActive()
            check(temporaryFile.renameTo(finalFile)) { "无法提交草稿附件。" }
            hardenFile(finalFile)
            syncDirectoryBestEffort()
            return StoredDraftAttachment(finalFile.name, byteCount, digest.toHex())
        } catch (error: Throwable) {
            temporaryFile.delete()
            finalFile.delete()
            throw error
        }
    }

    suspend fun <T> read(entity: ContentDraftEntity, decode: (InputStream) -> T): T {
        coroutineContext.ensureActive()
        require(entity.attachmentByteCount in 8..MAX_ATTACHMENT_BYTES.toLong()) { "草稿附件大小无效。" }
        require(entity.attachmentSHA256.matches(SHA256_PATTERN)) { "草稿附件校验值无效。" }
        val file = checkedFile(entity.attachmentFileName)
        require(file.exists() && file.isFile && file.canonicalPath == file.absolutePath) { "草稿附件不存在或不安全。" }
        require(file.length() == entity.attachmentByteCount) { "草稿附件大小不匹配。" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        coroutineContext.ensureActive()
        require(digest.toHex() == entity.attachmentSHA256) {
            "草稿附件校验失败。"
        }
        return file.inputStream().buffered().use(decode)
    }

    fun delete(fileName: String) {
        runCatching {
            val file = checkedFile(fileName)
            if (file.exists()) check(file.isFile && file.canonicalPath == file.absolutePath)
            if (file.exists()) check(file.delete())
        }
    }

    fun cleanup(referencedFileNames: Set<String>) {
        runCatching {
            prepareDirectory()
            directory.listFiles().orEmpty().forEach { file ->
                val keep = file.name in referencedFileNames && FILE_NAME_PATTERN.matches(file.name)
                if (!keep && file.isFile && file.canonicalPath == file.absolutePath) file.delete()
            }
        }
    }

    private fun prepareDirectory() {
        if (!directory.exists()) check(directory.mkdirs()) { "无法创建草稿附件目录。" }
        check(directory.isDirectory && directory.canonicalPath == directory.absolutePath) { "草稿附件目录不安全。" }
        check(directory.setReadable(false, false) && directory.setWritable(false, false) && directory.setExecutable(false, false))
        check(directory.setReadable(true, true) && directory.setWritable(true, true) && directory.setExecutable(true, true))
    }

    private fun checkedFile(fileName: String): File {
        require(FILE_NAME_PATTERN.matches(fileName) || TEMP_FILE_NAME_PATTERN.matches(fileName)) { "草稿附件文件名无效。" }
        val file = File(directory, fileName)
        check(file.parentFile?.canonicalFile == directory.canonicalFile) { "草稿附件路径无效。" }
        return file
    }

    private fun hardenFile(file: File) {
        check(file.setReadable(false, false) && file.setWritable(false, false) && file.setExecutable(false, false))
        check(file.setReadable(true, true) && file.setWritable(true, true))
    }

    private fun syncDirectoryBestEffort() {
        runCatching {
            val descriptor = android.system.Os.open(
                directory.absolutePath,
                android.system.OsConstants.O_RDONLY,
                0,
            )
            try {
                android.system.Os.fsync(descriptor)
            } finally {
                android.system.Os.close(descriptor)
            }
        }
    }

    companion object {
        const val MAX_ATTACHMENT_BYTES = 96 * 1_024 * 1_024
        private val FILE_NAME_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.tpdr")
        private val TEMP_FILE_NAME_PATTERN = Regex("\\.[0-9a-f-]{36}\\.tmp")
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")

        fun forTests(directory: File): AppDraftFileStore = AppDraftFileStore(directory)

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    }
}

private class DigestingLimitedOutputStream(
    private val output: OutputStream,
    private val digest: MessageDigest,
    private val maximumByteCount: Long,
) : OutputStream() {
    var byteCount: Long = 0
        private set

    override fun write(value: Int) {
        checkCapacity(1)
        output.write(value)
        digest.update(value.toByte())
        byteCount += 1
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size)
        checkCapacity(length)
        output.write(buffer, offset, length)
        digest.update(buffer, offset, length)
        byteCount += length
    }

    override fun flush() = output.flush()

    private fun checkCapacity(additional: Int) {
        require(byteCount + additional <= maximumByteCount) { "草稿附件总大小超出限制。" }
    }
}

private fun MessageDigest.toHex(): String = digest()
    .toHex()

private fun ByteArray.toHex(): String =
    joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
