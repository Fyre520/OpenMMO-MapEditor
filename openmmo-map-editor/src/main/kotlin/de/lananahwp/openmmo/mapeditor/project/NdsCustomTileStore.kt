package de.lananahwp.openmmo.mapeditor.project

import de.lananahwp.openmmo.mapeditor.core.NdsMeshSnapshot
import de.lananahwp.openmmo.mapeditor.core.NdsTileset
import de.lananahwp.openmmo.mapeditor.core.NdsTri
import de.lananahwp.openmmo.mapeditor.json.Json
import de.lananahwp.openmmo.mapeditor.json.JsonParser
import de.lananahwp.openmmo.mapeditor.json.JsonWriter
import java.io.File

/**
 * Paintable tiles lifted off map surfaces, shared by every project the editor has open.
 *
 * Deliberately not per-project, unlike the prop catalog. A tile is a baked snapshot carrying its
 * own textures and palettes, so it depends on neither the ROM nor the decomp it was cut from —
 * a path taken from Platinum paints onto a HeartGold map perfectly well, and that cross-map reuse
 * is the entire point. Kept in the user's home directory rather than inside a decomp so that it
 * survives switching projects and never writes into a checkout.
 *
 * A single store rather than per-project state also keeps the caches from diverging: several
 * projects are open at once, and a tile added while one map is loaded has to be visible from the
 * next map regardless of which project that belongs to.
 */
object NdsCustomTileStore {
  /** A tile in the shared set. [index] is what a grid persists, so it must never change. */
  data class TileInfo(val index: Int, val name: String)

  private var cache: List<TileInfo>? = null
  private val meshCache = HashMap<Int, NdsMeshSnapshot?>()

  /** Overridable so tests do not touch the real user directory. */
  var rootDir: File = File(System.getProperty("user.home"), ".openmmo/tiles")
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
        TileInfo(index, o.str("name") ?: "Tile $index")
      }
    } catch (_: Throwable) {
      emptyList()
    }
    cache = loaded
    return loaded
  }

  /** Stores a picked surface as a new paintable tile. */
  fun add(name: String, snapshot: NdsMeshSnapshot): TileInfo {
    require(snapshot.triangles.isNotEmpty()) { "That selection has no geometry to add as a tile" }
    val existing = tiles()
    // Never reuse a number, even one whose tile was deleted by hand: saved grids may still name it.
    val index = existing.maxOfOrNull { it.index }?.plus(1) ?: NdsTileset.CUSTOM_TILE_BASE
    val label = name.trim().ifEmpty { "Tile $index" }

    tileDir(index).mkdirs()
    NdsMeshSnapshot.write(File(tileDir(index), "mesh.bin"), normalise(snapshot))

    val updated = existing + TileInfo(index, label)
    val json = Json.JObj(linkedMapOf(
        "version" to Json.JNum(1.0),
        "tiles" to Json.JArr(updated.map { t ->
          Json.JObj(linkedMapOf(
              "index" to Json.JNum(t.index.toDouble()),
              "name" to Json.JStr(t.name),
          ))
        }),
    ))
    indexFile().parentFile?.mkdirs()
    indexFile().writeText(JsonWriter.writePretty(json) + "\n")
    cache = updated
    meshCache.remove(index)
    return TileInfo(index, label)
  }

  /** The geometry for a tile, in unit-square tile space. */
  fun mesh(index: Int): NdsMeshSnapshot? {
    if (index in meshCache) return meshCache[index]
    val loaded = NdsMeshSnapshot.read(File(tileDir(index), "mesh.bin"))
    meshCache[index] = loaded
    return loaded
  }

  /**
   * Moves a snapshot into tile space: spanning x and z in 0..1, resting on y=0.
   *
   * An extracted square arrives centred on its own origin at whatever size it was cut at, so
   * without this a tile would paint offset from its cell and at the source map's scale.
   */
  private fun normalise(snapshot: NdsMeshSnapshot): NdsMeshSnapshot {
    val tris = snapshot.triangles
    var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
    for (t in tris) {
      for (v in floatArrayOf(t.ax, t.bx, t.cx)) { minX = minOf(minX, v); maxX = maxOf(maxX, v) }
      for (v in floatArrayOf(t.ay, t.by, t.cy)) { minY = minOf(minY, v) }
      for (v in floatArrayOf(t.az, t.bz, t.cz)) { minZ = minOf(minZ, v); maxZ = maxOf(maxZ, v) }
    }
    // Uniform scale off the wider side keeps a not-quite-square cut from being stretched.
    val scale = 1f / maxOf((maxX - minX).coerceAtLeast(1e-4f), (maxZ - minZ).coerceAtLeast(1e-4f))
    fun mx(v: Float) = (v - minX) * scale
    fun my(v: Float) = (v - minY) * scale
    fun mz(v: Float) = (v - minZ) * scale
    return NdsMeshSnapshot(
        tris.map { t ->
          t.copy(
              ax = mx(t.ax), ay = my(t.ay), az = mz(t.az),
              bx = mx(t.bx), by = my(t.by), bz = mz(t.bz),
              cx = mx(t.cx), cy = my(t.cy), cz = mz(t.cz),
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
