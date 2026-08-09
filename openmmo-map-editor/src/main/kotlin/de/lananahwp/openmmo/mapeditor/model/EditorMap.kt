package de.lananahwp.openmmo.mapeditor.model

import de.lananahwp.openmmo.mapeditor.json.Json
import de.lananahwp.openmmo.mapeditor.json.JsonWriter

/**
 * One map's editable state: the live map.json object plus its layout. The JSON object is mutated in
 * place and written back as-is on save, so unknown keys (shared_events_map, scripts etc.) survive a
 * round trip. Layout blocks/border live in their .bin files and are held as u16 lists.
 */
class EditorMap(
    val dirName: String,
    val groupName: String,
    val groupIndex: Int,
    val mapIndex: Int,
    val mapJson: Json.JObj,
    val layout: EditorLayout,
) {
  val id: String get() = mapJson.str("id") ?: dirName
  val name: String get() = mapJson.str("name") ?: dirName
  val mapType: String get() = mapJson.str("map_type") ?: "MAP_TYPE_INDOOR"
  val music: String get() = mapJson.str("music") ?: "MUS_NONE"
  val mapsec: String get() = mapJson.str("region_map_section") ?: "MAPSEC_NONE"
  val weather: String get() = mapJson.str("weather") ?: "WEATHER_NONE"
  val requiresFlash: Boolean get() = mapJson.get("requires_flash")?.asBool() ?: false

  val warps: List<Json.JObj>
    get() = (mapJson.arr("warp_events")?.items ?: emptyList()).mapNotNull { it.asObj() }
  val objects: List<Json.JObj>
    get() = (mapJson.arr("object_events")?.items ?: emptyList()).mapNotNull { it.asObj() }
  val coordEvents: List<Json.JObj>
    get() = (mapJson.arr("coord_events")?.items ?: emptyList()).mapNotNull { it.asObj() }
  val bgEvents: List<Json.JObj>
    get() = (mapJson.arr("bg_events")?.items ?: emptyList()).mapNotNull { it.asObj() }
  val connections: List<Json.JObj>
    get() = (mapJson.arr("connections")?.items ?: emptyList()).mapNotNull { it.asObj() }

  /** Writes [mapJson] back to the project's map.json. */
  fun toJsonString(): String = JsonWriter.write(mapJson)

  fun set(idKey: String, value: Json) {
    mapJson.entries[idKey] = value
  }
}

/**
 * A map layout: dimension, tilesets and the two u16 arrays that live in the layout's .bin files.
 * [layoutJson] is the live layouts.json entry so width/height/tilesets edits persist on save.
 */
class EditorLayout(
    val name: String,
    val layoutJson: Json.JObj,
    val blocks: MutableList<Int>,
    val border: MutableList<Int>,
) {
  var width: Int
    get() = layoutJson.int("width") ?: 20
    set(v) {
      layoutJson.entries["width"] = Json.JNum(v.toDouble())
    }
  var height: Int
    get() = layoutJson.int("height") ?: 15
    set(v) {
      layoutJson.entries["height"] = Json.JNum(v.toDouble())
    }
  var primaryTileset: String
    get() = layoutJson.str("primary_tileset") ?: ""
    set(v) {
      layoutJson.entries["primary_tileset"] = Json.JStr(v)
    }
  var secondaryTileset: String
    get() = layoutJson.str("secondary_tileset") ?: ""
    set(v) {
      layoutJson.entries["secondary_tileset"] = Json.JStr(v)
    }

  fun tileAt(x: Int, y: Int): Int? =
      if (x in 0 until width && y in 0 until height) blocks.getOrNull(y * width + x) else null

  fun setTile(x: Int, y: Int, metatileId: Int) {
    val i = y * width + x
    if (x in 0 until width && y in 0 until height && i < blocks.size) blocks[i] = metatileId
  }

  fun resize(newWidth: Int, newHeight: Int, fill: Int) {
    if (newWidth <= 0 || newHeight <= 0 || (newWidth == width && newHeight == height)) return
    val out = ArrayList<Int>(newWidth * newHeight)
    for (y in 0 until newHeight) {
      for (x in 0 until newWidth) {
        out.add(if (x < width && y < height) blocks[y * width + x] else fill)
      }
    }
    blocks.clear()
    blocks.addAll(out)
    width = newWidth
    height = newHeight
  }
}

object EventField {
  fun getInt(e: Json.JObj, key: String): Int = e.int(key) ?: 0
  fun getStr(e: Json.JObj, key: String): String = e.str(key) ?: "0"
}
