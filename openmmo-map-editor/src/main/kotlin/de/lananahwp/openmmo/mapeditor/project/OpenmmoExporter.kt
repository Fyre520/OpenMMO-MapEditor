package de.lananahwp.openmmo.mapeditor.project

import de.lananahwp.openmmo.mapeditor.core.DIR_MAP
import de.lananahwp.openmmo.mapeditor.core.ENCOUNTER_MAP
import de.lananahwp.openmmo.mapeditor.core.ENCOUNTER_METHODS
import de.lananahwp.openmmo.mapeditor.core.MAP_TYPE_MAP
import de.lananahwp.openmmo.mapeditor.core.WEATHER_MAP
import de.lananahwp.openmmo.mapeditor.json.Json
import de.lananahwp.openmmo.mapeditor.json.JsonParser
import de.lananahwp.openmmo.mapeditor.json.JsonWriter
import de.lananahwp.openmmo.mapeditor.model.EditorMap
import java.io.File
import java.util.Base64

/** Exports decomp maps for OpenMMO's runtime loader. */
class OpenmmoExporter(private val project: DecompProject) {

  private val region get() = project.region
  private val tables get() = project.tables

  private data class PFrameScript(val varKey: String, val value: Int, val script: String)

  private class EncounterData(
      val weightsByMethod: Map<String, List<Int>>,
      val byMap: Map<String, Json.JObj>,
  )

  private val encounterData: EncounterData by lazy { readEncounters() }
  private val mapJsonCache = HashMap<String, Json.JObj>()

  private fun readEncounters(): EncounterData {
    val file = File(project.rootDir, "src/data/wild_encounters.json")
    if (!file.exists()) return EncounterData(emptyMap(), emptyMap())
    val group =
        JsonParser.parse(file.readText())
            .asObj()
            ?.arr("wild_encounter_groups")
            ?.items
            ?.firstOrNull()
            ?.asObj() ?: return EncounterData(emptyMap(), emptyMap())
    val weights =
        group.arr("fields")?.items.orEmpty().mapNotNull { field ->
          val obj = field.asObj() ?: return@mapNotNull null
          val type = obj.str("type") ?: return@mapNotNull null
          val rates = obj.arr("encounter_rates")?.items?.mapNotNull { it.asInt() } ?: return@mapNotNull null
          type to rates
        }
    val byMap =
        group.arr("encounters")?.items.orEmpty().mapNotNull { entry ->
          val obj = entry.asObj() ?: return@mapNotNull null
          val mapId = obj.str("map") ?: return@mapNotNull null
          mapId to obj
        }
    return EncounterData(weights.toMap(), byMap.toMap())
  }

  private fun readMapJson(dirName: String): Json.JObj? =
      mapJsonCache.getOrPut(dirName) {
        val f = File(project.rootDir, "data/maps/$dirName/map.json")
        if (!f.exists()) Json.JObj(linkedMapOf())
        else JsonParser.parse(f.readText()).asObj() ?: Json.JObj(linkedMapOf())
      }

  fun objectNameFor(map: EditorMap): String = identifier(map.dirName)

  /** Renders one map in OpenMMO's runtime JSON format. */
  fun renderMap(map: EditorMap): String {
    val root =
        obj(
            "regionId" to num(region.regionId),
            "bankId" to num(project.wireBank(map.exportGroupIndex)),
            "mapId" to num(map.exportMapIndex),
            "width" to num(map.layout.width),
            "height" to num(map.layout.height),
            "paletteIdx1" to num(tilesetPaletteId(map.layout.primaryTileset, 80)),
            "paletteIdx2" to num(tilesetPaletteId(map.layout.secondaryTileset, 82)),
            "borderWidth" to num(2),
            "borderHeight" to num(2),
            "unknownShort" to num(tables.musicIds[map.music] ?: 405),
            "unknownByte" to num(tables.mapsecIds[map.mapsec] ?: 0),
            "borderTiles" to borderTilesJson(map),
            "blockData" to str(blockData(map)),
            "behaviorData" to str(behaviorData(map)),
            "wildEncounters" to encountersJson(map),
            "lighting" to str("REGULAR"),
            "weather" to str(enumName(WEATHER_MAP[map.weather] ?: "Weather.REGULAR_WEATHER")),
            "mapType" to str(enumName(MAP_TYPE_MAP[map.mapType] ?: "MapType.INSIDE")),
            "encounterType" to
                str(enumName(ENCOUNTER_MAP[map.mapType] ?: "EncounterType.RANDOM")),
            "connections" to connectionsJson(map),
            "warps" to warpsJson(map),
            "npcs" to npcsJson(map),
            "bgEvents" to bgEventsJson(map),
            "onTransitionScript" to str(onTransitionScript(map)),
            "onFrameScripts" to frameScriptsJson(map),
            "coordScripts" to coordScriptsJson(map),
            "downloadData" to Json.JBool(true),
        )
    return JsonWriter.writePretty(root) + "\n"
  }

