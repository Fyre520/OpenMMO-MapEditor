package de.lananahwp.openmmo.mapeditor.ui

import de.lananahwp.openmmo.mapeditor.core.DecompBase
import de.lananahwp.openmmo.mapeditor.core.MapRenderer
import de.lananahwp.openmmo.mapeditor.core.RenderOverlay
import de.lananahwp.openmmo.mapeditor.json.Json
import de.lananahwp.openmmo.mapeditor.json.JsonWriter
import de.lananahwp.openmmo.mapeditor.model.EditorMap
import de.lananahwp.openmmo.mapeditor.model.MetatileBrush
import de.lananahwp.openmmo.mapeditor.project.DecompProject
import de.lananahwp.openmmo.mapeditor.project.OpenmmoExporter
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JRadioButtonMenuItem
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTextField
import javax.swing.JToolBar
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.DefaultListModel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.event.DocumentListener
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/** A loaded decomp project together with its renderer/exporter. */
private class ProjectHolder(val project: DecompProject) {
  val renderer = MapRenderer(project.source)
  val exporter = OpenmmoExporter(project)
}

/** Tree/list leaf: a map inside a specific decomp project. */
private class MapRef(val holder: ProjectHolder, val dirName: String) {
  val label: String get() = "${holder.project.region.name} — $dirName"
  override fun toString(): String = dirName
}

private enum class EditMode {
  TILE,
  COLLISION,
  ELEVATION,
  WARP,
}

private data class TileEdit(val x: Int, val y: Int, val before: Int, val after: Int)

/** Main map editor window. */
class EditorFrame(decompDirs: List<File>) : JFrame("OpenMMO Map Editor") {

  private val holders = LinkedHashMap<String, ProjectHolder>()
  private val allMaps = mutableListOf<MapRef>()
  private val rootNode = DefaultMutableTreeNode("Projects")
  private var currentHolder: ProjectHolder? = null
  private var currentMap: EditorMap? = null
  private var currentRef: MapRef? = null
  private var overlay = RenderOverlay.None
  private var editMode = EditMode.TILE
  private var dirty = false
  private var elevationBrush = 0
  private var collisionBrush = 1
  private var restoringSelection = false
  private var activeBrush = MetatileBrush.single(0)
  private val undoStack = ArrayDeque<List<TileEdit>>()
  private val redoStack = ArrayDeque<List<TileEdit>>()

  private val collisionPaint = JCheckBox("Paint collision")
  private val elevationPaint = JCheckBox("Paint elevation")
  private val warpPaint = JCheckBox("Place warp")
  private val eventOverlayPaint = JCheckBox("Events")

  private val tree = JTree().apply {
    selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
    isRootVisible = false
  }
  private val locationsModel = DefaultListModel<String>()
  private val locationsList = JList(locationsModel)
  private val locationsRefs = mutableListOf<MapRef>()

  private val canvas =
      MapCanvas(
          { x, y, mid -> paintBlock(x, y, mid) },
          { x, y -> hoverBlock(x, y) },
          { x, y -> pickBlock(x, y) },
      )
  private val zoomLabel = JLabel("100%")
  private val status = JLabel("Open a decomp (File -> Open Decomp)")

  private val metatileContainer = JPanel(BorderLayout())
  private val headerContainer = JPanel(BorderLayout())
  private val eventsPanel = EventsPanel()
  private var selector: MetatileSelector? = null
  private var selectorHolder: ProjectHolder? = null
  private var prefabPanel: PrefabPanel? = null
  private var headerPanel: HeaderPanel? = null
  private var headerHolder: ProjectHolder? = null

  private val groupsSearch = JTextField()
  private val locationsSearch = JTextField()

  init {
    defaultCloseOperation = DO_NOTHING_ON_CLOSE
    size = Dimension(1400, 860)
    setLocationRelativeTo(null)

    jMenuBar = buildMenuBar()
    contentPane.add(buildToolBar(), BorderLayout.NORTH)
    canvas.onZoomChanged = { updateZoomLabel() }

    addWindowListener(
        object : WindowAdapter() {
          override fun windowClosing(e: WindowEvent) {
            if (confirmMapChange()) dispose()
          }
        })

    // Build map navigation.
    val leftTabs = JTabbedPane()
    leftTabs.addTab("Groups", buildGroupsPanel())
    leftTabs.addTab("Locations", buildLocationsPanel())
    leftTabs.preferredSize = Dimension(280, 0)

    // Build editing tabs.
    val mainTabs = JTabbedPane()
    mainTabs.addTab("Map", buildMapTab())
    mainTabs.addTab("Header", headerContainer)
    mainTabs.addTab("Events", eventsPanel)

    eventsPanel.onAddWarp = { addWarpFromDialog() }
    eventsPanel.onRemoveWarp = { removeWarp(it) }
    eventsPanel.onConnectWarp = { connectWarp(it) }
    eventsPanel.onEditEvent = { kind, index -> editEvent(kind, index) }

    val mainSplit = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftTabs, mainTabs)
    mainSplit.resizeWeight = 0.0
    mainSplit.dividerLocation = 280
    contentPane.add(mainSplit, BorderLayout.CENTER)
    contentPane.add(status, BorderLayout.SOUTH)

