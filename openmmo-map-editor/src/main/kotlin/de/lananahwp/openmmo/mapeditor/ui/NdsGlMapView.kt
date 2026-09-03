package de.lananahwp.openmmo.mapeditor.ui

import com.jogamp.opengl.GL2
import com.jogamp.opengl.GLAutoDrawable
import com.jogamp.opengl.GLCapabilities
import com.jogamp.opengl.GLEventListener
import com.jogamp.opengl.GLProfile
import com.jogamp.opengl.awt.GLCanvas
import com.jogamp.opengl.glu.GLU
import de.lananahwp.openmmo.mapeditor.core.NdsTexture
import de.lananahwp.openmmo.mapeditor.core.NdsTileset
import de.lananahwp.openmmo.mapeditor.core.TileShape
import de.lananahwp.openmmo.mapeditor.model.NdsGrid
import java.awt.Color
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.nio.ByteBuffer
import javax.swing.SwingUtilities

/**
 * OpenGL (JOGL) 3D view of a DS map grid + decoded NSBMD model geometry.
 * Middle-drag orbits, right-drag pans, wheel zooms, left click paints.
 */
class NdsGlMapView(
    private val onPaintCell: (x: Int, z: Int) -> Unit,
    private val onPaintCollision: (x: Int, z: Int, value: Int) -> Unit,
    private val onCellInteraction: (hit: NdsPointerHit, dragging: Boolean) -> Boolean = { _, _ -> false },
    /** Ctrl+click/drag in Tile or Height mode clears the cell instead of painting it. */
    private val onEraseCell: (x: Int, z: Int) -> Unit = { _, _ -> },
    /** Called before the first cell in a press-drag-release paint gesture. */
    private val onStrokeBegin: () -> Unit = {},
) : GLCanvas(GLCapabilities(GLProfile.get(GLProfile.GL2))), GLEventListener, Nds3DView {

  enum class PaintMode { TILE, COLLISION, PERMISSION, ELEVATION }

  override fun setPaintMode(mode: Int) {
    paintMode =
        when (mode) {
          1 -> PaintMode.COLLISION
          2 -> PaintMode.PERMISSION
          3 -> PaintMode.ELEVATION
          else -> PaintMode.TILE
        }
  }

  override fun asComponent() = this

  override var grid: NdsGrid? = null
    set(value) {
      field = value
      if (value != null) {
        // The view transform maps the footprint center to 16 and fits the map to ~30 units,
        // so the camera always looks at that point with a fixed framing distance.
        centerX = 16.0
        centerZ = 16.0
        distance = 46.0
        modelXformCache = null
        tileSurfaceCache = null
      }
      repaint()
    }

  override var activeLayer = 0
  override var activeTile = 0
  override var activeHeight = 0.0
  override var brushCollision = 1
  override var showGrid = true
  override var showCollision = false
  var paintMode: PaintMode = PaintMode.TILE
  override var markers: List<NdsEventMarker> = emptyList()

  override var walkSurfaceTriangles: List<de.lananahwp.openmmo.mapeditor.core.NdsTri> = emptyList()
    set(value) {
      field = value
      // Keep neighbouring plates visibly distinct while dropping each quad's internal diagonal.
      walkSurfaceOutline = value.groupBy { it.editGroup }.values.flatMap(::ndsOutlineEdges)
      repaint()
    }

  private var walkSurfaceOutline: List<FloatArray> = emptyList()
  override var modelTriangles: List<de.lananahwp.openmmo.mapeditor.core.NdsTri> = emptyList()
    set(value) {
      field = value
      modelXformCache = null
      // The per-square surface heights are derived from these triangles and the transform they
      // produce, so both caches have to fall together.
      tileSurfaceCache = null
      repaint()
    }

  override var modelTextures: Map<String, NdsTexture> = emptyMap()
    set(value) {
      field = value
      texCache.clear()
      repaint()
    }

  override var modelPalettes: Map<String, IntArray> = emptyMap()
  override var modelOpacity: Float = 1f
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
  private val glu = GLU()
  private val texCache = HashMap<String, Int>()

  init {
    addGLEventListener(this)
    addMouseListener(
        object : MouseAdapter() {
          override fun mousePressed(e: MouseEvent) {
            lastX = e.x
            lastY = e.y
            if (e.button == MouseEvent.BUTTON1) {
              val hit = pointerHit(e.x, e.y, includeModelGroup = true) ?: return
              if (!onCellInteraction(hit, false) && hit.cellX != null && hit.cellZ != null) {
                onStrokeBegin()
                paint(hit.cellX, hit.cellZ, e.isControlDown)
              }
            }
          }

          override fun mouseReleased(e: MouseEvent) {
            cursor = Cursor.getDefaultCursor()
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
              yaw += dx * 0.6
              pitch = (pitch - dy * 0.6).coerceIn(5.0, 85.0)
              repaint()
            } else if (SwingUtilities.isRightMouseButton(e)) {
              // Pan in screen/viewport space (corrected for the map's yaw rotation).
              val s = distance / 800.0
              val cosYaw = Math.cos(Math.toRadians(yaw))
              val sinYaw = Math.sin(Math.toRadians(yaw))
              centerX += (dx * -cosYaw + dy * sinYaw) * s
              centerZ += (dx * -sinYaw + dy * -cosYaw) * s
              repaint()
            } else if (SwingUtilities.isLeftMouseButton(e)) {
              val hit = pointerHit(e.x, e.y, includeModelGroup = false) ?: return
              if (!onCellInteraction(hit, true) && hit.cellX != null && hit.cellZ != null) {
                paint(hit.cellX, hit.cellZ, e.isControlDown)
              }
            }
          }

          override fun mouseMoved(e: MouseEvent) {
            val cell = pickCell(e.x, e.y)
            if (cell != hoverCell) {
              hoverCell = cell
              repaint()
            }
          }
        })
    addMouseWheelListener { e: MouseWheelEvent ->
      distance = (distance * (if (e.wheelRotation < 0) 0.9 else 1.1)).coerceIn(8.0, 220.0)
      repaint()
    }
  }

  private fun paint(x: Int, z: Int, erase: Boolean) {
    when (paintMode) {
      PaintMode.TILE -> if (erase) onEraseCell(x, z) else onPaintCell(x, z)
      PaintMode.COLLISION -> onPaintCollision(x, z, brushCollision)
      PaintMode.PERMISSION -> onPaintCollision(x, z, brushCollision)
      PaintMode.ELEVATION -> if (erase) onEraseCell(x, z) else onPaintCell(x, z)
    }
  }

  override fun init(drawable: GLAutoDrawable) {
    val gl = drawable.gl as GL2
    gl.glClearColor(0.12f, 0.13f, 0.16f, 1f)
    gl.glEnable(GL2.GL_DEPTH_TEST)
    gl.glEnable(GL2.GL_BLEND)
    gl.glBlendFunc(GL2.GL_SRC_ALPHA, GL2.GL_ONE_MINUS_SRC_ALPHA)
    gl.glEnable(GL2.GL_COLOR_MATERIAL)
    gl.glColorMaterial(GL2.GL_FRONT_AND_BACK, GL2.GL_AMBIENT_AND_DIFFUSE)
    gl.glEnable(GL2.GL_LIGHTING)
    gl.glEnable(GL2.GL_LIGHT0)
    gl.glLightfv(GL2.GL_LIGHT0, GL2.GL_AMBIENT, floatArrayOf(0.4f, 0.4f, 0.4f, 1f), 0)
    gl.glLightfv(GL2.GL_LIGHT0, GL2.GL_DIFFUSE, floatArrayOf(0.8f, 0.8f, 0.8f, 1f), 0)
    gl.glLightfv(GL2.GL_LIGHT0, GL2.GL_POSITION, floatArrayOf(60f, 90f, 40f, 1f), 0)
    gl.glDisable(GL2.GL_CULL_FACE)
  }

  override fun dispose(drawable: GLAutoDrawable) {
    texCache.values.forEach { id ->
      try { (drawable.gl as GL2).glDeleteTextures(1, intArrayOf(id), 0) } catch (_: Throwable) {}
    }
    texCache.clear()
  }

  override fun display(drawable: GLAutoDrawable) {
    val gl = drawable.gl as GL2
    gl.glClear(GL2.GL_COLOR_BUFFER_BIT or GL2.GL_DEPTH_BUFFER_BIT)
    setupCamera(gl)
    drawGround(gl)
    drawModel(gl)
    drawPlacedTiles(gl)
    drawWalkSurfaces(gl)
    drawMarkers(gl)
    gl.glFlush()
  }

  override fun reshape(drawable: GLAutoDrawable, x: Int, y: Int, width: Int, height: Int) {
    val gl = drawable.gl as GL2
    gl.glViewport(0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1))
  }

  private fun setupCamera(gl: GL2) {
    gl.glMatrixMode(GL2.GL_PROJECTION)
    gl.glLoadIdentity()
    val aspect = width.toFloat() / height.coerceAtLeast(1)
    // Must match the field of view pickRay uses, or the cursor resolves off from what is drawn.
    glu.gluPerspective(NDS_FIELD_OF_VIEW, aspect.toDouble(), 1.0, 1000.0)
    gl.glMatrixMode(GL2.GL_MODELVIEW)
    gl.glLoadIdentity()
    val radYaw = Math.toRadians(yaw)
    val radPitch = Math.toRadians(pitch)
    val eyeX = centerX + distance * Math.cos(radPitch) * Math.sin(radYaw)
    val eyeY = distance * Math.sin(radPitch)
    val eyeZ = centerZ - distance * Math.cos(radPitch) * Math.cos(radYaw)
    glu.gluLookAt(eyeX, eyeY, eyeZ, centerX, 0.0, centerZ, 0.0, 1.0, 0.0)
  }

  private fun drawGround(gl: GL2) {
    val g = grid ?: return
    val gcols = g.cols
    val grows = g.rows
    val xf = modelXformCache
    gl.glDisable(GL2.GL_LIGHTING)
    // The permission grid is a translucent overlay at y=0 that must not occlude the model
    // (whose floor/terrain also sits at y=0), so disable depth writing while drawing it.
    gl.glEnable(GL2.GL_BLEND)
    gl.glBlendFunc(GL2.GL_SRC_ALPHA, GL2.GL_ONE_MINUS_SRC_ALPHA)
    gl.glDepthMask(false)
    for (x in 0 until gcols) {
      for (z in 0 until grows) {
        val color =
            if (showCollision) collisionColor(g.collisionAt(x, z))
            else terrainColor(g.permissionAt(x, z), g.collisionAt(x, z))
        gl.glColor4f(color.red / 255f, color.green / 255f, color.blue / 255f, 0.35f)
        gl.glBegin(GL2.GL_QUADS)
        groundVertex(gl, x.toDouble(), 0.0, z.toDouble(), xf)
        groundVertex(gl, (x + 1).toDouble(), 0.0, z.toDouble(), xf)
        groundVertex(gl, (x + 1).toDouble(), 0.0, (z + 1).toDouble(), xf)
        groundVertex(gl, x.toDouble(), 0.0, (z + 1).toDouble(), xf)
        gl.glEnd()
      }
    }
    if (showGrid) {
      gl.glColor4f(1f, 1f, 1f, 0.28f)
      gl.glBegin(GL2.GL_LINES)
      for (i in 0..gcols) {
        groundVertex(gl, i.toDouble(), 0.005, 0.0, xf)
        groundVertex(gl, i.toDouble(), 0.005, grows.toDouble(), xf)
      }
      for (i in 0..grows) {
        groundVertex(gl, 0.0, 0.005, i.toDouble(), xf)
        groundVertex(gl, gcols.toDouble(), 0.005, i.toDouble(), xf)
      }
      gl.glEnd()
    }
    hoverCell?.let { (hx, hz) ->
      gl.glColor4f(1f, 1f, 0.4f, 0.35f)
      gl.glBegin(GL2.GL_QUADS)
      groundVertex(gl, hx.toDouble(), 0.01, hz.toDouble(), xf)
      groundVertex(gl, (hx + 1).toDouble(), 0.01, hz.toDouble(), xf)
      groundVertex(gl, (hx + 1).toDouble(), 0.01, (hz + 1).toDouble(), xf)
      groundVertex(gl, hx.toDouble(), 0.01, (hz + 1).toDouble(), xf)
      gl.glEnd()
    }
    gl.glDisable(GL2.GL_BLEND)
    gl.glDepthMask(true)
    gl.glEnable(GL2.GL_LIGHTING)
  }

  /** Transforms a ground-plane point with the same xform used for the model. */
  private fun groundVertex(gl: GL2, x: Double, y: Double, z: Double, xf: ModelXform?) {
    if (xf == null) {
      gl.glVertex3d(x, y, z)
      return
    }
    gl.glVertex3d(16.0 + (x - xf.cx) * xf.scale, y, 16.0 + (z - xf.cz) * xf.scale)
  }

  /**
   * The top of the map's own geometry over each grid square, in world units, or null where the
   * square has no terrain over it.
   *
   * Rebuilt whenever the model changes. Terrain is coarse (one quad can span many squares), so
   * every square a triangle covers takes that triangle's height, not just the square holding its
   * centroid — otherwise a large quad would leave every square but one without a surface.
   */
  private var tileSurfaceCache: Array<DoubleArray>? = null

  private fun tileSurfaceHeights(g: NdsGrid, xf: ModelXform): Array<DoubleArray> {
    tileSurfaceCache?.let { return it }
    // NaN marks "no terrain here", so a square at world height 0 stays distinguishable from empty.
    val out = Array(g.cols) { DoubleArray(g.rows) { Double.NaN } }
    for (tri in modelTriangles) {
      val minX = kotlin.math.floor(minOf(tri.ax, tri.bx, tri.cx).toDouble()).toInt()
      val maxX = kotlin.math.floor(maxOf(tri.ax, tri.bx, tri.cx).toDouble()).toInt()
      val minZ = kotlin.math.floor(minOf(tri.az, tri.bz, tri.cz).toDouble()).toInt()
      val maxZ = kotlin.math.floor(maxOf(tri.az, tri.bz, tri.cz).toDouble()).toInt()
      val top = maxOf(tri.ay, tri.by, tri.cy)
      val worldTop = (top - xf.groundY).toDouble() * xf.scale
      for (x in maxOf(0, minX)..minOf(g.cols - 1, maxX)) {
        for (z in maxOf(0, minZ)..minOf(g.rows - 1, maxZ)) {
          val current = out[x][z]
          if (current.isNaN() || worldTop > current) out[x][z] = worldTop
        }
      }
    }
    tileSurfaceCache = out
    return out
  }

  /**
   * Renders tiles painted in Tile mode (grid.tiles / NdsTileset), the same flat/cube shapes
   * NdsSoftwareMapView draws. This was previously missing here entirely — the GL view painted
   * into grid.tiles correctly but never drew any of it, so Tile-mode brushes (Grass, Path, ...)
   * appeared to do nothing.
   */
  private fun drawPlacedTiles(gl: GL2) {
    val g = grid ?: return
    val xf = modelXformCache
    val surface = xf?.let { tileSurfaceHeights(g, it) }
    gl.glDisable(GL2.GL_LIGHTING)
    gl.glDisable(GL2.GL_TEXTURE_2D)
    gl.glEnable(GL2.GL_BLEND)
    gl.glBlendFunc(GL2.GL_SRC_ALPHA, GL2.GL_ONE_MINUS_SRC_ALPHA)
    // Painted tiles sit on the map's own surface, which is rarely the y=0 grid plane: the model
    // is drawn at (y - groundY) * scale, and groundY is its LOWEST vertex, so any map whose
    // walkable floor is above its lowest geometry renders that floor well above the grid. Drawing
    // paint at grid height therefore buried it under the terrain, visible only through holes and
    // around the edges. Polygon offset only settles coplanar ties, so it cannot cover a real
    // vertical gap; the tile has to be lifted to the surface it was painted on.
    gl.glEnable(GL2.GL_POLYGON_OFFSET_FILL)
    gl.glPolygonOffset(-2f, -2f)
    for (layer in 0 until NdsGrid.LAYERS) {
      for (x in 0 until g.cols) {
        for (z in 0 until g.rows) {
          val tile = g.tileAt(layer, x, z)
          if (tile < 0) continue
          val def = NdsTileset.tiles.getOrNull(tile) ?: continue
          // Squares with no terrain over them keep sitting on the grid plane, so paint stays
          // visible on the open ground around a map as well as on the map itself.
          val ground = surface?.get(x)?.get(z)?.takeIf { !it.isNaN() } ?: 0.0
          val base = ground + g.heightAt(layer, x, z)
          when (def.shape) {
            TileShape.FLAT -> {
              val top = shade(def.topColor, layer)
              gl.glColor4f(top.red / 255f, top.green / 255f, top.blue / 255f, 0.9f)
              gl.glBegin(GL2.GL_QUADS)
              groundVertex(gl, x.toDouble(), base + 0.01, z.toDouble(), xf)
              groundVertex(gl, (x + 1).toDouble(), base + 0.01, z.toDouble(), xf)
              groundVertex(gl, (x + 1).toDouble(), base + 0.01, (z + 1).toDouble(), xf)
              groundVertex(gl, x.toDouble(), base + 0.01, (z + 1).toDouble(), xf)
              gl.glEnd()
            }
            TileShape.CUBE, TileShape.BLOCK ->
                drawTileCube(
                    gl, xf, x, z, base, def.height.toDouble(),
                    shade(def.topColor, layer), shade(def.sideColor, layer))
          }
        }
      }
    }
    gl.glDisable(GL2.GL_POLYGON_OFFSET_FILL)
    gl.glDisable(GL2.GL_BLEND)
    gl.glEnable(GL2.GL_LIGHTING)
  }

  private fun drawTileCube(
      gl: GL2,
      xf: ModelXform?,
      x: Int,
      z: Int,
      y0: Double,
      h: Double,
      top: Color,
      side: Color,
  ) {
    val y1 = y0 + h
    gl.glColor4f(top.red / 255f, top.green / 255f, top.blue / 255f, 0.95f)
    gl.glBegin(GL2.GL_QUADS)
    groundVertex(gl, x.toDouble(), y1, z.toDouble(), xf)
    groundVertex(gl, (x + 1).toDouble(), y1, z.toDouble(), xf)
    groundVertex(gl, (x + 1).toDouble(), y1, (z + 1).toDouble(), xf)
    groundVertex(gl, x.toDouble(), y1, (z + 1).toDouble(), xf)
    gl.glEnd()
    gl.glColor4f(side.red / 255f, side.green / 255f, side.blue / 255f, 0.95f)
    gl.glBegin(GL2.GL_QUADS)
    // -Z
    groundVertex(gl, x.toDouble(), y0, z.toDouble(), xf)
    groundVertex(gl, (x + 1).toDouble(), y0, z.toDouble(), xf)
    groundVertex(gl, (x + 1).toDouble(), y1, z.toDouble(), xf)
    groundVertex(gl, x.toDouble(), y1, z.toDouble(), xf)
    // +Z
    groundVertex(gl, x.toDouble(), y0, (z + 1).toDouble(), xf)
    groundVertex(gl, (x + 1).toDouble(), y0, (z + 1).toDouble(), xf)
    groundVertex(gl, (x + 1).toDouble(), y1, (z + 1).toDouble(), xf)
    groundVertex(gl, x.toDouble(), y1, (z + 1).toDouble(), xf)
    // -X
    groundVertex(gl, x.toDouble(), y0, z.toDouble(), xf)
    groundVertex(gl, x.toDouble(), y0, (z + 1).toDouble(), xf)
    groundVertex(gl, x.toDouble(), y1, (z + 1).toDouble(), xf)
    groundVertex(gl, x.toDouble(), y1, z.toDouble(), xf)
    // +X
    groundVertex(gl, (x + 1).toDouble(), y0, z.toDouble(), xf)
    groundVertex(gl, (x + 1).toDouble(), y0, (z + 1).toDouble(), xf)
    groundVertex(gl, (x + 1).toDouble(), y1, (z + 1).toDouble(), xf)
    groundVertex(gl, (x + 1).toDouble(), y1, z.toDouble(), xf)
    gl.glEnd()
  }

  /** Darkens a tile color per layer, matching NdsSoftwareMapView's shading. */
  private fun shade(color: Color, layer: Int): Color {
    val s = 1f - (layer * 0.06f)
    return Color(
        (color.red * s).toInt().coerceIn(0, 255),
        (color.green * s).toInt().coerceIn(0, 255),
        (color.blue * s).toInt().coerceIn(0, 255))
  }

  private fun drawModel(gl: GL2) {
    if (modelTriangles.isEmpty() || modelOpacity <= 0f) return
    val xf = modelXform() ?: return
    val translucent = modelOpacity < 0.999f
    gl.glEnable(GL2.GL_TEXTURE_2D)
    gl.glDisable(GL2.GL_LIGHTING)
    // DS renders textured pixels as opaque-or-invisible (alpha test), not blended.
    if (translucent) {
      gl.glEnable(GL2.GL_BLEND)
      gl.glBlendFunc(GL2.GL_SRC_ALPHA, GL2.GL_ONE_MINUS_SRC_ALPHA)
      gl.glDepthMask(false)
    } else {
      gl.glDisable(GL2.GL_BLEND)
    }
    gl.glEnable(GL2.GL_ALPHA_TEST)
    gl.glAlphaFunc(GL2.GL_GREATER, 0.05f)
    var boundTex = -1
    var boundWrapS = Int.MIN_VALUE
    var boundWrapT = Int.MIN_VALUE
    var texturingOn = false
    for (tri in modelTriangles) {
      val texId =
          if (tri.texture.isNotEmpty()) glTextureId(gl, tri.texture, tri.palette) else -1
      // Triangles whose texture is missing/empty must NOT sample the default (white) texture:
      // disable GL_TEXTURE_2D so they render flat-shaded with their diffuse color instead.
      val wantTextured = texId != -1
      if (wantTextured != texturingOn) {
        if (wantTextured) gl.glEnable(GL2.GL_TEXTURE_2D)
        else gl.glDisable(GL2.GL_TEXTURE_2D)
        texturingOn = wantTextured
      }
      val textureChanged = texId != boundTex
      if (textureChanged) {
        if (boundTex != -1) gl.glBindTexture(GL2.GL_TEXTURE_2D, 0)
        if (texId != -1) gl.glBindTexture(GL2.GL_TEXTURE_2D, texId)
        boundTex = texId
      }
      if (texId != -1) {
        val wrapS = textureWrapMode(tri.repeatS, tri.flipS)
        val wrapT = textureWrapMode(tri.repeatT, tri.flipT)
        if (textureChanged || wrapS != boundWrapS) {
          gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_WRAP_S, wrapS)
          boundWrapS = wrapS
        }
        if (textureChanged || wrapT != boundWrapT) {
          gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_WRAP_T, wrapT)
          boundWrapT = wrapT
        }
      }
      val tex = if (texId != -1) modelTextures[tri.texture] else null
      val tw = tex?.width ?: 1
      val th = tex?.height ?: 1
      val color = Color(tri.color, true)
      // Modulate with the material's diffuse color (DSPRE uses this as glColor), which
      // darkens the mint palette colors to the correct in-game look.
      gl.glColor4f(color.red / 255f, color.green / 255f, color.blue / 255f, modelOpacity)
      gl.glBegin(GL2.GL_TRIANGLES)
      vertex(gl, tri.ax, tri.ay, tri.az, tri.u0, tri.v0, tw, th, texId != -1, tri.scaleS, tri.scaleT)
      vertex(gl, tri.bx, tri.by, tri.bz, tri.u1, tri.v1, tw, th, texId != -1, tri.scaleS, tri.scaleT)
      vertex(gl, tri.cx, tri.cy, tri.cz, tri.u2, tri.v2, tw, th, texId != -1, tri.scaleS, tri.scaleT)
      gl.glEnd()
    }
    gl.glBindTexture(GL2.GL_TEXTURE_2D, 0)
    gl.glDisable(GL2.GL_TEXTURE_2D)
    gl.glDisable(GL2.GL_ALPHA_TEST)
    if (translucent) gl.glDepthMask(true)
    gl.glEnable(GL2.GL_BLEND)
    gl.glEnable(GL2.GL_LIGHTING)
  }

  /** Draws ROM BDHC as a non-interactive cyan debug overlay. */
  private fun drawWalkSurfaces(gl: GL2) {
    if (walkSurfaceTriangles.isEmpty()) return
    val xf = modelXform() ?: return
    gl.glDisable(GL2.GL_LIGHTING)
    gl.glDisable(GL2.GL_TEXTURE_2D)
    gl.glEnable(GL2.GL_BLEND)
    gl.glBlendFunc(GL2.GL_SRC_ALPHA, GL2.GL_ONE_MINUS_SRC_ALPHA)
    gl.glDepthMask(false)

    gl.glColor4f(0.15f, 0.88f, 0.96f, 0.30f)
    gl.glBegin(GL2.GL_TRIANGLES)
    for (tri in walkSurfaceTriangles) {
      highlightVertex(gl, tri.ax, tri.ay, tri.az, xf)
      highlightVertex(gl, tri.bx, tri.by, tri.bz, xf)
      highlightVertex(gl, tri.cx, tri.cy, tri.cz, xf)
    }
    gl.glEnd()

    gl.glColor4f(0.45f, 0.96f, 1f, 0.90f)
    gl.glLineWidth(1.5f)
    gl.glBegin(GL2.GL_LINES)
    for (edge in walkSurfaceOutline) {
      highlightVertex(gl, edge[0], edge[1], edge[2], xf)
      highlightVertex(gl, edge[3], edge[4], edge[5], xf)
    }
    gl.glEnd()
    gl.glLineWidth(1f)

    gl.glDepthMask(true)
    gl.glDisable(GL2.GL_BLEND)
    gl.glEnable(GL2.GL_LIGHTING)
  }

  /**
   * How far the selection is lifted off the surface it marks, in world units.
   *
   * The view fits a map into ~30 world units, so this is a small fraction of one map square --
   * invisible in practice, but far larger than the depth-buffer resolution at the furthest zoom
   * the camera allows, which is what keeps the selection from flickering against the terrain.
   */
  private val highlightLift = 0.02

  private fun highlightVertex(gl: GL2, x: Float, y: Float, z: Float, xf: ModelXform) {
    gl.glVertex3d(
        16.0 + (x - xf.cx) * xf.scale,
        (y - xf.groundY).toDouble() * xf.scale + highlightLift,
        16.0 + (z - xf.cz) * xf.scale,
    )
  }

  private fun textureWrapMode(repeat: Boolean, flip: Boolean): Int =
      if (!repeat) GL2.GL_CLAMP_TO_EDGE
      else if (flip) GL2.GL_MIRRORED_REPEAT
      else GL2.GL_REPEAT

  private fun vertex(
      gl: GL2,
      x: Float,
      y: Float,
      z: Float,
      u: Float,
      v: Float,
      tw: Int,
      th: Int,
      textured: Boolean,
      scaleS: Float = 1f,
      scaleT: Float = 1f,
  ) {
    val xf = modelXformCache
    if (textured) {
      // DSPRE scales texture coords by the material's scaleS/scaleT (texture repeat).
      val uu = (u / tw) * scaleS
      val vv = 1f - (v / th) * scaleT
      gl.glTexCoord2f(uu, vv)
    }
    gl.glVertex3d(16.0 + (x - xf!!.cx) * xf.scale, (y - xf.groundY).toDouble() * xf.scale, 16.0 + (z - xf.cz) * xf.scale)
  }

  private class ModelXform(val scale: Float, val cx: Float, val cz: Float, val groundY: Float, val uScale: Float = 1f)

  private var modelXformCache: ModelXform? = null

  private fun modelXform(): ModelXform? {
    val tris = modelTriangles
    if (tris.isEmpty()) return null
    var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
    var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
    for (tri in tris) {
      for (v in floatArrayOf(tri.ax, tri.bx, tri.cx)) { if (v < minX) minX = v; if (v > maxX) maxX = v }
      for (v in floatArrayOf(tri.ay, tri.by, tri.cy)) { if (v < minY) minY = v; if (v > maxY) maxY = v }
      for (v in floatArrayOf(tri.az, tri.bz, tri.cz)) { if (v < minZ) minZ = v; if (v > maxZ) maxZ = v }
    }
    // Fit the footprint: the model and the permission grid share the same tile space, so the
    // transform is centered on the map footprint and the grid is drawn with the same xform.
    val g = grid
    val fpX = g?.cols?.toFloat() ?: 0f
    val fpZ = g?.rows?.toFloat() ?: 0f
    val spanX = maxOf((maxX - minX).coerceAtLeast(1e-3f), fpX.coerceAtLeast(1e-3f))
    val spanZ = maxOf((maxZ - minZ).coerceAtLeast(1e-3f), fpZ.coerceAtLeast(1e-3f))
    val scale = (30.0 / maxOf(spanX, spanZ)).toFloat()
    val cx = if (fpX > 0f) fpX / 2f else (minX + maxX) / 2f
    val cz = if (fpZ > 0f) fpZ / 2f else (minZ + maxZ) / 2f
    modelXformCache = ModelXform(scale, cx, cz, minY)
    return modelXformCache
  }

  /** Builds/caches a GL texture from a decoded NdsTexture. Uses the material's palette name
   *  (DSPRE's MatchTextures associates palettes by the model's palname); falls back to the
   *  texture's own palette when the material has no palette name. */
  private fun glTextureId(gl: GL2, name: String, palName: String): Int {
    val cacheKey = "$name\u0000$palName"
    texCache[cacheKey]?.let { return it }
    val tex = modelTextures[name] ?: return -1
    val pixels =
        if (palName.isNotEmpty()) {
          val pal = modelPalettes[palName]
          if (pal != null) tex.decodeWith(pal) else tex.decode()
        } else {
          tex.decode()
        }
        ?: return -1
    val w = tex.width
    val h = tex.height
    // Convert ARGB IntArray -> RGBA bytes (flip rows so v=0 is the texture top).
    val buf = ByteBuffer.allocate(w * h * 4)
    for (row in 0 until h) {
      val srcRow = h - 1 - row
      for (col in 0 until w) {
        val p = pixels[srcRow * w + col]
        buf.put((p shr 16).toByte())
        buf.put((p shr 8).toByte())
        buf.put(p.toByte())
        buf.put((p ushr 24).toByte())
      }
    }
    buf.flip()
    val ids = IntArray(1)
    gl.glGenTextures(1, ids, 0)
    gl.glBindTexture(GL2.GL_TEXTURE_2D, ids[0])
    gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_WRAP_S, GL2.GL_REPEAT)
    gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_WRAP_T, GL2.GL_REPEAT)
    gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_MIN_FILTER, GL2.GL_LINEAR)
    gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_MAG_FILTER, GL2.GL_NEAREST)
    gl.glTexImage2D(GL2.GL_TEXTURE_2D, 0, GL2.GL_RGBA, w, h, 0, GL2.GL_RGBA, GL2.GL_UNSIGNED_BYTE, buf)
    texCache[cacheKey] = ids[0]
    return ids[0]
  }

  private fun drawMarkers(gl: GL2) {
    if (markers.isEmpty()) return
    gl.glDisable(GL2.GL_LIGHTING)
    for (m in markers) {
      gl.glColor4f(m.color.red / 255f, m.color.green / 255f, m.color.blue / 255f, 1f)
      gl.glBegin(GL2.GL_QUADS)
      val x = m.x.toDouble() + 0.08
      val z = m.z.toDouble() + 0.08
      groundVertex(gl, x, 1.0, z, modelXformCache)
      groundVertex(gl, x + 0.84, 1.0, z, modelXformCache)
      groundVertex(gl, x + 0.84, 1.0, z + 0.84, modelXformCache)
      groundVertex(gl, x, 1.0, z + 0.84, modelXformCache)
      gl.glEnd()
    }
    gl.glEnable(GL2.GL_LIGHTING)
  }

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

  /** Casts a pick ray from screen coordinates; see [ndsPickRay] for why GL matrices are unusable here. */
  private fun pickRay(mx: Int, my: Int): NdsPickRay? =
      ndsPickRay(width, height, yaw, pitch, distance, centerX, centerZ, mx, my)

  private fun pointerHit(mx: Int, my: Int, includeModelGroup: Boolean): NdsPointerHit? {
    val ground = pickRay(mx, my)?.let(::groundPoint)
    val cell = ground?.let(::groundCell)
    val group = if (includeModelGroup) screenModelGroup(mx, my) else null
    if (ground == null && group == null) return null
    return NdsPointerHit(cell?.first, cell?.second, group, ground?.first, ground?.second)
  }

  private fun screenModelGroup(mx: Int, my: Int): String? {
    val xf = modelXform() ?: return null
    return pickNdsModelGroupAtScreen(
        modelTriangles,
        mx,
        my,
        NdsScreenPickView(
            width, height, yaw, pitch, distance, centerX, centerZ,
            xf.scale, xf.cx, xf.cz, xf.groundY,
        ),
    )
  }

  /** Unprojects the cursor onto the ground plane and returns the grid cell. */
  private fun pickCell(mx: Int, my: Int): Pair<Int, Int>? =
      pickRay(mx, my)?.let(::groundPoint)?.let(::groundCell)

  private fun groundPoint(ray: NdsPickRay): Pair<Float, Float>? {
    val nx = ray.origin[0]
    val ny = ray.origin[1]
    val nz = ray.origin[2]
    val dx = ray.direction[0]
    val dy = ray.direction[1]
    val dz = ray.direction[2]
    if (dy == 0.0) return null
    val t = -ny / dy
    if (t < 0.0) return null
    val wx = nx + dx * t
    val wz = nz + dz * t
    val xf = modelXformCache ?: modelXform()
    val gx =
        if (xf != null) ((wx - 16.0) / xf.scale + xf.cx).toFloat()
        else wx.toFloat()
    val gz =
        if (xf != null) ((wz - 16.0) / xf.scale + xf.cz).toFloat()
        else wz.toFloat()
    return gx to gz
  }

  private fun groundCell(point: Pair<Float, Float>): Pair<Int, Int>? {
    val g = grid ?: return null
    val gx = kotlin.math.floor(point.first.toDouble()).toInt()
    val gz = kotlin.math.floor(point.second.toDouble()).toInt()
    return (gx to gz).takeIf { gx in 0 until g.cols && gz in 0 until g.rows }
  }

  private fun modelGroup(ray: NdsPickRay, prefix: String, excludedPrefix: String? = null): String? {
    val xf = modelXform() ?: return null
    val scale = xf.scale.toDouble()
    if (scale == 0.0) return null
    val modelRay = NdsPickRay(
        doubleArrayOf(
            (ray.origin[0] - 16.0) / scale + xf.cx,
            ray.origin[1] / scale + xf.groundY,
            (ray.origin[2] - 16.0) / scale + xf.cz,
        ),
        doubleArrayOf(
            ray.direction[0] / scale,
            ray.direction[1] / scale,
            ray.direction[2] / scale,
        ),
    )
    return pickNdsModelGroup(modelTriangles, modelRay, prefix, excludedPrefix)
  }
}