  /** Writes one runtime map under [outputDir]. */
  fun exportMap(map: EditorMap, outputDir: File): File {
    val target =
        File(
            outputDir,
            "${identifier(region.name)}/${groupSegment(map.groupName)}/${objectNameFor(map)}.json",
        )
    target.parentFile.mkdirs()
    target.writeText(renderMap(map), Charsets.UTF_8)
    return target
  }

  /** Writes every runtime map. */
  fun exportAll(outputDir: File): List<File> {
    val selected = LinkedHashMap<Triple<Int, Int, Int>, EditorMap>()
    for (groupName in project.groupOrder) {
      val maps = project.groupMaps[groupName] ?: continue
      for (dirName in maps) {
        val map = project.loadMap(dirName) ?: continue
        val key = Triple(region.regionId, map.exportGroupIndex, map.exportMapIndex)
        val existing = selected[key]
        when {
          existing == null -> selected[key] = map
          map.isRuntimeOverride && !existing.isRuntimeOverride -> selected[key] = map
          map.isRuntimeOverride && existing.isRuntimeOverride ->
              error("Multiple runtime overrides target ${project.wireBank(map.exportGroupIndex)}:${map.exportMapIndex}")
        }
      }
    }
    return selected.values.map { exportMap(it, outputDir) }
  }

  private fun tilesetPaletteId(name: String, fallback: Int): Int =
      if (name.isEmpty()) fallback else tables.tilesetPaletteIds[name] ?: fallback

  private fun borderTilesJson(map: EditorMap): Json.JArr {
    val tiles = map.layout.border.take(4).toMutableList()
    while (tiles.size < 4) tiles += tiles.lastOrNull() ?: 8
    return arr(tiles.map { obj("material" to num(it), "collision" to num(0)) })
  }

  private fun encountersJson(map: EditorMap): Json.JArr {
    val entry = encounterData.byMap[map.sourceMapId ?: map.id] ?: return arr(emptyList())
    val result =
        ENCOUNTER_METHODS.mapNotNull { (jsonKey, methodRef) ->
          val table = entry.obj(jsonKey) ?: return@mapNotNull null
          val weights = encounterData.weightsByMethod[jsonKey].orEmpty()
          val slots =
              table.arr("mons")?.items.orEmpty().mapIndexedNotNull { index, mon ->
                val value = mon.asObj() ?: return@mapIndexedNotNull null
                val species = value.str("species") ?: return@mapIndexedNotNull null
                val internalId = tables.speciesIds[species] ?: return@mapIndexedNotNull null
                val speciesId = tables.nationalDex[internalId] ?: return@mapIndexedNotNull null
                obj(
                    "speciesId" to num(speciesId),
                    "minLevel" to num(value.int("min_level") ?: 1),
                    "maxLevel" to num(value.int("max_level") ?: 1),
                    "weight" to num(weights.getOrElse(index) { 1 }),
                )
              }
          if (slots.isEmpty()) null
          else
              obj(
                  "method" to str(enumName(methodRef)),
                  "encounterRate" to num(table.int("encounter_rate") ?: 0),
                  "slots" to arr(slots),
              )
        }
    return arr(result)
  }

  private fun connectionsJson(map: EditorMap): Json.JArr =
      arr(
          map.connections.mapNotNull { connection ->
            val directionName = connection.str("direction") ?: return@mapNotNull null
            val direction = DIR_MAP[directionName] ?: error("Unknown direction '$directionName'")
            val targetName = connection.str("map") ?: return@mapNotNull null
            val target = project.addressOf(targetName) ?: return@mapNotNull null
            obj(
                "direction" to str(enumName(direction)),
                "unknown" to num(connection.int("offset") ?: 0),
                "targetBank" to num(project.wireBank(target.groupIndex)),
                "targetMap" to num(target.mapIndex),
            )
          })

