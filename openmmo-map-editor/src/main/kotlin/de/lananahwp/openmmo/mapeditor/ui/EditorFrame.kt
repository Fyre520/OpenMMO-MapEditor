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
import de.lananahwp.openmmo.mapeditor.model.NdsCellEdit
import de.lananahwp.openmmo.mapeditor.model.NdsCellKind
import de.lananahwp.openmmo.mapeditor.model.NdsEditHistory
import de.lananahwp.openmmo.mapeditor.model.NdsGrid
import de.lananahwp.openmmo.mapeditor.model.NdsGrassField
import de.lananahwp.openmmo.mapeditor.model.NdsGridStep
import de.lananahwp.openmmo.mapeditor.model.NdsMap
import de.lananahwp.openmmo.mapeditor.model.NdsMapCropper
import de.lananahwp.openmmo.mapeditor.model.NdsProp
import de.lananahwp.openmmo.mapeditor.model.NdsSceneSnapshot
import de.lananahwp.openmmo.mapeditor.model.NdsSceneStep
import de.lananahwp.openmmo.mapeditor.model.NdsUndoStep
import de.lananahwp.openmmo.mapeditor.model.NdsWalkSurface
import de.lananahwp.openmmo.mapeditor.model.NdsWalkSurfaceDirection
import de.lananahwp.openmmo.mapeditor.project.DecompProject
import de.lananahwp.openmmo.mapeditor.project.NdsExporter
import de.lananahwp.openmmo.mapeditor.project.NdsProject
import de.lananahwp.openmmo.mapeditor.project.NdsPropLibrary
import de.lananahwp.openmmo.mapeditor.project.OpenmmoExporter
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.InputEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
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
import javax.swing.JDialog
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
import javax.swing.SwingWorker
import javax.swing.Timer
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

