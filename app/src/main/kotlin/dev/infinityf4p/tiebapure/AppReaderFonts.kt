package dev.infinityf4p.tiebapure

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.system.Os
import dev.infinityf4p.tiebapure.core.designsystem.ReaderTypefaceRegistry
import dev.infinityf4p.tiebapure.core.model.ImportedReaderFont
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AppReaderFontStore(private val context: Context) {
    private val root = File(context.filesDir, "reader-fonts")
    private val catalog = File(root, "fonts.bin")
    private val mutex = Mutex()
    private val mutableEntries = MutableStateFlow<List<ImportedReaderFont>>(emptyList())
    private val mutableReady = MutableStateFlow(false)
    private val mutableRevision = MutableStateFlow(0L)

    val entries: StateFlow<List<ImportedReaderFont>> = mutableEntries.asStateFlow()
    val revision: StateFlow<Long> = mutableRevision.asStateFlow()

    suspend fun load() = mutex.withLock {
        withContext(Dispatchers.IO) {
            prepareRoot()
            root.listFiles().orEmpty()
                .filter { it.name.startsWith(".staging-") }
                .forEach(File::delete)
            var catalogNeedsRewrite = catalog.exists().not()
            val decoded = if (catalog.exists()) {
                runCatching { readCatalog() }.getOrElse {
                    catalogNeedsRewrite = true
                    discoverStoredFonts()
                }
            } else {
                discoverStoredFonts()
            }
            val valid = decoded.distinctBy(ImportedReaderFont::id)
                .take(MAXIMUM_FONTS)
                .mapNotNull(::validateStoredFont)
            val validIDs = valid.map(ImportedReaderFont::id).toSet()
            mutableEntries.value.filter { it.id !in validIDs }.forEach {
                ReaderTypefaceRegistry.unregister(it.id)
            }
            valid.forEach { entry ->
                val file = File(root, "${entry.id}.${entry.fileExtension}")
                ReaderTypefaceRegistry.register(entry.id, Typeface.createFromFile(file))
            }
            if (catalogNeedsRewrite || valid != decoded) runCatching { writeCatalog(valid) }
            val referenced = valid.map { "${it.id}.${it.fileExtension}" }.toSet()
            root.listFiles().orEmpty()
                .filter { it.isFile && it != catalog && !it.name.startsWith(".") && it.name !in referenced }
                .forEach(File::delete)
            mutableEntries.value = valid
            mutableReady.value = true
            mutableRevision.value += 1
        }
    }

    suspend fun import(uriValue: String): ImportedReaderFont = mutex.withLock {
        withContext(Dispatchers.IO) {
            check(mutableReady.value) { "字体目录仍在初始化，请稍后重试。" }
            prepareRoot()
            val uri = Uri.parse(uriValue)
            val displayFileName = queryDisplayName(uri)
            val extension = displayFileName.substringAfterLast('.', "").lowercase()
            require(extension in ALLOWED_EXTENSIONS) { "请选择 TTF 或 OTF 字体文件。" }
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    check(total <= MAXIMUM_FONT_BYTES) { "字体文件超过 20 MB，未导入。" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: error("无法读取这个字体文件。")
            check(bytes.isNotEmpty()) { "字体文件为空。" }
            val id = sha256(bytes)
            mutableEntries.value.firstOrNull { it.id == id }?.let { return@withContext it }
            check(mutableEntries.value.size < MAXIMUM_FONTS) { "最多导入 20 个字体。" }

            val staging = File(root, ".staging-${UUID.randomUUID()}.$extension")
            val final = File(root, "$id.$extension")
            var committedNewFile = false
            try {
                FileOutputStream(staging).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
                restrictToOwner(staging)
                val typeface = Typeface.createFromFile(staging)
                if (!final.exists()) {
                    if (!staging.renameTo(final)) {
                        staging.copyTo(final, overwrite = false)
                        check(staging.delete()) { "无法完成字体文件写入。" }
                    }
                    committedNewFile = true
                } else {
                    check(final.length() == bytes.size.toLong() && sha256(final) == id) {
                        "字体文件摘要冲突。"
                    }
                    staging.delete()
                }
                restrictToOwner(final)
                val entry = ImportedReaderFont(
                    id = id,
                    displayName = sanitizeDisplayName(displayFileName),
                    fileExtension = extension,
                    byteCount = bytes.size.toLong(),
                )
                val updated = (mutableEntries.value + entry).distinctBy(ImportedReaderFont::id)
                writeCatalog(updated)
                ReaderTypefaceRegistry.register(id, typeface)
                mutableEntries.value = updated
                mutableRevision.value += 1
                entry
            } catch (error: Throwable) {
                staging.delete()
                if (committedNewFile) final.delete()
                throw IllegalStateException(error.message ?: "字体导入失败。", error)
            }
        }
    }

    suspend fun remove(id: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = mutableEntries.value
            val target = current.firstOrNull { it.id == id } ?: return@withContext
            val updated = current.filterNot { it.id == id }
            writeCatalog(updated)
            ReaderTypefaceRegistry.unregister(id)
            File(root, "${target.id}.${target.fileExtension}").delete()
            mutableEntries.value = updated
            mutableRevision.value += 1
        }
    }

    private fun prepareRoot() {
        check(root.isDirectory || root.mkdirs()) { "无法创建字体私有目录。" }
        check(root.canonicalFile.parentFile == context.filesDir.canonicalFile) { "字体目录无效。" }
        root.setReadable(false, false)
        root.setWritable(false, false)
        root.setExecutable(false, false)
        check(root.setReadable(true, true) && root.setWritable(true, true) && root.setExecutable(true, true)) {
            "无法保护字体私有目录。"
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        val queried = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        return queried?.takeIf(String::isNotBlank) ?: uri.lastPathSegment.orEmpty()
    }

    private fun sanitizeDisplayName(fileName: String): String {
        val withoutExtension = fileName.substringBeforeLast('.').trim()
            .filterNot { it.isISOControl() }
            .take(100)
        return withoutExtension.ifBlank { "自定义字体" }
    }

    private fun validateStoredFont(entry: ImportedReaderFont): ImportedReaderFont? {
        if (!entry.id.matches(DIGEST_PATTERN) || entry.fileExtension !in ALLOWED_EXTENSIONS ||
            entry.byteCount !in 1..MAXIMUM_FONT_BYTES.toLong() ||
            entry.displayName.isBlank() || entry.displayName.length > 100
        ) return null
        val file = File(root, "${entry.id}.${entry.fileExtension}")
        if (!file.isFile || file.canonicalFile.parentFile != root.canonicalFile ||
            file.length() != entry.byteCount || sha256(file) != entry.id
        ) return null
        return runCatching { Typeface.createFromFile(file); entry }.getOrNull()
    }

    private fun discoverStoredFonts(): List<ImportedReaderFont> = root.listFiles().orEmpty()
        .filter(File::isFile)
        .mapNotNull { file ->
            val extension = file.extension.lowercase()
            val id = file.nameWithoutExtension.lowercase()
            if (extension !in ALLOWED_EXTENSIONS || !id.matches(DIGEST_PATTERN) ||
                file.length() !in 1..MAXIMUM_FONT_BYTES.toLong() || sha256(file) != id
            ) return@mapNotNull null
            runCatching { Typeface.createFromFile(file) }.getOrNull() ?: return@mapNotNull null
            ImportedReaderFont(
                id = id,
                displayName = "自定义字体 ${id.take(8)}",
                fileExtension = extension,
                byteCount = file.length(),
            )
        }
        .distinctBy(ImportedReaderFont::id)
        .take(MAXIMUM_FONTS)

    private fun readCatalog(): List<ImportedReaderFont> {
        if (!catalog.exists()) return emptyList()
        val encoded = catalog.readBytes()
        check(encoded.size in (CHECKSUM_BYTES + 8)..MAXIMUM_CATALOG_BYTES) { "字体目录索引无效。" }
        val payload = encoded.copyOfRange(0, encoded.size - CHECKSUM_BYTES)
        val checksum = encoded.copyOfRange(encoded.size - CHECKSUM_BYTES, encoded.size)
        check(MessageDigest.isEqual(checksum, digest(payload))) { "字体目录索引校验失败。" }
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            check(input.readInt() == CATALOG_MAGIC && input.readInt() == CATALOG_VERSION)
            val count = input.readInt()
            check(count in 0..MAXIMUM_FONTS)
            List(count) {
                ImportedReaderFont(
                    id = input.readUTF(),
                    displayName = input.readUTF(),
                    fileExtension = input.readUTF(),
                    byteCount = input.readLong(),
                )
            }.also { check(input.available() == 0) }
        }
    }

    private fun writeCatalog(values: List<ImportedReaderFont>) {
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(CATALOG_MAGIC)
                output.writeInt(CATALOG_VERSION)
                output.writeInt(values.size)
                values.forEach { entry ->
                    output.writeUTF(entry.id)
                    output.writeUTF(entry.displayName)
                    output.writeUTF(entry.fileExtension)
                    output.writeLong(entry.byteCount)
                }
            }
            bytes.toByteArray()
        }
        check(payload.size + CHECKSUM_BYTES <= MAXIMUM_CATALOG_BYTES)
        val temporary = File(root, ".staging-catalog-${UUID.randomUUID()}")
        FileOutputStream(temporary).use { output ->
            output.write(payload)
            output.write(digest(payload))
            output.fd.sync()
        }
        restrictToOwner(temporary)
        try {
            Os.rename(temporary.absolutePath, catalog.absolutePath)
        } finally {
            temporary.delete()
        }
        restrictToOwner(catalog)
    }

    private fun restrictToOwner(file: File) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        check(file.setReadable(true, true) && file.setWritable(true, true)) { "无法保护字体文件。" }
    }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String = digest(bytes).toHex()
    private fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val MAXIMUM_FONT_BYTES = 20 * 1_024 * 1_024
        const val MAXIMUM_FONTS = 20
        const val MAXIMUM_CATALOG_BYTES = 64 * 1_024
        const val CHECKSUM_BYTES = 32
        const val CATALOG_MAGIC = 0x54504654
        const val CATALOG_VERSION = 1
        val ALLOWED_EXTENSIONS = setOf("ttf", "otf")
        val DIGEST_PATTERN = Regex("[0-9a-f]{64}")
    }
}
