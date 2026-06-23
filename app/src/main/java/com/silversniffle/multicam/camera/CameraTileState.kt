package com.silversniffle.multicam.camera

/** UI state for a single camera tile. Held in the adapter's list, never in the recycled view. */
sealed class CameraTileState {
    /** Not yet attempted (e.g. waiting for the surface or for permission). */
    data object Idle : CameraTileState()

    /** openCamera has been requested; preview not yet live. */
    data object Opening : CameraTileState()

    /** Repeating preview request is running. */
    data object Streaming : CameraTileState()

    /** Open/configure failed; [reason] is a human-readable explanation. */
    data class Error(val reason: String) : CameraTileState()
}
