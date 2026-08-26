package de.lananahwp.openmmo.mapeditor.ui

import de.lananahwp.openmmo.mapeditor.core.NdsTileset
import de.lananahwp.openmmo.mapeditor.core.NdsTri
import de.lananahwp.openmmo.mapeditor.model.NdsGrid
import kotlin.test.Test
import kotlin.test.assertEquals

class NdsBrushFootprintTest {
  @Test
  fun `tile-only opacity affects props but not terrain`() {
    val terrain = NdsTri(
        0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f,
        color = 0xFFFFFFFF.toInt(),
        u0 = 0f, v0 = 0f, u1 = 0f, v1 = 0f, u2 = 0f, v2 = 0f,
    )
    val prop = terrain.copy(editGroup = "prop:tree-1")
    assertEquals(1f, ndsTriangleOpacity(terrain, modelOpacity = 1f, propOpacity = 0.35f))
    assertEquals(0.35f, ndsTriangleOpacity(prop, modelOpacity = 1f, propOpacity = 0.35f))
  }

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
  fun `multi-square tile expands the cursor from its brush anchors`() {
    assertEquals(
        setOf(4 to 9, 5 to 9, 6 to 9, 4 to 10, 5 to 10, 6 to 10),
        ndsTileStampFootprint(4, 9, brushSize = 1, tileWidth = 3, tileHeight = 2,
            cols = 20, rows = 20).toSet(),
    )
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
