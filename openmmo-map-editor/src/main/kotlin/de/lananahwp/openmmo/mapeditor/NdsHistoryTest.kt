package de.lananahwp.openmmo.mapeditor

import de.lananahwp.openmmo.mapeditor.model.NdsCellEdit
import de.lananahwp.openmmo.mapeditor.model.NdsCellKind
import de.lananahwp.openmmo.mapeditor.model.NdsEditHistory
import de.lananahwp.openmmo.mapeditor.model.NdsEvents
import de.lananahwp.openmmo.mapeditor.model.NdsGrid
import de.lananahwp.openmmo.mapeditor.model.NdsGridStep
import de.lananahwp.openmmo.mapeditor.model.NdsMap
import de.lananahwp.openmmo.mapeditor.model.NdsMapHeader
import de.lananahwp.openmmo.mapeditor.model.NdsProp
import de.lananahwp.openmmo.mapeditor.model.NdsSceneSnapshot
import de.lananahwp.openmmo.mapeditor.model.NdsSceneStep
import de.lananahwp.openmmo.mapeditor.model.NdsTerrainRemoval

/**
 * Checks DS undo/redo: what one press-drag-release counts as, and that a step puts the map back
 * exactly as it was.
 *
 * Asset-free — the history works on an [NdsMap] and nothing else, so none of this needs a ROM, a
 * decomp or a window.
 */
fun main() {
  testStrokeIsOneStep()
  testUnchangedPaintIsNotAStep()
  testRepeatedWritesToOneSquareUnwindToTheStart()
  testEraseUndoesToTheTileThatWasThere()
  testRedoAndItsInvalidation()
  testSceneStepRestoresPropsAndCollision()
  testDragFoldsIntoOneStep()
  testSnapshotsDoNotAliasTheMap()
  testHistoryIsBounded()
  println("nds history: all checks passed")
}

private fun map(cols: Int = 8, rows: Int = 8): NdsMap =
    NdsMap("MAP_TEST", 0, NdsMapHeader(), NdsEvents(), NdsGrid(cols, rows))

/** A whole stroke: begin, then paint [cells] with [tile] on layer 0. */
private fun paint(history: NdsEditHistory, m: NdsMap, tile: Int, cells: List<Pair<Int, Int>>) {
  history.beginStroke("tile")
  for ((x, z) in cells) {
    history.recordCell(NdsCellEdit(NdsCellKind.TILE, 0, x, z, m.grid.tileAt(0, x, z), tile))
    m.grid.setTile(0, x, z, tile)
  }
}

/**
 * One drag undoes in one go.
 *
 * This is the headline behaviour: a drag across a map paints a square per mouse event, and a
 * history that recorded each one would need a Ctrl+Z per frame of the drag to reverse it.
 */
private fun testStrokeIsOneStep() {
  val m = map()
  val history = NdsEditHistory()
  val cells = listOf(1 to 1, 2 to 1, 3 to 1)
  paint(history, m, tile = 4, cells = cells)
  check(history.undoDepth == 1) { "a stroke should be one step, got ${history.undoDepth}" }

  val step = history.undo(m)
  check(step is NdsGridStep && step.edits.size == 3) { "expected a 3-square grid step" }
  for ((x, z) in cells) {
    check(m.grid.tileAt(0, x, z) == -1) { "square ($x, $z) was not put back" }
  }
  check(history.undoDepth == 0 && history.redoDepth == 1)
  check(history.undo(m) == null) { "there should be nothing left to undo" }
}

/** Painting a square with what it already holds is not an edit, so undo never no-ops. */
private fun testUnchangedPaintIsNotAStep() {
  val m = map()
  val history = NdsEditHistory()
  paint(history, m, tile = 4, cells = listOf(2 to 2))
  check(history.undoDepth == 1)

  // Same tile again, over the same square: nothing changes, so nothing is recorded.
  paint(history, m, tile = 4, cells = listOf(2 to 2))
  check(history.undoDepth == 1) { "a stroke that changed nothing was recorded anyway" }

  history.undo(m)
  check(m.grid.tileAt(0, 2, 2) == -1) { "undo should reach past the empty stroke" }
}

/** A stroke that crosses one square twice must unwind to the value the square started with. */
private fun testRepeatedWritesToOneSquareUnwindToTheStart() {
  val m = map()
  val history = NdsEditHistory()
  m.grid.setTile(0, 5, 5, 9)

  history.beginStroke("tile")
  for (tile in listOf(1, 2, 3)) {
    history.recordCell(NdsCellEdit(NdsCellKind.TILE, 0, 5, 5, m.grid.tileAt(0, 5, 5), tile))
    m.grid.setTile(0, 5, 5, tile)
  }
  check(m.grid.tileAt(0, 5, 5) == 3)

  history.undo(m)
  check(m.grid.tileAt(0, 5, 5) == 9) {
    "expected the pre-stroke tile 9, got ${m.grid.tileAt(0, 5, 5)}"
  }
}

/** The flow that prompted all this: paint a square, erase it, take the erase back. */
private fun testEraseUndoesToTheTileThatWasThere() {
  val m = map()
  val history = NdsEditHistory()
  paint(history, m, tile = 7, cells = listOf(3 to 4))

  history.beginStroke("tile")
  history.recordCell(NdsCellEdit(NdsCellKind.TILE, 0, 3, 4, m.grid.tileAt(0, 3, 4), -1))
  m.grid.setTile(0, 3, 4, -1)
  check(m.grid.tileAt(0, 3, 4) == -1) { "the erase did not take" }

  history.undo(m)
  check(m.grid.tileAt(0, 3, 4) == 7) { "undoing an erase should bring the tile back" }
  history.undo(m)
  check(m.grid.tileAt(0, 3, 4) == -1) { "undoing the paint should empty the square again" }
}

