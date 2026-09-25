package com.vitacut.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.vitacut.core.database.dao.ExportRecordDao
import com.vitacut.core.database.dao.ProjectDao
import com.vitacut.core.database.dao.TemplateDao
import com.vitacut.core.database.entity.ExportRecordEntity
import com.vitacut.core.database.entity.ProjectEntity
import com.vitacut.core.database.entity.TemplateEntity

/**
 * Vita Cut's local database.
 *
 * Stores metadata + JSON editing documents only — never media files themselves (those stay
 * referenced by URI, per the scoped-storage requirements).
 */
@Database(
    entities = [
        ProjectEntity::class,
        ExportRecordEntity::class,
        TemplateEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class VitaDatabase : RoomDatabase() {

    abstract fun projectDao(): ProjectDao
    abstract fun exportRecordDao(): ExportRecordDao
    abstract fun templateDao(): TemplateDao

    companion object {
        const val NAME = "vitacut.db"
    }
}