    tree.addTreeSelectionListener { e ->
      if (restoringSelection) return@addTreeSelectionListener
      val userObject = (e.path?.lastPathComponent as? DefaultMutableTreeNode)?.userObject
      if (userObject is MapRef) openMap(userObject)
    }
    locationsList.addListSelectionListener {
      if (!it.valueIsAdjusting) {
        val idx = locationsList.selectedIndex
        if (idx in locationsRefs.indices) openMap(locationsRefs[idx])
      }
    }
    installSearch(groupsSearch) { rebuildTree() }
    installSearch(locationsSearch) { rebuildLocations() }

    tree.model = DefaultTreeModel(rootNode)
    for (dir in decompDirs) addProject(dir)
    selectFirstMap()
  }

  private fun installSearch(field: JTextField, action: () -> Unit) {
    field.document.addDocumentListener(
        object : DocumentListener {
          override fun insertUpdate(e: DocumentEvent) = action()
          override fun removeUpdate(e: DocumentEvent) = action()
          override fun changedUpdate(e: DocumentEvent) = action()
        })
  }

  private fun buildGroupsPanel(): JPanel {
    val panel = JPanel(BorderLayout())
    groupsSearch.putClientProperty("JTextField.placeholderText", "Search maps…")
    panel.add(groupsSearch, BorderLayout.NORTH)
    panel.add(JScrollPane(tree), BorderLayout.CENTER)
    return panel
  }

  private fun buildLocationsPanel(): JPanel {
    val panel = JPanel(BorderLayout())
    locationsSearch.putClientProperty("JTextField.placeholderText", "Search maps…")
    panel.add(locationsSearch, BorderLayout.NORTH)
    panel.add(JScrollPane(locationsList), BorderLayout.CENTER)
    return panel
  }

  private fun buildMapTab(): JPanel {
    val panel = JPanel(BorderLayout())

    val collisionValue = JComboBox(arrayOf("Passable", "Impassable"))
    val elevationSpinner = JSpinner(SpinnerNumberModel(0, 0, 15, 1))
    collisionValue.selectedIndex = 1
    elevationSpinner.preferredSize = Dimension(50, elevationSpinner.preferredSize.height)
    collisionValue.toolTipText = "Passable allows movement; impassable blocks movement"
    collisionPaint.toolTipText = "C is passable; numbered tiles are impassable"
    elevationSpinner.toolTipText = "Elevation value painted onto map blocks"
    eventOverlayPaint.toolTipText = "P person, S script, T trigger, W warp"
    warpPaint.toolTipText = "Place warps while displaying existing events"
    collisionValue.addActionListener { collisionBrush = collisionValue.selectedIndex }
    elevationSpinner.addChangeListener { elevationBrush = (elevationSpinner.value as Number).toInt() }
    val grid = JCheckBox("Grid").apply { isSelected = true }

    collisionPaint.addActionListener {
      if (collisionPaint.isSelected) {
        elevationPaint.isSelected = false
        warpPaint.isSelected = false
        editMode = EditMode.COLLISION
        overlay = RenderOverlay.Collision
      } else {
        editMode = EditMode.TILE
        if (overlay == RenderOverlay.Collision) overlay = RenderOverlay.None
      }
      updateEventOverlayVisibility()
      refreshMapImage()
    }
    elevationPaint.addActionListener {
      if (elevationPaint.isSelected) {
        collisionPaint.isSelected = false
        warpPaint.isSelected = false
        editMode = EditMode.ELEVATION
        overlay = RenderOverlay.Elevation
      } else {
        editMode = EditMode.TILE
        if (overlay == RenderOverlay.Elevation) overlay = RenderOverlay.None
      }
      updateEventOverlayVisibility()
      refreshMapImage()
    }
    warpPaint.addActionListener {
      if (warpPaint.isSelected) {
        collisionPaint.isSelected = false
        elevationPaint.isSelected = false
        eventOverlayPaint.isSelected = false
        editMode = EditMode.WARP
        if (overlay != RenderOverlay.None) {
          overlay = RenderOverlay.None
          refreshMapImage()
        }
      } else {
        editMode = EditMode.TILE
      }
      updateEventOverlayVisibility()
    }
    eventOverlayPaint.addActionListener {
      updateEventOverlayVisibility()
    }
    grid.addActionListener {
      canvas.showGrid = grid.isSelected
      canvas.repaint()
    }
    val dimensions = JButton("Change Dimensions…")
    dimensions.addActionListener { changeDimensions() }

    val mapToolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2))
    mapToolbar.add(collisionPaint)
    mapToolbar.add(collisionValue)
    mapToolbar.add(elevationPaint)
    mapToolbar.add(JLabel("Value:"))
    mapToolbar.add(elevationSpinner)
    mapToolbar.add(warpPaint)
    mapToolbar.add(eventOverlayPaint)
    mapToolbar.add(grid)
    mapToolbar.add(dimensions)
    panel.add(mapToolbar, BorderLayout.NORTH)

    val canvasScroll = JScrollPane(canvas).apply {
      horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_ALWAYS
      verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_ALWAYS
    }
    val mapSplit = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, canvasScroll, metatileContainer)
    mapSplit.resizeWeight = 1.0
    mapSplit.dividerLocation = 1000
    panel.add(mapSplit, BorderLayout.CENTER)
    return panel
  }

  private fun buildMenuBar(): JMenuBar {
    val bar = JMenuBar()

    val file = JMenu("File")
    file.add(JMenuItem("Open Decomp…").apply { addActionListener { chooseProject() } })
    file.add(JMenuItem("New Map…").apply { addActionListener { newMap() } })
    file.add(
        JMenuItem("Duplicate as Runtime Override…").apply {
          addActionListener { duplicateAsOverride() }
        })
    file.add(
        JMenuItem("Save Map").apply {
          accelerator = KeyStroke.getKeyStroke("control S")
          addActionListener { save() }
        })
    file.addSeparator()
    file.add(JMenuItem("Export Runtime Map…").apply { addActionListener { exportCurrent() } })
    file.add(JMenuItem("Export All Runtime Maps…").apply { addActionListener { exportAll() } })
    bar.add(file)

    val edit = JMenu("Edit")
    edit.add(
        JMenuItem("Undo").apply {
          accelerator = KeyStroke.getKeyStroke("control Z")
          addActionListener { undo() }
        })
    edit.add(
        JMenuItem("Redo").apply {
          accelerator = KeyStroke.getKeyStroke("control Y")
          addActionListener { redo() }
        })
    bar.add(edit)

    val view = JMenu("View")
    val overlayGroup = ButtonGroup()
    for ((name, o) in
        listOf(
            "No Overlay" to RenderOverlay.None,
            "Collision" to RenderOverlay.Collision,
            "Elevation" to RenderOverlay.Elevation)) {
      val item = JRadioButtonMenuItem(name)
      item.isSelected = o == RenderOverlay.None
      item.addActionListener {
        overlay = o
        refreshMapImage()
      }
      overlayGroup.add(item)
      view.add(item)
    }
    bar.add(view)

    return bar
  }

  private fun buildToolBar(): JToolBar {
    val tb = JToolBar()
    tb.isFloatable = false
    tb.add(JButton("−").apply {
      isFocusPainted = false
      toolTipText = "Zoom out"
      addActionListener { zoomBy(1 / 1.25) }
    })
    tb.add(JButton("+").apply {
      isFocusPainted = false
      toolTipText = "Zoom in"
      addActionListener { zoomBy(1.25) }
    })
    tb.add(zoomLabel)
    tb.addSeparator()
    tb.add(JButton("Fit").apply {
      isFocusPainted = false
      toolTipText = "Fit map to view"
      addActionListener { fitZoom() }
    })
    tb.addSeparator()
    tb.add(JLabel("  Draw: left drag  Pick: right click  Zoom: Ctrl+wheel"))
    return tb
  }

  private fun zoomBy(factor: Double) {
    canvas.zoom = canvas.zoom * factor
    updateZoomLabel()
  }

  private fun updateZoomLabel() {
    zoomLabel.text = "${(canvas.zoom * 100).toInt()}%"
  }

  private fun fitZoom() {
    val img = canvas.mapImage ?: return
    val scroll =
        SwingUtilities.getAncestorOfClass(JScrollPane::class.java, canvas) as? JScrollPane ?: return
    val view = scroll.viewport.extentSize
    val zx = view.width.toDouble() / img.width
    val zy = view.height.toDouble() / img.height
    canvas.zoom = minOf(zx, zy).coerceIn(0.25, 8.0)
    updateZoomLabel()
  }

  private fun chooseProject() {
    val chooser = JFileChooser().apply {
      dialogTitle = "Select a decomp directory (pokeemerald or pokefirered)"
      fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
      currentDirectory = defaultDecompDir() ?: File(".")
    }
    if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
      groupsSearch.text = ""
      addProject(chooser.selectedFile)?.let { selectFirstMap(it) }
    }
  }

  private fun defaultDecompDir(): File? {
    var dir = File(".").absoluteFile
    repeat(10) {
      val d = File(dir, "decomp")
      if (File(d, "pokeemerald").isDirectory) return d
      dir = dir.parentFile ?: return null
    }
    return null
  }

  private fun addProject(dir: File): ProjectHolder? {
    try {
      val root = dir.canonicalFile
      holders[root.path]?.let { return it }
      val source = DecompBase(root)
      val project = DecompProject(root, source)
      val holder = ProjectHolder(project)
      holders[root.path] = holder
      rebuildAllProjects()
      status.text = "Opened $root"
      return holder
    } catch (t: Throwable) {
      JOptionPane.showMessageDialog(
          this, t.message ?: t.toString(), "Open failed", JOptionPane.ERROR_MESSAGE)
      return null
    }
  }

  private fun newMap() {
    val holder = currentHolder
    if (holder == null) {
      JOptionPane.showMessageDialog(
          this, "Open a decomp first, then create a map.", "New Map", JOptionPane.WARNING_MESSAGE)
      return
    }
    val dialog = NewMapDialog(holder.project)
    dialog.isVisible = true
    val p = dialog.params ?: return
    try {
      val layoutId = if (p.newLayout) "LAYOUT_${p.dirName}" else p.layoutId
      if (p.newLayout) {
        require(layoutId !in holder.project.layouts) { "Layout '$layoutId' already exists" }
        holder.project.createLayout(layoutId, p.width, p.height, p.primary, p.secondary, 0)
      }
      holder.project.createMap(
          p.groupIndex, p.index, p.dirName, p.mapConstant, p.name, layoutId,
          p.music, p.mapsec, p.weather, p.mapType, p.requiresFlash)
      holder.project.refresh()
      groupsSearch.text = ""
      locationsSearch.text = ""
      rebuildAllProjects()
      selectMap(holder, p.dirName)
      status.text = "Created map ${p.dirName} in ${holder.project.region.name}"
    } catch (t: Throwable) {
      JOptionPane.showMessageDialog(
          this, t.message ?: t.toString(), "New Map failed", JOptionPane.ERROR_MESSAGE)
    }
  }

  private fun duplicateAsOverride() {
    val holder = currentHolder ?: return
    val map = currentMap ?: return
    var suggested = "${map.dirName}_Custom"
    var suffix = 2
    while (holder.project.mapExists(suggested)) suggested = "${map.dirName}_Custom${suffix++}"
    val directory = JTextField(suggested, 28)
    val displayName = JTextField("${map.name} (Custom)", 28)
    val panel = JPanel(GridLayout(0, 2, 6, 4))
    panel.add(JLabel("Directory name"))
    panel.add(directory)
    panel.add(JLabel("Display name"))
    panel.add(displayName)
    val result =
        JOptionPane.showConfirmDialog(
            this,
            panel,
            "Duplicate as Runtime Override",
            JOptionPane.OK_CANCEL_OPTION,
        )
    if (result != JOptionPane.OK_OPTION) return
    try {
      val duplicate =
          holder.project.duplicateAsOverride(
              map,
              directory.text.trim(),
              displayName.text.trim().ifEmpty { map.name },
          )
      dirty = false
      groupsSearch.text = ""
      locationsSearch.text = ""
      rebuildAllProjects()
      selectMap(holder, duplicate.dirName)
      status.text =
          "Created ${duplicate.dirName} targeting ${holder.project.wireBank(duplicate.exportGroupIndex)}:${duplicate.exportMapIndex}"
    } catch (t: Throwable) {
      JOptionPane.showMessageDialog(
          this,
          t.message ?: t.toString(),
          "Duplicate failed",
          JOptionPane.ERROR_MESSAGE,
      )
    }
  }

  /** Rebuilds project navigation. */
  private fun rebuildAllProjects() {
    allMaps.clear()
    for (h in holders.values) {
      for (group in h.project.groupOrder) {
        for (mapDir in h.project.groupMaps[group].orEmpty()) {
          allMaps += MapRef(h, mapDir)
        }
      }
    }
    rebuildLocations()
    rebuildTree()
  }

  private fun selectMap(holder: ProjectHolder, dirName: String) {
    val model = tree.model as DefaultTreeModel
    var path: TreePath? = null
    fun search(node: DefaultMutableTreeNode) {
      for (i in 0 until node.childCount) {
        val child = node.getChildAt(i) as DefaultMutableTreeNode
        val ref = child.userObject as? MapRef
        if (child.isLeaf && ref?.holder === holder && ref.dirName == dirName) {
          path = TreePath(model.getPathToRoot(child))
          return
        }
        search(child)
      }
    }
    search(rootNode)
    if (path != null) tree.selectionPath = path
  }

  private fun rebuildTree() {
    val filter = groupsSearch.text.trim()
    rootNode.removeAllChildren()
    for (holder in holders.values) {
      val projectNode =
          DefaultMutableTreeNode("${holder.project.region.name} — ${holder.project.rootDir.name}")
      for (group in holder.project.groupOrder) {
        val groupNode = DefaultMutableTreeNode(group.removePrefix("gMapGroup_"))
        for (mapDir in holder.project.groupMaps[group].orEmpty()) {
          if (filter.isEmpty() || mapDir.contains(filter, ignoreCase = true)) {
            groupNode.add(DefaultMutableTreeNode(MapRef(holder, mapDir)))
          }
        }
        if (groupNode.childCount > 0) projectNode.add(groupNode)
      }
      if (projectNode.childCount > 0) rootNode.add(projectNode)
    }
    val model = DefaultTreeModel(rootNode)
    tree.model = model
    expandAll(model, rootNode)
  }

  private fun rebuildLocations() {
    val filter = locationsSearch.text.trim()
    locationsModel.clear()
    locationsRefs.clear()
    for (ref in allMaps) {
      if (filter.isEmpty() || ref.dirName.contains(filter, ignoreCase = true)) {
        locationsRefs += ref
        locationsModel.addElement(ref.label)
      }
    }
  }

  private fun expandAll(model: DefaultTreeModel, node: DefaultMutableTreeNode) {
    for (i in 0 until node.childCount) expandAll(model, node.getChildAt(i) as DefaultMutableTreeNode)
    if (!node.isLeaf) tree.expandPath(TreePath(model.getPathToRoot(node)))
  }

  private fun selectFirstMap() {
    if (rootNode.childCount == 0) return
    val projNode = rootNode.getChildAt(0) as DefaultMutableTreeNode
    if (projNode.childCount == 0) return
    val groupNode = projNode.getChildAt(0) as DefaultMutableTreeNode
    if (groupNode.childCount == 0) return
    tree.selectionPath = TreePath((tree.model as DefaultTreeModel).getPathToRoot(groupNode.getChildAt(0)))
  }

  private fun selectFirstMap(holder: ProjectHolder) {
    val first = allMaps.firstOrNull { it.holder === holder } ?: return
    selectMap(holder, first.dirName)
  }

  private fun openMap(ref: MapRef) {
    val changedMap = currentRef?.let { it.holder !== ref.holder || it.dirName != ref.dirName } ?: false
    if (changedMap && !confirmMapChange()) {
      restoreCurrentSelection()
      return
    }
    val map = ref.holder.project.loadMap(ref.dirName) ?: run {
      JOptionPane.showMessageDialog(
          this, "Cannot load map ${ref.dirName}", "Load failed", JOptionPane.WARNING_MESSAGE)
      return
    }
    currentHolder = ref.holder
    currentMap = map
    currentRef = ref
    undoStack.clear()
    redoStack.clear()
    canvas.blockWidth = map.layout.width
    canvas.blockHeight = map.layout.height

    if (selector == null || selectorHolder !== ref.holder) {
      val newSelector = MetatileSelector(ref.holder.project.source)
      newSelector.addSelectionListener {
        activeBrush = newSelector.selectedBrush
        canvas.brush = newSelector.selectedId
        selectEditMode(EditMode.TILE)
      }
      val newPrefabs =
          PrefabPanel(
              ref.holder.project.source,
              ref.holder.project.rootDir,
              { newSelector.selectedBrush },
              { collisionBrush to elevationBrush },
          ) { brush ->
            activeBrush = brush
            canvas.brush = brush.blocks.firstOrNull { it >= 0 }?.and(0x3FF) ?: 0
            selectEditMode(EditMode.TILE)
            status.text = "Selected ${brush.width}×${brush.height} prefab"
          }
      selector = newSelector
      selectorHolder = ref.holder
      prefabPanel = newPrefabs
      metatileContainer.removeAll()
      val tabs = JTabbedPane()
      tabs.addTab("Metatiles", newSelector.component())
      tabs.addTab("Prefabs", newPrefabs)
      metatileContainer.add(tabs, BorderLayout.CENTER)
      metatileContainer.revalidate()
      metatileContainer.repaint()
    }
    selector?.setTilesets(map.layout.primaryTileset, map.layout.secondaryTileset)
    prefabPanel?.setTilesets(map.layout.primaryTileset, map.layout.secondaryTileset)

    if (headerPanel == null || headerHolder !== ref.holder) {
      val newHeader = HeaderPanel(ref.holder.project.source) { w, h, p, s -> applyHeader(w, h, p, s) }
      headerPanel = newHeader
      headerHolder = ref.holder
      headerContainer.removeAll()
      headerContainer.add(newHeader, BorderLayout.CENTER)
      headerContainer.revalidate()
      headerContainer.repaint()
    }
    headerPanel?.setMap(map)
    eventsPanel.setMap(map)
    refreshEventOverlay()

    dirty = false
    updateTitle()
    refreshMapImage()
  }

  private fun restoreCurrentSelection() {
    val ref = currentRef ?: return
    restoringSelection = true
    try {
      selectMap(ref.holder, ref.dirName)
    } finally {
      restoringSelection = false
    }
  }

  private fun confirmMapChange(): Boolean {
    if (!dirty) return true
    val name = currentMap?.dirName ?: "current map"
    return when (
        JOptionPane.showConfirmDialog(
            this,
            "Save changes to $name?",
            "Unsaved changes",
            JOptionPane.YES_NO_CANCEL_OPTION,
        )) {
      JOptionPane.YES_OPTION -> save()
      JOptionPane.NO_OPTION -> true
      else -> false
    }
  }

  private fun selectEditMode(mode: EditMode) {
    editMode = mode
    if (mode != EditMode.COLLISION && overlay == RenderOverlay.Collision) {
      overlay = RenderOverlay.None
      refreshMapImage()
    }
    if (mode != EditMode.ELEVATION && overlay == RenderOverlay.Elevation) {
      overlay = RenderOverlay.None
      refreshMapImage()
    }
    collisionPaint.isSelected = mode == EditMode.COLLISION
    elevationPaint.isSelected = mode == EditMode.ELEVATION
    warpPaint.isSelected = mode == EditMode.WARP
    updateEventOverlayVisibility()
  }

  private fun updateEventOverlayVisibility() {
    when {
      eventOverlayPaint.isSelected -> {
        canvas.visibleEventTypes = MapEventType.entries.toSet()
        canvas.showEventOverlay = true
      }
      warpPaint.isSelected -> {
        canvas.visibleEventTypes = setOf(MapEventType.WARP)
        canvas.showEventOverlay = true
      }
      else -> canvas.showEventOverlay = false
    }
  }

  private fun applyHeader(width: Int, height: Int, primary: String, secondary: String) {
    val map = currentMap ?: return
    map.layout.resize(width, height, canvas.brush)
    map.layout.primaryTileset = primary
    map.layout.secondaryTileset = secondary
    undoStack.clear()
    redoStack.clear()
    canvas.blockWidth = map.layout.width
    canvas.blockHeight = map.layout.height
    headerPanel?.setMap(map)
    markDirty()
    refreshMapImage()
    status.text = "Applied layout changes to ${map.dirName}"
  }

  private fun changeDimensions() {
    val map = currentMap ?: return
    val w = JSpinner(SpinnerNumberModel(map.layout.width, 1, 1024, 1))
    val h = JSpinner(SpinnerNumberModel(map.layout.height, 1, 1024, 1))
    val panel = JPanel()
    panel.add(JLabel("Width:")); panel.add(w)
    panel.add(JLabel("Height:")); panel.add(h)
    val ok =
        JOptionPane.showConfirmDialog(
            this, panel, "Change Dimensions", JOptionPane.OK_CANCEL_OPTION)
    if (ok == JOptionPane.OK_OPTION) {
      applyHeader(
          (w.value as Number).toInt(),
          (h.value as Number).toInt(),
          map.layout.primaryTileset,
          map.layout.secondaryTileset)
    }
  }

  private fun refreshMapImage() {
    val map = currentMap ?: return
    val holder = currentHolder ?: return
    canvas.mapImage = holder.renderer.renderMap(map.layout, overlay)
  }

  private fun paintBlock(x: Int, y: Int, brushValue: Int) {
    if (editMode == EditMode.WARP) {
      addWarp(x, y, elevationBrush)
      selectEditMode(EditMode.TILE)
      return
    }
    val map = currentMap ?: return
    val holder = currentHolder ?: return
    if (editMode == EditMode.TILE && paintBrush(x, y)) return
    val i = y * map.layout.width + x
    if (i !in map.layout.blocks.indices) return
    val current = map.layout.blocks[i]
    val newBlock =
        when (editMode) {
          EditMode.COLLISION -> (current and 0xF3FF) or (collisionBrush shl 10)
          EditMode.ELEVATION -> (current and 0x0FFF) or (elevationBrush shl 12)
          else -> (current and 0xFC00) or (brushValue and 0x3FF)
        }
    if (newBlock == current) return
    map.layout.blocks[i] = newBlock
    undoStack.addLast(listOf(TileEdit(x, y, current, newBlock)))
    redoStack.clear()
    markDirty()
    if (editMode == EditMode.TILE) canvas.brush = brushValue
    val img =
        holder.renderer.blockImage(
            map.layout.primaryTileset, map.layout.secondaryTileset, newBlock, overlay)
    canvas.updateBlock(x, y, img)
    status.text =
        when (editMode) {
          EditMode.COLLISION ->
              "Set collision=${(newBlock shr 10) and 0x3} at (%d, %d)".format(x, y)
          EditMode.ELEVATION ->
              "Set elevation=$elevationBrush at (%d, %d)".format(x, y)
          else -> "Painted 0x%03X at (%d, %d)".format(brushValue, x, y)
        }
  }

  private fun paintBrush(anchorX: Int, anchorY: Int): Boolean {
    val map = currentMap ?: return false
    val holder = currentHolder ?: return false
    if (activeBrush.width == 1 && activeBrush.height == 1 && !activeBrush.includesAttributes) {
      return false
    }
    val edits = mutableListOf<TileEdit>()
    for (dy in 0 until activeBrush.height) {
      for (dx in 0 until activeBrush.width) {
        val x = anchorX + dx
        val y = anchorY + dy
        if (x !in 0 until map.layout.width || y !in 0 until map.layout.height) continue
        val brushBlock = activeBrush.blockAt(dx, dy)
        if (brushBlock < 0) continue
        val index = y * map.layout.width + x
        val current = map.layout.blocks[index]
        val replacement =
            if (activeBrush.includesAttributes) brushBlock
            else (current and 0xFC00) or (brushBlock and 0x3FF)
        if (replacement == current) continue
        map.layout.blocks[index] = replacement
        edits += TileEdit(x, y, current, replacement)
        canvas.updateBlock(
            x,
            y,
            holder.renderer.blockImage(
                map.layout.primaryTileset,
                map.layout.secondaryTileset,
                replacement,
                overlay,
            ),
        )
      }
    }
    if (edits.isEmpty()) return true
    undoStack.addLast(edits)
    redoStack.clear()
    markDirty()
    status.text = "Painted ${activeBrush.width}×${activeBrush.height} selection"
    return true
  }

  private fun pickBlock(x: Int, y: Int) {
    val map = currentMap ?: return
    val block = map.layout.tileAt(x, y) ?: return
    val metatile = block and 0x3FF
    selector?.selectMetatile(metatile)
    activeBrush = MetatileBrush.single(metatile)
    canvas.brush = metatile
    selectEditMode(EditMode.TILE)
    status.text = "Picked 0x%03X at (%d, %d)".format(metatile, x, y)
  }

  private fun undo() {
    val edits = undoStack.removeLastOrNull() ?: return
    for (edit in edits.asReversed()) applyTileEdit(edit.x, edit.y, edit.before)
    redoStack.addLast(edits)
    status.text = "Undid ${edits.size} tile edit(s)"
  }

  private fun redo() {
    val edits = redoStack.removeLastOrNull() ?: return
    for (edit in edits) applyTileEdit(edit.x, edit.y, edit.after)
    undoStack.addLast(edits)
    status.text = "Redid ${edits.size} tile edit(s)"
  }

  private fun applyTileEdit(x: Int, y: Int, value: Int) {
    val map = currentMap ?: return
    val holder = currentHolder ?: return
    val index = y * map.layout.width + x
    if (index !in map.layout.blocks.indices) return
    map.layout.blocks[index] = value
    markDirty()
    canvas.updateBlock(
        x,
        y,
        holder.renderer.blockImage(
            map.layout.primaryTileset,
            map.layout.secondaryTileset,
            value,
            overlay,
        ),
    )
  }

  private fun addWarp(x: Int, y: Int, elev: Int) {
    val map = currentMap ?: return
    val entry =
        Json.JObj(
            linkedMapOf(
                "x" to Json.JNum(x.toDouble()),
                "y" to Json.JNum(y.toDouble()),
                "elevation" to Json.JNum(elev.toDouble()),
                "dest_map" to Json.JStr("MAP_DYNAMIC"),
                "dest_warp_id" to Json.JStr("0"),
            ))
    val a = map.mapJson.arr("warp_events")
    val items = (a?.items ?: emptyList()) + entry
    map.mapJson.entries["warp_events"] = Json.JArr(items)
    markDirty()
    eventsPanel.setMap(map)
    refreshEventOverlay()
    status.text = "Added warp at (%d, %d) elev=%d".format(x, y, elev)
  }

  private fun addWarpFromDialog() {
    val map = currentMap ?: return
    val x = JSpinner(SpinnerNumberModel(0, 0, map.layout.width - 1, 1))
    val y = JSpinner(SpinnerNumberModel(0, 0, map.layout.height - 1, 1))
    val elevation = JSpinner(SpinnerNumberModel(3, 0, 15, 1))
    val panel = JPanel()
    panel.add(JLabel("X:"))
    panel.add(x)
    panel.add(JLabel("Y:"))
    panel.add(y)
    panel.add(JLabel("Elevation:"))
    panel.add(elevation)
    val result =
        JOptionPane.showConfirmDialog(this, panel, "Add Warp", JOptionPane.OK_CANCEL_OPTION)
    if (result == JOptionPane.OK_OPTION) {
      addWarp(
          (x.value as Number).toInt(),
          (y.value as Number).toInt(),
          (elevation.value as Number).toInt(),
      )
    }
  }

  private fun removeWarp(idx: Int) {
    val map = currentMap ?: return
    val a = map.mapJson.arr("warp_events") ?: return
    val items = a.items.toMutableList()
    if (idx !in items.indices) return
    items.removeAt(idx)
    map.mapJson.entries["warp_events"] = Json.JArr(items)
    markDirty()
    eventsPanel.setMap(map)
    refreshEventOverlay()
    status.text = "Removed warp $idx"
  }

  private fun editEvent(kind: EventKind, index: Int) {
    val map = currentMap ?: return
    val event =
        when (kind) {
          EventKind.WARP -> map.warps.getOrNull(index)
          EventKind.OBJECT -> map.objects.getOrNull(index)
          EventKind.BACKGROUND -> map.bgEvents.getOrNull(index)
          EventKind.COORDINATE -> map.coordEvents.getOrNull(index)
        } ?: return
    val fields =
        when (kind) {
          EventKind.WARP ->
              listOf("x" to true, "y" to true, "elevation" to true, "dest_map" to false, "dest_warp_id" to false)
          EventKind.OBJECT ->
              listOf(
                  "x" to true,
                  "y" to true,
                  "elevation" to true,
                  "graphics_id" to false,
                  "movement_type" to false,
                  "movement_range_x" to true,
                  "movement_range_y" to true,
                  "trainer_type" to false,
                  "script" to false,
                  "flag" to false,
              )
          EventKind.BACKGROUND ->
              listOf(
                  "x" to true,
                  "y" to true,
                  "elevation" to true,
                  "kind" to false,
                  "player_facing_dir" to false,
                  "script" to false,
              )
          EventKind.COORDINATE ->
              listOf(
                  "x" to true,
                  "y" to true,
                  "elevation" to true,
                  "type" to false,
                  "var" to false,
                  "var_value" to false,
                  "script" to false,
              )
        }
    val editors = LinkedHashMap<String, JTextField>()
    val panel = JPanel(GridLayout(0, 2, 6, 4))
    for ((key, _) in fields) {
      val value = event.get(key)?.asStr() ?: event.get(key)?.asInt()?.toString().orEmpty()
      val field = JTextField(value, 24)
      editors[key] = field
      panel.add(JLabel(key))
      panel.add(field)
    }
    val result =
        JOptionPane.showConfirmDialog(
            this,
            panel,
            "Edit ${kind.name.lowercase()} event",
            JOptionPane.OK_CANCEL_OPTION,
        )
    if (result != JOptionPane.OK_OPTION) return
    for ((key, numeric) in fields) {
      val value = editors.getValue(key).text.trim()
      if (numeric) {
        val number = value.toIntOrNull()
        if (number == null) {
          JOptionPane.showMessageDialog(
              this,
              "$key must be an integer.",
              "Invalid event",
              JOptionPane.WARNING_MESSAGE,
          )
          return
        }
        event.entries[key] = Json.JNum(number.toDouble())
      } else {
        event.entries[key] = Json.JStr(value)
      }
    }
    markDirty()
    eventsPanel.setMap(map)
    refreshEventOverlay()
    status.text = "Updated ${kind.name.lowercase()} event $index"
  }

  private fun refreshEventOverlay() {
    val map = currentMap
    if (map == null) {
      canvas.eventMarkers = emptyList()
      return
    }
    val markers = mutableListOf<MapEventMarker>()
    fun add(events: List<Json.JObj>, type: MapEventType) {
      for (event in events) {
        val x = event.int("x") ?: continue
        val y = event.int("y") ?: continue
        markers += MapEventMarker(x, y, type)
      }
    }
    add(map.objects, MapEventType.PERSON)
    add(map.bgEvents, MapEventType.SCRIPT)
    add(map.coordEvents, MapEventType.TRIGGER)
    add(map.warps, MapEventType.WARP)
    canvas.eventMarkers = markers
  }

  private fun connectWarp(idx: Int) {
    val map = currentMap ?: return
    if (idx !in map.warps.indices) return
    val holder = currentHolder ?: return
    val allDirs =
        holder.project.groupOrder.flatMap { group -> holder.project.groupMaps[group].orEmpty() }
    if (allDirs.isEmpty()) {
      JOptionPane.showMessageDialog(
          this, "No maps available as destinations.", "Connect Warp", JOptionPane.WARNING_MESSAGE)
      return
    }
    val dialog = ConnectWarpDialog(idx, map, allDirs, holder.project::readMapJson)
    dialog.isVisible = true
    val r = dialog.result ?: return
    val srcWarp = map.warps[idx]
    srcWarp.entries["dest_map"] = Json.JStr(r.destMapId)
    srcWarp.entries["dest_warp_id"] = Json.JStr(r.destWarpIdx.toString())
    markDirty()
    eventsPanel.setMap(map)
    if (r.bidirectional) {
      val destJson = holder.project.readMapJson(r.destDirName)
      val destWarps = destJson?.arr("warp_events")?.items
      val destWarp = destWarps?.getOrNull(r.destWarpIdx)?.asObj()
      if (destWarp != null) {
        destWarp.entries["dest_map"] = Json.JStr(map.id)
        destWarp.entries["dest_warp_id"] = Json.JStr(idx.toString())
        val destDir = File(holder.project.rootDir, "data/maps/${r.destDirName}")
        destDir.mkdirs()
        File(destDir, "map.json")
            .writeText(
                de.lananahwp.openmmo.mapeditor.json.JsonWriter.writePretty(
                    destJson as de.lananahwp.openmmo.mapeditor.json.Json) + "\n")
        status.text =
            "Connected warp $idx ↔ ${r.destDirName}[${r.destWarpIdx}] (bidirectional)"
        return
      }
    }
    status.text = "Connected warp $idx → ${r.destMapId}[${r.destWarpIdx}]"
  }

  private fun hoverBlock(x: Int, y: Int) {
    val map = currentMap ?: return
    val block = map.layout.tileAt(x, y) ?: return
    val mid = block and 0x3FF
    val collision = (block shr 10) and 0x3
    val elevation = (block shr 12) and 0xF
    status.text =
        "(%d, %d)  metatile=0x%03X  collision=%d  elevation=%d".format(x, y, mid, collision, elevation)
  }

  private fun save(): Boolean {
    val holder = currentHolder ?: return false
    val map = currentMap ?: return false
    try {
      holder.project.save(map)
      dirty = false
      updateTitle()
      status.text = "Saved ${map.dirName}"
      return true
    } catch (t: Throwable) {
      JOptionPane.showMessageDialog(
          this, t.message ?: t.toString(), "Save failed", JOptionPane.ERROR_MESSAGE)
      return false
    }
  }

  private fun markDirty() {
    dirty = true
    updateTitle()
  }

  private fun updateTitle() {
    val mapName = currentMap?.dirName?.let { " — $it" }.orEmpty()
    title = "OpenMMO Map Editor$mapName${if (dirty) " *" else ""}"
  }

  private fun exportCurrent() {
    val holder = currentHolder ?: return
    val map = currentMap ?: return
    if (dirty && !save()) return
    val dir = chooseExportDir() ?: return
    try {
      val f = holder.exporter.exportMap(map, dir)
      JOptionPane.showMessageDialog(
          this, "Wrote ${f.absolutePath}", "Export", JOptionPane.INFORMATION_MESSAGE)
    } catch (t: Throwable) {
      JOptionPane.showMessageDialog(
          this, t.message ?: t.toString(), "Export failed", JOptionPane.ERROR_MESSAGE)
    }
  }

  private fun exportAll() {
    val holder = currentHolder ?: return
    if (dirty && !save()) return
    val dir = chooseExportDir() ?: return
    try {
      val files = holder.exporter.exportAll(dir)
      JOptionPane.showMessageDialog(
          this, "Wrote ${files.size} files to ${dir.absolutePath}", "Export", JOptionPane.INFORMATION_MESSAGE)
    } catch (t: Throwable) {
      JOptionPane.showMessageDialog(
          this, t.message ?: t.toString(), "Export failed", JOptionPane.ERROR_MESSAGE)
    }
  }

  private fun chooseExportDir(): File? {
    val chooser = JFileChooser().apply {
      dialogTitle = "Choose export directory"
      fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    }
    return if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
  }

  companion object {
    @JvmStatic
    fun show(dirs: List<File>) {
      SwingUtilities.invokeLater { EditorFrame(dirs).isVisible = true }
    }
  }
}
