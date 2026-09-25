package com.vitacut.core.export.di

import com.vitacut.core.export.DefaultExportResourceProvider
import com.vitacut.core.export.ExportResourceProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ExportModule {

    @Binds
    @Singleton
    abstract fun bindExportResourceProvider(
        impl: DefaultExportResourceProvider,
    ): ExportResourceProvider
}
