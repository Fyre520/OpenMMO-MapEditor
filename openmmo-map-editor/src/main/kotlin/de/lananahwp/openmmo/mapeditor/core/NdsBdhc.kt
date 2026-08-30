package de.lananahwp.openmmo.mapeditor.core

import kotlin.math.abs

/** One X/Z corner of a BDHC plate's axis-aligned footprint, stored as Nintendo fx32. */
data class NdsBdhcPoint(val x: Int, val z: Int)

/** Plane normal (A, B, C) for Ax + By + Cz + D = 0, stored as Nintendo fx32. */
data class NdsBdhcNormal(val x: Int, val y: Int, val z: Int)

/** Indices joining a footprint, plane normal and plane constant into one walkable plate. */
data class NdsBdhcPlate(
    val firstPointIndex: Int,
    val secondPointIndex: Int,
    val normalIndex: Int,
    val constantIndex: Int,
)

/** One Z scanline bucket into the plate access list. */
data class NdsBdhcStrip(
    val scanline: Int,
    val accessListElementCount: Int,
    val accessListStartIndex: Int,
)

/**
 * Gen 4's invisible walkable-height database.
 *
 * Coordinates exposed by [heightAt] and [plateHeightAt] are ordinary game world units after
 * decoding fx32 (one map tile is 16 of these units). The stored integer arrays are kept exact so
 * parsing never throws away ROM precision.
 */
