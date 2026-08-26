package de.lananahwp.openmmo.mapeditor.ui

import de.lananahwp.openmmo.mapeditor.core.NdsTexture
import de.lananahwp.openmmo.mapeditor.core.NdsTri
import de.lananahwp.openmmo.mapeditor.core.NdsTileset
import de.lananahwp.openmmo.mapeditor.core.TileShape
import de.lananahwp.openmmo.mapeditor.model.NdsGrid
import java.awt.Component
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** What is under a viewport pointer: the ground-grid cell and, when applicable, a 3D model group. */
data class NdsPointerHit(
    val cellX: Int?,
    val cellZ: Int?,
    /** Closest editable model surface, either `prop:*` or a baked-terrain group. */
    val modelGroup: String? = null,
    /** Continuous map-space ground position; retained even outside the grid for uninterrupted dragging. */
    val groundX: Float? = null,
    val groundZ: Float? = null,
    /**
     * Where the pointer actually met the mesh, in map-tile space.
     *
     * [groundX]/[groundZ] project the pointer onto the y=0 ground plane, which is only the same
     * place when the terrain also sits at y=0. Maps whose model floats above the grid (their lowest
     * geometry, not their walkable floor, is what gets pinned to y=0) put the two several tiles
     * apart, so anything picking geometry must use these instead.
     */
    val surfaceX: Float? = null,
    val surfaceY: Float? = null,
    val surfaceZ: Float? = null,
    /** Texture name of the picked triangle, for "same texture only" surface selection. */
    val surfaceTexture: String? = null,
    val shiftDown: Boolean = false,
    val ctrlDown: Boolean = false,
)

/**
 * The top of [triangles] over each grid square, in world units, or NaN where nothing covers it.
 *
 * Fed the map's terrain alone rather than everything drawn: paint belongs to a square's ground,
 * so a prop standing on that square must not lift it. Terrain is coarse (one quad can span many
 * squares), so every square a triangle covers takes that triangle's height, not just the square
 * holding its centroid — otherwise a large quad would leave every square but one without a
 * surface. [groundY] and [scale] are the view transform's, so the result is directly comparable
 * with the geometry as drawn.
 */
internal fun ndsTileSurfaceHeights(
    triangles: List<NdsTri>,
    cols: Int,
    rows: Int,
    groundY: Float,
    scale: Float,
): Array<DoubleArray> {
  // NaN marks "no terrain here", so a square at world height 0 stays distinguishable from empty.
  val out = Array(cols) { DoubleArray(rows) { Double.NaN } }
  for (tri in triangles) {
    val minX = floor(minOf(tri.ax, tri.bx, tri.cx).toDouble()).toInt()
    val maxX = floor(maxOf(tri.ax, tri.bx, tri.cx).toDouble()).toInt()
    val minZ = floor(minOf(tri.az, tri.bz, tri.cz).toDouble()).toInt()
    val maxZ = floor(maxOf(tri.az, tri.bz, tri.cz).toDouble()).toInt()
    val worldTop = (maxOf(tri.ay, tri.by, tri.cy) - groundY).toDouble() * scale
    for (x in maxOf(0, minX)..minOf(cols - 1, maxX)) {
      for (z in maxOf(0, minZ)..minOf(rows - 1, maxZ)) {
        val current = out[x][z]
        if (current.isNaN() || worldTop > current) out[x][z] = worldTop
      }
    }
  }
  return out
}

/** A picked model triangle plus the map-tile-space point where the pointer met it. */
internal class NdsSurfaceHit(
    val triangle: NdsTri,
    val x: Float,
    val y: Float,
    val z: Float,
)

internal data class NdsPickRay(val origin: DoubleArray, val direction: DoubleArray)

/**
 * Vertical field of view, in degrees, shared by the 3D views.
 *
 * The projection the view renders with and the ray it picks with must use the same value, or the
 * cursor resolves to a different place than the one drawn under it.
 */
internal const val NDS_FIELD_OF_VIEW = 45.0

/**
 * Builds a world-space ray through a viewport pixel from the orbit camera's own parameters.
 *
 * Deliberately independent of any GL state: mouse events arrive on the AWT event dispatch thread,
 * where a GLCanvas's context is not current, so reading back the modelview/projection matrices
 * there yields nothing usable. Deriving the ray from yaw/pitch/distance works on any thread and
 * keeps both views picking identically.
 */
