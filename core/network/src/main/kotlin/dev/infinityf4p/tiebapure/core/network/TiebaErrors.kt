package dev.infinityf4p.tiebapure.core.network

import dev.infinityf4p.tiebapure.core.model.SubmissionVerificationChallenge
import java.io.IOException
import dev.infinityf4p.tiebapure.core.model.MutationOutcomeUnknown

sealed class TiebaNetworkException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class InvalidRequest(message: String) : TiebaNetworkException(message)
    class UnsafeRedirect(val destination: String?) : TiebaNetworkException("Refused unsafe redirect to $destination")
    class TooManyRedirects(val limit: Int) : TiebaNetworkException("Exceeded $limit redirects")
    class ResponseTooLarge(val limitBytes: Long) : TiebaNetworkException("Response exceeds $limitBytes bytes")
    class HttpStatus(val code: Int, val bodyPreview: String) : TiebaNetworkException("HTTP $code")
    class Decode(cause: Throwable) : TiebaNetworkException("Unable to decode server response", cause)
}

sealed class TiebaApiException(message: String) : IOException(message) {
    data object EmptyResponse : TiebaApiException("贴吧返回了空响应。")
    data class Business(val code: Int, val serverMessage: String) : TiebaApiException(serverMessage)
    data class SessionExpired(val code: Int?, val serverMessage: String) : TiebaApiException("登录状态已失效：$serverMessage")
}

sealed class TiebaAuthenticationException(message: String) : IOException(message) {
    data object InvalidCredentials : TiebaAuthenticationException("登录 Cookie 不完整或包含非法字符。")
    data object MissingAccountInfo : TiebaAuthenticationException("贴吧没有返回可用的账号资料，请重新登录。")
}

sealed class TiebaMutationException(message: String) : IllegalArgumentException(message) {
    data object MissingTbs : TiebaMutationException("未能刷新登录校验信息，请重新登录后再试。")
    data object InvalidThreadId : TiebaMutationException("帖子 ID 无效，无法完成操作。")
    data object InvalidPostId : TiebaMutationException("回复 ID 无效，无法完成操作。")
    data object InvalidUserId : TiebaMutationException("用户 ID 无效，无法完成操作。")
    data object InvalidForumId : TiebaMutationException("贴吧 ID 无效，无法完成操作。")
    data object InvalidForumName : TiebaMutationException("贴吧名称无效，无法完成操作。")
    data object MissingPortrait : TiebaMutationException("用户资料缺少 portrait，无法完成关注操作。")
    data object MissingNickname : TiebaMutationException("昵称不能为空。")
}

sealed class ContentSubmissionException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    data object NotLoggedIn : ContentSubmissionException("请先登录后再操作。")
    data object SessionExpired : ContentSubmissionException("登录状态已失效，请重新登录。")
    data class VerificationRequired(val challenge: SubmissionVerificationChallenge) :
        ContentSubmissionException(challenge.message)
    data class Business(val code: Int?, val serverMessage: String) : ContentSubmissionException(serverMessage)
    data class OutcomeUnknown(val original: Throwable) :
        ContentSubmissionException(OUTCOME_UNKNOWN_MESSAGE, original), MutationOutcomeUnknown {
        override val outcomeUnknownMessage: String = OUTCOME_UNKNOWN_MESSAGE
    }
    data class Unsupported(val reason: String) : ContentSubmissionException(reason)
}

sealed class TiebaWriteException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    data class OutcomeUnknown(val original: Throwable) : TiebaWriteException(
        OUTCOME_UNKNOWN_MESSAGE,
        original,
    ), MutationOutcomeUnknown {
        override val outcomeUnknownMessage: String = OUTCOME_UNKNOWN_MESSAGE
    }
}

private const val OUTCOME_UNKNOWN_MESSAGE =
    "请求可能已经送达，但未能确认操作结果，请先刷新页面再决定是否重试。"

object TiebaResponseValidator {
    private val sessionCodes = setOf(110001, 110002, 110003, 110004)

    fun validate(code: Int, message: String) {
        if (code == 0) return
        if (isSessionExpired(code, message)) throw TiebaApiException.SessionExpired(code, message)
        throw TiebaApiException.Business(code, message)
    }

    fun isSessionExpired(code: Int?, message: String): Boolean {
        val normalized = message.lowercase()
        return (code != null && code in sessionCodes) || (code == 4 && listOf(
            "登录", "登陆", "session", "bduss", "stoken", "失效", "过期",
        ).any(normalized::contains))
    }
}

enum class TiebaOperation { Read, Write }

/**
 * A write transport failure after dispatch is never reported as a safe retry.
 * The server may have committed the mutation before the connection failed.
 */
object TiebaFailureClassifier {
    fun classify(error: Throwable, operation: TiebaOperation, requestDispatched: Boolean): Throwable {
        if (operation == TiebaOperation.Write && requestDispatched &&
            error !is TiebaMutationException && error !is TiebaApiException.SessionExpired &&
            error !is ContentSubmissionException.VerificationRequired
        ) {
            return TiebaWriteException.OutcomeUnknown(error)
        }
        return error
    }
}
