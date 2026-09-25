package com.vitacut.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The single JSON configuration used for project persistence, autosave snapshots, undo history
 * spill (if ever needed) and template files.
 *
 * - `ignoreUnknownKeys` → projects saved by newer app versions still open (forward tolerance).
 * - `encodeDefaults` → explicit schema in the file, which makes hand-inspection and debugging of
 *   project files practical.
 * - `coerceInputValues` → an invalid enum value or null for a non-null field falls back to the
 *   default instead of throwing (crash resilience requirement).
 */
val VitaJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
    classDiscriminator = "type"
    isLenient = false
    prettyPrint = false
}

/** Serializes a project document to its JSON representation. */
fun Project.toJson(): String = VitaJson.encodeToString(Project.serializer(), this)

/**
 * Deserializes a project document. Returns null when the payload is unreadable — callers surface
 * a localized "project could not be opened" error instead of crashing.
 */
fun projectFromJson(json: String): Project? = runCatching {
    VitaJson.decodeFromString(Project.serializer(), json)
}.getOrNull()

fun Template.toJson(): String = VitaJson.encodeToString(Template.serializer(), this)

fun templateFromJson(json: String): Template? = runCatching {
    VitaJson.decodeFromString(Template.serializer(), json)
}.getOrNull()
