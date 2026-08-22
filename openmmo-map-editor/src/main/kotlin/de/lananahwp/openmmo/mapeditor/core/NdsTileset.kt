package de.lananahwp.openmmo.mapeditor.core

import java.awt.Color

/** How a DS tile's 3D geometry is generated. */
enum class TileShape {
  /** A flat 1x1 quad on the ground. */
  FLAT,

  /** A 1x1 block extruded to its tile height. */
  CUBE,

  /** A cube with a distinct top (e.g. trees, buildings). */
  BLOCK,
}

/** A reusable map tile used by the 3D editor (PDSMS-style abstraction). */
data class NdsTileDef(
    val name: String,
    val topColor: Color,
    val sideColor: Color = topColor.darker(),
    val shape: TileShape = TileShape.FLAT,
    val height: Int = 1,
)

/** Built-in palette of editable DS tiles. */
object NdsTileset {
  val tiles: List<NdsTileDef> =
      listOf(
          NdsTileDef("Grass", Color(72, 152, 60)),
          NdsTileDef("Light Grass", Color(108, 178, 78)),
          NdsTileDef("Dark Grass", Color(46, 118, 44)),
          NdsTileDef("Flowers", Color(196, 150, 70), Color(140, 100, 50), TileShape.FLAT),
          NdsTileDef("Path", Color(188, 170, 132), Color(150, 130, 100), TileShape.FLAT),
          NdsTileDef("Sand", Color(230, 210, 150), Color(180, 160, 110), TileShape.FLAT),
          NdsTileDef("Water", Color(58, 120, 196), Color(40, 84, 140), TileShape.FLAT),
          NdsTileDef("Deep Water", Color(30, 78, 150), Color(22, 54, 108), TileShape.FLAT),
          NdsTileDef("Wall", Color(150, 150, 155), Color(96, 96, 100), TileShape.CUBE, 3),
          NdsTileDef("Tree", Color(46, 130, 60), Color(90, 70, 40), TileShape.BLOCK, 2),
          NdsTileDef("Tall Grass", Color(70, 140, 70), Color(50, 100, 50), TileShape.BLOCK, 1),
          NdsTileDef("Roof", Color(150, 60, 60), Color(110, 44, 44), TileShape.BLOCK, 2),
          NdsTileDef("Floor", Color(200, 190, 170), Color(150, 140, 120), TileShape.FLAT),
          NdsTileDef("Rock", Color(130, 130, 135), Color(88, 88, 92), TileShape.BLOCK, 1),
      )

  fun color(index: Int): Color = tiles.getOrNull(index)?.topColor ?: Color(30, 30, 30)

  /**
   * First index reserved for project-defined tiles lifted off a map surface.
   *
   * Grids persist the tile *index*, so the built-in list and the custom list must never share a
   * number. Starting custom tiles well past the end of [tiles] means new built-ins can be added
   * later without silently repainting every saved map that used a custom tile.
   */
  const val CUSTOM_TILE_BASE = 1000

  fun isCustom(index: Int): Boolean = index >= CUSTOM_TILE_BASE
}
