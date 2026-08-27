package de.lananahwp.openmmo.mapeditor.model

import de.lananahwp.openmmo.mapeditor.core.NdsTri
import de.lananahwp.openmmo.mapeditor.project.NdsProject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NdsWalkSurfaceTest {
  @Test
  fun `cardinal slopes interpolate toward their named high edge`() {
    val east = surface(NdsWalkSurfaceDirection.EAST, low = 1.0, high = 5.0)
    val north = surface(NdsWalkSurfaceDirection.NORTH, low = 1.0, high = 5.0)

    assertEquals(1.0, east.heightAt(0.0, 2.0))
    assertEquals(3.0, east.heightAt(2.0, 2.0))
    assertEquals(5.0, east.heightAt(4.0, 2.0))
    assertEquals(5.0, north.heightAt(2.0, 0.0))
    assertEquals(1.0, north.heightAt(2.0, 4.0))
  }

  @Test
  fun `custom surfaces persist and feed the shared height query and overlay`() {
    val root = Files.createTempDirectory("nds-walk-surface").toFile()
    try {
      val project = NdsProject(root, legacyCustomTileRoot = null)
      val map = project.createMap("MAP_WALK_TEST", "Walk Test", 900, 1, 1)
      map.walkSurfaces += surface(NdsWalkSurfaceDirection.EAST, low = 0.0, high = 2.0)
      project.save(map)

      val reopened = assertNotNull(
          NdsProject(root, legacyCustomTileRoot = null).loadMap("MAP_WALK_TEST"))
      assertEquals(map.walkSurfaces, reopened.walkSurfaces)
      assertEquals(1.0, project.bdhcHeightAt(map, currentY = 1.0, x = 2.0, z = 2.0))
      assertEquals(2, project.bdhcTrianglesFor(map).size)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `compound slopes persist all four corner heights`() {
    val root = Files.createTempDirectory("nds-compound-walk-surface").toFile()
    try {
      val project = NdsProject(root, legacyCustomTileRoot = null)
      val map = project.createMap("MAP_COMPOUND_TEST", "Compound Test", 901, 1, 1)
      val surface = surface(NdsWalkSurfaceDirection.EAST, low = 0.0, high = 2.0)
      surface.setEdgeHeight(NdsWalkSurfaceDirection.NORTH, 3.0)
      map.walkSurfaces += surface
      project.save(map)

      val reopened = assertNotNull(
          NdsProject(root, legacyCustomTileRoot = null).loadMap("MAP_COMPOUND_TEST"))
      assertEquals(surface, reopened.walkSurfaces.single())
      assertEquals(listOf(2.0, 4.0, 2.0, 0.0),
          reopened.walkSurfaces.single().resolvedCornerHeights().toList())
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `scene history restores walk surface edits`() {
    val map = customMap()
    val history = NdsEditHistory()
    val before = NdsSceneSnapshot.of(map)
    map.walkSurfaces += surface(NdsWalkSurfaceDirection.SOUTH, 0.0, 2.0)
    history.recordScene("add walk surface", before, NdsSceneSnapshot.of(map))

    history.undo(map)
    assertEquals(emptyList(), map.walkSurfaces)
    history.redo(map)
    assertEquals(NdsWalkSurfaceDirection.SOUTH, map.walkSurfaces.single().riseDirection)
  }

  @Test
  fun `stair geometry fits a cardinal slope while flat geometry is rejected`() {
    val stairs = (0 until 4).flatMap { z -> quad(0f, z.toFloat(), 2f, z + 1f, z.toFloat()) }
    val fitted = assertNotNull(
        NdsProject.fitWalkSurfaceToTriangles(stairs, NdsGrid(cols = 8, rows = 8), "fit"))

    assertEquals(0, fitted.minX)
    assertEquals(0, fitted.minZ)
    assertEquals(2, fitted.maxX)
    assertEquals(4, fitted.maxZ)
    assertEquals(0.0, fitted.lowHeight)
    assertEquals(3.0, fitted.highHeight)
    assertEquals(NdsWalkSurfaceDirection.SOUTH, fitted.riseDirection)

    assertNull(
        NdsProject.fitWalkSurfaceToTriangles(
            quad(0f, 0f, 3f, 3f, 1f), NdsGrid(cols = 8, rows = 8), "flat"))
  }

  @Test
  fun `moving either endpoint changes or flips the slope direction`() {
    val editable = surface(NdsWalkSurfaceDirection.FLAT, low = 2.0, high = 2.0)

    editable.setEdgeHeight(NdsWalkSurfaceDirection.NORTH, 4.0)
    assertEquals(NdsWalkSurfaceDirection.NORTH, editable.riseDirection)
    assertEquals(2.0, editable.lowHeight)
    assertEquals(4.0, editable.highHeight)

    editable.setEdgeHeight(NdsWalkSurfaceDirection.NORTH, 1.0)
    assertEquals(NdsWalkSurfaceDirection.SOUTH, editable.riseDirection)
    assertEquals(1.0, editable.lowHeight)
    assertEquals(2.0, editable.highHeight)

    editable.setEdgeHeight(NdsWalkSurfaceDirection.NORTH, 2.0)
    assertEquals(NdsWalkSurfaceDirection.FLAT, editable.riseDirection)
    assertEquals(2.0, editable.lowHeight)
    assertEquals(2.0, editable.highHeight)
  }

  @Test
  fun `moving a perpendicular edge preserves the existing gradient`() {
    val editable = surface(NdsWalkSurfaceDirection.EAST, low = 0.0, high = 2.0)

    editable.setEdgeHeight(NdsWalkSurfaceDirection.NORTH, 3.0)

    assertEquals(NdsWalkSurfaceDirection.NORTH, editable.riseDirection)
    assertEquals(0.0, editable.lowHeight)
    assertEquals(4.0, editable.highHeight)
    assertEquals(3.0, editable.heightAtEdge(NdsWalkSurfaceDirection.NORTH))
    assertEquals(1.0, editable.heightAtEdge(NdsWalkSurfaceDirection.SOUTH))
    assertEquals(2.0, editable.heightAt(editable.minX.toDouble(), editable.minZ.toDouble()))
    assertEquals(4.0, editable.heightAt(editable.maxX.toDouble(), editable.minZ.toDouble()))
  }

  @Test
  fun `each footprint edge resizes independently and cannot cross its opposite`() {
    val editable = surface(NdsWalkSurfaceDirection.EAST, low = 0.0, high = 2.0)
    val grid = NdsGrid(cols = 8, rows = 8)

    editable.resizeEdge(NdsWalkSurfaceDirection.NORTH, 2, grid)
    editable.resizeEdge(NdsWalkSurfaceDirection.EAST, 7, grid)
    assertEquals(2, editable.minZ)
    assertEquals(7, editable.maxX)
    assertEquals(NdsWalkSurfaceDirection.EAST, editable.riseDirection)
    assertEquals(0.0, editable.lowHeight)
    assertEquals(2.0, editable.highHeight)

    editable.resizeEdge(NdsWalkSurfaceDirection.WEST, 99, grid)
    editable.resizeEdge(NdsWalkSurfaceDirection.SOUTH, -5, grid)
    assertEquals(6, editable.minX)
    assertEquals(3, editable.maxZ)
  }

  @Test
  fun `quarter turn swaps footprint dimensions and rotates the rise direction`() {
    val editable = NdsWalkSurface.cardinal(
        "rotate", minX = 2, minZ = 3, maxX = 6, maxZ = 5,
        lowHeight = 1.0, highHeight = 3.0,
        riseDirection = NdsWalkSurfaceDirection.EAST)

    editable.rotateQuarterTurns(1, NdsGrid(cols = 12, rows = 12))
    assertEquals(2, editable.maxX - editable.minX)
    assertEquals(4, editable.maxZ - editable.minZ)
    assertEquals(NdsWalkSurfaceDirection.SOUTH, editable.riseDirection)
    assertEquals(1.0, editable.lowHeight)
    assertEquals(3.0, editable.highHeight)

    editable.rotateQuarterTurns(2, NdsGrid(cols = 12, rows = 12))
    assertEquals(NdsWalkSurfaceDirection.NORTH, editable.riseDirection)
  }

  @Test
  fun `uniform footprint scaling keeps slope data and snaps to cells`() {
    val editable = NdsWalkSurface.cardinal(
        "scale", minX = 2, minZ = 3, maxX = 6, maxZ = 5,
        lowHeight = 1.0, highHeight = 3.0,
        riseDirection = NdsWalkSurfaceDirection.EAST)

    editable.scaleFootprint(1.5, NdsGrid(cols = 16, rows = 16))
    assertEquals(6, editable.maxX - editable.minX)
    assertEquals(3, editable.maxZ - editable.minZ)
    assertEquals(NdsWalkSurfaceDirection.EAST, editable.riseDirection)
    assertEquals(1.0, editable.lowHeight)
    assertEquals(3.0, editable.highHeight)
  }

  private fun quad(x0: Float, z0: Float, x1: Float, z1: Float, y: Float): List<NdsTri> =
      listOf(
          NdsTri(
              x0, y, z0, x1, y, z0, x1, y, z1,
              0, 0f, 0f, 0f, 0f, 0f, 0f),
          NdsTri(
              x0, y, z0, x1, y, z1, x0, y, z1,
              0, 0f, 0f, 0f, 0f, 0f, 0f),
      )

  private fun surface(
      direction: NdsWalkSurfaceDirection,
      low: Double,
      high: Double,
  ) = NdsWalkSurface.cardinal(
      id = "surface",
      minX = 0,
      minZ = 0,
      maxX = 4,
      maxZ = 4,
      lowHeight = low,
      highHeight = high,
      riseDirection = direction,
  )

  private fun customMap() = NdsMap(
      "MAP_TEST", 900, NdsMapHeader(), NdsEvents(), NdsGrid(), isCustom = true)
}
