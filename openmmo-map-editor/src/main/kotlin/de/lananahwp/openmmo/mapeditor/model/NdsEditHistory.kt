package de.lananahwp.openmmo.mapeditor.model

/** Which of a DS grid square's values one edit wrote. */
enum class NdsCellKind { TILE, HEIGHT, COLLISION, PERMISSION }

/** One write to a DS grid square, carrying the value it replaced so it can be put back. */
data class NdsCellEdit(
    val kind: NdsCellKind,
    val layer: Int,
    val x: Int,
    val z: Int,
    val before: Double,
    val after: Double,
) {
  /** Convenience for tile/collision/permission edits, whose values remain integral. */
  constructor(
      kind: NdsCellKind,
      layer: Int,
      x: Int,
      z: Int,
      before: Int,
      after: Int,
  ) : this(kind, layer, x, z, before.toDouble(), after.toDouble())
}

/**
 * One reversible change to a DS map.
 *
 * Painting is recorded square by square, because a stroke can cover hundreds of them and holding
 * the whole grid twice over per click would be wasteful. Prop and scenery work is recorded as a
 * before/after snapshot instead: those operations add, move and drop entries across short lists
 * at once, and the lists are short, so copying them is both simpler and safer than trying to
 * describe the edit.
 */
sealed class NdsUndoStep {
  /** Names the change for the status bar, e.g. "Undid 12 tile edit(s)". */
  abstract val label: String
}

/** A paint stroke: every square one press-drag-release wrote, in the order it wrote them. */
class NdsGridStep(override val label: String) : NdsUndoStep() {
  val edits = mutableListOf<NdsCellEdit>()
}

/** A prop or scenery change, as the two states it sat between. */
class NdsSceneStep(
    override val label: String,
    val before: NdsSceneSnapshot,
    /** Kept updatable so one drag accumulates into a single step. */
    var after: NdsSceneSnapshot,
) : NdsUndoStep()

/**
 * A copy of everything placed on a DS map: props, scenery, custom walk surfaces, and collision.
 *
 * Collision rides along because removing scenery can clear the collision underneath it, and
 * putting that scenery back has to put those values back too.
 */
class NdsSceneSnapshot(
    val props: List<NdsProp>,
    val removals: List<NdsTerrainRemoval>,
    val transforms: List<NdsTerrainTransform>,
    val walkSurfaces: List<NdsWalkSurface>,
    val collision: Array<IntArray>,
) {
  fun sameAs(other: NdsSceneSnapshot): Boolean =
      props == other.props &&
          removals == other.removals &&
          transforms == other.transforms &&
          walkSurfaces == other.walkSurfaces &&
          collision.size == other.collision.size &&
          collision.indices.all { collision[it].contentEquals(other.collision[it]) }

  companion object {
    /** Everything placed on [map], deep-copied so later edits cannot reach back into it. */
    fun of(map: NdsMap): NdsSceneSnapshot = NdsSceneSnapshot(
        props = map.props.map { it.copy() },
        removals = map.terrainRemovals.map {
          it.copy(clearedCollision = it.clearedCollision.toMutableList())
        },
        transforms = map.terrainTransforms.map { it.copy() },
        walkSurfaces = map.walkSurfaces.map { it.copy() },
        collision = Array(map.grid.cols) { x -> map.grid.collisions[x].copyOf() },
    )
  }
}

/**
 * Undo/redo history for one DS map.
 *
 * Holds no UI: it records what changed and puts it back on the map, and the caller redraws from
 * the step handed back. The grid and the prop lists are the whole of a DS map's editable state,
 * so a step is enough to reverse an edit exactly without keeping copies of the map itself.
 */
class NdsEditHistory(private val limit: Int = DEFAULT_LIMIT) {
  private val undoSteps = ArrayDeque<NdsUndoStep>()
  private val redoSteps = ArrayDeque<NdsUndoStep>()

  /** The stroke being painted. Reaches the stack on its first real edit, not on the press. */
  private var stroke: NdsGridStep? = null

  /** The step a drag is accumulating into, so a drag undoes in one go rather than per frame. */
  private var dragStep: NdsSceneStep? = null

  val undoDepth: Int get() = undoSteps.size
  val redoDepth: Int get() = redoSteps.size

  fun clear() {
    undoSteps.clear()
    redoSteps.clear()
    stroke = null
    dragStep = null
  }

  /**
   * Starts a paint stroke, so a press-drag-release undoes as one step rather than as one step per
   * square the drag happened to cross.
   */
  fun beginStroke(label: String) {
    stroke = NdsGridStep(label)
    dragStep = null
  }

