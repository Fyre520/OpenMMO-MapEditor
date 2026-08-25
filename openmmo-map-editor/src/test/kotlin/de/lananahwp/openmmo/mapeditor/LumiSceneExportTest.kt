package de.lananahwp.openmmo.mapeditor

import de.lananahwp.openmmo.mapeditor.core.Gen4Decomp
import kotlin.test.Test
import kotlin.test.assertEquals

class LumiSceneExportTest {
  @Test
  fun `derives and merges exterior matrix boundary segments`() {
    val cells =
        listOf(
            cell(1, 1, 10),
            cell(2, 1, 10),
            cell(1, 2, 10),
            cell(2, 2, 10),
            cell(1, 0, 20),
            cell(2, 0, 20),
            cell(3, 1, 30),
            cell(3, 2, 30),
        )

    assertEquals(
        listOf(
            LumiConnection("RIGHT", 0, 64, 30, 0),
            LumiConnection("UP", 0, 64, 20, 0),
        ),
        deriveLumiConnections(10, cells),
    )
  }

  @Test
  fun `ignores empty matrix sentinels and preserves destination offsets`() {
    val cells =
        listOf(
            cell(4, 3, 10),
            cell(5, 3, 10),
            cell(3, 3, 0),
            cell(6, 3, 0xFFFF),
            cell(4, 2, 20),
            cell(5, 2, 30),
            cell(3, 2, 20),
            cell(6, 2, 30),
        )

    assertEquals(
        listOf(
            LumiConnection("UP", 0, 32, 20, 32),
            LumiConnection("UP", 32, 32, 30, 0),
        ),
        deriveLumiConnections(10, cells),
    )
  }

  private fun cell(x: Int, y: Int, mapId: Int) = Gen4Decomp.MatrixCell(x, y, mapId)
}
