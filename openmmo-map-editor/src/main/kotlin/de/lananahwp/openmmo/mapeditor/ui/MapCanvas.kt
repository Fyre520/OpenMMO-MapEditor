package de.lananahwp.openmmo.mapeditor.ui

import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Font
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JPanel

enum class MapEventType {
  PERSON,
  SCRIPT,
  TRIGGER,
  WARP,
}

data class MapEventMarker(val x: Int, val y: Int, val type: MapEventType)

/** Displays and edits the rendered map. */
class MapCanvas(
    private val onPaintBlock: (x: Int, y: Int, metatileId: Int) -> Unit,
    private val onHover: (x: Int, y: Int) -> Unit,
    private val onPickBlock: (x: Int, y: Int) -> Unit,
) : JPanel() {

  var mapImage: BufferedImage? = null
    set(value) {
      field = value
      revalidate()
      repaint()
    }

  var zoom: Double = 1.0
    set(value) {
      field = value.coerceIn(0.25, 8.0)
      revalidate()
      repaint()
      onZoomChanged?.invoke()
    }

  var onZoomChanged: (() -> Unit)? = null

  /** Metatile id currently painted. */
  var brush: Int = 0

  /** Whether the per-block grid overlay is drawn. */
  var showGrid: Boolean = true

  var showEventOverlay: Boolean = false
    set(value) {
      field = value
      repaint()
    }

  var eventMarkers: List<MapEventMarker> = emptyList()
    set(value) {
      field = value
      repaint()
    }

  var visibleEventTypes: Set<MapEventType> = MapEventType.entries.toSet()
    set(value) {
      field = value
      repaint()
    }

  /** Map dimensions in blocks. */
  var blockWidth = 0
  var blockHeight = 0

  private var painting = false
  private val paintedBlocks = HashSet<Pair<Int, Int>>()

  init {
    background = Color(30, 30, 30)
    addMouseListener(
        object : MouseAdapter() {
          override fun mousePressed(e: MouseEvent) {
            if (e.button == MouseEvent.BUTTON1) {
              painting = true
              paintedBlocks.clear()
              paintAt(e.x, e.y)
            } else if (e.button == MouseEvent.BUTTON3) {
              blockAt(e.x, e.y)?.let { onPickBlock(it.first, it.second) }
            }
          }

          override fun mouseReleased(e: MouseEvent) {
            painting = false
            paintedBlocks.clear()
          }
        })
    addMouseMotionListener(
        object : MouseAdapter() {
          override fun mouseDragged(e: MouseEvent) {
            if (painting) paintAt(e.x, e.y)
          }

          override fun mouseMoved(e: MouseEvent) {
            blockAt(e.x, e.y)?.let { onHover(it.first, it.second) }
          }
        })
    addMouseWheelListener { e ->
      if (e.isControlDown) {
        zoom = if (e.wheelRotation < 0) zoom * 1.15 else zoom / 1.15
      }
    }
  }

  private fun blockAt(px: Int, py: Int): Pair<Int, Int>? {
    val img = mapImage ?: return null
    val bx = ((px / zoom).toInt() / 16)
    val by = ((py / zoom).toInt() / 16)
    if (bx in 0 until blockWidth && by in 0 until blockHeight) return bx to by
    return null
  }

  private fun paintAt(px: Int, py: Int) {
    val (bx, by) = blockAt(px, py) ?: return
    if (!paintedBlocks.add(bx to by)) return
    onPaintBlock(bx, by, brush)
  }

  /** Updates one cached map block. */
  fun updateBlock(x: Int, y: Int, blockImage: BufferedImage) {
    val img = mapImage ?: return
    val g = img.createGraphics()
    g.drawImage(blockImage, x * 16, y * 16, null)
    g.dispose()
    repaint((x * 16 * zoom).toInt(), (y * 16 * zoom).toInt(), (16 * zoom).toInt(), (16 * zoom).toInt())
  }

  override fun getPreferredSize(): Dimension {
    val img = mapImage
    return if (img != null) Dimension((img.width * zoom).toInt(), (img.height * zoom).toInt())
    else Dimension(600, 400)
  }

  override fun paintComponent(g: Graphics) {
    super.paintComponent(g)
    val img = mapImage ?: return
    val g2 = g as Graphics2D
    g2.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
    g2.drawImage(img, 0, 0, (img.width * zoom).toInt(), (img.height * zoom).toInt(), null)
    if (showGrid) {
      g2.color = Color(255, 255, 255, 40)
      val step = (16 * zoom).toInt().coerceAtLeast(1)
      for (x in step until (img.width * zoom).toInt() step step) g2.drawLine(x, 0, x, (img.height * zoom).toInt())
      for (y in step until (img.height * zoom).toInt() step step) g2.drawLine(0, y, (img.width * zoom).toInt(), y)
    }
    if (showEventOverlay) drawEventOverlay(g2)
  }

  private fun drawEventOverlay(g: Graphics2D) {
    val size = (16 * zoom).toInt().coerceAtLeast(1)
    val oldFont = g.font
    g.font = oldFont.deriveFont(Font.BOLD, (size * 0.72f).coerceAtLeast(9f))
    for (marker in eventMarkers) {
      if (marker.type !in visibleEventTypes) continue
      if (marker.x !in 0 until blockWidth || marker.y !in 0 until blockHeight) continue
      val px = marker.x * size
      val py = marker.y * size
      val (label, color) =
          when (marker.type) {
            MapEventType.PERSON -> "P" to Color(55, 175, 80, 160)
            MapEventType.SCRIPT -> "S" to Color(220, 165, 30, 170)
            MapEventType.TRIGGER -> "T" to Color(30, 155, 190, 165)
            MapEventType.WARP -> "W" to Color(128, 45, 170, 165)
          }
      g.color = color
      g.fillRect(px, py, size, size)
      g.color = Color.WHITE
      val metrics = g.fontMetrics
      val tx = px + (size - metrics.stringWidth(label)) / 2
      val ty = py + (size - metrics.height) / 2 + metrics.ascent
      g.drawString(label, tx, ty)
    }
    g.font = oldFont
  }
}
