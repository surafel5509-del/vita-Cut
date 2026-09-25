package com.vitacut.core.timeline

import com.vitacut.core.model.Caption
import com.vitacut.core.model.CaptionSet
import com.vitacut.core.model.Interpolation
import com.vitacut.core.model.ItemId
import com.vitacut.core.model.Keyframe
import com.vitacut.core.model.KeyframeProperty
import com.vitacut.core.model.KeyframeSet
import com.vitacut.core.model.KeyframeTrack
import com.vitacut.core.model.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The editing session document: current [Project] state + undo/redo history + dirty tracking.
 *
 * Every mutation goes through [update], which:
 * 1. applies the (pure) transformation,
 * 2. pushes a labeled snapshot onto the history,
 * 3. bumps [updatedAtMs] and the dirty flag so autosave knows when to persist.
 *
 * Thread-safety: all mutations are expected on a single (ViewModel) thread; reads via the
 * [projectFlow] are safe anywhere.
 */
class ProjectDocument(initial: Project) {

    private val history = HistoryStack(initial)

    private val _projectFlow = MutableStateFlow(initial)
    val projectFlow: StateFlow<Project> = _projectFlow.asStateFlow()

    val project: Project get() = _projectFlow.value

    private val _dirty = MutableStateFlow(false)
    /** True when the document has unsaved changes. */
    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()

    private val _historyState = MutableStateFlow(HistoryUiState(canUndo = false, canRedo = false))
    val historyState: StateFlow<HistoryUiState> = _historyState.asStateFlow()

    data class HistoryUiState(
        val canUndo: Boolean,
        val canRedo: Boolean,
        val undoLabel: String? = null,
        val redoLabel: String? = null,
    )

    /** Applies [transform] as one undoable operation labeled [label]. */
    fun update(label: String, transform: (Project) -> Project) {
        val before = _projectFlow.value
        val after = transform(before)
        if (after === before || after == before) return
        val stamped = after.copy(updatedAtMs = System.currentTimeMillis())
        history.push(label, stamped)
        _projectFlow.value = stamped
        _dirty.value = true
        publishHistory()
    }

    fun undo(): Boolean {
        val restored = history.undo() ?: return false
        _projectFlow.value = restored
        _dirty.value = true
        publishHistory()
        return true
    }

    fun redo(): Boolean {
        val restored = history.redo() ?: return false
        _projectFlow.value = restored
        _dirty.value = true
        publishHistory()
        return true
    }

    /** Called by autosave after a successful persist. */
    fun markSaved() {
        _dirty.value = false
    }

    /** Replaces the whole document without polluting history (project load / recovery). */
    fun reset(project: Project) {
        history.reset(project)
        _projectFlow.value = project
        _dirty.value = false
        publishHistory()
    }

    private fun publishHistory() {
        _historyState.value = HistoryUiState(
            canUndo = history.canUndo,
            canRedo = history.canRedo,
            undoLabel = history.undoLabel,
            redoLabel = history.redoLabel,
        )
    }

    // region Keyframe operations (shared by all item types)

    /**
     * Adds (or replaces) a keyframe for [property] on the item, at [timeUs] *relative to the
     * item's timeline start*.
     */
    fun addKeyframe(
        itemId: ItemId,
        property: KeyframeProperty,
        timeUs: Long,
        value: Float,
        interpolation: Interpolation = Interpolation.LINEAR,
        label: String = "keyframe.add",
    ) {
        update(label) { project ->
            mapKeyframeSet(project, itemId) { set ->
                val track = set.trackFor(property) ?: KeyframeTrack(property)
                val keys = KeyframeMathUpsert(track.keyframes, Keyframe(timeUs.coerceAtLeast(0L), value, interpolation))
                set.withTrack(track.copy(keyframes = keys))
            }
        }
    }

    fun removeKeyframe(itemId: ItemId, property: KeyframeProperty, timeUs: Long, label: String = "keyframe.remove") {
        update(label) { project ->
            mapKeyframeSet(project, itemId) { set ->
                val track = set.trackFor(property) ?: return@mapKeyframeSet set
                val keys = com.vitacut.core.model.KeyframeMath.remove(track.keyframes, timeUs)
                if (keys.isEmpty()) set.withoutTrack(property) else set.withTrack(track.copy(keyframes = keys))
            }
        }
    }

    fun moveKeyframe(
        itemId: ItemId,
        property: KeyframeProperty,
        originalTimeUs: Long,
        newTimeUs: Long,
        label: String = "keyframe.move",
    ) {
        update(label) { project ->
            mapKeyframeSet(project, itemId) { set ->
                val track = set.trackFor(property) ?: return@mapKeyframeSet set
                set.withTrack(
                    track.copy(
                        keyframes = com.vitacut.core.model.KeyframeMath.move(
                            track.keyframes,
                            originalTimeUs,
                            newTimeUs,
                        ),
                    ),
                )
            }
        }
    }

    fun setKeyframeInterpolation(
        itemId: ItemId,
        property: KeyframeProperty,
        timeUs: Long,
        interpolation: Interpolation,
        label: String = "keyframe.interpolation",
    ) {
        update(label) { project ->
            mapKeyframeSet(project, itemId) { set ->
                val track = set.trackFor(property) ?: return@mapKeyframeSet set
                set.withTrack(
                    track.copy(
                        keyframes = track.keyframes.map {
                            if (it.timeUs == timeUs) it.copy(interpolation = interpolation) else it
                        },
                    ),
                )
            }
        }
    }

    private fun KeyframeMathUpsert(keys: List<Keyframe>, key: Keyframe): List<Keyframe> =
        com.vitacut.core.model.KeyframeMath.upsert(keys, key)

    private fun mapKeyframeSet(
        project: Project,
        itemId: ItemId,
        block: (KeyframeSet) -> KeyframeSet,
    ): Project = TimelineEngine.mapItem(project, itemId) { item ->
        when (item) {
            is com.vitacut.core.model.VideoClipItem -> item.copy(keyframes = block(item.keyframes))
            is com.vitacut.core.model.AudioClipItem -> item.copy(keyframes = block(item.keyframes))
            is com.vitacut.core.model.TextItem -> item.copy(keyframes = block(item.keyframes))
            is com.vitacut.core.model.StickerItem -> item.copy(keyframes = block(item.keyframes))
        }
    }

    // endregion

    // region Caption operations

    fun setCaptionSet(captionSet: CaptionSet, label: String = "captions.set") {
        update(label) { it.copy(captions = captionSet) }
    }

    fun updateCaption(caption: Caption, label: String = "captions.update") {
        update(label) { project ->
            project.copy(
                captions = project.captions.copy(
                    captions = project.captions.captions.map {
                        if (it.id == caption.id) caption else it
                    },
                ),
            )
        }
    }

    fun deleteCaption(id: com.vitacut.core.model.CaptionId, label: String = "captions.delete") {
        update(label) { project ->
            project.copy(
                captions = project.captions.copy(
                    captions = project.captions.captions.filterNot { it.id == id },
                ),
            )
        }
    }

    // endregion
}
