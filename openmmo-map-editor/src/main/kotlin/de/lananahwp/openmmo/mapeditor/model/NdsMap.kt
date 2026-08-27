package de.lananahwp.openmmo.mapeditor.model

/** A DS map grid with up to [LAYERS] stacked tile layers (PDSMS-style).
 *
 * A single game "map" (e.g. Oreburgh City) occupies one or more 32x32 matrix cells;
 * this grid spans the whole footprint so [cols]/[rows] can exceed 32.
 */
class NdsGrid(val cols: Int = 32, val rows: Int = 32) {
  companion object {
    const val COLS = 32
    const val ROWS = 32
    const val LAYERS = 8
    /** Small separation used for transparent detail surfaces resting on an existing tile. */
    const val OVERLAY_LIFT = 0.02f
  }

  /** [layer][x][y] -> tile index, or -1 when the cell is empty. */
  val tiles = Array(LAYERS) { Array(cols) { IntArray(rows) { -1 } } }

  /** [layer][x][y] -> relative height offset of the placed tile, in map-tile units. */
  val heights = Array(LAYERS) { Array(cols) { DoubleArray(rows) { 0.0 } } }

  /** Collision value per grid square. */
  val collisions = Array(cols) { IntArray(rows) { 0 } }

  /** Permission type per grid square (grass, surf, sand, ...). */
  val permissionTypes = Array(cols) { IntArray(rows) { 0 } }

  fun tileAt(layer: Int, x: Int, y: Int): Int =
      if (inBounds(x, y)) tiles[layer][x][y] else -1

  fun heightAt(layer: Int, x: Int, y: Int): Double =
      if (inBounds(x, y)) heights[layer][x][y] else 0.0

  fun setTile(layer: Int, x: Int, y: Int, tileIndex: Int) {
    if (inBounds(x, y)) tiles[layer][x][y] = tileIndex
  }

  fun setHeight(layer: Int, x: Int, y: Int, height: Number) {
    if (inBounds(x, y)) heights[layer][x][y] = height.toDouble().coerceIn(-32.0, 32.0)
  }

  fun collisionAt(x: Int, y: Int): Int = if (inBounds(x, y)) collisions[x][y] else 0

  fun setCollision(x: Int, y: Int, value: Int) {
    if (inBounds(x, y)) collisions[x][y] = value and 0xFF
  }

  fun permissionAt(x: Int, y: Int): Int = if (inBounds(x, y)) permissionTypes[x][y] else 0

  fun setPermission(x: Int, y: Int, value: Int) {
    if (inBounds(x, y)) permissionTypes[x][y] = value and 0xFF
  }

  fun isLayerEmpty(layer: Int): Boolean {
    for (x in 0 until cols) for (y in 0 until rows) if (tiles[layer][x][y] != -1) return false
    return true
  }

  /** Clears tiles and heights on a layer. */
  fun clearLayer(layer: Int) {
    for (x in 0 until cols) for (y in 0 until rows) {
      tiles[layer][x][y] = -1
      heights[layer][x][y] = 0.0
    }
  }

  private fun inBounds(x: Int, y: Int) = x in 0 until cols && y in 0 until rows
}

/** One overworld object (NPC) from a Gen 4 zone event file. */
data class NdsObject(
    var id: String = "",
    var spriteId: String = "SPRITE_NONE",
    var movement: Int = 0,
    var type: Int = 0,
    var eventFlag: String = "FLAG_NOTHING",
    var scriptId: String = "0",
    var facingDirection: Int = 0,
    var param0: Int = 0,
    var param1: Int = 0,
    var param2: Int = 0,
    var xRange: Int = 0,
    var yRange: Int = 0,
    var x: Int = 0,
    var z: Int = 0,
    var y: Int = 0,
)

/** One warp from a Gen 4 zone event file. */
data class NdsWarp(
    var x: Int = 0,
    var z: Int = 0,
    var header: String = "MAP_NOTHING",
    var anchor: Int = 0,
    var y: Int = 0,
)

/** One coordinate trigger from a Gen 4 zone event file. */
data class NdsTrigger(
    var scriptId: String = "0",
    var x: Int = 0,
    var z: Int = 0,
    var w: Int = 1,
    var h: Int = 1,
    var y: Int = 0,
    var variable: String = "VAR_TEMP_x4000",
    var value: Int = 0,
)

/** One background-sound / bg event from a Gen 4 zone event file. */
data class NdsBgEvent(
    var scriptId: String = "0",
    var type: Int = 0,
    var x: Int = 0,
    var z: Int = 0,
    var y: Int = 0,
    var dir: Int = 4,
)

/** All events attached to a Gen 4 map. */
class NdsEvents {
  val objects = mutableListOf<NdsObject>()
  val warps = mutableListOf<NdsWarp>()
  val triggers = mutableListOf<NdsTrigger>()
  val bgEvents = mutableListOf<NdsBgEvent>()
  var header = ""

