package de.lananahwp.openmmo.mapeditor.project

import de.lananahwp.openmmo.mapeditor.core.NdsFamily
import de.lananahwp.openmmo.mapeditor.core.NdsMeshSnapshot
import de.lananahwp.openmmo.mapeditor.core.NdsTri
import de.lananahwp.openmmo.mapeditor.model.NdsEvents
import de.lananahwp.openmmo.mapeditor.model.NdsGrid
import de.lananahwp.openmmo.mapeditor.model.NdsMap
import de.lananahwp.openmmo.mapeditor.model.NdsMapHeader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NdsPropFeaturesTest {
  @Test
  fun `moving one prop reuses unchanged prop geometry`() {
    val root = Files.createTempDirectory("nds-prop-transform-cache").toFile()
    try {
      val project = NdsProject(root)
      val model = project.saveExtractedProp(
          "Cached", NdsMeshSnapshot(listOf(unitTriangle()), emptyMap(), emptyMap()), "TEST")
      val map = NdsMap("TEST", 1, NdsMapHeader(), NdsEvents(), NdsGrid())
      map.props += project.createProp(model.key, 1f, 2f).copy(id = "stationary")
      map.props += project.createProp(model.key, 3f, 4f).copy(id = "moving")

      val before = project.buildingTrianglesFor(map)
      map.props[1].x = 5f
      val after = project.buildingTrianglesFor(map)

      assertSame(before[0], after[0], "unchanged prop should retain its transformed triangle")
      assertNotSame(before[1], after[1], "moved prop must rebuild its transformed triangle")
      assertEquals(5f, after[1].ax)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `prop mirrors in local X and Z and persists the toggles`() {
    val root = Files.createTempDirectory("nds-mirrored-prop").toFile()
    try {
      val project = NdsProject(root)
      val model = project.saveExtractedProp(
          "Asymmetric", NdsMeshSnapshot(listOf(unitTriangle()), emptyMap(), emptyMap()), "TEST")
      val map = project.createMap("MAP_MIRROR_TEST", "Mirror Test", 902, 1, 1)
      val prop = project.createProp(model.key, 5f, 7f).copy(
          id = "mirrored", mirrorX = true, mirrorZ = true)
      map.props += prop

      val triangle = project.buildingTrianglesFor(map).single()
      assertEquals(5f, triangle.ax)
      assertEquals(4f, triangle.bx)
      assertEquals(7f, triangle.az)
      assertEquals(6f, triangle.cz)

      project.saveProps(map)
      val reloaded = assertNotNull(NdsProject(root).loadMap(map.name)).props.single()
      assertTrue(reloaded.mirrorX)
      assertTrue(reloaded.mirrorZ)
      assertEquals(prop.scaleX, reloaded.scaleX)
      assertEquals(prop.scaleZ, reloaded.scaleZ)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `merged prop preserves relative placement and reloads as a mesh catalog entry`() {
    val root = Files.createTempDirectory("nds-merged-prop").toFile()
    try {
      val project = NdsProject(root)
      val snapshot = NdsMeshSnapshot(listOf(unitTriangle()), emptyMap(), emptyMap())
      val first = project.saveExtractedProp("First", snapshot, "TEST")
      val second = project.saveExtractedProp("Second", snapshot, "TEST")
      val map = NdsMap("TEST", 1, NdsMapHeader(), NdsEvents(), NdsGrid())
      map.props += project.createProp(first.key, 1f, 2f).copy(id = "one")
      map.props += project.createProp(second.key, 4f, 2f).copy(id = "two")

      val merged = assertNotNull(project.buildMergedPropSnapshot(map, setOf("one", "two")))
      assertEquals(2, merged.snapshot.triangles.size)
      val xs = merged.snapshot.triangles.flatMap { listOf(it.ax, it.bx, it.cx) }
      assertEquals(-2f, xs.min())
      assertEquals(2f, xs.max())

      assertEquals(3f, merged.x)
      assertEquals(0f, merged.y)
      assertEquals(2.5f, merged.z)

      val saved = project.saveMergedProp("Together", merged.snapshot, map.name)
      assertEquals("Merged", saved.category)
      val reloaded = NdsProject(root).propModels().single { it.key == saved.key }
      assertEquals("Merged", reloaded.category)
      assertEquals(2, NdsProject(root).propModelPreview(saved.key, null).triangles.size)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `foreign prop is installed under a stable family-qualified key`() {
    val root = Files.createTempDirectory("nds-foreign-prop").toFile()
    try {
      val project = NdsProject(root)
      val snapshot = NdsMeshSnapshot(listOf(unitTriangle()), emptyMap(), emptyMap())
      val source = NdsProject.PropModelInfo(
          key = "rom:7",
          label = "Foreign building",
          imported = false,
          category = "Building",
          sourceFamily = NdsFamily.PLATINUM,
          sourceModelKey = "rom:7",
      )

      val first = project.installForeignProp(source, snapshot)
      val second = project.installForeignProp(source, snapshot)
      assertEquals("foreign:platinum:7", first.key)
      assertEquals(first.key, second.key)
      val reloaded = NdsProject(root).propModels().single { it.key == first.key }
      assertTrue(reloaded.imported)
      assertEquals(NdsFamily.PLATINUM, reloaded.sourceFamily)
      assertEquals("rom:7", reloaded.sourceModelKey)
      assertEquals(1, project.propModelPreview(first.key, null).triangles.size)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `extracted prop can be copied between projects with a stable local key`() {
    val sourceRoot = Files.createTempDirectory("nds-source-extracted-prop").toFile()
    val targetRoot = Files.createTempDirectory("nds-target-extracted-prop").toFile()
    try {
      val source = NdsProject(sourceRoot)
      val saved = source.saveExtractedProp(
          "Veilstone stair", NdsMeshSnapshot(listOf(unitTriangle()), emptyMap(), emptyMap()),
          "MAP_HEADER_VEILSTONE_CITY")
      val transferable = source.transferableCustomPropModels().single { it.key == saved.key }
          .copy(sourceFamily = NdsFamily.PLATINUM, sourceModelKey = saved.key)
      val snapshot = assertNotNull(source.transferableCustomPropSnapshot(saved.key))

      val target = NdsProject(targetRoot)
      val first = target.installForeignProp(transferable, snapshot)
      val second = target.installForeignProp(transferable, snapshot)

      assertEquals("foreign:platinum:extracted:veilstone_stair", first.key)
      assertEquals(first.key, second.key)
      val reloaded = NdsProject(targetRoot).customPropModels().single()
      assertEquals(NdsFamily.PLATINUM, reloaded.sourceFamily)
      assertEquals(saved.key, reloaded.sourceModelKey)
      assertEquals(1, NdsProject(targetRoot).propModelPreview(first.key, null).triangles.size)
    } finally {
      sourceRoot.deleteRecursively()
      targetRoot.deleteRecursively()
    }
  }

  @Test
  fun `built disk library can be reopened without its source project`() {
    val source = Files.createTempDirectory("nds-library-source").toFile()
    val cache = Files.createTempDirectory("nds-library-cache").toFile()
    try {
      val built = NdsPropLibrary.loadOrBuild(source, cache)
      assertEquals(NdsFamily.HEART_GOLD, built.family)
      val reopened = assertNotNull(NdsPropLibrary.loadCached(NdsFamily.HEART_GOLD, cache))
      assertEquals(built.models, reopened.models)
    } finally {
      source.deleteRecursively()
      cache.deleteRecursively()
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
