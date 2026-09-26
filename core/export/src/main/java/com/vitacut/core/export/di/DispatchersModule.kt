package com.vitacut.core.export.di

import com.vitacut.core.common.dispatcher.Dispatcher
import com.vitacut.core.common.dispatcher.VitaDispatchers
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object DispatchersModule {

    @Provides
    @Singleton
    @Dispatcher(VitaDispatchers.Media)
    fun provideMediaDispatcher(): CoroutineDispatcher =
        Executors.newFixedThreadPool(
            maxOf(2, Runtime.getRuntime().availableProcessors() / 2),
        ) { runnable ->
            Thread(runnable, "vita-media").apply { priority = Thread.NORM_PRIORITY - 1 }
        }.asCoroutineDispatcher()
}