  fun isEmpty() = objects.isEmpty() && warps.isEmpty() && triggers.isEmpty() && bgEvents.isEmpty()
}

/** One editable 3D prop/building placement in map tile coordinates. */
data class NdsProp(
    val id: String,
    var modelKey: String,
    var x: Float = 0f,
    var y: Float = 0f,
    var z: Float = 0f,
    var rotationX: Float = 0f,
    var rotationY: Float = 0f,
    var rotationZ: Float = 0f,
    var scaleX: Float = 1f,
    var scaleY: Float = 1f,
    var scaleZ: Float = 1f,
)

data class NdsCollisionRestore(
    val x: Int,
    val z: Int,
    val collision: Int,
)

data class NdsTerrainRemoval(
    val id: String,
    val groupId: String,
    val clearedCollision: MutableList<NdsCollisionRestore> = mutableListOf(),
    /** Present when the removed scenery was a ROM/custom prop rather than baked terrain. */
    val removedProp: NdsProp? = null,
)

/** Project-local translation applied to one connected object baked into the terrain model. */
data class NdsTerrainTransform(
    val groupId: String,
    var offsetX: Float = 0f,
    var offsetY: Float = 0f,
    var offsetZ: Float = 0f,
)

/** Which edge of a custom walk-surface rectangle is its high edge. */
enum class NdsWalkSurfaceDirection {
  FLAT,
  NORTH,
  EAST,
  SOUTH,
  WEST,

  ;

  fun opposite(): NdsWalkSurfaceDirection = when (this) {
    NORTH -> SOUTH
    EAST -> WEST
    SOUTH -> NORTH
    WEST -> EAST
    FLAT -> FLAT
  }
}

/**
 * One explicitly authored walkable plane for a custom map.
 *
 * X/Z bounds use map-tile edges: [minX], [minZ] are inclusive and [maxX], [maxZ] are the far
 * edges. Each corner stores its own height, allowing one plane to slope along X and Z at once.
 * [lowHeight], [highHeight], and [riseDirection] are calculated summaries used by the UI.
 */