  private fun warpsJson(map: EditorMap): Json.JArr =
      arr(
          map.warps.mapNotNull { warp ->
            val destinationName = warp.str("dest_map") ?: return@mapNotNull null
            val x = warp.int("x") ?: 0
            val y = warp.int("y") ?: 0
            val elevation = ((warp.int("elevation") ?: 0) - 1).coerceAtLeast(0)
            if (destinationName == "MAP_DYNAMIC") {
              warpJson(x, y, elevation, 0, 0, 0, 0, 0, 0, true)
            } else {
              val destination = project.addressOf(destinationName) ?: return@mapNotNull null
              val warpId = warp.int("dest_warp_id") ?: warp.str("dest_warp_id")?.toIntOrNull() ?: 0
              val target =
                  readMapJson(destination.mapDirName)
                      ?.arr("warp_events")
                      ?.items
                      ?.getOrNull(warpId)
                      ?.asObj()
              warpJson(
                  x,
                  y,
                  elevation,
                  region.regionId,
                  project.wireBank(destination.groupIndex),
                  destination.mapIndex,
                  target?.int("x") ?: x,
                  target?.int("y") ?: y,
                  ((target?.int("elevation") ?: 0) - 1).coerceAtLeast(0),
                  false,
              )
            }
          })

  private fun warpJson(
      x: Int,
      y: Int,
      elevation: Int,
      targetRegion: Int,
      targetBank: Int,
      targetMap: Int,
      targetX: Int,
      targetY: Int,
      targetElevation: Int,
      dynamic: Boolean,
  ): Json.JObj =
      obj(
          "x" to num(x),
          "y" to num(y),
          "elevation" to num(elevation),
          "targetRegionId" to num(targetRegion),
          "targetBankId" to num(targetBank),
          "targetMapId" to num(targetMap),
          "targetX" to num(targetX),
          "targetY" to num(targetY),
          "targetElevation" to num(targetElevation),
          "dynamic" to Json.JBool(dynamic),
      )

  private fun npcsJson(map: EditorMap): Json.JArr {
    val visibleOverride = region.defaultVisibleNpcs[map.sourceDirName]
    return arr(
        map.objects.mapIndexed { index, npc ->
          val flag = npc.str("flag") ?: "0"
          val shown = visibleOverride?.let { index in it } ?: (flag == "0")
          val graphics = npc.str("graphics_id") ?: "OBJ_EVENT_GFX_BOY_1"
          val movement = npc.str("movement_type") ?: "MOVEMENT_TYPE_NONE"
          obj(
              "entityIdx" to num(index),
              "graphicsId" to num(tables.gfxIds[graphics] ?: 0),
              "x" to num(npc.int("x") ?: 0),
              "y" to num(npc.int("y") ?: 0),
              "elevation" to num(npc.int("elevation") ?: 3),
              "movementType" to str(enumName(tables.movementTypes.ref(movement))),
              "movementRangeX" to num(npc.int("movement_range_x") ?: 0),
              "movementRangeY" to num(npc.int("movement_range_y") ?: 0),
              "trainerType" to num(if (npc.str("trainer_type") == "TRAINER_TYPE_NONE") 0 else 1),
              "facing" to str(enumName(tables.movementTypes.facingRef(movement))),
              "script" to str(npc.str("script") ?: "0x0"),
              "hideFlag" to str(if (shown) "" else "${region.name}/$flag"),
          )
        })
  }

  private fun bgEventsJson(map: EditorMap): Json.JArr =
      arr(
          map.bgEvents.map {
            obj(
                "x" to num(it.int("x") ?: 0),
                "y" to num(it.int("y") ?: 0),
                "facingDir" to str(it.str("player_facing_dir") ?: "BG_EVENT_PLAYER_FACING_ANY"),
                "script" to str(it.str("script") ?: "0x0"),
            )
          })

