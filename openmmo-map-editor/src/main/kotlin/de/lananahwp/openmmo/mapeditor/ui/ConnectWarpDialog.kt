package de.lananahwp.openmmo.mapeditor.ui

import de.lananahwp.openmmo.mapeditor.json.Json
import de.lananahwp.openmmo.mapeditor.model.EditorMap
import java.awt.BorderLayout
import java.awt.Component
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel

/** Connects source and destination warps. */
class ConnectWarpDialog(
    private val sourceWarpIdx: Int,
    private val sourceMap: EditorMap,
    allMapDirs: List<String>,
    private val readMapJson: (dirName: String) -> Json.JObj?,
) : JDialog() {

  private val destMapCombo = JComboBox<String>(allMapDirs.sorted().toTypedArray())
  private val destWarpCombo = JComboBox<String>()
  private val bidirectional = JCheckBox("Link both ways (wire the destination warp back)")

  var result: ConnectWarpResult? = null
    private set

  init {
    title = "Connect Warp"
    isModal = true

    val sourceWarp = sourceMap.warps.getOrNull(sourceWarpIdx)
    val form = JPanel(GridBagLayout())
    val c = GridBagConstraints()
    c.insets = Insets(4, 8, 4, 8)
    c.anchor = GridBagConstraints.WEST

    fun row(label: String, field: Component, y: Int) {
      c.gridx = 0; c.gridy = y; c.weightx = 0.0; c.fill = GridBagConstraints.NONE
      form.add(JLabel(label), c)
      c.gridx = 1; c.weightx = 1.0; c.fill = GridBagConstraints.HORIZONTAL
      form.add(field, c)
    }

    val srcInfo =
        if (sourceWarp != null) {
          "x=${sourceWarp.int("x") ?: 0} y=${sourceWarp.int("y") ?: 0}  elev=${sourceWarp.int("elevation") ?: 0}  → ${sourceWarp.str("dest_map") ?: "?"}"
        } else "warp $sourceWarpIdx (missing?)"
    row("Source warp", JLabel(srcInfo), 0)
    row("Destination map", destMapCombo, 1)
    row("Destination warp", destWarpCombo, 2)
    c.gridx = 1; c.gridy = 3
    form.add(bidirectional, c)

    destMapCombo.addActionListener { updateDestWarps() }
    if (destMapCombo.itemCount > 0) destMapCombo.selectedIndex = 0

    val ok = JButton("Connect")
    val cancel = JButton("Cancel")
    ok.addActionListener {
      val destDir = destMapCombo.selectedItem as? String ?: ""
      val destIdx = destWarpCombo.selectedIndex
      val destWarps = warpsOf(destDir)
      val destWarp = destWarps.getOrNull(destIdx)
      // Warps reference map constants.
      val destMapId = readMapJson(destDir)?.str("id") ?: destDir
      if (destWarp == null) {
        JOptionPane.showMessageDialog(this, "Select a destination warp.", "Connect Warp", JOptionPane.WARNING_MESSAGE)
        return@addActionListener
      }
      result = ConnectWarpResult(destMapId, destIdx, bidirectional.isSelected, destWarp, destDir)
      dispose()
    }
    cancel.addActionListener { dispose() }
    val buttons = JPanel()
    buttons.add(ok)
    buttons.add(cancel)

    contentPane.layout = BorderLayout()
    contentPane.add(form, BorderLayout.CENTER)
    contentPane.add(buttons, BorderLayout.SOUTH)
    pack()
    setLocationRelativeTo(null)
  }

  private fun updateDestWarps() {
    destWarpCombo.removeAllItems()
    val dir = destMapCombo.selectedItem as? String ?: return
    for ((i, w) in warpsOf(dir).withIndex()) {
      val x = w.int("x") ?: 0
      val y = w.int("y") ?: 0
      val dest = w.str("dest_map") ?: "?"
      destWarpCombo.addItem("[$i]  ($x, $y) → $dest")
    }
    if (destWarpCombo.itemCount > 0) destWarpCombo.selectedIndex = 0
  }

  private fun warpsOf(dirName: String): List<Json.JObj> =
      readMapJson(dirName)?.arr("warp_events")?.items?.mapNotNull { it.asObj() } ?: emptyList()
}

data class ConnectWarpResult(
    val destMapId: String,
    val destWarpIdx: Int,
    val bidirectional: Boolean,
    val destWarp: Json.JObj,
    val destDirName: String,
)
