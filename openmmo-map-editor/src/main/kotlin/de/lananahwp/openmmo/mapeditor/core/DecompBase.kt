package de.lananahwp.openmmo.mapeditor.core

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Reads tileset data from a PRET decomp. */
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

  override val numPalettesPrimary: Int =
      MetatileBehaviorsFiles.readNumber(File(rootDir, "include/fieldmap.h"), "NUM_PALS_IN_PRIMARY", 6)
  override val numPalettesTotal: Int =
      MetatileBehaviorsFiles.readNumber(File(rootDir, "include/fieldmap.h"), "NUM_PALS_TOTAL", 13)
  override val numTilesPrimary: Int =
      MetatileBehaviorsFiles.readNumber(File(rootDir, "include/fieldmap.h"), "NUM_TILES_IN_PRIMARY", 512)
  override val numTilesTotal: Int =
      MetatileBehaviorsFiles.readNumber(File(rootDir, "include/fieldmap.h"), "NUM_TILES_TOTAL", 1024)
  override val metatileAttrWidth: Int get() = attrWidth

  private val metatileCache = HashMap<String, IntArray>()
  private val attributeCache = HashMap<String, LongArray>()
  private val pixelsCache = HashMap<String, Array<ByteArray>>()

  /** gTileset_X -> (gMetatiles_X symbol, gMetatileAttributes_X symbol) from headers.h. */
  private val tilesetSymbols: Map<String, Pair<String, String>> by lazy {
    MetatileBehaviorsFiles.readTilesetSymbols(rootDir)
  }

  private fun metatilesSymbol(name: String): String =
      tilesetSymbols[name]?.first ?: "gMetatiles_${stripPrefix(name)}"

  private fun attributesSymbol(name: String): String =
      tilesetSymbols[name]?.second ?: "gMetatileAttributes_${stripPrefix(name)}"

  override fun tileCount(name: String): Int = pixels(name).size

  override fun metatileCount(name: String): Int = metatileTiles(name).size / 8

  override fun metatileTiles(name: String): IntArray =
      metatileCache.getOrPut(name) {
        val path =
            MetatileBehaviorsFiles.readSymbolIncbins(rootDir, metatilesSymbol(name))
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
        val path =
            MetatileBehaviorsFiles.readSymbolIncbins(rootDir, attributesSymbol(name))
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
    if (!file.exists()) return INVALID_PALETTE.copyOf()
    val colors = JascPal.parse(file)
    val out = IntArray(16)
    for (i in 0 until 16) out[i] = colors.getOrElse(i) { INVALID_COLOR }
    return out
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
        val raster = image.raster
        for (ty in 0 until h / 8) {
          for (tx in 0 until w / 8) {
            val tile = ByteArray(64)
            for (y in 0 until 8) {
              for (x in 0 until 8) {
    // Read indexed tile pixels.
    // Flatten unexpected 8bpp images.
                var v = raster.getSample(tx * 8 + x, ty * 8 + y, 0)
                v = v and 0x0F
                tile[y * 8 + x] = v.toByte()
              }
            }
            out.add(tile)
          }
        }
        out.toTypedArray()
      }

  private fun tilesetDir(name: String): File {
    val path = MetatileBehaviorsFiles.readSymbolIncbins(rootDir, metatilesSymbol(name))
    val base = path?.substringBeforeLast('/') ?: return rootDir
    return File(rootDir, base)
  }

  companion object {
    const val INVALID_COLOR: Int = 0xFFFF00FF.toInt()
    private val INVALID_PALETTE = IntArray(16) { INVALID_COLOR }

    fun stripPrefix(name: String): String = name.removePrefix("gTileset_")

    fun detectRomType(rootDir: File): Int =
        if (MetatileBehaviorsFiles.readAttrWidth(rootDir) == 4) 0 else 1
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
  fun readPrimaryCount(rootDir: File): Int =
      readNumber(File(rootDir, "include/fieldmap.h"), "NUM_METATILES_IN_PRIMARY", 512)

  fun readNumber(file: File, define: String, fallback: Int): Int {
    if (!file.exists()) return fallback
    val re = Regex("""#define\s+$define\s+(\d+)""")
    return file.readLines().firstNotNullOfOrNull { re.find(it.trim())?.groupValues?.get(1)?.toInt() }
        ?: fallback
  }

  fun readAttrWidth(rootDir: File): Int {
    val file = File(rootDir, "src/data/tilesets/metatiles.h")
    if (!file.exists()) return 2
    val re = Regex("""INCBIN_U(16|32)\("([^"]+)/metatile_attributes\.bin"\)""")
    return re.findAll(file.readText()).firstOrNull()?.groupValues?.get(1)?.let { if (it == "32") 4 else 2 } ?: 2
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

  /** Resolves metatile and attribute symbols. */
  fun readTilesetSymbols(rootDir: File): LinkedHashMap<String, Pair<String, String>> {
    val file = File(rootDir, "src/data/tilesets/headers.h")
    if (!file.exists()) return LinkedHashMap()
    val out = LinkedHashMap<String, Pair<String, String>>()
    val tilesetRe = Regex("""const struct Tileset\s+(gTileset_\w+)\s*=""")
    val metatilesRe = Regex("""\.metatiles\s*=\s*(gMetatiles_\w+)""")
    val attrsRe = Regex("""\.metatileAttributes\s*=\s*(gMetatileAttributes_\w+)""")
    var current: String? = null
    var met: String? = null
    var att: String? = null
    fun flush() {
      if (current != null && met != null && att != null) out[current!!] = met!! to att!!
    }
    for (line in file.readLines()) {
      tilesetRe.find(line)?.let {
        flush()
        current = it.groupValues[1]
        met = null
        att = null
      }
      current ?: continue
      metatilesRe.find(line)?.let { met = it.groupValues[1] }
      attrsRe.find(line)?.let { att = it.groupValues[1] }
    }
    flush()
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