internal fun ndsPickRay(
    width: Int,
    height: Int,
    yaw: Double,
    pitch: Double,
    distance: Double,
    centerX: Double,
    centerZ: Double,
    mouseX: Int,
    mouseY: Int,
): NdsPickRay? {
  if (width <= 0 || height <= 0) return null
  val radYaw = Math.toRadians(yaw)
  val radPitch = Math.toRadians(pitch)
  val eye = doubleArrayOf(
      centerX + distance * cos(radPitch) * sin(radYaw),
      distance * sin(radPitch),
      centerZ - distance * cos(radPitch) * cos(radYaw),
  )
  val forward = normalize3(doubleArrayOf(centerX - eye[0], -eye[1], centerZ - eye[2]))
  val right = normalize3(cross3(forward, doubleArrayOf(0.0, 1.0, 0.0)))
  val up = cross3(right, forward)

  val aspect = width.toDouble() / height.coerceAtLeast(1)
  val focal = 1.0 / tan(Math.toRadians(NDS_FIELD_OF_VIEW) / 2.0)
  val sx = (mouseX.toDouble() / width - 0.5) * 2.0
  val sy = (0.5 - mouseY.toDouble() / height) * 2.0
  val vx = sx * aspect / focal
  val vy = sy / focal

  // View z grows away from the camera, so the ray runs from the near plane out to the far plane.
  fun world(x: Double, y: Double, z: Double) = doubleArrayOf(
      eye[0] + x * right[0] + y * up[0] + z * forward[0],
      eye[1] + x * right[1] + y * up[1] + z * forward[1],
      eye[2] + x * right[2] + y * up[2] + z * forward[2],
  )
  val near = world(vx, vy, 1.0)
  val far = world(vx * 1000.0, vy * 1000.0, 1000.0)
  return NdsPickRay(
      near,
      doubleArrayOf(far[0] - near[0], far[1] - near[1], far[2] - near[2]),
  )
}

/**
 * The outline of a set of triangles: only edges belonging to exactly one of them.
 *
 * Stroking every triangle draws the triangulation itself — each square split by the diagonal
 * between its two triangles, and a line along every seam between neighbouring squares — which
 * reads as a lattice rather than a selection. Edges shared by two triangles are interior, so
 * dropping them leaves the silhouette of the whole selected region.
 *
 * Endpoints are quantised before comparison so that two triangles meeting at a shared corner agree
 * on it despite floating-point drift.
 */
internal fun ndsOutlineEdges(triangles: List<NdsTri>): List<FloatArray> {
  if (triangles.isEmpty()) return emptyList()
  fun key(x: Float, y: Float, z: Float): Long {
    val qx = Math.round(x * 256.0).toLong() and 0x1FFFFF
    val qy = Math.round(y * 256.0).toLong() and 0x1FFFFF
    val qz = Math.round(z * 256.0).toLong() and 0x1FFFFF
    return (qx shl 42) or (qy shl 21) or qz
  }
  val counts = HashMap<Pair<Long, Long>, Int>(triangles.size * 3)
  val coords = HashMap<Pair<Long, Long>, FloatArray>(triangles.size * 3)
  fun edge(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float) {
    val ka = key(ax, ay, az)
    val kb = key(bx, by, bz)
    if (ka == kb) return
    val id = if (ka <= kb) ka to kb else kb to ka
    counts[id] = (counts[id] ?: 0) + 1
    coords.getOrPut(id) { floatArrayOf(ax, ay, az, bx, by, bz) }
  }
  for (t in triangles) {
    edge(t.ax, t.ay, t.az, t.bx, t.by, t.bz)
    edge(t.bx, t.by, t.bz, t.cx, t.cy, t.cz)
    edge(t.cx, t.cy, t.cz, t.ax, t.ay, t.az)
  }
  return counts.entries.filter { it.value == 1 }.mapNotNull { coords[it.key] }
}

internal data class NdsScreenPickView(
    val width: Int,
    val height: Int,
    val yaw: Double,
    val pitch: Double,
    val distance: Double,
    val centerX: Double,
    val centerZ: Double,
    val modelScale: Float,
    val modelCenterX: Float,
    val modelCenterZ: Float,
    val modelGroundY: Float,
)

