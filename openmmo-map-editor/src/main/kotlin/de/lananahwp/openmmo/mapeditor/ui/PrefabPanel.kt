package de.lananahwp.openmmo.mapeditor.ui

import de.lananahwp.openmmo.mapeditor.core.BaseSource
import de.lananahwp.openmmo.mapeditor.core.MapRenderer
import de.lananahwp.openmmo.mapeditor.model.MetatileBrush
import de.lananahwp.openmmo.mapeditor.project.MapPrefab
import de.lananahwp.openmmo.mapeditor.project.PrefabStore
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.UUID
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane

class PrefabPanel(
    private val source: BaseSource,
    projectDir: File,
    private val selectionProvider: () -> MetatileBrush,
    private val attributeProvider: () -> Pair<Int, Int>,
    private val onSelected: (MetatileBrush) -> Unit,
) : JPanel(BorderLayout()) {
  private val renderer = MapRenderer(source)
  private val store = PrefabStore(projectDir)
  private val list = JPanel()
  private var primary = ""
  private var secondary = ""

  init {
    val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
    buttons.add(JButton("Create from selection").apply { addActionListener { createPrefab() } })
    buttons.add(JButton("Import…").apply { addActionListener { importPrefabs() } })
    add(buttons, BorderLayout.NORTH)
    list.layout = BoxLayout(list, BoxLayout.Y_AXIS)
    add(JScrollPane(list), BorderLayout.CENTER)
  }

  fun setTilesets(primary: String, secondary: String) {
    this.primary = primary
    this.secondary = secondary
    rebuild()
  }

  private fun createPrefab() {
    if (primary.isEmpty()) return
    val name = JOptionPane.showInputDialog(this, "Prefab name:", "Create Prefab", JOptionPane.PLAIN_MESSAGE)
        ?.trim() ?: return
    val selection = selectionProvider()
    val (collision, elevation) = attributeProvider()
    val blocks =
        IntArray(selection.blocks.size) { index ->
          val value = selection.blocks[index]
          if (value < 0) value
          else (value and 0x3FF) or (collision shl 10) or (elevation shl 12)
        }
    val usesPrimary = blocks.any { it >= 0 && (it and 0x3FF) < source.primaryMetatileCount }
    val usesSecondary = blocks.any { it >= 0 && (it and 0x3FF) >= source.primaryMetatileCount }
    store.add(
        MapPrefab(
            UUID.randomUUID().toString(),
            name,
            if (usesPrimary) primary else "",
            if (usesSecondary) secondary else "",
            MetatileBrush(selection.width, selection.height, blocks, true),
        ))
    rebuild()
  }

  private fun importPrefabs() {
    val chooser = JFileChooser().apply {
      dialogTitle = "Import Porymap prefabs.json"
      fileSelectionMode = JFileChooser.FILES_ONLY
    }
    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
    try {
      val count = store.import(chooser.selectedFile)
      rebuild()
      JOptionPane.showMessageDialog(this, "Imported $count prefabs.")
    } catch (error: Exception) {
      JOptionPane.showMessageDialog(
          this,
          error.message ?: error.toString(),
          "Import failed",
          JOptionPane.ERROR_MESSAGE,
      )
    }
  }

  private fun rebuild() {
    list.removeAll()
    val prefabs = store.compatible(primary, secondary)
    if (prefabs.isEmpty()) {
      list.add(JLabel("No compatible prefabs yet.").apply {
        border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
      })
    }
    for (prefab in prefabs) list.add(prefabRow(prefab))
    list.revalidate()
    list.repaint()
  }

  private fun prefabRow(prefab: MapPrefab): JPanel {
    val row = JPanel(BorderLayout(6, 2))
    row.border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
    val choose = JButton(prefab.name.ifEmpty { "Unnamed prefab" }, preview(prefab.brush))
    choose.horizontalAlignment = JButton.LEFT
    choose.addActionListener { onSelected(prefab.brush) }
    row.add(choose, BorderLayout.CENTER)
    row.add(JButton("Delete").apply {
      addActionListener {
        val answer = JOptionPane.showConfirmDialog(
            this@PrefabPanel,
            "Delete '${prefab.name}'?",
            "Delete Prefab",
            JOptionPane.YES_NO_OPTION,
        )
        if (answer == JOptionPane.YES_OPTION) {
          store.remove(prefab.id)
          rebuild()
        }
      }
    }, BorderLayout.EAST)
    row.maximumSize = Dimension(Int.MAX_VALUE, maxOf(48, prefab.brush.height * 32 + 10))
    return row
  }

  private fun preview(brush: MetatileBrush): ImageIcon {
    val scale = 2
    val image = BufferedImage(brush.width * 16, brush.height * 16, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    for (y in 0 until brush.height) {
      for (x in 0 until brush.width) {
        val block = brush.blockAt(x, y)
        if (block >= 0) {
          graphics.drawImage(renderer.renderMetatile(primary, secondary, block and 0x3FF), x * 16, y * 16, null)
        }
      }
    }
    graphics.dispose()
    val scaled = BufferedImage(image.width * scale, image.height * scale, BufferedImage.TYPE_INT_ARGB)
    val output = scaled.createGraphics()
    output.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION,
        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
    )
    output.drawImage(image, 0, 0, scaled.width, scaled.height, null)
    output.dispose()
    return ImageIcon(scaled)
  }
}
