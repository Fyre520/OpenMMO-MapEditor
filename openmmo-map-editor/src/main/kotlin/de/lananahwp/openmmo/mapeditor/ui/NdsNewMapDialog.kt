package de.lananahwp.openmmo.mapeditor.ui

import de.lananahwp.openmmo.mapeditor.project.NdsProject
import java.awt.BorderLayout
import java.awt.Component
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.File
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel
import javax.swing.filechooser.FileNameExtensionFilter

data class NdsNewMapParams(
    val name: String,
    val displayName: String,
    val mapId: Int,
    val cellsWide: Int,
    val cellsHigh: Int,
    val matrixX: Int?,
    val matrixY: Int?,
    val templateName: String?,
    val modelFile: File?,
    val textureFile: File?,
)

/** Collects settings for a project-local Gen 4 map. */
class NdsNewMapDialog(private val project: NdsProject) : JDialog() {
  private val nameField = JTextField("MAP_MY_NEW_MAP", 28)
  private val displayNameField = JTextField("My New Map", 28)
  private val idSpinner = JSpinner(SpinnerNumberModel(project.suggestedMapId(), 0, 0xFFFF, 1))
  private val widthSpinner = JSpinner(SpinnerNumberModel(1, 1, 32, 1))
  private val heightSpinner = JSpinner(SpinnerNumberModel(1, 1, 32, 1))
  private val suggestedOrigin = project.suggestedMatrixOrigin()
  private val placeInWorldCheck = JCheckBox("Place in overworld matrix", true)
  private val matrixXSpinner = JSpinner(SpinnerNumberModel(suggestedOrigin.first.coerceAtMost(255), 0, 255, 1))
  private val matrixYSpinner = JSpinner(SpinnerNumberModel(suggestedOrigin.second.coerceAtMost(255), 0, 255, 1))
  private val templateCombo =
      JComboBox((listOf("<Blank header>") + project.mapNames).toTypedArray())
  private val modelField = JTextField(28).also { it.isEditable = false }
  private val textureField = JTextField(28).also { it.isEditable = false }
  private var modelFile: File? = null
  private var textureFile: File? = null

  var params: NdsNewMapParams? = null
    private set

  init {
    title = "New Nintendo DS Map"
    isModal = true

    val form = JPanel(GridBagLayout())
    val c = GridBagConstraints().also {
      it.insets = Insets(4, 8, 4, 8)
      it.anchor = GridBagConstraints.WEST
    }
    fun row(label: String, field: Component, y: Int) {
      c.gridx = 0; c.gridy = y; c.weightx = 0.0; c.fill = GridBagConstraints.NONE
      form.add(JLabel(label), c)
      c.gridx = 1; c.weightx = 1.0; c.fill = GridBagConstraints.HORIZONTAL
      form.add(field, c)
    }

    row("Region / game", JLabel("${project.family.regionName.replaceFirstChar { it.uppercase() }} — ${project.family.displayName}"), 0)
    row("Map constant", nameField, 1)
    row("Display name", displayNameField, 2)
    row("Numeric map ID", idSpinner, 3)

    val size = JPanel()
    size.add(JLabel("Cells wide")); size.add(widthSpinner)
    size.add(JLabel("Cells high")); size.add(heightSpinner)
    row("Map footprint", size, 4)
    val placement = JPanel()
    placement.add(placeInWorldCheck)
    placement.add(JLabel("Origin X")); placement.add(matrixXSpinner)
    placement.add(JLabel("Y")); placement.add(matrixYSpinner)
    placeInWorldCheck.addActionListener {
      matrixXSpinner.isEnabled = placeInWorldCheck.isSelected
      matrixYSpinner.isEnabled = placeInWorldCheck.isSelected
    }
    row("World placement", placement, 5)
    row("Header/area template", templateCombo, 6)
    row("Map model (optional)", filePicker(modelField, "Choose NSBMD…", "nsbmd") {
      modelFile = it
      modelField.text = it.absolutePath
    }, 7)
    row("Texture pack (optional)", filePicker(textureField, "Choose NSBTX…", "nsbtx") {
      textureFile = it
      textureField.text = it.absolutePath
    }, 8)

    c.gridx = 0; c.gridy = 9; c.gridwidth = 2; c.fill = GridBagConstraints.HORIZONTAL
    form.add(JLabel("<html>Models may contain embedded textures. New maps and imported assets are stored<br>inside this project; the source ROM is not modified.</html>"), c)

    val create = JButton("Create Map").also { it.addActionListener { onCreate() } }
    val cancel = JButton("Cancel").also { it.addActionListener { dispose() } }
    val buttons = JPanel().also { it.add(create); it.add(cancel) }
    contentPane.layout = BorderLayout()
    contentPane.add(form, BorderLayout.CENTER)
    contentPane.add(buttons, BorderLayout.SOUTH)
    pack()
    setLocationRelativeTo(null)
  }