/** Picks the visible projected triangle rather than projecting the pointer onto the ground. */
internal fun pickNdsModelGroupAtScreen(
    triangles: List<NdsTri>,
    mouseX: Int,
    mouseY: Int,
    view: NdsScreenPickView,
): String? {
  val projector = NdsScreenProjector(view) ?: return null
  var closestDepth = Double.POSITIVE_INFINITY
  var selected: String? = null
  for (tri in triangles) {
    if (tri.editGroup.isEmpty()) continue
    val a = projector.project(tri.ax, tri.ay, tri.az) ?: continue
    val b = projector.project(tri.bx, tri.by, tri.bz) ?: continue
    val c = projector.project(tri.cx, tri.cy, tri.cz) ?: continue
    val denominator = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
    if (abs(denominator) < 1e-7) continue
    val px = mouseX.toDouble()
    val py = mouseY.toDouble()
    val u = ((b[1] - c[1]) * (px - c[0]) + (c[0] - b[0]) * (py - c[1])) / denominator
    val v = ((c[1] - a[1]) * (px - c[0]) + (a[0] - c[0]) * (py - c[1])) / denominator
    val w = 1.0 - u - v
    if (u < -0.002 || v < -0.002 || w < -0.002) continue
    val depth = u * a[2] + v * b[2] + w * c[2]
    if (depth < closestDepth) {
      closestDepth = depth
      selected = tri.editGroup
    }
  }
  return selected
}

/**
 * Picks the frontmost triangle under the pointer and reports where on it the pointer landed.
 *
 * A separate path from [pickNdsModelGroupAtScreen], which the established paint and object modes
 * use and which is deliberately left untouched. This one additionally keeps the intersection
 * point, which is what lets surface picking work out which map tile was clicked on the *mesh* —
 * independent of where the same pointer would land on the y=0 ground plane.
 */
internal fun pickNdsModelSurfaceAtScreen(
    triangles: List<NdsTri>,
    mouseX: Int,
    mouseY: Int,
    view: NdsScreenPickView,
): NdsSurfaceHit? {
  val projector = NdsScreenProjector(view) ?: return null
  var closestDepth = Double.POSITIVE_INFINITY
  var selected: NdsSurfaceHit? = null
  for (tri in triangles) {
    if (tri.editGroup.isEmpty()) continue
    val a = projector.project(tri.ax, tri.ay, tri.az) ?: continue
    val b = projector.project(tri.bx, tri.by, tri.bz) ?: continue
    val c = projector.project(tri.cx, tri.cy, tri.cz) ?: continue
    val denominator = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
    if (abs(denominator) < 1e-7) continue
    val px = mouseX.toDouble()
    val py = mouseY.toDouble()
    val u = ((b[1] - c[1]) * (px - c[0]) + (c[0] - b[0]) * (py - c[1])) / denominator
    val v = ((c[1] - a[1]) * (px - c[0]) + (a[0] - c[0]) * (py - c[1])) / denominator
    val w = 1.0 - u - v
    if (u < -0.002 || v < -0.002 || w < -0.002) continue
    val depth = u * a[2] + v * b[2] + w * c[2]
    if (depth >= closestDepth) continue
    closestDepth = depth
    // Screen-space barycentrics are not linear in model space; dividing each by its vertex depth
    // and renormalizing recovers the perspective-correct weights, so the reported point stays on
    // the right tile even on large triangles seen at a grazing angle.
    val ua = u / a[2]
    val vb = v / b[2]
    val wc = w / c[2]
    val sum = ua + vb + wc
    if (abs(sum) < 1e-12) continue
    val fa = (ua / sum).toFloat()
    val fb = (vb / sum).toFloat()
    val fc = (wc / sum).toFloat()
    selected = NdsSurfaceHit(
        tri,
        tri.ax * fa + tri.bx * fb + tri.cx * fc,
        tri.ay * fa + tri.by * fb + tri.cy * fc,
        tri.az * fa + tri.bz * fb + tri.cz * fc,
    )
  }
  return selected
}

internal fun projectNdsPoint(
    view: NdsScreenPickView,
    x: Float,
    y: Float,
    z: Float,
): DoubleArray? {
  return NdsScreenProjector(view)?.project(x, y, z)
}

