package de.lananahwp.openmmo.mapeditor.core

/** One decoded NSBTX texture. */
class NdsTexture(
    val name: String,
    val format: Int,
    val width: Int,
    val height: Int,
    val texdata: ByteArray,
    val spdata: ByteArray?,
    val palette: IntArray,
    val color0: Boolean,
) {
  /** Decodes this texture into ARGB pixels (width*height). */
  fun decode(): IntArray? = decodeWith(palette)

  /** Decodes this texture with an explicit palette. */
  fun decodeWith(pal: IntArray): IntArray? {
    val out = IntArray(width * height)
    return try {
      when (format) {
        1, 2, 3, 4, 6 -> decodePaletted(out, pal)
        5 -> decodeCompressed(out, pal)
        7 -> decodeDirect(out)
        else -> return null
      }
      out
    } catch (_: Exception) {
      null
    }
  }

  private fun decodePaletted(out: IntArray, pal: IntArray) {
    val bpp =
        when (format) {
          2 -> 2
          3 -> 4
          else -> 8
        }
    fun indexAt(i: Int): Int =
        when (bpp) {
          2 -> (texdata[i / 4].toInt() ushr ((i % 4) * 2)) and 0x3
          4 -> (texdata[i / 2].toInt() ushr ((i % 2) * 4)) and 0xF
          else -> texdata[i].toInt() and 0xFF
        }
    for (i in 0 until width * height) {
      if (format == 1) {
        // A3I5: index = low 5 bits, alpha = top 3 bits (PDSMS).
        val raw = texdata[i].toInt() and 0xFF
        val idx = raw and 0x1F
        val alpha = if (idx == 0 && color0) 0 else raw and 0xE0
        out[i] = (pal.getOrElse(idx) { 0xFFFFFFFF.toInt() } and 0x00FFFFFF) or (alpha shl 24)
      } else if (format == 6) {
        // A5I3: index = low 3 bits, alpha = top 5 bits (PDSMS).
        val raw = texdata[i].toInt() and 0xFF
        val idx = raw and 0x07
        val alpha = if (idx == 0 && color0) 0 else raw and 0xF8
        out[i] = (pal.getOrElse(idx) { 0xFFFFFFFF.toInt() } and 0x00FFFFFF) or (alpha shl 24)
      } else {
        // Paletted formats are opaque; only color 0 may be transparent (color0 flag).
        val idx = indexAt(i)
        out[i] = if (color0 && idx == 0) 0 else pal.getOrElse(idx) { 0xFFFFFFFF.toInt() }
      }
    }
  }

  private fun decodeCompressed(out: IntArray, pal: IntArray) {
    val sp = spdata ?: return
    var spPos = 0
    var texPos = 0
    for (y in 0 until height / 4) {
      for (x in 0 until width / 4) {
        val palDat = (sp[spPos].toInt() and 0xFF) or ((sp[spPos + 1].toInt() and 0xFF) shl 8)
        spPos += 2
        val palOffs = (palDat and 0x3FFF) * 2
        val mode = (palDat shr 14) and 3
        for (yy in 0 until 4) {
          val row = texdata[texPos++].toInt() and 0xFF
          for (xx in 0 until 4) {
            val color = (row shr (xx * 2)) and 3
            val px = x * 4 + xx
            val py = y * 4 + yy
            when {
              mode == 0 && color == 3 -> out[py * width + px] = 0
              mode == 1 && color == 3 -> out[py * width + px] = 0
              mode == 1 && color == 2 -> {
                val a = pal.getOrElse(palOffs) { 0xFFFFFFFF.toInt() }
                val b = pal.getOrElse(palOffs + 1) { 0xFFFFFFFF.toInt() }
                out[py * width + px] = mean(a, b, 0.5)
              }
              mode == 3 && color == 2 -> {
                val a = pal.getOrElse(palOffs) { 0xFFFFFFFF.toInt() }
                val b = pal.getOrElse(palOffs + 1) { 0xFFFFFFFF.toInt() }
                out[py * width + px] = mean(a, b, 5.0 / 8.0)
              }
              mode == 3 && color == 3 -> {
                val a = pal.getOrElse(palOffs) { 0xFFFFFFFF.toInt() }
                val b = pal.getOrElse(palOffs + 1) { 0xFFFFFFFF.toInt() }
                out[py * width + px] = mean(a, b, 3.0 / 8.0)
              }
              else -> out[py * width + px] = pal.getOrElse(palOffs + color) { 0xFFFFFFFF.toInt() }
            }
          }
        }
      }
    }
  }

  private fun decodeDirect(out: IntArray) {
    for (i in 0 until width * height) {
      val p = (texdata[i * 2].toInt() and 0xFF) or ((texdata[i * 2 + 1].toInt() and 0xFF) shl 8)
      val r = ((p shr 0) and 0x1F) * 255 / 31
      val g = ((p shr 5) and 0x1F) * 255 / 31
      val b = ((p shr 10) and 0x1F) * 255 / 31
      val a = if ((p and 0x8000) != 0) 0 else 255
      out[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
    }
  }

  private fun mean(a: Int, b: Int, t: Double): Int {
    val ar = (a shr 16) and 0xFF
    val ag = (a shr 8) and 0xFF
    val ab = a and 0xFF
    val aa = (a ushr 24) and 0xFF
    val br = (b shr 16) and 0xFF
    val bg = (b shr 8) and 0xFF
    val bb = b and 0xFF
    val ba = (b ushr 24) and 0xFF
    fun mix(x: Int, y: Int) = ((x * (1 - t) + y * t)).toInt()
    return (mix(aa, ba) shl 24) or (mix(ar, br) shl 16) or (mix(ag, bg) shl 8) or mix(ab, bb)
  }
}

/** Parses an NSBTX (BTX0) texture pack (port of DSPRE's NSBTXLoader.ReadTex0). */
object NdsNsbtx {

  internal fun parse(bytes: ByteArray): List<NdsTexture> {
    return parsePack(bytes).textures
  }

  /** Parses an NSBTX pack into its textures and palettes (by name). */
  internal fun parsePack(bytes: ByteArray): NdsNsbtxPack {
    try {
      return parsePackInner(bytes)
    } catch (_: Exception) {
      return NdsNsbtxPack(emptyList(), emptyMap())
    }
  }

  internal class NdsNsbtxPack(val textures: List<NdsTexture>, val palettes: Map<String, IntArray>)

  private fun parsePackInner(bytes: ByteArray): NdsNsbtxPack {
    val r = NsBmdReader(bytes)
    val blockOffset = findTex0Offset(bytes)
        ?: throw IllegalArgumentException("The Nitro file has no TEX0 block")
    return readTex0(r, bytes, blockOffset)
  }

  /** Finds TEX0 through the Nitro block table, for both standalone NSBTX and embedded NSBMD data. */
  private fun findTex0Offset(bytes: ByteArray): Int? {
    if (bytes.size < 0x14) return null
    fun u16(offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    fun u32(offset: Int): Int =
        u16(offset) or (u16(offset + 2) shl 16)
    val count = u16(0x0E).coerceAtMost((bytes.size - 0x10) / 4)
    for (i in 0 until count) {
      val offset = u32(0x10 + i * 4)
      if (offset >= 0 && offset + 4 <= bytes.size &&
          bytes[offset] == 'T'.code.toByte() && bytes[offset + 1] == 'E'.code.toByte() &&
          bytes[offset + 2] == 'X'.code.toByte() && bytes[offset + 3] == '0'.code.toByte()) {
        return offset
      }
    }
    return null
  }

  private fun readTex0(r: NsBmdReader, bytes: ByteArray, blockOffset: Int): NdsNsbtxPack {
    r.seek(blockOffset)
    r.skip(4) // "TEX0"
    r.u32() // blocksize
    r.skip(4)
    r.skip(2) // texdatasize
    r.skip(6)
    val texdataoffset = r.u32() + blockOffset
    r.skip(4)
    r.skip(2) // sptexsize
    r.skip(6)
    val sptexoffset = r.u32() + blockOffset
    val spdataoffset = r.u32() + blockOffset
    r.skip(4)
    r.skip(2) // paldatasize
    r.skip(2)
    val paldefoffset = r.u32() + blockOffset
    val paldataoffset = r.u32() + blockOffset

    r.skip(1)
    val texnum = r.u8()
    val blockPtr = r.pos
    r.seek(paldefoffset)
    r.skip(1)
    val palnum = r.u8()
    r.seek(blockPtr)

    data class TexInfo(val offset: Int, val format: Int, val width: Int, val height: Int, val color0: Boolean, val name: String, val size: Int)
    data class PalInfo(val offset: Int, val size: Int, val name: String)

    val bpp = intArrayOf(0, 8, 2, 4, 8, 2, 8, 16)
    r.skip(14 + texnum * 4)
    val texInfos = ArrayList<TexInfo>(texnum)
    for (i in 0 until texnum) {
      val offset = r.u16() shl 3
      val param = r.u16()
      r.skip(4)
      val format = (param shr 10) and 7
      val width = 8 shl ((param shr 4) and 7)
      val height = 8 shl ((param shr 7) and 7)
      val color0 = ((param shr 13) and 1) == 1
      val absOffset = if (format == 5) offset + sptexoffset else offset + texdataoffset
      val size = width * height * bpp[format] / 8
      texInfos += TexInfo(absOffset, format, width, height, color0, "", size)
    }
    for (i in 0 until texnum) texInfos[i].let { /* name read below */ }

    // Texture names
    val texNames = ArrayList<String>(texnum)
    for (i in 0 until texnum) texNames += r.name()
    // Palette defs
    r.seek(paldefoffset + 2)
    r.skip(14 + palnum * 4)
    val palOffsets = IntArray(palnum)
    for (i in 0 until palnum) {
      palOffsets[i] = (r.u16() shl 3) + paldataoffset
      r.skip(2)
    }
    val palNames = ArrayList<String>(palnum)
    for (i in 0 until palnum) palNames += r.name()

    // Palette sizes from sorted offsets
    val offsets = (palOffsets.toSet() + (blockOffset + r.u32().let { 0 })).toMutableList()
    // recompute block limit from BTX0 header
    val blockSize = NsBmdReader(bytes).let { it.seek(blockOffset); it.skip(4); it.u32() }
    offsets.removeAll { it <= 0 }
    offsets.add(blockOffset + blockSize)
    offsets.sort()
    val palSizes = IntArray(palnum)
    for (k in 0 until palnum) {
      val o = palOffsets[k]
      val next = offsets.firstOrNull { it - o > 0 } ?: (blockOffset + blockSize)
      palSizes[k] = next - o
    }

    // Texture data + palettes
    val result = ArrayList<NdsTexture>(texnum)
    for (i in 0 until texnum) {
      val info = texInfos[i]
      r.seek(info.offset)
      val texBytes = r.data.copyOfRange(r.pos, (r.pos + info.size).coerceAtMost(r.data.size))
      var spBytes: ByteArray? = null
      if (info.format == 5) {
        val spSize = info.size / 2
        r.seek(spdataoffset + (info.offset - sptexoffset) / 2)
        spBytes = r.data.copyOfRange(r.pos, (r.pos + spSize).coerceAtMost(r.data.size))
      }
      // find the palette for this texture
      val palOffset = palOffsets.getOrElse(i) { paldataoffset }
      val palSize = palSizes.getOrElse(i) { 0x200 }
      r.seek(palOffset)
      val entryCount = (palSize / 2).coerceIn(0, 256)
      val palette = IntArray(256) { 0xFFFFFFFF.toInt() }
      for (j in 0 until entryCount) {
        val p = r.u16()
        // BGR555 -> opaque RGB (PDSMS: alpha is handled per-format, palette stays opaque).
        val red = ((p shr 0) and 0x1F) shl 3
        val green = ((p shr 5) and 0x1F) shl 3
        val blue = ((p shr 10) and 0x1F) shl 3
        palette[j] = 0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
      }
      result += NdsTexture(texNames[i], info.format, info.width, info.height, texBytes, spBytes, palette, info.color0)
    }

    // Build palettes by name for name-based matching.
    val palettesByName = LinkedHashMap<String, IntArray>()
    for (k in 0 until palnum) {
      val palOffset = palOffsets[k]
      val palSize = palSizes[k]
      r.seek(palOffset)
      val entryCount = (palSize / 2).coerceIn(0, 256)
      val palColors = IntArray(256) { 0xFFFFFFFF.toInt() }
      for (j in 0 until entryCount) {
        val p = r.u16()
        val red = ((p shr 0) and 0x1F) shl 3
        val green = ((p shr 5) and 0x1F) shl 3
        val blue = ((p shr 10) and 0x1F) shl 3
        palColors[j] = 0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
      }
      palettesByName[palNames[k]] = palColors
    }
    return NdsNsbtxPack(result, palettesByName)
  }
}
