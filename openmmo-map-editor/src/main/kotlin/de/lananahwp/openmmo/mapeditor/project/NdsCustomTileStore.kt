package de.lananahwp.openmmo.mapeditor.project

import de.lananahwp.openmmo.mapeditor.core.NdsMeshSnapshot
import de.lananahwp.openmmo.mapeditor.core.NdsTileset
import de.lananahwp.openmmo.mapeditor.core.NdsTri
import de.lananahwp.openmmo.mapeditor.json.Json
import de.lananahwp.openmmo.mapeditor.json.JsonParser
import de.lananahwp.openmmo.mapeditor.json.JsonWriter
import java.io.File

/**
 * Paintable tiles lifted off map surfaces and stored with one DS project.
 *
 * A tile is a baked snapshot carrying its
 * own textures and palettes, so it depends on neither the ROM nor the decomp it was cut from —
 * a path taken from Platinum still paints onto a HeartGold map perfectly well. Keeping the
 * library under `.openmmo/nds/tiles` makes maps, props, and referenced tiles travel together.
 *
 * Each open project owns its own store and caches.
 */
class NdsCustomTileStore(rootDir: File) {
  /** A tile in the project set. [index] is what a grid persists, so it must never change. */
  data class TileInfo(val index: Int, val name: String, val source: String? = null)

  private var cache: List<TileInfo>? = null
  private val meshCache = HashMap<Int, NdsMeshSnapshot?>()

  var rootDir: File = rootDir
    set(value) {
      field = value
      invalidate()
    }

  fun invalidate() {
    cache = null
    meshCache.clear()
  }

  private fun indexFile(): File = File(rootDir, "tiles.json")

  private fun tileDir(index: Int): File = File(rootDir, "tile-$index")

  /**
   * Every stored tile, in the order they were added.
   *
   * The order and the numbering are append-only: a grid records the tile index, so renumbering
   * would repaint saved maps with whatever tile inherited the number.
   */
  fun tiles(): List<TileInfo> {
    cache?.let { return it }
    val file = indexFile()
    val loaded = if (!file.isFile) emptyList() else try {
      JsonParser.parse(file.readText()).asObj()?.arr("tiles")?.items.orEmpty().mapNotNull { item ->
        val o = item.asObj() ?: return@mapNotNull null
        val index = o.int("index") ?: return@mapNotNull null
        TileInfo(index, o.str("name") ?: "Tile $index", o.str("source"))
      }
    } catch (_: Throwable) {
      emptyList()
    }
    cache = loaded
    return loaded
  }

  /** Stores a picked surface as a new paintable tile. */
  fun add(name: String, snapshot: NdsMeshSnapshot, source: String? = null): TileInfo {
    require(snapshot.triangles.isNotEmpty()) { "That selection has no geometry to add as a tile" }
    val existing = tiles()
    // Never reuse a number, even one whose tile was deleted by hand: saved grids may still name it.
    val index = existing.maxOfOrNull { it.index }?.plus(1) ?: NdsTileset.CUSTOM_TILE_BASE
    val label = name.trim().ifEmpty { "Tile $index" }

    tileDir(index).mkdirs()
    NdsMeshSnapshot.write(File(tileDir(index), "mesh.bin"), restOnGround(snapshot))

    val updated = existing + TileInfo(index, label, source)
    val json = Json.JObj(linkedMapOf(
        "version" to Json.JNum(2.0),
        "tiles" to Json.JArr(updated.map { t ->
          Json.JObj(linkedMapOf(
              "index" to Json.JNum(t.index.toDouble()),
              "name" to Json.JStr(t.name),
          ).also { entries -> t.source?.let { entries["source"] = Json.JStr(it) } })
        }),
    ))
    indexFile().parentFile?.mkdirs()
    indexFile().writeText(JsonWriter.writePretty(json) + "\n")
    cache = updated
    meshCache.remove(index)
    return TileInfo(index, label, source)
  }

  /** The geometry for a tile, in unit-square tile space. */
  fun mesh(index: Int): NdsMeshSnapshot? {
    if (index in meshCache) return meshCache[index]
    val loaded = NdsMeshSnapshot.read(File(tileDir(index), "mesh.bin"))
    meshCache[index] = loaded
    return loaded
  }

  /**
   * Rests a tile on the ground: y=0 at its lowest point, x and z exactly as they arrived.
   *
   * The geometry is already in tile space -- extraction output is in map-tile units, one unit
   * per square, and [NdsProject.SurfaceOrigin.CELL] has put it relative to the square it was cut
   * from. So there is nothing to scale, and this only makes the resting height certain.
   *
   * This used to scale the cut's bounding box to fill a unit square, which was wrong by
   * construction: it resized a tile by however much of its square the geometry happened to
   * cover. A patch left partly bare -- which is what a cliff square becomes once its vertical
   * faces are dropped -- came out magnified, and a near-degenerate sliver came out enormous.
   */
  private fun restOnGround(snapshot: NdsMeshSnapshot): NdsMeshSnapshot {
    val tris = snapshot.triangles
    var minY = Float.MAX_VALUE
    for (t in tris) {
      for (v in floatArrayOf(t.ay, t.by, t.cy)) minY = minOf(minY, v)
    }
    if (minY == 0f) return snapshot
    return NdsMeshSnapshot(
        tris.map { t ->
          t.copy(
              ay = t.ay - minY,
              by = t.by - minY,
              cy = t.cy - minY,
          )
        },
        snapshot.textures,
        snapshot.palettes,
    )
  }

  /** Tile geometry with textures namespaced per tile, ready to hand to a 3D view. */
  fun viewGeometry(): Map<Int, List<NdsTri>> {
    val out = HashMap<Int, List<NdsTri>>()
    for (tile in tiles()) {
      val mesh = mesh(tile.index) ?: continue
      out[tile.index] = mesh.triangles.map { tri ->
        tri.copy(
            texture = if (tri.texture.isEmpty()) "" else texturePrefix(tile.index) + tri.texture,
            palette = if (tri.palette.isEmpty()) "" else texturePrefix(tile.index) + tri.palette,
        )
      }
    }
    return out
  }

  /**
   * Namespace for a tile's textures, mirroring how catalog props are handled, so a tile cut from
   * one map cannot be repainted by a same-named texture belonging to another.
   */
  fun texturePrefix(index: Int): String = "tile$index::"
}
