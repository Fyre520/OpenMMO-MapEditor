package de.lananahwp.openmmo.mapeditor.core

/**
 * A source of tileset data for the editor: metatiles, attributes (collision/behavior), 8x8 tile
 * graphics and palettes. The editor never bundles tilesets; a base source is either a PRET decomp
 * checked out as a submodule ([DecompBase]) or a GBA ROM of the game ([RomBase]).
 */
interface BaseSource {
  val displayName: String

  /** 0 for FireRed (Kanto), 1 for Emerald (Hoenn); matches the server romType. */
  val romType: Int

  val behaviorTable: BehaviorTable

  /** gTileset_* names in definition order, primary tilesets first. */
  val tilesetNames: List<String>

  fun isSecondaryTileset(name: String): Boolean

  /** NUM_METATILES_IN_PRIMARY. */
  val primaryMetatileCount: Int

  /** Number of 16-color sub-palettes a primary tileset provides (the split point for palette ids). */
  val numPalettesPrimary: Int

  /** Total number of 16-color sub-palettes across the primary + secondary tilesets. */
  val numPalettesTotal: Int

  /** Number of 8x8 tiles a tileset provides. */
  fun tileCount(name: String): Int

  /** Number of metatiles in a tileset. */
  fun metatileCount(name: String): Int

  /**
   * Flat metatile data for a tileset: one little-endian u16 per tile, 8 tiles per metatile. Each
   * value packs tileId (bits 0-9), xflip (bit 10), yflip (bit 11), palette (bits 12-15).
   */
  fun metatileTiles(name: String): IntArray

  /** Raw metatile attribute words for a tileset, one per metatile (u16 on Emerald, u32 on FireRed). */
  fun metatileAttributes(name: String): LongArray

  /** One behavior ordinal per metatile for a tileset. */
  fun behaviorOrdinals(name: String): IntArray

  /** The 64 pixel indices (0-15) of an 8x8 tile in a tileset. */
  fun tilePixels(name: String, tileId: Int): ByteArray

  /** ARGB colors of one 16-color sub-palette of a tileset. */
  fun paletteColors(name: String, paletteId: Int): IntArray
}

/** Convenience for a decomp or ROM directory that also knows its region config. */
interface RegionSource : BaseSource {
  val region: RegionConfig
}
