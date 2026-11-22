package dev.filamentiso.editor

import dev.filamentiso.model.Level

/** Snapshot undo/redo. Levels are immutable, so keeping old ones is cheap and always correct. */
class History(initial: Level, private val limit: Int = 100) {
    private val undoStack = ArrayDeque<Level>()
    private val redoStack = ArrayDeque<Level>()

    var current: Level = initial
        private set

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    /** Applies [edit]. Returns true if it changed anything (no-op edits don't create undo steps). */
    fun apply(edit: (Level) -> Level): Boolean {
        val next = edit(current)
        if (next == current) return false
        undoStack.addLast(current)
        if (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
        current = next
        return true
    }

    fun undo(): Boolean {
        val prev = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(current); current = prev
        return true
    }

    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(current); current = next
        return true
    }
}
