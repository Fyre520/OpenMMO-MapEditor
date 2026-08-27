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
  /** A tile/stamp in the project set. [index] is what a grid persists, so it must never change. */
  data class TileInfo(
      val index: Int,
      val name: String,
      val source: String? = null,
      val width: Int = 1,
      val height: Int = 1,
      /** Transparent surface detail that is placed above, rather than replacing, ground. */
      val overlay: Boolean = false,
      /** Generator/runtime building block retained for saved maps but omitted from the picker. */
      val hidden: Boolean = false,
  )

  /** Opaque recovery record returned when tiles are moved out of the live catalog. */
  class Archive internal constructor(
      internal val removed: List<TileInfo>,
      internal val movedDirectories: List<Pair<File, File>>,
  )

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

  private fun legacyTileDir(index: Int): File = File(rootDir, "tile-$index")

  private fun namedTileDir(index: Int, name: String): File {
    val suffix = name.trim()
        .replace(Regex("[^A-Za-z0-9]+"), "_")
        .trim('_')
        .ifEmpty { "Tile" }
    return File(rootDir, "tile-${index}_$suffix")
  }

  private fun tileDirs(index: Int): List<File> {
    val legacyName = "tile-$index"
    val namedPrefix = "${legacyName}_"
    return rootDir.listFiles().orEmpty().filter {
      it.isDirectory && (it.name == legacyName || it.name.startsWith(namedPrefix))
    }
  }

  private fun existingTileDir(index: Int, preferredName: String? = null): File? {
    preferredName?.let { name -> namedTileDir(index, name).takeIf(File::isDirectory)?.let { return it } }
    legacyTileDir(index).takeIf(File::isDirectory)?.let { return it }
    return tileDirs(index).sortedBy { it.name }.firstOrNull()
  }

  /** The next unused custom-tile code offered by the editor. */
  fun nextAvailableIndex(): Int {
    var candidate = (tiles().maxOfOrNull { it.index }?.plus(1))
        ?: NdsTileset.CUSTOM_TILE_BASE
    candidate = maxOf(candidate, NdsTileset.CUSTOM_TILE_BASE)
    while (existingTileDir(candidate) != null) {
      check(candidate < Int.MAX_VALUE) { "No custom tile codes remain" }
      candidate++
    }
    return candidate
  }

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
        TileInfo(
            index,
            o.str("name") ?: "Tile $index",
            o.str("source"),
            o.int("width")?.coerceAtLeast(1) ?: 1,
            o.int("height")?.coerceAtLeast(1) ?: 1,
            o.get("overlay")?.asBool() ?: false,
            o.get("hidden")?.asBool() ?: false,
        )
      }
    } catch (_: Throwable) {
      emptyList()
    }
    cache = loaded
    return loaded
  }

  /** Stores a picked surface as a new paintable tile. */
  fun add(
      name: String,
      snapshot: NdsMeshSnapshot,
      source: String? = null,
      width: Int = 1,
      height: Int = 1,
      overlay: Boolean = false,
      hidden: Boolean = false,
      requestedIndex: Int? = null,
  ): TileInfo {
    require(snapshot.triangles.isNotEmpty()) { "That selection has no geometry to add as a tile" }
    require(width >= 1 && height >= 1) { "Tile footprint must be at least 1x1" }
    val existing = tiles()
    val index = requestedIndex ?: nextAvailableIndex()
    require(index >= NdsTileset.CUSTOM_TILE_BASE) {
      "Custom tile code must be ${NdsTileset.CUSTOM_TILE_BASE} or higher"
    }
    val occupyingTile = existing.firstOrNull { it.index == index }
    require(occupyingTile == null) {
      "Tile code $index is already used by '${occupyingTile?.name}'"
    }
    require(existingTileDir(index) == null) {
      "Tile code $index already has stored tile data"
    }
    val label = name.trim().ifEmpty { "Tile $index" }
    val destination = namedTileDir(index, label)

    destination.mkdirs()
    NdsMeshSnapshot.write(File(destination, "mesh.bin"), restOnGround(snapshot))

    val updated = existing + TileInfo(index, label, source, width, height, overlay, hidden)
    writeIndex(updated)
    cache = updated
    meshCache.remove(index)
    return TileInfo(index, label, source, width, height, overlay, hidden)
  }

  /** Replaces one generated tile without changing the stable index already used by maps. */
  fun replace(
      index: Int,
      name: String,
      snapshot: NdsMeshSnapshot,
      overlay: Boolean? = null,
      width: Int? = null,
      height: Int? = null,
      hidden: Boolean? = null,
  ): TileInfo {
    require(snapshot.triangles.isNotEmpty()) { "Replacement tile has no geometry" }
    val existing = tiles()
    val old = existing.firstOrNull { it.index == index } ?: error("Unknown tile $index")
    val replacement = old.copy(
        name = name.trim().ifEmpty { old.name },
        overlay = overlay ?: old.overlay,
        width = width?.coerceAtLeast(1) ?: old.width,
        height = height?.coerceAtLeast(1) ?: old.height,
        hidden = hidden ?: old.hidden,
    )
    val destination = existingTileDir(index, old.name) ?: namedTileDir(index, replacement.name)
    destination.mkdirs()
    NdsMeshSnapshot.write(File(destination, "mesh.bin"), restOnGround(snapshot))
    val readableDestination = namedTileDir(index, replacement.name)
    if (destination != readableDestination && !readableDestination.exists()) {
      destination.renameTo(readableDestination)
    }
    val updated = existing.map { if (it.index == index) replacement else it }
    writeIndex(updated)
    cache = updated
    meshCache.remove(index)
    return replacement
  }

  /** Removes exactly the named generated tile indices, leaving every other stable index intact. */
  fun remove(indices: Set<Int>) {
    if (indices.isEmpty()) return
    val existing = tiles()
    val known = existing.map { it.index }.toSet()
    require(indices.all { it in known }) { "Cannot remove unknown tile indices" }
    for (index in indices) tileDirs(index).forEach(File::deleteRecursively)
    val updated = existing.filterNot { it.index in indices }
    writeIndex(updated)
    cache = updated
    indices.forEach(meshCache::remove)
  }

  /**
   * Removes tiles without destroying their files, so a Clear Assets operation can be undone.
   * [backupRoot] must be unique to this operation.
   */
  fun archive(indices: Set<Int>, backupRoot: File): Archive {
    if (indices.isEmpty()) return Archive(emptyList(), emptyList())
    val existing = tiles()
    val removed = existing.filter { it.index in indices }
    require(removed.size == indices.size) { "Cannot archive unknown tile indices" }
    val moves = mutableListOf<Pair<File, File>>()
    try {
      backupRoot.mkdirs()
      for (tile in removed) {
        val directories = tileDirs(tile.index)
        for (source in directories) {
          val destination = File(backupRoot, source.name)
          require(!destination.exists()) { "Recovery folder already contains ${source.name}" }
          moveDirectory(source, destination)
          moves += source to destination
        }
      }
      val updated = existing.filterNot { it.index in indices }
      writeIndex(updated)
      cache = updated
      indices.forEach(meshCache::remove)
      return Archive(removed, moves)
    } catch (failure: Throwable) {
      for ((original, backup) in moves.asReversed()) {
        if (backup.exists() && !original.exists()) runCatching { moveDirectory(backup, original) }
      }
      throw failure
    }
  }

  /** Restores a previous [archive] operation, including its stable tile codes. */
  fun restore(archive: Archive) {
    if (archive.removed.isEmpty()) return
    val existing = tiles()
    val restoringIndices = archive.removed.map { it.index }.toSet()
    require(existing.none { it.index in restoringIndices }) {
      "A deleted tile code has been reused and cannot be restored"
    }
    require(archive.movedDirectories.all { (original, backup) ->
      !original.exists() && backup.isDirectory
    }) { "Some deleted tile data can no longer be restored" }

    val restoredMoves = mutableListOf<Pair<File, File>>()
    try {
      for ((original, backup) in archive.movedDirectories) {
        moveDirectory(backup, original)
        restoredMoves += original to backup
      }
      val updated = (existing + archive.removed).sortedBy { it.index }
      writeIndex(updated)
      cache = updated
      restoringIndices.forEach(meshCache::remove)
    } catch (failure: Throwable) {
      for ((original, backup) in restoredMoves.asReversed()) {
        if (original.exists() && !backup.exists()) runCatching { moveDirectory(original, backup) }
      }
      throw failure
    }
  }

  private fun moveDirectory(source: File, destination: File) {
    destination.parentFile?.mkdirs()
    if (source.renameTo(destination)) return
    source.copyRecursively(destination, overwrite = false)
    check(source.deleteRecursively()) { "Could not remove ${source.path} after copying it" }
  }

  private fun writeIndex(updated: List<TileInfo>) {
    val json = Json.JObj(linkedMapOf(
        "version" to Json.JNum(5.0),
        "tiles" to Json.JArr(updated.map { t ->
          Json.JObj(linkedMapOf(
              "index" to Json.JNum(t.index.toDouble()),
              "name" to Json.JStr(t.name),
            "width" to Json.JNum(t.width.toDouble()),
            "height" to Json.JNum(t.height.toDouble()),
            "overlay" to Json.JBool(t.overlay),
            "hidden" to Json.JBool(t.hidden),
          ).also { entries -> t.source?.let { entries["source"] = Json.JStr(it) } })
        }),
    ))
    indexFile().parentFile?.mkdirs()
    indexFile().writeText(JsonWriter.writePretty(json) + "\n")
  }

  /** The geometry for a tile, in unit-square tile space. */
  fun mesh(index: Int): NdsMeshSnapshot? {
    if (index in meshCache) return meshCache[index]
    val tile = tiles().firstOrNull { it.index == index }
    val directory = existingTileDir(index, tile?.name)
    val loaded = directory?.let { NdsMeshSnapshot.read(File(it, "mesh.bin")) }
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
