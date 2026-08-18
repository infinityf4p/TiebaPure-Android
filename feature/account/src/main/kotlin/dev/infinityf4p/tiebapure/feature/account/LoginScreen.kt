package dev.infinityf4p.tiebapure.feature.account

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun LoginRoute(
    viewModel: LoginViewModel,
    onLoggedIn: (dev.infinityf4p.tiebapure.core.model.Account) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    state.account?.let { account -> LaunchedEffect(account) { onLoggedIn(account) } }
    state.errorMessage?.let { message ->
        LaunchedEffect(message) {
            onError(message)
            viewModel.consumeError()
        }
    }
    LoginScreen(
        modifier = modifier,
        isValidating = state.isValidating,
        onCookiesReady = viewModel::complete,
        onError = viewModel::reportWebError,
    )
}

@Composable
fun LoginScreen(
    modifier: Modifier = Modifier,
    isValidating: Boolean = false,
    onCookiesReady: (BaiduLoginCookies) -> Unit,
    onError: (String) -> Unit,
) {
    var isLoading by remember { mutableStateOf(true) }
    var webView: WebView? by remember { mutableStateOf(null) }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                webViewClient = WebViewClient()
                clearHistory()
                removeAllViews()
                destroy()
            }
            webView = null
            clearBaiduWebSession()
        }
    }

    Box(modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                secureLoginWebView(
                    context = context,
                    onLoading = { isLoading = it },
                    onCookiesReady = onCookiesReady,
                    onError = onError,
                ).also { webView = it }
            },
        )
        if (isLoading || isValidating) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator()
                if (isValidating) Text("正在验证登录", color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun secureLoginWebView(
    context: android.content.Context,
    onLoading: (Boolean) -> Unit,
    onCookiesReady: (BaiduLoginCookies) -> Unit,
    onError: (String) -> Unit,
): WebView {
    val cookies = CookieManager.getInstance().apply { setAcceptCookie(true) }
    return IsolatedLoginWebView(context).apply {
        settings.javaScriptEnabled = true
        settings.databaseEnabled = false
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.setSupportMultipleWindows(false)
        settings.userAgentString = ANDROID_LOGIN_USER_AGENT
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) settings.safeBrowsingEnabled = true

        webViewClient = object : WebViewClient() {
            private var completed = false
            private var isRecoveringBlockedNavigation = false
            private var isCompletingTrustedPage = false

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handleNavigation(view, request.url)

            @Deprecated("Compatibility for API 23")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                handleNavigation(view, url.toUri())

            private fun handleNavigation(view: WebView, uri: Uri): Boolean {
                val raw = uri.toString()
                if (LoginBoundary.isCompletionUrl(raw)) {
                    // If STOKEN has not landed yet, let the trusted completion
                    // page load so its own cookie exchange can finish.
                    return complete(view)
                }
                if (LoginBoundary.isAllowedUrl(raw)) return false
                if (LoginBoundary.isExternalAppRedirect(raw)) {
                    if (!isRecoveringBlockedNavigation) {
                        isRecoveringBlockedNavigation = true
                        recoverAfterBlockedRedirect(view, BLOCKED_REDIRECT_COOKIE_RETRIES)
                    }
                } else {
                    onError("登录页面尝试打开不受信任的地址，已阻止。")
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                onLoading(true)
            }

            override fun onPageFinished(view: WebView, url: String) {
                onLoading(false)
                if (LoginBoundary.isCompletionUrl(url) && !complete(view) && !isCompletingTrustedPage) {
                    isCompletingTrustedPage = true
                    completeAfterTrustedPage(view, COMPLETION_COOKIE_RETRIES)
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) onError("登录页面加载失败，请检查网络后重试。")
            }

            private fun complete(view: WebView): Boolean {
                if (completed) return true
                val headers = COOKIE_ORIGINS.map(cookies::getCookie)
                val extracted = LoginBoundary.extractCookies(headers) ?: return false
                completed = true
                isRecoveringBlockedNavigation = false
                isCompletingTrustedPage = false
                view.stopLoading()
                onCookiesReady(extracted)
                return true
            }

            private fun completeAfterTrustedPage(view: WebView, remainingRetries: Int) {
                if (completed || complete(view)) return
                if (remainingRetries > 0) {
                    view.postDelayed(
                        { completeAfterTrustedPage(view, remainingRetries - 1) },
                        COMPLETION_COOKIE_RETRY_MILLIS,
                    )
                } else {
                    isCompletingTrustedPage = false
                    onError("登录页面已返回，但登录凭据不完整，请重试。")
                }
            }

            private fun recoverAfterBlockedRedirect(view: WebView, remainingRetries: Int) {
                val headers = COOKIE_ORIGINS.map(cookies::getCookie)
                if (LoginBoundary.hasPrimaryLoginCookie(headers)) {
                    isRecoveringBlockedNavigation = false
                    view.stopLoading()
                    view.loadUrl(LoginBoundary.completionUrl)
                    return
                }
                if (remainingRetries > 0) {
                    view.postDelayed(
                        { recoverAfterBlockedRedirect(view, remainingRetries - 1) },
                        BLOCKED_REDIRECT_COOKIE_RETRY_MILLIS,
                    )
                } else {
                    isRecoveringBlockedNavigation = false
                    onError("登录跳转已被拦截，但尚未取得登录凭据，请重试。")
                }
            }
        }
        onLoading(true)
        cookies.removeAllCookies {
            cookies.flush()
            post {
                if (acceptsInitialLoad) loadUrl(LoginBoundary.loginUrl)
            }
        }
    }
}

private class IsolatedLoginWebView(context: android.content.Context) : WebView(context) {
    var acceptsInitialLoad = true
        private set

    override fun destroy() {
        acceptsInitialLoad = false
        super.destroy()
    }
}

fun clearBaiduWebSession(onComplete: (() -> Unit)? = null) {
    CookieManager.getInstance().removeAllCookies {
        CookieManager.getInstance().flush()
        onComplete?.invoke()
    }
    WebView.clearClientCertPreferences(null)
}

private const val ANDROID_LOGIN_USER_AGENT = "Mozilla/5.0 (Linux; Android 13; Mobile) " +
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

private val COOKIE_ORIGINS = listOf(
    "https://baidu.com/",
    "https://wappass.baidu.com/",
    "https://passport.baidu.com/",
    "https://tieba.baidu.com/",
    "https://tiebac.baidu.com/",
)

private const val BLOCKED_REDIRECT_COOKIE_RETRIES = 4
private const val BLOCKED_REDIRECT_COOKIE_RETRY_MILLIS = 200L
private const val COMPLETION_COOKIE_RETRIES = 4
private const val COMPLETION_COOKIE_RETRY_MILLIS = 200L
