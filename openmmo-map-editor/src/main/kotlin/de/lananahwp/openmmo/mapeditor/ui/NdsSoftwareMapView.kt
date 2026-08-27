package de.lananahwp.openmmo.mapeditor.ui

import de.lananahwp.openmmo.mapeditor.core.NdsTileset
import de.lananahwp.openmmo.mapeditor.model.NdsGrid
import de.lananahwp.openmmo.mapeditor.model.NdsGrassField
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Event marker rendered over a DS map grid. */
data class NdsEventMarker(val x: Int, val z: Int, val label: String, val color: Color)

/**
 * Software-rendered 3D view of a DS map grid (no OpenGL required).
 *
 * Interaction mirrors a GL editor: left-click paints the active tile onto the active layer,
 * shift + drag orbits the camera, right drag pans, and the mouse wheel zooms.
 */
class NdsSoftwareMapView(
    private val onPaintCell: (x: Int, z: Int) -> Unit,
    private val onPaintCollision: (x: Int, z: Int, value: Int) -> Unit,
    private val onCellInteraction: (hit: NdsPointerHit, dragging: Boolean) -> Boolean = { _, _ -> false },
    /** Ctrl+click/drag in a paint mode: clear this cell rather than paint the active brush into it. */
    private val onEraseCell: (x: Int, z: Int) -> Unit = { _, _ -> },
    /**
     * A paint gesture is starting (press, before the first cell is painted).
     *
     * Lets the editor group everything a press-drag-release paints into one undo step, which a
     * per-cell callback cannot do: it never learns where one stroke ends and the next begins.
     */
    private val onStrokeBegin: () -> Unit = {},
    /** Reports the map square under the pointer, or null when the pointer leaves the map. */
    private val onHoverCell: (Pair<Int, Int>?) -> Unit = {},
    /** Reports the final pointer position after a left-button interaction ends. */
    private val onCellInteractionEnd: (hit: NdsPointerHit?) -> Unit = {},
) : JPanel(), Nds3DView {

  enum class PaintMode { TILE, COLLISION, PERMISSION, ELEVATION, WALK_SURFACE, NONE }

  override fun setPaintMode(mode: Int) {
    paintMode =
        when (mode) {
          0 -> PaintMode.TILE
          1 -> PaintMode.COLLISION
          2 -> PaintMode.PERMISSION
          3 -> PaintMode.ELEVATION
          8 -> PaintMode.WALK_SURFACE
          else -> PaintMode.NONE
        }
  }

  override fun asComponent(): JPanel = this

  override var grid: NdsGrid? = null
    set(value) {
      field = value
      if (value != null) {
        // The view transform maps the footprint center to 16 and fits the map to ~30 units.
        centerX = 16.0
        centerZ = 16.0
        distance = 46.0
      }
      repaint()
    }

  override var activeLayer = 0
  override var activeTile = 0
  override var activeTileWidth = 1
  override var activeTileHeight = 1
  override var activeHeight = 0.0
  override var brushSize = 1
    set(value) {
      field = value.coerceAtLeast(1)
      repaint()
    }
  override var brushCollision = 1
  override var showGrid = true
  override var showCollision = false
  var paintMode: PaintMode = PaintMode.TILE
  override var markers: List<NdsEventMarker> = emptyList()

  override var highlightTriangles: List<de.lananahwp.openmmo.mapeditor.core.NdsTri> = emptyList()
    set(value) {
      field = value
      highlightOutline = ndsOutlineEdges(value)
      repaint()
    }

  private var highlightOutline: List<FloatArray> = emptyList()

  override var walkSurfaceTriangles: List<de.lananahwp.openmmo.mapeditor.core.NdsTri> = emptyList()
    set(value) {
      field = value
      walkSurfaceOutline = value.groupBy { it.editGroup }.values.flatMap(::ndsOutlineEdges)
      repaint()
    }

  private var walkSurfaceOutline: List<FloatArray> = emptyList()

  override var walkSurfaceHandles: List<NdsWalkHandle> = emptyList()
    set(value) {
      field = value
      repaint()
    }

  override var surfacePicking: Boolean = false

  override fun projectMapPoint(x: Float, y: Float, z: Float): DoubleArray? =
      screenPickView()?.let { projectNdsPoint(it, x, y, z) }

  override var customTileGeometry: Map<Int, List<de.lananahwp.openmmo.mapeditor.core.NdsTri>> = emptyMap()
    set(value) {
      field = value
      repaint()
    }
  override var customTileOverlays: Set<Int> = emptySet()
    set(value) {
      field = value
      repaint()
    }

  /**
   * Painted project-defined tiles, moved into their cells so the textured pass can draw them
   * alongside the map's own geometry.
   *
   * Like the flat tiles this view already draws, they sit at grid height rather than following the
   * map surface; the OpenGL view resolves the surface height per square, this fallback does not.
   */
  private fun placedCustomTileTriangles(): List<de.lananahwp.openmmo.mapeditor.core.NdsTri> {
    val g = grid ?: return emptyList()
    if (customTileGeometry.isEmpty()) return emptyList()
    val out = ArrayList<de.lananahwp.openmmo.mapeditor.core.NdsTri>()
    for (layer in 0 until NdsGrid.LAYERS) {
      for (x in 0 until g.cols) for (z in 0 until g.rows) {
        val tile = g.tileAt(layer, x, z)
        if (tile < 0 || !de.lananahwp.openmmo.mapeditor.core.NdsTileset.isCustom(tile)) continue
        val geometry = customTileGeometry[tile] ?: continue
        val overlayLift = if (tile in customTileOverlays) NdsGrid.OVERLAY_LIFT else 0f
        val base = g.heightAt(layer, x, z).toFloat() + overlayLift
        for (t in geometry) {
          out += t.copy(
              ax = x + t.ax, ay = base + t.ay, az = z + t.az,
              bx = x + t.bx, by = base + t.by, bz = z + t.bz,
              cx = x + t.cx, cy = base + t.cy, cz = z + t.cz,
          )
        }
      }
    }
    out += NdsGrassField.triangles(g, customTileGeometry) { fringe ->
      fringe.sourceHeight.toFloat() + NdsGrid.OVERLAY_LIFT
    }
    return out
  }

  /** Decoded NSBMD model triangles rendered over the grid. */
  override var modelTriangles: List<de.lananahwp.openmmo.mapeditor.core.NdsTri> = emptyList()
    set(value) {
      field = value
      repaint()
    }

  /**
   * Terrain without the props on it. Carried for the interface only: this view draws painted
   * tiles at grid height rather than following the map surface, so it never measures against it.
   */
  override var surfaceTriangles: List<de.lananahwp.openmmo.mapeditor.core.NdsTri> = emptyList()

  /** Textures (by name) used by the model triangles. */
  override var modelTextures: Map<String, de.lananahwp.openmmo.mapeditor.core.NdsTexture> = emptyMap()
  override var modelPalettes: Map<String, IntArray> = emptyMap()
    set(value) {
      field = value
      repaint()
    }
  override var modelOpacity: Float = 1f
    set(value) {
      field = value.coerceIn(0f, 1f)
      repaint()
    }
  override var propOpacity: Float = 1f
    set(value) {
      field = value.coerceIn(0f, 1f)
      repaint()
    }

  var yaw = 45.0
  var pitch = 30.0
  var distance = 46.0
  var centerX = 16.0
  var centerZ = 16.0

  private var hoverCell: Pair<Int, Int>? = null
  private var lastX = 0
  private var lastY = 0

  init {
    background = Color(30, 33, 40)
    preferredSize = Dimension(800, 600)
    addMouseListener(
        object : MouseAdapter() {
          override fun mousePressed(e: MouseEvent) {
            lastX = e.x
            lastY = e.y
            if (e.button == MouseEvent.BUTTON1) {
              val hit =
                  if (surfacePicking) surfacePointerHit(e.x, e.y, e.isShiftDown, e.isControlDown)
                  else pointerHit(e.x, e.y, includeModelGroup = true)?.copy(
                      shiftDown = e.isShiftDown,
                      ctrlDown = e.isControlDown,
                  )
              if (hit == null) return
              if (!onCellInteraction(hit, false) && hit.cellX != null && hit.cellZ != null) {
                onStrokeBegin()
                paint(hit.cellX, hit.cellZ, e.isControlDown)
              }
            }
          }

          override fun mouseReleased(e: MouseEvent) {
            if (e.button == MouseEvent.BUTTON1) {
              onCellInteractionEnd(
                  pointerHit(e.x, e.y, includeModelGroup = true)?.copy(
                      shiftDown = e.isShiftDown,
                      ctrlDown = e.isControlDown,
                  ))
            }
            cursor = Cursor.getDefaultCursor()
          }

          override fun mouseExited(e: MouseEvent) {
            setHoverCell(null)
          }
        })
    addMouseMotionListener(
        object : MouseAdapter() {
          override fun mouseDragged(e: MouseEvent) {
            val dx = e.x - lastX
            val dy = e.y - lastY
            lastX = e.x
            lastY = e.y
            if (SwingUtilities.isMiddleMouseButton(e)) {
              // Middle-drag orbits the camera.
              yaw += dx * 0.6
              pitch = (pitch - dy * 0.6).coerceIn(5.0, 85.0)
              repaint()
            } else if (SwingUtilities.isRightMouseButton(e)) {
              // Pan in screen/viewport space (corrected for the map's yaw rotation).
              val s = distance / 800.0
              val cosYaw = cos(Math.toRadians(yaw))
              val sinYaw = sin(Math.toRadians(yaw))
              centerX += (dx * -cosYaw + dy * sinYaw) * s
              centerZ += (dx * -sinYaw + dy * -cosYaw) * s
              repaint()
            } else if (SwingUtilities.isLeftMouseButton(e)) {
              // Surface picking keeps resolving the mesh while dragging, because painting a
              // selection across it needs the tile under the geometry, not under the ground plane.
              val hit =
                  if (surfacePicking) surfacePointerHit(e.x, e.y, e.isShiftDown, e.isControlDown)
                  else pointerHit(e.x, e.y, includeModelGroup = false)
              if (hit != null &&
                  !onCellInteraction(hit, true) && hit.cellX != null && hit.cellZ != null) {
                paint(hit.cellX, hit.cellZ, e.isControlDown)
              }
            }
            updateHoverCell(e.x, e.y)
          }

          override fun mouseMoved(e: MouseEvent) {
            updateHoverCell(e.x, e.y)
          }
        })
    addMouseWheelListener { e: MouseWheelEvent ->
      distance = (distance * (if (e.wheelRotation < 0) 0.9 else 1.1)).coerceIn(8.0, 220.0)
      repaint()
    }
  }

  private fun updateHoverCell(screenX: Int, screenY: Int) {
    setHoverCell(pickCell(screenX, screenY))
  }

  private fun setHoverCell(cell: Pair<Int, Int>?) {
    if (cell == hoverCell) return
    hoverCell = cell
    onHoverCell(cell)
    repaint()
  }

  private fun paint(x: Int, z: Int, erase: Boolean) {
    when (paintMode) {
      // Ctrl is the erase modifier for the two modes that write a cell's own contents: it takes
      // the tile back out of the square, or drops its height back to the map floor.
      PaintMode.TILE -> if (erase) onEraseCell(x, z) else onPaintCell(x, z)
      PaintMode.COLLISION -> onPaintCollision(x, z, brushCollision)
      PaintMode.PERMISSION -> onPaintCollision(x, z, brushCollision)
      PaintMode.ELEVATION -> if (erase) onEraseCell(x, z) else onPaintCell(x, z)
      PaintMode.WALK_SURFACE -> Unit
      PaintMode.NONE -> Unit
    }
  }

  // ---- Rendering -----------------------------------------------------------

  private data class Camera(val right: DoubleArray, val up: DoubleArray, val forward: DoubleArray)

  private fun camera(): Camera {
    val radYaw = Math.toRadians(yaw)
    val radPitch = Math.toRadians(pitch)
    val eyeX = centerX + distance * cos(radPitch) * sin(radYaw)
    val eyeY = distance * sin(radPitch)
    val eyeZ = centerZ - distance * cos(radPitch) * cos(radYaw)
    val eye = doubleArrayOf(eyeX, eyeY, eyeZ)
    // forward = normalize(center - eye)
    val forward = normalize(doubleArrayOf(centerX - eyeX, -eyeY, centerZ - eyeZ))
    // right = normalize(cross(forward, worldUp))
    val right = normalize(cross(forward, doubleArrayOf(0.0, 1.0, 0.0)))
    val up = cross(right, forward)
    return Camera(right, up, forward)
  }

  private fun viewCoords(camera: Camera, p: DoubleArray): DoubleArray {
    val dx = p[0]
    val dy = p[1]
    val dz = p[2]
    val ex = centerX + distance * cos(Math.toRadians(pitch)) * sin(Math.toRadians(yaw))
    val ey = distance * sin(Math.toRadians(pitch))
    val ez = centerZ - distance * cos(Math.toRadians(pitch)) * cos(Math.toRadians(yaw))
    val rel = doubleArrayOf(dx - ex, dy - ey, dz - ez)
    return doubleArrayOf(
        dot(rel, camera.right),
        dot(rel, camera.up),
        dot(rel, camera.forward),
    )
  }

  private fun project(view: DoubleArray): DoubleArray? {
    val depth = view[2]
    if (depth < 1.0 || depth > 1000.0) return null
    val aspect = width.toDouble() / height.coerceAtLeast(1)
    val f = 1.0 / tan(Math.toRadians(45.0) / 2.0)
    val clipX = (f / aspect) * view[0] / depth
    val clipY = f * view[1] / depth
    val sx = (clipX * 0.5 + 0.5) * width
    val sy = (0.5 - clipY * 0.5) * height
    return doubleArrayOf(sx, sy, depth)
  }

  private fun worldPoint(camera: Camera, view: DoubleArray): DoubleArray {
    val ex = centerX + distance * cos(Math.toRadians(pitch)) * sin(Math.toRadians(yaw))
    val ey = distance * sin(Math.toRadians(pitch))
    val ez = centerZ - distance * cos(Math.toRadians(pitch)) * cos(Math.toRadians(yaw))
    return doubleArrayOf(
        ex + view[0] * camera.right[0] + view[1] * camera.up[0] + view[2] * camera.forward[0],
        ey + view[0] * camera.right[1] + view[1] * camera.up[1] + view[2] * camera.forward[1],
        ez + view[0] * camera.right[2] + view[1] * camera.up[2] + view[2] * camera.forward[2],
    )
  }

  private data class Face(
      val depth: Double,
      val xs: IntArray,
      val ys: IntArray,
      val fill: Color,
      val stroke: Boolean = false,
  )

  override fun paintComponent(g: Graphics) {
    super.paintComponent(g)
    if (width <= 1 || height <= 1) return
    val g2 = g as Graphics2D
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
    g2.color = background
    g2.fillRect(0, 0, width, height)
    val cam = camera()
    val grid = grid ?: return

    val ground = ArrayList<Face>()
    val lines = ArrayList<Face>()
    val tiles = ArrayList<Face>()
    val markerFaces = ArrayList<Face>()
    val mx = modelXform()
    fun gx(x: Double, z: Double): DoubleArray =
        if (mx == null) doubleArrayOf(x, 0.0, z)
        else doubleArrayOf(16.0 + (x - mx.cx) * mx.scale, 0.0, 16.0 + (z - mx.cz) * mx.scale)

    var hasData = false
    for (x in 0 until grid.cols) for (z in 0 until grid.rows) {
      if (grid.permissionAt(x, z) != 0 || grid.collisionAt(x, z) != 0) {
        hasData = true
        break
      }
    }
    if (!hasData) {
      outer@ for (layer in 0 until NdsGrid.LAYERS) {
        for (x in 0 until grid.cols) for (z in 0 until grid.rows) {
          if (grid.tileAt(layer, x, z) >= 0) {
            hasData = true
            break@outer
          }
        }
      }
    }

    for (x in 0 until grid.cols) {
      for (z in 0 until grid.rows) {
        val color =
            if (!hasData) checkerColor(x, z)
            else if (showCollision) collisionColor(grid.collisionAt(x, z))
            else terrainColor(grid.permissionAt(x, z), grid.collisionAt(x, z))
        val p = gx(x.toDouble(), z.toDouble())
        quad(ground, cam, p[0], p[1], p[2], color)
      }
    }
    if (showGrid) {
      val lineColor = Color(255, 255, 255, 50)
      for (i in 0..grid.cols) {
        val a = gx(i.toDouble(), 0.0)
        val b = gx(i.toDouble(), grid.rows.toDouble())
        line(lines, cam, a[0], a[1], a[2], b[0], b[1], b[2], lineColor)
      }
      for (i in 0..grid.rows) {
        val a = gx(0.0, i.toDouble())
        val b = gx(grid.cols.toDouble(), i.toDouble())
        line(lines, cam, a[0], a[1], a[2], b[0], b[1], b[2], lineColor)
      }
    }
    for (layer in 0 until NdsGrid.LAYERS) {
      for (x in 0 until grid.cols) {
        for (z in 0 until grid.rows) {
          val tile = grid.tileAt(layer, x, z)
          if (tile < 0) continue
          val def = NdsTileset.tiles.getOrNull(tile) ?: continue
          val base = grid.heightAt(layer, x, z)
          when (def.shape) {
            de.lananahwp.openmmo.mapeditor.core.TileShape.FLAT -> {
              val p = gx(x.toDouble(), z.toDouble())
              quad(tiles, cam, p[0], base, p[2], shade(def.topColor, layer))
            }
            de.lananahwp.openmmo.mapeditor.core.TileShape.CUBE,
            de.lananahwp.openmmo.mapeditor.core.TileShape.BLOCK ->
                cube(
                    tiles,
                    cam,
                    x.toDouble(),
                    z.toDouble(),
                    base,
                    def.height.toDouble(),
                    shade(def.topColor, layer),
                    shade(def.sideColor, layer),
                )
          }
        }
      }
    }
    for (marker in markers) {
      val p = gx(marker.x.toDouble() + 0.08, marker.z.toDouble() + 0.08)
      quad(markerFaces, cam, p[0], 1.0, p[2], marker.color)
    }
    drawModelTriangles(tiles, cam)

    drawFaces(g2, ground)
    drawFaces(g2, lines)
    drawFaces(g2, tiles)
    drawFaces(g2, markerFaces)
    drawTexturedTriangles(g2)
    drawWalkSurfaceTriangles(g2, cam)
    drawBrushCursor(g2, cam, grid, mx)
    // After the textured pass, so the selection stays visible on top of the geometry it marks.
    drawHighlightTriangles(g2, cam)
  }

  /** Draws the paint footprint last so textured terrain cannot cover it. */
  private fun drawBrushCursor(g2: Graphics2D, cam: Camera, grid: NdsGrid, m: ModelXform?) {
    if (paintMode == PaintMode.NONE) return
    if (paintMode == PaintMode.WALK_SURFACE && walkSurfaceHandles.isNotEmpty()) return
    val (hx, hz) = hoverCell ?: return
    val surface = m?.let {
      ndsTileSurfaceHeights(surfaceTriangles, grid.cols, grid.rows, it.groundY, it.scale)
    }
    val faces = ArrayList<Face>()
    val color = if (paintMode == PaintMode.WALK_SURFACE) Color(38, 224, 245, 140)
        else Color(255, 230, 46, if (paintMode == PaintMode.ELEVATION) 200 else 90)
    for ((cx, cz) in ndsTileStampFootprint(
        hx, hz, if (paintMode == PaintMode.WALK_SURFACE) 1 else brushSize,
        activeTileWidth, activeTileHeight, grid.cols, grid.rows)) {
      val terrain = surface?.get(cx)?.get(cz)?.takeIf { !it.isNaN() } ?: 0.0
      val top = ndsPaintCursorHeight(
          grid, cx, cz, activeLayer, terrain, m?.scale ?: 1f, customTileGeometry) + 0.08
      gridQuad(faces, cam, cx, cz, top, color, m)
    }
    drawFaces(g2, faces)
  }

  /** Draws the current selection as a tinted, outlined overlay over the geometry it was taken from. */
  private fun drawHighlightTriangles(g2: Graphics2D, cam: Camera) {
    if (highlightTriangles.isEmpty()) return
    val m = modelXform() ?: return
    val fill = Color(255, 209, 38, 110)
    val outline = Color(255, 243, 140, 240)
    val faces = ArrayList<Face>(highlightTriangles.size)
    for (tri in highlightTriangles) {
      val a = project(viewCoords(cam, xform(tri.ax, tri.ay, tri.az, m))) ?: continue
      val b = project(viewCoords(cam, xform(tri.bx, tri.by, tri.bz, m))) ?: continue
      val c = project(viewCoords(cam, xform(tri.cx, tri.cy, tri.cz, m))) ?: continue
      faces += Face(
          (a[2] + b[2] + c[2]) / 3.0,
          intArrayOf(a[0].toInt(), b[0].toInt(), c[0].toInt()),
          intArrayOf(a[1].toInt(), b[1].toInt(), c[1].toInt()),
          fill,
      )
    }
    faces.sortedByDescending { it.depth }.forEach { f ->
      g2.color = f.fill
      g2.fillPolygon(f.xs, f.ys, f.xs.size)
    }
    // Only the silhouette, not every triangle edge; see ndsOutlineEdges.
    g2.color = outline
    for (e in highlightOutline) {
      val a = project(viewCoords(cam, xform(e[0], e[1], e[2], m))) ?: continue
      val b = project(viewCoords(cam, xform(e[3], e[4], e[5], m))) ?: continue
      g2.drawLine(a[0].toInt(), a[1].toInt(), b[0].toInt(), b[1].toInt())
    }
  }

  /** Draws ROM BDHC after the scene as a non-interactive cyan debug overlay. */
  private fun drawWalkSurfaceTriangles(g2: Graphics2D, cam: Camera) {
    if (walkSurfaceTriangles.isEmpty()) return
    val m = modelXform() ?: return
    val fill = Color(38, 224, 245, 78)
    val outline = Color(115, 245, 255, 230)
    val faces = ArrayList<Face>(walkSurfaceTriangles.size)
    for (tri in walkSurfaceTriangles) {
      val a = project(viewCoords(cam, xform(tri.ax, tri.ay, tri.az, m))) ?: continue
      val b = project(viewCoords(cam, xform(tri.bx, tri.by, tri.bz, m))) ?: continue
      val c = project(viewCoords(cam, xform(tri.cx, tri.cy, tri.cz, m))) ?: continue
      faces += Face(
          (a[2] + b[2] + c[2]) / 3.0,
          intArrayOf(a[0].toInt(), b[0].toInt(), c[0].toInt()),
          intArrayOf(a[1].toInt(), b[1].toInt(), c[1].toInt()),
          fill,
      )
    }
    faces.sortedByDescending { it.depth }.forEach { face ->
      g2.color = face.fill
      g2.fillPolygon(face.xs, face.ys, face.xs.size)
    }
    g2.color = outline
    for (edge in walkSurfaceOutline) {
      val a = project(viewCoords(cam, xform(edge[0], edge[1], edge[2], m))) ?: continue
      val b = project(viewCoords(cam, xform(edge[3], edge[4], edge[5], m))) ?: continue
      g2.drawLine(a[0].toInt(), a[1].toInt(), b[0].toInt(), b[1].toInt())
    }
    val oldStroke = g2.stroke
    val oldFont = g2.font
    g2.stroke = BasicStroke(2f)
    g2.font = Font("Dialog", Font.BOLD, 14)
    for (handle in walkSurfaceHandles) {
      val point = project(viewCoords(cam, xform(handle.x, handle.y, handle.z, m))) ?: continue
      val x = point[0].toInt()
      val y = point[1].toInt()
      g2.color = Color(10, 15, 20, 245)
      g2.fillOval(x - 8, y - 8, 16, 16)
      g2.color = Color(handle.color, true)
      g2.fillOval(x - 7, y - 7, 14, 14)
      g2.color = Color.WHITE
      g2.drawOval(x - 7, y - 7, 14, 14)
      val glyph = ndsWalkHandleGlyph(handle.role)
      val metrics = g2.fontMetrics
      g2.drawString(
          glyph,
          x - metrics.stringWidth(glyph) / 2,
          y + (metrics.ascent - metrics.descent) / 2,
      )
    }
    g2.stroke = oldStroke
    g2.font = oldFont
  }

  private class ModelXform(val scale: Float, val cx: Float, val cz: Float, val groundY: Float)

  /** Computes the scale/offset that fits the model + grid footprint into the view. */
  private fun modelXform(): ModelXform? {
    val tris = modelTriangles
    val g = grid
    if (tris.isEmpty() && g == null) return null
    var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
    var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
    for (tri in tris) {
      for (v in floatArrayOf(tri.ax, tri.bx, tri.cx)) { if (v < minX) minX = v; if (v > maxX) maxX = v }
      for (v in floatArrayOf(tri.ay, tri.by, tri.cy)) { if (v < minY) minY = v; if (v > maxY) maxY = v }
      for (v in floatArrayOf(tri.az, tri.bz, tri.cz)) { if (v < minZ) minZ = v; if (v > maxZ) maxZ = v }
    }
    val fpX = g?.cols?.toFloat() ?: 0f
    val fpZ = g?.rows?.toFloat() ?: 0f
    val modelSpanX = if (tris.isEmpty()) 0f else maxX - minX
    val modelSpanZ = if (tris.isEmpty()) 0f else maxZ - minZ
    val spanX = maxOf(modelSpanX.coerceAtLeast(1e-3f), fpX.coerceAtLeast(1e-3f))
    val spanZ = maxOf(modelSpanZ.coerceAtLeast(1e-3f), fpZ.coerceAtLeast(1e-3f))
    val scale = (30.0 / maxOf(spanX, spanZ)).toFloat()
    val cx = if (fpX > 0f) fpX / 2f else (minX + maxX) / 2f
    val cz = if (fpZ > 0f) fpZ / 2f else (minZ + maxZ) / 2f
    // A grid defines editor height 0. Props must not redefine that origin merely by existing.
    return ModelXform(scale, cx, cz, if (g != null) 0f else minY)
  }

  private fun xform(x: Float, y: Float, z: Float, m: ModelXform): DoubleArray =
      doubleArrayOf(
          16.0 + (x - m.cx) * m.scale,
          (y - m.groundY).toDouble() * m.scale,
          16.0 + (z - m.cz) * m.scale,
      )

  /** Projects decoded NSBMD triangles into painter-sorted faces, scaled to fit the grid. */
  private fun drawModelTriangles(out: MutableList<Face>, cam: Camera) {
    if (modelOpacity <= 0f) return
    val m = modelXform()
    val triangles = modelTriangles + placedCustomTileTriangles().filter {
      it.texture.isEmpty() || it.texture !in modelTextures
    }
    for (tri in triangles) {
      val a = project(viewCoords(cam, viewPoint(tri.ax, tri.ay, tri.az, m)))
      val b = project(viewCoords(cam, viewPoint(tri.bx, tri.by, tri.bz, m)))
      val c = project(viewCoords(cam, viewPoint(tri.cx, tri.cy, tri.cz, m)))
      if (a == null || b == null || c == null) continue
      val depth = (a[2] + b[2] + c[2]) / 3.0
      val original = Color(tri.color, true)
      val opacity = ndsTriangleOpacity(tri, modelOpacity, propOpacity)
      val color = Color(original.red, original.green, original.blue,
          (original.alpha * opacity).toInt())
      if (color.alpha == 0) continue
      out += Face(
          depth,
          intArrayOf(a[0].toInt(), b[0].toInt(), c[0].toInt()),
          intArrayOf(a[1].toInt(), b[1].toInt(), c[1].toInt()),
          color,
      )
    }
  }

  /** Depth-buffered texture-mapped rendering of textured model triangles. */
  private fun drawTexturedTriangles(g2: Graphics2D) {
    if (modelOpacity <= 0f) return
    val m = modelXform()
    // Painted custom tiles join the map's own geometry here so they are textured and
    // depth-buffered against it rather than drawn as flat colour.
    val textured = (modelTriangles + placedCustomTileTriangles())
        .filter { it.texture.isNotEmpty() && it.texture in modelTextures }
    if (textured.isEmpty()) return
    val cam = camera()
    val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val depthBuf = FloatArray(width * height) { Float.MAX_VALUE }

    data class Proj(val x: Int, val y: Int, val invW: Float)

    for (tri in textured) {
      val tex = modelTextures[tri.texture] ?: continue
      val pixels =
          if (tri.palette.isNotEmpty()) {
            val pal = modelPalettes[tri.palette]
            if (pal != null) tex.decodeWith(pal) else tex.decode()
          } else {
            tex.decode()
          }
          ?: continue
      val pa = project(viewCoords(cam, viewPoint(tri.ax, tri.ay, tri.az, m))) ?: continue
      val pb = project(viewCoords(cam, viewPoint(tri.bx, tri.by, tri.bz, m))) ?: continue
      val pc = project(viewCoords(cam, viewPoint(tri.cx, tri.cy, tri.cz, m))) ?: continue
      // perspective-correct interpolation weights
      val wa = 1f / pa[2].toFloat()
      val wb = 1f / pb[2].toFloat()
      val wc = 1f / pc[2].toFloat()
      val ax = pa[0].toFloat(); val ay = pa[1].toFloat()
      val bx = pb[0].toFloat(); val by = pb[1].toFloat()
      val cx = pc[0].toFloat(); val cy = pc[1].toFloat()
      val minX = maxOf(0, minOf(ax, bx, cx).toInt())
      val maxX = minOf(width - 1, maxOf(ax, bx, cx).toInt())
      val minY = maxOf(0, minOf(ay, by, cy).toInt())
      val maxY = minOf(height - 1, maxOf(ay, by, cy).toInt())
      val area = ((bx - ax) * (cy - ay) - (cx - ax) * (by - ay))
      if (area == 0f) continue
      val invArea = 1f / area
      val tw = tex.width.toFloat()
      val th = tex.height.toFloat()
      for (py in minY..maxY) {
        val row = py * width
        for (px in minX..maxX) {
          val fx = px.toFloat() + 0.5f
          val fy = py.toFloat() + 0.5f
          val w0 = ((bx - fx) * (cy - fy) - (cx - fx) * (by - fy)) * invArea
          val w1 = ((cx - fx) * (ay - fy) - (ax - fx) * (cy - fy)) * invArea
          val w2 = 1f - w0 - w1
          if (w0 < 0f || w1 < 0f || w2 < 0f) continue
          // perspective-correct uv
          val zInv = w0 * wa + w1 * wb + w2 * wc
          if (zInv <= 0f) continue
          val depth = 1f / zInv
          val idx = row + px
          if (depth >= depthBuf[idx]) continue
          val u = (w0 * wa * tri.u0 + w1 * wb * tri.u1 + w2 * wc * tri.u2) / zInv
          val v = (w0 * wa * tri.v0 + w1 * wb * tri.v1 + w2 * wc * tri.v2) / zInv
          val su = sampleTextureCoord(u * tri.scaleS, tw.toInt(), tri.repeatS, tri.flipS)
          val sv = sampleTextureCoord(v * tri.scaleT, th.toInt(), tri.repeatT, tri.flipT)
          val sourceColor = pixels[sv * tw.toInt() + su]
          val sourceAlpha = (sourceColor ushr 24) and 0xFF
          val opacity = ndsTriangleOpacity(tri, modelOpacity, propOpacity)
          val col = (sourceColor and 0x00FFFFFF) or ((sourceAlpha * opacity).toInt() shl 24)
          if ((col ushr 24) and 0xFF == 0) continue
          // Match the GL view: translucent geometry does not hide a painted tile rendered after
          // it. This is what lets Show only tiles reveal/edit the square beneath a placed prop.
          if (opacity >= 0.999f) depthBuf[idx] = depth
          img.setRGB(px, py, modulate(col, tri.color))
        }
      }
    }
    g2.drawImage(img, 0, 0, null)
  }

  /** Applies the DS material's clamp, repeat, and mirrored-repeat sampling mode. */
  private fun sampleTextureCoord(value: Float, size: Int, repeat: Boolean, flip: Boolean): Int {
    if (size <= 1) return 0
    val coordinate = kotlin.math.floor(value.toDouble()).toInt()
    if (!repeat) return coordinate.coerceIn(0, size - 1)
    val period = if (flip) size * 2 else size
    var wrapped = coordinate % period
    if (wrapped < 0) wrapped += period
    return if (flip && wrapped >= size) period - 1 - wrapped else wrapped
  }

  /** Uses raw grid space for template-only custom maps, which have no NSBMD transform. */
  private fun viewPoint(x: Float, y: Float, z: Float, transform: ModelXform?): DoubleArray =
      if (transform == null) doubleArrayOf(x.toDouble(), y.toDouble(), z.toDouble())
      else xform(x, y, z, transform)

  /** Multiplies an ARGB pixel by a (ARGB) diffuse color's RGB channels. */
  private fun modulate(pixel: Int, diffuse: Int): Int {
    val dr = (diffuse shr 16) and 0xFF
    val dg = (diffuse shr 8) and 0xFF
    val db = diffuse and 0xFF
    if (dr == 255 && dg == 255 && db == 255) return pixel
    val a = (pixel ushr 24) and 0xFF
    val r = (((pixel shr 16) and 0xFF) * dr) / 255
    val g = (((pixel shr 8) and 0xFF) * dg) / 255
    val b = ((pixel and 0xFF) * db) / 255
    return (a shl 24) or (r shl 16) or (g shl 8) or b
  }

  private fun drawFaces(g2: Graphics2D, faces: List<Face>) {
    if (faces.isEmpty()) return
    faces.sortedByDescending { it.depth }.forEach { f ->
      g2.color = f.fill
      if (f.stroke && f.xs.size >= 2) {
        g2.drawLine(f.xs[0], f.ys[0], f.xs[1], f.ys[1])
      } else {
        g2.fillPolygon(f.xs, f.ys, f.xs.size)
      }
    }
  }

  private fun quad(out: MutableList<Face>, cam: Camera, x: Double, y: Double, z: Double, color: Color) {
    val a = project(viewCoords(cam, doubleArrayOf(x, y, z)))
    val b = project(viewCoords(cam, doubleArrayOf(x + 1, y, z)))
    val c = project(viewCoords(cam, doubleArrayOf(x + 1, y, z + 1)))
    val d = project(viewCoords(cam, doubleArrayOf(x, y, z + 1)))
    if (a == null || b == null || c == null || d == null) return
    val depth = (a[2] + b[2] + c[2] + d[2]) / 4.0
    out += Face(depth, intArrayOf(a[0].toInt(), b[0].toInt(), c[0].toInt(), d[0].toInt()), intArrayOf(a[1].toInt(), b[1].toInt(), c[1].toInt(), d[1].toInt()), color)
  }

  private fun gridQuad(
      out: MutableList<Face>,
      cam: Camera,
      x: Int,
      z: Int,
      y: Double,
      color: Color,
      m: ModelXform?,
  ) {
    fun point(px: Int, pz: Int): DoubleArray =
        if (m == null) doubleArrayOf(px.toDouble(), y, pz.toDouble())
        else doubleArrayOf(
            16.0 + (px - m.cx) * m.scale,
            y,
            16.0 + (pz - m.cz) * m.scale,
        )
    val a = project(viewCoords(cam, point(x, z))) ?: return
    val b = project(viewCoords(cam, point(x + 1, z))) ?: return
    val c = project(viewCoords(cam, point(x + 1, z + 1))) ?: return
    val d = project(viewCoords(cam, point(x, z + 1))) ?: return
    out += Face(
        (a[2] + b[2] + c[2] + d[2]) / 4.0,
        intArrayOf(a[0].toInt(), b[0].toInt(), c[0].toInt(), d[0].toInt()),
        intArrayOf(a[1].toInt(), b[1].toInt(), c[1].toInt(), d[1].toInt()),
        color,
    )
  }

  private fun cube(
      out: MutableList<Face>,
      cam: Camera,
      x: Double,
      z: Double,
      base: Double,
      h: Double,
      top: Color,
      side: Color,
  ) {
    val y0 = base
    val y1 = base + h
    // top
    quad(out, cam, x, y1, z, top)
    // sides
    quadSide(out, cam, x, y0, z, x + 1, y1, z, side) // -Z
    quadSide(out, cam, x, y0, z + 1, x + 1, y1, z + 1, side) // +Z
    quadSide(out, cam, x, y0, z, x, y1, z + 1, side) // -X
    quadSide(out, cam, x + 1, y0, z, x + 1, y1, z + 1, side) // +X
  }

  private fun quadSide(
      out: MutableList<Face>,
      cam: Camera,
      x0: Double,
      y0: Double,
      z0: Double,
      x1: Double,
      y1: Double,
      z1: Double,
      color: Color,
  ) {
    val a = project(viewCoords(cam, doubleArrayOf(x0, y0, z0)))
    val b = project(viewCoords(cam, doubleArrayOf(x1, y0, z1)))
    val c = project(viewCoords(cam, doubleArrayOf(x1, y1, z1)))
    val d = project(viewCoords(cam, doubleArrayOf(x0, y1, z0)))
    if (a == null || b == null || c == null || d == null) return
    val depth = (a[2] + b[2] + c[2] + d[2]) / 4.0
    out += Face(depth, intArrayOf(a[0].toInt(), b[0].toInt(), c[0].toInt(), d[0].toInt()), intArrayOf(a[1].toInt(), b[1].toInt(), c[1].toInt(), d[1].toInt()), color)
  }

  private fun line(
      out: MutableList<Face>,
      cam: Camera,
      x0: Double,
      y0: Double,
      z0: Double,
      x1: Double,
      y1: Double,
      z1: Double,
      color: Color,
  ) {
    val a = project(viewCoords(cam, doubleArrayOf(x0, y0, z0))) ?: return
    val b = project(viewCoords(cam, doubleArrayOf(x1, y1, z1))) ?: return
    val depth = (a[2] + b[2]) / 2.0
    out += Face(depth, intArrayOf(a[0].toInt(), b[0].toInt()), intArrayOf(a[1].toInt(), b[1].toInt()), color, stroke = true)
  }

  private fun checkerColor(x: Int, z: Int): Color =
      if ((x + z) % 2 == 0) Color(46, 50, 56) else Color(54, 59, 66)

  private fun collisionColor(value: Int): Color =
      when (value) {
        0 -> Color(42, 45, 52)
        0x80 -> Color(180, 40, 40)
        else -> Color(180, 120, 40)
      }

  private val terrainPalette =
      arrayOf(
          Color(84, 150, 70),
          Color(70, 120, 60),
          Color(120, 130, 60),
          Color(150, 150, 80),
          Color(200, 180, 130),
          Color(180, 140, 90),
          Color(90, 100, 70),
          Color(130, 100, 80),
      )

  private fun terrainColor(type: Int, collision: Int): Color {
    if (collision and 0x80 != 0) return Color(110, 100, 95)
    if (collision != 0) return Color(150, 130, 110)
    return when (type) {
      0x00 -> Color(84, 150, 70)
      0x01, 0x02, 0x03 -> Color(72, 140, 62)
      0x08, 0x09 -> Color(224, 204, 148)
      0x10, 0x11, 0x12, 0x13 -> Color(60, 110, 180)
      0x20, 0x21, 0x22 -> Color(100, 162, 86)
      else -> terrainPalette[type and 0x7]
    }
  }

  private fun shade(color: Color, layer: Int): Color {
    val s = 1f - (layer * 0.06f)
    return Color((color.red * s).toInt(), (color.green * s).toInt(), (color.blue * s).toInt())
  }

  // ---- Picking -------------------------------------------------------------

  private fun pickRay(mx: Int, my: Int): NdsPickRay? {
    if (width <= 0 || height <= 0) return null
    val cam = camera()
    val aspect = width.toDouble() / height.coerceAtLeast(1)
    val f = 1.0 / tan(Math.toRadians(45.0) / 2.0)
    val sx = (mx.toDouble() / width - 0.5) * 2.0
    val sy = (0.5 - my.toDouble() / height) * 2.0
    val vxUnit = sx * aspect / f
    val vyUnit = sy / f
    // ray in world space from near to far (view z grows away from the camera)
    val near = worldPoint(cam, doubleArrayOf(vxUnit, vyUnit, 1.0))
    val far = worldPoint(cam, doubleArrayOf(vxUnit * 1000.0, vyUnit * 1000.0, 1000.0))
    val dx = far[0] - near[0]
    val dy = far[1] - near[1]
    val dz = far[2] - near[2]
    return NdsPickRay(near, doubleArrayOf(dx, dy, dz))
  }

  private fun pointerHit(mx: Int, my: Int, includeModelGroup: Boolean): NdsPointerHit? {
    val ground = pickRay(mx, my)?.let(::groundPoint)
    val cell = ground?.let(::groundCell)
    val group = if (includeModelGroup) screenModelGroup(mx, my) else null
    val walkSurfaceId = if (includeModelGroup && paintMode == PaintMode.WALK_SURFACE) {
      screenWalkSurface(mx, my)
    } else null
    val walkHandle = if (includeModelGroup && paintMode == PaintMode.WALK_SURFACE) {
      screenWalkHandle(mx, my)
    } else null
    if (ground == null && group == null && walkSurfaceId == null && walkHandle == null) return null
    return NdsPointerHit(
        cell?.first, cell?.second, group, ground?.first, ground?.second,
        screenX = mx, screenY = my, walkSurfaceId = walkSurfaceId, walkHandle = walkHandle)
  }

  private fun screenPickView(): NdsScreenPickView? {
    val transform = modelXform() ?: return null
    return NdsScreenPickView(
        width, height, yaw, pitch, distance, centerX, centerZ,
        transform.scale, transform.cx, transform.cz, transform.groundY,
    )
  }

  private fun screenWalkSurface(mx: Int, my: Int): String? {
    val pickView = screenPickView() ?: return null
    return pickNdsModelGroupAtScreen(walkSurfaceTriangles, mx, my, pickView)
        ?.removePrefix("custom-bdhc:")
        ?.takeUnless { it == "__walk-preview__" }
  }

  private fun screenWalkHandle(mx: Int, my: Int): NdsWalkHandleRole? {
    val pickView = screenPickView() ?: return null
    return pickNdsWalkHandleAtScreen(walkSurfaceHandles, mx, my, pickView)
  }

  private fun screenModelGroup(mx: Int, my: Int): String? {
    val pickView = screenPickView() ?: return null
    return pickNdsModelGroupAtScreen(
        modelTriangles,
        mx,
        my,
        pickView,
    )
  }

  /**
   * The pointer resolved against the mesh, for surface picking only.
   *
   * Kept separate from [pointerHit] so the paint and object modes keep resolving clicks exactly as
   * they did; nothing here is on their path.
   */
  private fun surfacePointerHit(mx: Int, my: Int, shiftDown: Boolean, ctrlDown: Boolean): NdsPointerHit? {
    val ground = pickRay(mx, my)?.let(::groundPoint)
    val cell = ground?.let(::groundCell)
    val transform = modelXform()
    val surface = transform?.let {
      pickNdsModelSurfaceAtScreen(
          modelTriangles,
          mx,
          my,
          NdsScreenPickView(
              width, height, yaw, pitch, distance, centerX, centerZ,
              it.scale, it.cx, it.cz, it.groundY,
          ),
      )
    }
    if (ground == null && surface == null) return null
    return NdsPointerHit(
        cell?.first,
        cell?.second,
        surface?.triangle?.editGroup?.takeIf { it.isNotEmpty() },
        ground?.first,
        ground?.second,
        surface?.x,
        surface?.y,
        surface?.z,
        surface?.triangle?.texture,
        shiftDown,
        ctrlDown,
    )
  }

  private fun pickCell(mx: Int, my: Int): Pair<Int, Int>? =
      pickRay(mx, my)?.let(::groundPoint)?.let(::groundCell)

  private fun groundPoint(ray: NdsPickRay): Pair<Float, Float>? {
    val near = ray.origin
    val dx = ray.direction[0]
    val dy = ray.direction[1]
    val dz = ray.direction[2]
    if (dy == 0.0) return null
    val t = -near[1] / dy
    if (t < 0.0) return null
    val wx = near[0] + dx * t
    val wz = near[2] + dz * t
    val mx = modelXform()
    val gx =
        if (mx != null) ((wx - 16.0) / mx.scale + mx.cx).toFloat()
        else wx.toFloat()
    val gz =
        if (mx != null) ((wz - 16.0) / mx.scale + mx.cz).toFloat()
        else wz.toFloat()
    return gx to gz
  }

  private fun groundCell(point: Pair<Float, Float>): Pair<Int, Int>? {
    val grid = grid ?: return null
    val gx = kotlin.math.floor(point.first.toDouble()).toInt()
    val gz = kotlin.math.floor(point.second.toDouble()).toInt()
    return (gx to gz).takeIf { gx in 0 until grid.cols && gz in 0 until grid.rows }
  }

  private fun modelGroup(ray: NdsPickRay, prefix: String, excludedPrefix: String? = null): String? {
    val transform = modelXform() ?: return null
    val scale = transform.scale.toDouble()
    if (scale == 0.0) return null
    val modelRay = NdsPickRay(
        doubleArrayOf(
            (ray.origin[0] - 16.0) / scale + transform.cx,
            ray.origin[1] / scale + transform.groundY,
            (ray.origin[2] - 16.0) / scale + transform.cz,
        ),
        doubleArrayOf(
            ray.direction[0] / scale,
            ray.direction[1] / scale,
            ray.direction[2] / scale,
        ),
    )
    return pickNdsModelGroup(modelTriangles, modelRay, prefix, excludedPrefix)
  }

  private fun normalize(v: DoubleArray): DoubleArray {
    val len = kotlin.math.sqrt(dot(v, v))
    return if (len == 0.0) doubleArrayOf(0.0, 0.0, 0.0)
    else doubleArrayOf(v[0] / len, v[1] / len, v[2] / len)
  }

  private fun cross(a: DoubleArray, b: DoubleArray): DoubleArray =
      doubleArrayOf(
          a[1] * b[2] - a[2] * b[1],
          a[2] * b[0] - a[0] * b[2],
          a[0] * b[1] - a[1] * b[0],
      )

  private fun dot(a: DoubleArray, b: DoubleArray): Double =
      a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}
