package de.lananahwp.openmmo.mapeditor.project

import de.lananahwp.openmmo.mapeditor.core.NdsMeshSnapshot
import de.lananahwp.openmmo.mapeditor.core.NdsTri
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NdsCustomTileStoreTest {
  @Test
  fun `multi-square tile footprint persists`() {
    val root = Files.createTempDirectory("nds-multi-tile").toFile()
    try {
      val triangle = NdsTri(
          0f, 0f, 0f, 3f, 0f, 0f, 0f, 0f, 2f,
          color = 0xFFFFFFFF.toInt(),
          u0 = 0f, v0 = 0f, u1 = 0f, v1 = 0f, u2 = 0f, v2 = 0f,
      )
      val store = NdsCustomTileStore(root)
      val saved = store.add("Plaza", NdsMeshSnapshot(listOf(triangle), emptyMap(), emptyMap()),
          width = 3, height = 2)
      store.invalidate()

      assertEquals(3, saved.width)
      assertEquals(2, saved.height)
      assertEquals(3 to 2, store.tiles().single().let { it.width to it.height })
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `project tile library lives beside maps and props`() {
    val root = Files.createTempDirectory("nds-project-tiles").toFile()
    try {
      val project = NdsProject(root, legacyCustomTileRoot = null)
      assertEquals(
          root.resolve(".openmmo/nds/tiles").canonicalFile,
          project.customTileStore.rootDir.canonicalFile,
      )
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `legacy global library is copied once into the project`() {
    val root = Files.createTempDirectory("nds-project-tiles").toFile()
    val legacy = Files.createTempDirectory("nds-global-tiles").toFile()
    try {
      legacy.resolve("tiles.json").writeText(
          """{"version":1,"tiles":[{"index":1000,"name":"Old tile"}]}""")
      legacy.resolve("tile-1000").mkdirs()
      legacy.resolve("tile-1000/mesh.bin").writeBytes(byteArrayOf(1, 2, 3))

      val project = NdsProject(root, legacyCustomTileRoot = legacy)
      assertEquals(listOf("Old tile"), project.customTileStore.tiles().map { it.name })
      assertTrue(root.resolve(".openmmo/nds/tiles/tile-1000/mesh.bin").isFile)

      // Once a project library exists, later global changes must not overwrite it.
      legacy.resolve("tiles.json").writeText("""{"version":1,"tiles":[]}""")
      assertEquals(listOf("Old tile"), NdsProject(root, legacyCustomTileRoot = legacy)
          .customTileStore.tiles().map { it.name })
    } finally {
      root.deleteRecursively()
      legacy.deleteRecursively()
    }
  }
}
