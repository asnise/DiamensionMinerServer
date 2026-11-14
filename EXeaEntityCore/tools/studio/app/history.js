// Undo / redo by snapshots: the editor hands over its whole editable state as JSON after each change.
// Changes less than 600 ms apart (typing in one field, dragging a marker) become one step.
export class History {
    constructor(limit = 200) {
        this.limit = limit;
        this.reset(null);
    }

    reset(snapshot) {
        this.undoStack = [];
        this.redoStack = [];
        this.current = snapshot;
        this.lastAt = 0;
    }

    /** Call after every change with the new state. */
    record(snapshot) {
        if (snapshot === this.current) return;
        const now = performance.now();
        if (now - this.lastAt > 600 || !this.undoStack.length) {
            this.undoStack.push(this.current);
            if (this.undoStack.length > this.limit) this.undoStack.shift();
        }
        this.lastAt = now;
        this.current = snapshot;
        this.redoStack = [];
    }

    get canUndo() { return this.undoStack.length > 0 && this.undoStack[this.undoStack.length - 1] !== null; }
    get canRedo() { return this.redoStack.length > 0; }

    /** Returns the snapshot to restore, or null. */
    undo() {
        if (!this.canUndo) return null;
        this.redoStack.push(this.current);
        this.current = this.undoStack.pop();
        this.lastAt = 0;
        return this.current;
    }

    redo() {
        if (!this.canRedo) return null;
        this.undoStack.push(this.current);
        this.current = this.redoStack.pop();
        this.lastAt = 0;
        return this.current;
    }
}
