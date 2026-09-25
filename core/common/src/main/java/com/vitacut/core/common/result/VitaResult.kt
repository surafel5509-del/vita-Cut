package com.vitacut.core.common.result

/**
 * A typed outcome for operations that can fail for *expected* reasons (missing file, unsupported
 * codec, permission denied…). Unexpected failures should still crash loudly in debug builds but
 * are mapped into [VitaResult.Failure] at repository boundaries so the UI never shows a raw stack
 * trace to the user.
 */
sealed interface VitaResult<out T> {

    data class Success<T>(val data: T) : VitaResult<T>

    data class Failure(val error: VitaError) : VitaResult<Nothing>

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    fun getOrNull(): T? = (this as? Success)?.data
    fun errorOrNull(): VitaError? = (this as? Failure)?.error

    fun <R> map(transform: (T) -> R): VitaResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Failure -> this
    }

    suspend fun <R> suspendMap(transform: suspend (T) -> R): VitaResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Failure -> this
    }
}

/**
 * Every user-visible failure mode in the app maps to one of these categories; feature layers turn
 * them into localized messages via [messageKey].
 */
sealed class VitaError(val messageKey: String) {

    /** A media file referenced by a project no longer exists or is unreadable. */
    data class FileMissing(val uri: String) : VitaError("error_file_missing")

    /** The container/codec combination cannot be decoded or encoded on this device. */
    data class UnsupportedMedia(val detail: String) : VitaError("error_unsupported_media")

    /** The media file is corrupt or truncated. */
    data class CorruptMedia(val detail: String) : VitaError("error_corrupt_media")

    /** A runtime permission needed for the operation was denied. */
    data class PermissionDenied(val permission: String) : VitaError("error_permission_denied")

    /** Not enough free storage to complete (export, proxy generation, cache…). */
    data class InsufficientStorage(val requiredBytes: Long, val freeBytes: Long) :
        VitaError("error_insufficient_storage")

    /** Export/render failed mid-way. */
    data class ExportFailed(val detail: String, val errorCode: Int = -1) :
        VitaError("error_export_failed")

    /** An operation was cancelled by the user. */
    data object Cancelled : VitaError("error_cancelled")

    /** The device ran out of memory while processing media. */
    data object OutOfMemory : VitaError("error_out_of_memory")

    /** Speech-to-text or another on-device AI capability is unavailable. */
    data class CapabilityUnavailable(val capability: String) :
        VitaError("error_capability_unavailable")

    /** Project file could not be parsed (older/newer format, manual tampering…). */
    data class ProjectUnreadable(val detail: String) : VitaError("error_project_unreadable")

    /** Generic unexpected failure. */
    data class Unknown(val throwable: Throwable?) : VitaError("error_unknown")
}

/** Wraps [block], mapping thrown exceptions into [VitaResult.Failure]. */
inline fun <T> runCatchingVita(block: () -> T): VitaResult<T> = try {
    VitaResult.Success(block())
} catch (ce: kotlin.coroutines.cancellation.CancellationException) {
    throw ce
} catch (oom: OutOfMemoryError) {
    VitaResult.Failure(VitaError.OutOfMemory)
} catch (t: Throwable) {
    VitaResult.Failure(VitaError.Unknown(t))
}

/** Suspending variant of [runCatchingVita]. */
suspend inline fun <T> suspendRunCatchingVita(crossinline block: suspend () -> T): VitaResult<T> =
    try {
        VitaResult.Success(block())
    } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
        throw ce
    } catch (oom: OutOfMemoryError) {
        VitaResult.Failure(VitaError.OutOfMemory)
    } catch (t: Throwable) {
        VitaResult.Failure(VitaError.Unknown(t))
    }
