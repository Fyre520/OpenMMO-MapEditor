package de.lananahwp.openmmo.mapeditor.ui

import de.lananahwp.openmmo.mapeditor.core.BaseSource
import de.lananahwp.openmmo.mapeditor.core.MapRenderer
import de.lananahwp.openmmo.mapeditor.model.MetatileBrush
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSlider
import javax.swing.JSplitPane
import kotlin.math.ceil

/** Selects metatiles from primary and secondary sheets. */
class MetatileSelector(private val source: BaseSource) {

  companion object {
    private const val METATILES_PER_ROW = 8
  }

  private val renderer = MapRenderer(source)
  private var primary = ""
  private var secondary = ""
  private var cellSize = 24
  private val cols = METATILES_PER_ROW

  var selectedId: Int = 0
    private set

  var selectedBrush: MetatileBrush = MetatileBrush.single(0)
    private set

  private val selectionListeners = mutableListOf<() -> Unit>()
  private val primarySheet = Sheet()
  private val secondarySheet = Sheet()
  private val primaryLabel = JLabel("  Primary")
  private val secondaryLabel = JLabel("  Secondary")

  fun addSelectionListener(l: () -> Unit) {
    selectionListeners += l
  }

  fun selectMetatile(id: Int) {
    if (id in primarySheet.ids || id in secondarySheet.ids) selectSingle(id)
  }

  /** Loads tilesets and resets selection. */
  fun setTilesets(primary: String, secondary: String) {
    this.primary = primary
    this.secondary = secondary
    val primaryCount = source.primaryMetatileCount
    val secondaryCount = source.metatileCount(secondary)
    primarySheet.ids = (0 until primaryCount).toList()
    secondarySheet.ids = (primaryCount until primaryCount + secondaryCount).toList()
    primarySheet.clearCache()
    secondarySheet.clearCache()
    primaryLabel.text = "  ${primaryTitle()}"
    secondaryLabel.text = "  ${secondaryTitle()}"
    selectSingle(0)
  }

