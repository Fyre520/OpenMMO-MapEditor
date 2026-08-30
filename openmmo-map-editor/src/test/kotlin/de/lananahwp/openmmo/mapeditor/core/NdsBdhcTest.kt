package de.lananahwp.openmmo.mapeditor.core

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NdsBdhcTest {
  @Test
  fun `parses plates and solves flat and sloped heights`() {
    val bdhc = assertNotNull(NdsBdhc.parse(fixture()))

    assertEquals(2, bdhc.points.size)
    assertEquals(2, bdhc.plates.size)
    assertEquals(0.0, assertNotNull(bdhc.plateHeightAt(0, 8.0, 4.0)), 1e-9)
    assertEquals(8.0, assertNotNull(bdhc.plateHeightAt(1, 8.0, 4.0)), 1e-9)
  }

  @Test
  fun `chooses the overlapping floor nearest the current height`() {
    val bdhc = assertNotNull(NdsBdhc.parse(fixture()))

    assertEquals(0.0, assertNotNull(bdhc.heightAt(0.1, 8.0, 8.0)), 1e-9)
    assertEquals(8.0, assertNotNull(bdhc.heightAt(7.9, 8.0, 8.0)), 1e-9)
    assertNull(bdhc.heightAt(0.0, 17.0, 8.0))
  }

  @Test
  fun `rejects incomplete inconsistent or trailing data`() {
    val valid = fixture()
    assertNull(NdsBdhc.parse(valid.copyOf(valid.size - 1)))
    assertNull(NdsBdhc.parse(valid + byteArrayOf(0)))
    assertNull(NdsBdhc.parse(valid.copyOf().also { it[0] = 'X'.code.toByte() }))

    // The second plate's first point index lives at byte 72 in this fixture.
    assertNull(NdsBdhc.parse(valid.copyOf().also {
      it[72] = 0xFF.toByte()
      it[73] = 0x7F
    }))
  }

  @Test
  fun `map data extracts the BDHC section following the model`() {
    val bdhcBytes = fixture()
    val bytes = ByteArrayOutputStream().apply {
      u32(2048)
      u32(0)
      u32(0)
      u32(bdhcBytes.size)
      write(ByteArray(2048))
      write(bdhcBytes)
    }.toByteArray()

    val mapData = assertNotNull(NdsMapData.parse(bytes))
    assertEquals(2, assertNotNull(mapData.bdhc).plates.size)
  }

  /**
   * Two plates over the same 16x16-game-unit footprint: y=0 and y=x. One strip exposes both.
   */
  private fun fixture(): ByteArray = ByteArrayOutputStream().apply {
    write("BDHC".toByteArray(Charsets.US_ASCII))
    u16(2) // points
    u16(2) // normals
    u16(2) // constants
    u16(2) // plates
    u16(1) // strips
    u16(2) // access list

    // Axis-aligned footprint corners, fx32.
    s32(0); s32(0)
    s32(fx32(16)); s32(fx32(16))

    // Flat plane y=0, followed by -x+y=0 (y=x).
    s32(0); s32(fx32(1)); s32(0)
    s32(fx32(-1)); s32(fx32(1)); s32(0)

    s32(0); s32(0) // D constants

    u16(0); u16(1); u16(0); u16(0)
    u16(0); u16(1); u16(1); u16(1)

    s32(0); u16(2); u16(0)
    u16(0); u16(1)
  }.toByteArray()

  private fun fx32(value: Int): Int = value * NdsBdhc.FX32_ONE.toInt()

  private fun ByteArrayOutputStream.u16(value: Int) {
    write(value and 0xFF)
    write((value ushr 8) and 0xFF)
  }

  private fun ByteArrayOutputStream.u32(value: Int) = s32(value)

  private fun ByteArrayOutputStream.s32(value: Int) {
    write(value and 0xFF)
    write((value ushr 8) and 0xFF)
    write((value ushr 16) and 0xFF)
    write((value ushr 24) and 0xFF)
  }
}
