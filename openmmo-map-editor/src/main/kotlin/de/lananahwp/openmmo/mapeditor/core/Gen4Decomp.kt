package de.lananahwp.openmmo.mapeditor.core

import de.lananahwp.openmmo.mapeditor.json.JsonParser
import de.lananahwp.openmmo.mapeditor.model.NdsBgEvent
import de.lananahwp.openmmo.mapeditor.model.NdsEvents
import de.lananahwp.openmmo.mapeditor.model.NdsGrid
import de.lananahwp.openmmo.mapeditor.model.NdsMap
import de.lananahwp.openmmo.mapeditor.model.NdsMapHeader
import de.lananahwp.openmmo.mapeditor.model.NdsObject
import de.lananahwp.openmmo.mapeditor.model.NdsTrigger
import de.lananahwp.openmmo.mapeditor.model.NdsWarp
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Which Gen 4 game family a decomp belongs to. */
enum class NdsFamily(val displayName: String, val regionName: String, val romType: Int) {
  HEART_GOLD("HeartGold/SoulSilver", "johto", 4),
  PLATINUM("Diamond/Pearl/Platinum", "sinnoh", 3),
}

/** A parsed Gen 4 map definition (decomp-format agnostic). */
data class NdsMapDef(
    val name: String,
    val mapId: Int,
    val header: NdsMapHeader,
    val events: NdsEvents,
)

/** Parses maps, headers, events, and matrices from a Gen 4 decomp. */
class Gen4Decomp(val rootDir: File) {
  val family: NdsFamily = detectFamily()

  /** MAP_ name -> numeric id. */
  val mapIds: Map<String, Int> by lazy { readMapIds() }

  /** Numeric id -> MAP_ name. */
  val mapNames: Map<Int, String> by lazy { mapIds.entries.associate { (k, v) -> v to k } }

  /** SPRITE_ name -> numeric id. */
  val spriteIds: Map<String, Int> by lazy { readDefineTable("include/constants/sprites.h", "SPRITE_") }

  private val headerDefs by lazy { readHeaders() }
  private val matrixDefs by lazy { readMatrices() }

  private fun detectFamily(): NdsFamily {
    val hg = File(rootDir, "src/data/map_headers.h").isFile &&
        File(rootDir, "files/fielddata/eventdata/zone_event").isDirectory
    if (hg) return NdsFamily.HEART_GOLD
    val pt = File(rootDir, "include/data/map_headers.h").isFile &&
        File(rootDir, "generated/map_headers.txt").isFile
    if (pt) return NdsFamily.PLATINUM
    return NdsFamily.HEART_GOLD
  }

  /** Reads `#define PREFIX... value` tables. */
  private fun readDefineTable(path: String, prefix: String): Map<String, Int> {
    val file = File(rootDir, path)
    if (!file.exists()) return emptyMap()
    val re = Regex("""#define\s+($prefix\w+)\s+(\d+)""")
    return file.readLines().mapNotNull { re.find(it.trim()) }
        .associate { it.groupValues[1] to it.groupValues[2].toInt() }
  }

  private fun readMapIds(): Map<String, Int> {
    if (family == NdsFamily.HEART_GOLD) {
      return readDefineTable("include/constants/maps.h", "MAP_")
          .filterKeys { it != "MAP_ID_MAX" && it != "MAP_EVERYWHERE" && it != "MAP_NOTHING" }
    }
    // Platinum decomp lists header names in generation order.
    val file = File(rootDir, "generated/map_headers.txt")
    if (!file.exists()) return emptyMap()
    return file.readLines().map(String::trim).filter(String::isNotEmpty)
        .withIndex()
        .filter { it.index >= 2 }
        .associate { it.value to it.index }
  }

  private fun headerFile(): File =
      if (family == NdsFamily.HEART_GOLD) File(rootDir, "src/data/map_headers.h")
      else File(rootDir, "include/data/map_headers.h")

  private fun eventsFile(name: String): File =
      if (family == NdsFamily.HEART_GOLD)
        File(rootDir, "files/fielddata/eventdata/zone_event/$name.json")
      else File(rootDir, "res/field/events/$name.json")

  /** Parses map_headers.h into map definitions. */
  private fun readHeaders(): Map<String, NdsMapHeader> {
    val file = headerFile()
    if (!file.exists()) return emptyMap()
    val out = LinkedHashMap<String, NdsMapHeader>()
    var current: NdsMapHeader? = null
    val entryRe =
        if (family == NdsFamily.HEART_GOLD) Regex("""\[(MAP_\w+)]\s*=\s*\{""")
        else Regex("""\[(MAP_HEADER_\w+)]\s*=\s*\{""")
    for (raw in file.readLines()) {
      val line = raw.trim()
      entryRe.find(line)?.let {
        current = NdsMapHeader().also { h ->
          h.name = it.groupValues[1]
          if (family == NdsFamily.HEART_GOLD) {
            h.eventsFile = it.groupValues[1]
          } else {
            h.eventsFile = it.groupValues[1].removePrefix("MAP_HEADER_")
          }
        }
        current?.let { c -> out[c.name] = c }
        continue
      }
      val h = current ?: continue
      assignHeaderField(h, line)
    }
    return out
  }

