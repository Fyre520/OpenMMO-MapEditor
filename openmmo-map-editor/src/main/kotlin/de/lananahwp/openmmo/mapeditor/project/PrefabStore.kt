package de.lananahwp.openmmo.mapeditor.project

import de.lananahwp.openmmo.mapeditor.json.Json
import de.lananahwp.openmmo.mapeditor.json.JsonParser
import de.lananahwp.openmmo.mapeditor.json.JsonWriter
import de.lananahwp.openmmo.mapeditor.model.MetatileBrush
import java.io.File
import java.util.UUID

data class MapPrefab(
    val id: String,
    val name: String,
    val primaryTileset: String,
    val secondaryTileset: String,
    val brush: MetatileBrush,
)

class PrefabStore(private val projectDir: File) {
  private val file = File(projectDir, "prefabs.json")
  val items = mutableListOf<MapPrefab>()

  init {
    load(file, false)
  }

  fun compatible(primary: String, secondary: String): List<MapPrefab> =
      items.filter {
        (it.primaryTileset.isEmpty() || it.primaryTileset == primary) &&
            (it.secondaryTileset.isEmpty() || it.secondaryTileset == secondary)
      }

  fun add(prefab: MapPrefab) {
    items += prefab
    save()
  }

  fun remove(id: String) {
    items.removeAll { it.id == id }
    save()
  }

  fun import(source: File): Int {
    val before = items.size
    load(source, true)
    save()
    return items.size - before
  }

  private fun load(source: File, append: Boolean) {
    if (!source.isFile) return
    val root = JsonParser.parse(source.readText()).asArr() ?: return
    if (!append) items.clear()
    for (value in root.items) parsePrefab(value.asObj() ?: continue)?.let(items::add)
  }

  private fun parsePrefab(obj: Json.JObj): MapPrefab? {
    val width = obj.int("width") ?: return null
    val height = obj.int("height") ?: return null
    if (width <= 0 || height <= 0) return null
    val blocks = IntArray(width * height) { -1 }
    for (value in obj.arr("metatiles")?.items.orEmpty()) {
      val tile = value.asObj() ?: continue
      val x = tile.int("x") ?: continue
      val y = tile.int("y") ?: continue
      if (x !in 0 until width || y !in 0 until height) continue
      val id = tile.int("metatile_id") ?: continue
      val collision = tile.int("collision") ?: 0
      val elevation = tile.int("elevation") ?: 0
      blocks[y * width + x] = id or (collision shl 10) or (elevation shl 12)
    }
    return MapPrefab(
        UUID.randomUUID().toString(),
        obj.str("name").orEmpty(),
        obj.str("primary_tileset").orEmpty(),
        obj.str("secondary_tileset").orEmpty(),
        MetatileBrush(width, height, blocks, true),
    )
  }

  private fun save() {
    val values = items.map(::toJson)
    file.writeText(JsonWriter.writePretty(Json.JArr(values)) + "\n")
  }

  private fun toJson(prefab: MapPrefab): Json {
    val tiles = mutableListOf<Json>()
    for (y in 0 until prefab.brush.height) {
      for (x in 0 until prefab.brush.width) {
        val block = prefab.brush.blockAt(x, y)
        if (block < 0) continue
        tiles +=
            Json.JObj(
                linkedMapOf(
                    "x" to Json.JNum(x.toDouble()),
                    "y" to Json.JNum(y.toDouble()),
                    "metatile_id" to Json.JNum((block and 0x3FF).toDouble()),
                    "collision" to Json.JNum(((block shr 10) and 0x3).toDouble()),
                    "elevation" to Json.JNum(((block shr 12) and 0xF).toDouble()),
                ))
      }
    }
    return Json.JObj(
        linkedMapOf(
            "name" to Json.JStr(prefab.name),
            "width" to Json.JNum(prefab.brush.width.toDouble()),
            "height" to Json.JNum(prefab.brush.height.toDouble()),
            "primary_tileset" to Json.JStr(prefab.primaryTileset),
            "secondary_tileset" to Json.JStr(prefab.secondaryTileset),
            "metatiles" to Json.JArr(tiles),
        ))
  }
}
