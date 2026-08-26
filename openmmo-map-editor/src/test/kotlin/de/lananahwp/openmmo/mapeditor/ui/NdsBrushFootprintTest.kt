package de.lananahwp.openmmo.mapeditor.ui

import de.lananahwp.openmmo.mapeditor.core.NdsTileset
import de.lananahwp.openmmo.mapeditor.model.NdsGrid
import kotlin.test.Test
import kotlin.test.assertEquals

class NdsBrushFootprintTest {
  @Test
  fun `cursor footprint matches odd and even paint brushes`() {
    assertEquals(listOf(4 to 9), ndsBrushFootprint(4, 9, 1, 20, 20))
    assertEquals(
        setOf(3 to 8, 4 to 8, 5 to 8, 3 to 9, 4 to 9, 5 to 9, 3 to 10, 4 to 10, 5 to 10),
        ndsBrushFootprint(4, 9, 3, 20, 20).toSet(),
    )
    assertEquals(setOf(4 to 9, 5 to 9, 4 to 10, 5 to 10),
        ndsBrushFootprint(4, 9, 2, 20, 20).toSet())
  }

  @Test
  fun `cursor footprint clips at map edges`() {
    assertEquals(setOf(0 to 0, 1 to 0, 0 to 1, 1 to 1),
        ndsBrushFootprint(0, 0, 3, 4, 4).toSet())
  }

  @Test
  fun `cursor height follows terrain tile elevation and tile shape`() {
    val grid = NdsGrid(4, 4)
    grid.setTile(0, 1, 2, NdsTileset.tiles.indexOfFirst { it.name == "Wall" })
    grid.setHeight(0, 1, 2, 3)
    assertEquals(11.0, ndsPaintCursorHeight(
        grid, 1, 2, 0, terrainHeight = 5.0, modelScale = 1f,
        customGeometry = emptyMap()))
  }

  @Test
  fun `cursor stays above a painted tile on another layer`() {
    val grid = NdsGrid(4, 4)
    val wall = NdsTileset.tiles.indexOfFirst { it.name == "Wall" }
    grid.setTile(0, 1, 2, 0)
    grid.setTile(3, 1, 2, wall)
    grid.setHeight(3, 1, 2, 7)
    assertEquals(15.0, ndsPaintCursorHeight(
        grid, 1, 2, activeLayer = 0, terrainHeight = 5.0, modelScale = 1f,
        customGeometry = emptyMap()))
  }
}
