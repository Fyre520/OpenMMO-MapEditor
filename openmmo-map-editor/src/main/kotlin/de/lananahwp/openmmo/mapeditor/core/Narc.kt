package de.lananahwp.openmmo.mapeditor.core

import java.io.File

/** Reads entries out of a Nitro ARChive (NARC). */
class Narc(val bytes: ByteArray) {

  val files: List<ByteArray> by lazy { readFiles() }

  private fun u16(offset: Int): Int =
      (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

  private fun u32(offset: Int): Int =
      u16(offset) or (u16(offset + 2) shl 16)

  private fun findMagic(magic: String, start: Int = 4): Int? {
    val m = magic.toByteArray(Charsets.US_ASCII)
    var i = start.coerceAtLeast(4)
    while (i + 4 <= bytes.size) {
      if (bytes[i] == m[0] && bytes[i + 1] == m[1] && bytes[i + 2] == m[2] && bytes[i + 3] == m[3]) {
        return i
      }
      i++
    }
    return null
  }

  private fun readFiles(): List<ByteArray> {
    require(bytes.size >= 16 && String(bytes, 0, 4, Charsets.US_ASCII) == "NARC") {
      "Not a NARC archive"
    }
    val btaf = findMagic("BTAF") ?: error("NARC missing BTAF chunk")
    val count = u16(btaf + 8)
    val allocationSize = u32(btaf + 4)
    val gmif = findMagic("GMIF", btaf + allocationSize) ?: error("NARC missing GMIF chunk")
    // Each BTAF entry is an (start, end) u32 pair relative to the GMIF payload.
    val starts = (0 until count).map { u32(btaf + 0xC + it * 8) }
    val ends = (0 until count).map { u32(btaf + 0xC + it * 8 + 4) }
    val payload = gmif + 8
    return (0 until count).map { i ->
      val start = payload + starts[i]
      val end = payload + ends[i]
      if (end <= start) ByteArray(0)
      else bytes.copyOfRange(start, end.coerceAtMost(bytes.size))
    }
  }

  companion object {
    fun from(file: File): Narc = Narc(file.readBytes())
  }
}
