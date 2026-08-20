package de.lananahwp.openmmo.mapeditor.ui

import de.lananahwp.openmmo.mapeditor.project.DecompProject
import java.awt.BorderLayout
import java.awt.Component
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel

/** Result of the New Map dialog. */
data class NewMapParams(
    val mapConstant: String,
    val dirName: String,
    val name: String,
    val groupIndex: Int,
    val index: Int,
    val layoutId: String,
    val newLayout: Boolean,
    val width: Int,
    val height: Int,
    val primary: String,
    val secondary: String,
    val music: String,
    val mapsec: String,
    val weather: String,
    val mapType: String,
    val requiresFlash: Boolean,
)

/** Collects new map settings. */
class NewMapDialog(private val project: DecompProject) : JDialog() {

  private val musicOptions =
      project.tables.musicIds.keys.sorted().ifEmpty { listOf("MUS_NONE") }
  private val mapsecOptions =
      project.tables.mapsecIds.keys.sorted().ifEmpty { listOf("MAPSEC_NONE") }
  private val weatherOptions =
      project.constants("include/constants/weather.h", "WEATHER_").ifEmpty { listOf("WEATHER_NONE") }
  private val mapTypeOptions =
      project.constants("include/constants/map_types.h", "MAP_TYPE_").ifEmpty { listOf("MAP_TYPE_INDOOR") }
  private val layoutOptions = listOf("<New layout…>") + project.layouts.keys.sorted()
  private val primaryTilesets =
      project.source.tilesetNames.filter { !project.source.isSecondaryTileset(it) }
  private val secondaryTilesets =
      project.source.tilesetNames.filter { project.source.isSecondaryTileset(it) }

  private val offset = project.region.gbaBankOffset
  // Default to the next wire bank.
  private val maxBank =
      (project.groupOrder.size - 1).let { if (it >= 0) it + offset else offset }

  private val mapConstantField = JTextField("MAP_MY_NEW_MAP")
  private val dirNameField = JTextField("MyNewMap")
  private val nameField = JTextField("My New Map")
  private val bankSpinner =
      JSpinner(SpinnerNumberModel((maxBank + 1).coerceAtMost(255), offset, 255, 1))
  private val idSpinner = JSpinner(SpinnerNumberModel(0, 0, 0, 1))
  private val groupLabel = JLabel("group: —")
  private val layoutCombo = JComboBox<String>(layoutOptions.toTypedArray())
  private val widthSpinner = JSpinner(SpinnerNumberModel(20, 1, 1024, 1))
  private val heightSpinner = JSpinner(SpinnerNumberModel(15, 1, 1024, 1))
  private val primaryCombo = JComboBox<String>(primaryTilesets.toTypedArray())
  private val secondaryCombo = JComboBox<String>(secondaryTilesets.toTypedArray())
  private val musicCombo = JComboBox<String>(musicOptions.toTypedArray())
  private val mapsecCombo = JComboBox<String>(mapsecOptions.toTypedArray())
  private val weatherCombo = JComboBox<String>(weatherOptions.toTypedArray())
  private val mapTypeCombo = JComboBox<String>(mapTypeOptions.toTypedArray())
  private val flashCheck = JCheckBox("Requires Flash")
  private val layoutFields = JPanel(GridBagLayout())

  var params: NewMapParams? = null
    private set

