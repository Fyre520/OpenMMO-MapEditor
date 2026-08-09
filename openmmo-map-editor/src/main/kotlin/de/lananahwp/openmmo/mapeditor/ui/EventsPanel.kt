package de.lananahwp.openmmo.mapeditor.ui

import de.lananahwp.openmmo.mapeditor.json.Json
import de.lananahwp.openmmo.mapeditor.model.EditorMap
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.table.DefaultTableModel

/** Displays and edits map events. */
class EventsPanel : JPanel(BorderLayout()) {

  private val warpsModel = ReadOnlyTableModel(arrayOf("x", "y", "elev", "dest", "destWarp"))
  private val objectsModel =
      ReadOnlyTableModel(arrayOf("x", "y", "elev", "graphics", "movement", "flag"))
  private val bgModel = ReadOnlyTableModel(arrayOf("x", "y", "facing", "script"))
  private val coordModel =
      ReadOnlyTableModel(arrayOf("x", "y", "elev", "var", "value", "script"))

  private val warpsTable = JTable(warpsModel)
  private val objectsTable = JTable(objectsModel)
  private val bgTable = JTable(bgModel)
  private val coordTable = JTable(coordModel)

  var onAddWarp: (() -> Unit)? = null
  var onRemoveWarp: ((Int) -> Unit)? = null
  var onConnectWarp: ((Int) -> Unit)? = null
  var onEditEvent: ((EventKind, Int) -> Unit)? = null

  init {
    warpsTable.addMouseListener(
        object : MouseAdapter() {
          override fun mouseClicked(e: MouseEvent) {
            if (e.clickCount == 2 && warpsTable.selectedRow >= 0) {
              onConnectWarp?.invoke(warpsTable.selectedRow)
            }
          }
        })
    val warpsPanel = JPanel(BorderLayout())
    val warpButtons = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
    warpButtons.add(JButton("Add Warp").apply { addActionListener { onAddWarp?.invoke() } })
    warpButtons.add(JButton("Remove Warp").apply {
      addActionListener {
        val r = warpsTable.selectedRow
        if (r >= 0) onRemoveWarp?.invoke(r)
      }
    })
    warpButtons.add(JButton("Edit Warp…").apply {
      addActionListener {
        val row = warpsTable.selectedRow
        if (row >= 0) onEditEvent?.invoke(EventKind.WARP, row)
      }
    })
    warpButtons.add(JButton("Connect Warp…").apply {
      addActionListener {
        val r = warpsTable.selectedRow
        if (r >= 0) onConnectWarp?.invoke(r)
      }
    })
    warpsPanel.add(warpButtons, BorderLayout.NORTH)
    warpsPanel.add(JScrollPane(warpsTable), BorderLayout.CENTER)

    val tabs = JTabbedPane()
    tabs.addTab("Warps", warpsPanel)
    tabs.addTab("Objects", eventPanel(objectsTable, EventKind.OBJECT))
    tabs.addTab("Bg Events", eventPanel(bgTable, EventKind.BACKGROUND))
    tabs.addTab("Coord Events", eventPanel(coordTable, EventKind.COORDINATE))
    add(tabs, BorderLayout.CENTER)
  }

  private fun eventPanel(table: JTable, kind: EventKind): JPanel {
    table.addMouseListener(
        object : MouseAdapter() {
          override fun mouseClicked(e: MouseEvent) {
            if (e.clickCount == 2 && table.selectedRow >= 0) {
              onEditEvent?.invoke(kind, table.selectedRow)
            }
          }
        })
    val panel = JPanel(BorderLayout())
    val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
    buttons.add(
        JButton("Edit Selected…").apply {
          addActionListener {
            val row = table.selectedRow
            if (row >= 0) onEditEvent?.invoke(kind, row)
          }
        })
    panel.add(buttons, BorderLayout.NORTH)
    panel.add(JScrollPane(table), BorderLayout.CENTER)
    return panel
  }

  fun setMap(map: EditorMap) {
    warpsModel.setRowCount(0)
    for (w in map.warps) {
      warpsModel.addRow(
          arrayOf<Any>(
              w.int("x") ?: 0,
              w.int("y") ?: 0,
              w.int("elevation") ?: 0,
              w.str("dest_map") ?: "",
              w.str("dest_warp_id") ?: w.int("dest_warp_id") ?: ""))
    }
    objectsModel.setRowCount(0)
    for (o in map.objects) {
      objectsModel.addRow(
          arrayOf<Any>(
              o.int("x") ?: 0,
              o.int("y") ?: 0,
              o.int("elevation") ?: 0,
              o.str("graphics_id") ?: "",
              o.str("movement_type") ?: "",
              o.str("flag") ?: ""))
    }
    bgModel.setRowCount(0)
    for (b in map.bgEvents) {
      bgModel.addRow(
          arrayOf<Any>(
              b.int("x") ?: 0,
              b.int("y") ?: 0,
              b.str("player_facing_dir") ?: "",
              b.str("script") ?: ""))
    }
    coordModel.setRowCount(0)
    for (e in map.coordEvents) {
      coordModel.addRow(
          arrayOf<Any>(
              e.int("x") ?: 0,
              e.int("y") ?: 0,
              e.int("elevation") ?: 0,
              e.str("var") ?: "",
              e.str("var_value") ?: e.int("var_value") ?: "",
              e.str("script") ?: ""))
    }
  }
}

enum class EventKind {
  WARP,
  OBJECT,
  BACKGROUND,
  COORDINATE,
}

private class ReadOnlyTableModel(columns: Array<String>) : DefaultTableModel(columns, 0) {
  override fun isCellEditable(row: Int, column: Int): Boolean = false
}
