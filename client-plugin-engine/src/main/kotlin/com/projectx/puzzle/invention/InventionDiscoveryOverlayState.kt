package com.projectx.puzzle.invention

/** An immutable snapshot handed from the logic thread to the render thread. */
data class InventionDiscoveryOverlayState(
    val slotHints: Array<SlotHint>,
    val lines: List<String>,
    val solved: Boolean,
) {
    override fun equals(other: Any?): Boolean =
        other is InventionDiscoveryOverlayState &&
            slotHints.contentEquals(other.slotHints) && lines == other.lines && solved == other.solved

    override fun hashCode(): Int = (slotHints.contentHashCode() * 31 + lines.hashCode()) * 31 + solved.hashCode()

    companion object {
        const val INTERFACE_ID = 1708
        const val FIRST_ICON_COMPONENT = 14
    }
}