  private fun filePicker(
      field: JTextField,
      buttonText: String,
      extension: String,
      selected: (File) -> Unit,
  ): JPanel = JPanel(BorderLayout(4, 0)).also { panel ->
    panel.add(field, BorderLayout.CENTER)
    panel.add(JButton(buttonText).also { button ->
      button.addActionListener {
        val chooser = JFileChooser().also {
          it.fileFilter = FileNameExtensionFilter("Nintendo DS ${extension.uppercase()} (*.$extension)", extension)
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) selected(chooser.selectedFile)
      }
    }, BorderLayout.EAST)
  }

  private fun normalizeName(raw: String): String {
    var value = raw.trim().uppercase().replace(Regex("[^A-Z0-9_]+"), "_")
    value = value.replace(Regex("_+"), "_").trim('_')
    return if (value.startsWith("MAP_")) value else "MAP_$value"
  }

  private fun onCreate() {
    val name = normalizeName(nameField.text)
    val id = (idSpinner.value as Number).toInt()
    if (!name.matches(Regex("MAP_[A-Z0-9_]+"))) {
      JOptionPane.showMessageDialog(this, "Enter a valid map name.", "New Map", JOptionPane.WARNING_MESSAGE)
      return
    }
    if (project.hasMap(name)) {
      JOptionPane.showMessageDialog(this, "A map named '$name' already exists.", "New Map", JOptionPane.WARNING_MESSAGE)
      return
    }
    if (project.mapIdInUse(id)) {
      JOptionPane.showMessageDialog(this, "Map ID $id is already in use.", "New Map", JOptionPane.WARNING_MESSAGE)
      return
    }
    if (textureFile != null && modelFile == null) {
      JOptionPane.showMessageDialog(this, "Choose a model before choosing a texture pack.", "New Map", JOptionPane.WARNING_MESSAGE)
      return
    }
    val cellsWide = (widthSpinner.value as Number).toInt()
    val cellsHigh = (heightSpinner.value as Number).toInt()
    val matrixX = if (placeInWorldCheck.isSelected) (matrixXSpinner.value as Number).toInt() else null
    val matrixY = if (placeInWorldCheck.isSelected) (matrixYSpinner.value as Number).toInt() else null
    if (matrixX != null && matrixY != null &&
        (matrixX + cellsWide > 256 || matrixY + cellsHigh > 256)) {
      JOptionPane.showMessageDialog(
          this, "The map does not fit inside the world matrix.", "New Map", JOptionPane.WARNING_MESSAGE)
      return
    }
    if (matrixX != null && matrixY != null &&
        project.matrixPlacementConflicts(matrixX, matrixY, cellsWide, cellsHigh)) {
      JOptionPane.showMessageDialog(
          this, "That world-matrix area overlaps an existing map.", "New Map", JOptionPane.WARNING_MESSAGE)
      return
    }
    params = NdsNewMapParams(
        name,
        displayNameField.text.trim().ifEmpty { name },
        id,
        cellsWide,
        cellsHigh,
        matrixX,
        matrixY,
        (templateCombo.selectedItem as? String)?.takeUnless { it == "<Blank header>" },
        modelFile,
        textureFile,
    )
    dispose()
  }
}
