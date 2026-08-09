package de.lananahwp.openmmo.mapeditor.core

import de.lananahwp.openmmo.mapeditor.model.EditorLayout
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.image.BufferedImage

/** Overlay drawn above rendered map tiles. */
enum class RenderOverlay {
  None,
  Collision,
  Elevation,
}

/** Renders GBA maps using decomp tileset data. */
class MapRenderer(private val source: BaseSource) {

  companion object {
    const val TILE_SIZE = 8
    const val METATILE_SIZE = 16
    const val TILES_PER_METATILE = 8

    const val UNUSED_TILE_NORMAL = 0x3014
    const val UNUSED_TILE_COVERED = 0x0000
    const val UNUSED_TILE_SPLIT = 0x0000

    private const val INVALID = 0xFFFF00FF.toInt()
  }

  private data class TileKey(
      val tileset: String,
      val localId: Int,
      val paletteSource: String,
      val paletteId: Int,
      val xflip: Boolean,
      val yflip: Boolean,
  )

  private data class MetatileKey(
      val primary: String,
      val secondary: String,
      val metatileId: Int,
  )

  private val tileCache = HashMap<TileKey, BufferedImage>()
  private val metatileCache = HashMap<MetatileKey, BufferedImage>()

  /** Renders the complete map with an optional overlay. */
  fun renderMap(layout: EditorLayout, overlay: RenderOverlay = RenderOverlay.None): BufferedImage {
    val w = layout.width
    val h = layout.height
    if (w <= 0 || h <= 0 || layout.blocks.isEmpty()) return blank(METATILE_SIZE, METATILE_SIZE)
    val primary = layout.primaryTileset
    val secondary = layout.secondaryTileset
    val img = BufferedImage(w * METATILE_SIZE, h * METATILE_SIZE, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    for (i in layout.blocks.indices) {
      val block = layout.blocks[i]
      val x = (i % w) * METATILE_SIZE
      val y = (i / w) * METATILE_SIZE
      g.drawImage(metatile(primary, secondary, block and 0x3FF), x, y, null)
      if (overlay != RenderOverlay.None) drawOverlay(g, block, x, y, overlay)
    }
    g.dispose()
    return img
  }

  /** Renders one block for incremental painting. */
  fun blockImage(
      primary: String,
      secondary: String,
      block: Int,
      overlay: RenderOverlay = RenderOverlay.None,
  ): BufferedImage {
    val base = metatile(primary, secondary, block and 0x3FF)
    if (overlay == RenderOverlay.None) return base
    val img = BufferedImage(METATILE_SIZE, METATILE_SIZE, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.drawImage(base, 0, 0, null)
    drawOverlay(g, block, 0, 0, overlay)
    g.dispose()
    return img
  }

  /** Renders the layout border. */
  fun renderBorder(layout: EditorLayout, overlay: RenderOverlay = RenderOverlay.None): BufferedImage {
    val w = layout.borderWidth
    val h = layout.borderHeight
    if (w <= 0 || h <= 0 || layout.border.isEmpty()) return blank(METATILE_SIZE, METATILE_SIZE)
    val primary = layout.primaryTileset
    val secondary = layout.secondaryTileset
    val img = BufferedImage(w * METATILE_SIZE, h * METATILE_SIZE, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    for (i in layout.border.indices) {
      val block = layout.border[i]
      val x = (i % w) * METATILE_SIZE
      val y = (i / w) * METATILE_SIZE
      g.drawImage(metatile(primary, secondary, block and 0x3FF), x, y, null)
      if (overlay != RenderOverlay.None) drawOverlay(g, block, x, y, overlay)
    }
    g.dispose()
    return img
  }

  /** Renders a single metatile for the tileset picker. */
  fun renderMetatile(primary: String, secondary: String, metatileId: Int): BufferedImage =
      metatile(primary, secondary, metatileId)

  private fun metatile(primary: String, secondary: String, metatileId: Int): BufferedImage =
      metatileCache.getOrPut(MetatileKey(primary, secondary, metatileId)) {
        val img = BufferedImage(METATILE_SIZE, METATILE_SIZE, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        // Fill transparency with black.
        g.color = Color.BLACK
        g.fillRect(0, 0, METATILE_SIZE, METATILE_SIZE)
        val tiles = resolveMetatile(primary, secondary, metatileId)
        if (tiles != null) {
          val layerType = layerTypeOf(primary, secondary, metatileId)
          for (layer in 0..2) {
            for (ty in 0 until 2) {
              for (tx in 0 until 2) {
                val offset = ty * 2 + tx
                val raw =
                    when (layerType) {
                      0 -> if (layer == 0) UNUSED_TILE_NORMAL else tiles[offset + (layer - 1) * 4]
                      1 -> if (layer == 2) UNUSED_TILE_COVERED else tiles[offset + layer * 4]
                      2 -> if (layer == 1) UNUSED_TILE_SPLIT else tiles[offset + (if (layer == 0) 0 else 1) * 4]
                      else -> if (layer == 0) UNUSED_TILE_NORMAL else tiles[offset + (layer - 1) * 4]
                    }
                g.drawImage(tile(primary, secondary, raw), tx * TILE_SIZE, ty * TILE_SIZE, null)
              }
            }
          }
        }
        g.dispose()
        img
      }

  private fun resolveMetatile(primary: String, secondary: String, metatileId: Int): IntArray? {
    val inPrimary = metatileId < source.primaryMetatileCount
    val tileset = if (inPrimary) primary else secondary
    val index = if (inPrimary) metatileId else metatileId - source.primaryMetatileCount
    val tiles = source.metatileTiles(tileset)
    val start = index * TILES_PER_METATILE
    if (tiles.size < start + TILES_PER_METATILE) return null
    return tiles.copyOfRange(start, start + TILES_PER_METATILE)
  }

  /** Reads the metatile layer type. */
  private fun layerTypeOf(primary: String, secondary: String, metatileId: Int): Int {
    val inPrimary = metatileId < source.primaryMetatileCount
    val tileset = if (inPrimary) primary else secondary
    val index = if (inPrimary) metatileId else metatileId - source.primaryMetatileCount
    val attrs = source.metatileAttributes(tileset)
    val attr = attrs.getOrElse(index) { 0L }
    return if (source.metatileAttrWidth == 2) ((attr shr 12) and 0xF).toInt()
    else ((attr shr 29) and 0x3).toInt()
  }

  /** Builds and caches one tile. */
  private fun tile(primary: String, secondary: String, raw: Int): BufferedImage {
    val tileId = raw and 0x3FF
    val paletteId = (raw shr 12) and 0xF
    val xflip = (raw and 0x0400) != 0
    val yflip = (raw and 0x0800) != 0

    val tileset =
        when {
          tileId < source.numTilesPrimary -> primary
          tileId < source.numTilesTotal -> secondary
          else -> null
        }
    val paletteSource =
        when {
          paletteId < source.numPalettesPrimary -> primary
          paletteId < source.numPalettesTotal -> secondary
          else -> null
        }
    if (tileset == null || paletteSource == null) return invalidTile(xflip, yflip)

    val localId = if (tileId < source.numTilesPrimary) tileId else tileId - source.numTilesPrimary
    val key = TileKey(tileset, localId, paletteSource, paletteId, xflip, yflip)
    return tileCache.getOrPut(key) { buildTile(localId, tileset, paletteSource, paletteId, xflip, yflip) }
  }

  private fun buildTile(
      localId: Int,
      tileset: String,
      paletteSource: String,
      paletteId: Int,
      xflip: Boolean,
      yflip: Boolean,
  ): BufferedImage {
    val pixels = source.tilePixels(tileset, localId)
    if (pixels.size != 64) return invalidTile(xflip, yflip)
    val palette = source.paletteColors(paletteSource, paletteId)
    val img = BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB)
    val rgba = IntArray(64)
    for (i in 0 until 64) {
      val index = pixels[i].toInt() and 0x0F
      rgba[i] = if (index == 0) 0x00000000 else palette.getOrElse(index) { INVALID }
    }
    img.setRGB(0, 0, TILE_SIZE, TILE_SIZE, rgba, 0, TILE_SIZE)
    return if (xflip || yflip) flip(img, xflip, yflip) else img
  }

  private fun invalidTile(xflip: Boolean, yflip: Boolean): BufferedImage {
    val img = BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB)
    val rgba = IntArray(64) { INVALID }
    img.setRGB(0, 0, TILE_SIZE, TILE_SIZE, rgba, 0, TILE_SIZE)
    return if (xflip || yflip) flip(img, xflip, yflip) else img
  }

  private fun flip(img: BufferedImage, xflip: Boolean, yflip: Boolean): BufferedImage {
    val out = BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    g.translate(if (xflip) TILE_SIZE else 0, if (yflip) TILE_SIZE else 0)
    g.scale(if (xflip) -1.0 else 1.0, if (yflip) -1.0 else 1.0)
    g.drawImage(img, 0, 0, null)
    g.dispose()
    return out
  }

  private fun drawOverlay(g: Graphics2D, block: Int, x: Int, y: Int, overlay: RenderOverlay) {
    val collision = (block shr 10) and 0x3
    val elevation = (block shr 12) and 0xF
    val old = g.composite
    val oldFont = g.font
    when (overlay) {
      RenderOverlay.Collision -> {
        val label = if (collision == 0) "C" else collision.toString()
        g.composite = AlphaComposite.SrcOver.derive(0.42f)
        g.color = if (collision == 0) Color(105, 45, 150) else Color(210, 35, 35)
        g.fillRect(x, y, METATILE_SIZE, METATILE_SIZE)
        g.composite = AlphaComposite.SrcOver.derive(0.95f)
        g.color = Color.WHITE
        g.font = oldFont.deriveFont(Font.BOLD, 11f)
        val metrics = g.fontMetrics
        val tx = x + (METATILE_SIZE - metrics.stringWidth(label)) / 2
        val ty = y + (METATILE_SIZE - metrics.height) / 2 + metrics.ascent
        g.drawString(label, tx, ty)
      }
      RenderOverlay.Elevation ->
          if (elevation > 0) {
            g.composite = AlphaComposite.SrcOver.derive(0.35f)
            g.color = Color.CYAN
            g.fillRect(x, y + METATILE_SIZE - elevation, METATILE_SIZE, elevation)
          }
      RenderOverlay.None -> {}
    }
    g.composite = old
    g.font = oldFont
  }

  private fun blank(w: Int, h: Int): BufferedImage =
      BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
}
