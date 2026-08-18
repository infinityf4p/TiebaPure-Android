package dev.infinityf4p.tiebapure

import java.net.URI
import java.net.URLDecoder

internal enum class ExternalNavigationAction {
    View,
    SendText,
}

internal data class ExternalNavigationInput(
    val action: ExternalNavigationAction,
    val data: String? = null,
    val mimeType: String? = null,
    val sharedText: String? = null,
)

internal sealed interface ExternalNavigationDestination {
    data class Thread(val threadId: Long) : ExternalNavigationDestination
    data class Forum(val name: String) : ExternalNavigationDestination
    data class Search(val query: String?) : ExternalNavigationDestination
}

internal data class ExternalNavigationEvent(
    val id: Long,
    val destination: ExternalNavigationDestination,
)

internal fun parseExternalNavigation(input: ExternalNavigationInput): ExternalNavigationDestination? = when (input.action) {
    ExternalNavigationAction.View -> input.data?.let(::parseExternalNavigationUri)
    ExternalNavigationAction.SendText -> {
        if (!input.mimeType.equals("text/plain", ignoreCase = true)) null
        else input.sharedText?.let(::findSharedTiebaThread)
    }
}

internal fun buildExternalNavigationRoute(
    destination: ExternalNavigationDestination,
    encode: (String) -> String,
): String = when (destination) {
    is ExternalNavigationDestination.Thread -> buildThreadRoute(destination.threadId)
    is ExternalNavigationDestination.Forum -> "forum/${encode(destination.name.removeSuffix("吧"))}"
    is ExternalNavigationDestination.Search -> buildSearchRoute(destination.query, encode)
}

internal fun buildSearchRoute(query: String?, encode: (String) -> String): String {
    val normalized = query?.trim().orEmpty()
    return if (normalized.isEmpty()) "search" else "search?query=${encode(normalized)}"
}

private const val TiebaHost = "tieba.baidu.com"
private const val MaxSharedTextLength = 32_768
private const val MaxForumNameLength = 100
private const val MaxSearchQueryLength = 256

private val webThreadPath = Regex("^/p/([0-9]+)/?$")
private val customThreadPath = Regex("^/([0-9]+)/?$")
private val sharedHttpsCandidate = Regex("https://[^\\s<>\\\"']+", RegexOption.IGNORE_CASE)
private val trailingSharedUrlPunctuation = setOf(
    '.', ',', ';', ':', '!', '?', ')', ']', '}',
    '。', '，', '；', '：', '！', '？', '”', '’',
)

private fun parseExternalNavigationUri(raw: String): ExternalNavigationDestination? {
    val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
    return when {
        uri.scheme.equals("https", ignoreCase = true) -> parseTiebaWebThread(uri)
        uri.scheme.equals("tiebapure", ignoreCase = true) -> parseTiebaPureUri(uri)
        else -> null
    }
}

private fun parseTiebaWebThread(uri: URI): ExternalNavigationDestination? {
    if (uri.rawUserInfo != null) return null
    if (!uri.host.equals(TiebaHost, ignoreCase = true)) return null
    if (uri.port != -1 && uri.port != 443) return null
    val id = webThreadPath.matchEntire(uri.rawPath.orEmpty())
        ?.groupValues
        ?.get(1)
        ?.toPositiveLongOrNull()
        ?: return null
    return ExternalNavigationDestination.Thread(id)
}

private fun parseTiebaPureUri(uri: URI): ExternalNavigationDestination? {
    if (uri.rawUserInfo != null || uri.port != -1 || uri.rawFragment != null) return null
    return when (uri.host?.lowercase()) {
        "thread" -> parseCustomThread(uri)
        "forum" -> parseCustomForum(uri)
        "search" -> parseCustomSearch(uri)
        else -> null
    }
}

private fun parseCustomThread(uri: URI): ExternalNavigationDestination? {
    if (uri.rawQuery != null) return null
    val id = customThreadPath.matchEntire(uri.rawPath.orEmpty())
        ?.groupValues
        ?.get(1)
        ?.toPositiveLongOrNull()
        ?: return null
    return ExternalNavigationDestination.Thread(id)
}

private fun parseCustomForum(uri: URI): ExternalNavigationDestination? {
    if (uri.rawQuery != null) return null
    val segment = singleRawPathSegment(uri) ?: return null
    val decoded = decodeUriComponent(segment, plusAsSpace = false) ?: return null
    val name = decoded.trim().removeSuffix("吧").trim()
    if (!name.isSafeExternalText(MaxForumNameLength) || name.any { it == '/' || it == '\\' }) return null
    return ExternalNavigationDestination.Forum(name)
}

private fun parseCustomSearch(uri: URI): ExternalNavigationDestination? {
    val rawPath = uri.rawPath.orEmpty()
    val decoded = when {
        rawPath.isEmpty() || rawPath == "/" -> {
            val rawQuery = uri.rawQuery ?: return ExternalNavigationDestination.Search(null)
            parseSingleQueryValue(rawQuery, setOf("q", "query")) ?: return null
        }
        uri.rawQuery == null -> {
            val segment = singleRawPathSegment(uri) ?: return null
            decodeUriComponent(segment, plusAsSpace = false) ?: return null
        }
        else -> return null
    }
    val query = decoded.trim().takeIf(String::isNotEmpty)
    if (query != null && !query.isSafeExternalText(MaxSearchQueryLength)) return null
    return ExternalNavigationDestination.Search(query)
}

private fun findSharedTiebaThread(text: String): ExternalNavigationDestination? =
    sharedHttpsCandidate.findAll(text.take(MaxSharedTextLength))
        .map { match -> match.value.trimEnd { it in trailingSharedUrlPunctuation } }
        .mapNotNull { candidate -> parseExternalNavigationUri(candidate) as? ExternalNavigationDestination.Thread }
        .firstOrNull()

private fun singleRawPathSegment(uri: URI): String? {
    val raw = uri.rawPath.orEmpty()
    if (!raw.startsWith('/')) return null
    val segment = raw.removePrefix("/").removeSuffix("/")
    return segment.takeIf { it.isNotEmpty() && '/' !in it }
}

private fun parseSingleQueryValue(rawQuery: String, acceptedNames: Set<String>): String? {
    if (rawQuery.isEmpty()) return ""
    val parts = rawQuery.split('&')
    if (parts.size != 1) return null
    val pair = parts.single().split('=', limit = 2)
    val name = decodeUriComponent(pair[0], plusAsSpace = true)?.lowercase() ?: return null
    if (name !in acceptedNames) return null
    return decodeUriComponent(pair.getOrElse(1) { "" }, plusAsSpace = true)
}

private fun decodeUriComponent(value: String, plusAsSpace: Boolean): String? {
    val encoded = if (plusAsSpace) value else value.replace("+", "%2B")
    return runCatching { URLDecoder.decode(encoded, Charsets.UTF_8.name()) }
        .getOrNull()
        ?.takeUnless { '\ufffd' in it }
}

private fun String.toPositiveLongOrNull(): Long? = toLongOrNull()?.takeIf { it > 0L }

private fun String.isSafeExternalText(maxLength: Int): Boolean =
    isNotEmpty() && length <= maxLength && none { it.isISOControl() || it == '\ufffd' }
