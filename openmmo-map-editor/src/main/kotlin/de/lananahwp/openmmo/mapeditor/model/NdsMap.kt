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

  /** [layer][x][y] -> relative height offset of the placed tile. */
  val heights = Array(LAYERS) { Array(cols) { IntArray(rows) { 0 } } }

  /** Collision value per grid square. */
  val collisions = Array(cols) { IntArray(rows) { 0 } }

  /** Permission type per grid square (grass, surf, sand, ...). */
  val permissionTypes = Array(cols) { IntArray(rows) { 0 } }

  fun tileAt(layer: Int, x: Int, y: Int): Int =
      if (inBounds(x, y)) tiles[layer][x][y] else -1

  fun heightAt(layer: Int, x: Int, y: Int): Int =
      if (inBounds(x, y)) heights[layer][x][y] else 0

  fun setTile(layer: Int, x: Int, y: Int, tileIndex: Int) {
    if (inBounds(x, y)) tiles[layer][x][y] = tileIndex
  }

  fun setHeight(layer: Int, x: Int, y: Int, height: Int) {
    if (inBounds(x, y)) heights[layer][x][y] = height.coerceIn(-32, 32)
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
      heights[layer][x][y] = 0
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

  val regionName: String get() = "johto"
}
