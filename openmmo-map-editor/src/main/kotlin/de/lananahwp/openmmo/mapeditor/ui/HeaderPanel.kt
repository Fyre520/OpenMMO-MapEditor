package de.lananahwp.openmmo.mapeditor.ui

import de.lananahwp.openmmo.mapeditor.core.BaseSource
import de.lananahwp.openmmo.mapeditor.model.EditorMap
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTextArea
import javax.swing.SpinnerNumberModel

/** Edits layout dimensions and tilesets. */
class HeaderPanel(
    private val source: BaseSource,
    private val onApply: (width: Int, height: Int, primary: String, secondary: String) -> Unit,
) : JPanel(BorderLayout()) {

  private val primaryTilesets = source.tilesetNames.filter { !source.isSecondaryTileset(it) }
  private val secondaryTilesets = source.tilesetNames.filter { source.isSecondaryTileset(it) }

  private val widthSpinner = JSpinner(SpinnerNumberModel(20, 1, 1024, 1))
  private val heightSpinner = JSpinner(SpinnerNumberModel(15, 1, 1024, 1))
  private val primaryCombo = JComboBox<String>(primaryTilesets.toTypedArray())
  private val secondaryCombo = JComboBox<String>(secondaryTilesets.toTypedArray())
  private val info = JTextArea().apply {
    isEditable = false
    lineWrap = true
    wrapStyleWord = true
    font = font.deriveFont(12f)
  }

  private var currentMap: EditorMap? = null

  init {
    val form = JPanel(GridBagLayout())
    val c = GridBagConstraints()
    c.insets = Insets(4, 8, 4, 8)
    c.anchor = GridBagConstraints.WEST

    fun addLabel(text: String, row: Int) {
      c.gridx = 0
      c.gridy = row
      c.weightx = 0.0
      form.add(JLabel(text), c)
    }

    fun addField(field: java.awt.Component, row: Int) {
      c.gridx = 1
      c.gridy = row
      c.weightx = 1.0
      c.fill = GridBagConstraints.HORIZONTAL
      form.add(field, c)
      c.fill = GridBagConstraints.NONE
    }

    addLabel("Width", 0); addField(widthSpinner, 0)
    addLabel("Height", 1); addField(heightSpinner, 1)
    addLabel("Primary tileset", 2); addField(primaryCombo, 2)
    addLabel("Secondary tileset", 3); addField(secondaryCombo, 3)

    val apply = JButton("Apply changes")
    apply.addActionListener {
      val map = currentMap ?: return@addActionListener
      val w = (widthSpinner.value as Number).toInt()
      val h = (heightSpinner.value as Number).toInt()
      onApply(w, h, primaryCombo.selectedItem as? String ?: map.layout.primaryTileset, secondaryCombo.selectedItem as? String ?: map.layout.secondaryTileset)
    }
    c.gridx = 1
    c.gridy = 4
    c.fill = GridBagConstraints.NONE
    form.add(apply, c)

    add(form, BorderLayout.NORTH)
    add(JScrollPane(info), BorderLayout.CENTER)
  }

  fun setMap(map: EditorMap) {
    currentMap = map
    widthSpinner.value = map.layout.width
    heightSpinner.value = map.layout.height
    primaryTilesets.indexOf(map.layout.primaryTileset).takeIf { it >= 0 }?.let { primaryCombo.selectedIndex = it }
    secondaryTilesets.indexOf(map.layout.secondaryTileset).takeIf { it >= 0 }?.let { secondaryCombo.selectedIndex = it }

    val sb = StringBuilder()
    sb.append("Name:     ").append(map.name).append('\n')
    sb.append("Id:       ").append(map.id).append('\n')
    sb.append("Group:    ").append(map.groupName).append(" [").append(map.groupIndex).append(']').append('\n')
    sb.append("Index:    ").append(map.mapIndex).append('\n')
    if (map.isRuntimeOverride) {
      sb.append("Override: ")
          .append(map.sourceDirName)
          .append(" [group ")
          .append(map.exportGroupIndex)
          .append(", map ")
          .append(map.exportMapIndex)
          .append("]\n")
    }
    sb.append("Music:    ").append(map.music).append('\n')
    sb.append("Mapsec:   ").append(map.mapsec).append('\n')
    sb.append("Weather:  ").append(map.weather).append('\n')
    sb.append("Type:     ").append(map.mapType).append('\n')
    sb.append("Flash:    ").append(map.requiresFlash).append('\n')
    sb.append("Warps:    ").append(map.warps.size).append('\n')
    sb.append("Objects:  ").append(map.objects.size).append('\n')
    sb.append("Bg events:").append(map.bgEvents.size).append('\n')
    sb.append("Coord events: ").append(map.coordEvents.size).append('\n')
    info.text = sb.toString()
  }
}
