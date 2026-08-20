package de.lananahwp.openmmo.mapeditor.project

import de.lananahwp.openmmo.mapeditor.core.NdsFamily
import de.lananahwp.openmmo.mapeditor.json.Json
import de.lananahwp.openmmo.mapeditor.json.JsonWriter
import de.lananahwp.openmmo.mapeditor.model.NdsMap

/**
 * Exports Gen 4 DS maps in OpenMMO's runtime format.
 *
 * NDS maps are keyed on (regionId, bankId, mapId) like every other map. The server sends a
 * `SpecialMapData` payload (matrix id + border connections + lighting/weather/type) for NDS ROM
 * types; [downloadData] marks the map as needing its layout downloaded by clients.
 */
class NdsExporter(private val project: NdsProject) {

  private val decomp get() = project.decomp
  private val family get() = decomp.family

  /** Writes one runtime map under [outputDir] as `<region>/<name>.json`. */
  fun exportMap(map: NdsMap, outputDir: java.io.File): java.io.File {
    val target =
        java.io.File(
            outputDir,
            "${family.regionName}/${identifier(map.name)}.json",
        )
    target.parentFile.mkdirs()
    target.writeText(renderMap(map), Charsets.UTF_8)
    return target
  }

  /** Writes every map in the project. */
  fun exportAll(outputDir: java.io.File): List<java.io.File> =
      project.mapNames.mapNotNull { name ->
        project.loadMap(name)?.let { exportMap(it, outputDir) }
      }

  /** Renders one map in OpenMMO's NDS runtime JSON shape. */
  fun renderMap(map: NdsMap): String {
    val h = map.header
    val root =
        obj(
            "regionId" to num(regionId()),
            "bankId" to num(map.mapId and 0xFF),
            "mapId" to num(map.mapId ushr 8),
            "romType" to num(family.romType),
            "wireContext" to num(wireContext()),
            "mapMatrixId" to num(h.matrixId),
            "lighting" to str(lighting(h)),
            "weather" to str(weather(h)),
            "mapType" to str(mapType(h)),
            "ndsMapCells" to ndsCells(map),
            "borderConnections" to borders(map),
            "warps" to warps(map),
            "npcs" to npcs(map),
            "bgEvents" to bgEvents(map),
            "downloadData" to Json.JBool(true),
        )
    return JsonWriter.writePretty(root) + "\n"
  }

  private fun regionId(): Int = family.romType

  private fun wireContext(): Int = if (family == NdsFamily.HEART_GOLD) 1 else 0

  private fun lighting(h: de.lananahwp.openmmo.mapeditor.model.NdsMapHeader): String =
      if (h.weather == 11) "DARK_FLASH_USABLE" else "REGULAR"

  private fun weather(h: de.lananahwp.openmmo.mapeditor.model.NdsMapHeader): String {
    if (family == NdsFamily.HEART_GOLD) {
      return HGSS_WEATHER[h.weather] ?: "GEN4_RAIN2"
    }
    return "GEN4_RAIN2"
  }

  private fun mapType(h: de.lananahwp.openmmo.mapeditor.model.NdsMapHeader): String =
      if (family == NdsFamily.HEART_GOLD) HGSS_TYPES[h.mapType] ?: "UNKNOWN_0x00"
      else SINNOH_TYPES[h.mapType] ?: "UNKNOWN_0x00"

  private fun ndsCells(map: NdsMap): Json.JArr =
      arr(map.matrixCells.map { obj("x" to num(it.first), "y" to num(it.second)) })

  private fun borders(map: NdsMap): Json.JArr {
    val neighborIds = LinkedHashSet<Int>()
    for ((x, y) in map.matrixCells) {
      for ((dx, dy) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
        val nx = x + dx
        val ny = y + dy
        val n = project.matrixCells().firstOrNull { it.x == nx && it.y == ny }?.mapId
        if (n != null && n != map.mapId) neighborIds += n
      }
    }
    return arr(
        neighborIds.mapIndexed { index, id ->
          obj("key" to num(index), "value" to num(id))
        })
  }

