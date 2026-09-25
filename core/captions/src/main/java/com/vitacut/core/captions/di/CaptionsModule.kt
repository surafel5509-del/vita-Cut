package com.vitacut.core.captions.di

import com.vitacut.core.captions.AssistedCaptionProvider
import com.vitacut.core.captions.TranscriptionProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Transcription engines are a Hilt multibinding set: any module can contribute a provider with
 * `@Binds @IntoSet` and [com.vitacut.core.captions.TranscriptionRegistry] picks it up by
 * priority without call-site changes.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class CaptionsModule {

    @Binds
    @IntoSet
    abstract fun bindAssistedProvider(impl: AssistedCaptionProvider): TranscriptionProvider
}