private class NdsScreenProjector private constructor(private val view: NdsScreenPickView) {
  private val eye: DoubleArray
  private val forward: DoubleArray
  private val right: DoubleArray
  private val up: DoubleArray
  private val aspect: Double
  private val focal = 1.0 / tan(Math.toRadians(45.0) / 2.0)

  init {
    require(view.width > 0 && view.height > 0)
    val yaw = Math.toRadians(view.yaw)
    val pitch = Math.toRadians(view.pitch)
    eye = doubleArrayOf(
        view.centerX + view.distance * cos(pitch) * sin(yaw),
        view.distance * sin(pitch),
        view.centerZ - view.distance * cos(pitch) * cos(yaw),
    )
    forward = normalize3(doubleArrayOf(view.centerX - eye[0], -eye[1], view.centerZ - eye[2]))
    right = normalize3(cross3(forward, doubleArrayOf(0.0, 1.0, 0.0)))
    up = cross3(right, forward)
    aspect = view.width.toDouble() / view.height
  }

  fun project(x: Float, y: Float, z: Float): DoubleArray? {
    val worldX = 16.0 + (x - view.modelCenterX) * view.modelScale
    val worldY = ((y - view.modelGroundY) * view.modelScale).toDouble()
    val worldZ = 16.0 + (z - view.modelCenterZ) * view.modelScale
    val relative = doubleArrayOf(worldX - eye[0], worldY - eye[1], worldZ - eye[2])
    val vx = dot3(relative, right)
    val vy = dot3(relative, up)
    val depth = dot3(relative, forward)
    if (depth < 1.0 || depth > 1000.0) return null
    val sx = (((focal / aspect) * vx / depth) * 0.5 + 0.5) * view.width
    val sy = (0.5 - (focal * vy / depth) * 0.5) * view.height
    return doubleArrayOf(sx, sy, depth)
  }

  companion object {
    operator fun invoke(view: NdsScreenPickView): NdsScreenProjector? =
        if (view.width <= 0 || view.height <= 0) null else NdsScreenProjector(view)
  }
}

/** Returns the closest editable model group intersected by [ray]. */
internal fun pickNdsModelGroup(
    triangles: List<NdsTri>,
    ray: NdsPickRay,
    groupPrefix: String,
    excludedPrefix: String? = null,
): String? {
  var closest = Double.POSITIVE_INFINITY
  var selected: String? = null
  for (tri in triangles) {
    if (!tri.editGroup.startsWith(groupPrefix) ||
        (excludedPrefix != null && tri.editGroup.startsWith(excludedPrefix))) continue
    val edge1 = doubleArrayOf(
        (tri.bx - tri.ax).toDouble(),
        (tri.by - tri.ay).toDouble(),
        (tri.bz - tri.az).toDouble(),
    )
    val edge2 = doubleArrayOf(
        (tri.cx - tri.ax).toDouble(),
        (tri.cy - tri.ay).toDouble(),
        (tri.cz - tri.az).toDouble(),
    )
    val p = cross3(ray.direction, edge2)
    val determinant = dot3(edge1, p)
    if (abs(determinant) < 1e-9) continue
    val inverse = 1.0 / determinant
    val fromA = doubleArrayOf(
        ray.origin[0] - tri.ax,
        ray.origin[1] - tri.ay,
        ray.origin[2] - tri.az,
    )
    val u = dot3(fromA, p) * inverse
    if (u < 0.0 || u > 1.0) continue
    val q = cross3(fromA, edge1)
    val v = dot3(ray.direction, q) * inverse
    if (v < 0.0 || u + v > 1.0) continue
    val distance = dot3(edge2, q) * inverse
    if (distance > 1e-7 && distance < closest) {
      closest = distance
      selected = tri.editGroup
    }
  }
  return selected
}

private fun cross3(a: DoubleArray, b: DoubleArray): DoubleArray =
    doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

private fun dot3(a: DoubleArray, b: DoubleArray): Double =
    a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

private fun normalize3(v: DoubleArray): DoubleArray {
  val length = sqrt(dot3(v, v))
  return if (length == 0.0) doubleArrayOf(0.0, 0.0, 0.0)
  else doubleArrayOf(v[0] / length, v[1] / length, v[2] / length)
}

