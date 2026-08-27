package de.lananahwp.openmmo.mapeditor.model

import kotlin.test.Test
import kotlin.test.assertEquals

class NdsDecimalHeightTest {
  @Test
  fun `grid preserves decimal heights and clamps the existing range`() {
    val grid = NdsGrid(2, 2)
    grid.setHeight(0, 0, 0, 1.125)
    grid.setHeight(0, 1, 0, 40.5)
    grid.setHeight(0, 0, 1, -40.5)

    assertEquals(1.125, grid.heightAt(0, 0, 0))
    assertEquals(32.0, grid.heightAt(0, 1, 0))
    assertEquals(-32.0, grid.heightAt(0, 0, 1))
  }

  @Test
  fun `undo and redo restore decimal heights exactly`() {
    val map = NdsMap("decimal", 0, NdsMapHeader(), NdsEvents(), NdsGrid(2, 2), isCustom = true)
    val history = NdsEditHistory()

    history.beginStroke("height")
    history.recordCell(NdsCellEdit(NdsCellKind.HEIGHT, 0, 1, 1, 0.0, 2.375))
    map.grid.setHeight(0, 1, 1, 2.375)

    history.undo(map)
    assertEquals(0.0, map.grid.heightAt(0, 1, 1))
    history.redo(map)
    assertEquals(2.375, map.grid.heightAt(0, 1, 1))
  }
}
