package de.lananahwp.openmmo.mapeditor.project

import de.lananahwp.openmmo.mapeditor.core.NdsMeshSnapshot
import de.lananahwp.openmmo.mapeditor.core.NdsTri
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NdsAssetCleanupTest {
  @Test
  fun `cleanup finds only unused assets and deletion can be undone`() {
    val root = Files.createTempDirectory("nds-asset-cleanup").toFile()
    try {
      val project = NdsProject(root, legacyCustomTileRoot = null)
      val snapshot = NdsMeshSnapshot(listOf(unitTriangle()), emptyMap(), emptyMap())
      val usedProp = project.saveExtractedProp("Used prop", snapshot, "MAP_CLEANUP")
      val unusedProp = project.saveExtractedProp("Unused prop", snapshot, "MAP_CLEANUP")
      val openOnlyProp = project.saveExtractedProp("Open only prop", snapshot, "MAP_CLEANUP")
      project.customTileStore.add("Used tile", snapshot, requestedIndex = 1200)
      project.customTileStore.add("Unused tile", snapshot, requestedIndex = 1201)

      val map = project.createMap(
          "MAP_CLEANUP", "Cleanup", 1, 1, 1,
          matrixX = null, matrixY = null)
      map.props += project.createProp(usedProp.key, 2f, 2f)
      map.grid.setTile(0, 2, 2, 1200)
      project.save(map)

      // Reopen to prove the scanner reads all persisted custom maps, then add an unsaved placement
      // to prove currently open work is protected too.
      val reopened = NdsProject(root, legacyCustomTileRoot = null)
      val loaded = assertNotNull(reopened.loadMap("MAP_CLEANUP"))
      loaded.props += reopened.createProp(openOnlyProp.key, 4f, 4f)

      val candidates = reopened.unusedCustomAssets()
      assertEquals(listOf(unusedProp.key), candidates.extractedProps.map { it.key })
      assertEquals(listOf(1201), candidates.tiles.map { it.index })

      val undo = reopened.deleteUnusedAssets(setOf(unusedProp.key), setOf(1201))
      assertTrue(reopened.customPropModels().none { it.key == unusedProp.key })
      assertTrue(reopened.customTileStore.tiles().none { it.index == 1201 })
      assertEquals(1, reopened.propModelPreview(usedProp.key, loaded).triangles.size)
      assertEquals(1, reopened.customTileStore.mesh(1200)?.triangles?.size)

      reopened.undoAssetCleanup(undo)
      assertTrue(reopened.customPropModels().any { it.key == unusedProp.key })
      assertTrue(reopened.customTileStore.tiles().any { it.index == 1201 })
      assertEquals(1, reopened.propModelPreview(unusedProp.key, loaded).triangles.size)
      assertEquals(1, reopened.customTileStore.mesh(1201)?.triangles?.size)
    } finally {
      root.deleteRecursively()
    }
  }

  private fun unitTriangle() = NdsTri(
      0f, 0f, 0f,
      1f, 0f, 0f,
      0f, 0f, 1f,
      0xFFFFFFFF.toInt(),
      0f, 0f, 1f, 0f, 0f, 1f,
  )
}
