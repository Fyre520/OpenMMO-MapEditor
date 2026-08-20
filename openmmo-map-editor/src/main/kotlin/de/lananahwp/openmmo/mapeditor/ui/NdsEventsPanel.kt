package de.lananahwp.openmmo.mapeditor.ui

import de.lananahwp.openmmo.mapeditor.model.NdsBgEvent
import de.lananahwp.openmmo.mapeditor.model.NdsMap
import de.lananahwp.openmmo.mapeditor.model.NdsObject
import de.lananahwp.openmmo.mapeditor.model.NdsTrigger
import de.lananahwp.openmmo.mapeditor.model.NdsWarp
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.table.DefaultTableModel

/** Edits the events attached to a Gen 4 map. */
class NdsEventsPanel(private val onChange: (NdsMap) -> Unit) : JPanel(BorderLayout()) {

  private var map: NdsMap? = null

  private val objectsTable = JTable()
  private val warpsTable = JTable()
  private val triggersTable = JTable()
  private val bgsTable = JTable()

  init {
    val tabs = JTabbedPane()
    tabs.addTab("Objects", tablePane(objectsTable, "Add Object", "Edit", "Remove") { addObject() })
    tabs.addTab("Warps", tablePane(warpsTable, "Add Warp", "Edit", "Remove") { addWarp() })
    tabs.addTab("Triggers", tablePane(triggersTable, "Add Trigger", "Edit", "Remove") { addTrigger() })
    tabs.addTab("BG Events", tablePane(bgsTable, "Add BG", "Edit", "Remove") { addBg() })
    add(tabs, BorderLayout.CENTER)
  }

