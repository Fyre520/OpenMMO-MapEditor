package de.lananahwp.openmmo.mapeditor.ui

import de.lananahwp.openmmo.mapeditor.model.NdsMap
import de.lananahwp.openmmo.mapeditor.model.NdsProp
import de.lananahwp.openmmo.mapeditor.model.NdsGrid
import de.lananahwp.openmmo.mapeditor.project.NdsProject
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.DefaultListModel
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.ListSelectionEvent

/** Prop catalog, placed-prop list, and transform editor for DS maps. */
class NdsPropsPanel(
    private val onImport: () -> Unit,
    private val onPlace: (String) -> Unit,
    private val onPreview: (String) -> NdsProject.PropModelPreview,
    private val onSelect: (String?) -> Unit,
    private val onRemove: () -> Unit,
    private val onDuplicate: () -> Unit,
    private val onChanged: () -> Unit,
) : JPanel(BorderLayout(6, 6)) {
  private val search = JTextField()
  private val catalog = JComboBox<NdsProject.PropModelInfo>()
  private val catalogCategory = JLabel(" ")
  private val preview =
      NdsSoftwareMapView({ _, _ -> }, { _, _, _ -> }).apply {
        grid = NdsGrid(0, 0)
        showGrid = false
        preferredSize = Dimension(260, 180)
      }
  private val previewHint = JLabel("Middle-drag to rotate • wheel to zoom")
  private val listModel = DefaultListModel<String>()
  private val propList = JList(listModel)
  private val x = decimalSpinner(0.0, -4096.0, 4096.0, 0.25)
  private val y = decimalSpinner(0.0, -4096.0, 4096.0, 0.25)
  private val z = decimalSpinner(0.0, -4096.0, 4096.0, 0.25)
  private val rx = decimalSpinner(0.0, -3600.0, 3600.0, 1.0)
  private val ry = decimalSpinner(0.0, -3600.0, 3600.0, 1.0)
  private val rz = decimalSpinner(0.0, -3600.0, 3600.0, 1.0)
  private val sx = decimalSpinner(1.0, 0.001, 10000.0, 0.05)
  private val sy = decimalSpinner(1.0, 0.001, 10000.0, 0.05)
  private val sz = decimalSpinner(1.0, 0.001, 10000.0, 0.05)
  private var map: NdsMap? = null
  private var propIds = emptyList<String>()
  private var selectedId: String? = null
  private var syncing = false
  private var syncingCatalog = false
  private var allModels = emptyList<NdsProject.PropModelInfo>()
  private var modelLabels = emptyMap<String, String>()

  init {
    preferredSize = Dimension(280, 600)
    border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
    catalog.maximumRowCount = 24
    catalog.addActionListener {
      if (!syncingCatalog) refreshPreview()
    }
    search.toolTipText = "Search by name, category, or ROM number"
    search.document.addDocumentListener(object : DocumentListener {
      override fun insertUpdate(e: DocumentEvent?) = applyCatalogFilter()
      override fun removeUpdate(e: DocumentEvent?) = applyCatalogFilter()
      override fun changedUpdate(e: DocumentEvent?) = applyCatalogFilter()
    })
    catalogCategory.font = catalogCategory.font.deriveFont(10f)

    val catalogPanel = JPanel(BorderLayout(4, 4)).also {
      it.border = BorderFactory.createTitledBorder("Prop model")
      it.add(JPanel(BorderLayout(3, 3)).also { selector ->
        selector.add(search, BorderLayout.NORTH)
        selector.add(catalog, BorderLayout.CENTER)
        selector.add(catalogCategory, BorderLayout.SOUTH)
      }, BorderLayout.NORTH)
      it.add(preview, BorderLayout.CENTER)
      it.add(JPanel(GridLayout(1, 2, 4, 0)).also { buttons ->
        buttons.add(JButton("Import…").also { b -> b.addActionListener { onImport() } })
        buttons.add(JButton("Place at center").also { b ->
          b.addActionListener {
            (catalog.selectedItem as? NdsProject.PropModelInfo)?.key?.let(onPlace)
          }
        })
      }, BorderLayout.SOUTH)
    }
    val catalogArea = JPanel(BorderLayout(2, 2)).also {
      it.add(catalogPanel, BorderLayout.CENTER)
      previewHint.horizontalAlignment = JLabel.CENTER
      previewHint.font = previewHint.font.deriveFont(10f)
      it.add(previewHint, BorderLayout.SOUTH)
    }
    add(catalogArea, BorderLayout.NORTH)

    propList.addListSelectionListener { e: ListSelectionEvent ->
      if (e.valueIsAdjusting || syncing) return@addListSelectionListener
      val id = propIds.getOrNull(propList.selectedIndex)
      selectProp(id, notify = true)
    }
    add(JScrollPane(propList).also {
      it.border = BorderFactory.createTitledBorder("Placed props")
    }, BorderLayout.CENTER)

    val transforms = JPanel(GridLayout(0, 4, 4, 3))
    transforms.add(JLabel("")); transforms.add(JLabel("X")); transforms.add(JLabel("Y")); transforms.add(JLabel("Z"))
    fun row(label: String, a: JSpinner, b: JSpinner, c: JSpinner) {
      transforms.add(JLabel(label)); transforms.add(a); transforms.add(b); transforms.add(c)
    }
    row("Position", x, y, z)
    row("Rotation", rx, ry, rz)
    row("Scale", sx, sy, sz)
    val bottom = JPanel(BorderLayout(4, 4)).also {
      it.border = BorderFactory.createTitledBorder("Selected transform")
      it.add(transforms, BorderLayout.CENTER)
      it.add(JPanel(GridLayout(1, 2, 4, 0)).also { buttons ->
        buttons.add(JButton("Duplicate").also { b -> b.addActionListener { onDuplicate() } })
        buttons.add(JButton("Remove").also { b -> b.addActionListener { onRemove() } })
      }, BorderLayout.SOUTH)
    }
    add(bottom, BorderLayout.SOUTH)

    listOf(x, y, z, rx, ry, rz, sx, sy, sz).forEach { spinner ->
      spinner.addChangeListener { applyTransform() }
    }
    setTransformEnabled(false)
  }

  fun setModels(models: List<NdsProject.PropModelInfo>) {
    allModels = models
    modelLabels = models.associate { it.key to it.label }
    applyCatalogFilter()
  }

  private fun applyCatalogFilter() {
    val selected = (catalog.selectedItem as? NdsProject.PropModelInfo)?.key
    val terms = search.text.trim().lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
    val models = if (terms.isEmpty()) allModels else allModels.filter { model ->
      val haystack = "${model.label} ${model.category} ${model.key} ${model.key.removePrefix("rom:")}".lowercase()
      terms.all(haystack::contains)
    }
    syncingCatalog = true
    catalog.removeAllItems()
    models.forEach(catalog::addItem)
    if (selected != null) {
      for (i in 0 until catalog.itemCount) if (catalog.getItemAt(i).key == selected) {
        catalog.selectedIndex = i
        break
      }
    }
    syncingCatalog = false
    refreshPreview()
  }

  fun selectModel(key: String) {
    if ((0 until catalog.itemCount).none { catalog.getItemAt(it).key == key }) {
      search.text = ""
    }
    for (i in 0 until catalog.itemCount) if (catalog.getItemAt(i).key == key) {
      catalog.selectedIndex = i
      refreshPreview()
      return
    }
  }

  fun setMap(map: NdsMap?) {
    this.map = map
    selectedId = null
    refreshProps(null)
  }

  fun refreshProps(preferredSelection: String? = selectedId) {
    val props = map?.props.orEmpty()
    propIds = props.map { it.id }
    syncing = true
    listModel.clear()
    props.forEachIndexed { index, p -> listModel.addElement(label(index, p)) }
    val index = preferredSelection?.let(propIds::indexOf) ?: -1
    propList.selectedIndex = index
    syncing = false
    selectProp(propIds.getOrNull(index), notify = false)
  }

  fun selectProp(id: String?, notify: Boolean = false) {
    selectedId = id
    val prop = map?.props?.firstOrNull { it.id == id }
    syncing = true
    if (prop != null) {
      x.value = prop.x.toDouble(); y.value = prop.y.toDouble(); z.value = prop.z.toDouble()
      rx.value = prop.rotationX.toDouble(); ry.value = prop.rotationY.toDouble(); rz.value = prop.rotationZ.toDouble()
      sx.value = prop.scaleX.toDouble(); sy.value = prop.scaleY.toDouble(); sz.value = prop.scaleZ.toDouble()
      val index = propIds.indexOf(prop.id)
      if (index >= 0 && propList.selectedIndex != index) propList.selectedIndex = index
    } else {
      propList.clearSelection()
    }
    syncing = false
    setTransformEnabled(prop != null)
    if (notify) onSelect(id)
  }

  private fun applyTransform() {
    if (syncing) return
    val prop = map?.props?.firstOrNull { it.id == selectedId } ?: return
    prop.x = number(x); prop.y = number(y); prop.z = number(z)
    prop.rotationX = number(rx); prop.rotationY = number(ry); prop.rotationZ = number(rz)
    prop.scaleX = number(sx); prop.scaleY = number(sy); prop.scaleZ = number(sz)
    val index = propIds.indexOf(prop.id)
    if (index >= 0) listModel[index] = label(index, prop)
    onChanged()
  }

  private fun refreshPreview() {
    val selected = catalog.selectedItem as? NdsProject.PropModelInfo
    val key = selected?.key
    catalogCategory.text = selected?.let { "Category: ${it.category}" } ?: "No matching prop models"
    if (key == null) {
      preview.modelTriangles = emptyList()
      preview.modelTextures = emptyMap()
      preview.modelPalettes = emptyMap()
      return
    }
    val data = onPreview(key)
    preview.modelTriangles = data.triangles
    preview.modelTextures = data.textures
    preview.modelPalettes = data.palettes
  }

  private fun setTransformEnabled(enabled: Boolean) {
    listOf(x, y, z, rx, ry, rz, sx, sy, sz).forEach { it.isEnabled = enabled }
  }

  private fun label(index: Int, prop: NdsProp): String =
      "${index + 1}. ${modelLabels[prop.modelKey] ?: prop.modelKey.substringAfter(':')} @ %.2f, %.2f"
          .format(prop.x, prop.z)

  private fun number(spinner: JSpinner): Float = (spinner.value as Number).toFloat()

  private fun decimalSpinner(value: Double, min: Double, max: Double, step: Double) =
      JSpinner(SpinnerNumberModel(value, min, max, step)).also {
        it.preferredSize = Dimension(76, it.preferredSize.height)
      }
}
