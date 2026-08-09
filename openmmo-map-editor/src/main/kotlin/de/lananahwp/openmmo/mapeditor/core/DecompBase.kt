package de.lananahwp.openmmo.mapeditor.core

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Base source backed by a PRET decomp project (pokeemerald or pokefirered), typically checked out
 * as a git submodule. Reads the tileset data the way porymap does: metatiles, metatile attributes,
 * behavior constants, the 4bpp tiles image and its JASC palettes.
 */
class DecompBase(private val rootDir: File) : RegionSource {

  override val displayName: String = rootDir.absolutePath
  override val romType: Int = detectRomType(rootDir)
  override val region: RegionConfig =
      REGIONS.values.first { it.romType == romType }
  override val primaryMetatileCount: Int =
      MetatileBehaviorsFiles.readPrimaryCount(rootDir)

  private val attrWidth: Int = MetatileBehaviorsFiles.readAttrWidth(rootDir)
  override val behaviorTable: BehaviorTable =
      BehaviorTable.fromNames(
          MetatileBehaviorsFiles.readBehaviorIds(File(rootDir, "include/constants/metatile_behaviors.h")),
          if (attrWidth == 4) 0x1FF else 0xFF,
      )

  override val tilesetNames: List<String> =
      MetatileBehaviorsFiles.readTilesetHeaders(rootDir)
          .let { headers ->
            headers.filter { !it.value }.map { it.key } +
                headers.filter { it.value }.map { it.key }
          }

  override fun isSecondaryTileset(name: String): Boolean =
      MetatileBehaviorsFiles.readTilesetHeaders(rootDir)[name] ?: false

  override val numPalettesPrimary: Int get() = 6
  override val numPalettesTotal: Int get() = 13

  private val metatileCache = HashMap<String, IntArray>()
  private val attributeCache = HashMap<String, LongArray>()
  private val pixelsCache = HashMap<String, Array<ByteArray>>()

  override fun tileCount(name: String): Int = pixels(name).size

  override fun metatileCount(name: String): Int = metatileTiles(name).size / 8

  override fun metatileTiles(name: String): IntArray =
      metatileCache.getOrPut(name) {
        val path = MetatileBehaviorsFiles.readSymbolIncbins(rootDir, "gMetatiles_${stripPrefix(name)}")
            ?: return@getOrPut IntArray(0)
        val file = File(rootDir, path)
        if (!file.exists()) IntArray(0)
        else {
          val bytes = file.readBytes()
          IntArray(bytes.size / 2) { i ->
            (bytes[i * 2].toInt() and 0xFF) or ((bytes[i * 2 + 1].toInt() and 0xFF) shl 8)
          }
        }
      }

  override fun metatileAttributes(name: String): LongArray =
      attributeCache.getOrPut(name) {
        val path = MetatileBehaviorsFiles.readSymbolIncbins(rootDir, "gMetatileAttributes_${stripPrefix(name)}")
            ?: return@getOrPut LongArray(0)
        val file = File(rootDir, path)
        if (!file.exists()) LongArray(0)
        else {
          val bytes = file.readBytes()
          LongArray(bytes.size / attrWidth) { i ->
            var attr = 0L
            for (b in 0 until attrWidth) attr = attr or ((bytes[i * attrWidth + b].toLong() and 0xFF) shl (8 * b))
            attr
          }
        }
      }

  override fun behaviorOrdinals(name: String): IntArray =
      metatileAttributes(name).map { behaviorTable.behaviorOf(it).ordinal }.toIntArray()

  override fun tilePixels(name: String, tileId: Int): ByteArray =
      pixels(name).getOrNull(tileId) ?: ByteArray(64)

  override fun paletteColors(name: String, paletteId: Int): IntArray {
    val dir = tilesetDir(name)
    val file = File(dir, "palettes/${paletteId.toString().padStart(2, '0')}.pal")
    if (!file.exists()) return grayscalePalette(paletteId)
    val colors = JascPal.parse(file)
    if (colors.size < 16) {
      val out = colors.toMutableList()
      while (out.size < 16) out.add(0xFFFFFFFF.toInt())
      return out.toIntArray()
    }
    return colors.take(16).toIntArray()
  }

  private fun pixels(name: String): Array<ByteArray> =
      pixelsCache.getOrPut(name) {
        val dir = tilesetDir(name)
        val png = File(dir, "tiles.png")
        if (!png.exists()) return@getOrPut arrayOf()
        val image = ImageIO.read(png) ?: return@getOrPut arrayOf()
        val w = image.width
        val h = image.height
        if (w % 8 != 0 || h % 8 != 0) return@getOrPut arrayOf()
        val out = mutableListOf<ByteArray>()
        for (ty in 0 until h / 8) {
          for (tx in 0 until w / 8) {
            val tile = ByteArray(64)
            for (y in 0 until 8) {
              for (x in 0 until 8) {
                var v = image.getRGB(tx * 8 + x, ty * 8 + y) and 0xFF
                v = v and 0x0F // flatten 8bpp to 4bpp like gbagfx
                tile[y * 8 + x] = v.toByte()
              }
            }
            out.add(tile)
          }
        }
        out.toTypedArray()
      }