  private fun warps(map: NdsMap): Json.JArr =
      arr(
          map.events.warps.mapNotNull { warp ->
            val targetName = warp.header
            val targetMap = project.loadMap(targetName) ?: return@mapNotNull null
            val targetId = targetMap.mapId
            val targetWarp = targetMap.events.warps.getOrNull(warp.anchor)
            obj(
                "x" to num(warp.x),
                "z" to num(warp.z),
                "elevation" to num(warp.y.coerceAtLeast(0)),
                "targetRegionId" to num(regionId()),
                "targetBankId" to num(targetId and 0xFF),
                "targetMapId" to num(targetId ushr 8),
                "targetX" to num(targetWarp?.x ?: warp.x),
                "targetZ" to num(targetWarp?.z ?: warp.z),
                "targetElevation" to num(targetWarp?.y?.coerceAtLeast(0) ?: 0),
            )
          })

  private fun npcs(map: NdsMap): Json.JArr =
      arr(
          map.events.objects.mapIndexed { index, obj ->
            val spriteId = decomp.spriteIds[obj.spriteId] ?: 0
            obj(
                "entityIdx" to num(index),
                "graphicsId" to num(spriteId),
                "x" to num(obj.x),
                "z" to num(obj.z),
                "elevation" to num(obj.y),
                "movementType" to str(movementType(obj.movement)),
                "movementRangeX" to num(obj.xRange),
                "movementRangeY" to num(obj.yRange),
                "trainerType" to num(if (obj.scriptId.startsWith("std_trainer")) 1 else 0),
                "facing" to str(facing(obj.facingDirection)),
                "script" to str(obj.scriptId),
                "hideFlag" to str(hideFlag(obj)),
            )
          })

  private fun bgEvents(map: NdsMap): Json.JArr =
      arr(
          map.events.bgEvents.map {
            obj(
                "x" to num(it.x),
                "z" to num(it.z),
                "facingDir" to str(bgFacing(it.dir)),
                "script" to str(it.scriptId),
            )
          })

  private fun movementType(movement: Int): String =
      when (movement) {
        0 -> "NONE"
        1 -> "FACE_UP"
        2 -> "FACE_DOWN"
        3 -> "FACE_LEFT"
        4 -> "FACE_RIGHT"
        8 -> "WANDER"
        14 -> "WANDER"
        else -> "NONE"
      }

  private fun facing(dir: Int): String =
      when (dir) {
        0 -> "UP"
        1 -> "DOWN"
        2 -> "LEFT"
        3 -> "RIGHT"
        else -> "DOWN"
      }

  private fun bgFacing(dir: Int): String =
      when (dir) {
        0 -> "BG_EVENT_PLAYER_FACING_NORTH"
        1 -> "BG_EVENT_PLAYER_FACING_EAST"
        2 -> "BG_EVENT_PLAYER_FACING_WEST"
        3 -> "BG_EVENT_PLAYER_FACING_SOUTH"
        else -> "BG_EVENT_PLAYER_FACING_ANY"
      }

  private fun hideFlag(obj: de.lananahwp.openmmo.mapeditor.model.NdsObject): String =
      if (obj.eventFlag == "FLAG_NOTHING") "" else "${family.regionName}/${obj.eventFlag}"

  private fun identifier(name: String): String =
      name.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")

  private fun num(value: Int): Json.JNum = Json.JNum(value.toDouble())

  private fun str(value: String): Json.JStr = Json.JStr(value)

  private fun arr(items: List<Json>): Json.JArr = Json.JArr(items)

  private fun obj(vararg entries: Pair<String, Json>): Json.JObj =
      Json.JObj(linkedMapOf(*entries))

  companion object {
    val HGSS_WEATHER =
        mapOf(
            0 to "GEN4_RAIN2",
            1 to "GEN4_RAIN",
            2 to "GEN4_RAIN",
            3 to "GEN4_RAIN",
            5 to "GEN4_SNOW",
            6 to "GEN4_SNOW",
            7 to "GEN4_SANDSTORM",
            8 to "GEN4_SNOW",
            11 to "GEN4_RAIN2",
            13 to "GEN4_RAIN2",
        )
    val HGSS_TYPES =
        mapOf(
            0 to "UNKNOWN_0x00",
            1 to "ROUTE",
            2 to "ROUTE",
            3 to "UNDERGROUND",
            4 to "INSIDE",
            5 to "INSIDE",
            6 to "UNDERGROUND",
        )
    val SINNOH_TYPES =
        mapOf(
            0 to "UNKNOWN_0x00",
            1 to "ROUTE",
            2 to "ROUTE",
            3 to "UNDERGROUND",
            4 to "INSIDE",
            6 to "UNDERGROUND",
        )
  }
}
