package com.vitacut.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vitacut.core.database.entity.TemplateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TemplateDao {

    @Query("SELECT * FROM templates ORDER BY use_count DESC, template_id ASC")
    fun observeAll(): Flow<List<TemplateEntity>>

    @Query("SELECT * FROM templates WHERE category = :category ORDER BY template_id ASC")
    fun observeByCategory(category: String): Flow<List<TemplateEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(templates: List<TemplateEntity>)

    @Query("UPDATE templates SET is_favorite = :favorite WHERE template_id = :templateId")
    suspend fun setFavorite(templateId: String, favorite: Boolean)

    @Query(
        "UPDATE templates SET use_count = use_count + 1, last_used_ms = :nowMs " +
            "WHERE template_id = :templateId",
    )
    suspend fun recordUse(templateId: String, nowMs: Long)
}