data class NdsWalkSurface(
    val id: String,
    var minX: Int,
    var minZ: Int,
    var maxX: Int,
    var maxZ: Int,
    var northWestHeight: Double = 0.0,
    var northEastHeight: Double = northWestHeight,
    var southEastHeight: Double = northWestHeight,
    var southWestHeight: Double = northWestHeight,
) {
  companion object {
    /** Creates the simple flat/cardinal shape used by painting and prop fitting. */
    fun cardinal(
        id: String,
        minX: Int,
        minZ: Int,
        maxX: Int,
        maxZ: Int,
        lowHeight: Double = 0.0,
        highHeight: Double = lowHeight,
        riseDirection: NdsWalkSurfaceDirection = NdsWalkSurfaceDirection.FLAT,
    ): NdsWalkSurface {
      val corners = when (riseDirection) {
        NdsWalkSurfaceDirection.FLAT -> doubleArrayOf(lowHeight, lowHeight, lowHeight, lowHeight)
        NdsWalkSurfaceDirection.NORTH ->
          doubleArrayOf(highHeight, highHeight, lowHeight, lowHeight)
        NdsWalkSurfaceDirection.EAST ->
          doubleArrayOf(lowHeight, highHeight, highHeight, lowHeight)
        NdsWalkSurfaceDirection.SOUTH ->
          doubleArrayOf(lowHeight, lowHeight, highHeight, highHeight)
        NdsWalkSurfaceDirection.WEST ->
          doubleArrayOf(highHeight, lowHeight, lowHeight, highHeight)
      }
      return NdsWalkSurface(
          id, minX, minZ, maxX, maxZ,
          corners[0], corners[1], corners[2], corners[3])
    }
  }

  val lowHeight: Double get() = minOf(
      northWestHeight, northEastHeight, southEastHeight, southWestHeight)

  val highHeight: Double get() = maxOf(
      northWestHeight, northEastHeight, southEastHeight, southWestHeight)

  val riseDirection: NdsWalkSurfaceDirection
    get() {
      if (kotlin.math.abs(highHeight - lowHeight) < 1e-9) return NdsWalkSurfaceDirection.FLAT
      val northSouth = (northWestHeight + northEastHeight - southEastHeight - southWestHeight) / 2.0
      val eastWest = (northEastHeight + southEastHeight - northWestHeight - southWestHeight) / 2.0
      return if (kotlin.math.abs(northSouth) >= kotlin.math.abs(eastWest)) {
        if (northSouth >= 0.0) NdsWalkSurfaceDirection.NORTH else NdsWalkSurfaceDirection.SOUTH
      } else {
        if (eastWest >= 0.0) NdsWalkSurfaceDirection.EAST else NdsWalkSurfaceDirection.WEST
      }
    }

  fun contains(x: Double, z: Double): Boolean =
      x >= minX && x <= maxX && z >= minZ && z <= maxZ

  fun heightAt(x: Double, z: Double): Double {
    if (maxX <= minX || maxZ <= minZ) return lowHeight
    val (northWest, northEast, southEast, southWest) = resolvedCornerHeights()
    val u = (x - minX) / (maxX - minX)
    val v = (z - minZ) / (maxZ - minZ)
    val north = northWest + (northEast - northWest) * u
    val south = southWest + (southEast - southWest) * u
    return north + (south - north) * v
  }

  /** NW, NE, SE, SW heights. */
  fun resolvedCornerHeights(): DoubleArray = doubleArrayOf(
      northWestHeight, northEastHeight, southEastHeight, southWestHeight)

  /** Replaces the complete height shape. */
  fun setCornerHeights(northWest: Double, northEast: Double, southEast: Double, southWest: Double) {
    northWestHeight = northWest
    northEastHeight = northEast
    southEastHeight = southEast
    southWestHeight = southWest
  }

  /** Restores the compound height shape from [other]. */
  fun copyHeightShapeFrom(other: NdsWalkSurface) {
    northWestHeight = other.northWestHeight
    northEastHeight = other.northEastHeight
    southEastHeight = other.southEastHeight
    southWestHeight = other.southWestHeight
  }

  /** Moves the entire plane vertically without changing either of its gradients. */
  fun translateHeight(delta: Double) {
    val corners = resolvedCornerHeights()
    val allowed = delta.coerceIn(-32.0 - corners.min(), 32.0 - corners.max())
    setCornerHeights(
        corners[0] + allowed, corners[1] + allowed,
        corners[2] + allowed, corners[3] + allowed)
  }

  /** Height at the midpoint of one cardinal edge. */
  fun heightAtEdge(edge: NdsWalkSurfaceDirection): Double {
    val x = when (edge) {
      NdsWalkSurfaceDirection.EAST -> maxX.toDouble()
      NdsWalkSurfaceDirection.WEST -> minX.toDouble()
      else -> (minX + maxX) / 2.0
    }
    val z = when (edge) {
      NdsWalkSurfaceDirection.NORTH -> minZ.toDouble()
      NdsWalkSurfaceDirection.SOUTH -> maxZ.toDouble()
      else -> (minZ + maxZ) / 2.0
    }
    return heightAt(x, z)
  }

  /**
   * Moves one complete edge vertically. The perpendicular gradient is retained, allowing a plane
   * to slope along X and Z at once instead of resetting when a second side is adjusted.
   */
  fun setEdgeHeight(edge: NdsWalkSurfaceDirection, value: Double) {
    require(edge != NdsWalkSurfaceDirection.FLAT)
    val corners = resolvedCornerHeights()
    val indices = when (edge) {
      NdsWalkSurfaceDirection.NORTH -> intArrayOf(0, 1)
      NdsWalkSurfaceDirection.EAST -> intArrayOf(1, 2)
      NdsWalkSurfaceDirection.SOUTH -> intArrayOf(2, 3)
      NdsWalkSurfaceDirection.WEST -> intArrayOf(0, 3)
      NdsWalkSurfaceDirection.FLAT -> error("Flat is not an edge")
    }
    val current = (corners[indices[0]] + corners[indices[1]]) / 2.0
    val requested = value - current
    val affectedMin = minOf(corners[indices[0]], corners[indices[1]])
    val affectedMax = maxOf(corners[indices[0]], corners[indices[1]])
    val delta = requested.coerceIn(-32.0 - affectedMin, 32.0 - affectedMax)
    corners[indices[0]] += delta
    corners[indices[1]] += delta
    setCornerHeights(corners[0], corners[1], corners[2], corners[3])
  }

  /** Moves one footprint edge while retaining a non-empty rectangle inside [grid]. */
  fun resizeEdge(edge: NdsWalkSurfaceDirection, coordinate: Int, grid: NdsGrid) {
    when (edge) {
      NdsWalkSurfaceDirection.NORTH -> minZ = coordinate.coerceIn(0, maxZ - 1)
      NdsWalkSurfaceDirection.EAST -> maxX = coordinate.coerceIn(minX + 1, grid.cols)
      NdsWalkSurfaceDirection.SOUTH -> maxZ = coordinate.coerceIn(minZ + 1, grid.rows)
      NdsWalkSurfaceDirection.WEST -> minX = coordinate.coerceIn(0, maxX - 1)
      NdsWalkSurfaceDirection.FLAT -> Unit
    }
  }

  /** Rotates the footprint and rise direction clockwise in grid-aligned 90-degree steps. */
  fun rotateQuarterTurns(turns: Int, grid: NdsGrid) {
    val count = ((turns % 4) + 4) % 4
    if (count == 0) return
    val corners = resolvedCornerHeights()
    val oldWidth = maxX - minX
    val oldHeight = maxZ - minZ
    val centerX = (minX + maxX) / 2.0
    val centerZ = (minZ + maxZ) / 2.0
    val width = if (count % 2 == 0) oldWidth else oldHeight
    val height = if (count % 2 == 0) oldHeight else oldWidth
    val nextMinX = kotlin.math.round(centerX - width / 2.0).toInt()
        .coerceIn(0, grid.cols - width)
    val nextMinZ = kotlin.math.round(centerZ - height / 2.0).toInt()
        .coerceIn(0, grid.rows - height)
    minX = nextMinX
    maxX = nextMinX + width
    minZ = nextMinZ
    maxZ = nextMinZ + height
    repeat(count) {
      val oldNorthWest = corners[0]
      corners[0] = corners[3]
      corners[3] = corners[2]
      corners[2] = corners[1]
      corners[1] = oldNorthWest
    }
    setCornerHeights(corners[0], corners[1], corners[2], corners[3])
  }

  /** Scales width and depth proportionally around the footprint centre, snapped to whole cells. */
  fun scaleFootprint(factor: Double, grid: NdsGrid) {
    if (!factor.isFinite() || factor <= 0.0) return
    val centerX = (minX + maxX) / 2.0
    val centerZ = (minZ + maxZ) / 2.0
    val width = kotlin.math.round((maxX - minX) * factor).toInt().coerceIn(1, grid.cols)
    val height = kotlin.math.round((maxZ - minZ) * factor).toInt().coerceIn(1, grid.rows)
    val nextMinX = kotlin.math.round(centerX - width / 2.0).toInt()
        .coerceIn(0, grid.cols - width)
    val nextMinZ = kotlin.math.round(centerZ - height / 2.0).toInt()
        .coerceIn(0, grid.rows - height)
    minX = nextMinX
    maxX = nextMinX + width
    minZ = nextMinZ
    maxZ = nextMinZ + height
  }

  fun isValidFor(grid: NdsGrid): Boolean =
      minX >= 0 && minZ >= 0 && maxX > minX && maxZ > minZ &&
          maxX <= grid.cols && maxZ <= grid.rows &&
          resolvedCornerHeights().all { it.isFinite() }
}

