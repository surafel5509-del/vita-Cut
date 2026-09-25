package com.vitacut.core.common.dispatcher

import javax.inject.Qualifier
import kotlin.coroutines.CoroutineContext

/** Qualifiers for the coroutine dispatchers used across the app. */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class Dispatcher(val vitaDispatcher: VitaDispatchers)

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope

enum class VitaDispatchers {
    /** CPU-heavy work: waveform analysis, beat detection, tracking. */
    Default,

    /** Disk / DB / network IO. */
    Io,

    /** Long-running media decoding & encoding operations that must not starve the IO pool. */
    Media,
}

/**
 * Abstraction over [CoroutineContext] providers so dispatchers can be faked in tests.
 */
interface DispatcherProvider {
    val default: CoroutineContext
    val io: CoroutineContext
    val media: CoroutineContext
    val main: CoroutineContext
}
