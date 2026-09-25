package com.vitacut.core.common.dispatcher

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.CoroutineContext

/**
 * Production [DispatcherProvider].
 *
 * Media decoding/encoding runs on a dedicated bounded pool so that heavy exports never starve
 * regular IO work (DB writes, autosave, thumbnail loading).
 */
@Singleton
class DefaultDispatcherProvider @Inject constructor() : DispatcherProvider {

    private val mediaDispatcher: CoroutineDispatcher =
        Executors.newFixedThreadPool(
            maxOf(2, Runtime.getRuntime().availableProcessors() / 2),
        ) { runnable ->
            Thread(runnable, "vita-media").apply { priority = Thread.NORM_PRIORITY - 1 }
        }.asCoroutineDispatcher()

    override val default: CoroutineContext = Dispatchers.Default
    override val io: CoroutineContext = Dispatchers.IO
    override val media: CoroutineContext = mediaDispatcher
    override val main: CoroutineContext = Dispatchers.Main.immediate
}