  /**
   * Records one grid write, putting the open stroke on the stack when it first changes something.
   *
   * A stroke that changes nothing — repainting squares with what they already held — never
   * reaches the stack, so undo can never appear to do nothing.
   */
  fun recordCell(edit: NdsCellEdit) {
    if (edit.before == edit.after) return
    val open = stroke ?: NdsGridStep("tile").also { stroke = it }
    if (open.edits.isEmpty()) push(open)
    open.edits += edit
  }

  /** Records a discrete prop/scenery change. Identical states record nothing. */
  fun recordScene(label: String, before: NdsSceneSnapshot, after: NdsSceneSnapshot) {
    if (before.sameAs(after)) return
    push(NdsSceneStep(label, before, after))
  }

  /**
   * Folds a drag into one step: the first movement pushes it, later ones just move its end point.
   *
   * Without this, a prop dragged across the map would leave a step per mouse event, and undoing
   * the move would take as many presses as the drag had frames.
   */
  fun recordSceneDrag(label: String, before: NdsSceneSnapshot, after: NdsSceneSnapshot) {
    if (before.sameAs(after)) return
    val open = dragStep
    if (open == null) {
      val step = NdsSceneStep(label, before, after)
      dragStep = step
      push(step)
    } else {
      open.after = after
    }
  }

  /** Reverses the last step on [map] and returns it, or null when there is nothing to undo. */
  fun undo(map: NdsMap): NdsUndoStep? {
    val step = undoSteps.removeLastOrNull() ?: return null
    closeOpenGroups()
    when (step) {
      // Reverse order, so repeated writes to one square unwind to the value it started with.
      is NdsGridStep -> for (edit in step.edits.asReversed()) apply(map, edit, edit.before)
      is NdsSceneStep -> applyScene(map, step.before)
    }
    redoSteps.addLast(step)
    return step
  }

  /** Reapplies the last undone step on [map] and returns it, or null when there is none. */
  fun redo(map: NdsMap): NdsUndoStep? {
    val step = redoSteps.removeLastOrNull() ?: return null
    closeOpenGroups()
    when (step) {
      is NdsGridStep -> for (edit in step.edits) apply(map, edit, edit.after)
      is NdsSceneStep -> applyScene(map, step.after)
    }
    undoSteps.addLast(step)
    return step
  }

  private fun push(step: NdsUndoStep) {
    undoSteps.addLast(step)
    while (undoSteps.size > limit) undoSteps.removeFirst()
    // A new edit invalidates whatever was undone past it.
    redoSteps.clear()
  }

  /**
   * Drops the open stroke and drag.
   *
   * Whatever was open belongs to the past once history moves: appending to it would edit a step
   * that has already changed stacks.
   */
  private fun closeOpenGroups() {
    stroke = null
    dragStep = null
  }

  private fun apply(map: NdsMap, edit: NdsCellEdit, value: Double) {
    when (edit.kind) {
      NdsCellKind.TILE -> map.grid.setTile(edit.layer, edit.x, edit.z, value.toInt())
      NdsCellKind.HEIGHT -> map.grid.setHeight(edit.layer, edit.x, edit.z, value)
      NdsCellKind.COLLISION -> map.grid.setCollision(edit.x, edit.z, value.toInt())
      NdsCellKind.PERMISSION -> map.grid.setPermission(edit.x, edit.z, value.toInt())
    }
  }

  /** Puts the props, scenery and collision of a snapshot back onto the map. */
  private fun applyScene(map: NdsMap, snapshot: NdsSceneSnapshot) {
    map.props.clear()
    map.props += snapshot.props.map { it.copy() }
    map.terrainRemovals.clear()
    map.terrainRemovals += snapshot.removals.map {
      it.copy(clearedCollision = it.clearedCollision.toMutableList())
    }
    map.terrainTransforms.clear()
    map.terrainTransforms += snapshot.transforms.map { it.copy() }
    map.walkSurfaces.clear()
    map.walkSurfaces += snapshot.walkSurfaces.map { it.copy() }
    for (x in 0 until map.grid.cols) {
      snapshot.collision.getOrNull(x)?.copyInto(map.grid.collisions[x])
    }
  }

  companion object {
    /**
     * How many steps to keep. Scene steps carry a copy of the collision grid, so this also bounds
     * how much of the map the history can hold.
     */
    const val DEFAULT_LIMIT = 150
  }
}
