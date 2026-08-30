package de.lananahwp.openmmo.mapeditor.ui

import de.lananahwp.openmmo.mapeditor.core.DecompBase
import de.lananahwp.openmmo.mapeditor.core.MapRenderer
import de.lananahwp.openmmo.mapeditor.core.NdsTileset
import de.lananahwp.openmmo.mapeditor.core.RenderOverlay
import de.lananahwp.openmmo.mapeditor.json.Json
import de.lananahwp.openmmo.mapeditor.json.JsonParser
import de.lananahwp.openmmo.mapeditor.json.JsonWriter
import de.lananahwp.openmmo.mapeditor.model.EditorMap
import de.lananahwp.openmmo.mapeditor.model.MetatileBrush
import de.lananahwp.openmmo.mapeditor.model.NdsGrid
import de.lananahwp.openmmo.mapeditor.model.NdsMap
import de.lananahwp.openmmo.mapeditor.project.DecompProject
import de.lananahwp.openmmo.mapeditor.project.NdsExporter
import de.lananahwp.openmmo.mapeditor.project.NdsProject
import de.lananahwp.openmmo.mapeditor.project.OpenmmoExporter
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.InputEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
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
import javax.swing.JPopupMenu
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
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.math.ceil
import kotlin.math.sqrt

/** A loaded decomp project together with its renderer/exporter. */
private class ProjectHolder(val project: DecompProject) {
  val renderer = MapRenderer(project.source)
  val exporter = OpenmmoExporter(project)
}

/** A loaded Gen 4 DS project together with its exporter. */
private class NdsHolder(val project: NdsProject) {
  val exporter = NdsExporter(project)
}

/** Tree/list leaf: a map inside a specific decomp project. */
private class MapRef(val holder: ProjectHolder, val dirName: String) {
  val label: String get() = "${holder.project.region.name} — $dirName"
  override fun toString(): String = dirName
}

/** Tree/list leaf: a map inside a specific Gen 4 DS project. */
private class NdsRef(val holder: NdsHolder, val name: String) {
  val label: String get() = "${holder.project.family.regionName} — $name"
  override fun toString(): String = name
}

/** One destination offered at the top of the New Map flow. */
private class NewMapTarget(
    val gba: ProjectHolder? = null,
    val nds: NdsHolder? = null,
) {
  override fun toString(): String = when {
    gba != null -> "${gba.project.region.name} — Game Boy Advance (${gba.project.rootDir.name})"
    nds != null -> "${nds.project.family.regionName.replaceFirstChar { it.uppercase() }} — ${nds.project.family.displayName} (${nds.project.rootDir.name})"
    else -> "Unknown region"
  }
}

private enum class EditMode {
  TILE,
  SELECT,
  COLLISION,
  ELEVATION,
  WARP,
  EVENTS,
  FILL,
}

private data class TileEdit(val x: Int, val y: Int, val before: Int, val after: Int)

private data class CopiedEvent(val type: MapEventType, val event: Json.JObj)

private data class CopiedEvents(val type: MapEventType, val events: List<Json.JObj>)

/** Main map editor window. */
class EditorFrame(decompDirs: List<File>) : JFrame("OpenMMO Map Editor") {

  private val holders = LinkedHashMap<String, ProjectHolder>()
  private val ndsHolders = LinkedHashMap<String, NdsHolder>()
  private val allMaps = mutableListOf<MapRef>()
  private val allNdsMaps = mutableListOf<NdsRef>()
  private val rootNode = DefaultMutableTreeNode("Projects")
  private var recentList = mutableListOf<String>()
  private lateinit var recentMenu: JMenu
  private var currentHolder: ProjectHolder? = null
  private var currentMap: EditorMap? = null
  private var currentRef: MapRef? = null
  private var currentNdsHolder: NdsHolder? = null
  private var currentNdsMap: NdsMap? = null
  private var currentNdsRef: NdsRef? = null
  private var overlay = RenderOverlay.None
  private var editMode = EditMode.TILE
  private var dirty = false
  private var elevationBrush = 0
  private var collisionBrush = 1
  private var restoringSelection = false
  private var activeBrush = MetatileBrush.single(0)
  private var copiedBlocks: Array<IntArray>? = null
  private var lastPasteAnchor: Pair<Int, Int>? = null
  private val undoStack = ArrayDeque<List<TileEdit>>()
  private val redoStack = ArrayDeque<List<TileEdit>>()
  private val snapshotUndoStack = ArrayDeque<Pair<String, String>>()
  private val snapshotRedoStack = ArrayDeque<Pair<String, String>>()
  private var copiedEvent: CopiedEvent? = null
  private var copiedEvents: CopiedEvents? = null
  private val selectedEventMarkers = mutableSetOf<MapEventMarker>()

  private val collisionPaint = JCheckBox("Paint collision")
  private val elevationPaint = JCheckBox("Paint elevation")
  private val warpPaint = JCheckBox("Place warp")
  private val eventOverlayPaint = JCheckBox("Events")
  private val floodFillPaint = JCheckBox("Flood fill")
  private val selectPaint = JCheckBox("Select")
  private val playerViewCheck = JCheckBox("Player view")

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
          { marker, x, y -> moveEvent(marker, x, y) },
          { marker, x, y, px, py -> showEventContextMenu(marker, x, y, px, py) },
          { marker, mods -> selectEvent(marker, mods) },
          { x1, y1, x2, y2 -> selectRegion(x1, y1, x2, y2) },
          { dx, dy -> moveSelection(dx, dy) },
          { copySelection() },
          { pasteSelection() },
          { clearSelection() },
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

  // DS editing panels and state.
  private val mapCards = JPanel(CardLayout())
  private val headerCards = JPanel(CardLayout())
  private val eventsCards = JPanel(CardLayout())
  private val ndsViewContainer = JPanel(BorderLayout())
  private var ndsView: Nds3DView? = null
  private val ndsHeaderPanel = NdsHeaderPanel { onNdsHeaderApplied() }
  private val ndsEventsPanel = NdsEventsPanel { onNdsEventsChanged() }
  private val ndsTileCombo = JComboBox<String>()
  private val ndsLayerSpinner = JSpinner(SpinnerNumberModel(0, 0, NdsGrid.LAYERS - 1, 1))
  private val ndsHeightSpinner = JSpinner(SpinnerNumberModel(0.0, -32.0, 32.0, 0.25))
  private val ndsCollisionValueSpinner = JSpinner(SpinnerNumberModel(0, 0, 255, 1))
  private val ndsPaintMode =
      JComboBox(arrayOf("Tile", "Collision", "Permission", "Height", "Select Object / Move Prop", "Remove Scenery Object"))
  private val ndsGridCheck = JCheckBox("Grid")
  private val ndsCollisionCheck = JCheckBox("Collisions")
  private val ndsClearCollisionWithTerrain = JCheckBox("Clear collision with object", true)
  private val ndsCollisionEditView = JCheckBox("Transparent collision view")
  private val ndsRestoreTerrainButton = JButton("Restore last object")
  private var selectedNdsPropId: String? = null
  private var selectedNdsTerrainGroup: String? = null
  private var ndsPropDragOffsetX = 0f
  private var ndsPropDragOffsetZ = 0f
  private var ndsTerrainDragStartX: Float? = null
  private var ndsTerrainDragStartZ: Float? = null
  private var ndsTerrainDragInitialOffsetX = 0f
  private var ndsTerrainDragInitialOffsetZ = 0f
  private val ndsPropsPanel = NdsPropsPanel(
      onImport = { importNdsPropModel() },
      onPlace = { beginNdsPropPlacement(it) },
      onPreview = { key ->
        currentNdsHolder?.project?.propModelPreview(key, currentNdsMap)
            ?: NdsProject.PropModelPreview(emptyList(), emptyMap(), emptyMap())
      },
      onSelect = { selectNdsProp(it) },
      onRemove = { removeSelectedNdsProp() },
      onDuplicate = { duplicateSelectedNdsProp() },
      onChanged = { onNdsPropTransformChanged() },
  )

  private val groupsSearch = JTextField()
  private val locationsSearch = JTextField()