class NdsBdhc private constructor(
    val points: List<NdsBdhcPoint>,
    val normals: List<NdsBdhcNormal>,
    val constants: IntArray,
    val plates: List<NdsBdhcPlate>,
    val strips: List<NdsBdhcStrip>,
    val accessList: IntArray,
) {
  /** Solves one plate's plane for Y at local game coordinates [x], [z]. */
  fun plateHeightAt(plateIndex: Int, x: Double, z: Double): Double? {
    val plate = plates.getOrNull(plateIndex) ?: return null
    val normal = normals[plate.normalIndex]
    val ny = fx32(normal.y)
    if (abs(ny) < 1e-12) return null
    return -(fx32(normal.x) * x + fx32(normal.z) * z + fx32(constants[plate.constantIndex])) / ny
  }

  /** Whether local game coordinates [x], [z] lie inside a plate's inclusive footprint. */
  fun contains(plateIndex: Int, x: Double, z: Double): Boolean {
    val plate = plates.getOrNull(plateIndex) ?: return false
    val first = points[plate.firstPointIndex]
    val second = points[plate.secondPointIndex]
    val x0 = minOf(fx32(first.x), fx32(second.x))
    val x1 = maxOf(fx32(first.x), fx32(second.x))
    val z0 = minOf(fx32(first.z), fx32(second.z))
    val z1 = maxOf(fx32(first.z), fx32(second.z))
    return x in x0..x1 && z in z0..z1
  }

  /**
   * Reproduces `CalculateObjectHeight` from Platinum/HGSS.
   *
   * A bridge can put several plates over one X/Z position. The games test at most ten candidates
   * from the relevant strip and choose the height nearest [currentY], rather than blindly taking
   * the highest or lowest floor.
   */
  fun heightAt(currentY: Double, x: Double, z: Double): Double? {
    val strip = stripFor(z) ?: return null
    var best: Double? = null
    var bestDifference = Double.POSITIVE_INFINITY
    var candidates = 0
    val end = strip.accessListStartIndex + strip.accessListElementCount
    for (i in strip.accessListStartIndex until end) {
      val plateIndex = accessList[i]
      if (!contains(plateIndex, x, z)) continue
      val height = plateHeightAt(plateIndex, x, z) ?: continue
      val difference = abs(currentY - height)
      if (difference < bestDifference) {
        best = height
        bestDifference = difference
      }
      candidates++
      if (candidates >= MAX_HEIGHT_CANDIDATES) break
    }
    return best
  }

  /** Plate footprint in decoded local game units: minX, minZ, maxX, maxZ. */
  fun plateBounds(plateIndex: Int): DoubleArray? {
    val plate = plates.getOrNull(plateIndex) ?: return null
    val first = points[plate.firstPointIndex]
    val second = points[plate.secondPointIndex]
    return doubleArrayOf(
        minOf(fx32(first.x), fx32(second.x)),
        minOf(fx32(first.z), fx32(second.z)),
        maxOf(fx32(first.x), fx32(second.x)),
        maxOf(fx32(first.z), fx32(second.z)),
    )
  }

  /** Exact port of the game's binary search for the strip owning one Z scanline. */
  private fun stripFor(z: Double): NdsBdhcStrip? {
    if (strips.isEmpty()) return null
    if (strips.size == 1) return strips[0]
    var low = 0
    var high = strips.lastIndex
    var mid = high / 2
    while (true) {
      if (fx32(strips[mid].scanline) > z) {
        if (high - 1 > low) {
          high = mid
          mid = (low + high) / 2
        } else {
          return strips[mid]
        }
      } else {
        if (low + 1 < high) {
          low = mid
          mid = (low + high) / 2
        } else {
          return strips[mid + 1]
        }
      }
    }
  }

  companion object {
    const val FX32_ONE = 4096.0
    const val GAME_UNITS_PER_TILE = 16.0
    private const val HEADER_SIZE = 16
    private const val MAX_HEIGHT_CANDIDATES = 10

    fun fx32(value: Int): Double = value / FX32_ONE

    /** Strictly parses one complete BDHC section, returning null for malformed data. */
    fun parse(bytes: ByteArray): NdsBdhc? {
      if (bytes.size < HEADER_SIZE ||
          bytes[0] != 'B'.code.toByte() || bytes[1] != 'D'.code.toByte() ||
          bytes[2] != 'H'.code.toByte() || bytes[3] != 'C'.code.toByte()) return null

      fun u16(offset: Int): Int =
          (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
      fun s32(offset: Int): Int =
          (bytes[offset].toInt() and 0xFF) or
              ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
              ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
              (bytes[offset + 3].toInt() shl 24)

      val pointCount = u16(4)
      val normalCount = u16(6)
      val constantCount = u16(8)
      val plateCount = u16(10)
      val stripCount = u16(12)
      val accessCount = u16(14)
      val expected = HEADER_SIZE.toLong() + pointCount * 8L + normalCount * 12L +
          constantCount * 4L + plateCount * 8L + stripCount * 8L + accessCount * 2L
      if (expected != bytes.size.toLong()) return null

      var cursor = HEADER_SIZE
      val points = ArrayList<NdsBdhcPoint>(pointCount)
      repeat(pointCount) {
        points += NdsBdhcPoint(s32(cursor), s32(cursor + 4))
        cursor += 8
      }
      val normals = ArrayList<NdsBdhcNormal>(normalCount)
      repeat(normalCount) {
        normals += NdsBdhcNormal(s32(cursor), s32(cursor + 4), s32(cursor + 8))
        cursor += 12
      }
      val constants = IntArray(constantCount) {
        s32(cursor).also { cursor += 4 }
      }
      val plates = ArrayList<NdsBdhcPlate>(plateCount)
      repeat(plateCount) {
        plates += NdsBdhcPlate(
            u16(cursor), u16(cursor + 2), u16(cursor + 4), u16(cursor + 6))
        cursor += 8
      }
      val strips = ArrayList<NdsBdhcStrip>(stripCount)
      repeat(stripCount) {
        strips += NdsBdhcStrip(s32(cursor), u16(cursor + 4), u16(cursor + 6))
        cursor += 8
      }
      val accessList = IntArray(accessCount) {
        u16(cursor).also { cursor += 2 }
      }

      if (plates.any {
            it.firstPointIndex !in points.indices || it.secondPointIndex !in points.indices ||
                it.normalIndex !in normals.indices || it.constantIndex !in constants.indices
          }) return null
      if (strips.any {
            it.accessListStartIndex < 0 || it.accessListElementCount < 0 ||
                it.accessListStartIndex + it.accessListElementCount > accessList.size
          }) return null
      if (strips.zipWithNext().any { (a, b) -> a.scanline > b.scanline }) return null
      if (accessList.any { it !in plates.indices }) return null
      return NdsBdhc(points, normals, constants, plates, strips, accessList)
    }
  }
}
