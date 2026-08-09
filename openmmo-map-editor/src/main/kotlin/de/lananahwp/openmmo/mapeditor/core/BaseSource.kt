package de.lananahwp.openmmo.mapeditor.core

/** Supplies tileset graphics, attributes, and palettes. */
interface BaseSource {
  val displayName: String

  /** Server ROM type. */
  val romType: Int

  val behaviorTable: BehaviorTable

  /** gTileset_* names in definition order, primary tilesets first. */
  val tilesetNames: List<String>

  fun isSecondaryTileset(name: String): Boolean

  /** NUM_METATILES_IN_PRIMARY. */
  val primaryMetatileCount: Int

  /** NUM_TILES_IN_PRIMARY: the split point for absolute VRAM tile ids. */
  val numTilesPrimary: Int

  /** NUM_TILES_TOTAL: tile ids at or above this are invalid. */
  val numTilesTotal: Int

  /** Primary palette count. */
  val numPalettesPrimary: Int

  /** Total palette count. */
  val numPalettesTotal: Int

  /** Metatile attribute width. */
  val metatileAttrWidth: Int

  /** Number of 8x8 tiles a tileset provides. */
  fun tileCount(name: String): Int

  /** Number of metatiles in a tileset. */
  fun metatileCount(name: String): Int

  /** Reads packed metatile tile data. */
  fun metatileTiles(name: String): IntArray

  /** Reads metatile attributes. */
  fun metatileAttributes(name: String): LongArray

  /** One behavior ordinal per metatile for a tileset. */
  fun behaviorOrdinals(name: String): IntArray

  /** Reads tile pixel indices. */
  fun tilePixels(name: String, tileId: Int): ByteArray

  /** ARGB colors of one 16-color sub-palette of a tileset. */
  fun paletteColors(name: String, paletteId: Int): IntArray
}

/** Adds region metadata to a source. */
interface RegionSource : BaseSource {
  val region: RegionConfig
}