  init {
    defaultCloseOperation = DO_NOTHING_ON_CLOSE
    rootPane.registerKeyboardAction(
        { if (currentNdsMap != null) removeSelectedNdsObject() },
        KeyStroke.getKeyStroke("DELETE"),
        javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW,
    )
    size = Dimension(1400, 860)
    setLocationRelativeTo(null)

    // Restore window geometry.
    val config = loadConfig()
    config?.arr("windowSize")?.let { arr ->
      if (arr.items.size == 2) {
        val w = arr.items[0].asInt() ?: 1400
        val h = arr.items[1].asInt() ?: 860
        size = Dimension(w, h)
      }
    }
    config?.arr("windowPos")?.let { arr ->
      if (arr.items.size == 2) {
        val x = arr.items[0].asInt()
        val y = arr.items[1].asInt()
        if (x != null && y != null) setLocation(x, y)
      }
    }

    jMenuBar = buildMenuBar()
    contentPane.add(buildToolBar(), BorderLayout.NORTH)
    canvas.onZoomChanged = { updateZoomLabel() }

    addWindowListener(
        object : WindowAdapter() {
          override fun windowClosing(e: WindowEvent) {
            if (confirmMapChange()) {
              persistWindowGeometry()
              dispose()
            }
          }
        })

    // Build map navigation.
    val leftTabs = JTabbedPane()
    leftTabs.addTab("Groups", buildGroupsPanel())
    leftTabs.addTab("Locations", buildLocationsPanel())
    leftTabs.preferredSize = Dimension(280, 0)

    // Build editing tabs.
    val mainTabs = JTabbedPane()
    mapCards.add(buildMapTab(), "gba")
    mapCards.add(buildNdsMapPanel(), "nds")
    headerCards.add(headerContainer, "gba")
    headerCards.add(ndsHeaderPanel, "nds")
    eventsCards.add(eventsPanel, "gba")
    eventsCards.add(ndsEventsPanel, "nds")
    mainTabs.addTab("Map", mapCards)
    mainTabs.addTab("Header", headerCards)
    mainTabs.addTab("Events", eventsCards)

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
      when (userObject) {
        is MapRef -> openMap(userObject)
        is NdsRef -> openNdsMap(userObject)
        else -> {}
      }
    }
    tree.addMouseListener(
        object : MouseAdapter() {
          override fun mouseReleased(e: MouseEvent) {
            if (!SwingUtilities.isRightMouseButton(e)) return
            val path = tree.getPathForLocation(e.x, e.y) ?: return
            val ref = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject
            if (ref !is MapRef) return
            val menu = JPopupMenu()
            menu.add(
                JMenuItem("Duplicate as Runtime Override…").apply {
                  addActionListener { duplicateAsOverride(ref) }
                })
            menu.show(tree, e.x, e.y)
          }
        })
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
        eventOverlayPaint.isSelected = false
        floodFillPaint.isSelected = false
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
        eventOverlayPaint.isSelected = false
        floodFillPaint.isSelected = false
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
        floodFillPaint.isSelected = false
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
      if (eventOverlayPaint.isSelected) {
        collisionPaint.isSelected = false
        elevationPaint.isSelected = false
        warpPaint.isSelected = false
        floodFillPaint.isSelected = false
        editMode = EditMode.EVENTS
        if (overlay != RenderOverlay.None) {
          overlay = RenderOverlay.None
          refreshMapImage()
        }
      } else {
        editMode = EditMode.TILE
      }
      updateEventOverlayVisibility()
    }
    floodFillPaint.addActionListener {
      if (floodFillPaint.isSelected) {
        collisionPaint.isSelected = false
        elevationPaint.isSelected = false
        warpPaint.isSelected = false
        eventOverlayPaint.isSelected = false
        editMode = EditMode.FILL
        if (overlay != RenderOverlay.None) {
          overlay = RenderOverlay.None
          refreshMapImage()
        }
      } else {
        editMode = EditMode.TILE
      }
      updateEventOverlayVisibility()
    }
    selectPaint.addActionListener {
      if (selectPaint.isSelected) {
        collisionPaint.isSelected = false
        elevationPaint.isSelected = false
        warpPaint.isSelected = false
        eventOverlayPaint.isSelected = false
        floodFillPaint.isSelected = false
        editMode = EditMode.SELECT
        if (overlay != RenderOverlay.None) {
          overlay = RenderOverlay.None
          refreshMapImage()
        }
      } else {
        editMode = EditMode.TILE
        canvas.selection = null
      }
      updateEventOverlayVisibility()
      canvas.selectionMode = selectPaint.isSelected
    }
    grid.addActionListener {
      canvas.showGrid = grid.isSelected
      canvas.repaint()
    }
    playerViewCheck.addActionListener {
      canvas.showPlayerView = playerViewCheck.isSelected
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
    mapToolbar.add(floodFillPaint)
    mapToolbar.add(selectPaint)
    mapToolbar.add(grid)
    mapToolbar.add(playerViewCheck)
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

  private fun buildNdsMapPanel(): JPanel {
    val panel = JPanel(BorderLayout())

    ndsTileCombo.model =
        javax.swing.DefaultComboBoxModel(
            NdsTileset.tiles.map { it.name }.toTypedArray())
    ndsTileCombo.selectedIndex = 0
    ndsTileCombo.addActionListener {
      view()?.activeTile = ndsTileCombo.selectedIndex.coerceAtLeast(0)
    }
    ndsLayerSpinner.preferredSize = Dimension(60, ndsLayerSpinner.preferredSize.height)
    ndsLayerSpinner.addChangeListener {
      view()?.activeLayer = (ndsLayerSpinner.value as Number).toInt()
    }
    ndsHeightSpinner.editor = JSpinner.NumberEditor(ndsHeightSpinner, "0.####")
    ndsHeightSpinner.preferredSize = Dimension(72, ndsHeightSpinner.preferredSize.height)
    ndsHeightSpinner.toolTipText =
        "Tile height in map-tile units; arrows change it by 0.25 and decimals may be typed"
    ndsHeightSpinner.addChangeListener {
      view()?.activeHeight = (ndsHeightSpinner.value as Number).toDouble()
    }
    ndsCollisionValueSpinner.preferredSize = Dimension(60, ndsCollisionValueSpinner.preferredSize.height)
    ndsCollisionValueSpinner.addChangeListener {
      view()?.brushCollision = (ndsCollisionValueSpinner.value as Number).toInt()
    }
    ndsPaintMode.addActionListener {
      view()?.setPaintMode(ndsPaintMode.selectedIndex.coerceAtLeast(0))
    }
    ndsGridCheck.isSelected = true
    ndsGridCheck.addActionListener { view()?.showGrid = ndsGridCheck.isSelected }
    ndsCollisionCheck.addActionListener { view()?.showCollision = ndsCollisionCheck.isSelected }
    ndsCollisionEditView.addActionListener {
      val enabled = ndsCollisionEditView.isSelected
      if (enabled) {
        ndsCollisionCheck.isSelected = true
        ndsPaintMode.selectedIndex = 1
      }
      view()?.let {
        it.modelOpacity = if (enabled) 0.12f else 1f
        if (enabled) it.showCollision = true
        it.asComponent().repaint()
      }
      status.text = if (enabled) {
        "Transparent collision view — choose Collision mode and paint the grid directly"
      } else {
        "Normal map view"
      }
    }
    ndsRestoreTerrainButton.addActionListener { restoreLastNdsTerrainObject() }

    val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2))
    toolbar.add(JLabel("Tile:"))
    toolbar.add(ndsTileCombo)
    toolbar.add(JLabel("Layer:"))
    toolbar.add(ndsLayerSpinner)
    toolbar.add(JLabel("Height:"))
    toolbar.add(ndsHeightSpinner)
    toolbar.add(JLabel("Mode:"))
    toolbar.add(ndsPaintMode)
    toolbar.add(JLabel("Value:"))
    toolbar.add(ndsCollisionValueSpinner)
    toolbar.add(ndsGridCheck)
    toolbar.add(ndsCollisionCheck)
    val terrainToolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2))
    terrainToolbar.add(ndsCollisionEditView)
    terrainToolbar.add(ndsClearCollisionWithTerrain)
    terrainToolbar.add(ndsRestoreTerrainButton)
    terrainToolbar.add(JLabel("  Middle drag rotates · Left click edits · Right drag pans · Wheel zooms"))
    panel.add(JPanel(GridLayout(2, 1)).also {
      it.add(toolbar)
      it.add(terrainToolbar)
    }, BorderLayout.NORTH)
    panel.add(ndsViewContainer, BorderLayout.CENTER)
    panel.add(ndsPropsPanel, BorderLayout.EAST)
    return panel
  }

  /** Returns the active DS 3D view, creating it (OpenGL first, software fallback) on demand. */
  private fun view(): Nds3DView? {
    val existing = ndsView
    if (existing != null) return existing
    val software =
        NdsSoftwareMapView(
            { x, z -> paintNdsCell(x, z) },
            { x, z, value -> paintNdsCollision(x, z, value) },
            { hit, dragging -> handleNdsCellInteraction(hit, dragging) },
        )
    val created =
        try {
          NdsGlMapView(
              { x, z -> paintNdsCell(x, z) },
              { x, z, value -> paintNdsCollision(x, z, value) },
              { hit, dragging -> handleNdsCellInteraction(hit, dragging) },
          )
        } catch (t: Throwable) {
          System.out.println("[Nds] OpenGL view unavailable (${t.message}); using software renderer")
          software
        }
    ndsView = created
    ndsViewContainer.removeAll()
    ndsViewContainer.add(created.asComponent(), BorderLayout.CENTER)
    ndsViewContainer.revalidate()
    ndsViewContainer.repaint()
    created.setPaintMode(ndsPaintMode.selectedIndex.coerceAtLeast(0))
    created.showGrid = ndsGridCheck.isSelected
    created.showCollision = ndsCollisionCheck.isSelected
    created.brushCollision = (ndsCollisionValueSpinner.value as Number).toInt()
    created.modelOpacity = if (ndsCollisionEditView.isSelected) 0.12f else 1f
    return created
  }

  private fun buildMenuBar(): JMenuBar {
    val bar = JMenuBar()

    val config = loadConfig()
    config?.arr("recentProjects")?.items?.mapNotNull { it.asStr() }?.let { recentList = it.toMutableList() }

    val file = JMenu("File")
    file.add(JMenuItem("Open Decomp…").apply { addActionListener { chooseProject() } })
    recentMenu = JMenu("Open Recent")
    fun rebuildRecent() {
      recentMenu.removeAll()
      for (path in recentList.take(10)) {
        recentMenu.add(JMenuItem(path).apply {
          addActionListener {
            val dir = File(path)
            if (dir.isDirectory) {
              if (addProject(dir)) selectFirstMap()
            } else {
              JOptionPane.showMessageDialog(
                  this@EditorFrame, "Decomp not found at $path", "Open Recent", JOptionPane.WARNING_MESSAGE)
            }
          }
        })
      }
      recentMenu.isEnabled = recentList.isNotEmpty()
    }
    rebuildRecent()
    file.add(recentMenu)
    file.add(JMenuItem("New Map…").apply { addActionListener { newMap() } })
    file.add(JMenuItem("Import DS Map Model…").apply { addActionListener { importNdsModel() } })
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
    file.add(
        JMenuItem("Export DS Map…").apply { addActionListener { exportNdsCurrent() } })
    file.add(
        JMenuItem("Export All DS Maps…").apply { addActionListener { exportNdsAll() } })
    file.addSeparator()
    file.add(JMenuItem("Export Map Image…").apply { addActionListener { exportImage() } })
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
      if (addProject(chooser.selectedFile)) selectFirstMap()
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

  private fun addProject(dir: File): Boolean {
    val root = dir.canonicalFile
    if (isGen4Decomp(root)) return addNdsProject(root)
    try {
      holders[root.path]?.let { return true }
      val source = DecompBase(root)
      val project = DecompProject(root, source)
      val holder = ProjectHolder(project)
      holders[root.path] = holder
      rebuildAllProjects()
      rememberRecentProject(root.path)
      status.text = "Opened $root"
      return true
    } catch (t: Throwable) {
      JOptionPane.showMessageDialog(
          this, t.message ?: t.toString(), "Open failed", JOptionPane.ERROR_MESSAGE)
      return false
    }
  }

  private fun addNdsProject(root: File): Boolean {
    try {
      ndsHolders[root.path]?.let { return true }
      val project = NdsProject(root)
      val holder = NdsHolder(project)
      ndsHolders[root.path] = holder
      rebuildAllProjects()
      rememberRecentProject(root.path)
      status.text = "Opened DS decomp $root (${project.family.displayName})"
      return true
    } catch (t: Throwable) {
      JOptionPane.showMessageDialog(
          this, t.message ?: t.toString(), "Open failed", JOptionPane.ERROR_MESSAGE)
      return false
    }
  }

  private fun isGen4Decomp(root: File): Boolean {
    val hg = File(root, "src/data/map_headers.h").isFile &&
        File(root, "include/constants/maps.h").isFile
    val pt = File(root, "include/data/map_headers.h").isFile
    return hg || pt
  }

  private fun newMap() {
    val targets = holders.values.map { NewMapTarget(gba = it) } +
        ndsHolders.values.map { NewMapTarget(nds = it) }
    if (targets.isEmpty()) {
      JOptionPane.showMessageDialog(
          this, "Open a decomp first, then create a map.", "New Map", JOptionPane.WARNING_MESSAGE)
      return
    }
    val targetCombo = JComboBox(targets.toTypedArray())
    targets.indexOfFirst {
      it.gba === currentHolder || it.nds === currentNdsHolder
    }.takeIf { it >= 0 }?.let { targetCombo.selectedIndex = it }
    val targetPanel = JPanel(BorderLayout(8, 8)).also {
      it.add(JLabel("Region / game"), BorderLayout.NORTH)
      it.add(targetCombo, BorderLayout.CENTER)
    }
    if (JOptionPane.showConfirmDialog(
            this, targetPanel, "New Map", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) !=
        JOptionPane.OK_OPTION) return
    val target = targetCombo.selectedItem as? NewMapTarget ?: return
    target.nds?.let {
      createNdsMap(it)
      return
    }
    val holder = target.gba ?: return
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

  private fun createNdsMap(holder: NdsHolder) {
    val dialog = NdsNewMapDialog(holder.project)
    dialog.isVisible = true
    val p = dialog.params ?: return
    try {
      val map = holder.project.createMap(
          name = p.name,
          displayName = p.displayName,
          mapId = p.mapId,
          cellsWide = p.cellsWide,
          cellsHigh = p.cellsHigh,
          matrixX = p.matrixX,
          matrixY = p.matrixY,
          templateName = p.templateName,
          modelFile = p.modelFile,
          textureFile = p.textureFile,
      )
      groupsSearch.text = ""
      locationsSearch.text = ""
      rebuildAllProjects()
      selectNdsMap(holder, map.name)
      status.text = "Created DS map ${map.displayName} in ${holder.project.family.regionName}"
    } catch (t: Throwable) {
      JOptionPane.showMessageDialog(
          this, t.message ?: t.toString(), "New DS Map failed", JOptionPane.ERROR_MESSAGE)
    }
  }

  private fun importNdsModel() {
    val holder = currentNdsHolder
    val map = currentNdsMap
    if (holder == null || map == null) {
      JOptionPane.showMessageDialog(
          this, "Open a Nintendo DS map first.", "Import DS Model", JOptionPane.WARNING_MESSAGE)
      return
    }
    if (holder.project.hasImportedModel(map)) {
      val replace = JOptionPane.showConfirmDialog(
          this,
          "This map already has an imported model. Replace it?",
          "Import DS Model",
          JOptionPane.YES_NO_OPTION,
          JOptionPane.WARNING_MESSAGE,
      )
      if (replace != JOptionPane.YES_OPTION) return
    }
    val modelChooser = JFileChooser().also {
      it.dialogTitle = "Choose an NSBMD map model"
      it.fileFilter = FileNameExtensionFilter("Nintendo DS model (*.nsbmd)", "nsbmd")
    }
    if (modelChooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
    val textureChoice = JOptionPane.showConfirmDialog(
        this,
        "Does this model use a separate NSBTX texture pack?\nChoose No if its textures are embedded.",
        "Import DS Model",
        JOptionPane.YES_NO_CANCEL_OPTION,
        JOptionPane.QUESTION_MESSAGE,
    )
    if (textureChoice == JOptionPane.CANCEL_OPTION || textureChoice == JOptionPane.CLOSED_OPTION) return
    var textures: File? = null
    if (textureChoice == JOptionPane.YES_OPTION) {
      val textureChooser = JFileChooser().also {
        it.dialogTitle = "Choose an NSBTX texture pack"
        it.fileFilter = FileNameExtensionFilter("Nintendo DS textures (*.nsbtx)", "nsbtx")
      }
      if (textureChooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
      textures = textureChooser.selectedFile
    }
    try {
      val result = holder.project.importModel(map, modelChooser.selectedFile, textures)
      val v = view()
      if (v != null) {
        v.modelTriangles = holder.project.trianglesFor(map)
        v.modelTextures = holder.project.texturesFor(map)
        v.modelPalettes = holder.project.palettesFor(map)
      }
      status.text =
          "Imported ${modelChooser.selectedFile.name}: ${result.triangles} triangles, ${result.textures} textures"
    } catch (t: Throwable) {
      JOptionPane.showMessageDialog(
          this, t.message ?: t.toString(), "Model import failed", JOptionPane.ERROR_MESSAGE)
    }
  }

  private fun importNdsPropModel() {
    val holder = currentNdsHolder
    if (holder == null) {
      JOptionPane.showMessageDialog(
          this, "Open a Nintendo DS map first.", "Import Prop Model", JOptionPane.WARNING_MESSAGE)
      return
    }
    val modelChooser = JFileChooser().also {
      it.dialogTitle = "Choose an NSBMD prop model"
      it.fileFilter = FileNameExtensionFilter("Nintendo DS model (*.nsbmd)", "nsbmd")
    }
    if (modelChooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
    val textureChoice = JOptionPane.showConfirmDialog(
        this,
        "Does this prop use a separate NSBTX texture pack?\nChoose No if textures are embedded.",
        "Import Prop Model",
        JOptionPane.YES_NO_CANCEL_OPTION,
        JOptionPane.QUESTION_MESSAGE,
    )
    if (textureChoice == JOptionPane.CANCEL_OPTION || textureChoice == JOptionPane.CLOSED_OPTION) return
    var textureFile: File? = null
    if (textureChoice == JOptionPane.YES_OPTION) {
      val textureChooser = JFileChooser().also {
        it.dialogTitle = "Choose the prop's NSBTX texture pack"
        it.fileFilter = FileNameExtensionFilter("Nintendo DS textures (*.nsbtx)", "nsbtx")
      }
      if (textureChooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
      textureFile = textureChooser.selectedFile
    }
    val label = JOptionPane.showInputDialog(
        this,
        "Catalog name",
        modelChooser.selectedFile.nameWithoutExtension,
    )?.trim() ?: return
    try {
      val imported = holder.project.importPropModel(label, modelChooser.selectedFile, textureFile)
      ndsPropsPanel.setModels(holder.project.propModels())
      ndsPropsPanel.selectModel(imported.key)
      status.text = "Imported prop model ${imported.label}; click Place, then click the map"
    } catch (t: Throwable) {
      JOptionPane.showMessageDialog(
          this, t.message ?: t.toString(), "Prop import failed", JOptionPane.ERROR_MESSAGE)
    }
  }

  private fun duplicateAsOverride(ref: MapRef? = null) {
    val holder = ref?.holder ?: currentHolder ?: return
    val current = currentRef
    val usesCurrentMap = ref == null || current?.holder === holder && current.dirName == ref.dirName
    if (!usesCurrentMap && dirty && !confirmMapChange()) return
    val map = if (usesCurrentMap) currentMap else ref?.let { holder.project.loadMap(it.dirName) }
    if (map == null) {
      JOptionPane.showMessageDialog(
          this,
          "Cannot load map ${ref?.dirName.orEmpty()}.",
          "Duplicate failed",
          JOptionPane.WARNING_MESSAGE,
      )
      return
    }
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
    allNdsMaps.clear()
    for (h in holders.values) {
      for (group in h.project.groupOrder) {
        for (mapDir in h.project.groupMaps[group].orEmpty()) {
          allMaps += MapRef(h, mapDir)
        }
      }
    }
    for (h in ndsHolders.values) {
      for (name in h.project.mapNames) {
        allNdsMaps += NdsRef(h, name)
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

  private fun selectNdsMap(holder: NdsHolder, name: String) {
    val model = tree.model as DefaultTreeModel
    var path: TreePath? = null
    fun search(node: DefaultMutableTreeNode) {
      for (i in 0 until node.childCount) {
        val child = node.getChildAt(i) as DefaultMutableTreeNode
        val ref = child.userObject as? NdsRef
        if (child.isLeaf && ref?.holder === holder && ref.name == name) {
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
    for (holder in ndsHolders.values) {
      val projectNode =
          DefaultMutableTreeNode(
              "DS ${holder.project.family.displayName} — ${holder.project.rootDir.name}")
      val groupNode = DefaultMutableTreeNode("Maps")
      for (name in holder.project.mapNames) {
        if (filter.isEmpty() || name.contains(filter, ignoreCase = true)) {
          groupNode.add(DefaultMutableTreeNode(NdsRef(holder, name)))
        }
      }
      if (groupNode.childCount > 0) projectNode.add(groupNode)
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
    val changedMap =
        (currentRef?.let { it.holder !== ref.holder || it.dirName != ref.dirName } ?: false) ||
            currentNdsRef != null
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
    currentNdsHolder = null
    currentNdsMap = null
    currentNdsRef = null
    undoStack.clear()
    redoStack.clear()
    snapshotUndoStack.clear()
    snapshotRedoStack.clear()
    selectedEventMarkers.clear()
    syncSelectedEvents()
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

    showMapCards(true)
    dirty = false
    updateTitle()
    refreshMapImage()
  }

  private fun openNdsMap(ref: NdsRef) {
    val switching = currentRef != null ||
        currentNdsRef?.let { it.holder !== ref.holder || it.name != ref.name } == true
    if (switching && !confirmMapChange()) {
      restoreCurrentSelection()
      return
    }
    val map = ref.holder.project.loadMap(ref.name) ?: run {
      JOptionPane.showMessageDialog(
          this, "Cannot load DS map ${ref.name}", "Load failed", JOptionPane.WARNING_MESSAGE)
      return
    }
    currentNdsHolder = ref.holder
    currentNdsMap = map
    currentNdsRef = ref
    currentHolder = null
    currentMap = null
    currentRef = null
    showMapCards(false)
    val v = view() ?: return
    v.grid = map.grid
    val tris = ref.holder.project.trianglesFor(map)
    val bld = ref.holder.project.buildingTrianglesFor(map)
    v.modelTriangles = if (bld.isEmpty()) tris else tris + bld
    v.modelTextures = ref.holder.project.texturesFor(map)
    v.modelPalettes = ref.holder.project.palettesFor(map)
    ndsHeaderPanel.setMap(map)
    ndsEventsPanel.setMap(map)
    selectedNdsPropId = null
    selectedNdsTerrainGroup = null
    ndsPropsPanel.setModels(ref.holder.project.propModels())
    ndsPropsPanel.setMap(map)
    refreshNdsMarkers()
    dirty = false
    updateTitle()
    var dataCells = 0
    for (y in 0 until map.grid.rows) for (x in 0 until map.grid.cols) {
      if (map.grid.permissionAt(x, y) != 0 || map.grid.collisionAt(x, y) != 0) dataCells++
    }
    val romInfo =
        if (ref.holder.project.hasRom) "ROM=${ref.holder.project.rom?.gameCode}"
        else "no ROM found"
    status.text =
        "Opened DS map ${map.name} ($romInfo, grid cells=$dataCells, terrain=${tris.size} tris, props=${map.props.size})"
  }

  private fun showMapCards(gba: Boolean) {
    (mapCards.layout as CardLayout).show(mapCards, if (gba) "gba" else "nds")
    (headerCards.layout as CardLayout).show(headerCards, if (gba) "gba" else "nds")
    (eventsCards.layout as CardLayout).show(eventsCards, if (gba) "gba" else "nds")
  }

  private fun buildNdsMarkers(map: NdsMap): List<NdsEventMarker> {
    val markers = mutableListOf<NdsEventMarker>()
    for (w in map.events.warps) {
      markers += NdsEventMarker(w.x, w.z, "W", java.awt.Color(128, 45, 170, 200))
    }
    for (o in map.events.objects) {
      markers += NdsEventMarker(o.x, o.z, "P", java.awt.Color(55, 175, 80, 200))
    }
    for (t in map.events.triggers) {
      markers += NdsEventMarker(t.x, t.z, "T", java.awt.Color(30, 155, 190, 200))
    }
    for (b in map.events.bgEvents) {
      markers += NdsEventMarker(b.x, b.z, "S", java.awt.Color(220, 165, 30, 200))
    }
    map.props.firstOrNull { it.id == selectedNdsPropId }?.let { prop ->
      markers += NdsEventMarker(
          kotlin.math.floor(prop.x).toInt(),
          kotlin.math.floor(prop.z).toInt(),
          "PROP",
          java.awt.Color(255, 210, 40, 230),
      )
    }
    selectedNdsTerrainGroup?.let { groupId ->
      currentNdsHolder?.project?.terrainObject(map, groupId)?.let { selection ->
        markers += NdsEventMarker(
            kotlin.math.floor((selection.minX + selection.maxX) / 2f).toInt(),
            kotlin.math.floor((selection.minZ + selection.maxZ) / 2f).toInt(),
            "SCENERY",
            java.awt.Color(255, 210, 40, 230),
        )
      }
    }
    return markers
  }

  private fun refreshNdsMarkers() {
    val map = currentNdsMap ?: return
    val v = view() ?: return
    v.markers = buildNdsMarkers(map)
    v.asComponent().repaint()
  }

  private fun restoreCurrentSelection() {
    restoringSelection = true
    try {
      val gba = currentRef
      if (gba != null) {
        selectMap(gba.holder, gba.dirName)
        return
      }
      val nds = currentNdsRef ?: return
      selectNdsMap(nds.holder, nds.name)
    } finally {
      restoringSelection = false
    }
  }

  private fun confirmMapChange(): Boolean {
    if (!dirty) return true
    val name = currentMap?.dirName ?: currentNdsMap?.name ?: "current map"
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
    eventOverlayPaint.isSelected = mode == EditMode.EVENTS
    floodFillPaint.isSelected = mode == EditMode.FILL
    selectPaint.isSelected = mode == EditMode.SELECT
    canvas.selectionMode = mode == EditMode.SELECT
    if (mode != EditMode.SELECT) canvas.selection = null
    updateEventOverlayVisibility()
  }

  private fun updateEventOverlayVisibility() {
    canvas.eventEditingEnabled = editMode == EditMode.EVENTS
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
    snapshotForUndo()
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
    if (editMode == EditMode.EVENTS || editMode == EditMode.SELECT) return
    if (editMode == EditMode.WARP) {
      addWarp(x, y, elevationBrush)
      selectEditMode(EditMode.TILE)
      return
    }
    val map = currentMap ?: return
    val holder = currentHolder ?: return
    if (editMode == EditMode.FILL) {
      floodFill(x, y, brushValue)
      return
    }
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

  private fun floodFill(startX: Int, startY: Int, brushValue: Int) {
    val map = currentMap ?: return
    val holder = currentHolder ?: return
    val w = map.layout.width
    val h = map.layout.height
    val startIndex = startY * w + startX
    if (startIndex !in map.layout.blocks.indices) return
    val target = map.layout.blocks[startIndex]
    val replacement = (target and 0xFC00) or (brushValue and 0x3FF)
    if (replacement == target) return
    val edits = mutableListOf<TileEdit>()
    val updates = mutableListOf<Triple<Int, Int, BufferedImage>>()
    val visited = HashSet<Int>()
    val queue = ArrayDeque<Int>()
    queue.addLast(startIndex)
    visited.add(startIndex)
    while (queue.isNotEmpty()) {
      val idx = queue.removeFirst()
      val cx = idx % w
      val cy = idx / w
      val cur = map.layout.blocks[idx]
      if ((cur and 0x3FF) != (target and 0x3FF)) continue
      map.layout.blocks[idx] = replacement
      edits += TileEdit(cx, cy, cur, replacement)
      updates += Triple(cx, cy,
          holder.renderer.blockImage(map.layout.primaryTileset, map.layout.secondaryTileset, replacement, overlay))
      for ((dx, dy) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
        val nx = cx + dx
        val ny = cy + dy
        if (nx in 0 until w && ny in 0 until h) {
          val ni = ny * w + nx
          if (ni !in visited && ni in map.layout.blocks.indices) {
            visited.add(ni)
            queue.addLast(ni)
          }
        }
      }
    }
    if (edits.isEmpty()) return
    canvas.updateBlocks(updates)
    undoStack.addLast(edits)
    redoStack.clear()
    markDirty()
    canvas.brush = brushValue
    status.text = "Flood filled ${edits.size} blocks"
  }

  private fun paintBrush(anchorX: Int, anchorY: Int): Boolean {
    val map = currentMap ?: return false
    val holder = currentHolder ?: return false
    if (activeBrush.width == 1 && activeBrush.height == 1 && !activeBrush.includesAttributes) {
      return false
    }
    val edits = mutableListOf<TileEdit>()
    val updates = mutableListOf<Triple<Int, Int, BufferedImage>>()
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
        updates += Triple(
            x, y,
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
    canvas.updateBlocks(updates)
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
    if (editMode == EditMode.FILL) return
    if (editMode != EditMode.SELECT) selectEditMode(EditMode.TILE)
    status.text = "Picked 0x%03X at (%d, %d)".format(metatile, x, y)
  }

  // ---- Selection / copy / paste / move -------------------------------------

  private fun selectRegion(x1: Int, y1: Int, x2: Int, y2: Int) {
    status.text =
        "Selected ${x2 - x1 + 1}×${y2 - y1 + 1} at (${x1}, ${y1}) — Ctrl+C copy, Ctrl+V paste, drag to move, Delete clears"
  }

  private fun copySelection() {
    val map = currentMap ?: return
    val sel = canvas.selection ?: return
    val w = sel[2] - sel[0] + 1
    val h = sel[3] - sel[1] + 1
    if (w <= 0 || h <= 0) return
    val blocks = Array(h) { y -> IntArray(w) { x -> map.layout.tileAt(sel[0] + x, sel[1] + y) ?: 0 } }
    copiedBlocks = blocks
    status.text = "Copied ${w}×${h}"
  }

  private fun pasteSelection() {
    val map = currentMap ?: return
    val holder = currentHolder ?: return
    val blocks = copiedBlocks ?: return
    val h = blocks.size
    val w = blocks[0].size
    val anchor = canvas.hoveredBlock ?: (canvas.selection?.let { it[0] to it[1] }) ?: return
    val edits = mutableListOf<TileEdit>()
    val updates = mutableListOf<Triple<Int, Int, BufferedImage>>()
    for (y in 0 until h) {
      for (x in 0 until w) {
        val bx = anchor.first + x
        val by = anchor.second + y
        if (bx !in 0 until map.layout.width || by !in 0 until map.layout.height) continue
        val idx = by * map.layout.width + bx
        val current = map.layout.blocks[idx]
        val value = blocks[y][x]
        if (value == current) continue
        map.layout.blocks[idx] = value
        edits += TileEdit(bx, by, current, value)
        updates +=
            Triple(
                bx, by,
                holder.renderer.blockImage(
                    map.layout.primaryTileset, map.layout.secondaryTileset, value, overlay))
      }
    }
    if (edits.isNotEmpty()) {
      canvas.updateBlocks(updates)
      undoStack.addLast(edits)
      redoStack.clear()
      markDirty()
    }
    canvas.selection = intArrayOf(anchor.first, anchor.second, anchor.first + w - 1, anchor.second + h - 1)
    status.text = "Pasted ${w}×${h} at (${anchor.first}, ${anchor.second})"
  }

  private fun moveSelection(dx: Int, dy: Int) {
    val map = currentMap ?: return
    val holder = currentHolder ?: return
    val sel = canvas.selection ?: return
    val w = sel[2] - sel[0] + 1
    val h = sel[3] - sel[1] + 1
    if (dx == 0 && dy == 0) return
    val destX = (sel[0] + dx).coerceIn(0, (map.layout.width - 1).coerceAtLeast(0))
    val destY = (sel[1] + dy).coerceIn(0, (map.layout.height - 1).coerceAtLeast(0))
    val blocks = Array(h) { y -> IntArray(w) { x -> map.layout.tileAt(sel[0] + x, sel[1] + y) ?: 0 } }
    val edits = mutableListOf<TileEdit>()
    val updates = mutableListOf<Triple<Int, Int, BufferedImage>>()
    fun putBlock(bx: Int, by: Int, value: Int) {
      if (bx !in 0 until map.layout.width || by !in 0 until map.layout.height) return
      val idx = by * map.layout.width + bx
      val current = map.layout.blocks[idx]
      if (current == value) return
      map.layout.blocks[idx] = value
      edits += TileEdit(bx, by, current, value)
      updates +=
          Triple(
              bx, by,
              holder.renderer.blockImage(
                  map.layout.primaryTileset, map.layout.secondaryTileset, value, overlay))
    }
    // Erase source cells that are not also covered by the destination (keeps overlap safe).
    for (y in 0 until h) {
      for (x in 0 until w) {
        val sx = sel[0] + x
        val sy = sel[1] + y
        val inDest = sx in destX until (destX + w) && sy in destY until (destY + h)
        if (!inDest) putBlock(sx, sy, 0)
      }
    }
    for (y in 0 until h) for (x in 0 until w) putBlock(destX + x, destY + y, blocks[y][x])
    if (edits.isNotEmpty()) {
      canvas.updateBlocks(updates)
      undoStack.addLast(edits)
      redoStack.clear()
      markDirty()
    }
    canvas.selection = intArrayOf(destX, destY, destX + w - 1, destY + h - 1)
    status.text = "Moved ${w}×${h} by (${dx}, ${dy})"
  }

  private fun clearSelection() {
    val map = currentMap ?: return
    val holder = currentHolder ?: return
    val sel = canvas.selection ?: return
    val edits = mutableListOf<TileEdit>()
    val updates = mutableListOf<Triple<Int, Int, BufferedImage>>()
    for (y in sel[1]..sel[3]) {
      for (x in sel[0]..sel[2]) {
        if (x !in 0 until map.layout.width || y !in 0 until map.layout.height) continue
        val idx = y * map.layout.width + x
        val current = map.layout.blocks[idx]
        if (current == 0) continue
        map.layout.blocks[idx] = 0
        edits += TileEdit(x, y, current, 0)
        updates +=
            Triple(
                x, y,
                holder.renderer.blockImage(
                    map.layout.primaryTileset, map.layout.secondaryTileset, 0, overlay))
      }
    }
    if (edits.isNotEmpty()) {
      canvas.updateBlocks(updates)
      undoStack.addLast(edits)
      redoStack.clear()
      markDirty()
    }
    canvas.selection = null
    status.text = "Cleared selection"
  }

  private fun undo() {
    val map = currentMap ?: return
    if (undoStack.isNotEmpty()) {
      val edits = undoStack.removeLast()
      for (edit in edits.asReversed()) applyTileEdit(edit.x, edit.y, edit.before)
      redoStack.addLast(edits)
      status.text = "Undid ${edits.size} tile edit(s)"
      return
    }
    if (snapshotUndoStack.isNotEmpty()) {
      val snapshot = snapshotUndoStack.removeLast()
      snapshotRedoStack.addLast(map.dirName to JsonWriter.write(map.mapJson))
      restoreMapSnapshot(map, snapshot.second)
      status.text = "Undid event/header edit"
      return
    }
  }

  private fun redo() {
    val map = currentMap ?: return
    if (redoStack.isNotEmpty()) {
      val edits = redoStack.removeLast()
      for (edit in edits) applyTileEdit(edit.x, edit.y, edit.after)
      undoStack.addLast(edits)
      status.text = "Redid ${edits.size} tile edit(s)"
      return
    }
    if (snapshotRedoStack.isNotEmpty()) {
      val snapshot = snapshotRedoStack.removeLast()
      snapshotUndoStack.addLast(map.dirName to JsonWriter.write(map.mapJson))
      restoreMapSnapshot(map, snapshot.second)
      status.text = "Redid event/header edit"
      return
    }
  }

  private fun snapshotForUndo() {
    val map = currentMap ?: return
    snapshotUndoStack.addLast(map.dirName to JsonWriter.write(map.mapJson))
    snapshotRedoStack.clear()
  }

  private fun restoreMapSnapshot(map: EditorMap, jsonText: String) {
    val restored = JsonParser.parse(jsonText).asObj() ?: return
    map.mapJson.entries.clear()
    map.mapJson.entries.putAll(restored.entries)
    markDirty()
    eventsPanel.setMap(map)
    headerPanel?.setMap(map)
    refreshEventOverlay()
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
    snapshotForUndo()
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
    snapshotForUndo()
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
    snapshotForUndo()
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
      for ((index, event) in events.withIndex()) {
        val x = event.int("x") ?: continue
        val y = event.int("y") ?: continue
        markers += MapEventMarker(x, y, type, index)
      }
    }
    add(map.objects, MapEventType.PERSON)
    add(map.bgEvents, MapEventType.SCRIPT)
    add(map.coordEvents, MapEventType.TRIGGER)
    add(map.warps, MapEventType.WARP)
    canvas.eventMarkers = markers
  }

  private fun moveEvent(marker: MapEventMarker, x: Int, y: Int) {
    val map = currentMap ?: return
    val event = eventFor(map, marker) ?: return
    if (event.int("x") == x && event.int("y") == y) return
    snapshotForUndo()
    event.entries["x"] = Json.JNum(x.toDouble())
    event.entries["y"] = Json.JNum(y.toDouble())
    markDirty()
    eventsPanel.setMap(map)
    refreshEventOverlay()
    status.text = "Moved ${marker.type.name.lowercase()} event ${marker.index} to ($x, $y)"
  }

  private fun showEventContextMenu(
      marker: MapEventMarker?,
      x: Int,
      y: Int,
      px: Int,
      py: Int,
  ) {
    if (marker != null && marker !in selectedEventMarkers) {
      selectedEventMarkers.clear()
      selectedEventMarkers.add(marker)
      syncSelectedEvents()
    }
    val menu = JPopupMenu()
    if (marker == null) {
      if (selectedEventMarkers.isEmpty()) {
        val item = JMenuItem("Paste Event")
        item.isEnabled = copiedEvent != null || copiedEvents != null
        item.addActionListener { pasteEvent(x, y) }
        menu.add(item)
      } else {
        menu.add(JMenuItem("Delete Selected").apply {
          addActionListener { deleteSelectedEvents() }
        })
        menu.add(JMenuItem("Clear Selection").apply {
          addActionListener { clearEventSelection() }
        })
      }
    } else {
      menu.add(JMenuItem("Copy Event").apply { addActionListener { copySelectedEvents(marker) } })
      menu.add(JMenuItem("Copy Selected").apply {
        addActionListener { copySelectedEvents(marker) }
      })
      menu.add(JMenuItem("Delete Selected").apply { addActionListener { deleteSelectedEvents() } })
      if (marker.type == MapEventType.WARP) {
        menu.add(
            JMenuItem("Go to Connected Map").apply {
              addActionListener { goToConnectedMap(marker) }
            })
      }
    }
    menu.show(canvas, px, py)
  }

  private fun selectEvent(marker: MapEventMarker?, modifiers: Int) {
    val ctrl = modifiers and (InputEvent.CTRL_DOWN_MASK or InputEvent.META_DOWN_MASK) != 0
    when {
      marker == null -> {
        if (!ctrl) selectedEventMarkers.clear()
        syncSelectedEvents()
      }
      ctrl -> {
        if (!selectedEventMarkers.remove(marker)) selectedEventMarkers.add(marker)
        syncSelectedEvents()
      }
      else -> {
        if (selectedEventMarkers.size <= 1 && selectedEventMarkers.contains(marker)) return
        selectedEventMarkers.clear()
        selectedEventMarkers.add(marker)
        syncSelectedEvents()
      }
    }
  }

  private fun syncSelectedEvents() {
    canvas.selectedEventMarkers = selectedEventMarkers.toSet()
  }

  private fun copySelectedEvents(marker: MapEventMarker) {
    val map = currentMap ?: return
    val events =
        selectedEventMarkers.mapNotNull { eventFor(map, it) }
            .ifEmpty { listOfNotNull(eventFor(map, marker)) }
    if (events.isEmpty()) return
    val copies =
        events.map {
          JsonParser.parse(JsonWriter.write(it)).asObj() ?: return
        }
    copiedEvent = CopiedEvent(marker.type, copies.first())
    copiedEvents = CopiedEvents(marker.type, copies)
    status.text = "Copied ${copies.size} ${marker.type.name.lowercase()} event(s)"
  }

  private fun deleteSelectedEvents() {
    val map = currentMap ?: return
    if (selectedEventMarkers.isEmpty()) return
    snapshotForUndo()
    val byType = selectedEventMarkers.groupBy { it.type }
    for ((type, markers) in byType) {
      val key = eventArrayKey(type)
      val indices = markers.map { it.index }.toSet()
      val remaining =
          map.mapJson.arr(key)?.items.orEmpty()
              .filterIndexed { i, _ -> i !in indices }
      map.mapJson.entries[key] = Json.JArr(remaining)
    }
    selectedEventMarkers.clear()
    syncSelectedEvents()
    markDirty()
    eventsPanel.setMap(map)
    refreshEventOverlay()
    status.text = "Deleted ${byType.values.sumOf { it.size }} event(s)"
  }

  private fun clearEventSelection() {
    selectedEventMarkers.clear()
    syncSelectedEvents()
  }

  private fun pasteEvent(x: Int, y: Int) {
    val map = currentMap ?: return
    snapshotForUndo()
    val batch = copiedEvents
    if (batch != null) {
      val copies =
          batch.events.map {
            JsonParser.parse(JsonWriter.write(it)).asObj() ?: return
          }
      val key = eventArrayKey(batch.type)
      val items = map.mapJson.arr(key)?.items.orEmpty()
      val cols = ceil(sqrt(copies.size.toDouble())).toInt().coerceAtLeast(1)
      copies.forEachIndexed { i, c ->
        c.entries["x"] = Json.JNum((x + i % cols).toDouble())
        c.entries["y"] = Json.JNum((y + i / cols).toDouble())
      }
      map.mapJson.entries[key] = Json.JArr(items + copies)
      markDirty()
      eventsPanel.setMap(map)
      refreshEventOverlay()
      status.text = "Pasted ${copies.size} ${batch.type.name.lowercase()} event(s) at ($x, $y)"
      return
    }
    val copied = copiedEvent ?: return
    val event = JsonParser.parse(JsonWriter.write(copied.event)).asObj() ?: return
    event.entries["x"] = Json.JNum(x.toDouble())
    event.entries["y"] = Json.JNum(y.toDouble())
    val key = eventArrayKey(copied.type)
    val items = map.mapJson.arr(key)?.items.orEmpty() + event
    map.mapJson.entries[key] = Json.JArr(items)
    markDirty()
    eventsPanel.setMap(map)
    refreshEventOverlay()
    status.text = "Pasted ${copied.type.name.lowercase()} event at ($x, $y)"
  }

  private fun goToConnectedMap(marker: MapEventMarker) {
    val map = currentMap ?: return
    val holder = currentHolder ?: return
    val warp = eventFor(map, marker) ?: return
    val destination = warp.str("dest_map")
    val directory = destination?.let(holder.project::mapDir)
    if (directory == null) {
      JOptionPane.showMessageDialog(
          this,
          "The connected map could not be found.",
          "Warp Destination",
          JOptionPane.INFORMATION_MESSAGE,
      )
      return
    }
    groupsSearch.text = ""
    locationsSearch.text = ""
    selectMap(holder, directory)
  }

  private fun eventFor(map: EditorMap, marker: MapEventMarker): Json.JObj? =
      when (marker.type) {
        MapEventType.PERSON -> map.objects.getOrNull(marker.index)
        MapEventType.SCRIPT -> map.bgEvents.getOrNull(marker.index)
        MapEventType.TRIGGER -> map.coordEvents.getOrNull(marker.index)
        MapEventType.WARP -> map.warps.getOrNull(marker.index)
      }

  private fun eventArrayKey(type: MapEventType): String =
      when (type) {
        MapEventType.PERSON -> "object_events"
        MapEventType.SCRIPT -> "bg_events"
        MapEventType.TRIGGER -> "coord_events"
        MapEventType.WARP -> "warp_events"
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
    snapshotForUndo()
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
    canvas.hoveredBlock = x to y
    val map = currentMap ?: return
    val block = map.layout.tileAt(x, y) ?: return
    val mid = block and 0x3FF
    val collision = (block shr 10) and 0x3
    val elevation = (block shr 12) and 0xF
    status.text =
        "(%d, %d)  metatile=0x%03X  collision=%d  elevation=%d".format(x, y, mid, collision, elevation)
  }

  private fun save(): Boolean {
    val nds = currentNdsMap
    if (nds != null) {
      val holder = currentNdsHolder ?: return false
      return try {
        holder.project.save(nds)
        dirty = false
        updateTitle()
        status.text = "Saved DS map ${nds.name}"
        true
      } catch (t: Throwable) {
        JOptionPane.showMessageDialog(
            this, t.message ?: t.toString(), "Save failed", JOptionPane.ERROR_MESSAGE)
        false
      }
    }
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
    val gbaName = currentMap?.dirName?.let { " — $it" }
    val ndsName = currentNdsMap?.name?.let { " — DS $it" }
    title = "OpenMMO Map Editor${gbaName ?: ndsName ?: ""}${if (dirty) " *" else ""}"
  }

  // ---- DS editing callbacks -------------------------------------------------

  /** Consumes viewport clicks while one of the prop modes is active. */
  private fun handleNdsCellInteraction(hit: NdsPointerHit, dragging: Boolean): Boolean {
    val map = currentNdsMap ?: return false
    return when (ndsPaintMode.selectedIndex) {
      4 -> {
        if (!dragging) {
          val exactId = hit.modelGroup
              ?.takeIf { it.startsWith("prop:") }
              ?.removePrefix("prop:")
              ?.takeIf { candidate -> map.props.any { it.id == candidate } }
          val cx = hit.groundX ?: hit.cellX?.plus(0.5f)
          val cz = hit.groundZ ?: hit.cellZ?.plus(0.5f)
          val footprintProp =
              if (exactId == null && cx != null && cz != null) {
                currentNdsHolder?.project?.propAt(map, cx, cz)
              } else null
          val terrainSelection =
              if (exactId == null && hit.modelGroup != null) {
                currentNdsHolder?.project?.terrainObject(map, hit.modelGroup)
              } else null
          if (terrainSelection != null) {
            selectNdsTerrainObject(terrainSelection.groupId)
          } else {
            selectNdsProp(exactId ?: footprintProp?.id)
          }
          val selected = map.props.firstOrNull { it.id == selectedNdsPropId }
          if (selected != null && cx != null && cz != null) {
            // Keep the grabbed point under the cursor instead of snapping a large prop's origin.
            ndsPropDragOffsetX = selected.x - cx
            ndsPropDragOffsetZ = selected.z - cz
          } else {
            ndsPropDragOffsetX = 0f
            ndsPropDragOffsetZ = 0f
          }
          val terrainGroup = selectedNdsTerrainGroup
          if (terrainGroup != null && cx != null && cz != null) {
            val offset = currentNdsHolder?.project?.terrainObjectOffset(map, terrainGroup) ?: (0f to 0f)
            ndsTerrainDragStartX = cx
            ndsTerrainDragStartZ = cz
            ndsTerrainDragInitialOffsetX = offset.first
            ndsTerrainDragInitialOffsetZ = offset.second
          } else {
            ndsTerrainDragStartX = null
            ndsTerrainDragStartZ = null
          }
        } else {
          val prop = map.props.firstOrNull { it.id == selectedNdsPropId }
          val x = hit.groundX ?: hit.cellX?.plus(0.5f)
          val z = hit.groundZ ?: hit.cellZ?.plus(0.5f)
          if (prop != null && x != null && z != null) {
            prop.x = x + ndsPropDragOffsetX
            prop.z = z + ndsPropDragOffsetZ
            ndsPropsPanel.refreshProps(prop.id)
            markDirty()
            refreshNdsPropGeometry(refreshTextures = false)
          } else {
            val terrainGroup = selectedNdsTerrainGroup
            val startX = ndsTerrainDragStartX
            val startZ = ndsTerrainDragStartZ
            val project = currentNdsHolder?.project
            if (terrainGroup != null && startX != null && startZ != null &&
                x != null && z != null && project != null &&
                project.moveTerrainObject(
                    map,
                    terrainGroup,
                    ndsTerrainDragInitialOffsetX + x - startX,
                    ndsTerrainDragInitialOffsetZ + z - startZ,
                )) {
              markDirty()
              refreshNdsPropGeometry(refreshTextures = false)
              status.text = "Moved terrain scenery; collision remains unchanged"
            }
          }
        }
        true
      }
      5 -> {
        if (!dragging) removeNdsSceneryObjectAt(hit)
        true
      }
      else -> false
    }
  }

  private fun removeNdsSceneryObjectAt(hit: NdsPointerHit) {
    val map = currentNdsMap ?: return
    val holder = currentNdsHolder ?: return
    val propId = hit.modelGroup
        ?.takeIf { it.startsWith("prop:") }
        ?.removePrefix("prop:")
    if (propId != null) {
      val removal = holder.project.removePropObject(
          map, propId, ndsClearCollisionWithTerrain.isSelected) ?: return
      selectedNdsPropId = null
      selectedNdsTerrainGroup = null
      ndsPropsPanel.refreshProps(null)
      markDirty()
      refreshNdsPropGeometry(refreshTextures = false)
      val removed = removal.removedProp!!
      val cleared = removal.clearedCollision.size
      status.text = if (cleared > 0) {
        "Removed scenery prop ${removed.modelKey.substringAfter(':')} and cleared $cleared collision tile(s)"
      } else {
        "Removed scenery prop ${removed.modelKey.substringAfter(':')}; collision unchanged"
      }
      return
    }
    val exact = hit.modelGroup?.let { holder.project.terrainObject(map, it) }
    val x = hit.cellX
    val z = hit.cellZ
    val selection = exact ?: if (x != null && z != null) {
      holder.project.terrainObjectAt(map, x + 0.5f, z + 0.5f)
    } else null
    if (selection == null) {
      status.text = "No separate scenery object found here"
      return
    }
    val removal = holder.project.removeTerrainObject(
        map,
        selection,
        ndsClearCollisionWithTerrain.isSelected,
    ) ?: return
    markDirty()
    refreshNdsPropGeometry(refreshTextures = false)
    val cleared = removal.clearedCollision.size
    status.text = if (cleared > 0) {
      "Removed terrain object (${selection.triangleCount} triangles) and cleared $cleared collision tile(s)"
    } else {
      "Removed terrain object (${selection.triangleCount} triangles); collision unchanged"
    }
  }

  private fun restoreLastNdsTerrainObject() {
    val map = currentNdsMap ?: return
    val holder = currentNdsHolder ?: return
    val restored = holder.project.restoreLastTerrainObject(map)
    if (restored == null) {
      status.text = "There are no removed scenery objects to restore"
      return
    }
    restored.removedProp?.let { prop ->
      selectedNdsPropId = prop.id
      selectedNdsTerrainGroup = null
      ndsPropsPanel.refreshProps(prop.id)
    } ?: run {
      selectedNdsTerrainGroup = restored.groupId.takeIf { it.isNotEmpty() }
    }
    markDirty()
    refreshNdsPropGeometry(refreshTextures = restored.removedProp != null)
    status.text = "Restored the last scenery object and its previous collision values"
  }

  private fun beginNdsPropPlacement(modelKey: String) {
    val map = currentNdsMap ?: return
    val holder = currentNdsHolder ?: return
    try {
      val prop = holder.project.createProp(
          modelKey,
          map.grid.cols / 2f,
          map.grid.rows / 2f,
      )
      map.props += prop
      selectedNdsPropId = prop.id
      ndsPropsPanel.refreshProps(prop.id)
      ndsPaintMode.selectedIndex = 4
      refreshNdsPropGeometry(refreshTextures = true)
      markDirty()
      status.text = "Placed ${modelKey.substringAfter(':')} at the map center — drag it to move"
    } catch (t: Throwable) {
      JOptionPane.showMessageDialog(
          this, t.message ?: t.toString(), "Place prop failed", JOptionPane.ERROR_MESSAGE)
    }
  }

  private fun selectNdsProp(id: String?) {
    val map = currentNdsMap ?: return
    selectedNdsPropId = id?.takeIf { candidate -> map.props.any { it.id == candidate } }
    selectedNdsTerrainGroup = null
    if (selectedNdsPropId != null && ndsPaintMode.selectedIndex != 4) {
      ndsPaintMode.selectedIndex = 4
    }
    ndsPropsPanel.selectProp(selectedNdsPropId)
    refreshNdsMarkers()
    val prop = map.props.firstOrNull { it.id == selectedNdsPropId }
    status.text = if (prop == null) "No prop selected"
    else "Selected ${prop.modelKey.substringAfter(':')} — drag to move, Delete removes"
  }

  private fun selectNdsTerrainObject(groupId: String?) {
    val map = currentNdsMap ?: return
    val project = currentNdsHolder?.project ?: return
    selectedNdsPropId = null
    selectedNdsTerrainGroup = groupId?.takeIf { project.terrainObject(map, it) != null }
    ndsPropsPanel.selectProp(null)
    refreshNdsMarkers()
    status.text = if (selectedNdsTerrainGroup == null) {
      "No scenery selected"
    } else {
      "Selected baked scenery — drag to move, Delete removes; collision is edited separately"
    }
  }

  private fun removeSelectedNdsObject() {
    if (selectedNdsPropId != null) {
      removeSelectedNdsProp()
      return
    }
    val map = currentNdsMap ?: return
    val holder = currentNdsHolder ?: return
    val groupId = selectedNdsTerrainGroup ?: return
    val selection = holder.project.terrainObject(map, groupId) ?: return
    val removal = holder.project.removeTerrainObject(
        map, selection, ndsClearCollisionWithTerrain.isSelected) ?: return
    selectedNdsTerrainGroup = null
    markDirty()
    refreshNdsPropGeometry(refreshTextures = false)
    status.text = if (removal.clearedCollision.isNotEmpty()) {
      "Removed selected scenery and cleared ${removal.clearedCollision.size} collision tile(s)"
    } else {
      "Removed selected scenery; collision unchanged"
    }
  }

  private fun removeSelectedNdsProp() {
    val map = currentNdsMap ?: return
    val holder = currentNdsHolder ?: return
    val id = selectedNdsPropId ?: return
    val removal = holder.project.removePropObject(
        map, id, ndsClearCollisionWithTerrain.isSelected) ?: return
    val removed = removal.removedProp ?: return
    selectedNdsPropId = null
    selectedNdsTerrainGroup = null
    ndsPropsPanel.refreshProps(null)
    markDirty()
    refreshNdsPropGeometry(refreshTextures = false)
    status.text = "Removed prop ${removed.modelKey.substringAfter(':')}"
  }

  private fun duplicateSelectedNdsProp() {
    val map = currentNdsMap ?: return
    val holder = currentNdsHolder ?: return
    val source = map.props.firstOrNull { it.id == selectedNdsPropId } ?: return
    val duplicate = holder.project.duplicateProp(source)
    map.props += duplicate
    selectedNdsPropId = duplicate.id
    ndsPropsPanel.refreshProps(duplicate.id)
    markDirty()
    refreshNdsPropGeometry(refreshTextures = false)
    status.text = "Duplicated prop; drag it into place"
  }

  private fun onNdsPropTransformChanged() {
    if (currentNdsMap == null) return
    markDirty()
    refreshNdsPropGeometry(refreshTextures = false)
  }

  private fun refreshNdsPropGeometry(refreshTextures: Boolean) {
    val map = currentNdsMap ?: return
    val holder = currentNdsHolder ?: return
    val v = view() ?: return
    val terrain = holder.project.trianglesFor(map)
    val props = holder.project.buildingTrianglesFor(map)
    v.modelTriangles = terrain + props
    if (refreshTextures) {
      v.modelTextures = holder.project.texturesFor(map)
      v.modelPalettes = holder.project.palettesFor(map)
    }
    refreshNdsMarkers()
    v.asComponent().repaint()
  }

  private fun paintNdsCell(x: Int, z: Int) {
    val map = currentNdsMap ?: return
    val view = view() ?: return
    val layer = view.activeLayer
    if (ndsPaintMode.selectedIndex == 3) {
      map.grid.setHeight(layer, x, z, view.activeHeight)
    } else {
      map.grid.setTile(layer, x, z, view.activeTile)
    }
    markDirty()
    view.asComponent().repaint()
  }

  private fun paintNdsCollision(x: Int, z: Int, value: Int) {
    val map = currentNdsMap ?: return
    val view = view() ?: return
    if (ndsPaintMode.selectedIndex == 2) {
      map.grid.setPermission(x, z, value)
    } else {
      map.grid.setCollision(x, z, value)
    }
    markDirty()
    view.asComponent().repaint()
  }

  private fun onNdsHeaderApplied() {
    markDirty()
    status.text = "Header applied to ${currentNdsMap?.name}"
  }

  private fun onNdsEventsChanged() {
    markDirty()
    refreshNdsMarkers()
  }

  // ---- DS export ------------------------------------------------------------

  private fun exportNdsCurrent() {
    val holder = currentNdsHolder ?: return
    val map = currentNdsMap ?: return
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

  private fun exportNdsAll() {
    val holder = currentNdsHolder ?: return
    if (dirty && !save()) return
    val dir = chooseExportDir() ?: return
    try {
      val files = holder.exporter.exportAll(dir)
      JOptionPane.showMessageDialog(
          this,
          "Wrote ${files.size} DS maps to ${dir.absolutePath}",
          "Export",
          JOptionPane.INFORMATION_MESSAGE)
    } catch (t: Throwable) {
      JOptionPane.showMessageDialog(
          this, t.message ?: t.toString(), "Export failed", JOptionPane.ERROR_MESSAGE)
    }
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

  private fun exportImage() {
    val map = currentMap ?: return
    val holder = currentHolder ?: return
    val chooser = JFileChooser().apply {
      dialogTitle = "Save map image"
      selectedFile = File("${map.dirName}.png")
      fileSelectionMode = JFileChooser.FILES_ONLY
    }
    if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
    try {
      val img = holder.renderer.renderMap(map.layout, overlay)
      ImageIO.write(img, "png", chooser.selectedFile)
      JOptionPane.showMessageDialog(
          this, "Wrote ${chooser.selectedFile.absolutePath}", "Export Image", JOptionPane.INFORMATION_MESSAGE)
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

  private fun rememberRecentProject(path: String) {
    recentList.remove(path)
    recentList.add(0, path)
    val obj = loadConfig() ?: Json.JObj(linkedMapOf())
    obj.entries["recentProjects"] = Json.JArr(recentList.take(10).map { Json.JStr(it) })
    saveConfig(obj)
  }

  private fun persistWindowGeometry() {
    val obj = loadConfig() ?: Json.JObj(linkedMapOf())
    obj.entries["windowSize"] = Json.JArr(listOf(Json.JNum(width.toDouble()), Json.JNum(height.toDouble())))
    obj.entries["windowPos"] = Json.JArr(listOf(Json.JNum(x.toDouble()), Json.JNum(y.toDouble())))
    saveConfig(obj)
  }

  companion object {
    private const val CONFIG_FILE = ".openmmo-map-editor.json"

    @JvmStatic
    fun show(dirs: List<File>) {
      SwingUtilities.invokeLater { EditorFrame(dirs).isVisible = true }
    }

    private fun configFile(): File =
        File(System.getProperty("user.home"), CONFIG_FILE)

    private fun loadConfig(): Json.JObj? {
      val f = configFile()
      if (!f.isFile) return null
      return try { JsonParser.parse(f.readText()).asObj() } catch (_: Exception) { null }
    }

    private fun saveConfig(obj: Json.JObj) {
      try { configFile().writeText(JsonWriter.writePretty(obj)) } catch (_: Exception) {}
    }
  }
}