  private fun tilesetDir(name: String): File {
    val path = MetatileBehaviorsFiles.readSymbolIncbins(rootDir, "gMetatiles_${stripPrefix(name)}")
    val base = path?.substringBeforeLast('/') ?: return rootDir
    return File(rootDir, base)
  }

  companion object {
    fun stripPrefix(name: String): String = name.removePrefix("gTileset_")

    fun detectRomType(rootDir: File): Int =
        if (MetatileBehaviorsFiles.readAttrWidth(rootDir) == 4) 0 else 1

    private fun grayscalePalette(id: Int): IntArray =
        IntArray(16) { j ->
          val v = j * 16 + id
          (0xFF000000.toInt()) or (v shl 16) or (v shl 8) or v
        }
  }
}

object JascPal {
  fun parse(file: File): List<Int> {
    val lines = file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.size < 3 || !lines[0].startsWith("JASC-PAL")) return emptyList()
    val colors = mutableListOf<Int>()
    for (line in lines.drop(3)) {
      val parts = line.split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
      if (parts.size >= 3) {
        val r = parts[0].coerceIn(0, 255)
        val g = parts[1].coerceIn(0, 255)
        val b = parts[2].coerceIn(0, 255)
        colors.add(0xFF000000.toInt() or (r shl 16) or (g shl 8) or b)
      }
    }
    return colors
  }
}

/** File-based helpers shared with the codegen's MetatileBehaviors parser. */
object MetatileBehaviorsFiles {
  fun readPrimaryCount(rootDir: File): Int {
    val file = File(rootDir, "include/fieldmap.h")
    if (!file.exists()) return 512
    val re = Regex("""#define\s+NUM_METATILES_IN_PRIMARY\s+(\d+)""")
    return file.readLines().firstNotNullOfOrNull { re.find(it.trim())?.groupValues?.get(1)?.toInt() } ?: 512
  }

  fun readAttrWidth(rootDir: File): Int {
    val file = File(rootDir, "src/data/tilesets/metatiles.h")
    if (!file.exists()) return 2
    val re = Regex("""INCBIN_U(16|32)\("([^"]+)/metatile_attributes\.bin"\)""")
    return re.findAll(file.readText()).firstOrNull()?.groupValues?.get(1)?.toInt()?.let { if (it == "32") 4 else 2 } ?: 2
  }

  fun readBehaviorIds(file: File): Map<String, Int> {
    if (!file.exists()) return emptyMap()
    val out = LinkedHashMap<String, Int>()
    val defineRe = Regex("""#define\s+(MB_\w+)\s+(0x[0-9A-Fa-f]+|\d+)""")
    for (m in defineRe.findAll(file.readText())) out[m.groupValues[1]] = parseInt(m.groupValues[2])
    val entryRe = Regex("""^(MB_\w+)\s*(?:=\s*(0x[0-9A-Fa-f]+|\d+))?""")
    var next = 0
    var inEnum = false
    for (raw in file.readLines()) {
      val line = raw.trim()
      if (line.startsWith("enum")) { inEnum = true; continue }
      if (!inEnum) continue
      if (line.startsWith("}")) { inEnum = false; continue }
      val m = entryRe.find(line) ?: continue
      val id = m.groupValues[2].takeIf { it.isNotEmpty() }?.let { parseInt(it) } ?: next
      out.putIfAbsent(m.groupValues[1], id)
      next = id + 1
    }
    return out
  }

  /** gTileset_X -> isSecondary, in declaration order. */
  fun readTilesetHeaders(rootDir: File): LinkedHashMap<String, Boolean> {
    val file = File(rootDir, "src/data/tilesets/headers.h")
    if (!file.exists()) return LinkedHashMap()
    val out = LinkedHashMap<String, Boolean>()
    var current: String? = null
    for (line in file.readLines()) {
      Regex("""const struct Tileset\s+(gTileset_\w+)""").find(line)?.let { m ->
        current = m.groupValues[1]
        out[current] = false
      }
      current?.let { c ->
        Regex("""\.isSecondary\s*=\s*(TRUE|FALSE)""").find(line)?.let { m ->
          out[c] = m.groupValues[1] == "TRUE"
        }
      }
    }
    return out
  }

  /** gMetatiles_X / gMetatileAttributes_X -> relative INCBIN path from metatiles.h. */
  fun readSymbolIncbins(rootDir: File, symbol: String): String? {
    val file = File(rootDir, "src/data/tilesets/metatiles.h")
    if (!file.exists()) return null
    val re = Regex("""$symbol\[]\s*=\s*INCBIN_U(?:16|32)\("([^"]+)"\)""")
    return re.find(file.readText())?.groupValues?.get(1)
  }

  private fun parseInt(s: String): Int =
      if (s.startsWith("0x") || s.startsWith("0X")) s.substring(2).toInt(16) else s.toInt()
}