  fun component(): JComponent {
    val panel = JPanel(BorderLayout())

    val slider = JSlider(12, 60, cellSize)
    slider.majorTickSpacing = 12
    slider.paintTicks = true
    slider.addChangeListener {
      cellSize = slider.value
      primarySheet.refresh()
      secondarySheet.refresh()
    }
    val sliderRow = JPanel(BorderLayout())
    sliderRow.add(JLabel("  Metatile zoom  "), BorderLayout.WEST)
    sliderRow.add(slider, BorderLayout.CENTER)
    panel.add(sliderRow, BorderLayout.NORTH)

    val split =
        JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            section(primaryLabel, JScrollPane(primarySheet)),
            section(secondaryLabel, JScrollPane(secondarySheet)))
    split.resizeWeight = 0.5
    panel.add(split, BorderLayout.CENTER)
    return panel
  }

  private fun primaryTitle(): String =
      if (primary.isEmpty()) "Primary" else "Primary — $primary"

  private fun secondaryTitle(): String =
      if (secondary.isEmpty()) "Secondary" else "Secondary — $secondary"

  private fun section(label: JLabel, content: JComponent): JComponent {
    label.apply {
      border = BorderFactory.createEmptyBorder(4, 0, 2, 0)
    }
    val p = JPanel(BorderLayout())
    p.add(label, BorderLayout.NORTH)
    p.add(content, BorderLayout.CENTER)
    return p
  }

  private fun selectSingle(id: Int) {
    selectedId = id
    selectedBrush = MetatileBrush.single(id)
    primarySheet.selectOnly(id)
    secondarySheet.selectOnly(id)
    primarySheet.repaint()
    secondarySheet.repaint()
    selectionListeners.forEach { it() }
  }

  private fun selectRange(sheet: Sheet, start: Int, end: Int) {
    if (start !in sheet.ids.indices || end !in sheet.ids.indices) return
    val left = minOf(start % cols, end % cols)
    val right = maxOf(start % cols, end % cols)
    val top = minOf(start / cols, end / cols)
    val bottom = maxOf(start / cols, end / cols)
    val width = right - left + 1
    val height = bottom - top + 1
    val blocks = IntArray(width * height)
    val selected = mutableSetOf<Int>()
    for (y in 0 until height) {
      for (x in 0 until width) {
        val index = (top + y) * cols + left + x
        if (index !in sheet.ids.indices) return
        selected += index
        blocks[y * width + x] = sheet.ids[index]
      }
    }
    selectedId = blocks.first()
    selectedBrush = MetatileBrush(width, height, blocks)
    primarySheet.selectedCells = if (sheet === primarySheet) selected else emptySet()
    secondarySheet.selectedCells = if (sheet === secondarySheet) selected else emptySet()
    primarySheet.repaint()
    secondarySheet.repaint()
    selectionListeners.forEach { it() }
  }

  private inner class Sheet : JPanel() {
    var ids: List<Int> = emptyList()
    var selectedCells: Set<Int> = emptySet()
    private val gap = 2
    private var cellCache = HashMap<Int, java.awt.Image>()
    private var cachedCellSize = 0
    private var selectionAnchor = -1

    init {
      background = Color(46, 46, 46)
      border = BorderFactory.createLineBorder(Color(70, 70, 70))
      toolTipText = " "
      val mouse =
          object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
              if (e.button != MouseEvent.BUTTON1) return
              val idx = hitTest(e.x, e.y)
              if (idx !in ids.indices) return
              selectionAnchor = idx
              selectRange(this@Sheet, idx, idx)
            }

            override fun mouseDragged(e: MouseEvent) {
              if (selectionAnchor !in ids.indices) return
              val idx = hitTest(e.x, e.y)
              if (idx in ids.indices) selectRange(this@Sheet, selectionAnchor, idx)
            }

            override fun mouseReleased(e: MouseEvent) {
              selectionAnchor = -1
            }
          }
      addMouseListener(mouse)
      addMouseMotionListener(mouse)
    }

    fun selectOnly(id: Int) {
      val index = ids.indexOf(id)
      selectedCells = if (index >= 0) setOf(index) else emptySet()
    }

    fun clearCache() {
      cellCache.clear()
      cachedCellSize = 0
    }

    fun refresh() {
      cellCache.clear()
      cachedCellSize = 0
      revalidate()
      repaint()
    }

    override fun getPreferredSize(): Dimension {
      val c = maxOf(cols, 1)
      val rows = ceil(ids.size.toDouble() / c).toInt()
      return Dimension(c * (cellSize + gap) + gap, rows * (cellSize + gap) + gap)
    }

    override fun getToolTipText(event: MouseEvent): String? {
      val idx = hitTest(event.x, event.y)
      return if (idx in ids.indices) "0x%03X".format(ids[idx]) else null
    }

    private fun hitTest(x: Int, y: Int): Int {
      if (x < gap || y < gap) return -1
      val w = cellSize + gap
      val cx = (x - gap) / w
      val cy = (y - gap) / w
      if (cx < 0 || cy < 0 || cx >= cols) return -1
      return cy * cols + cx
    }

    private fun cellImage(id: Int): java.awt.Image {
      if (cachedCellSize != cellSize) {
        cellCache.clear()
        cachedCellSize = cellSize
      }
      return cellCache.getOrPut(id) {
        val img = renderer.renderMetatile(primary, secondary, id)
        val scaled = BufferedImage(cellSize, cellSize, BufferedImage.TYPE_INT_ARGB)
        val g = scaled.createGraphics()
        g.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        g.drawImage(img, 0, 0, cellSize, cellSize, null)
        g.dispose()
        scaled
      }
    }

    override fun paintComponent(g: Graphics) {
      super.paintComponent(g)
      if (primary.isEmpty()) return
      val g2 = g as Graphics2D
      val w = cellSize + gap
      val clip = g.clipBounds
      // Draw visible cells only.
      val firstCol = maxOf(0, (clip.x - gap) / w)
      val lastCol = minOf(cols - 1, (clip.x + clip.width - gap) / w)
      val firstRow = maxOf(0, (clip.y - gap) / w)
      val lastRow = minOf((ids.size - 1) / cols, (clip.y + clip.height - gap) / w)
      for (row in firstRow..lastRow) {
        for (col in firstCol..lastCol) {
          val i = row * cols + col
          if (i >= ids.size) break
          val id = ids[i]
          val x = gap + col * w
          val y = gap + row * w
          g2.drawImage(cellImage(id), x, y, null)
          if (i in selectedCells) {
            g2.color = Color(255, 210, 80)
            g2.drawRect(x - 1, y - 1, cellSize + 1, cellSize + 1)
          }
        }
      }
    }
  }
}