  private fun assignHeaderField(h: NdsMapHeader, line: String) {
    val eq = Regex("""\.(\w+)\s*=\s*(.+)""").find(line) ?: return
    val field = eq.groupValues[1]
    val value = eq.groupValues[2].trim().removeSuffix(",").trim()
    fun bool(default: Boolean): Boolean =
        when {
          value == "TRUE" || value == "true" -> true
          value == "FALSE" || value == "false" -> false
          else -> default
        }
    fun int(): Int? =
        if (value.startsWith("0x", ignoreCase = true)) value.substring(2).toIntOrNull(16)
        else value.toIntOrNull()
    fun bankName(): String = value.substringAfterLast('_').trim()
    // Extracts the numeric id from names like `map_matrix_0074` / `map_matrix_000` /
    // `NARC_map_matrix_map_matrix_0074_UNION_bin` -> 74.
    fun matrixNumber(): Int? =
        Regex("""map_matrix_(\d+)""").find(value)?.groupValues?.get(1)?.toIntOrNull()
            ?: int()
    when (field) {
      "wildEncounterBank" -> h.wildEncounterBank = value
      "areaDataBank" -> int()?.let { h.areaDataBank = it }
      "areaDataArchiveID" -> {
        h.areaDataArchiveID = value.filter { it.isDigit() }.toIntOrNull() ?: 0
      }
      "moveModelBank" -> int()?.let { h.moveModelBank = it }
      "worldMapX" -> int()?.let { h.worldMapX = it }
      "worldMapY" -> int()?.let { h.worldMapY = it }
      "matrixId", "mapMatrixID" -> matrixNumber()?.let { h.matrixId = it }
      "scriptsBank" -> h.scriptsBank = value
      "scriptHeaderBank" -> h.scriptHeaderBank = value
      "msgBank" -> h.msgBank = value
      "dayMusicId" -> h.dayMusicId = value
      "nightMusicId" -> h.nightMusicId = value
      "eventsBank" -> h.eventsFile = value
      "mapsec" -> h.mapsec = value
      "areaIcon" -> int()?.let { h.areaIcon = it }
      "momCallIntroParam" -> int()?.let { h.momCallIntroParam = it }
      "regionNo" -> h.regionNo = value
      "weather" -> int()?.let { h.weather = it }
      "mapType" -> int()?.let { h.mapType = it }
      "cameraType" -> int()?.let { h.cameraType = it }
      "followMode" -> h.followMode = value
      "battleBg" -> h.battleBg = value
      "bikeAllowed" -> h.bikeAllowed = bool(h.bikeAllowed)
      "runningAllowed_Unused" -> h.runningAllowed = bool(h.runningAllowed)
      "escapeRopeAllowed" -> h.escapeRopeAllowed = bool(h.escapeRopeAllowed)
      "flyAllowed" -> h.flyAllowed = bool(h.flyAllowed)
      "outgoingCalls" -> h.outgoingCalls = bool(h.outgoingCalls)
      "incomingCalls" -> h.incomingCalls = bool(h.incomingCalls)
      "radioSignal" -> h.radioSignal = bool(h.radioSignal)
      else -> {}
    }
  }

  /** Resolves a header's events file to its zone-event JSON basename. */
  private fun eventsBankName(header: NdsMapHeader): String? {
    if (family == NdsFamily.HEART_GOLD) {
      // eventsBank = NARC_zone_event_006_R01_bin -> zone event file 006_R01.json
      val idx = header.eventsFile.lastIndexOf("_bin")
      val base = if (idx >= 0) header.eventsFile.substring(0, idx) else header.eventsFile
      return base.substringAfterLast("zone_event_").takeIf { it.isNotBlank() }
    }
    return header.eventsFile.takeIf { it != "events_empty" }
  }

  private fun readEvents(header: NdsMapHeader): NdsEvents {
    val base = eventsBankName(header) ?: return NdsEvents()
    val file = eventsFile(base)
    if (!file.exists()) {
      // Some zone names are looked up through the map name.
      val alt = eventsFile(header.name)
      if (!alt.exists()) return NdsEvents()
      return parseEvents(alt)
    }
    return parseEvents(file)
  }

