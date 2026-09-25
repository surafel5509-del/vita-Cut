package com.vitacut.core.timeline

/**
 * Snapshot-based undo/redo history.
 *
 * Because [com.vitacut.core.model.Project] is an immutable tree of data classes, a "snapshot" is
 * just a reference — structural sharing makes this approach memory-cheap (only the mutated
 * branches are new objects) while giving exact, operation-labeled undo for *every* edit.
 *
 * The stack has a practical bound ([maxEntries]) to protect low-memory devices; the oldest
 * entries are dropped first.
 */
class HistoryStack<T>(
    initial: T,
    private val maxEntries: Int = 200,
) {
    private data class Entry<T>(val state: T, val label: String)

    private val undoEntries = ArrayDeque<Entry<T>>()
    private val redoEntries = ArrayDeque<Entry<T>>()
    private var current: Entry<T> = Entry(initial, "open")

    val canUndo: Boolean get() = undoEntries.isNotEmpty()
    val canRedo: Boolean get() = redoEntries.isNotEmpty()

    /** Label of the operation that would be undone (for the undo button tooltip / a11y). */
    val undoLabel: String? get() = undoEntries.lastOrNull()?.label
    val redoLabel: String? get() = redoEntries.lastOrNull()?.label

    val currentState: T get() = current.state

    val size: Int get() = undoEntries.size

    /**
     * Commits a new state produced by the operation [label]. Clears the redo branch (standard
     * editor semantics). No-op if [state] is referentially identical to the current state.
     */
    fun push(label: String, state: T) {
        if (state === current.state || state == current.state) return
        undoEntries.addLast(current)
        if (undoEntries.size > maxEntries) undoEntries.removeFirst()
        current = Entry(state, label)
        redoEntries.clear()
    }

    /** Steps back one operation; returns the restored state or null when nothing to undo. */
    fun undo(): T? {
        val entry = undoEntries.removeLastOrNull() ?: return null
        redoEntries.addLast(current)
        current = entry
        return current.state
    }

    /** Steps forward one operation; returns the restored state or null when nothing to redo. */
    fun redo(): T? {
        val entry = redoEntries.removeLastOrNull() ?: return null
        undoEntries.addLast(current)
        current = entry
        return current.state
    }

    /**
     * Replaces the current state *without* creating an undo entry — used when loading a project
     * or recovering an autosave (the load itself must not be undoable).
     */
    fun reset(state: T, label: String = "reset") {
        undoEntries.clear()
        redoEntries.clear()
        current = Entry(state, label)
    }
}
