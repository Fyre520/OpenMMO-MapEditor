package de.lananahwp.openmmo.mapeditor.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class NdsBrushFootprintTest {
  @Test
  fun `footprint centers odd and even brushes consistently`() {
    assertEquals(listOf(4 to 9), ndsBrushFootprint(4, 9, 1, 20, 20))
    assertEquals(
        setOf(3 to 8, 4 to 8, 5 to 8, 3 to 9, 4 to 9, 5 to 9, 3 to 10, 4 to 10, 5 to 10),
        ndsBrushFootprint(4, 9, 3, 20, 20).toSet(),
    )
    assertEquals(
        setOf(4 to 9, 5 to 9, 4 to 10, 5 to 10),
        ndsBrushFootprint(4, 9, 2, 20, 20).toSet(),
    )
  }

  @Test
  fun `footprint clips at map edges`() {
    assertEquals(
        setOf(0 to 0, 1 to 0, 0 to 1, 1 to 1),
        ndsBrushFootprint(0, 0, 3, 4, 4).toSet(),
    )
  }
}
