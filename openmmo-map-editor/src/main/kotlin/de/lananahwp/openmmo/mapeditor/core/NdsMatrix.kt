package de.lananahwp.openmmo.mapeditor.core

/**
 * A Gen 4 map matrix (DSPRE `GameMatrix` layout).
 *
 * Each matrix entry (one cell = one map) stores an optional header id, an optional
 * altitude, and a map-file id (the index into the maps narc, e.g. `land_data.narc`
 * / `a/0/6/5`). `EMPTY` marks a void cell.
 */
class NdsMatrix(
    val width: Int,
    val height: Int,
    val hasHeaders: Boolean,
    val hasHeights: Boolean,
    val name: String,
    val headers: IntArray,
    val altitudes: IntArray,
    val maps: IntArray,
) {
  val isEmpty: Boolean get() = width <= 0 || height <= 0

  fun mapAt(x: Int, y: Int): Int =
      if (x in 0 until width && y in 0 until height) maps[y * width + x] else EMPTY

  fun headerAt(x: Int, y: Int): Int =
      if (hasHeaders && x in 0 until width && y in 0 until height) headers[y * width + x] else 0

  fun altitudeAt(x: Int, y: Int): Int =
      if (hasHeights && x in 0 until width && y in 0 until height) altitudes[y * width + x] else 0

  /** Returns every non-empty cell in this matrix as (x, y, mapFileId). */
  fun cells(): List<IntArray> {
    val out = mutableListOf<IntArray>()
    for (y in 0 until height) {
      for (x in 0 until width) {
        val m = maps[y * width + x]
        if (m != EMPTY) out += intArrayOf(x, y, m)
      }
    }
    return out
  }

  companion object {
    const val EMPTY = 0xFFFF

    /** Parses one matrix file. Returns null on malformed data. */
    fun parse(bytes: ByteArray): NdsMatrix? {
      if (bytes.size < 5) return null
      val width = bytes[0].toInt() and 0xFF
      val height = bytes[1].toInt() and 0xFF
      if (width <= 0 || height <= 0 || width > 128 || height > 128) return null
      val hasHeaders = bytes[2].toInt() != 0
      val hasHeights = bytes[3].toInt() != 0
      val nameLen = bytes[4].toInt() and 0xFF
      var cursor = 5
      if (cursor + nameLen > bytes.size) return null
      val name = String(bytes, cursor, nameLen, Charsets.US_ASCII)
      cursor += nameLen
      val count = width * height
      val headers = IntArray(count)
      if (hasHeaders) {
        if (cursor + count * 2 > bytes.size) return null
        for (i in 0 until count) headers[i] = u16(bytes, cursor + i * 2)
        cursor += count * 2
      }
      val altitudes = IntArray(count)
      if (hasHeights) {
        if (cursor + count > bytes.size) return null
        for (i in 0 until count) altitudes[i] = bytes[cursor + i].toInt() and 0xFF
        cursor += count
      }
      val maps = IntArray(count)
      if (cursor + count * 2 > bytes.size) return null
      for (i in 0 until count) maps[i] = u16(bytes, cursor + i * 2)
      return NdsMatrix(width, height, hasHeaders, hasHeights, name, headers, altitudes, maps)
    }

    private fun u16(bytes: ByteArray, off: Int): Int =
        (bytes[off].toInt() and 0xFF) or ((bytes[off + 1].toInt() and 0xFF) shl 8)
  }
}