/**
 * Common surface for the DS 3D map views (OpenGL and software fallback).
 * Paint modes: 0 = tile, 1 = collision, 2 = permission, 3 = elevation.
 */
interface Nds3DView {
  var grid: NdsGrid?
  var modelTriangles: List<NdsTri>

  /**
   * The geometry painted tiles rest on: the map's own terrain, without the props placed on it.
   *
   * Kept apart from [modelTriangles] -- which is everything drawn, terrain and props together --
   * because paint belongs to the ground of a square. Resolving a tile's height against every
   * triangle stood it on the roof of whatever prop happened to be there, so moving a prop over
   * painted ground made the paint climb the prop instead of disappearing under it.
   */
  var surfaceTriangles: List<NdsTri>
  var modelTextures: Map<String, NdsTexture>
  var modelPalettes: Map<String, IntArray>
  var modelOpacity: Float
  var activeLayer: Int
  var activeTile: Int
  var activeHeight: Int
  /** Width and depth, in map squares, of the paint cursor and one paint operation. */
  var brushSize: Int
  var brushCollision: Int
  var showGrid: Boolean
  var showCollision: Boolean
  var markers: List<NdsEventMarker>

  /**
   * Triangles drawn tinted and outlined on top of the model to show what is currently selected.
   * These are copies of geometry already in [modelTriangles]; they are not part of the map.
   */
  var highlightTriangles: List<NdsTri>

  /**
   * Whether left-drag should keep resolving the mesh surface under the pointer.
   *
   * Off by default: picking tests every model triangle, which is wasted work for the paint modes
   * that only need a grid cell. Surface selection turns it on so a drag can paint across geometry.
   */
  var surfacePicking: Boolean

  /**
   * Geometry for project-defined tiles, keyed by tile index, in unit-square tile space.
   *
   * Built-in tiles are flat colours the view generates itself; these carry real map surface, so
   * the view has to be handed the triangles. Their textures arrive through [modelTextures] under
   * the same names the triangles reference.
   */
  var customTileGeometry: Map<Int, List<NdsTri>>
  fun setPaintMode(mode: Int)

  fun asComponent(): Component
}

/** Cells covered by a square brush, matching NdsProject.surfaceBrushCells' centering rule. */
internal fun ndsBrushFootprint(
    x: Int,
    z: Int,
    size: Int,
    cols: Int,
    rows: Int,
): List<Pair<Int, Int>> {
  val span = size.coerceAtLeast(1)
  val originX = x - (span - 1) / 2
  val originZ = z - (span - 1) / 2
  return buildList(span * span) {
    for (dz in 0 until span) for (dx in 0 until span) {
      val cellX = originX + dx
      val cellZ = originZ + dz
      if (cellX in 0 until cols && cellZ in 0 until rows) add(cellX to cellZ)
    }
  }
}

/** Render-space top of the tile being edited in one cell. */
internal fun ndsPaintCursorHeight(
    grid: NdsGrid,
    x: Int,
    z: Int,
    activeLayer: Int,
    terrainHeight: Double,
    modelScale: Float,
    customGeometry: Map<Int, List<NdsTri>>,
): Double {
  fun layerTop(layer: Int): Double {
    val base = terrainHeight + grid.heightAt(layer, x, z)
    val tile = grid.tileAt(layer, x, z)
    if (NdsTileset.isCustom(tile)) {
      val meshTop = customGeometry[tile].orEmpty()
          .maxOfOrNull { maxOf(it.ay, it.by, it.cy) } ?: 0f
      return base + meshTop * modelScale
    }
    val definition = NdsTileset.tiles.getOrNull(tile)
    return base + when (definition?.shape) {
      TileShape.CUBE, TileShape.BLOCK -> definition.height.toDouble()
      else -> 0.0
    }
  }

  // The edit still targets activeLayer, but the cursor must remain visible over any higher layer
  // already occupying this square. Otherwise its quad intersects the visible tile above it.
  var top = layerTop(activeLayer.coerceIn(0, NdsGrid.LAYERS - 1))
  for (layer in 0 until NdsGrid.LAYERS) {
    if (grid.tileAt(layer, x, z) >= 0) top = maxOf(top, layerTop(layer))
  }
  return top
}