  private fun tablePane(
      table: JTable,
      addLabel: String,
      editLabel: String,
      removeLabel: String,
      onAdd: () -> Unit,
  ): JPanel {
    table.setShowGrid(true)
    table.fillsViewportHeight = true
    val panel = JPanel(BorderLayout())
    panel.add(JScrollPane(table), BorderLayout.CENTER)
    val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4))
    buttons.add(JButton(addLabel).apply {
      preferredSize = Dimension(110, 26)
      addActionListener { onAdd() }
    })
    buttons.add(JButton(editLabel).apply {
      preferredSize = Dimension(80, 26)
      addActionListener { editSelected(table) }
    })
    buttons.add(JButton(removeLabel).apply {
      preferredSize = Dimension(90, 26)
      addActionListener { removeSelected(table) }
    })
    panel.add(buttons, BorderLayout.SOUTH)
    return panel
  }

  fun setMap(map: NdsMap) {
    this.map = map
    refresh()
  }

  private fun refresh() {
    val map = this.map ?: return
    objectsTable.model =
        DefaultTableModel(
            map.events.objects.map {
              arrayOf<Any>(it.id, it.spriteId, it.movement, it.scriptId, it.x, it.z)
            }.toTypedArray(),
            arrayOf("ID", "Sprite", "Mov", "Script", "X", "Z"),
        )
    warpsTable.model =
        DefaultTableModel(
            map.events.warps.map { arrayOf<Any>(it.x, it.z, it.header, it.anchor) }.toTypedArray(),
            arrayOf("X", "Z", "Destination", "Anchor"),
        )
    triggersTable.model =
        DefaultTableModel(
            map.events.triggers.map {
              arrayOf<Any>(it.scriptId, it.x, it.z, it.w, it.h, it.variable, it.value)
            }.toTypedArray(),
            arrayOf("Script", "X", "Z", "W", "H", "Var", "Val"),
        )
    bgsTable.model =
        DefaultTableModel(
            map.events.bgEvents.map { arrayOf<Any>(it.scriptId, it.type, it.x, it.z, it.dir) }.toTypedArray(),
            arrayOf("Script", "Type", "X", "Z", "Dir"),
        )
  }

  private fun editSelected(table: JTable) {
    val map = this.map ?: return
    val row = table.selectedRow
    when (table) {
      objectsTable -> if (row >= 0) editObject(map.events.objects[row], row)
      warpsTable -> if (row >= 0) editWarp(map.events.warps[row], row)
      triggersTable -> if (row >= 0) editTrigger(map.events.triggers[row], row)
      bgsTable -> if (row >= 0) editBg(map.events.bgEvents[row], row)
    }
  }

  private fun removeSelected(table: JTable) {
    val map = this.map ?: return
    val row = table.selectedRow
    when (table) {
      objectsTable -> if (row >= 0) { map.events.objects.removeAt(row); commit() }
      warpsTable -> if (row >= 0) { map.events.warps.removeAt(row); commit() }
      triggersTable -> if (row >= 0) { map.events.triggers.removeAt(row); commit() }
      bgsTable -> if (row >= 0) { map.events.bgEvents.removeAt(row); commit() }
    }
  }

  private fun addObject() {
    val map = this.map ?: return
    val obj = NdsObject()
    val result = editObject(obj, map.events.objects.size)
    if (result) commit()
  }

  private fun editObject(obj: NdsObject, index: Int): Boolean {
    val sprite = JTextField(obj.spriteId, 26)
    val movement = JTextField(obj.movement.toString(), 26)
    val type = JTextField(obj.type.toString(), 26)
    val flag = JTextField(obj.eventFlag, 26)
    val script = JTextField(obj.scriptId, 26)
    val facing = JTextField(obj.facingDirection.toString(), 26)
    val x = JTextField(obj.x.toString(), 26)
    val z = JTextField(obj.z.toString(), 26)
    val y = JTextField(obj.y.toString(), 26)
    val panel = JPanel(GridLayout(0, 2, 6, 4))
    fun row(label: String, field: JTextField) {
      panel.add(JLabel(label)); panel.add(field)
    }
    row("Sprite", sprite); row("Movement", movement); row("Type", type)
    row("Event flag", flag); row("Script", script); row("Facing", facing)
    row("X", x); row("Z", z); row("Y", y)
    if (JOptionPane.showConfirmDialog(this, panel, "Edit Object", JOptionPane.OK_CANCEL_OPTION) !=
        JOptionPane.OK_OPTION) return false
    obj.spriteId = sprite.text.trim()
    obj.movement = spriteInt(movement, obj.movement)
    obj.type = spriteInt(type, obj.type)
    obj.eventFlag = flag.text.trim()
    obj.scriptId = script.text.trim()
    obj.facingDirection = spriteInt(facing, obj.facingDirection)
    obj.x = spriteInt(x, obj.x)
    obj.z = spriteInt(z, obj.z)
    obj.y = spriteInt(y, obj.y)
    return true
  }

  private fun addWarp() {
    val map = this.map ?: return
    val warp = NdsWarp()
    val x = JTextField(warp.x.toString(), 20)
    val z = JTextField(warp.z.toString(), 20)
    val header = JTextField(warp.header, 20)
    val anchor = JTextField(warp.anchor.toString(), 20)
    val panel = JPanel(GridLayout(0, 2, 6, 4))
    panel.add(JLabel("X")); panel.add(x)
    panel.add(JLabel("Z")); panel.add(z)
    panel.add(JLabel("Destination")); panel.add(header)
    panel.add(JLabel("Anchor")); panel.add(anchor)
    if (JOptionPane.showConfirmDialog(this, panel, "Add Warp", JOptionPane.OK_CANCEL_OPTION) !=
        JOptionPane.OK_OPTION) return
    warp.x = spriteInt(x, warp.x)
    warp.z = spriteInt(z, warp.z)
    warp.header = header.text.trim()
    warp.anchor = spriteInt(anchor, warp.anchor)
    map.events.warps += warp
    commit()
  }

  private fun editWarp(warp: NdsWarp, index: Int) {
    val x = JTextField(warp.x.toString(), 20)
    val z = JTextField(warp.z.toString(), 20)
    val header = JTextField(warp.header, 20)
    val anchor = JTextField(warp.anchor.toString(), 20)
    val panel = JPanel(GridLayout(0, 2, 6, 4))
    panel.add(JLabel("X")); panel.add(x)
    panel.add(JLabel("Z")); panel.add(z)
    panel.add(JLabel("Destination")); panel.add(header)
    panel.add(JLabel("Anchor")); panel.add(anchor)
    if (JOptionPane.showConfirmDialog(this, panel, "Edit Warp", JOptionPane.OK_CANCEL_OPTION) !=
        JOptionPane.OK_OPTION) return
    warp.x = spriteInt(x, warp.x)
    warp.z = spriteInt(z, warp.z)
    warp.header = header.text.trim()
    warp.anchor = spriteInt(anchor, warp.anchor)
    commit()
  }

  private fun addTrigger() {
    val map = this.map ?: return
    val t = NdsTrigger()
    val script = JTextField(t.scriptId, 26)
    val x = JTextField(t.x.toString(), 20)
    val z = JTextField(t.z.toString(), 20)
    val variable = JTextField(t.variable, 26)
    val value = JTextField(t.value.toString(), 20)
    val panel = JPanel(GridLayout(0, 2, 6, 4))
    panel.add(JLabel("Script")); panel.add(script)
    panel.add(JLabel("X")); panel.add(x)
    panel.add(JLabel("Z")); panel.add(z)
    panel.add(JLabel("Var")); panel.add(variable)
    panel.add(JLabel("Value")); panel.add(value)
    if (JOptionPane.showConfirmDialog(this, panel, "Add Trigger", JOptionPane.OK_CANCEL_OPTION) !=
        JOptionPane.OK_OPTION) return
    t.scriptId = script.text.trim()
    t.x = spriteInt(x, t.x)
    t.z = spriteInt(z, t.z)
    t.variable = variable.text.trim()
    t.value = spriteInt(value, t.value)
    map.events.triggers += t
    commit()
  }

  private fun editTrigger(t: NdsTrigger, index: Int) {
    val script = JTextField(t.scriptId, 26)
    val x = JTextField(t.x.toString(), 20)
    val z = JTextField(t.z.toString(), 20)
    val variable = JTextField(t.variable, 26)
    val value = JTextField(t.value.toString(), 20)
    val panel = JPanel(GridLayout(0, 2, 6, 4))
    panel.add(JLabel("Script")); panel.add(script)
    panel.add(JLabel("X")); panel.add(x)
    panel.add(JLabel("Z")); panel.add(z)
    panel.add(JLabel("Var")); panel.add(variable)
    panel.add(JLabel("Value")); panel.add(value)
    if (JOptionPane.showConfirmDialog(this, panel, "Edit Trigger", JOptionPane.OK_CANCEL_OPTION) !=
        JOptionPane.OK_OPTION) return
    t.scriptId = script.text.trim()
    t.x = spriteInt(x, t.x)
    t.z = spriteInt(z, t.z)
    t.variable = variable.text.trim()
    t.value = spriteInt(value, t.value)
    commit()
  }

  private fun addBg() {
    val map = this.map ?: return
    val b = NdsBgEvent()
    val script = JTextField(b.scriptId, 26)
    val x = JTextField(b.x.toString(), 20)
    val z = JTextField(b.z.toString(), 20)
    val dir = JComboBox(arrayOf("North", "East", "West", "South", "Any"))
    dir.selectedIndex = b.dir.coerceIn(0, 4)
    val panel = JPanel(GridLayout(0, 2, 6, 4))
    panel.add(JLabel("Script")); panel.add(script)
    panel.add(JLabel("X")); panel.add(x)
    panel.add(JLabel("Z")); panel.add(z)
    panel.add(JLabel("Dir")); panel.add(dir)
    if (JOptionPane.showConfirmDialog(this, panel, "Add BG Event", JOptionPane.OK_CANCEL_OPTION) !=
        JOptionPane.OK_OPTION) return
    b.scriptId = script.text.trim()
    b.x = spriteInt(x, b.x)
    b.z = spriteInt(z, b.z)
    b.dir = dir.selectedIndex
    map.events.bgEvents += b
    commit()
  }

  private fun editBg(b: NdsBgEvent, index: Int) {
    val script = JTextField(b.scriptId, 26)
    val x = JTextField(b.x.toString(), 20)
    val z = JTextField(b.z.toString(), 20)
    val dir = JComboBox(arrayOf("North", "East", "West", "South", "Any"))
    dir.selectedIndex = b.dir.coerceIn(0, 4)
    val panel = JPanel(GridLayout(0, 2, 6, 4))
    panel.add(JLabel("Script")); panel.add(script)
    panel.add(JLabel("X")); panel.add(x)
    panel.add(JLabel("Z")); panel.add(z)
    panel.add(JLabel("Dir")); panel.add(dir)
    if (JOptionPane.showConfirmDialog(this, panel, "Edit BG Event", JOptionPane.OK_CANCEL_OPTION) !=
        JOptionPane.OK_OPTION) return
    b.scriptId = script.text.trim()
    b.x = spriteInt(x, b.x)
    b.z = spriteInt(z, b.z)
    b.dir = dir.selectedIndex
    commit()
  }

  private fun spriteInt(field: JTextField, fallback: Int): Int =
      field.text.trim().toIntOrNull() ?: fallback

  private fun commit() {
    refresh()
    map?.let(onChange)
  }
}
