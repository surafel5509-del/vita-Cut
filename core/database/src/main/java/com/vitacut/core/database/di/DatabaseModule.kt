package com.vitacut.core.database.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.vitacut.core.database.VitaDatabase
import com.vitacut.core.database.dao.ExportRecordDao
import com.vitacut.core.database.dao.ProjectDao
import com.vitacut.core.database.dao.TemplateDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): VitaDatabase =
        Room.databaseBuilder(context, VitaDatabase::class.java, VitaDatabase.NAME)
            // WAL gives us crash-resilient writes: an interrupted transaction never leaves a
            // half-written row, which the project recovery flow depends on.
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    // Fresh install: nothing to migrate yet. Kept as an explicit hook for
                    // future seeding logic.
                }
            })
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()

    @Provides
    fun provideProjectDao(db: VitaDatabase): ProjectDao = db.projectDao()

    @Provides
    fun provideExportRecordDao(db: VitaDatabase): ExportRecordDao = db.exportRecordDao()

    @Provides
    fun provideTemplateDao(db: VitaDatabase): TemplateDao = db.templateDao()
}
