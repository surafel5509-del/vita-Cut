package com.vitacut.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vitacut.core.database.entity.ExportRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExportRecordDao {

    @Query("SELECT * FROM exports ORDER BY exported_at_ms DESC")
    fun observeAll(): Flow<List<ExportRecordEntity>>

    @Query("SELECT * FROM exports WHERE project_id = :projectId ORDER BY exported_at_ms DESC")
    fun observeForProject(projectId: String): Flow<List<ExportRecordEntity>>

    @Query("SELECT * FROM exports WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): ExportRecordEntity?

    /** Rows whose output file may still be a partial temp file — cleaned up on next launch. */
    @Query("SELECT * FROM exports WHERE status IN ('pending', 'running')")
    suspend fun findIncomplete(): List<ExportRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: ExportRecordEntity): Long

    @Query("UPDATE exports SET status = :status, error_message = :error WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, error: String? = null)

    @Query(
        "UPDATE exports SET status = :status, file_path = :filePath, published_uri = :publishedUri, " +
            "file_size_bytes = :sizeBytes WHERE id = :id",
    )
    suspend fun markSucceeded(id: Long, status: String, filePath: String?, publishedUri: String?, sizeBytes: Long)

    @Query("DELETE FROM exports WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM exports WHERE status IN ('failed', 'cancelled')")
    suspend fun deleteFailed()
}
