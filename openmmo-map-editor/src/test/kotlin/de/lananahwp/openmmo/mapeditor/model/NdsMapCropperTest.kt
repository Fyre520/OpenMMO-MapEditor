package de.lananahwp.openmmo.mapeditor.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NdsMapCropperTest {
  @Test
  fun `crop preserves selected grid data and translates local coordinates`() {
    val map = customMap(4, 3)
    map.grid.setTile(2, 33, 34, 17)
    map.grid.setHeight(2, 33, 34, 6)
    map.grid.setCollision(33, 34, 9)
    map.grid.setPermission(33, 34, 12)
    map.props += NdsProp("kept", "custom:tree", x = 40f, z = 40f)
    map.props += NdsProp("cut", "custom:tree", x = 2f, z = 2f)
    map.events.objects += NdsObject(id = "kept", x = 35, z = 36)
    map.events.objects += NdsObject(id = "cut", x = 100, z = 90)
    map.events.warps += NdsWarp(x = 63, z = 40)
    map.events.bgEvents += NdsBgEvent(x = 50, z = 33)
    map.events.triggers += NdsTrigger(scriptId = "clipped", x = 30, z = 35, w = 5, h = 4)
    map.events.triggers += NdsTrigger(scriptId = "cut", x = 2, z = 2, w = 1, h = 1)

    val impact = NdsMapCropper.crop(map, startX = 32, startZ = 32,
        width = 64, height = 32)

    assertEquals(64, map.grid.cols)
    assertEquals(32, map.grid.rows)
    assertEquals(17, map.grid.tileAt(2, 1, 2))
    assertEquals(6, map.grid.heightAt(2, 1, 2))
    assertEquals(9, map.grid.collisionAt(1, 2))
    assertEquals(12, map.grid.permissionAt(1, 2))
    assertEquals(listOf("kept"), map.props.map { it.id })
    assertEquals(8f to 8f, map.props.single().let { it.x to it.z })
    assertEquals(3 to 4, map.events.objects.single().let { it.x to it.z })
    assertEquals(31 to 8, map.events.warps.single().let { it.x to it.z })
    assertEquals(18 to 1, map.events.bgEvents.single().let { it.x to it.z })
    assertEquals(NdsTrigger(scriptId = "clipped", x = 0, z = 3, w = 3, h = 4),
        map.events.triggers.single())
    assertEquals(1, impact.propsRemoved)
    assertEquals(1, impact.objectsRemoved)
    assertEquals(1, impact.triggersRemoved)
    assertEquals(1, impact.triggersClipped)
  }

  @Test
  fun `crop retains the selected matrix cells at their world coordinates`() {
    val map = customMap(4, 3)
    for (z in 20 until 23) for (x in 10 until 14) map.matrixCells += x to z

    NdsMapCropper.crop(map, startX = 32, startZ = 32, width = 64, height = 32)

    assertEquals(listOf(11 to 21, 12 to 21), map.matrixCells)
  }

  @Test
  fun `crop translates retained removed-prop restoration data`() {
    val map = customMap(2, 2)
    map.terrainRemovals += NdsTerrainRemoval(
        id = "inside",
        groupId = "",
        clearedCollision = mutableListOf(
            NdsCollisionRestore(35, 37, 8),
            NdsCollisionRestore(2, 2, 4),
        ),
        removedProp = NdsProp("removed", "custom:tree", x = 40f, z = 45f),
    )
    map.terrainRemovals += NdsTerrainRemoval(
        id = "outside", groupId = "",
        removedProp = NdsProp("discarded", "custom:tree", x = 2f, z = 2f),
    )

    NdsMapCropper.crop(map, startX = 32, startZ = 32, width = 32, height = 32)

    val removal = map.terrainRemovals.single()
    assertEquals("inside", removal.id)
    assertEquals(8f to 13f, removal.removedProp!!.let { it.x to it.z })
    assertEquals(listOf(NdsCollisionRestore(3, 5, 8)), removal.clearedCollision)
  }

  @Test
  fun `crop rejects ROM maps and rectangles outside the map`() {
    val romMap = NdsMap("MAP_ROM", 1, NdsMapHeader(), NdsEvents(), NdsGrid(64, 64))
    assertFailsWith<IllegalArgumentException> {
      NdsMapCropper.crop(romMap, 0, 0, 1, 1)
    }

    val custom = customMap(2, 2)
    assertFailsWith<IllegalArgumentException> {
      NdsMapCropper.crop(custom, 63, 63, 2, 1)
    }
  }

  @Test
  fun `arbitrary inclusive tile bounds are padded to a game matrix size`() {
    val map = customMap(2, 2)
    map.matrixCells += listOf(10 to 20, 11 to 20, 10 to 21, 11 to 21)
    map.grid.setTile(0, 10, 22, 7)

    val impact = NdsMapCropper.crop(map, startX = 10, startZ = 22, width = 54, height = 42)

    assertEquals(64, map.grid.cols)
    assertEquals(64, map.grid.rows)
    assertEquals(7, map.grid.tileAt(0, 0, 0))
    assertEquals(64, impact.outputWidth)
    assertEquals(64, impact.outputHeight)
    assertEquals(listOf(10 to 20, 11 to 20, 10 to 21, 11 to 21), map.matrixCells)
  }

  private fun customMap(cellsWide: Int, cellsHigh: Int) = NdsMap(
      name = "MAP_TEST",
      mapId = 900,
      header = NdsMapHeader(),
      events = NdsEvents(),
      grid = NdsGrid(cellsWide * NdsGrid.COLS, cellsHigh * NdsGrid.ROWS),
      isCustom = true,
  )
}
