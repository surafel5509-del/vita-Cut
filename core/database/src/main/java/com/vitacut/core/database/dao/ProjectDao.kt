package com.vitacut.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.vitacut.core.database.entity.ProjectEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {

    @Query("SELECT * FROM projects ORDER BY updated_at_ms DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE status = :status ORDER BY updated_at_ms DESC")
    fun observeByStatus(status: String): Flow<List<ProjectEntity>>

    @Query(
        "SELECT * FROM projects WHERE name LIKE '%' || :query || '%' " +
            "ORDER BY updated_at_ms DESC",
    )
    fun search(query: String): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE project_id = :projectId LIMIT 1")
    fun findById(projectId: String): ProjectEntity?

    @Query("SELECT * FROM projects WHERE project_id = :projectId LIMIT 1")
    fun observeById(projectId: String): Flow<ProjectEntity?>

    /** Most recently touched project with a pending autosave — used by the recovery dialog. */
    @Query(
        "SELECT * FROM projects WHERE pending_json IS NOT NULL " +
            "ORDER BY updated_at_ms DESC LIMIT 1",
    )
    fun findRecoverable(): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(project: ProjectEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(projects: List<ProjectEntity>)

    @Update
    suspend fun update(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE project_id = :projectId")
    suspend fun deleteById(projectId: String)

    /**
     * Transactional autosave: write the pending snapshot first, then promote it. Splitting the
     * two steps lets an interrupted process leave a recoverable state instead of a half-written
     * document (crash-resilience requirement).
     */
    @Transaction
    suspend fun savePending(projectId: String, pendingJson: String, updatedAtMs: Long) {
        updatePending(projectId, pendingJson, updatedAtMs)
    }

    @Query(
        "UPDATE projects SET pending_json = :pendingJson, updated_at_ms = :updatedAtMs " +
            "WHERE project_id = :projectId",
    )
    suspend fun updatePending(projectId: String, pendingJson: String, updatedAtMs: Long)

    /** Promotes the pending snapshot to the committed document. */
    @Transaction
    suspend fun commitPending(projectId: String, projectJson: String, updatedAtMs: Long) {
        commitPendingInternal(projectId, projectJson, updatedAtMs)
    }

    @Query(
        "UPDATE projects SET project_json = :projectJson, pending_json = NULL, " +
            "updated_at_ms = :updatedAtMs WHERE project_id = :projectId",
    )
    suspend fun commitPendingInternal(projectId: String, projectJson: String, updatedAtMs: Long)

    @Query("UPDATE projects SET name = :name, updated_at_ms = :nowMs WHERE project_id = :projectId")
    suspend fun rename(projectId: String, name: String, nowMs: Long)

    @Query("UPDATE projects SET status = :status WHERE project_id = :projectId")
    suspend fun updateStatus(projectId: String, status: String)

    @Query("UPDATE projects SET thumbnail_path = :path WHERE project_id = :projectId")
    suspend fun updateThumbnail(projectId: String, path: String?)

    @Query("UPDATE projects SET export_count = export_count + 1 WHERE project_id = :projectId")
    suspend fun incrementExportCount(projectId: String)

    @Query("SELECT COUNT(*) FROM projects")
    suspend fun count(): Int
}
