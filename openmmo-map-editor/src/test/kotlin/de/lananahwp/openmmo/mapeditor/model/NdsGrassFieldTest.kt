package de.lananahwp.openmmo.mapeditor.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NdsGrassFieldTest {
  @Test
  fun `one painted cell creates four edges and four corners`() {
    val grid = NdsGrid(5, 5)
    grid.setTile(1, 2, 2, NdsGrassField.INTERIOR)
    grid.setHeight(1, 2, 2, 3)

    val fringe = NdsGrassField.fringes(grid)
    assertEquals(8, fringe.size)
    assertEquals(setOf(1 to 1, 2 to 1, 3 to 1, 1 to 2, 3 to 2, 1 to 3, 2 to 3, 3 to 3),
        fringe.map { it.x to it.z }.toSet())
    assertTrue(fringe.all { it.sourceLayer == 1 && it.sourceHeight == 3.0 })
  }

  @Test
  fun `joining cells removes their shared boundary`() {
    val grid = NdsGrid(6, 5)
    grid.setTile(1, 2, 2, NdsGrassField.INTERIOR)
    grid.setTile(1, 3, 2, NdsGrassField.INTERIOR)

    val fringe = NdsGrassField.fringes(grid)
    assertTrue(fringe.none { it.x == 2 && it.z == 2 })
    assertTrue(fringe.none { it.x == 3 && it.z == 2 })
    assertEquals(10, fringe.map { it.x to it.z }.toSet().size)
  }
}