/** Editable header fields of a Gen 4 map. */
class NdsMapHeader {
  var name: String = "MAP_NOTHING"
  var wildEncounterBank: String = "ENCDATA_NA"
  var areaDataBank: Int = 0
  var areaDataArchiveID: Int = 0
  var moveModelBank: Int = 15
  var worldMapX: Int = 0
  var worldMapY: Int = 0
  var matrixId: Int = 0
  var scriptsBank: String = ""
  var scriptHeaderBank: String = ""
  var msgBank: String = ""
  var dayMusicId: String = "SEQ_DUMMY"
  var nightMusicId: String = "SEQ_DUMMY"
  var eventsFile: String = ""
  var mapsec: String = "MAPSEC_NONE"
  var areaIcon: Int = 0
  var momCallIntroParam: Int = 0
  var regionNo: String = "MAP_REGION_JOHTO"
  var weather: Int = 0
  var mapType: Int = 0
  var cameraType: Int = 0
  var followMode: String = "MAP_FOLLOWMODE_PREVENT"
  var battleBg: String = "BATTLE_BG_PLAIN"
  var bikeAllowed: Boolean = true
  var runningAllowed: Boolean = true
  var escapeRopeAllowed: Boolean = true
  var flyAllowed: Boolean = false
  var outgoingCalls: Boolean = false
  var incomingCalls: Boolean = false
  var radioSignal: Boolean = false
}

/** A map inside a Gen 4 project. */
class NdsMap(
    val name: String,
    val mapId: Int,
    val header: NdsMapHeader,
    val events: NdsEvents,
    var grid: NdsGrid,
    var displayName: String = name,
    val isCustom: Boolean = false,
) {
  /** The world-matrix cells occupied by this map (main matrix). */
  val matrixCells = mutableListOf<Pair<Int, Int>>()

  /** Editable ROM-derived and custom prop placements. */
  val props = mutableListOf<NdsProp>()

  /** Reversible removed scenery: hidden terrain groups and archived placed props. */
  val terrainRemovals = mutableListOf<NdsTerrainRemoval>()

  /** Movements of connected scenery that remains baked into the source terrain model. */
  val terrainTransforms = mutableListOf<NdsTerrainTransform>()

  /** Explicit walkable planes authored for this custom map; ROM maps read their planes from BDHC. */
  val walkSurfaces = mutableListOf<NdsWalkSurface>()

  val regionName: String get() = "johto"
}
