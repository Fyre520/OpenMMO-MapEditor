package de.lananahwp.openmmo.mapeditor.model

import de.lananahwp.openmmo.mapeditor.core.NdsTri

/** Stable IDs of the authentic HGSS grass pieces generated into the project tile library. */
object NdsGrassField {
  const val INTERIOR = 1005
  const val EDGE_NORTH = 1009
  const val EDGE_EAST = 1011
  const val EDGE_SOUTH = 1015
  const val EDGE_WEST = 1020
  const val CORNER_NW = 1031
  const val CORNER_NE = 1039
  val COMPONENTS = setOf(INTERIOR, EDGE_NORTH, EDGE_EAST, EDGE_SOUTH, EDGE_WEST, CORNER_NW, CORNER_NE)

  data class Cell(val x: Int, val z: Int, val layer: Int, val height: Double)
  data class Fringe(val tile: Int, val x: Int, val z: Int, val sourceLayer: Int, val sourceHeight: Double, val turns: Int = 0)

  fun cells(grid: NdsGrid): Map<Pair<Int, Int>, Cell> = buildMap {
    for (layer in 0 until NdsGrid.LAYERS) for (x in 0 until grid.cols) for (z in 0 until grid.rows) {
      if (grid.tileAt(layer, x, z) == INTERIOR) put(x to z, Cell(x, z, layer, grid.heightAt(layer, x, z)))
    }
  }

  /**
   * Builds the transparent fringe in the empty cells around a field.  The field itself is saved
   * only as interior cells; this outline is regenerated after every edit, undo, load and export.
   */
  fun fringes(grid: NdsGrid): List<Fringe> {
    val grass = cells(grid)
    if (grass.isEmpty()) return emptyList()
    val out = ArrayList<Fringe>()
    for (z in 0 until grid.rows) for (x in 0 until grid.cols) {
      if ((x to z) in grass) continue
      fun source(dx: Int, dz: Int) = grass[(x + dx) to (z + dz)]
      fun add(tile: Int, cell: Cell?, turns: Int = 0) {
        if (cell != null) out += Fringe(tile, x, z, cell.layer, cell.height, turns)
      }
      // Each edge occupies the ground cell immediately outside the grass.
      add(EDGE_NORTH, source(0, 1))
      add(EDGE_EAST, source(-1, 0))
      add(EDGE_SOUTH, source(0, -1))
      add(EDGE_WEST, source(1, 0))
      // Outer corners fill a diagonal only when neither adjoining edge already owns the shape.
      if (source(0, 1) == null && source(1, 0) == null) add(CORNER_NW, source(1, 1))
      if (source(0, 1) == null && source(-1, 0) == null) add(CORNER_NE, source(-1, 1))
      if (source(0, -1) == null && source(-1, 0) == null) add(CORNER_NW, source(-1, -1), 2)
      if (source(0, -1) == null && source(1, 0) == null) add(CORNER_NE, source(1, -1), 2)
    }
    return out
  }

  fun rotated(triangles: List<NdsTri>, turns: Int): List<NdsTri> {
    if ((turns and 3) == 0) return triangles
    fun point(x: Float, z: Float): Pair<Float, Float> {
      var px = x
      var pz = z
      repeat(turns and 3) { val nx = 1f - pz; pz = px; px = nx }
      return px to pz
    }
    return triangles.map { tri ->
      val a = point(tri.ax, tri.az); val b = point(tri.bx, tri.bz); val c = point(tri.cx, tri.cz)
      tri.copy(ax = a.first, az = a.second, bx = b.first, bz = b.second, cx = c.first, cz = c.second)
    }
  }

  fun triangles(
      grid: NdsGrid,
      geometry: Map<Int, List<NdsTri>>,
      baseHeight: (Fringe) -> Float,
  ): List<NdsTri> = buildList {
    for (fringe in fringes(grid)) {
      val base = baseHeight(fringe)
      for (tri in rotated(geometry[fringe.tile].orEmpty(), fringe.turns)) {
        add(tri.copy(
            ax = fringe.x + tri.ax, ay = base + tri.ay, az = fringe.z + tri.az,
            bx = fringe.x + tri.bx, by = base + tri.by, bz = fringe.z + tri.bz,
            cx = fringe.x + tri.cx, cy = base + tri.cy, cz = fringe.z + tri.cz))
      }
    }
  }
}
