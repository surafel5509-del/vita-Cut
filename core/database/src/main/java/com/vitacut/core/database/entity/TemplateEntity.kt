package com.vitacut.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached metadata for a bundled template (the full template document lives in app assets JSON).
 * Stores only what the gallery needs plus user-specific state like favorites & usage count.
 */
@Entity(tableName = "templates")
data class TemplateEntity(
    @PrimaryKey
    @ColumnInfo(name = "template_id")
    val templateId: String,

    val name: String,
    val category: String,

    @ColumnInfo(name = "thumbnail_key")
    val thumbnailKey: String?,

    @ColumnInfo(name = "duration_us")
    val durationUs: Long,

    @ColumnInfo(name = "canvas_aspect")
    val canvasAspect: Float,

    @ColumnInfo(name = "is_favorite")
    val isFavorite: Boolean = false,

    @ColumnInfo(name = "use_count")
    val useCount: Int = 0,

    @ColumnInfo(name = "last_used_ms")
    val lastUsedMs: Long = 0L,
)
