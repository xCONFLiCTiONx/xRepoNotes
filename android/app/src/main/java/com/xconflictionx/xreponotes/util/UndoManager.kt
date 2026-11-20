package com.xconflictionx.xreponotes.util

import androidx.compose.ui.text.input.TextFieldValue

class UndoManager(
    private val maxHistory: Int = 150
) {
    private val undoStack = ArrayDeque<TextFieldValue>()
    private val redoStack = ArrayDeque<TextFieldValue>()
    private var isExecutingUndoRedo = false

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun onFieldValueChanged(newValue: TextFieldValue, oldValue: TextFieldValue) {
        if (isExecutingUndoRedo) return
        if (newValue.text == oldValue.text) return

        if (undoStack.isEmpty() || undoStack.last().text != oldValue.text) {
            undoStack.addLast(oldValue)
            if (undoStack.size > maxHistory) {
                undoStack.removeFirst()
            }
        }
        redoStack.clear()
    }

    fun undo(currentValue: TextFieldValue): TextFieldValue? {
        if (!canUndo) return null
        isExecutingUndoRedo = true
        redoStack.addLast(currentValue)
        val previous = undoStack.removeLast()
        isExecutingUndoRedo = false
        return previous
    }

    fun redo(currentValue: TextFieldValue): TextFieldValue? {
        if (!canRedo) return null
        isExecutingUndoRedo = true
        undoStack.addLast(currentValue)
        val next = redoStack.removeLast()
        isExecutingUndoRedo = false
        return next
    }

    fun reset() {
        undoStack.clear()
        redoStack.clear()
    }
}
