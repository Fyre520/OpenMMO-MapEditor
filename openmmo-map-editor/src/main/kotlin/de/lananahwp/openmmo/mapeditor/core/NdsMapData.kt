package de.lananahwp.openmmo.mapeditor.core

/** One placed building/object inside a map file (DSPRE `Building`). */
data class NdsBuilding(
    val modelId: Int,
    val xPosition: Short,
    val yPosition: Short,
    val zPosition: Short,
    val xFraction: Int,
    val yFraction: Int,
    val zFraction: Int,
    val xRotation: Int,
    val yRotation: Int,
    val zRotation: Int,
    val width: Int,
    val height: Int,
    val length: Int,
)

/**
 * Parsed Gen 4 map file (permissions + buildings + model + terrain).
 *
 * DPPt/HGSS map files begin with four u32 section lengths:
 * permissions (32x32 byte pairs of type/collision), buildings, NSBMD model, BDHC.
 * HeartGold prepends a BGS sound block (u16 signature 0x1234, u16 length, data) after the header.
 */
class NdsMapData private constructor(
    val permissions: IntArray,
    val collisions: IntArray,
    val modelBytes: ByteArray?,
    val buildings: List<NdsBuilding>,
    val bdhc: NdsBdhc?,
) {
  val permissionsSize = 32
  val collisionSize = 32

  fun permissionAt(x: Int, y: Int): Int = permissions.getOrElse(y * 32 + x) { 0 }
  fun collisionAt(x: Int, y: Int): Int = collisions.getOrElse(y * 32 + x) { 0 }

  companion object {
    /** Parses one map file. [hasBgs] is true for HeartGold map archives. */
    fun parse(bytes: ByteArray, hasBgs: Boolean = false): NdsMapData? {
      if (bytes.size < 16) return null
      fun u32(off: Int): Int =
          (bytes[off].toInt() and 0xFF) or
              ((bytes[off + 1].toInt() and 0xFF) shl 8) or
              ((bytes[off + 2].toInt() and 0xFF) shl 16) or
              ((bytes[off + 3].toInt() and 0xFF) shl 24)
      fun u16(off: Int): Int =
          (bytes[off].toInt() and 0xFF) or ((bytes[off + 1].toInt() and 0xFF) shl 8)
      fun s16(off: Int): Short =
          (u16(off).toShort())
      val permLen = u32(0)
      val bldLen = u32(4)
      val nsbmdLen = u32(8)
      val bdhcLen = u32(12)
      if (permLen != 2048 && permLen != 2047 && permLen != 2048 - 0) return null
      var cursor = 16
      if (hasBgs) {
        if (cursor + 4 > bytes.size) return null
        val sig = (bytes[cursor].toInt() and 0xFF) or ((bytes[cursor + 1].toInt() and 0xFF) shl 8)
        if (sig != 0x1234) return null
        val bgsLen = (bytes[cursor + 2].toInt() and 0xFF) or ((bytes[cursor + 3].toInt() and 0xFF) shl 8)
        cursor += 4 + bgsLen
      }
      if (cursor + permLen > bytes.size) return null
      val permissions = IntArray(32 * 32)
      val collisions = IntArray(32 * 32)
      for (i in 0 until 32 * 32) {
        val at = cursor + i * 2
        permissions[i] = bytes[at].toInt() and 0xFF
        collisions[i] = bytes[at + 1].toInt() and 0xFF
      }
      cursor += permLen
      // Buildings: each entry is 48 bytes (DSPRE Building layout).
      val buildings = mutableListOf<NdsBuilding>()
      if (bldLen > 0 && bldLen % 48 == 0 && cursor + bldLen <= bytes.size) {
        var b = cursor
        while (b + 48 <= cursor + bldLen) {
          val modelId = u32(b)
          val xFraction = u16(b + 4)
          val xPosition = s16(b + 6)
          val yFraction = u16(b + 8)
          val yPosition = s16(b + 10)
          val zFraction = u16(b + 12)
          val zPosition = s16(b + 14)
          val xRotation = u16(b + 16)
          val yRotation = u16(b + 20)
          val zRotation = u16(b + 24)
          val width = u16(b + 29)
          val height = u16(b + 33)
          val length = u16(b + 37)
          if (modelId != 0) {
            buildings +=
                NdsBuilding(
                    modelId, xPosition, yPosition, zPosition,
                    xFraction, yFraction, zFraction,
                    xRotation, yRotation, zRotation,
                    width, height, length,
                )
          }
          b += 48
        }
      }
      cursor += bldLen
      if (cursor + nsbmdLen > bytes.size) {
        return NdsMapData(permissions, collisions, null, buildings, null)
      }
      val model = bytes.copyOfRange(cursor, cursor + nsbmdLen)
      cursor += nsbmdLen
      val bdhc =
          if (bdhcLen > 0 && cursor + bdhcLen <= bytes.size)
            NdsBdhc.parse(bytes.copyOfRange(cursor, cursor + bdhcLen))
          else null
      return NdsMapData(permissions, collisions, model, buildings, bdhc)
    }
  }
}

/** Reads internal map names from a ROM `fielddata/maptable/mapname.bin`. */
object NdsMapNames {
  private const val FIELD_WIDTH = 16

  fun parse(bytes: ByteArray): List<String> {
    val out = mutableListOf<String>()
    var offset = 0
    while (offset + FIELD_WIDTH <= bytes.size) {
      var end = offset
      while (end < offset + FIELD_WIDTH && bytes[end].toInt() != 0) end++
      out += String(bytes, offset, end - offset, Charsets.US_ASCII)
      offset += FIELD_WIDTH
    }
    return out
  }
}