private fun testRedoAndItsInvalidation() {
  val m = map()
  val history = NdsEditHistory()
  paint(history, m, tile = 4, cells = listOf(1 to 1))
  paint(history, m, tile = 5, cells = listOf(2 to 2))

  history.undo(m)
  history.undo(m)
  check(m.grid.tileAt(0, 1, 1) == -1 && m.grid.tileAt(0, 2, 2) == -1)
  check(history.redoDepth == 2)

  history.redo(m)
  check(m.grid.tileAt(0, 1, 1) == 4) { "redo did not reapply the first stroke" }
  check(history.redoDepth == 1)

  // Painting after an undo drops the branch that was undone; it no longer describes this map.
  paint(history, m, tile = 6, cells = listOf(7 to 7))
  check(history.redoDepth == 0) { "a new edit must invalidate the redo stack" }
}

/** Prop and scenery steps go back through a snapshot, collision included. */
private fun testSceneStepRestoresPropsAndCollision() {
  val m = map()
  val history = NdsEditHistory()
  m.grid.setCollision(2, 2, 0x80)
  val before = NdsSceneSnapshot.of(m)

  // Removing scenery: the prop goes, an archive entry appears, and collision under it is cleared.
  m.props += NdsProp(id = "p1", modelKey = "rom:1", x = 4f, z = 5f)
  val after = NdsSceneSnapshot.of(m)
  history.recordScene("place prop", before, after)

  m.terrainRemovals += NdsTerrainRemoval(id = "r1", groupId = "g1")
  m.grid.setCollision(2, 2, 0)
  history.recordScene("remove scenery", after, NdsSceneSnapshot.of(m))
  check(history.undoDepth == 2)

  val undoneRemoval = history.undo(m)
  check(undoneRemoval is NdsSceneStep && undoneRemoval.label == "remove scenery")
  check(m.terrainRemovals.isEmpty()) { "the archive entry should be gone again" }
  check(m.grid.collisionAt(2, 2) == 0x80) { "collision cleared with the scenery was not restored" }
  check(m.props.size == 1) { "undoing the removal must not touch the prop placed before it" }

  history.undo(m)
  check(m.props.isEmpty()) { "undoing the placement should remove the prop" }

  history.redo(m)
  check(m.props.single().let { it.id == "p1" && it.x == 4f && it.z == 5f }) {
    "redo should put the prop back where it was"
  }
}

/** A drag is one step no matter how many mouse events it spans. */
private fun testDragFoldsIntoOneStep() {
  val m = map()
  val history = NdsEditHistory()
  m.props += NdsProp(id = "p1", modelKey = "rom:1", x = 1f, z = 1f)
  val dragStart = NdsSceneSnapshot.of(m)

  for (step in 1..5) {
    m.props[0].x = 1f + step
    history.recordSceneDrag("move prop", dragStart, NdsSceneSnapshot.of(m))
  }
  check(history.undoDepth == 1) { "a drag should fold into one step, got ${history.undoDepth}" }
  check(m.props[0].x == 6f)

  history.undo(m)
  check(m.props[0].x == 1f) { "undo should return the prop to where the drag started" }
  history.redo(m)
  check(m.props[0].x == 6f) { "redo should return it to where the drag ended" }
}

/** A snapshot is a copy: editing the map afterwards must not rewrite history. */
private fun testSnapshotsDoNotAliasTheMap() {
  val m = map()
  m.props += NdsProp(id = "p1", modelKey = "rom:1", x = 1f, z = 1f)
  m.grid.setCollision(0, 0, 3)
  val snapshot = NdsSceneSnapshot.of(m)

  m.props[0].x = 99f
  m.props += NdsProp(id = "p2", modelKey = "rom:2")
  m.grid.setCollision(0, 0, 7)

  check(snapshot.props.size == 1) { "the snapshot grew a prop with the map" }
  check(snapshot.props[0].x == 1f) { "the snapshot's prop moved with the map's" }
  check(snapshot.collision[0][0] == 3) { "the snapshot's collision changed with the map's" }
  check(!snapshot.sameAs(NdsSceneSnapshot.of(m))) { "the two states should compare as different" }
}

/** The stack is bounded, oldest first, so a long session cannot grow without limit. */
private fun testHistoryIsBounded() {
  val m = map()
  val history = NdsEditHistory(limit = 2)
  paint(history, m, tile = 1, cells = listOf(0 to 0))
  paint(history, m, tile = 2, cells = listOf(1 to 0))
  paint(history, m, tile = 3, cells = listOf(2 to 0))
  check(history.undoDepth == 2) { "expected the limit to hold at 2, got ${history.undoDepth}" }

  history.undo(m)
  history.undo(m)
  check(history.undo(m) == null)
  // The dropped step was the oldest, so its square keeps the value it was painted.
  check(m.grid.tileAt(0, 0, 0) == 1) { "the oldest step should have fallen off the stack" }
  check(m.grid.tileAt(0, 1, 0) == -1 && m.grid.tileAt(0, 2, 0) == -1)
}