private enum class NdsWalkTransformKind { MOVE, MOVE_HEIGHT, RESIZE, ROTATE, SCALE, EDGE_HEIGHT }

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
  // DS undo, kept apart from the GBA stacks above: the two editors share a window but never a
  // map, so a step from one could not be applied to the other.
  private val ndsHistory = NdsEditHistory()
  /** The scene as it was when the current prop drag started. */
  private var ndsDragSceneBefore: NdsSceneSnapshot? = null
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
  private val ndsCursorCoordinates = JLabel("Cursor: X —, Z —")
  private var ndsCursorCell: Pair<Int, Int>? = null

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
  private val ndsActiveRomTilesOnly = JCheckBox("Load tiles from active ROM only", false)
  private val ndsLayerSpinner = JSpinner(SpinnerNumberModel(0, 0, NdsGrid.LAYERS - 1, 1))
  private val ndsHeightSpinner = JSpinner(SpinnerNumberModel(0.0, -32.0, 32.0, 0.25))
  private val ndsCollisionValueSpinner = JSpinner(SpinnerNumberModel(0, 0, 255, 1))
  /** Paint brush width in squares, for the four modes that write a square at a time. */
  private val ndsTileBrushSpinner = JSpinner(SpinnerNumberModel(1, 1, 32, 1))
  /**
   * Whether a prop lands on whole map squares.
   *
   * Painting is always square-aligned; props are placed in continuous map coordinates, and a
   * surface lifted off one square only lines back up with the grid at a whole coordinate.
   */
  private val ndsSnapToGridCheck = JCheckBox("Snap to grid", true)
  private val ndsPaintMode =
      JComboBox(arrayOf(
          "Tile",
          "Collision",
          "Permission",
          "Height",
          "Select Object / Move Prop",
          "Remove Scenery Object",
          "Pick Surface -> Prop",
          "Grass Field",
          "Walk Surface",
      ))
  private val ndsGridCheck = JCheckBox("Grid")
  private val ndsCollisionCheck = JCheckBox("Collisions")
  private val ndsWalkSurfaceCheck = JCheckBox("Show walk surfaces")
  private val ndsWalkToolLabel = JLabel("Walk tool:")
  private val ndsWalkTool = JComboBox(arrayOf("Flat", "Slope: drag low to high", "From prop"))
  private val ndsWalkHighLabel = JLabel("High:")
  private val ndsWalkHighSpinner = JSpinner(SpinnerNumberModel(1.0, -32.0, 32.0, 0.25))
  private val ndsClearCollisionWithTerrain = JCheckBox("Clear collision with object", true)
  private val ndsCollisionEditView = JCheckBox("Transparent collision view")
  private val ndsShowOnlyTiles = JCheckBox("Show only tiles")
  private val ndsRestoreTerrainButton = JButton("Restore last object")

  // Surface picking ("Pick Surface -> Prop"). Kept entirely separate from the terrain-object
  // selection above: this one is a set of map tiles, not a connected-component group, so flat
  // ground like a path can be taken a square at a time.
  private val ndsSurfaceBrushSpinner = JSpinner(SpinnerNumberModel(1, 1, 32, 1))
  private val ndsSurfaceSameTexture = JCheckBox("Same texture only", true)
  /**
   * Whether the vertical faces standing on the picked squares come along too.
   *
   * Purely additive, and off by default, so both cut modes behave as they always have until it
   * is asked for. It is what makes a cliff face reachable at all: a tile-aligned wall stands
   * exactly on the line between two squares, so the footprint test the surfaces are chosen by
   * rejects it from both at once.
   */
  private val ndsSurfaceIncludeWalls = JCheckBox("Include walls", false)
  private val ndsSurfaceCut = JComboBox(arrayOf("Whole squares", "Free-form"))
  private val ndsSurfaceSaveButton = JButton("Save selection as prop...")
  private val ndsSurfaceAddTileButton = JButton("Add as tile")
  private val ndsClearAssetsButton = JButton("Clear Assets...")

  /** One Tile-combo row. Null [project] means a built-in procedural tile. */
  private data class NdsTileChoice(val index: Int, val label: String, val project: NdsProject?)
  private var ndsTileChoices: List<NdsTileChoice> =
      NdsTileset.tiles.mapIndexed { index, tile -> NdsTileChoice(index, tile.name, null) }
  private var refreshingNdsTileCombo = false
  private val ndsSurfaceClearButton = JButton("Clear selection")
  private val ndsSurfaceCells = LinkedHashSet<Long>()
  private val ndsAssetCleanupUndo =
      java.util.ArrayDeque<List<Pair<NdsProject, NdsProject.AssetCleanupUndo>>>()

  private var ndsWalkPaintStart: Pair<Int, Int>? = null
  private var ndsWalkPaintCurrent: Pair<Int, Int>? = null
  private var ndsWalkPaintBefore: NdsSceneSnapshot? = null
  private var ndsWalkPaintErasing = false
  private var ndsWalkPreview: NdsWalkSurface? = null
  private var selectedNdsWalkSurfaceId: String? = null
  private var ndsWalkTransformKind: NdsWalkTransformKind? = null
  private var ndsWalkTransformBefore: NdsSceneSnapshot? = null
  private var ndsWalkTransformOriginal: NdsWalkSurface? = null
  private var ndsWalkTransformStartCell: Pair<Int, Int>? = null
  private var ndsWalkTransformStartGround: Pair<Float, Float>? = null
  private var ndsWalkTransformStartScreen: Pair<Int, Int>? = null
  private var ndsWalkTransformScreenAxis: Pair<Double, Double>? = null
  private var ndsWalkTransformEdge: NdsWalkSurfaceDirection? = null
  private var ndsWalkTransformCenterScreen: Pair<Double, Double>? = null

  /**
   * The mesh height the pointer met when each square was picked.
   *
   * Maps stack surfaces over one square — a tree canopy above its own ground, tall grass above the
   * floor it grows from — so a square has to remember which one was actually clicked. Without it,
   * squaring rebuilt roughly a fifth of National Park's squares up in the canopy.
   */
  private val ndsSurfacePickedHeights = HashMap<Long, Float>()
  private var ndsSurfaceTextureFilter: String? = null
  private var ndsSurfaceBoxAnchorX: Float? = null
  private var ndsSurfaceBoxAnchorZ: Float? = null
  private var ndsSurfaceBoxBaseCells: Set<Long> = emptySet()
  // Where the current drag last resolved on the mesh, so the squares between two pointer
  // samples can be filled in. Null between strokes.
  private var ndsSurfaceLastX: Float? = null
  private var ndsSurfaceLastZ: Float? = null
  private var ndsSurfaceLastY: Float? = null
  private var ndsSurfaceErasing = false
  private var ndsTilePickGesture = false

  /** Multi-selection plus the primary prop whose transform is shown in the sidebar. */
  private val selectedNdsPropIds = LinkedHashSet<String>()
  private var selectedNdsPropId: String? = null
  private var selectedNdsTerrainGroup: String? = null
  private var ndsPropDragOffsetX = 0f
  private var ndsPropDragOffsetZ = 0f
  private var ndsPropDragStartPositions = emptyMap<String, Pair<Float, Float>>()
  private var ndsTerrainDragStartX: Float? = null
  private var ndsTerrainDragStartZ: Float? = null
  private var ndsTerrainDragInitialOffsetX = 0f
  private var ndsTerrainDragInitialOffsetZ = 0f
  private val ndsPropLibraries = HashMap<de.lananahwp.openmmo.mapeditor.core.NdsFamily, NdsPropLibrary>()
  private val ndsPropLibrariesLoading = HashSet<de.lananahwp.openmmo.mapeditor.core.NdsFamily>()
  /** Catalog identity -> open project supplying an extracted/merged prop not yet copied locally. */
  private val ndsExternalPropSources = HashMap<String, NdsProject>()
  private var showAllNdsProps = false
  private var ndsNumericTransformBefore: NdsSceneSnapshot? = null
  private var ndsNumericTransformMap: NdsMap? = null
  private val ndsNumericTransformTimer = Timer(400) { commitNdsNumericTransform() }.apply {
    isRepeats = false
  }
  private val ndsPropsPanel = NdsPropsPanel(
      onImport = { importNdsPropModel() },
      onPlace = { beginNdsPropPlacement(it) },
      onPreview = { info -> previewNdsProp(info) },
      onSelect = { ids, primary -> selectNdsProps(ids, primary) },
      onRemove = { removeSelectedNdsProp() },
      onDuplicate = { duplicateSelectedNdsProp() },
      onMerge = { mergeSelectedNdsProps() },
      onShowAllNdsPropsChanged = {
        showAllNdsProps = it
        rebuildNdsPropCatalog()
      },
      onChanged = { before -> onNdsPropTransformChanged(before) },
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
    contentPane.add(JPanel(BorderLayout(12, 0)).also {
      it.border = BorderFactory.createEmptyBorder(2, 6, 2, 6)
      it.add(ndsCursorCoordinates, BorderLayout.WEST)
      it.add(status, BorderLayout.CENTER)
    }, BorderLayout.SOUTH)

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
    // Custom extraction names can be arbitrarily long. Let the popup show those names without
    // letting the closed combo grow and push the Mode selector into a clipped toolbar row.
    ndsTileCombo.preferredSize = Dimension(280, ndsTileCombo.preferredSize.height)
    ndsTileCombo.toolTipText = "Select a built-in or extracted tile (Alt+click the map to pick one)"
    ndsTileCombo.addActionListener {
      selectNdsTileChoice()
    }
    ndsActiveRomTilesOnly.toolTipText =
        "Show only custom tiles stored with the currently open HeartGold or Platinum project"
    ndsActiveRomTilesOnly.addActionListener { refreshNdsTileCombo() }
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
      if (ndsPaintMode.selectedIndex == 8) refreshNdsWalkPaintPreview()
    }
    ndsTileBrushSpinner.preferredSize = Dimension(60, ndsTileBrushSpinner.preferredSize.height)
    ndsTileBrushSpinner.toolTipText =
        "How many squares across one click paints, centred on the square under the pointer"
    ndsTileBrushSpinner.addChangeListener {
      view()?.brushSize = (ndsTileBrushSpinner.value as Number).toInt()
    }
    ndsSnapToGridCheck.toolTipText =
        "Place and drag props on whole map squares; painted tiles are always square-aligned"
    ndsCollisionValueSpinner.preferredSize = Dimension(60, ndsCollisionValueSpinner.preferredSize.height)
    ndsCollisionValueSpinner.addChangeListener {
      view()?.brushCollision = (ndsCollisionValueSpinner.value as Number).toInt()
    }
    ndsPaintMode.addActionListener {
      view()?.setPaintMode(
          if (ndsPaintMode.selectedIndex == 7) 0 else ndsPaintMode.selectedIndex.coerceAtLeast(0))
      onNdsPaintModeChanged()
    }
    ndsGridCheck.isSelected = true
    ndsGridCheck.addActionListener { view()?.showGrid = ndsGridCheck.isSelected }
    ndsCollisionCheck.addActionListener { view()?.showCollision = ndsCollisionCheck.isSelected }
    ndsWalkSurfaceCheck.toolTipText =
        "Show ROM BDHC or the custom map's explicitly authored walkable-height planes"
    ndsWalkSurfaceCheck.addActionListener { refreshNdsWalkSurfaces() }
    ndsWalkTool.toolTipText =
        "Flat: drag an area; Slope: drag from its low edge to high edge; From prop: click stairs"
    ndsWalkTool.addActionListener {
      clearNdsWalkPaintPreview()
      updateNdsWalkToolControls()
      if (ndsPaintMode.selectedIndex == 8) onNdsPaintModeChanged()
    }
    ndsWalkHighSpinner.editor = JSpinner.NumberEditor(ndsWalkHighSpinner, "0.####")
    ndsWalkHighSpinner.preferredSize = Dimension(72, ndsWalkHighSpinner.preferredSize.height)
    ndsWalkHighSpinner.toolTipText = "Height reached at the end of a low-to-high slope drag"
    ndsWalkHighSpinner.addChangeListener { refreshNdsWalkPaintPreview() }
    ndsCollisionEditView.addActionListener {
      val enabled = ndsCollisionEditView.isSelected
      if (enabled) {
        ndsShowOnlyTiles.isSelected = false
        ndsCollisionCheck.isSelected = true
        ndsPaintMode.selectedIndex = 1
      }
      applyNdsVisibilityMode()
      status.text = if (enabled) {
        "Transparent collision view — choose Collision mode and paint the grid directly"
      } else {
        "Normal map view"
      }
    }
    ndsShowOnlyTiles.toolTipText =
        "Make placed props transparent so the painted tiles beneath them stay visible and editable"
    ndsShowOnlyTiles.addActionListener {
      val enabled = ndsShowOnlyTiles.isSelected
      if (enabled) {
        ndsCollisionEditView.isSelected = false
        ndsCollisionCheck.isSelected = false
        ndsPaintMode.selectedIndex = 0
      }
      applyNdsVisibilityMode()
      status.text = if (enabled) {
        "Tile-only view — placed props are transparent; paint tiles normally"
      } else {
        "Normal map view"
      }
    }
    ndsRestoreTerrainButton.addActionListener { restoreLastNdsTerrainObject() }

    ndsSurfaceBrushSpinner.preferredSize = Dimension(52, ndsSurfaceBrushSpinner.preferredSize.height)
    ndsSurfaceBrushSpinner.toolTipText =
        "How many map squares across each click selects: 1 picks the single square under the pointer"
    ndsSurfaceSameTexture.toolTipText =
        "Keep only the clicked surface's texture, so a path comes away without the grass it is joined to"
    ndsSurfaceSameTexture.addActionListener {
      // Toggling changes what the already-picked squares resolve to, so redraw immediately
      // instead of waiting for the next click.
      refreshNdsSurfaceHighlight()
      if (ndsSurfaceCells.isNotEmpty()) {
        status.text = "Surface selection: ${ndsSurfaceHighlightTriangles().size} triangle(s)" +
            (ndsSurfaceTextureFilterOrNull()?.let { " of texture '$it'" } ?: " (all textures)")
      }
    }
    ndsSurfaceIncludeWalls.toolTipText =
        "Also take the vertical faces standing on the picked squares - cliff faces, step risers. " +
            "Needed for a cliff: a wall sitting on a square edge belongs to neither neighbour " +
            "without it."
    ndsSurfaceIncludeWalls.addActionListener {
      refreshNdsSurfaceHighlight()
      if (ndsSurfaceCells.isNotEmpty()) {
        status.text = "Surface selection: ${ndsSurfaceHighlightTriangles().size} triangle(s)" +
            (if (ndsSurfaceIncludeWalls.isSelected) ", walls included" else ", without walls")
      }
    }
    ndsSurfaceCut.toolTipText =
        "Whole squares: each picked square becomes one flat quad, easy to place and line up. " +
            "Free-form: keeps the map's own slopes and walls exactly as they are."
    ndsSurfaceCut.addActionListener {
      refreshNdsSurfaceHighlight()
      if (ndsSurfaceCells.isNotEmpty()) {
        status.text = "Surface selection: ${ndsSurfaceHighlightTriangles().size} triangle(s) " +
            "(${(ndsSurfaceCut.selectedItem as? String)?.lowercase()})"
      }
    }
    ndsSurfaceSaveButton.addActionListener { saveNdsSurfaceSelectionAsProp() }
    ndsSurfaceAddTileButton.toolTipText =
        "Add the picked rectangular area to the Tile list as a reusable multi-square stamp"
    ndsSurfaceAddTileButton.addActionListener { addNdsSurfaceSelectionAsTile() }
    ndsClearAssetsButton.toolTipText =
        "Review extracted props and custom tiles that are unused across every editor map"
    ndsClearAssetsButton.addActionListener { showNdsAssetCleanup() }
    ndsSurfaceClearButton.addActionListener {
      clearNdsSurfaceSelection()
      status.text = "Cleared the surface selection"
    }

    val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2))
    toolbar.add(JLabel("Tile:"))
    toolbar.add(ndsTileCombo)
    toolbar.add(ndsActiveRomTilesOnly)
    toolbar.add(JLabel("Layer:"))
    toolbar.add(ndsLayerSpinner)
    toolbar.add(JLabel("Height:"))
    toolbar.add(ndsHeightSpinner)
    toolbar.add(JLabel("Brush:"))
    toolbar.add(ndsTileBrushSpinner)
    toolbar.add(JLabel("Mode:"))
    toolbar.add(ndsPaintMode)
    toolbar.add(JLabel("Value:"))
    toolbar.add(ndsCollisionValueSpinner)
    toolbar.add(ndsGridCheck)
    toolbar.add(ndsCollisionCheck)
    val terrainToolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2))
    terrainToolbar.add(ndsCollisionEditView)
    terrainToolbar.add(ndsShowOnlyTiles)
    terrainToolbar.add(ndsWalkSurfaceCheck)
    terrainToolbar.add(ndsWalkToolLabel)
    terrainToolbar.add(ndsWalkTool)
    terrainToolbar.add(ndsWalkHighLabel)
    terrainToolbar.add(ndsWalkHighSpinner)
    terrainToolbar.add(ndsClearCollisionWithTerrain)
    terrainToolbar.add(ndsSnapToGridCheck)
    terrainToolbar.add(ndsRestoreTerrainButton)
    terrainToolbar.add(JLabel(
        "  Middle drag rotates · Left click edits · Alt+click picks tile · Right drag pans · Wheel zooms"))
    val surfaceToolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2))
    surfaceToolbar.add(JLabel("Pick brush (squares):"))
    surfaceToolbar.add(ndsSurfaceBrushSpinner)
    surfaceToolbar.add(ndsSurfaceSameTexture)
    surfaceToolbar.add(ndsSurfaceIncludeWalls)
    surfaceToolbar.add(JLabel("Cut:"))
    surfaceToolbar.add(ndsSurfaceCut)
    surfaceToolbar.add(ndsSurfaceSaveButton)
    surfaceToolbar.add(ndsSurfaceAddTileButton)
    surfaceToolbar.add(ndsSurfaceClearButton)
    surfaceToolbar.add(ndsClearAssetsButton)
    surfaceToolbar.add(JLabel("  Drag paints · Shift+drag boxes · Ctrl removes"))
    onNdsPaintModeChanged()
    panel.add(JPanel(GridLayout(3, 1)).also {
      it.add(toolbar)
      it.add(terrainToolbar)
      it.add(surfaceToolbar)
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
            { x, z, emptyOnly -> paintNdsCell(x, z, emptyOnly) },
            { x, z, value -> paintNdsCollision(x, z, value) },
            { hit, dragging -> handleNdsCellInteraction(hit, dragging) },
            { x, z -> eraseNdsCell(x, z) },
            { ndsHistory.beginStroke(ndsStrokeLabel()) },
            { cell -> updateNdsCursorCoordinates(cell) },
            { hit ->
              ndsTilePickGesture = false
              finishNdsWalkSurfacePaint(hit)
            },
        )
    val created =
        try {
          NdsGlMapView(
              { x, z, emptyOnly -> paintNdsCell(x, z, emptyOnly) },
              { x, z, value -> paintNdsCollision(x, z, value) },
              { hit, dragging -> handleNdsCellInteraction(hit, dragging) },
              { x, z -> eraseNdsCell(x, z) },
              { ndsHistory.beginStroke(ndsStrokeLabel()) },
              { cell -> updateNdsCursorCoordinates(cell) },
              { hit ->
                ndsTilePickGesture = false
                finishNdsWalkSurfacePaint(hit)
              },
          )
        } catch (t: Throwable) {
          System.out.println("[Nds] OpenGL view unavailable (${t.message}); using software renderer")
          software
        }
    ndsView = created
    installNdsShortcuts(created.asComponent())
    ndsViewContainer.removeAll()
    ndsViewContainer.add(created.asComponent(), BorderLayout.CENTER)
    ndsViewContainer.revalidate()
    ndsViewContainer.repaint()
    created.setPaintMode(if (ndsPaintMode.selectedIndex == 7) 0 else ndsPaintMode.selectedIndex.coerceAtLeast(0))
    created.showGrid = ndsGridCheck.isSelected
    created.showCollision = ndsCollisionCheck.isSelected
    created.brushSize = (ndsTileBrushSpinner.value as Number).toInt()
    created.brushCollision = (ndsCollisionValueSpinner.value as Number).toInt()
    created.modelOpacity = if (ndsCollisionEditView.isSelected) 0.12f else 1f
    created.propOpacity = if (ndsShowOnlyTiles.isSelected) 0.35f else 1f
    created.walkSurfaceTriangles = currentNdsWalkSurfaces()
    return created
  }

  /** Resolves the optional ROM BDHC overlay without ever adding it to editable terrain. */
  private fun currentNdsWalkSurfaces(): List<de.lananahwp.openmmo.mapeditor.core.NdsTri> {
    if (!ndsWalkSurfaceCheck.isSelected) return emptyList()
    val project = currentNdsHolder?.project ?: return emptyList()
    val map = currentNdsMap ?: return emptyList()
    return project.bdhcTrianglesFor(map)
  }

  private fun refreshNdsWalkSurfaces() {
    val triangles = currentNdsWalkSurfaces()
    view()?.walkSurfaceTriangles = triangles
    refreshNdsWalkSurfaceHandles()
    val map = currentNdsMap
    status.text = when {
      !ndsWalkSurfaceCheck.isSelected -> "Walk-surface overlay hidden"
      triangles.isNotEmpty() && map?.isCustom == true ->
        "Showing ${triangles.size / 2} custom walk-surface plane(s)"
      triangles.isNotEmpty() -> "Showing ${triangles.size / 2} ROM BDHC walk-surface plate(s)"
      map?.isCustom == true -> "This custom map has no authored walk surfaces"
      map != null -> "No compatible ROM BDHC walk surfaces are available for this map"
      else -> "Open a Nintendo DS map to show its ROM BDHC walk surfaces"
    }
  }

  /** Keeps the walk-surface toolbar small: only controls used by the active tool are visible. */
  private fun updateNdsWalkToolControls() {
    val walkMode = ndsPaintMode.selectedIndex == 8
    val slopeTool = walkMode && ndsWalkTool.selectedIndex == 1
    ndsWalkToolLabel.isVisible = walkMode
    ndsWalkTool.isVisible = walkMode
    ndsWalkHighLabel.isVisible = slopeTool
    ndsWalkHighSpinner.isVisible = slopeTool
    ndsHeightSpinner.toolTipText = when {
      slopeTool -> "Height at the low edge of the slope"
      walkMode && ndsWalkTool.selectedIndex == 0 -> "Height of the flat walk surface"
      else -> "Tile height in map-tile units; arrows change it by 0.25 and decimals may be typed"
    }
    ndsWalkTool.parent?.revalidate()
    ndsWalkTool.parent?.repaint()
  }

  private fun walkDirection(role: NdsWalkHandleRole): NdsWalkSurfaceDirection? = when (role) {
    NdsWalkHandleRole.CENTER, NdsWalkHandleRole.ROTATE, NdsWalkHandleRole.SCALE -> null
    NdsWalkHandleRole.NORTH -> NdsWalkSurfaceDirection.NORTH
    NdsWalkHandleRole.EAST -> NdsWalkSurfaceDirection.EAST
    NdsWalkHandleRole.SOUTH -> NdsWalkSurfaceDirection.SOUTH
    NdsWalkHandleRole.WEST -> NdsWalkSurfaceDirection.WEST
  }

  private fun walkRole(direction: NdsWalkSurfaceDirection): NdsWalkHandleRole? = when (direction) {
    NdsWalkSurfaceDirection.FLAT -> null
    NdsWalkSurfaceDirection.NORTH -> NdsWalkHandleRole.NORTH
    NdsWalkSurfaceDirection.EAST -> NdsWalkHandleRole.EAST
    NdsWalkSurfaceDirection.SOUTH -> NdsWalkHandleRole.SOUTH
    NdsWalkSurfaceDirection.WEST -> NdsWalkHandleRole.WEST
  }

  private fun walkHandlePoint(
      surface: NdsWalkSurface,
      role: NdsWalkHandleRole,
  ): Triple<Float, Float, Float> {
    if (role == NdsWalkHandleRole.ROTATE) {
      val reference = surface.riseDirection.takeUnless { it == NdsWalkSurfaceDirection.FLAT }
          ?: NdsWalkSurfaceDirection.NORTH
      val edgeRole = requireNotNull(walkRole(reference))
      val edge = walkHandlePoint(surface, edgeRole)
      val offset = 0.8f
      return when (reference) {
        NdsWalkSurfaceDirection.NORTH -> Triple(edge.first, edge.second + 0.18f, edge.third - offset)
        NdsWalkSurfaceDirection.EAST -> Triple(edge.first + offset, edge.second + 0.18f, edge.third)
        NdsWalkSurfaceDirection.SOUTH -> Triple(edge.first, edge.second + 0.18f, edge.third + offset)
        NdsWalkSurfaceDirection.WEST -> Triple(edge.first - offset, edge.second + 0.18f, edge.third)
        NdsWalkSurfaceDirection.FLAT -> edge
      }
    }
    if (role == NdsWalkHandleRole.SCALE) {
      val x = surface.maxX + 0.55f
      val z = surface.maxZ + 0.55f
      val y = surface.heightAt(surface.maxX.toDouble(), surface.maxZ.toDouble()).toFloat() + 0.18f
      return Triple(x, y, z)
    }
    val x = when (role) {
      NdsWalkHandleRole.EAST -> surface.maxX.toFloat()
      NdsWalkHandleRole.WEST -> surface.minX.toFloat()
      else -> (surface.minX + surface.maxX) / 2f
    }
    val z = when (role) {
      NdsWalkHandleRole.NORTH -> surface.minZ.toFloat()
      NdsWalkHandleRole.SOUTH -> surface.maxZ.toFloat()
      else -> (surface.minZ + surface.maxZ) / 2f
    }
    return Triple(x, surface.heightAt(x.toDouble(), z.toDouble()).toFloat() + 0.04f, z)
  }

  /** Shows all four editable edges; changing a perpendicular edge reorients the slope. */
  private fun refreshNdsWalkSurfaceHandles() {
    val v = ndsView ?: return
    val surface = currentNdsMap?.walkSurfaces
        ?.firstOrNull { it.id == selectedNdsWalkSurfaceId }
    if (surface == null || ndsPaintMode.selectedIndex != 8) {
      selectedNdsWalkSurfaceId = null
      v.walkSurfaceHandles = emptyList()
      return
    }
    val roles = listOf(
        NdsWalkHandleRole.NORTH, NdsWalkHandleRole.EAST,
        NdsWalkHandleRole.SOUTH, NdsWalkHandleRole.WEST)
    val handles = mutableListOf<NdsWalkHandle>()
    val center = walkHandlePoint(surface, NdsWalkHandleRole.CENTER)
    handles += NdsWalkHandle(
        NdsWalkHandleRole.CENTER, center.first, center.second, center.third,
        0xFFFFFFFF.toInt())
    for (role in roles) {
      val direction = requireNotNull(walkDirection(role))
      val point = walkHandlePoint(surface, role)
      val color = when {
        surface.riseDirection == NdsWalkSurfaceDirection.FLAT -> 0xFF50E3C2.toInt()
        direction == surface.riseDirection -> 0xFFFF9D2E.toInt()
        direction == surface.riseDirection.opposite() -> 0xFF3498FF.toInt()
        else -> 0xFF50E3C2.toInt()
      }
      handles += NdsWalkHandle(role, point.first, point.second, point.third, color)
    }
    val rotate = walkHandlePoint(surface, NdsWalkHandleRole.ROTATE)
    handles += NdsWalkHandle(
        NdsWalkHandleRole.ROTATE, rotate.first, rotate.second, rotate.third,
        0xFFC76CFF.toInt())
    val scale = walkHandlePoint(surface, NdsWalkHandleRole.SCALE)
    handles += NdsWalkHandle(
        NdsWalkHandleRole.SCALE, scale.first, scale.second, scale.third,
        0xFFFFD34D.toInt())
    v.walkSurfaceHandles = handles
  }

  private fun selectNdsWalkSurface(id: String?) {
    val map = currentNdsMap
    selectedNdsWalkSurfaceId = id?.takeIf { candidate ->
      map?.walkSurfaces?.any { it.id == candidate } == true
    }
    refreshNdsWalkSurfaceHandles()
    val surface = map?.walkSurfaces?.firstOrNull { it.id == selectedNdsWalkSurfaceId }
    status.text = if (surface == null) {
      "Walk surface selection cleared"
    } else {
      "Selected walk surface X ${surface.minX}-${surface.maxX - 1}, " +
          "Z ${surface.minZ}-${surface.maxZ - 1}; move/resize icons, purple rotates, " +
          "yellow scales, Shift+any edge changes slope, Shift+center changes height"
    }
  }

  private fun clearNdsWalkTransform() {
    ndsWalkTransformKind = null
    ndsWalkTransformBefore = null
    ndsWalkTransformOriginal = null
    ndsWalkTransformStartCell = null
    ndsWalkTransformStartGround = null
    ndsWalkTransformStartScreen = null
    ndsWalkTransformScreenAxis = null
    ndsWalkTransformEdge = null
    ndsWalkTransformCenterScreen = null
  }

  private fun clearNdsWalkPaintPreview() {
    ndsWalkPaintStart = null
    ndsWalkPaintCurrent = null
    ndsWalkPaintBefore = null
    ndsWalkPaintErasing = false
    ndsWalkPreview = null
    selectedNdsWalkSurfaceId = null
    clearNdsWalkTransform()
    view()?.walkSurfaceTriangles = currentNdsWalkSurfaces()
  }

  private fun walkPaintCell(hit: NdsPointerHit?): Pair<Int, Int>? {
    val map = currentNdsMap ?: return null
    val x = hit?.cellX ?: return null
    val z = hit.cellZ ?: return null
    return (x to z).takeIf { x in 0 until map.grid.cols && z in 0 until map.grid.rows }
  }

  /** Builds the rectangle currently being dragged; slope direction follows low-edge to high-edge. */
  private fun buildNdsWalkPaintSurface(id: String): NdsWalkSurface? {
    val start = ndsWalkPaintStart ?: return null
    val end = ndsWalkPaintCurrent ?: return null
    val low = (ndsHeightSpinner.value as Number).toDouble()
    val minX = minOf(start.first, end.first)
    val minZ = minOf(start.second, end.second)
    val maxX = maxOf(start.first, end.first) + 1
    val maxZ = maxOf(start.second, end.second) + 1
    if (ndsWalkTool.selectedIndex == 0) {
      return NdsWalkSurface.cardinal(
          id, minX, minZ, maxX, maxZ, low, low, NdsWalkSurfaceDirection.FLAT)
    }
    if (ndsWalkTool.selectedIndex != 1 || start == end) return null
    val high = (ndsWalkHighSpinner.value as Number).toDouble()
    if (high <= low) return null
    val dx = end.first - start.first
    val dz = end.second - start.second
    val direction = if (kotlin.math.abs(dx) >= kotlin.math.abs(dz)) {
      if (dx > 0) NdsWalkSurfaceDirection.EAST else NdsWalkSurfaceDirection.WEST
    } else {
      if (dz > 0) NdsWalkSurfaceDirection.SOUTH else NdsWalkSurfaceDirection.NORTH
    }
    return NdsWalkSurface.cardinal(id, minX, minZ, maxX, maxZ, low, high, direction)
  }

  private fun refreshNdsWalkPaintPreview() {
    if (ndsPaintMode.selectedIndex != 8 || ndsWalkPaintErasing) return
    val map = currentNdsMap ?: return
    val project = currentNdsHolder?.project ?: return
    val preview = buildNdsWalkPaintSurface("__walk-preview__")
    ndsWalkPreview = preview
    val base = currentNdsWalkSurfaces()
    view()?.walkSurfaceTriangles =
        if (preview == null) base else base + project.walkSurfacePreviewTriangles(map, preview)
  }

  private fun eraseNdsWalkSurfaceAt(map: NdsMap, cell: Pair<Int, Int>) {
    val removed = map.walkSurfaces.filter {
      it.contains(cell.first + 0.5, cell.second + 0.5)
    }.map { it.id }.toSet()
    if (removed.isNotEmpty() && map.walkSurfaces.removeAll { it.id in removed }) {
      if (selectedNdsWalkSurfaceId in removed) selectedNdsWalkSurfaceId = null
      view()?.walkSurfaceTriangles = currentNdsWalkSurfaces()
      refreshNdsWalkSurfaceHandles()
    }
  }

  private fun beginNdsWalkTransform(
      map: NdsMap,
      surface: NdsWalkSurface,
      hit: NdsPointerHit,
      kind: NdsWalkTransformKind,
      edge: NdsWalkSurfaceDirection? = null,
  ) {
    selectedNdsWalkSurfaceId = surface.id
    ndsWalkTransformKind = kind
    ndsWalkTransformBefore = NdsSceneSnapshot.of(map)
    ndsWalkTransformOriginal = surface.copy()
    ndsWalkTransformStartCell = walkPaintCell(hit)
    ndsWalkTransformStartGround =
        if (hit.groundX != null && hit.groundZ != null) hit.groundX to hit.groundZ else null
    ndsWalkTransformStartScreen =
        if (hit.screenX != null && hit.screenY != null) hit.screenX to hit.screenY else null
    ndsWalkTransformEdge = edge
    val center = walkHandlePoint(surface, NdsWalkHandleRole.CENTER)
    ndsWalkTransformCenterScreen = view()?.projectMapPoint(center.first, center.second, center.third)
        ?.let { it[0] to it[1] }
    ndsWalkTransformScreenAxis = if (edge != null || kind == NdsWalkTransformKind.MOVE_HEIGHT) {
      val point = if (edge == null) center else {
        val role = requireNotNull(walkRole(edge))
        walkHandlePoint(surface, role)
      }
      val base = view()?.projectMapPoint(point.first, point.second, point.third)
      val raised = view()?.projectMapPoint(point.first, point.second + 1f, point.third)
      if (base == null || raised == null) null
      else (raised[0] - base[0]) to (raised[1] - base[1])
    } else null
    refreshNdsWalkSurfaceHandles()
  }

  private fun applyNdsWalkTransform(map: NdsMap, hit: NdsPointerHit) {
    val original = ndsWalkTransformOriginal ?: return
    val surface = map.walkSurfaces.firstOrNull { it.id == original.id } ?: return
    when (ndsWalkTransformKind) {
      NdsWalkTransformKind.MOVE -> {
        val start = ndsWalkTransformStartCell ?: return
        val current = walkPaintCell(hit) ?: return
        val rawDx = current.first - start.first
        val rawDz = current.second - start.second
        val dx = rawDx.coerceIn(-original.minX, map.grid.cols - original.maxX)
        val dz = rawDz.coerceIn(-original.minZ, map.grid.rows - original.maxZ)
        surface.minX = original.minX + dx
        surface.maxX = original.maxX + dx
        surface.minZ = original.minZ + dz
        surface.maxZ = original.maxZ + dz
        status.text =
            "Moving walk surface: X ${surface.minX}-${surface.maxX - 1}, " +
                "Z ${surface.minZ}-${surface.maxZ - 1}"
      }
      NdsWalkTransformKind.MOVE_HEIGHT -> {
        val start = ndsWalkTransformStartScreen ?: return
        val mx = hit.screenX ?: return
        val my = hit.screenY ?: return
        val axis = ndsWalkTransformScreenAxis
        val dx = (mx - start.first).toDouble()
        val dy = (my - start.second).toDouble()
        val amount = if (axis == null || axis.first * axis.first + axis.second * axis.second < 4.0) {
          -dy / 24.0
        } else {
          (dx * axis.first + dy * axis.second) /
              (axis.first * axis.first + axis.second * axis.second)
        }
        val delta = (kotlin.math.round(amount * 4.0) / 4.0)
        surface.copyHeightShapeFrom(original)
        surface.translateHeight(delta)
        status.text = if (surface.riseDirection == NdsWalkSurfaceDirection.FLAT) {
          "Moving walk surface vertically: height ${formatNdsHeight(surface.lowHeight)}"
        } else {
          "Moving slope vertically: ${formatNdsHeight(surface.lowHeight)} to " +
              formatNdsHeight(surface.highHeight)
        }
      }
      NdsWalkTransformKind.RESIZE -> {
        val edge = ndsWalkTransformEdge ?: return
        val start = ndsWalkTransformStartGround ?: return
        val x = hit.groundX ?: return
        val z = hit.groundZ ?: return
        surface.minX = original.minX
        surface.maxX = original.maxX
        surface.minZ = original.minZ
        surface.maxZ = original.maxZ
        val coordinate = when (edge) {
          NdsWalkSurfaceDirection.NORTH ->
            kotlin.math.round(original.minZ + z - start.second).toInt()
          NdsWalkSurfaceDirection.EAST ->
            kotlin.math.round(original.maxX + x - start.first).toInt()
          NdsWalkSurfaceDirection.SOUTH ->
            kotlin.math.round(original.maxZ + z - start.second).toInt()
          NdsWalkSurfaceDirection.WEST ->
            kotlin.math.round(original.minX + x - start.first).toInt()
          NdsWalkSurfaceDirection.FLAT -> return
        }
        surface.resizeEdge(edge, coordinate, map.grid)
        status.text =
            "Resizing ${edge.name.lowercase()} edge: " +
                "X ${surface.minX}-${surface.maxX - 1}, Z ${surface.minZ}-${surface.maxZ - 1}"
      }
      NdsWalkTransformKind.ROTATE -> {
        val x = hit.groundX ?: return
        val z = hit.groundZ ?: return
        val centerX = (original.minX + original.maxX) / 2f
        val centerZ = (original.minZ + original.maxZ) / 2f
        val dx = x - centerX
        val dz = z - centerZ
        if (kotlin.math.abs(dx) < 0.15f && kotlin.math.abs(dz) < 0.15f) return
        val target = if (kotlin.math.abs(dx) >= kotlin.math.abs(dz)) {
          if (dx >= 0f) NdsWalkSurfaceDirection.EAST else NdsWalkSurfaceDirection.WEST
        } else {
          if (dz >= 0f) NdsWalkSurfaceDirection.SOUTH else NdsWalkSurfaceDirection.NORTH
        }
        val reference = original.riseDirection.takeUnless { it == NdsWalkSurfaceDirection.FLAT }
            ?: NdsWalkSurfaceDirection.NORTH
        fun index(direction: NdsWalkSurfaceDirection): Int = when (direction) {
          NdsWalkSurfaceDirection.NORTH -> 0
          NdsWalkSurfaceDirection.EAST -> 1
          NdsWalkSurfaceDirection.SOUTH -> 2
          NdsWalkSurfaceDirection.WEST -> 3
          NdsWalkSurfaceDirection.FLAT -> 0
        }
        val turns = (index(target) - index(reference) + 4) % 4
        surface.minX = original.minX
        surface.maxX = original.maxX
        surface.minZ = original.minZ
        surface.maxZ = original.maxZ
        surface.copyHeightShapeFrom(original)
        surface.rotateQuarterTurns(turns, map.grid)
        status.text =
            "Rotating walk surface: ${turns * 90}°, " +
                "rises ${surface.riseDirection.name.lowercase()}"
      }
      NdsWalkTransformKind.SCALE -> {
        val start = ndsWalkTransformStartScreen ?: return
        val center = ndsWalkTransformCenterScreen ?: return
        val mx = hit.screenX?.toDouble() ?: return
        val my = hit.screenY?.toDouble() ?: return
        val startDistance = kotlin.math.hypot(start.first - center.first, start.second - center.second)
        if (startDistance < 2.0) return
        val currentDistance = kotlin.math.hypot(mx - center.first, my - center.second)
        val factor = (currentDistance / startDistance).coerceIn(0.05, 32.0)
        surface.minX = original.minX
        surface.maxX = original.maxX
        surface.minZ = original.minZ
        surface.maxZ = original.maxZ
        surface.scaleFootprint(factor, map.grid)
        status.text =
            "Scaling walk surface: ${surface.maxX - surface.minX}×" +
                "${surface.maxZ - surface.minZ} cells"
      }
      NdsWalkTransformKind.EDGE_HEIGHT -> {
        val edge = ndsWalkTransformEdge ?: return
        val start = ndsWalkTransformStartScreen ?: return
        val mx = hit.screenX ?: return
        val my = hit.screenY ?: return
        val axis = ndsWalkTransformScreenAxis
        val dx = (mx - start.first).toDouble()
        val dy = (my - start.second).toDouble()
        val amount = if (axis == null || axis.first * axis.first + axis.second * axis.second < 4.0) {
          -dy / 24.0
        } else {
          (dx * axis.first + dy * axis.second) /
              (axis.first * axis.first + axis.second * axis.second)
        }
        val originalEdgeHeight = original.heightAtEdge(edge)
        val newHeight = (kotlin.math.round((originalEdgeHeight + amount) * 4.0) / 4.0)
            .coerceIn(-32.0, 32.0)
        surface.copyHeightShapeFrom(original)
        surface.setEdgeHeight(edge, newHeight)
        status.text = if (surface.riseDirection == NdsWalkSurfaceDirection.FLAT) {
          "Walk surface is flat at ${formatNdsHeight(surface.lowHeight)}"
        } else {
          "Slope rises ${surface.riseDirection.name.lowercase()}: " +
              "${formatNdsHeight(surface.lowHeight)} to ${formatNdsHeight(surface.highHeight)}"
        }
      }
      null -> return
    }
    view()?.walkSurfaceTriangles = currentNdsWalkSurfaces()
    refreshNdsWalkSurfaceHandles()
  }

  private fun finishNdsWalkTransform(map: NdsMap) {
    val kind = ndsWalkTransformKind ?: return
    val before = ndsWalkTransformBefore ?: return
    val after = NdsSceneSnapshot.of(map)
    val changed = !before.sameAs(after)
    ndsHistory.recordScene(
        when (kind) {
          NdsWalkTransformKind.MOVE -> "move walk surface"
          NdsWalkTransformKind.MOVE_HEIGHT -> "move walk surface vertically"
          NdsWalkTransformKind.RESIZE -> "resize walk surface"
          NdsWalkTransformKind.ROTATE -> "rotate walk surface"
          NdsWalkTransformKind.SCALE -> "scale walk surface"
          NdsWalkTransformKind.EDGE_HEIGHT -> "adjust walk slope"
        },
        before,
        after,
    )
    clearNdsWalkTransform()
    refreshNdsWalkSurfaces()
    if (changed) {
      markDirty()
      status.text = when (kind) {
        NdsWalkTransformKind.MOVE -> "Moved walk surface (Ctrl+Z to undo)"
        NdsWalkTransformKind.MOVE_HEIGHT -> "Moved walk surface vertically (Ctrl+Z to undo)"
        NdsWalkTransformKind.RESIZE -> "Resized walk surface (Ctrl+Z to undo)"
        NdsWalkTransformKind.ROTATE -> "Rotated walk surface (Ctrl+Z to undo)"
        NdsWalkTransformKind.SCALE -> "Scaled walk surface (Ctrl+Z to undo)"
        NdsWalkTransformKind.EDGE_HEIGHT -> "Adjusted walk-surface slope (Ctrl+Z to undo)"
      }
    }
  }

  private fun deleteSelectedNdsWalkSurface() {
    val map = currentNdsMap ?: return
    val id = selectedNdsWalkSurfaceId ?: return
    val before = NdsSceneSnapshot.of(map)
    if (!map.walkSurfaces.removeAll { it.id == id }) return
    ndsHistory.recordScene("delete walk surface", before, NdsSceneSnapshot.of(map))
    selectedNdsWalkSurfaceId = null
    clearNdsWalkTransform()
    markDirty()
    refreshNdsWalkSurfaces()
    status.text = "Deleted walk surface (Ctrl+Z to undo)"
  }

  /** Handles the simple Flat/Slope drag tools and the geometry-driven From prop click tool. */
  private fun handleNdsWalkSurfaceInteraction(
      map: NdsMap,
      hit: NdsPointerHit,
      dragging: Boolean,
  ) {
    if (!map.isCustom) {
      if (!dragging) status.text = "ROM walk surfaces come from BDHC and are read-only"
      return
    }
    if (dragging && ndsWalkTransformKind != null) {
      applyNdsWalkTransform(map, hit)
      return
    }
    if (!dragging) {
      val selected = map.walkSurfaces.firstOrNull { it.id == selectedNdsWalkSurfaceId }
      if (!hit.ctrlDown && hit.shiftDown) {
        if (selected != null && hit.walkHandle in
            setOf(NdsWalkHandleRole.ROTATE, NdsWalkHandleRole.SCALE)) {
          status.text = "Drag the rotate/scale icon without Shift"
          return
        }
        if (selected != null && hit.walkHandle == NdsWalkHandleRole.CENTER) {
          clearNdsWalkPaintPreview()
          beginNdsWalkTransform(map, selected, hit, NdsWalkTransformKind.MOVE_HEIGHT)
          status.text = "Moving selected walk surface vertically; drag the center icon up or down"
          return
        }
        val edge = hit.walkHandle?.let(::walkDirection)
        if (selected != null && edge != null) {
          clearNdsWalkPaintPreview()
          beginNdsWalkTransform(map, selected, hit, NdsWalkTransformKind.EDGE_HEIGHT, edge)
          status.text =
              "Adjusting ${edge.name.lowercase()} edge; perpendicular edges reorient the slope"
        } else {
          selectNdsWalkSurface(hit.walkSurfaceId)
        }
        return
      }
      if (!hit.ctrlDown && selected != null && hit.walkHandle == NdsWalkHandleRole.ROTATE) {
        clearNdsWalkPaintPreview()
        beginNdsWalkTransform(
            map, selected, hit, NdsWalkTransformKind.ROTATE)
        status.text = "Rotating selected walk surface; drag around its center"
        return
      }
      if (!hit.ctrlDown && selected != null && hit.walkHandle == NdsWalkHandleRole.SCALE) {
        clearNdsWalkPaintPreview()
        beginNdsWalkTransform(
            map, selected, hit, NdsWalkTransformKind.SCALE)
        status.text = "Scaling selected walk surface; drag toward or away from its center"
        return
      }
      val resizeEdge = hit.walkHandle?.let(::walkDirection)
      if (!hit.ctrlDown && selected != null && resizeEdge != null) {
        clearNdsWalkPaintPreview()
        beginNdsWalkTransform(
            map, selected, hit, NdsWalkTransformKind.RESIZE, resizeEdge)
        status.text = "Resizing ${resizeEdge.name.lowercase()} edge; drag across the grid"
        return
      }
      if (!hit.ctrlDown && selected != null &&
          (hit.walkHandle == NdsWalkHandleRole.CENTER || hit.walkSurfaceId == selected.id)) {
        clearNdsWalkPaintPreview()
        beginNdsWalkTransform(map, selected, hit, NdsWalkTransformKind.MOVE)
        status.text = "Moving selected walk surface; drag across the grid"
        return
      }
      if (!hit.ctrlDown && selected != null) {
        // Selection is a distinct editing state. A miss clears it and consumes this gesture so a
        // slightly missed handle can never create a new plane underneath the one being adjusted.
        selectNdsWalkSurface(null)
        status.text = "Walk surface deselected; paint on the next click"
        return
      }

      val cell = walkPaintCell(hit) ?: return
      clearNdsWalkPaintPreview()
      ndsWalkSurfaceCheck.isSelected = true
      ndsWalkPaintBefore = NdsSceneSnapshot.of(map)
      ndsWalkPaintErasing = hit.ctrlDown
      ndsWalkPaintStart = cell
      ndsWalkPaintCurrent = cell
      if (ndsWalkPaintErasing) {
        eraseNdsWalkSurfaceAt(map, cell)
        status.text = "Erasing walk surfaces; release to finish"
        return
      }
      if (ndsWalkTool.selectedIndex == 2) {
        val group = hit.modelGroup
        val propId = group?.removePrefix("prop:")?.takeIf { group.startsWith("prop:") }
        val fitted = propId?.let { currentNdsHolder?.project?.walkSurfaceFromProp(map, it) }
        val before = ndsWalkPaintBefore
        clearNdsWalkPaintPreview()
        if (fitted == null || before == null) {
          status.text = "Click a stair prop with a clear cardinal rise; this shape could not be fitted"
          return
        }
        map.walkSurfaces.removeAll {
          it.minX == fitted.minX && it.minZ == fitted.minZ &&
              it.maxX == fitted.maxX && it.maxZ == fitted.maxZ
        }
        map.walkSurfaces += fitted
        selectedNdsWalkSurfaceId = fitted.id
        ndsHistory.recordScene("fit walk surface to prop", before, NdsSceneSnapshot.of(map))
        markDirty()
        refreshNdsWalkSurfaces()
        status.text = "Created a ${fitted.riseDirection.name.lowercase()}-rising walk surface from the prop"
        return
      }
      refreshNdsWalkPaintPreview()
      return
    }

    val cell = walkPaintCell(hit) ?: return
    if (ndsWalkPaintStart == null) return
    ndsWalkPaintCurrent = cell
    if (ndsWalkPaintErasing) eraseNdsWalkSurfaceAt(map, cell)
    else refreshNdsWalkPaintPreview()
  }

  /** Commits one walk-surface drag as a single undo step. */
  private fun finishNdsWalkSurfacePaint(hit: NdsPointerHit?) {
    if (ndsPaintMode.selectedIndex != 8) {
      if (ndsWalkPaintStart != null) clearNdsWalkPaintPreview()
      return
    }
    val map = currentNdsMap ?: return
    if (ndsWalkTransformKind != null) {
      finishNdsWalkTransform(map)
      return
    }
    val before = ndsWalkPaintBefore ?: return
    walkPaintCell(hit)?.let { ndsWalkPaintCurrent = it }
    if (ndsWalkPaintErasing) {
      val after = NdsSceneSnapshot.of(map)
      val changed = !before.sameAs(after)
      ndsHistory.recordScene("erase walk surface", before, after)
      clearNdsWalkPaintPreview()
      if (changed) {
        markDirty()
        status.text = "Erased walk surface(s) (Ctrl+Z to undo)"
      }
      return
    }
    if (ndsWalkTool.selectedIndex == 2) {
      clearNdsWalkPaintPreview()
      return
    }
    val surface = buildNdsWalkPaintSurface("walk-${java.util.UUID.randomUUID()}")
    if (surface == null) {
      val message = if (ndsWalkTool.selectedIndex == 1 &&
          (ndsWalkHighSpinner.value as Number).toDouble() <=
              (ndsHeightSpinner.value as Number).toDouble()) {
        "Slope High must be greater than Height"
      } else {
        "Drag a slope across at least two squares"
      }
      clearNdsWalkPaintPreview()
      status.text = message
      return
    }
    map.walkSurfaces.removeAll {
      it.minX == surface.minX && it.minZ == surface.minZ &&
          it.maxX == surface.maxX && it.maxZ == surface.maxZ
    }
    map.walkSurfaces += surface
    selectedNdsWalkSurfaceId = surface.id
    ndsHistory.recordScene("paint walk surface", before, NdsSceneSnapshot.of(map))
    clearNdsWalkPaintPreview()
    markDirty()
    refreshNdsWalkSurfaces()
    status.text = if (surface.riseDirection == NdsWalkSurfaceDirection.FLAT) {
      "Painted flat walk surface at height ${formatNdsHeight(surface.lowHeight)}"
    } else {
      "Painted ${surface.riseDirection.name.lowercase()} slope from " +
          "${formatNdsHeight(surface.lowHeight)} to ${formatNdsHeight(surface.highHeight)}"
    }
  }

  /** Coordinate-based authoring stays separate from tile, collision and permission paint modes. */
  private fun manageNdsWalkSurfaces() {
    val map = currentNdsMap
    if (map == null || !map.isCustom) {
      JOptionPane.showMessageDialog(
          this,
          "Walk surfaces can be authored only for custom maps. ROM maps already use their BDHC.",
          "Custom Walk Surfaces",
          JOptionPane.WARNING_MESSAGE,
      )
      return
    }

    val listModel = DefaultListModel<String>()
    val list = JList(listModel).also {
      it.selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
      it.visibleRowCount = 10
    }
    fun label(surface: NdsWalkSurface): String {
      val tiles = "X ${surface.minX}-${surface.maxX - 1}, Z ${surface.minZ}-${surface.maxZ - 1}"
      val height = if (surface.riseDirection == NdsWalkSurfaceDirection.FLAT) {
        "height ${formatNdsHeight(surface.lowHeight)}"
      } else {
        "${formatNdsHeight(surface.lowHeight)} to ${formatNdsHeight(surface.highHeight)}, " +
            "rises ${surface.riseDirection.name.lowercase()}"
      }
      return "$tiles — $height"
    }
    fun reload(selected: Int = list.selectedIndex) {
      listModel.clear()
      map.walkSurfaces.forEach { listModel.addElement(label(it)) }
      if (listModel.size > 0) list.selectedIndex = selected.coerceIn(0, listModel.size - 1)
    }

    val dialog = JDialog(this, "Custom Walk Surfaces", true)
    val add = JButton("Add...")
    val edit = JButton("Edit...")
    val remove = JButton("Remove")
    val close = JButton("Close")
    fun changed(message: String) {
      markDirty()
      ndsWalkSurfaceCheck.isSelected = true
      refreshNdsWalkSurfaces()
      status.text = "$message (Ctrl+Z to undo)"
    }
    add.addActionListener {
      val surface = editNdsWalkSurface(map, null) ?: return@addActionListener
      recordNdsSceneChange("add walk surface") { map.walkSurfaces += surface }
      reload(map.walkSurfaces.lastIndex)
      changed("Added custom walk surface")
    }
    edit.addActionListener {
      val index = list.selectedIndex
      val existing = map.walkSurfaces.getOrNull(index) ?: return@addActionListener
      val replacement = editNdsWalkSurface(map, existing) ?: return@addActionListener
      recordNdsSceneChange("edit walk surface") { map.walkSurfaces[index] = replacement }
      reload(index)
      changed("Updated custom walk surface")
    }
    remove.addActionListener {
      val index = list.selectedIndex
      if (index !in map.walkSurfaces.indices) return@addActionListener
      recordNdsSceneChange("remove walk surface") { map.walkSurfaces.removeAt(index) }
      reload(index)
      changed("Removed custom walk surface")
    }
    close.addActionListener { dialog.dispose() }
    list.addListSelectionListener {
      val selected = list.selectedIndex >= 0
      edit.isEnabled = selected
      remove.isEnabled = selected
    }
    reload()
    edit.isEnabled = list.selectedIndex >= 0
    remove.isEnabled = list.selectedIndex >= 0

    dialog.layout = BorderLayout(8, 8)
    dialog.add(
        JLabel(
            "Planes affect neither visible tiles nor painted collision; they describe walkable height."),
        BorderLayout.NORTH,
    )
    dialog.add(JScrollPane(list).also { it.preferredSize = Dimension(600, 230) }, BorderLayout.CENTER)
    dialog.add(JPanel(FlowLayout(FlowLayout.RIGHT)).also {
      it.add(add); it.add(edit); it.add(remove); it.add(close)
    }, BorderLayout.SOUTH)
    dialog.rootPane.defaultButton = close
    dialog.pack()
    dialog.setLocationRelativeTo(this)
    dialog.isVisible = true
  }

  /** Adds or edits one flat plane/cardinal slope using inclusive tile coordinates. */
  private fun editNdsWalkSurface(map: NdsMap, existing: NdsWalkSurface?): NdsWalkSurface? {
    val cursor = ndsCursorCell ?: (0 to 0)
    val startX = JTextField((existing?.minX ?: cursor.first).toString(), 6)
    val startZ = JTextField((existing?.minZ ?: cursor.second).toString(), 6)
    val endX = JTextField(((existing?.maxX?.minus(1)) ?: cursor.first).toString(), 6)
    val endZ = JTextField(((existing?.maxZ?.minus(1)) ?: cursor.second).toString(), 6)
    val directions = arrayOf("Flat", "Rises north", "Rises east", "Rises south", "Rises west")
    val directionValues = arrayOf(
        NdsWalkSurfaceDirection.FLAT,
        NdsWalkSurfaceDirection.NORTH,
        NdsWalkSurfaceDirection.EAST,
        NdsWalkSurfaceDirection.SOUTH,
        NdsWalkSurfaceDirection.WEST,
    )
    val direction = JComboBox(directions)
    direction.selectedIndex = directionValues.indexOf(existing?.riseDirection ?: NdsWalkSurfaceDirection.FLAT)
    val low = JSpinner(SpinnerNumberModel(existing?.lowHeight ?: 0.0, -32.0, 32.0, 0.25))
    val high = JSpinner(SpinnerNumberModel(existing?.highHeight ?: 1.0, -32.0, 32.0, 0.25))
    low.editor = JSpinner.NumberEditor(low, "0.####")
    high.editor = JSpinner.NumberEditor(high, "0.####")
    fun updateHeightFields() { high.isEnabled = direction.selectedIndex != 0 }
    direction.addActionListener { updateHeightFields() }
    updateHeightFields()

    val fields = JPanel(GridLayout(0, 2, 8, 4)).also {
      it.add(JLabel("Start X")); it.add(startX)
      it.add(JLabel("Start Z")); it.add(startZ)
      it.add(JLabel("End X (inclusive)")); it.add(endX)
      it.add(JLabel("End Z (inclusive)")); it.add(endZ)
      it.add(JLabel("Shape")); it.add(direction)
      it.add(JLabel("Low / flat height")); it.add(low)
      it.add(JLabel("High height")); it.add(high)
    }
    val panel = JPanel(BorderLayout(0, 8)).also {
      it.add(
          JLabel("Coordinates are local map tiles (X 0-${map.grid.cols - 1}, Z 0-${map.grid.rows - 1})."),
          BorderLayout.NORTH,
      )
      it.add(fields, BorderLayout.CENTER)
    }
    while (true) {
      val result = JOptionPane.showConfirmDialog(
          this,
          panel,
          if (existing == null) "Add Walk Surface" else "Edit Walk Surface",
          JOptionPane.OK_CANCEL_OPTION,
          JOptionPane.PLAIN_MESSAGE,
      )
      if (result != JOptionPane.OK_OPTION) return null
      val x0 = startX.text.trim().toIntOrNull()
      val z0 = startZ.text.trim().toIntOrNull()
      val x1Inclusive = endX.text.trim().toIntOrNull()
      val z1Inclusive = endZ.text.trim().toIntOrNull()
      if (x0 == null || z0 == null || x1Inclusive == null || z1Inclusive == null) {
        JOptionPane.showMessageDialog(
            this, "All coordinates must be whole numbers.", "Invalid Walk Surface",
            JOptionPane.ERROR_MESSAGE)
        continue
      }
      if (x0 !in 0 until map.grid.cols || z0 !in 0 until map.grid.rows ||
          x1Inclusive !in x0 until map.grid.cols || z1Inclusive !in z0 until map.grid.rows) {
        JOptionPane.showMessageDialog(
            this,
            "Start and end must describe a non-empty rectangle inside the map.",
            "Invalid Walk Surface",
            JOptionPane.ERROR_MESSAGE,
        )
        continue
      }
      val selectedDirection = directionValues[direction.selectedIndex]
      val lowHeight = (low.value as Number).toDouble()
      val highHeight = if (selectedDirection == NdsWalkSurfaceDirection.FLAT) {
        lowHeight
      } else {
        (high.value as Number).toDouble()
      }
      if (selectedDirection != NdsWalkSurfaceDirection.FLAT && highHeight <= lowHeight) {
        JOptionPane.showMessageDialog(
            this,
            "High height must be greater than low height for a slope.",
            "Invalid Walk Surface",
            JOptionPane.ERROR_MESSAGE,
        )
        continue
      }
      return NdsWalkSurface.cardinal(
          id = existing?.id ?: java.util.UUID.randomUUID().toString(),
          minX = x0,
          minZ = z0,
          maxX = x1Inclusive + 1,
          maxZ = z1Inclusive + 1,
          lowHeight = lowHeight,
          highHeight = highHeight,
          riseDirection = selectedDirection,
      )
    }
  }

  private fun formatNdsHeight(value: Double): String =
      java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

  private fun applyNdsVisibilityMode() {
    view()?.let {
      it.modelOpacity = if (ndsCollisionEditView.isSelected) 0.12f else 1f
      it.propOpacity = if (ndsShowOnlyTiles.isSelected) 0.35f else 1f
      it.showCollision = ndsCollisionCheck.isSelected
      it.asComponent().repaint()
    }
  }

  private fun updateNdsCursorCoordinates(cell: Pair<Int, Int>?) {
    ndsCursorCell = cell
    ndsCursorCoordinates.text = if (cell == null) {
      "Cursor: X —, Z —"
    } else {
      val (x, z) = cell
      "Cursor: X $x, Z $z  |  Matrix cell: X ${x / NdsGrid.COLS}, Z ${z / NdsGrid.ROWS}"
    }
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
    file.add(
        JMenuItem("Crop Custom Map by Coordinates…").apply {
          addActionListener { cropNdsCustomMap() }
        })
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

  /** Crops an editor-created DS map by inclusive local tile coordinates. */
  private fun cropNdsCustomMap() {
    val map = currentNdsMap
    val holder = currentNdsHolder
    val ref = currentNdsRef
    if (map == null || holder == null || ref == null) {
      JOptionPane.showMessageDialog(
          this, "Open a custom DS map first.", "Crop Custom Map", JOptionPane.WARNING_MESSAGE)
      return
    }
    if (!map.isCustom) {
      JOptionPane.showMessageDialog(
          this, "ROM maps cannot be resized. Duplicate or create a custom map first.",
          "Crop Custom Map", JOptionPane.WARNING_MESSAGE)
      return
    }
    if (holder.project.hasImportedModel(map)) {
      JOptionPane.showMessageDialog(
          this,
          "This map has an imported DS terrain model. Cropping would refit and distort that " +
              "model, so imported-terrain maps cannot be cropped yet.",
          "Crop Custom Map", JOptionPane.WARNING_MESSAGE)
      return
    }
    val oldWidth = map.grid.cols
    val oldHeight = map.grid.rows
    // Plain fields are intentional: constrained JSpinners rewrite an in-progress typed value when
    // another field changes its bounds, making coordinates appear to jump back to their defaults.
    val startX = JTextField("0", 6)
    val startZ = JTextField("0", 6)
    val endX = JTextField((oldWidth - 1).toString(), 6)
    val endZ = JTextField((oldHeight - 1).toString(), 6)
    val tileSummary = JLabel()

    fun updateBounds() {
      val x = startX.text.trim().toIntOrNull()
      val z = startZ.text.trim().toIntOrNull()
      val lastX = endX.text.trim().toIntOrNull()
      val lastZ = endZ.text.trim().toIntOrNull()
      if (x == null || z == null || lastX == null || lastZ == null || lastX < x || lastZ < z) {
        tileSummary.text = "Enter whole-number tile coordinates; each end must follow its start"
        return
      }
      val w = lastX - x + 1
      val h = lastZ - z + 1
      val paddedW = ((w + NdsGrid.COLS - 1) / NdsGrid.COLS) * NdsGrid.COLS
      val paddedH = ((h + NdsGrid.ROWS - 1) / NdsGrid.ROWS) * NdsGrid.ROWS
      tileSummary.text =
          "Keeps tiles X $x-$lastX, Z $z-$lastZ; " +
              "selection ${w}x${h}, game map ${paddedW}x${paddedH} tiles"
    }
    installSearch(startX) { updateBounds() }
    installSearch(startZ) { updateBounds() }
    installSearch(endX) { updateBounds() }
    installSearch(endZ) { updateBounds() }
    updateBounds()

    val fields = JPanel(GridLayout(0, 2, 8, 6)).also {
      it.add(JLabel("Current size"))
      it.add(JLabel("${oldWidth}x${oldHeight} tiles (X 0-${oldWidth - 1}, Z 0-${oldHeight - 1})"))
      it.add(JLabel("Start X"))
      it.add(startX)
      it.add(JLabel("Start Z"))
      it.add(startZ)
      it.add(JLabel("End X (inclusive)"))
      it.add(endX)
      it.add(JLabel("End Z (inclusive)"))
      it.add(endZ)
    }
    val panel = JPanel(BorderLayout(8, 8)).also {
      it.add(JLabel("Coordinates are local map tiles and include both endpoints."), BorderLayout.NORTH)
      it.add(fields, BorderLayout.CENTER)
      it.add(tileSummary, BorderLayout.SOUTH)
    }
    var chosen: IntArray? = null
    while (chosen == null) {
      if (JOptionPane.showConfirmDialog(
              this, panel, "Crop Custom Map by Coordinates",
              JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return
      val values = listOf(startX, startZ, endX, endZ).map { it.text.trim().toIntOrNull() }
      if (values.any { it == null }) {
        JOptionPane.showMessageDialog(
            this, "All coordinates must be whole numbers.", "Invalid crop coordinates",
            JOptionPane.ERROR_MESSAGE)
        continue
      }
      val entered = values.map { requireNotNull(it) }
      val candidate = intArrayOf(
          entered[0], entered[1], entered[2] - entered[0] + 1, entered[3] - entered[1] + 1)
      try {
        NdsMapCropper.impact(map, candidate[0], candidate[1], candidate[2], candidate[3])
        chosen = candidate
      } catch (t: Throwable) {
        JOptionPane.showMessageDialog(
            this, t.message ?: t.toString(), "Invalid crop coordinates", JOptionPane.ERROR_MESSAGE)
      }
    }

    val (x, z, w, h) = requireNotNull(chosen)
    if (x == 0 && z == 0 && w == oldWidth && h == oldHeight) {
      status.text = "Crop cancelled: those coordinates keep the entire map"
      return
    }

    val impact = NdsMapCropper.impact(map, x, z, w, h)
    val consequences = buildList {
      if (impact.propsRemoved > 0) add("${impact.propsRemoved} prop(s)")
      if (impact.objectsRemoved > 0) add("${impact.objectsRemoved} NPC/object event(s)")
      if (impact.warpsRemoved > 0) add("${impact.warpsRemoved} warp(s)")
      if (impact.triggersRemoved > 0) add("${impact.triggersRemoved} trigger(s)")
      if (impact.bgEventsRemoved > 0) add("${impact.bgEventsRemoved} background event(s)")
      if (impact.walkSurfacesRemoved > 0) add("${impact.walkSurfacesRemoved} walk surface(s)")
    }
    val warning = buildString {
      append("Crop ${map.displayName} to the selected ${w}x${h} tiles?\n\n")
      append("Everything retained will be shifted so tile ($x, $z) becomes (0, 0).")
      if (impact.outputWidth != w || impact.outputHeight != h) {
        append("\nThe resulting map will be padded with empty tiles to " +
            "${impact.outputWidth}x${impact.outputHeight}, a valid 32x32-cell footprint.")
      }
      if (consequences.isNotEmpty()) {
        append("\nThe crop will permanently remove: ")
        append(consequences.joinToString(", "))
        append('.')
      }
      if (impact.triggersClipped > 0) {
        append("\n${impact.triggersClipped} partially overlapping trigger(s) will be clipped.")
      }
      if (impact.walkSurfacesClipped > 0) {
        append("\n${impact.walkSurfacesClipped} partially overlapping walk surface(s) will be clipped " +
            "without changing their remaining slope.")
      }
      append("\n\nThis operation is saved immediately and cannot be undone.")
    }
    if (JOptionPane.showConfirmDialog(
            this, warning, "Confirm Map Crop", JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return

    try {
      NdsMapCropper.crop(map, x, z, w, h)
      holder.project.save(map)
      discardNdsNumericTransform()
      ndsHistory.clear()
      clearNdsSurfaceSelection()
      openNdsMap(ref)
      status.text =
          "Cropped ${map.displayName} to ${map.grid.cols}x${map.grid.rows} tiles"
    } catch (t: Throwable) {
      JOptionPane.showMessageDialog(
          this, t.message ?: t.toString(), "Crop failed", JOptionPane.ERROR_MESSAGE)
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
        val terrain = holder.project.trianglesFor(map)
        v.surfaceTriangles = terrain
        v.modelTriangles = terrain
        // An imported mesh no longer matches the ROM's invisible walk planes.
        v.walkSurfaceTriangles = emptyList()
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
      rebuildNdsPropCatalog()
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
    discardNdsNumericTransform()
    currentMap = map
    currentRef = ref
    currentNdsHolder = null
    currentNdsMap = null
    currentNdsRef = null
    undoStack.clear()
    redoStack.clear()
    snapshotUndoStack.clear()
    snapshotRedoStack.clear()
    ndsHistory.clear()
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
    discardNdsNumericTransform()
    currentNdsHolder = ref.holder
    currentNdsMap = map
    currentNdsRef = ref
    currentHolder = null
    currentMap = null
    currentRef = null
    // History describes squares and props of the map it was recorded on, so it cannot carry over.
    ndsHistory.clear()
    ndsDragSceneBefore = null
    ndsWalkPaintStart = null
    ndsWalkPaintCurrent = null
    ndsWalkPaintBefore = null
    ndsWalkPaintErasing = false
    ndsWalkPreview = null
    showMapCards(false)
    val v = view() ?: return
    v.grid = map.grid
    val tris = ref.holder.project.trianglesFor(map)
    val bld = ref.holder.project.buildingTrianglesFor(map)
    v.surfaceTriangles = tris
    v.modelTriangles = if (bld.isEmpty()) tris else tris + bld
    v.walkSurfaceTriangles = currentNdsWalkSurfaces()
    v.walkSurfaceHandles = emptyList()
    v.modelTextures = ref.holder.project.texturesFor(map)
    v.modelPalettes = ref.holder.project.palettesFor(map)
    refreshNdsCustomTiles(ref.holder.project.texturesFor(map), ref.holder.project.palettesFor(map))
    refreshNdsTileCombo()
    ndsHeaderPanel.setMap(map)
    ndsEventsPanel.setMap(map)
    selectedNdsPropIds.clear()
    selectedNdsPropId = null
    selectedNdsTerrainGroup = null
    // A surface selection names squares on the map it was picked from, so it cannot carry over.
    clearNdsSurfaceSelection()
    v.surfacePicking = ndsPaintMode.selectedIndex == 6
    rebuildNdsPropCatalog()
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
    for (prop in map.props.filter { it.id in selectedNdsPropIds }) {
      markers += NdsEventMarker(
          kotlin.math.floor(prop.x).toInt(),
          kotlin.math.floor(prop.z).toInt(),
          if (prop.id == selectedNdsPropId) "PROP" else "PROP+",
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
    // The DS editor keeps its own history; only one of the two maps is ever open.
    if (currentNdsMap != null) {
      undoNds()
      return
    }
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
    if (currentNdsMap != null) {
      redoNds()
      return
    }
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
      0 -> {
        if (!dragging) ndsTilePickGesture = hit.altDown
        if (ndsTilePickGesture) {
          if (!dragging) pickNdsPaintedTile(hit.cellX, hit.cellZ)
          true
        } else false
      }
      4 -> {
        if (!dragging) {
          // The drag that is about to start undoes as one step, measured from here.
          ndsDragSceneBefore = NdsSceneSnapshot.of(map)
          ndsHistory.beginStroke(ndsStrokeLabel())
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
          val clickedId = exactId ?: footprintProp?.id
          if (terrainSelection != null && !hit.ctrlDown) {
            selectNdsTerrainObject(terrainSelection.groupId)
          } else if (clickedId != null) {
            if (hit.ctrlDown) {
              val next = LinkedHashSet(selectedNdsPropIds)
              if (!next.add(clickedId)) next.remove(clickedId)
              selectNdsProps(next, clickedId.takeIf { it in next } ?: next.lastOrNull())
            } else {
              selectNdsProp(clickedId)
            }
          } else if (!hit.ctrlDown) {
            selectNdsProp(null)
          }
          val selected = map.props.firstOrNull { it.id == selectedNdsPropId }
          ndsPropDragStartPositions = map.props
              .filter { it.id in selectedNdsPropIds }
              .associate { it.id to (it.x to it.z) }
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
            val start = ndsPropDragStartPositions[prop.id] ?: (prop.x to prop.z)
            val targetX = snapNdsCoord(x + ndsPropDragOffsetX)
            val targetZ = snapNdsCoord(z + ndsPropDragOffsetZ)
            val dx = targetX - start.first
            val dz = targetZ - start.second
            for (selected in map.props.filter { it.id in selectedNdsPropIds }) {
              val initial = ndsPropDragStartPositions[selected.id] ?: continue
              selected.x = initial.first + dx
              selected.z = initial.second + dz
            }
            ndsPropsPanel.refreshProps(selectedNdsPropIds, selectedNdsPropId)
            markDirty()
            ndsDragSceneBefore?.let {
              ndsHistory.recordSceneDrag("move ${selectedNdsPropIds.size} prop(s)", it, NdsSceneSnapshot.of(map))
            }
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
                    snapNdsCoord(ndsTerrainDragInitialOffsetX + x - startX),
                    snapNdsCoord(ndsTerrainDragInitialOffsetZ + z - startZ),
                )) {
              markDirty()
              ndsDragSceneBefore?.let {
                ndsHistory.recordSceneDrag("move scenery", it, NdsSceneSnapshot.of(map))
              }
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
      6 -> {
        handleNdsSurfacePick(hit, dragging)
        true
      }
      8 -> {
        handleNdsWalkSurfaceInteraction(map, hit, dragging)
        true
      }
      else -> false
    }
  }

  // ---- Surface picking ------------------------------------------------------

  /**
   * Builds up a selection of individual map squares on the terrain mesh.
   *
   * Plain drag paints squares in; Shift+drag sweeps a box; holding Ctrl subtracts instead. The
   * tile comes from where the pointer met the *geometry* ([NdsPointerHit.surfaceX]/[surfaceZ]),
   * falling back to the ground-plane projection only when nothing was hit — on a map whose model
   * floats above the grid those two are several squares apart, and the mesh position is the one
   * that matches what the user sees under the cursor.
   */
  private fun handleNdsSurfacePick(hit: NdsPointerHit, dragging: Boolean) {
    if (currentNdsMap == null || currentNdsHolder == null) return
    val x = hit.surfaceX ?: hit.groundX ?: hit.cellX?.plus(0.5f) ?: return
    val z = hit.surfaceZ ?: hit.groundZ ?: hit.cellZ?.plus(0.5f) ?: return

    if (!dragging) {
      ndsSurfaceErasing = hit.ctrlDown
      // A new stroke starts its trail here rather than continuing from wherever the last one
      // ended, which would sweep in everything between the two.
      ndsSurfaceLastX = null
      ndsSurfaceLastZ = null
      ndsSurfaceLastY = null
      // A fresh click on bare geometry re-arms the texture filter from whatever was clicked.
      if (!hit.ctrlDown && hit.surfaceTexture != null && ndsSurfaceCells.isEmpty()) {
        ndsSurfaceTextureFilter = hit.surfaceTexture.takeIf { it.isNotEmpty() }
      }
      if (hit.shiftDown) {
        ndsSurfaceBoxAnchorX = x
        ndsSurfaceBoxAnchorZ = z
        ndsSurfaceBoxBaseCells = LinkedHashSet(ndsSurfaceCells)
      } else {
        ndsSurfaceBoxAnchorX = null
        ndsSurfaceBoxAnchorZ = null
      }
    }

    val anchorX = ndsSurfaceBoxAnchorX
    val anchorZ = ndsSurfaceBoxAnchorZ
    if (anchorX != null && anchorZ != null) {
      // Recompute the whole box every drag event so shrinking it back releases squares again.
      val box = NdsProject.surfaceRectCells(anchorX, anchorZ, x, z)
      val rebuilt = LinkedHashSet(ndsSurfaceBoxBaseCells)
      if (ndsSurfaceErasing) rebuilt -= box else rebuilt += box
      ndsSurfaceCells.clear()
      ndsSurfaceCells += rebuilt
      // A box sweep only knows the height under the pointer, so every square it adds takes that
      // one. It is the surface the user was tracing along, which is what they mean by the sweep.
      // Squares already in the selection keep the surface they were picked with; see below.
      if (!ndsSurfaceErasing) {
        hit.surfaceY?.let { y -> for (cell in box) ndsSurfacePickedHeights.putIfAbsent(cell, y) }
      }
    } else {
      // Take what the pointer crossed, not only where it was sampled: samples are sparse
      // because each one ray-tests the whole model, so marking just them left a dotted trail
      // of squares with holes between them -- impossible to select a cliff with.
      val size = (ndsSurfaceBrushSpinner.value as Number).toInt()
      val trail = NdsProject.surfaceStrokeCells(
          ndsSurfaceLastX ?: x, ndsSurfaceLastZ ?: z, x, z, size)
      if (ndsSurfaceErasing) {
        ndsSurfaceCells -= trail.keys
        ndsSurfacePickedHeights.keys -= trail.keys
      } else {
        ndsSurfaceCells += trail.keys
        val toY = hit.surfaceY
        if (toY != null) {
          // Height follows the trail as well, so dragging up a slope keeps tracking it.
          //
          // First pick wins, and that matters: this height decides which of the surfaces
          // stacked over a square gets rebuilt. Overwriting it meant running the cursor back
          // over a square moved it to whatever was under the pointer this time, so tracing the
          // top of a cliff and then its base swapped the tops for the ground rather than
          // adding to them -- the selection kept losing what had just been picked, and the
          // whole structure could never be held at once. Ctrl+drag releases a square to pick
          // it again on a different surface.
          val fromY = ndsSurfaceLastY ?: toY
          for ((cell, t) in trail) {
            ndsSurfacePickedHeights.putIfAbsent(cell, fromY + (toY - fromY) * t)
          }
        }
      }
    }
    ndsSurfaceLastX = x
    ndsSurfaceLastZ = z
    hit.surfaceY?.let { ndsSurfaceLastY = it }

    ndsSurfacePickedHeights.keys.retainAll(ndsSurfaceCells)
    if (ndsSurfaceCells.isEmpty()) ndsSurfaceTextureFilter = null
    refreshNdsSurfaceHighlight()
    val triangles = ndsSurfaceHighlightTriangles().size
    val filter = ndsSurfaceTextureFilter
    status.text = if (ndsSurfaceCells.isEmpty()) {
      "Surface selection cleared"
    } else {
      "Selected ${ndsSurfaceCells.size} square(s), $triangles triangle(s)" +
          (if (filter != null && ndsSurfaceSameTexture.isSelected) " of texture '$filter'" else "") +
          " — add it as a tile or save it as a prop"
    }
  }

  /** The currently selected terrain triangles, as drawn in the viewport. */
  private fun ndsSurfaceHighlightTriangles(): List<de.lananahwp.openmmo.mapeditor.core.NdsTri> {
    if (ndsSurfaceCells.isEmpty()) return emptyList()
    val map = currentNdsMap ?: return emptyList()
    val project = currentNdsHolder?.project ?: return emptyList()
    return project.surfaceTriangles(
        map, ndsSurfaceCells, ndsSurfaceTextureFilterOrNull(), ndsSurfaceCutMode(),
        ndsSurfacePickedHeights, ndsSurfaceIncludeWalls.isSelected)
  }

  private fun ndsSurfaceTextureFilterOrNull(): String? =
      if (ndsSurfaceSameTexture.isSelected) ndsSurfaceTextureFilter else null

  private fun ndsSurfaceCutMode(): NdsProject.SurfaceCut =
      if (ndsSurfaceCut.selectedIndex == 1) NdsProject.SurfaceCut.FREEFORM
      else NdsProject.SurfaceCut.SQUARES

  private fun refreshNdsSurfaceHighlight() {
    val v = ndsView ?: return
    v.highlightTriangles = ndsSurfaceHighlightTriangles()
    v.asComponent().repaint()
  }

  private fun clearNdsSurfaceSelection() {
    ndsSurfaceCells.clear()
    ndsSurfacePickedHeights.clear()
    ndsSurfaceTextureFilter = null
    ndsSurfaceBoxAnchorX = null
    ndsSurfaceBoxAnchorZ = null
    ndsSurfaceBoxBaseCells = emptySet()
    ndsSurfaceErasing = false
    ndsSurfaceLastX = null
    ndsSurfaceLastZ = null
    ndsSurfaceLastY = null
    refreshNdsSurfaceHighlight()
  }

  /** Enables the per-mode extras and drops selection state that no longer applies. */
  private fun onNdsPaintModeChanged() {
    val surfaceMode = ndsPaintMode.selectedIndex == 6
    val walkMode = ndsPaintMode.selectedIndex == 8
    // The first four modes are the ones that write a square at a time.
    ndsTileBrushSpinner.isEnabled =
        ndsPaintMode.selectedIndex in 0..3 || ndsPaintMode.selectedIndex == 7
    ndsSurfaceBrushSpinner.isEnabled = surfaceMode
    ndsSurfaceSameTexture.isEnabled = surfaceMode
    ndsSurfaceIncludeWalls.isEnabled = surfaceMode
    ndsSurfaceCut.isEnabled = surfaceMode
    ndsSurfaceSaveButton.isEnabled = surfaceMode
    ndsSurfaceAddTileButton.isEnabled = surfaceMode
    ndsSurfaceClearButton.isEnabled = surfaceMode
    // Only this mode needs the pointer resolved against the mesh on every drag event. Read the
    // field rather than view(), which would build the GL canvas just to set a flag.
    ndsView?.surfacePicking = surfaceMode
    if (!surfaceMode && ndsSurfaceCells.isNotEmpty()) clearNdsSurfaceSelection()
    if (!walkMode) {
      if (ndsWalkPaintStart != null) clearNdsWalkPaintPreview()
      selectedNdsWalkSurfaceId = null
      clearNdsWalkTransform()
      ndsView?.walkSurfaceHandles = emptyList()
    }
    if (walkMode && currentNdsMap?.isCustom == true) {
      ndsWalkSurfaceCheck.isSelected = true
      refreshNdsWalkSurfaces()
    }
    updateNdsWalkToolControls()
    if (surfaceMode) {
      status.text =
          "Pick Surface: click the map squares you want, then add them as a tile or prop " +
              "(drag paints, Shift+drag boxes, Ctrl removes)"
    } else if (ndsPaintMode.selectedIndex == 0) {
      status.text =
          "Tile: drag paints, Alt+click picks a painted tile, Shift+drag uses clear footprints, " +
              "Ctrl+drag clears, " +
              "Brush sets how many squares wide · Ctrl+Z undoes a stroke"
    } else if (ndsPaintMode.selectedIndex == 3) {
      status.text =
          "Height: drag raises squares to the set height, Ctrl+drag returns them to 0 " +
              "· Ctrl+Z undoes a stroke"
    } else if (ndsPaintMode.selectedIndex == 7) {
      status.text =
          "Paint Grass: drag to grow a connected HGSS field; edges are automatic; " +
              "Ctrl+drag removes grass"
    } else if (walkMode) {
      status.text = when (ndsWalkTool.selectedIndex) {
        0 -> "Walk Surface / Flat: drag an area; Shift-click cyan to select; Ctrl+drag erases"
        1 -> "Walk Surface / Slope: drag low to high; Shift-click cyan to select and edit"
        else -> "Walk Surface / From prop: click stairs; Shift-click cyan to select and edit"
      }
    }
  }

  /**
   * Rebuilds the Tile combo from the built-ins plus both open ROM projects by default.
   *
   * A foreign tile is only a catalog choice here. [selectNdsTileChoice] copies it into the active
   * project before painting because the map grid persists an id, not a source-project path.
   */
  private fun refreshNdsTileCombo(preferredId: Int? = null) {
    val active = currentNdsHolder?.project
    val previous = ndsTileChoices.getOrNull(ndsTileCombo.selectedIndex)
    val projects = if (ndsActiveRomTilesOnly.isSelected) {
      listOfNotNull(active)
    } else {
      ndsHolders.values.map { it.project }
          .sortedBy { if (it === active) 0 else 1 }
    }
    val builtIns = NdsTileset.tiles.mapIndexed { index, tile ->
      NdsTileChoice(index, "${tile.name} (#$index)", null)
    }
    val custom = projects.flatMap { project ->
      val familyTag = if (project.family == de.lananahwp.openmmo.mapeditor.core.NdsFamily.PLATINUM)
        "Pt" else "HGSS"
      project.customTileStore.tiles().filterNot { it.hidden }.map { tile ->
        val footprint = if (tile.width == 1 && tile.height == 1) "" else " (${tile.width}x${tile.height})"
        val kind = if (tile.overlay) " [Overlay]" else ""
        NdsTileChoice(
            tile.index,
            "[$familyTag] ${tile.name}$footprint$kind (#${tile.index})",
            project,
        )
      }
    }
    ndsTileChoices = builtIns + custom
    refreshingNdsTileCombo = true
    try {
      ndsTileCombo.model = javax.swing.DefaultComboBoxModel(
          ndsTileChoices.map { it.label }.toTypedArray())
      val wanted = preferredId?.let { id ->
        ndsTileChoices.indexOfFirst {
          it.index == id && (it.project == null || it.project === active)
        }
      }?.takeIf { it >= 0 } ?: previous?.takeIf { old ->
        old.project == null || old.project === active
      }?.let { old ->
        ndsTileChoices.indexOfFirst { it.index == old.index && it.project === old.project }
      }?.takeIf { it >= 0 } ?: 0
      ndsTileCombo.selectedIndex = wanted
      val selected = ndsTileChoices.getOrNull(wanted)
      if (selected?.project == null || selected.project === active) {
        setNdsActiveTile(selected?.index ?: 0)
      }
    } finally {
      refreshingNdsTileCombo = false
    }
  }

  /** Resolves a catalog row to an id owned by the active project. */
  private fun selectNdsTileChoice() {
    if (refreshingNdsTileCombo) return
    val choice = ndsTileChoices.getOrNull(ndsTileCombo.selectedIndex) ?: return
    // Paint Grass deliberately ignores the Tile combo and writes its internal field marker.
    // Choosing a tile is therefore an explicit request to go back to ordinary tile painting.
    if (ndsPaintMode.selectedIndex == 7 || ndsPaintMode.selectedIndex == 8) {
      ndsPaintMode.selectedIndex = 0
    }
    val active = currentNdsHolder?.project
    if (choice.project == null || active == null || choice.project === active) {
      setNdsActiveTile(choice.index)
      return
    }
    val sourceProject = requireNotNull(choice.project)
    val sourceTile = sourceProject.customTileStore.tiles().firstOrNull { it.index == choice.index }
        ?: return
    val sourceKey = "${sourceProject.family.name}:${sourceTile.index}"
    val existing = active.customTileStore.tiles().firstOrNull { it.source == sourceKey }
    val local = existing ?: sourceProject.customTileStore.mesh(sourceTile.index)?.let { snapshot ->
      active.customTileStore.add(
          sourceTile.name, snapshot, sourceKey, sourceTile.width, sourceTile.height,
          sourceTile.overlay)
    }
    if (local == null) {
      status.text = "Could not load '${sourceTile.name}' from ${sourceProject.family.displayName}"
      return
    }
    val map = currentNdsMap
    if (map != null) {
      refreshNdsCustomTiles(active.texturesFor(map), active.palettesFor(map))
    }
    refreshNdsTileCombo(local.index)
    setNdsActiveTile(local.index)
    status.text = if (existing == null) {
      "Copied '${sourceTile.name}' into ${active.family.displayName} tiles"
    } else {
      "Selected '${sourceTile.name}' from the local ${active.family.displayName} copy"
    }
  }

  private fun setNdsActiveTile(index: Int) {
    val v = ndsView ?: return
    val tile = currentNdsHolder?.project?.customTileStore?.tiles()?.firstOrNull { it.index == index }
    v.activeTile = index
    v.activeTileWidth = tile?.width ?: 1
    v.activeTileHeight = tile?.height ?: 1
    v.asComponent().repaint()
  }

  /** Alt+click eyedropper for built-in and project-local painted tiles. */
  private fun pickNdsPaintedTile(x: Int?, z: Int?) {
    val map = currentNdsMap ?: return
    val project = currentNdsHolder?.project ?: return
    if (x == null || z == null) return
    val footprints = project.customTileStore.tiles()
        .associate { it.index to (it.width to it.height) }
    val hit = ndsPlacedTileAt(map.grid, x, z, footprints)
    if (hit == null) {
      status.text = "No painted tile found on that square"
      return
    }
    refreshNdsTileCombo(hit.tile)
    val label = ndsTileChoices.getOrNull(ndsTileCombo.selectedIndex)?.label ?: "tile #${hit.tile}"
    status.text = "Picked $label from layer ${hit.layer}"
  }

  /**
   * Hands the view this project's tile geometry, and merges the textures it needs into the ones
   * already loaded for the map.
   *
   * Tile textures are namespaced per tile, the same way catalog props are, so a tile lifted from
   * one map cannot be repainted by a same-named texture belonging to another.
   */
  private fun refreshNdsCustomTiles(mapTextures: Map<String, de.lananahwp.openmmo.mapeditor.core.NdsTexture>,
                                    mapPalettes: Map<String, IntArray>) {
    val store = currentNdsHolder?.project?.customTileStore ?: return
    val v = ndsView ?: return
    val textures = LinkedHashMap(mapTextures)
    val palettes = LinkedHashMap(mapPalettes)
    for (tile in store.tiles()) {
      val mesh = store.mesh(tile.index) ?: continue
      val prefix = store.texturePrefix(tile.index)
      for ((name, tex) in mesh.textures) textures[prefix + name] = tex
      for ((name, pal) in mesh.palettes) palettes[prefix + name] = pal
    }
    v.modelTextures = textures
    v.modelPalettes = palettes
    v.customTileGeometry = store.viewGeometry()
    v.customTileOverlays = store.tiles().filter { it.overlay }.map { it.index }.toSet()
  }

  private fun showNdsAssetCleanup() {
    if (ndsHolders.isEmpty()) {
      JOptionPane.showMessageDialog(
          this, "Open a HeartGold/SoulSilver or Platinum project first.",
          "Clear Assets", JOptionPane.INFORMATION_MESSAGE)
      return
    }
    NdsAssetCleanupDialog(
        this,
        loadEntries = ::ndsAssetCleanupEntries,
        canUndo = { ndsAssetCleanupUndo.isNotEmpty() },
        deleteEntries = ::deleteNdsCleanupEntries,
        undoLastDelete = ::undoLastNdsAssetCleanup,
    ).isVisible = true
  }

  private fun ndsAssetCleanupEntries(): List<NdsCleanupAssetEntry> = buildList {
    for (project in ndsHolders.values.map { it.project }.distinct()) {
      val familyTag = if (project.family == de.lananahwp.openmmo.mapeditor.core.NdsFamily.PLATINUM)
        "Pt" else "HGSS"
      val candidates = project.unusedCustomAssets()
      for (prop in candidates.extractedProps) {
        add(NdsCleanupAssetEntry(
            project, NdsCleanupAssetKind.PROP, prop.key,
            "[$familyTag] ${prop.label} (${prop.key})"))
      }
      for (tile in candidates.tiles) {
        val details = buildList {
          if (tile.width != 1 || tile.height != 1) add("${tile.width}x${tile.height}")
          if (tile.overlay) add("overlay")
          if (tile.hidden) add("hidden")
        }.joinToString(", ").let { if (it.isEmpty()) "" else " — $it" }
        add(NdsCleanupAssetEntry(
            project, NdsCleanupAssetKind.TILE, tile.index.toString(),
            "[$familyTag] ${tile.name} (#${tile.index})$details"))
      }
    }
  }.sortedWith(compareBy<NdsCleanupAssetEntry>({ it.kind }, { it.label.lowercase() }))

  private fun deleteNdsCleanupEntries(entries: List<NdsCleanupAssetEntry>) {
    val completed = mutableListOf<Pair<NdsProject, NdsProject.AssetCleanupUndo>>()
    try {
      for ((project, selected) in entries.groupBy { it.project }) {
        val props = selected.filter { it.kind == NdsCleanupAssetKind.PROP }.map { it.key }.toSet()
        val tiles = selected.filter { it.kind == NdsCleanupAssetKind.TILE }
            .map { it.key.toInt() }.toSet()
        completed += project to project.deleteUnusedAssets(props, tiles)
      }
    } catch (failure: Throwable) {
      for ((project, undo) in completed.asReversed()) {
        runCatching { project.undoAssetCleanup(undo) }
            .onFailure(failure::addSuppressed)
      }
      throw failure
    }
    ndsAssetCleanupUndo.addLast(completed)
    refreshNdsAssetCatalogs()
  }

  private fun undoLastNdsAssetCleanup() {
    val batch = ndsAssetCleanupUndo.peekLast() ?: return
    for ((project, undo) in batch.asReversed()) project.undoAssetCleanup(undo)
    ndsAssetCleanupUndo.removeLast()
    refreshNdsAssetCatalogs()
  }

  private fun refreshNdsAssetCatalogs() {
    val map = currentNdsMap
    val holder = currentNdsHolder
    if (map != null && holder != null) {
      refreshNdsCustomTiles(holder.project.texturesFor(map), holder.project.palettesFor(map))
    }
    refreshNdsTileCombo()
    rebuildNdsPropCatalog()
    ndsView?.asComponent()?.repaint()
  }

  /** Adds a rectangular set of picked squares as one anchored paintable tile stamp. */
  private fun addNdsSurfaceSelectionAsTile() {
    val map = currentNdsMap
    val holder = currentNdsHolder
    if (map == null || holder == null || ndsSurfaceCells.isEmpty()) {
      JOptionPane.showMessageDialog(
          this,
          "Pick one or more map squares first.",
          "Add as Tile",
          JOptionPane.WARNING_MESSAGE,
      )
      return
    }
    val selectedX = ndsSurfaceCells.map(NdsProject::surfaceCellX)
    val selectedZ = ndsSurfaceCells.map(NdsProject::surfaceCellZ)
    val minX = selectedX.min()
    val maxX = selectedX.max()
    val minZ = selectedZ.min()
    val maxZ = selectedZ.max()
    val tileWidth = maxX - minX + 1
    val tileHeight = maxZ - minZ + 1
    if (ndsSurfaceCells.size != tileWidth * tileHeight) {
      JOptionPane.showMessageDialog(
          this,
          "A multi-square tile must be a complete rectangle without gaps. " +
              "The current selection has ${ndsSurfaceCells.size} of ${tileWidth * tileHeight} squares.",
          "Add as Tile",
          JOptionPane.WARNING_MESSAGE,
      )
      return
    }
    // Tile space, not prop space: a tile is painted into a square, so its geometry has to sit
    // where it was cut relative to that square rather than being recentred on itself.
    val snapshot = holder.project.buildSurfaceExtraction(
        map, ndsSurfaceCells, ndsSurfaceTextureFilterOrNull(), ndsSurfaceCutMode(),
        ndsSurfacePickedHeights, ndsSurfaceIncludeWalls.isSelected,
        NdsProject.SurfaceOrigin.CELL)
    if (snapshot == null) {
      JOptionPane.showMessageDialog(
          this,
          "That selection has no geometry on it. Try turning off \"Same texture only\", or pick " +
              "squares with visible terrain.",
          "Add as Tile",
          JOptionPane.WARNING_MESSAGE,
      )
      return
    }
    val nameField = JTextField("${map.displayName} tile", 28)
    val codeField = JTextField(holder.project.customTileStore.nextAvailableIndex().toString(), 8)
    val overlay = JCheckBox("Overlay tile (preserve and show the tile underneath)")
    overlay.toolTipText =
        "For transparent grass edges, rocks, shadows, and other surface details"
    val fields = JPanel(BorderLayout(0, 8)).apply {
      add(JPanel(GridLayout(0, 2, 8, 8)).apply {
        add(JLabel("Tile name"))
        add(nameField)
        add(JLabel("Tile code (${NdsTileset.CUSTOM_TILE_BASE} or higher)"))
        add(codeField)
      }, BorderLayout.NORTH)
      add(overlay, BorderLayout.SOUTH)
    }
    while (true) {
      val accepted = JOptionPane.showConfirmDialog(
          this, fields, "Add as Tile", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
      if (accepted != JOptionPane.OK_OPTION) return
      val name = nameField.text.trim()
      val code = codeField.text.trim().toIntOrNull()
      val validationError = when {
        name.isEmpty() -> "Enter a tile name."
        code == null -> "Tile code must be a whole number."
        code < NdsTileset.CUSTOM_TILE_BASE ->
          "Custom tile code must be ${NdsTileset.CUSTOM_TILE_BASE} or higher."
        holder.project.customTileStore.tiles().any { it.index == code } ->
          "Tile code $code is already taken."
        else -> null
      }
      if (validationError != null) {
        JOptionPane.showMessageDialog(
            this, validationError, "Invalid tile code", JOptionPane.WARNING_MESSAGE)
        continue
      }
      try {
        val tile = holder.project.customTileStore.add(
            name, snapshot, width = tileWidth, height = tileHeight,
            overlay = overlay.isSelected, requestedIndex = code)
        refreshNdsCustomTiles(holder.project.texturesFor(map), holder.project.palettesFor(map))
        refreshNdsTileCombo(tile.index)
        status.text =
            "Added ${tile.width}x${tile.height} tile '${tile.name}' as code ${tile.index}; " +
                "it is selected, and Pick Surface mode remains active"
        return
      } catch (t: Throwable) {
        JOptionPane.showMessageDialog(
            this, t.message ?: t.toString(), "Add as Tile failed", JOptionPane.ERROR_MESSAGE)
      }
    }
  }

  /** Bakes the picked squares into a reusable catalog prop. */
  private fun saveNdsSurfaceSelectionAsProp() {
    val map = currentNdsMap
    val holder = currentNdsHolder
    if (map == null || holder == null || ndsSurfaceCells.isEmpty()) {
      JOptionPane.showMessageDialog(
          this,
          "Pick some map squares first: choose \"Pick Surface -> Prop\" mode, then click the " +
              "part of the map you want to copy.",
          "Save Selection as Prop",
          JOptionPane.WARNING_MESSAGE,
      )
      return
    }
    val snapshot = holder.project.buildSurfaceExtraction(
        map, ndsSurfaceCells, ndsSurfaceTextureFilterOrNull(), ndsSurfaceCutMode(),
        ndsSurfacePickedHeights, ndsSurfaceIncludeWalls.isSelected)
    if (snapshot == null) {
      JOptionPane.showMessageDialog(
          this,
          "Those squares contain no geometry. Try turning off \"Same texture only\", or pick a " +
              "square that has visible terrain on it.",
          "Save Selection as Prop",
          JOptionPane.WARNING_MESSAGE,
      )
      return
    }
    val suggested = "${map.displayName} surface"
    val label = JOptionPane.showInputDialog(
        this, "Name for the catalog entry", "Save Selection as Prop",
        JOptionPane.PLAIN_MESSAGE, null, null, suggested)?.toString()?.trim()
    if (label.isNullOrEmpty()) return
    try {
      val saved = holder.project.saveExtractedProp(label, snapshot, map.name)
      rebuildNdsPropCatalog()
      ndsPropsPanel.selectModel(saved.key)
      status.text =
          "Saved '${saved.label}' (${snapshot.triangles.size} triangles) to the prop catalog — " +
              "open any map and use Place at center"
    } catch (t: Throwable) {
      JOptionPane.showMessageDialog(
          this, t.message ?: t.toString(), "Save Selection as Prop failed", JOptionPane.ERROR_MESSAGE)
    }
  }

  private fun removeNdsSceneryObjectAt(hit: NdsPointerHit) {
    recordNdsSceneChange("remove scenery") {
      val map = currentNdsMap ?: return
      val holder = currentNdsHolder ?: return
      val propId = hit.modelGroup
          ?.takeIf { it.startsWith("prop:") }
          ?.removePrefix("prop:")
      if (propId != null) {
        val removal = holder.project.removePropObject(
            map, propId, ndsClearCollisionWithTerrain.isSelected) ?: return
        selectedNdsPropIds.remove(propId)
        selectedNdsPropId = selectedNdsPropIds.lastOrNull()
        selectedNdsTerrainGroup = null
        ndsPropsPanel.refreshProps(selectedNdsPropIds, selectedNdsPropId)
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
  }

  private fun restoreLastNdsTerrainObject() {
    recordNdsSceneChange("restore scenery") {
      val map = currentNdsMap ?: return
      val holder = currentNdsHolder ?: return
      val restored = holder.project.restoreLastTerrainObject(map)
      if (restored == null) {
        status.text = "There are no removed scenery objects to restore"
        return
      }
      restored.removedProp?.let { prop ->
        selectedNdsPropIds.clear(); selectedNdsPropIds += prop.id
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
  }

  private fun previewNdsProp(info: NdsProject.PropModelInfo): NdsProject.PropModelPreview {
    val current = currentNdsHolder?.project
    ndsExternalPropSources[info.catalogId]?.let { source ->
      return source.propModelPreview(info.sourceModelKey, null)
    }
    if (current?.customPropModels()?.any { it.key == info.key } == true) {
      return current.propModelPreview(info.key, currentNdsMap)
    }
    val foreign = info.sourceFamily?.takeIf { current != null && it != current.family }
    return if (foreign == null) {
      current?.propModelPreview(info.sourceModelKey, currentNdsMap)
          ?: NdsProject.PropModelPreview(emptyList(), emptyMap(), emptyMap())
    } else {
      // A loaded library is authoritative and can replace an older project-local snapshot.
      // Without it, keep already-installed foreign props usable while Show all is off.
      ndsPropLibraries[foreign]?.preview(info)
          ?: current?.propModelPreview(info.key, currentNdsMap)
          ?: NdsProject.PropModelPreview(emptyList(), emptyMap(), emptyMap())
    }
  }

  private fun rebuildNdsPropCatalog() {
    val current = currentNdsHolder?.project ?: return
    ndsExternalPropSources.clear()
    val models = current.propModels().toMutableList()
    if (showAllNdsProps) {
      val installedProjectProps = current.customPropModels()
          .mapNotNull { model ->
            val family = model.sourceFamily ?: return@mapNotNull null
            family to model.sourceModelKey
          }
          .toSet()
      for (source in ndsHolders.values.map { it.project }.distinct().filter { it !== current }) {
        for (model in source.transferableCustomPropModels()) {
          if ((source.family to model.key) in installedProjectProps) continue
          val catalogId = "project:${source.family.name}:${source.rootDir.canonicalPath}:${model.key}"
          val external = model.copy(
              catalogId = catalogId,
              sourceFamily = source.family,
              sourceModelKey = model.key,
          )
          models += external
          ndsExternalPropSources[catalogId] = source
        }
      }
      val otherFamily = de.lananahwp.openmmo.mapeditor.core.NdsFamily.entries
          .firstOrNull { it != current.family }
      val source = ndsHolders.values.map { it.project }
          .firstOrNull { it.family == otherFamily }
      if (otherFamily != null && otherFamily !in ndsPropLibraries && source == null) {
        NdsPropLibrary.loadCached(otherFamily)?.let { ndsPropLibraries[otherFamily] = it }
      }
      models += ndsPropLibraries.values
          .filter { it.family != current.family }
          .flatMap { it.models }
      if (source != null && source.family !in ndsPropLibraries &&
          ndsPropLibrariesLoading.add(source.family)) {
        status.text = "Building ${source.family.displayName} prop cache in the background..."
        object : SwingWorker<NdsPropLibrary, Unit>() {
          override fun doInBackground(): NdsPropLibrary = NdsPropLibrary.loadOrBuild(source.rootDir)
          override fun done() {
            ndsPropLibrariesLoading.remove(source.family)
            try {
              val library = get()
              ndsPropLibraries[library.family] = library
              rebuildNdsPropCatalog()
              status.text = "Loaded ${library.models.size} cached ${library.family.displayName} props"
            } catch (t: Throwable) {
              status.text = "Could not build ${source.family.displayName} prop cache"
              JOptionPane.showMessageDialog(
                  this@EditorFrame, t.cause?.message ?: t.message ?: t.toString(),
                  "NDS prop cache failed", JOptionPane.WARNING_MESSAGE)
            }
          }
        }.execute()
      } else if (source == null && otherFamily !in ndsPropLibraries) {
        status.text = "Open the other NDS decomp once to build its prop cache"
      }
    }
    ndsPropsPanel.setModels(models.distinctBy { it.catalogId })
  }

  private fun beginNdsPropPlacement(info: NdsProject.PropModelInfo) {
    recordNdsSceneChange("place prop") {
      val map = currentNdsMap ?: return
      val holder = currentNdsHolder ?: return
      try {
        val sourceProject = ndsExternalPropSources[info.catalogId]
        val foreign = info.sourceFamily?.takeIf { it != holder.project.family }
        var copiedFromProject = false
        val localCustom = holder.project.customPropModels().any { it.key == info.key }
        val modelKey = if (sourceProject != null) {
          val snapshot = sourceProject.transferableCustomPropSnapshot(info.sourceModelKey)
              ?: error("The source project prop ${info.label} could not be read")
          copiedFromProject = true
          holder.project.installForeignProp(info, snapshot).key
        } else if (localCustom) {
          info.key
        } else if (foreign == null) {
          info.sourceModelKey
        } else {
          val library = ndsPropLibraries[foreign]
          if (library == null) {
            // This is an already-installed local entry being used without Show all enabled.
            info.key.takeIf { holder.project.propModelPreview(it, map).triangles.isNotEmpty() }
                ?: error("The ${foreign.displayName} prop library is not loaded")
          } else {
            val snapshot = library.snapshot(info)
                ?: error("The cached prop ${info.label} could not be read")
            holder.project.installForeignProp(info, snapshot).key
          }
        }
        val prop = holder.project.createProp(
            modelKey,
            snapNdsCoord(map.grid.cols / 2f),
            snapNdsCoord(map.grid.rows / 2f),
        )
        map.props += prop
        selectedNdsPropIds.clear(); selectedNdsPropIds += prop.id
        selectedNdsPropId = prop.id
        rebuildNdsPropCatalog()
        ndsPropsPanel.refreshProps(selectedNdsPropIds, prop.id)
        ndsPaintMode.selectedIndex = 4
        refreshNdsPropGeometry(refreshTextures = true)
        markDirty()
        status.text = if (copiedFromProject) {
          "Copied ${info.label} into ${holder.project.family.displayName} props and placed it at the map center"
        } else {
          "Placed ${info.label} at the map center - drag it to move"
        }
      } catch (t: Throwable) {
        JOptionPane.showMessageDialog(
            this, t.message ?: t.toString(), "Place prop failed", JOptionPane.ERROR_MESSAGE)
      }
    }
  }

  private fun selectNdsProp(id: String?) = selectNdsProps(id?.let(::setOf).orEmpty(), id)

  private fun selectNdsProps(ids: Set<String>, primary: String?) {
    commitNdsNumericTransform()
    val map = currentNdsMap ?: return
    selectedNdsPropIds.clear()
    selectedNdsPropIds += ids.filter { candidate -> map.props.any { it.id == candidate } }
    selectedNdsPropId = primary?.takeIf { it in selectedNdsPropIds } ?: selectedNdsPropIds.lastOrNull()
    selectedNdsTerrainGroup = null
    if (selectedNdsPropIds.isNotEmpty() && ndsPaintMode.selectedIndex != 4) ndsPaintMode.selectedIndex = 4
    ndsPropsPanel.selectProps(selectedNdsPropIds, selectedNdsPropId)
    refreshNdsMarkers()
    val prop = map.props.firstOrNull { it.id == selectedNdsPropId }
    status.text = when {
      prop == null -> "No prop selected"
      selectedNdsPropIds.size == 1 ->
          "Selected ${prop.modelKey.substringAfter(':')} - drag to move, Delete removes"
      else -> "Selected ${selectedNdsPropIds.size} props - drag to move together or use Merge Prop"
    }
  }

  private fun selectNdsTerrainObject(groupId: String?) {
    val map = currentNdsMap ?: return
    val project = currentNdsHolder?.project ?: return
    selectedNdsPropIds.clear()
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
    recordNdsSceneChange("remove ${selectedNdsPropIds.size} prop(s)") {
      val map = currentNdsMap ?: return
      val holder = currentNdsHolder ?: return
      val ids = selectedNdsPropIds.toList()
      if (ids.isEmpty()) return
      var removed = 0
      for (id in ids) {
        if (holder.project.removePropObject(map, id, ndsClearCollisionWithTerrain.isSelected) != null) removed++
      }
      selectedNdsPropIds.clear()
      selectedNdsPropId = null
      selectedNdsTerrainGroup = null
      ndsPropsPanel.refreshProps(null)
      markDirty()
      refreshNdsPropGeometry(refreshTextures = false)
      status.text = "Removed $removed prop(s)"
    }
  }

  private fun duplicateSelectedNdsProp() {
    recordNdsSceneChange("duplicate prop") {
      val map = currentNdsMap ?: return
      val holder = currentNdsHolder ?: return
      val source = map.props.firstOrNull { it.id == selectedNdsPropId } ?: return
      val duplicate = holder.project.duplicateProp(source)
      map.props += duplicate
      selectedNdsPropIds.clear(); selectedNdsPropIds += duplicate.id
      selectedNdsPropId = duplicate.id
      ndsPropsPanel.refreshProps(selectedNdsPropIds, duplicate.id)
      markDirty()
      refreshNdsPropGeometry(refreshTextures = false)
      status.text = "Duplicated prop; drag it into place"
    }
  }

  private fun mergeSelectedNdsProps() {
    val map = currentNdsMap ?: return
    val holder = currentNdsHolder ?: return
    if (selectedNdsPropIds.size < 2) return
    val label = JOptionPane.showInputDialog(
        this, "Name for the merged catalog prop", "Merge Props",
        JOptionPane.PLAIN_MESSAGE, null, null, "${map.displayName} merged prop")
        ?.toString()?.trim() ?: return
    if (label.isEmpty()) return
    try {
      val selectedIds = selectedNdsPropIds.toSet()
      val merged = holder.project.buildMergedPropSnapshot(map, selectedIds)
          ?: error("The selected props contain no supported geometry")
      val saved = holder.project.saveMergedProp(label, merged.snapshot, map.name)
      recordNdsSceneChange("merge ${selectedIds.size} props") {
        // Replacement must not archive removals or alter collision: it is the same visible object.
        map.props.removeAll { it.id in selectedIds }
        val replacement = holder.project.createProp(saved.key, merged.x, merged.z)
        replacement.y = merged.y
        map.props += replacement
        selectedNdsPropIds.clear(); selectedNdsPropIds += replacement.id
        selectedNdsPropId = replacement.id
        selectedNdsTerrainGroup = null
        rebuildNdsPropCatalog()
        ndsPropsPanel.selectModel(saved.catalogId)
        ndsPropsPanel.refreshProps(selectedNdsPropIds, replacement.id)
        markDirty()
        refreshNdsPropGeometry(refreshTextures = true)
        status.text = "Replaced ${selectedIds.size} props with merged '${saved.label}'"
      }
    } catch (t: Throwable) {
      JOptionPane.showMessageDialog(this, t.message ?: t.toString(), "Merge Prop failed", JOptionPane.ERROR_MESSAGE)
    }
  }

  private fun onNdsPropTransformChanged(beforeProp: NdsProp) {
    val map = currentNdsMap ?: return
    if (ndsNumericTransformBefore == null || ndsNumericTransformMap !== map) {
      commitNdsNumericTransform()
      val current = NdsSceneSnapshot.of(map)
      ndsNumericTransformBefore = NdsSceneSnapshot(
          props = current.props.map { if (it.id == beforeProp.id) beforeProp.copy() else it },
          removals = current.removals,
          transforms = current.transforms,
          walkSurfaces = current.walkSurfaces,
          collision = current.collision,
      )
      ndsNumericTransformMap = map
    }
    ndsNumericTransformTimer.restart()
    markDirty()
    refreshNdsPropGeometry(refreshTextures = false)
  }

  /** Records a burst of spinner/typed transform changes as one undoable scene edit. */
  private fun commitNdsNumericTransform() {
    ndsNumericTransformTimer.stop()
    val before = ndsNumericTransformBefore
    val map = ndsNumericTransformMap
    ndsNumericTransformBefore = null
    ndsNumericTransformMap = null
    if (before != null && map != null && currentNdsMap === map) {
      ndsHistory.recordScene("change prop transform", before, NdsSceneSnapshot.of(map))
    }
  }

  private fun discardNdsNumericTransform() {
    ndsNumericTransformTimer.stop()
    ndsNumericTransformBefore = null
    ndsNumericTransformMap = null
  }

  private fun refreshNdsPropGeometry(refreshTextures: Boolean) {
    val map = currentNdsMap ?: return
    val holder = currentNdsHolder ?: return
    val v = view() ?: return
    val terrain = holder.project.trianglesFor(map)
    val props = holder.project.buildingTrianglesFor(map)
    // Painted tiles follow the terrain only, so props moving around never carry the paint with
    // them; the view still draws and picks against everything.
    v.surfaceTriangles = terrain
    v.modelTriangles = terrain + props
    if (refreshTextures) {
      // Re-merges the project tile textures, which this would otherwise drop.
      refreshNdsCustomTiles(holder.project.texturesFor(map), holder.project.palettesFor(map))
    }
    refreshNdsMarkers()
    // Terrain just changed shape, so re-resolve which triangles the picked squares now hold.
    refreshNdsSurfaceHighlight()
    v.asComponent().repaint()
  }

  // ---- DS undo --------------------------------------------------------------

  /**
   * Binds undo/redo on the 3D view itself.
   *
   * The OpenGL view is a heavyweight AWT canvas, and Swing menu accelerators are not reliably
   * delivered while one of those holds focus -- which it does from the first click on the map, so
   * the Edit menu's Ctrl+Z would be dead exactly while someone is painting. Binding the keys on
   * the component cannot shadow a text field's own Ctrl+Z either, because it only fires while the
   * view has focus.
   */
  private fun installNdsShortcuts(component: Component) {
    component.isFocusable = true
    component.addMouseListener(object : MouseAdapter() {
      override fun mousePressed(e: MouseEvent) {
        component.requestFocusInWindow()
      }
    })
    component.addKeyListener(object : KeyAdapter() {
      override fun keyPressed(e: KeyEvent) {
        if (e.keyCode == KeyEvent.VK_DELETE && ndsPaintMode.selectedIndex == 8 &&
            selectedNdsWalkSurfaceId != null) {
          deleteSelectedNdsWalkSurface()
          e.consume()
          return
        }
        if (!e.isControlDown) return
        when (e.keyCode) {
          // Ctrl+Shift+Z redoes as well, which is what most editors answer to.
          KeyEvent.VK_Z -> if (e.isShiftDown) redo() else undo()
          KeyEvent.VK_Y -> redo()
          else -> return
        }
        e.consume()
      }
    })
  }

  /** Names a stroke after the mode that painted it, for the status line. */
  private fun ndsStrokeLabel(): String =
      when (ndsPaintMode.selectedIndex) {
        1 -> "collision"
        2 -> "permission"
        3 -> "height"
        7 -> "grass"
        8 -> "walk surface"
        else -> "tile"
      }

  /**
   * Runs a prop or scenery change and records what it did.
   *
   * Inline so the wrapped block can bail out with `return`, which these methods do freely --
   * several of them return in the middle *after* changing the scene. That is why the snapshot is
   * taken in a `finally`: a non-local return would jump straight past a plain trailing
   * comparison, and the change would go unrecorded and stay unundoable. Anything that leaves the
   * scene as it found it records nothing, so a bail-out costs only the comparison.
   */
  private inline fun recordNdsSceneChange(label: String, act: () -> Unit) {
    commitNdsNumericTransform()
    val map = currentNdsMap ?: return
    val before = NdsSceneSnapshot.of(map)
    try {
      act()
    } finally {
      ndsHistory.recordScene(label, before, NdsSceneSnapshot.of(map))
    }
  }

  /**
   * Rounds a map-tile coordinate onto the grid while Snap to grid is on.
   *
   * Whole coordinates are the alignment that matters: tile space is one unit per square, so a
   * surface lifted off a square only lines back up with the grid at a whole coordinate.
   */
  private fun snapNdsCoord(value: Float): Float =
      if (ndsSnapToGridCheck.isSelected) kotlin.math.round(value) else value

  private fun undoNds() {
    commitNdsNumericTransform()
    val map = currentNdsMap ?: return
    val step = ndsHistory.undo(map)
    if (step == null) {
      status.text = "Nothing left to undo"
      return
    }
    afterNdsHistoryStep(map, step, undone = true)
  }

  private fun redoNds() {
    commitNdsNumericTransform()
    val map = currentNdsMap ?: return
    val step = ndsHistory.redo(map)
    if (step == null) {
      status.text = "Nothing left to redo"
      return
    }
    afterNdsHistoryStep(map, step, undone = false)
  }

  /** Redraws whatever the step it was handed touched, and says what happened. */
  private fun afterNdsHistoryStep(map: NdsMap, step: NdsUndoStep, undone: Boolean) {
    val verb = if (undone) "Undid" else "Redid"
    when (step) {
      is NdsGridStep -> {
        view()?.asComponent()?.repaint()
        status.text = "$verb ${step.edits.size} ${step.label} edit(s)"
      }
      is NdsSceneStep -> {
        // The selection can name a prop that no longer exists on this side of the step.
        selectedNdsPropIds.retainAll(map.props.map { it.id }.toSet())
        if (selectedNdsPropId !in selectedNdsPropIds) selectedNdsPropId = selectedNdsPropIds.lastOrNull()
        selectedNdsTerrainGroup = null
        if (selectedNdsWalkSurfaceId != null &&
            map.walkSurfaces.none { it.id == selectedNdsWalkSurfaceId }) {
          selectedNdsWalkSurfaceId = null
        }
        ndsPropsPanel.refreshProps(selectedNdsPropIds, selectedNdsPropId)
        // Textures too: a prop coming back needs the ones it was drawn with re-merged.
        refreshNdsPropGeometry(refreshTextures = true)
        view()?.walkSurfaceTriangles = currentNdsWalkSurfaces()
        refreshNdsWalkSurfaceHandles()
        status.text = "$verb ${step.label}"
      }
    }
    markDirty()
  }

  /**
   * The squares one click paints, centred on the square under the pointer.
   *
   * Shares [NdsProject.surfaceBrushCells] with the surface picker's brush so the two size and
   * centre identically -- including for even sizes, where the clicked square has to stay inside
   * the brush.
   */
  private fun ndsBrushCells(grid: NdsGrid, x: Int, z: Int): List<Pair<Int, Int>> {
    val size = (ndsTileBrushSpinner.value as Number).toInt()
    val cells =
        if (size <= 1) listOf(x to z)
        else NdsProject.surfaceBrushCells(x + 0.5f, z + 0.5f, size)
            .map { NdsProject.surfaceCellX(it) to NdsProject.surfaceCellZ(it) }
    return cells.filter { (cx, cz) -> cx in 0 until grid.cols && cz in 0 until grid.rows }
  }

  private fun paintNdsCell(x: Int, z: Int, emptyOnly: Boolean = false) {
    val map = currentNdsMap ?: return
    val view = view() ?: return
    if (ndsPaintMode.selectedIndex == 7) {
      paintNdsGrass(map, view, x, z, erase = false)
      return
    }
    val layer = view.activeLayer
    val height = ndsPaintMode.selectedIndex == 3
    val tileStore = currentNdsHolder?.project?.customTileStore
    val storedTiles = if (height) emptyList() else tileStore?.tiles().orEmpty()
    val stamp = storedTiles.firstOrNull { it.index == view.activeTile }
    val tileFootprints = storedTiles.associate { it.index to (it.width to it.height) }
    val overlayIds = storedTiles.filter { it.overlay }.map { it.index }.toSet()
    var painted = 0
    var overlayFull = false
    for ((cx, cz) in ndsBrushCells(map.grid, x, z)) {
      if (height) {
        ndsHistory.recordCell(NdsCellEdit(
            NdsCellKind.HEIGHT, layer, cx, cz,
            map.grid.heightAt(layer, cx, cz), view.activeHeight.coerceIn(-32.0, 32.0)))
        map.grid.setHeight(layer, cx, cz, view.activeHeight)
      } else {
        if (stamp != null &&
            (cx + stamp.width > map.grid.cols || cz + stamp.height > map.grid.rows)) continue
        if (emptyOnly && !ndsTilePlacementIsClear(
                map.grid, cx, cz, stamp?.width ?: 1, stamp?.height ?: 1, tileFootprints)) {
          continue
        }
        val targetLayer = if (stamp?.overlay == true) {
          // Repainting an overlay cell replaces its existing detail instead of building an
          // invisible stack every time the brush crosses that square.
          (layer until NdsGrid.LAYERS).firstOrNull {
            map.grid.tileAt(it, cx, cz) in overlayIds
          } ?: ndsOverlayLayer(map.grid, layer, cx, cz, stamp.width, stamp.height)
        } else layer
        if (targetLayer == null) {
          overlayFull = true
          continue
        }
        // When the active layer already contains ground, an overlay moves above it. Carry its
        // painted height along so the detail remains attached to that ground surface.
        if (targetLayer != layer) {
          val inheritedHeight = map.grid.heightAt(layer, cx, cz)
          if (map.grid.heightAt(targetLayer, cx, cz) != inheritedHeight) {
            ndsHistory.recordCell(NdsCellEdit(
                NdsCellKind.HEIGHT, targetLayer, cx, cz,
                map.grid.heightAt(targetLayer, cx, cz), inheritedHeight))
            map.grid.setHeight(targetLayer, cx, cz, inheritedHeight)
          }
        }
        ndsHistory.recordCell(NdsCellEdit(
            NdsCellKind.TILE, targetLayer, cx, cz,
            map.grid.tileAt(targetLayer, cx, cz), view.activeTile))
        map.grid.setTile(targetLayer, cx, cz, view.activeTile)
      }
      painted++
    }
    if (!height && painted == 0 && (stamp != null || emptyOnly)) {
      status.text = if (emptyOnly) {
        "Shift paint skipped: that tile footprint overlaps an existing painted tile"
      } else if (overlayFull) {
        "No free overlay layer is available on that square"
      } else {
        "${stamp?.width ?: 1}x${stamp?.height ?: 1} tile does not fit at that map edge"
      }
      return
    }
    markDirty()
    view.asComponent().repaint()
  }

  /**
   * Ctrl+click/drag in Tile or Height mode: empties the square on the active layer.
   *
   * Painting can only ever overwrite one tile index with another, so without this a square that
   * was painted by mistake stayed painted for the life of the map.
   */
  private fun eraseNdsCell(x: Int, z: Int) {
    val map = currentNdsMap ?: return
    val view = view() ?: return
    if (ndsPaintMode.selectedIndex == 7) {
      paintNdsGrass(map, view, x, z, erase = true)
      return
    }
    val layer = view.activeLayer
    val height = ndsPaintMode.selectedIndex == 3
    val selectedOverlay = currentNdsHolder?.project?.customTileStore?.tiles()
        ?.firstOrNull { it.index == view.activeTile }?.overlay == true
    for ((cx, cz) in ndsBrushCells(map.grid, x, z)) {
      if (height) {
        ndsHistory.recordCell(NdsCellEdit(
            NdsCellKind.HEIGHT, layer, cx, cz, map.grid.heightAt(layer, cx, cz), 0.0))
        map.grid.setHeight(layer, cx, cz, 0)
      } else {
        val targetLayer = if (selectedOverlay) {
          (layer until NdsGrid.LAYERS).firstOrNull {
            map.grid.tileAt(it, cx, cz) == view.activeTile
          } ?: layer
        } else layer
        // -1 is the grid's own empty value, the same one a fresh map starts every square at.
        ndsHistory.recordCell(NdsCellEdit(
            NdsCellKind.TILE, targetLayer, cx, cz, map.grid.tileAt(targetLayer, cx, cz), -1))
        map.grid.setTile(targetLayer, cx, cz, -1)
        if (selectedOverlay && targetLayer != layer && map.grid.heightAt(targetLayer, cx, cz) != 0.0) {
          ndsHistory.recordCell(NdsCellEdit(
              NdsCellKind.HEIGHT, targetLayer, cx, cz,
              map.grid.heightAt(targetLayer, cx, cz), 0.0))
          map.grid.setHeight(targetLayer, cx, cz, 0)
        }
      }
    }
    markDirty()
    view.asComponent().repaint()
  }

  /** Stores only grass interiors; [NdsGrassField] derives the authentic fringe around them. */
  private fun paintNdsGrass(map: NdsMap, view: Nds3DView, x: Int, z: Int, erase: Boolean) {
    val store = currentNdsHolder?.project?.customTileStore ?: return
    if (store.tiles().none { it.index == NdsGrassField.INTERIOR }) {
      status.text = "This HGSS grass library has not been generated for the active project"
      return
    }
    val activeLayer = view.activeLayer
    var changed = 0
    for ((cx, cz) in ndsBrushCells(map.grid, x, z)) {
      val existingLayer = (0 until NdsGrid.LAYERS).firstOrNull {
        map.grid.tileAt(it, cx, cz) == NdsGrassField.INTERIOR
      }
      if (erase) {
        val target = existingLayer ?: continue
        ndsHistory.recordCell(NdsCellEdit(
            NdsCellKind.TILE, target, cx, cz, NdsGrassField.INTERIOR, -1))
        map.grid.setTile(target, cx, cz, -1)
        if (target != activeLayer && map.grid.heightAt(target, cx, cz) != 0.0) {
          ndsHistory.recordCell(NdsCellEdit(
              NdsCellKind.HEIGHT, target, cx, cz, map.grid.heightAt(target, cx, cz), 0.0))
          map.grid.setHeight(target, cx, cz, 0)
        }
        changed++
        continue
      }
      if (existingLayer != null) continue
      val target = ndsOverlayLayer(map.grid, activeLayer, cx, cz, 1, 1)
      if (target == null) continue
      val inheritedHeight = map.grid.heightAt(activeLayer, cx, cz)
      if (target != activeLayer && map.grid.heightAt(target, cx, cz) != inheritedHeight) {
        ndsHistory.recordCell(NdsCellEdit(
            NdsCellKind.HEIGHT, target, cx, cz,
            map.grid.heightAt(target, cx, cz), inheritedHeight))
        map.grid.setHeight(target, cx, cz, inheritedHeight)
      }
      ndsHistory.recordCell(NdsCellEdit(
          NdsCellKind.TILE, target, cx, cz,
          map.grid.tileAt(target, cx, cz), NdsGrassField.INTERIOR))
      map.grid.setTile(target, cx, cz, NdsGrassField.INTERIOR)
      changed++
    }
    if (changed > 0) markDirty()
    view.asComponent().repaint()
  }

  private fun paintNdsCollision(x: Int, z: Int, value: Int) {
    val map = currentNdsMap ?: return
    val view = view() ?: return
    val permission = ndsPaintMode.selectedIndex == 2
    // Both grids store a byte, so record what the setter will actually keep.
    val masked = value and 0xFF
    for ((cx, cz) in ndsBrushCells(map.grid, x, z)) {
      if (permission) {
        ndsHistory.recordCell(NdsCellEdit(
            NdsCellKind.PERMISSION, 0, cx, cz, map.grid.permissionAt(cx, cz), masked))
        map.grid.setPermission(cx, cz, value)
      } else {
        ndsHistory.recordCell(NdsCellEdit(
            NdsCellKind.COLLISION, 0, cx, cz, map.grid.collisionAt(cx, cz), masked))
        map.grid.setCollision(cx, cz, value)
      }
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
