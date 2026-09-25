package com.vitacut.core.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Stable identifiers for every timeline element.
 *
 * IDs are generated once when an element is created and never change afterwards, even when the
 * element is moved between tracks. This is what makes undo/redo snapshots, keyframe tracks and
 * motion-tracking bindings safe to reference across edits.
 */
fun newId(): String = UUID.randomUUID().toString()

@Serializable
@JvmInline
value class ProjectId(val value: String = newId())

@Serializable
@JvmInline
value class AssetId(val value: String = newId())

@Serializable
@JvmInline
value class TrackId(val value: String = newId())

@Serializable
@JvmInline
value class ItemId(val value: String = newId())

@Serializable
@JvmInline
value class CaptionId(val value: String = newId())

@Serializable
@JvmInline
value class TemplateId(val value: String)
