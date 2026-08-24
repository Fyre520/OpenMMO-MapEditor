package de.lananahwp.openmmo.mapeditor.core

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class Gen4DecompTest {
  @Test
  fun `resolves symbolic HGSS map types`() {
    val root = Files.createTempDirectory("gen4-decomp-map-type")
    try {
      root.resolve("src/data").createDirectories()
      root.resolve("files/fielddata/eventdata/zone_event").createDirectories()
      root.resolve("include/constants").createDirectories()
      root.resolve("include/constants/maps.h").writeText("#define MAP_TEST 7\n")
      root.resolve("src/data/map_headers.h")
          .writeText(
              """
              [MAP_TEST] = {
                .mapType = MAP_TYPE_INTERIOR,
                .cameraType = 4,
              },
              """.trimIndent())

      val map = Gen4Decomp(root.toFile()).parseAll().single()

      assertEquals(4, map.header.mapType)
      assertEquals(4, map.header.cameraType)
    } finally {
      root.toFile().deleteRecursively()
    }
  }
}