  private fun frameScriptsJson(map: EditorMap): Json.JArr =
      arr(
          parseFrameScripts(map).map {
            obj("varKey" to str(it.varKey), "value" to num(it.value), "script" to str(it.script))
          })

  private fun coordScriptsJson(map: EditorMap): Json.JArr =
      arr(
          map.coordEvents.mapNotNull { event ->
            if (event.str("type") != "trigger") return@mapNotNull null
            val variable = event.str("var") ?: return@mapNotNull null
            val value =
                event.str("var_value")?.toIntOrNull()
                    ?: event.int("var_value")
                    ?: return@mapNotNull null
            val script = event.str("script") ?: return@mapNotNull null
            obj(
                "x" to num(event.int("x") ?: 0),
                "y" to num(event.int("y") ?: 0),
                "elevation" to num(event.int("elevation") ?: 0),
                "varKey" to str("${region.name}/$variable"),
                "value" to num(value),
                "script" to str(script),
            )
          })

  private fun blockData(map: EditorMap): String {
    val path = map.layout.layoutJson.str("blockdata_filepath") ?: return ""
    val file = File(project.rootDir, path)
    if (!file.exists()) return ""
    return Base64.getEncoder().encodeToString(file.readBytes())
  }

  private fun behaviorData(map: EditorMap): String {
    val path = map.layout.layoutJson.str("blockdata_filepath") ?: return ""
    val file = File(project.rootDir, path)
    if (!file.exists()) return ""
    val primary = map.layout.primaryTileset
    val secondary = map.layout.secondaryTileset
    val pOrd = if (primary.isNotEmpty()) project.source.behaviorOrdinals(primary) else IntArray(0)
    val sOrd = if (secondary.isNotEmpty()) project.source.behaviorOrdinals(secondary) else IntArray(0)
    return project.source.behaviorTable
        .behaviorData(project.source.primaryMetatileCount, pOrd, sOrd, file.readBytes())
  }

  private fun onTransitionScript(map: EditorMap): String {
    val file = File(project.rootDir, "data/maps/${map.sourceDirName}/scripts.inc")
    if (!file.exists()) return ""
    val re = Regex("""map_script\s+MAP_SCRIPT_ON_TRANSITION\s*,\s*(\w+)""")
    return re.find(file.readText())?.groupValues?.get(1) ?: ""
  }

  private fun parseFrameScripts(map: EditorMap): List<PFrameScript> {
    val file = File(project.rootDir, "data/maps/${map.sourceDirName}/scripts.inc")
    if (!file.exists()) return emptyList()
    val text = file.readText()
    val tableLabel =
        Regex("""map_script\s+MAP_SCRIPT_ON_FRAME_TABLE\s*,\s*(\w+)""")
            .find(text)
            ?.groupValues
            ?.get(1) ?: return emptyList()
    val block =
        Regex("""(?m)^$tableLabel:\s*\n(.*?)(?=\n\s*\.2byte)""", RegexOption.DOT_MATCHES_ALL)
            .find(text)
            ?.groupValues
            ?.get(1) ?: return emptyList()
    val entry = Regex("""map_script_2\s+(\w+)\s*,\s*(\d+)\s*,\s*(\w+)""")
    return entry
        .findAll(block)
        .map {
          PFrameScript(
              varKey = "${region.name}/${it.groupValues[1]}",
              value = it.groupValues[2].toInt(),
              script = it.groupValues[3],
          )
        }
        .toList()
  }

  private fun groupSegment(groupName: String): String {
    val stripped = groupName.removePrefix("gMapGroup_")
    val sanitized = stripped.filter { it.isLetterOrDigit() }.lowercase()
    return sanitized.ifEmpty { "misc" }
  }

  private fun identifier(name: String): String {
    val sanitized = name.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")
    return if (sanitized.firstOrNull()?.isDigit() == true) "_$sanitized" else sanitized
  }

  private fun enumName(reference: String): String = reference.substringAfterLast('.')

  private fun num(value: Int): Json.JNum = Json.JNum(value.toDouble())

  private fun str(value: String): Json.JStr = Json.JStr(value)

  private fun arr(items: List<Json>): Json.JArr = Json.JArr(items)

  private fun obj(vararg entries: Pair<String, Json>): Json.JObj =
      Json.JObj(linkedMapOf(*entries))
}
