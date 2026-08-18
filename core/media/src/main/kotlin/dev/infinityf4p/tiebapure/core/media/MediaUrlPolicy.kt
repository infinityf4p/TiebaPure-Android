package dev.infinityf4p.tiebapure.core.media

import java.net.URI
import dev.infinityf4p.tiebapure.core.model.TiebaEmoticon
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object MediaUrlPolicy {
    private val allowedHosts = setOf(
        "baidu.com",
        "bdimg.com",
        "bdstatic.com",
    )

    fun isAllowed(rawUrl: String?): Boolean {
        val raw = rawUrl ?: return false
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        val url = raw.toHttpUrlOrNull() ?: return false
        if (url.scheme != "https" || uri.rawUserInfo != null || url.port != 443) return false
        if (url.fragment != null || uri.rawPath.isNullOrEmpty()) return false
        val host = url.host.trimEnd('.').lowercase()
        return allowedHosts.any { host == it || host.endsWith(".$it") }
    }

    fun isAllowedDownloadableVideo(rawUrl: String?): Boolean {
        if (!isAllowed(rawUrl)) return false
        val path = rawUrl?.toHttpUrlOrNull()?.encodedPath ?: return false
        val finalComponent = path.substringAfterLast('/').lowercase()
        return !finalComponent.endsWith(".m3u8") && !finalComponent.endsWith(".m3u")
    }

    fun isAllowedDirectMp4(rawUrl: String?): Boolean = isAllowedDownloadableVideo(rawUrl)

    fun isAllowedTiebaEmoticon(rawUrl: String?): Boolean {
        val raw = rawUrl ?: return false
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        val url = raw.toHttpUrlOrNull() ?: return false
        if (url.scheme != "https" || url.host != TiebaEmoticon.host || url.port != 443) return false
        if (uri.rawUserInfo != null || url.query != null || url.fragment != null) return false
        val prefix = "/tb/editor/images/client/"
        val path = url.encodedPath
        if (!path.startsWith(prefix) || !path.endsWith(".png")) return false
        val imageName = path.substring(prefix.length, path.length - ".png".length)
        return TiebaEmoticon.isValidImageName(imageName) &&
            TiebaEmoticon.imageUrlFor(imageName) == url.toString()
    }

    internal fun resolveRedirect(currentUrl: String, location: String): String? {
        val resolved = currentUrl.toHttpUrlOrNull()?.resolve(location) ?: return null
        return resolved.toString().takeIf(::isAllowed)
    }

    internal fun resolveVideoRedirect(currentUrl: String, location: String): String? =
        resolveRedirect(currentUrl, location)?.takeIf(::isAllowedDownloadableVideo)

    internal fun resolveEmoticonRedirect(currentUrl: String, location: String): String? {
        val resolved = currentUrl.toHttpUrlOrNull()?.resolve(location) ?: return null
        return resolved.toString().takeIf(::isAllowedTiebaEmoticon)
    }
}
