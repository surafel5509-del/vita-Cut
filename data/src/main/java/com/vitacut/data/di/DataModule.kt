package com.vitacut.data.di

import com.vitacut.data.repository.ExportRepositoryImpl
import com.vitacut.data.repository.ProjectRepositoryImpl
import com.vitacut.data.repository.TemplateRepositoryImpl
import com.vitacut.domain.repository.ExportRepository
import com.vitacut.domain.repository.ProjectRepository
import com.vitacut.domain.repository.TemplateRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class DataModule {

    @Binds
    @Singleton
    abstract fun bindProjectRepository(impl: ProjectRepositoryImpl): ProjectRepository

    @Binds
    @Singleton
    abstract fun bindTemplateRepository(impl: TemplateRepositoryImpl): TemplateRepository

    @Binds
    @Singleton
    abstract fun bindExportRepository(impl: ExportRepositoryImpl): ExportRepository
}
