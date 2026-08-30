package de.lananahwp.openmmo.mapeditor.model

/** Which of a DS grid square's values one edit wrote. */
enum class NdsCellKind { TILE, HEIGHT, COLLISION, PERMISSION }

/** One write to a DS grid square, carrying the value it replaced so it can be put back. */
data class NdsCellEdit(
    val kind: NdsCellKind,
    val layer: Int,
    val x: Int,
    val z: Int,
    val before: Number,
    val after: Number,
)

/** A paint stroke: every square one press-drag-release wrote, in the order it wrote them. */
class NdsGridStep(val label: String) {
  val edits = mutableListOf<NdsCellEdit>()
}

/**
 * Undo/redo history for grid painting on one DS map.
 *
 * Painting is recorded square by square because a stroke can cover hundreds of cells and holding
 * the whole grid twice per click would be wasteful. The caller owns repainting and dirty state.
 */
class NdsEditHistory(private val limit: Int = DEFAULT_LIMIT) {
  private val undoSteps = ArrayDeque<NdsGridStep>()
  private val redoSteps = ArrayDeque<NdsGridStep>()
  private var stroke: NdsGridStep? = null

  val undoDepth: Int get() = undoSteps.size
  val redoDepth: Int get() = redoSteps.size

  fun clear() {
    undoSteps.clear()
    redoSteps.clear()
    stroke = null
  }

  /** Starts a paint gesture so its press-drag-release sequence becomes one history step. */
  fun beginStroke(label: String) {
    stroke = NdsGridStep(label)
  }

  /** Records one grid write. A stroke that changes nothing never reaches the undo stack. */
  fun recordCell(edit: NdsCellEdit) {
    if (edit.before.toDouble() == edit.after.toDouble()) return
    val open = stroke ?: NdsGridStep("tile").also { stroke = it }
    if (open.edits.isEmpty()) push(open)
    open.edits += edit
  }

  /** Reverses the last stroke on [map] and returns it, or null when there is nothing to undo. */
  fun undo(map: NdsMap): NdsGridStep? {
    val step = undoSteps.removeLastOrNull() ?: return null
    stroke = null
    for (edit in step.edits.asReversed()) apply(map, edit, edit.before)
    redoSteps.addLast(step)
    return step
  }

  /** Reapplies the last undone stroke on [map] and returns it, or null when there is none. */
  fun redo(map: NdsMap): NdsGridStep? {
    val step = redoSteps.removeLastOrNull() ?: return null
    stroke = null
    for (edit in step.edits) apply(map, edit, edit.after)
    undoSteps.addLast(step)
    return step
  }

  private fun push(step: NdsGridStep) {
    undoSteps.addLast(step)
    while (undoSteps.size > limit) undoSteps.removeFirst()
    // A real new edit invalidates whatever was undone past it.
    redoSteps.clear()
  }

  private fun apply(map: NdsMap, edit: NdsCellEdit, value: Number) {
    when (edit.kind) {
      NdsCellKind.TILE -> map.grid.setTile(edit.layer, edit.x, edit.z, value.toInt())
      NdsCellKind.HEIGHT -> map.grid.setHeight(edit.layer, edit.x, edit.z, value)
      NdsCellKind.COLLISION -> map.grid.setCollision(edit.x, edit.z, value.toInt())
      NdsCellKind.PERMISSION -> map.grid.setPermission(edit.x, edit.z, value.toInt())
    }
  }

  companion object {
    const val DEFAULT_LIMIT = 150
  }
}
