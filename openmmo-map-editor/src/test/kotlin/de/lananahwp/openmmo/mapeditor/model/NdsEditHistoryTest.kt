package de.lananahwp.openmmo.mapeditor.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NdsEditHistoryTest {
  @Test
  fun `undo and redo preserve fractional heights`() {
    val map = testMap()
    val history = NdsEditHistory()
    map.grid.setHeight(0, 1, 2, 1.25)

    history.beginStroke("height")
    history.recordCell(NdsCellEdit(NdsCellKind.HEIGHT, 0, 1, 2, 1.25, 2.75))
    map.grid.setHeight(0, 1, 2, 2.75)

    history.undo(map)
    assertEquals(1.25, map.grid.heightAt(0, 1, 2))
    assertEquals(0, history.undoDepth)
    assertEquals(1, history.redoDepth)

    history.redo(map)
    assertEquals(2.75, map.grid.heightAt(0, 1, 2))
    assertEquals(1, history.undoDepth)
    assertEquals(0, history.redoDepth)
  }

  @Test
  fun `one stroke groups every write and restores repeated cells in reverse order`() {
    val map = testMap()
    val history = NdsEditHistory()
    map.grid.setTile(0, 0, 0, 1)
    map.grid.setTile(0, 1, 0, 4)

    history.beginStroke("tile")
    paintTile(history, map, 0, 0, before = 1, after = 2)
    paintTile(history, map, 0, 0, before = 2, after = 3)
    paintTile(history, map, 1, 0, before = 4, after = 5)

    assertEquals(1, history.undoDepth)
    history.undo(map)
    assertEquals(1, map.grid.tileAt(0, 0, 0))
    assertEquals(4, map.grid.tileAt(0, 1, 0))

    history.redo(map)
    assertEquals(3, map.grid.tileAt(0, 0, 0))
    assertEquals(5, map.grid.tileAt(0, 1, 0))
  }

  @Test
  fun `a new edit after undo clears redo history`() {
    val map = testMap()
    val history = NdsEditHistory()

    history.beginStroke("tile")
    paintTile(history, map, 0, 0, before = -1, after = 1)
    history.undo(map)
    assertEquals(1, history.redoDepth)

    history.beginStroke("collision")
    history.recordCell(NdsCellEdit(NdsCellKind.COLLISION, 0, 0, 0, 0, 7))
    map.grid.setCollision(0, 0, 7)

    assertEquals(0, history.redoDepth)
    assertNull(history.redo(map))
  }

  @Test
  fun `history limit evicts the oldest stroke`() {
    val map = testMap()
    val history = NdsEditHistory(limit = 2)

    for (value in 1..3) {
      history.beginStroke("tile")
      paintTile(history, map, 0, 0, before = value - 1, after = value)
    }

    assertEquals(2, history.undoDepth)
    history.undo(map)
    history.undo(map)
    assertEquals(1, map.grid.tileAt(0, 0, 0))
    assertNull(history.undo(map))
  }

  @Test
  fun `numerically unchanged values do not create history`() {
    val history = NdsEditHistory()

    history.beginStroke("height")
    history.recordCell(NdsCellEdit(NdsCellKind.HEIGHT, 0, 0, 0, 1, 1.0))

    assertEquals(0, history.undoDepth)
  }

  private fun paintTile(
      history: NdsEditHistory,
      map: NdsMap,
      x: Int,
      z: Int,
      before: Int,
      after: Int,
  ) {
    history.recordCell(NdsCellEdit(NdsCellKind.TILE, 0, x, z, before, after))
    map.grid.setTile(0, x, z, after)
  }

  private fun testMap() =
      NdsMap(
          name = "test",
          mapId = 0,
          header = NdsMapHeader(),
          events = NdsEvents(),
          grid = NdsGrid(cols = 4, rows = 4),
      )
}