  private fun parseEvents(file: File): NdsEvents {
    val root = JsonParser.parse(file.readText()).asObj() ?: return NdsEvents()
    val events = NdsEvents()
    events.header = root.str("header") ?: ""
    for (item in root.arr("objects")?.items.orEmpty()) {
      val o = item.asObj() ?: continue
      events.objects +=
          NdsObject(
              id = o.str("id") ?: "",
              spriteId = o.str("spriteId") ?: "SPRITE_NONE",
              movement = o.int("movement") ?: 0,
              type = o.int("type") ?: 0,
              eventFlag = o.str("eventFlag") ?: "FLAG_NOTHING",
              scriptId = o.str("scriptId") ?: "0",
              facingDirection = o.int("facingDirection") ?: 0,
              param0 = o.int("param0") ?: 0,
              param1 = o.int("param1") ?: 0,
              param2 = o.int("param2") ?: 0,
              xRange = o.int("xRange") ?: 0,
              yRange = o.int("yRange") ?: 0,
              x = o.int("x") ?: 0,
              z = o.int("z") ?: 0,
              y = o.int("y") ?: 0,
          )
    }
    for (item in root.arr("warps")?.items.orEmpty()) {
      val w = item.asObj() ?: continue
      events.warps +=
          NdsWarp(
              x = w.int("x") ?: 0,
              z = w.int("z") ?: 0,
              header = w.str("header") ?: "MAP_NOTHING",
              anchor = w.int("anchor") ?: 0,
              y = w.int("y") ?: 0,
          )
    }
    for (item in root.arr("coords")?.items.orEmpty()) {
      val c = item.asObj() ?: continue
      events.triggers +=
          NdsTrigger(
              scriptId = c.str("scriptId") ?: "0",
              x = c.int("x") ?: 0,
              z = c.int("z") ?: 0,
              w = c.int("w") ?: 1,
              h = c.int("h") ?: 1,
              y = c.int("y") ?: 0,
              variable = c.str("var") ?: "VAR_TEMP_x4000",
              value = c.int("val") ?: 0,
          )
    }
    for (item in root.arr("bgs")?.items.orEmpty()) {
      val b = item.asObj() ?: continue
      events.bgEvents +=
          NdsBgEvent(
              scriptId = b.str("scriptId") ?: "0",
              type = b.int("type") ?: 0,
              x = b.int("x") ?: 0,
              z = b.int("z") ?: 0,
              y = b.int("y") ?: 0,
              dir = b.int("dir") ?: 4,
          )
    }
    return events
  }

  /** Main-matrix cell (x, y) -> map id. */
  private fun readMatrices(): List<MatrixCell> {
    val matrixDir =
        if (family == NdsFamily.HEART_GOLD) File(rootDir, "files/fielddata/mapmatrix/map_matrix")
        else File(rootDir, "generated/map_matrix")
    val main =
        if (family == NdsFamily.HEART_GOLD) File(matrixDir, "map_matrix_0000_EVERYWHERE.bin")
        else File(rootDir, "res/field/map_matrix/map_matrix_0000.bin")
    if (!main.exists()) return emptyList()
    val bytes = main.readBytes()
    val width = bytes[0].toInt() and 0xFF
    val height = bytes[1].toInt() and 0xFF
    val nameLength = bytes[4].toInt() and 0xFF
    val offset = 5 + nameLength
    val out = mutableListOf<MatrixCell>()
    for (y in 0 until height) {
      for (x in 0 until width) {
        val at = offset + (y * width + x) * 2
        if (at + 2 > bytes.size) continue
        val id = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
        out += MatrixCell(x, y, id)
      }
    }
    return out
  }

  /** Cells of the main world matrix. */
  fun matrixCells(): List<MatrixCell> = matrixDefs

  /** All map definitions, in map id order. */
  fun parseAll(): List<NdsMapDef> {
    val defs = mutableListOf<NdsMapDef>()
    for ((name, id) in mapIds) {
      val header = headerDefs[name] ?: continue
      val events = readEvents(header)
      defs += NdsMapDef(name, id, header, events)
    }
    return defs.sortedBy { it.mapId }
  }

  /** Loads one map, keeping matrix cell information. */
  fun loadMap(name: String): NdsMap? {
    val id = mapIds[name] ?: return null
    val header = headerDefs[name] ?: return null
    val events = readEvents(header)
    val map = NdsMap(name, id, header, events, NdsGrid())
    map.regionName
    val cells = matrixDefs.filter { it.mapId == id }
    map.matrixCells.addAll(cells.map { it.x to it.y })
    return map
  }

  data class MatrixCell(val x: Int, val y: Int, val mapId: Int)
}

/** Reads little-endian 16-bit lists (matrix data). */
internal fun readU16Le(file: File): List<Int> {
  if (!file.exists()) return emptyList()
  val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
  val out = mutableListOf<Int>()
  while (buf.remaining() >= 2) out += buf.short.toInt() and 0xFFFF
  return out
}
