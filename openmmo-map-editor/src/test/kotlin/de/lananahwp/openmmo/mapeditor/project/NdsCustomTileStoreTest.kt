package de.lananahwp.openmmo.mapeditor.project

import de.lananahwp.openmmo.mapeditor.core.NdsMeshSnapshot
import de.lananahwp.openmmo.mapeditor.core.NdsTri
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NdsCustomTileStoreTest {
  @Test
  fun `requested tile code persists and cannot be reused`() {
    val root = Files.createTempDirectory("nds-explicit-tile-code").toFile()
    try {
      val triangle = NdsTri(
          0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f,
          color = -1, u0 = 0f, v0 = 0f, u1 = 0f, v1 = 0f, u2 = 0f, v2 = 0f)
      val snapshot = NdsMeshSnapshot(listOf(triangle), emptyMap(), emptyMap())
      val store = NdsCustomTileStore(root)

      val saved = store.add("Chosen code", snapshot, requestedIndex = 1234)

      assertEquals(1234, saved.index)
      assertTrue(root.resolve("tile-1234_Chosen_code/mesh.bin").isFile)
      assertEquals(1235, store.nextAvailableIndex())
      store.invalidate()
      assertEquals(1, store.mesh(1234)?.triangles?.size)
      val failure = assertFailsWith<IllegalArgumentException> {
        store.add("Duplicate", snapshot, requestedIndex = 1234)
      }
      assertTrue(failure.message.orEmpty().contains("already used"))
      assertEquals(listOf("Chosen code"), store.tiles().map { it.name })
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `requested tile code cannot overlap built in codes`() {
    val root = Files.createTempDirectory("nds-reserved-tile-code").toFile()
    try {
      val triangle = NdsTri(
          0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f,
          color = -1, u0 = 0f, v0 = 0f, u1 = 0f, v1 = 0f, u2 = 0f, v2 = 0f)
      val store = NdsCustomTileStore(root)

      assertFailsWith<IllegalArgumentException> {
        store.add(
            "Reserved", NdsMeshSnapshot(listOf(triangle), emptyMap(), emptyMap()),
            requestedIndex = 13)
      }
      assertTrue(store.tiles().isEmpty())
    } finally {
      root.deleteRecursively()
    }
  }

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
  fun `internal tile visibility persists`() {
    val root = Files.createTempDirectory("nds-hidden-tile").toFile()
    try {
      val triangle = NdsTri(
          0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f,
          color = -1, u0 = 0f, v0 = 0f, u1 = 0f, v1 = 0f, u2 = 0f, v2 = 0f)
      val store = NdsCustomTileStore(root)
      store.add("Internal grass edge", NdsMeshSnapshot(listOf(triangle), emptyMap(), emptyMap()),
          overlay = true, hidden = true)
      store.invalidate()

      assertTrue(store.tiles().single().hidden)
      assertTrue(store.tiles().single().overlay)
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
