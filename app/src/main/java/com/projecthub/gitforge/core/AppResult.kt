package com.projecthub.gitforge.core

/**
 * Classifies a failure so the UI can decide how to present it. Keeping the kind
 * separate from the message lets screens show targeted recovery actions
 * (for example "re-authorise" for [AUTH] or "retry later" for [RATE_LIMIT]).
 */
enum class ErrorKind {
    NETWORK,
    AUTH,
    RATE_LIMIT,
    NOT_FOUND,
    CONFLICT,
    IO,
    GIT,
    VALIDATION,
    UNSUPPORTED,
    UNKNOWN,
}

data class AppError(
    val kind: ErrorKind,
    val message: String,
    val cause: Throwable? = null,
    /** True when retrying the same operation may plausibly succeed. */
    val retryable: Boolean = kind == ErrorKind.NETWORK || kind == ErrorKind.RATE_LIMIT,
)

sealed interface AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>
}

inline fun <T> AppResult<T>.onSuccess(block: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) block(data)
    return this
}

inline fun <T> AppResult<T>.onFailure(block: (AppError) -> Unit): AppResult<T> {
    if (this is AppResult.Failure) block(error)
    return this
}

fun <T> AppResult<T>.getOrNull(): T? = (this as? AppResult.Success)?.data

fun <T> AppResult<T>.errorOrNull(): AppError? = (this as? AppResult.Failure)?.error

/**
 * Wraps a suspending block, converting thrown exceptions into [AppResult.Failure].
 * The throwable's message is passed through [Redaction] so a credential that
 * leaked into an exception string can never reach the UI or the log.
 */
suspend fun <T> appResult(block: suspend () -> T): AppResult<T> = try {
    AppResult.Success(block())
} catch (t: Throwable) {
    if (t is kotlinx.coroutines.CancellationException) throw t
    AppResult.Failure(t.toAppError())
}

fun Throwable.toAppError(): AppError {
    val safeMessage = Redaction.redact(message ?: this::class.java.simpleName)
    // Preserve the classified kind so callers keep targeted recovery actions.
    if (this is com.projecthub.gitforge.git.GitOperationException) {
        return AppError(kind = kind, message = safeMessage, cause = this)
    }
    if (this is com.projecthub.gitforge.github.GitHubException) {
        return AppError(kind = kind, message = safeMessage, cause = this)
    }
    return AppError(kind = ErrorKind.UNKNOWN, message = safeMessage, cause = this)
}
