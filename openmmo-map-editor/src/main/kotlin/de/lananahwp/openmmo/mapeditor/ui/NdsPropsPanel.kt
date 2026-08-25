package de.lananahwp.openmmo.mapeditor.ui

import de.lananahwp.openmmo.mapeditor.model.NdsGrid
import de.lananahwp.openmmo.mapeditor.model.NdsMap
import de.lananahwp.openmmo.mapeditor.model.NdsProp
import de.lananahwp.openmmo.mapeditor.project.NdsProject
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SpinnerNumberModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.ListSelectionEvent

/** Prop catalog, placed-prop list, and transform editor for DS maps. */
class NdsPropsPanel(
    private val onImport: () -> Unit,
    private val onPlace: (NdsProject.PropModelInfo) -> Unit,
    private val onPreview: (NdsProject.PropModelInfo) -> NdsProject.PropModelPreview,
    private val onSelect: (Set<String>, String?) -> Unit,
    private val onRemove: () -> Unit,
    private val onDuplicate: () -> Unit,
    private val onMerge: () -> Unit,
    private val onShowAllNdsPropsChanged: (Boolean) -> Unit,
    private val onChanged: () -> Unit,
) : JPanel(BorderLayout(6, 6)) {
  private val search = JTextField()
  private val catalog = JComboBox<NdsProject.PropModelInfo>()
  private val showAllNdsProps = JCheckBox("Show all NDS props")
  private val catalogCategory = JLabel(" ")
  private val preview = NdsSoftwareMapView({ _, _ -> }, { _, _, _ -> }).apply {
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
  private val linkScale = JCheckBox("Link scale XYZ", false)
  private val mergeButton = JButton("Merge Prop")
  private var map: NdsMap? = null
  private var propIds = emptyList<String>()
  private var selectedIds = linkedSetOf<String>()
  private var primaryId: String? = null
  private var syncing = false
  private var syncingCatalog = false
  private var allModels = emptyList<NdsProject.PropModelInfo>()
  private var modelLabels = emptyMap<String, String>()

  init {
    preferredSize = Dimension(290, 600)
    border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
    catalog.maximumRowCount = 24
    catalog.addActionListener { if (!syncingCatalog) refreshPreview() }
    installCatalogCycling()
    search.toolTipText = "Search by name, category, game, or ROM number"
    search.document.addDocumentListener(object : DocumentListener {
      override fun insertUpdate(e: DocumentEvent?) = applyCatalogFilter()
      override fun removeUpdate(e: DocumentEvent?) = applyCatalogFilter()
      override fun changedUpdate(e: DocumentEvent?) = applyCatalogFilter()
    })
    showAllNdsProps.addActionListener { onShowAllNdsPropsChanged(showAllNdsProps.isSelected) }
    catalogCategory.font = catalogCategory.font.deriveFont(10f)

    val catalogPanel = JPanel(BorderLayout(4, 4)).also {
      it.border = BorderFactory.createTitledBorder("Prop model")
      it.add(JPanel(BorderLayout(3, 3)).also { selector ->
        selector.add(search, BorderLayout.NORTH)
        selector.add(catalog, BorderLayout.CENTER)
        selector.add(JPanel(BorderLayout()).also { details ->
          details.add(catalogCategory, BorderLayout.CENTER)
          details.add(showAllNdsProps, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
      }, BorderLayout.NORTH)
      it.add(preview, BorderLayout.CENTER)
      it.add(JPanel(GridLayout(1, 2, 4, 0)).also { buttons ->
        buttons.add(JButton("Import…").also { b -> b.addActionListener { onImport() } })
        buttons.add(JButton("Place at center").also { b ->
          b.addActionListener { (catalog.selectedItem as? NdsProject.PropModelInfo)?.let(onPlace) }
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

    propList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
    propList.addListSelectionListener { e: ListSelectionEvent ->
      if (e.valueIsAdjusting || syncing) return@addListSelectionListener
      val ids = propList.selectedIndices.map(propIds::getOrNull).filterNotNull().toCollection(LinkedHashSet())
      val lead = propIds.getOrNull(propList.leadSelectionIndex)?.takeIf { it in ids }
      selectProps(ids, lead ?: ids.lastOrNull(), notify = true)
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
    transforms.add(linkScale); transforms.add(JLabel("")); transforms.add(JLabel("")); transforms.add(JLabel(""))
    val bottom = JPanel(BorderLayout(4, 4)).also {
      it.border = BorderFactory.createTitledBorder("Selected transform")
      it.add(transforms, BorderLayout.CENTER)
      it.add(JPanel(GridLayout(1, 3, 4, 0)).also { buttons ->
        buttons.add(JButton("Duplicate").also { b -> b.addActionListener { onDuplicate() } })
        mergeButton.addActionListener { onMerge() }
        buttons.add(mergeButton)
        buttons.add(JButton("Remove").also { b -> b.addActionListener { onRemove() } })
      }, BorderLayout.SOUTH)
    }
    add(bottom, BorderLayout.SOUTH)

    listOf(x, y, z, rx, ry, rz).forEach { spinner ->
      spinner.addChangeListener { applyTransform() }
    }
    listOf(sx, sy, sz).forEach { spinner ->
      spinner.addChangeListener { applyScale(spinner) }
    }
    linkScale.addActionListener {
      if (linkScale.isSelected && !syncing) applyScale(sx)
    }
    setTransformEnabled(false)
  }

  fun setModels(models: List<NdsProject.PropModelInfo>) {
    allModels = models
    modelLabels = models.associate { it.key to it.label }
    applyCatalogFilter()
  }

  private fun applyCatalogFilter() {
    val selected = (catalog.selectedItem as? NdsProject.PropModelInfo)?.catalogId
    val terms = search.text.trim().lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
    val models = if (terms.isEmpty()) allModels else allModels.filter { model ->
      val family = model.sourceFamily?.displayName.orEmpty()
      val haystack = "${model.label} ${model.category} $family ${model.sourceModelKey} " +
          model.sourceModelKey.removePrefix("rom:")
      terms.all(haystack.lowercase()::contains)
    }
    syncingCatalog = true
    catalog.removeAllItems()
    models.forEach(catalog::addItem)
    val index = selected?.let { id -> (0 until catalog.itemCount).firstOrNull { catalog.getItemAt(it).catalogId == id } }
    if (index != null) catalog.selectedIndex = index
    syncingCatalog = false
    refreshPreview()
  }

  fun selectModel(catalogId: String) {
    if ((0 until catalog.itemCount).none { catalog.getItemAt(it).catalogId == catalogId }) search.text = ""
    for (i in 0 until catalog.itemCount) if (catalog.getItemAt(i).catalogId == catalogId) {
      catalog.selectedIndex = i
      refreshPreview()
      return
    }
  }

  fun setMap(map: NdsMap?) {
    this.map = map
    selectedIds.clear()
    primaryId = null
    refreshProps()
  }

  fun refreshProps(preferredSelection: Set<String> = selectedIds, preferredPrimary: String? = primaryId) {
    val props = map?.props.orEmpty()
    propIds = props.map { it.id }
    syncing = true
    listModel.clear()
    props.forEachIndexed { index, p -> listModel.addElement(label(index, p)) }
    val valid = preferredSelection.filterTo(LinkedHashSet()) { it in propIds }
    propList.selectedIndices = valid.map(propIds::indexOf).filter { it >= 0 }.toIntArray()
    syncing = false
    selectProps(valid, preferredPrimary?.takeIf { it in valid } ?: valid.lastOrNull(), notify = false)
  }

  fun refreshProps(preferredSelection: String?) =
      refreshProps(preferredSelection?.let(::setOf).orEmpty(), preferredSelection)

  fun selectProp(id: String?, notify: Boolean = false) =
      selectProps(id?.let(::setOf).orEmpty(), id, notify)
  fun selectProps(ids: Set<String>, primary: String? = ids.lastOrNull(), notify: Boolean = false) {
    selectedIds = ids.filterTo(LinkedHashSet()) { it in propIds }
    primaryId = primary?.takeIf { it in selectedIds } ?: selectedIds.lastOrNull()
    val prop = map?.props?.firstOrNull { it.id == primaryId }
    syncing = true
    propList.selectedIndices = selectedIds.map(propIds::indexOf).filter { it >= 0 }.toIntArray()
    if (prop != null) {
      x.value = prop.x.toDouble(); y.value = prop.y.toDouble(); z.value = prop.z.toDouble()
      rx.value = prop.rotationX.toDouble(); ry.value = prop.rotationY.toDouble(); rz.value = prop.rotationZ.toDouble()
      sx.value = prop.scaleX.toDouble(); sy.value = prop.scaleY.toDouble(); sz.value = prop.scaleZ.toDouble()
    }
    syncing = false
    setTransformEnabled(prop != null)
    mergeButton.isEnabled = selectedIds.size >= 2
    if (notify) onSelect(selectedIds, primaryId)
  }

  private fun applyScale(source: JSpinner) {
    if (syncing) return
    if (linkScale.isSelected) {
      val value = source.value
      syncing = true
      if (source !== sx) sx.value = value
      if (source !== sy) sy.value = value
      if (source !== sz) sz.value = value
      syncing = false
    }
    applyTransform()
  }

  private fun applyTransform() {
    if (syncing) return
    val prop = map?.props?.firstOrNull { it.id == primaryId } ?: return
    prop.x = number(x); prop.y = number(y); prop.z = number(z)
    prop.rotationX = number(rx); prop.rotationY = number(ry); prop.rotationZ = number(rz)
    prop.scaleX = number(sx); prop.scaleY = number(sy); prop.scaleZ = number(sz)
    val index = propIds.indexOf(prop.id)
    if (index >= 0) listModel[index] = label(index, prop)
    onChanged()
  }

  private fun refreshPreview() {
    val selected = catalog.selectedItem as? NdsProject.PropModelInfo
    catalogCategory.text = selected?.let {
      val origin = it.sourceFamily?.displayName?.let { family -> " · $family" }.orEmpty()
      "Category: ${it.category}$origin"
    } ?: "No matching prop models"
    if (selected == null) {
      preview.modelTriangles = emptyList(); preview.modelTextures = emptyMap(); preview.modelPalettes = emptyMap()
      return
    }
    val data = onPreview(selected)
    preview.modelTriangles = data.triangles
    preview.modelTextures = data.textures
    preview.modelPalettes = data.palettes
  }

  private fun installCatalogCycling() {
    fun bind(keyCode: Int, actionName: String, delta: Int) {
      catalog.getInputMap(JComponent.WHEN_FOCUSED)
          .put(KeyStroke.getKeyStroke(keyCode, 0), actionName)
      catalog.actionMap.put(actionName, object : AbstractAction() {
        override fun actionPerformed(e: java.awt.event.ActionEvent?) {
          if (catalog.itemCount == 0) return
          catalog.isPopupVisible = false
          catalog.selectedIndex = (catalog.selectedIndex + delta).coerceIn(0, catalog.itemCount - 1)
        }
      })
    }
    bind(KeyEvent.VK_UP, "catalogPreviousNoPopup", -1)
    bind(KeyEvent.VK_LEFT, "catalogPreviousNoPopupLeft", -1)
    bind(KeyEvent.VK_DOWN, "catalogNextNoPopup", 1)
    bind(KeyEvent.VK_RIGHT, "catalogNextNoPopupRight", 1)
  }

  private fun setTransformEnabled(enabled: Boolean) {
    listOf(x, y, z, rx, ry, rz, sx, sy, sz).forEach { it.isEnabled = enabled }
    linkScale.isEnabled = enabled
    mergeButton.isEnabled = selectedIds.size >= 2
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
