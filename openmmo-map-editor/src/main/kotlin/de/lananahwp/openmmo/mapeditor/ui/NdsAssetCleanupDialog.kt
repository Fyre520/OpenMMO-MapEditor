package de.lananahwp.openmmo.mapeditor.ui

import de.lananahwp.openmmo.mapeditor.project.NdsProject
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.KeyStroke
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel

internal enum class NdsCleanupAssetKind { PROP, TILE }

internal data class NdsCleanupAssetEntry(
    val project: NdsProject,
    val kind: NdsCleanupAssetKind,
    val key: String,
    val label: String,
    var checked: Boolean = false,
)

/** Selectable two-column review of assets proven unused by every editor map. */
internal class NdsAssetCleanupDialog(
    owner: JFrame,
    private val loadEntries: () -> List<NdsCleanupAssetEntry>,
    private val canUndo: () -> Boolean,
    private val deleteEntries: (List<NdsCleanupAssetEntry>) -> Unit,
    private val undoLastDelete: () -> Unit,
) : JDialog(owner, "Clear Assets", true) {
  private val propModel = DefaultListModel<NdsCleanupAssetEntry>()
  private val tileModel = DefaultListModel<NdsCleanupAssetEntry>()
  private val propsLabel = JLabel()
  private val tilesLabel = JLabel()
  private val status = JLabel("Only assets unused across all saved and currently open maps are shown.")
  private val deleteButton = JButton("Delete")
  private val undoButton = JButton("Undo Last Delete")

  init {
    defaultCloseOperation = DISPOSE_ON_CLOSE
    minimumSize = Dimension(760, 420)
    preferredSize = Dimension(920, 560)

    val propList = assetList(propModel)
    val tileList = assetList(tileModel)
    val columns = JPanel(GridLayout(1, 2, 12, 0)).apply {
      border = BorderFactory.createEmptyBorder(10, 10, 6, 10)
      add(assetColumn(propsLabel, propList))
      add(assetColumn(tilesLabel, tileList))
    }
    add(columns, BorderLayout.CENTER)

    val selectAll = JButton("Select All").apply {
      addActionListener {
        allEntries().forEach { it.checked = true }
        repaintLists(propList, tileList)
      }
    }
    val unselectAll = JButton("Unselect All").apply {
      addActionListener {
        allEntries().forEach { it.checked = false }
        repaintLists(propList, tileList)
      }
    }
    deleteButton.addActionListener { deleteSelected() }
    undoButton.addActionListener {
      try {
        undoLastDelete()
        reload()
        status.text = "Restored the assets from the last deletion."
      } catch (failure: Throwable) {
        showFailure("Undo failed", failure)
      }
    }
    val close = JButton("Close").apply { addActionListener { dispose() } }
    val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
      add(selectAll)
      add(unselectAll)
      add(undoButton)
      add(deleteButton)
      add(close)
    }
    add(JPanel(BorderLayout(8, 6)).apply {
      border = BorderFactory.createEmptyBorder(4, 10, 10, 10)
      add(status, BorderLayout.CENTER)
      add(buttons, BorderLayout.EAST)
    }, BorderLayout.SOUTH)

    reload()
    pack()
    setLocationRelativeTo(owner)
  }

  private fun assetColumn(label: JLabel, list: JList<NdsCleanupAssetEntry>) =
      JPanel(BorderLayout(0, 6)).apply {
        border = BorderFactory.createEtchedBorder()
        add(JPanel(BorderLayout()).apply {
          border = BorderFactory.createEmptyBorder(7, 8, 0, 8)
          add(label, BorderLayout.WEST)
        }, BorderLayout.NORTH)
        add(JScrollPane(list), BorderLayout.CENTER)
      }

  private fun assetList(model: DefaultListModel<NdsCleanupAssetEntry>) =
      JList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = AssetRenderer()
        addMouseListener(object : MouseAdapter() {
          override fun mousePressed(event: MouseEvent) {
            val index = locationToIndex(event.point)
            if (index < 0 || !getCellBounds(index, index).contains(event.point)) return
            model.get(index).checked = !model.get(index).checked
            repaint(getCellBounds(index, index))
            updateButtons()
          }
        })
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "toggle-check")
        actionMap.put("toggle-check", object : AbstractAction() {
          override fun actionPerformed(event: java.awt.event.ActionEvent?) {
            val index = selectedIndex
            if (index < 0) return
            model.get(index).checked = !model.get(index).checked
            repaint(getCellBounds(index, index))
            updateButtons()
          }
        })
      }

  private fun reload() {
    propModel.clear()
    tileModel.clear()
    for (entry in loadEntries()) {
      when (entry.kind) {
        NdsCleanupAssetKind.PROP -> propModel.addElement(entry)
        NdsCleanupAssetKind.TILE -> tileModel.addElement(entry)
      }
    }
    propsLabel.text = "Unused extracted props (${propModel.size})"
    tilesLabel.text = "Unused custom tiles (${tileModel.size})"
    updateButtons()
  }

  private fun deleteSelected() {
    val selected = allEntries().filter { it.checked }
    if (selected.isEmpty()) return
    val props = selected.count { it.kind == NdsCleanupAssetKind.PROP }
    val tiles = selected.size - props
    val answer = JOptionPane.showConfirmDialog(
        this,
        "Are you sure you want to delete ${countLabel(props, "prop")} and " +
            "${countLabel(tiles, "tile")}?\n\nYou can restore them with Undo Last Delete.",
        "Delete unused assets?",
        JOptionPane.YES_NO_OPTION,
        JOptionPane.WARNING_MESSAGE,
    )
    if (answer != JOptionPane.YES_OPTION) return
    try {
      deleteEntries(selected)
      reload()
      status.text = "Deleted ${countLabel(props, "prop")} and ${countLabel(tiles, "tile")}."
    } catch (failure: Throwable) {
      showFailure("Could not delete assets", failure)
    }
  }

  private fun allEntries(): List<NdsCleanupAssetEntry> = buildList {
    for (i in 0 until propModel.size) add(propModel.get(i))
    for (i in 0 until tileModel.size) add(tileModel.get(i))
  }

  private fun repaintLists(vararg lists: JList<NdsCleanupAssetEntry>) {
    lists.forEach(Component::repaint)
    updateButtons()
  }

  private fun updateButtons() {
    deleteButton.isEnabled = allEntries().any { it.checked }
    undoButton.isEnabled = canUndo()
  }

  private fun showFailure(title: String, failure: Throwable) {
    JOptionPane.showMessageDialog(
        this, failure.message ?: failure.toString(), title, JOptionPane.ERROR_MESSAGE)
  }

  private fun countLabel(count: Int, noun: String) = "$count $noun${if (count == 1) "" else "s"}"

  private class AssetRenderer : JCheckBox(), ListCellRenderer<NdsCleanupAssetEntry> {
    init {
      isOpaque = true
      border = BorderFactory.createEmptyBorder(5, 7, 5, 7)
    }

    override fun getListCellRendererComponent(
        list: JList<out NdsCleanupAssetEntry>,
        value: NdsCleanupAssetEntry,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
      text = value.label
      this.isSelected = value.checked
      background = if (isSelected) list.selectionBackground else list.background
      foreground = if (isSelected) list.selectionForeground else list.foreground
      isEnabled = list.isEnabled
      return this
    }
  }
}