  init {
    title = "New Map"
    isModal = true
    bankSpinner.addChangeListener { bankChanged() }
    bankChanged()
    idSpinner.model = SpinnerNumberModel(0, 0, 0, 1)

    mapConstantField.addActionListener { deriveNames() }
    dirNameField.addActionListener {
      nameField.text = if (nameField.text.isBlank()) dirNameField.text else nameField.text
    }
    layoutCombo.addActionListener { layoutFields.isVisible = layoutCombo.selectedIndex == 0 }

    buildLayoutFields()
    layoutFields.isVisible = layoutCombo.selectedIndex == 0

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

    row("Map constant (id)", mapConstantField, 0)
    row("Directory name", dirNameField, 1)
    row("Display name", nameField, 2)
    row("Bank (wire number)", bankSpinner, 3)
    c.gridx = 1; c.gridy = 4; c.fill = GridBagConstraints.HORIZONTAL
    form.add(groupLabel, c)
    row("ID (index in group)", idSpinner, 5)
    row("Layout", layoutCombo, 6)

    c.gridx = 1; c.gridy = 7; c.fill = GridBagConstraints.HORIZONTAL
    form.add(layoutFields, c)

    row("Music", musicCombo, 8)
    row("Map section", mapsecCombo, 9)
    row("Weather", weatherCombo, 10)
    row("Map type", mapTypeCombo, 11)
    c.gridx = 1; c.gridy = 12; c.anchor = GridBagConstraints.WEST
    form.add(flashCheck, c)

    val ok = JButton("Create Map")
    val cancel = JButton("Cancel")
    ok.addActionListener { onOk() }
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

  private fun bankChanged() {
    val bank = (bankSpinner.value as Number).toInt()
    val gi = bank - offset
    if (gi in project.groupOrder.indices) {
      val group = project.groupOrder[gi]
      groupLabel.text = "group: ${group.removePrefix("gMapGroup_")}"
      val size = project.groupMaps[group]?.size ?: 0
      idSpinner.model = SpinnerNumberModel(size, size, size, 1)
    } else {
      groupLabel.text = "group: <new — will be created>"
      idSpinner.model = SpinnerNumberModel(0, 0, 0, 1)
    }
  }

  private fun buildLayoutFields() {
    layoutFields.removeAll()
    val c = GridBagConstraints()
    c.insets = Insets(2, 0, 2, 4)
    c.anchor = GridBagConstraints.WEST

    fun row(label: String, field: Component, x: Int, y: Int) {
      c.gridx = x; c.gridy = y; c.fill = GridBagConstraints.NONE
      layoutFields.add(JLabel(label), c)
      c.gridx = x + 1; c.fill = GridBagConstraints.HORIZONTAL
      layoutFields.add(field, c)
    }

    row("Width", widthSpinner, 0, 0)
    row("Height", heightSpinner, 2, 0)
    row("Primary tileset", primaryCombo, 0, 1)
    row("Secondary tileset", secondaryCombo, 2, 1)
    layoutFields.border = BorderFactory.createTitledBorder("New layout")
  }

  /** Normalizes a user-entered constant to MAP_UPPER_SNAKE_CASE (adds MAP_ prefix, uppercases). */
  private fun normalizeConstant(raw: String): String {
    var s = raw.trim().uppercase()
    s = s.replace(Regex("[^A-Z0-9_]+"), "_")
    s = s.replace(Regex("_+"), "_").trim('_')
    return if (s.startsWith("MAP_")) s else "MAP_$s"
  }

  private fun deriveNames() {
    val constant = normalizeConstant(mapConstantField.text)
    if (constant == "MAP_" || constant == "MAP") return
    val derived = constant.removePrefix("MAP_")
    if (dirNameField.text.isBlank() || dirNameField.text == "MyNewMap") {
      dirNameField.text = derived.ifBlank { "NewMap" }
    }
    if (nameField.text.isBlank() || nameField.text == "My New Map") {
      nameField.text = derived.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
    }
  }

  private fun onOk() {
    val constant = normalizeConstant(mapConstantField.text)
    val dirName = dirNameField.text.trim()
    if (constant == "MAP" || constant == "MAP_" || dirName.isEmpty()) {
      JOptionPane.showMessageDialog(this, "Map constant and directory name are required.", "New Map", JOptionPane.WARNING_MESSAGE)
      return
    }
    if (!constant.matches(Regex("MAP_[A-Z0-9_]+"))) {
      JOptionPane.showMessageDialog(
          this,
          "Map constants may only contain letters, numbers, and underscores.",
          "New Map",
          JOptionPane.WARNING_MESSAGE,
      )
      return
    }
    if (!dirName.matches(Regex("[A-Za-z][A-Za-z0-9_]*"))) {
      JOptionPane.showMessageDialog(
          this,
          "Directory names may only contain letters, numbers, and underscores.",
          "New Map",
          JOptionPane.WARNING_MESSAGE,
      )
      return
    }
    if (project.mapExists(constant) || project.mapExists(dirName)) {
      JOptionPane.showMessageDialog(this, "A map with constant '$constant' or directory '$dirName' already exists.", "New Map", JOptionPane.WARNING_MESSAGE)
      return
    }
    val bank = (bankSpinner.value as Number).toInt()
    val gi = bank - offset
    if (layoutCombo.selectedIndex == 0 && (primaryCombo.itemCount == 0 || secondaryCombo.itemCount == 0)) {
      JOptionPane.showMessageDialog(
          this,
          "The project has no usable tileset pair.",
          "New Map",
          JOptionPane.WARNING_MESSAGE,
      )
      return
    }
    params =
        NewMapParams(
            mapConstant = constant,
            dirName = dirName,
            name = nameField.text.trim().ifEmpty { dirName },
            groupIndex = gi,
            index = (idSpinner.value as Number).toInt(),
            layoutId = if (layoutCombo.selectedIndex == 0) "" else layoutCombo.selectedItem as String,
            newLayout = layoutCombo.selectedIndex == 0,
            width = (widthSpinner.value as Number).toInt(),
            height = (heightSpinner.value as Number).toInt(),
            primary = primaryCombo.selectedItem as? String ?: "",
            secondary = secondaryCombo.selectedItem as? String ?: "",
            music = musicCombo.selectedItem as? String ?: "MUS_NONE",
            mapsec = mapsecCombo.selectedItem as? String ?: "MAPSEC_NONE",
            weather = weatherCombo.selectedItem as? String ?: "WEATHER_NONE",
            mapType = mapTypeCombo.selectedItem as? String ?: "MAP_TYPE_INDOOR",
            requiresFlash = flashCheck.isSelected,
        )
    dispose()
  }
}
