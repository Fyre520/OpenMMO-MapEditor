package de.lananahwp.openmmo.mapeditor.project

import de.lananahwp.openmmo.mapeditor.core.Gen4Decomp
import de.lananahwp.openmmo.mapeditor.core.NdsTileset
import de.lananahwp.openmmo.mapeditor.core.NdsTri
import de.lananahwp.openmmo.mapeditor.core.TileShape
import de.lananahwp.openmmo.mapeditor.json.Json
import de.lananahwp.openmmo.mapeditor.json.JsonParser
import de.lananahwp.openmmo.mapeditor.json.JsonWriter
import de.lananahwp.openmmo.mapeditor.model.NdsBgEvent
import de.lananahwp.openmmo.mapeditor.model.NdsEvents
import de.lananahwp.openmmo.mapeditor.model.NdsGrid
import de.lananahwp.openmmo.mapeditor.model.NdsMap
import de.lananahwp.openmmo.mapeditor.model.NdsMapHeader
import de.lananahwp.openmmo.mapeditor.model.NdsObject
import de.lananahwp.openmmo.mapeditor.model.NdsProp
import de.lananahwp.openmmo.mapeditor.model.NdsCollisionRestore
import de.lananahwp.openmmo.mapeditor.model.NdsTerrainRemoval
import de.lananahwp.openmmo.mapeditor.model.NdsTerrainTransform
import de.lananahwp.openmmo.mapeditor.model.NdsTrigger
import de.lananahwp.openmmo.mapeditor.model.NdsWarp
import java.io.File
import java.awt.Color
import kotlin.math.floor

/** A Gen 4 DS map project backed by a decomp (and optionally a matching ROM). */
class NdsProject(val rootDir: File) {
  /**
   * Where this project keeps the editor's own files: custom maps, props, imported models and grid
   * overrides.
   *
   * Overridable for the same reason [NdsCustomTileStore.rootDir] is: a test that persists
   * anything must be able to point somewhere throwaway. Everything a user has built with the
   * editor lives under here, so a test writing into a real decomp -- let alone cleaning up after
   * itself by removing this directory -- destroys work that has nothing to do with it.
   */
  var overrideRoot: File = File(rootDir, ".openmmo")

  /** 1 DS map-model unit = 4 tiles (map cells are ~8 units per 32x32 tile cell). */
  private val TILE_SCALE = 4f

  /** How picked geometry is cut out of the map. */
  enum class SurfaceCut {
    /**
     * Each picked square is rebuilt as one flat quad, two triangles, cornered exactly on the tile
     * boundary. Predictable to work with afterwards, and walls standing in the square are dropped.
     */
    SQUARES,

    /**
     * Exactly the geometry standing on the picked squares, clipped to them but otherwise left as
     * the map built it. Keeps slopes, overhangs and walls that squaring would flatten away.
     */
    FREEFORM,
  }

  companion object {
    /** Catalog category for props lifted straight off another map's mesh. */
    const val EXTRACTED_CATEGORY = "Extracted"

    /** Packs a map-tile coordinate into a single key for selection sets. */
    fun surfaceCellKey(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)

    fun surfaceCellX(key: Long): Int = (key shr 32).toInt()

    fun surfaceCellZ(key: Long): Int = key.toInt()

    /**
     * The map tiles a brush covers, centred on the tile containing ([x], [z]).
     *
     * [size] is measured in tiles: 1 selects the single square under the pointer, 2 a 2x2 block,
     * and so on. Even sizes grow right/down so the clicked square is always included.
     */
    fun surfaceBrushCells(x: Float, z: Float, size: Int): Set<Long> {
      val span = size.coerceAtLeast(1)
      val originX = kotlin.math.floor(x.toDouble()).toInt() - (span - 1) / 2
      val originZ = kotlin.math.floor(z.toDouble()).toInt() - (span - 1) / 2
      val out = LinkedHashSet<Long>(span * span)
      for (dz in 0 until span) for (dx in 0 until span) {
        out += surfaceCellKey(originX + dx, originZ + dz)
      }
      return out
    }

    /** Every tile in the rectangle spanned by two map-space corners. */
    fun surfaceRectCells(x0: Float, z0: Float, x1: Float, z1: Float): Set<Long> {
      val minX = kotlin.math.floor(minOf(x0, x1).toDouble()).toInt()
      val maxX = kotlin.math.floor(maxOf(x0, x1).toDouble()).toInt()
      val minZ = kotlin.math.floor(minOf(z0, z1).toDouble()).toInt()
      val maxZ = kotlin.math.floor(maxOf(z0, z1).toDouble()).toInt()
      val out = LinkedHashSet<Long>()
      for (z in minZ..maxZ) for (x in minX..maxX) out += surfaceCellKey(x, z)
      return out
    }

    /**
     * Cuts out exactly the geometry standing on the given tiles.
     *
     * Map terrain is not tiled one quad per square: a flat stretch of Olivine ground is a single
     * 5x5-tile quad, so picking a triangle whole — by centroid, or by any other whole-triangle
     * rule — hands back far more map than the user asked for. Each triangle is therefore clipped
     * to the selected squares, which is the only way "one square" can mean one square regardless
     * of how coarse the source mesh is.
     *
     * [textureFilter], when set, keeps only triangles using that texture — the way to lift a path
     * off the grass it is welded to.
     */
    fun filterSurfaceTriangles(
        triangles: List<de.lananahwp.openmmo.mapeditor.core.NdsTri>,
        cells: Set<Long>,
        textureFilter: String? = null,
        cut: SurfaceCut = SurfaceCut.SQUARES,
        pickedHeights: Map<Long, Float>? = null,
    ): List<de.lananahwp.openmmo.mapeditor.core.NdsTri> {
      if (cells.isEmpty()) return emptyList()
      val candidates =
          if (textureFilter == null) triangles else triangles.filter { it.texture == textureFilter }
      if (candidates.isEmpty()) return emptyList()

      if (cut == SurfaceCut.SQUARES) {
        val out = ArrayList<de.lananahwp.openmmo.mapeditor.core.NdsTri>(cells.size * 2)
        for (cell in cells) {
          out += squareForCell(
              candidates, surfaceCellX(cell), surfaceCellZ(cell), pickedHeights?.get(cell))
        }
        return out
      }

      val out = ArrayList<de.lananahwp.openmmo.mapeditor.core.NdsTri>()
      for (tri in candidates) {
        val triMinX = minOf(tri.ax, tri.bx, tri.cx)
        val triMaxX = maxOf(tri.ax, tri.bx, tri.cx)
        val triMinZ = minOf(tri.az, tri.bz, tri.cz)
        val triMaxZ = maxOf(tri.az, tri.bz, tri.cz)
        for (cell in cells) {
          val cellX = surfaceCellX(cell).toFloat()
          val cellZ = surfaceCellZ(cell).toFloat()
          // Skip the clip entirely when the triangle cannot reach this square.
          if (triMaxX <= cellX || triMinX >= cellX + 1f) continue
          if (triMaxZ <= cellZ || triMinZ >= cellZ + 1f) continue
          out += clipTriangleToCell(tri, cellX, cellZ)
        }
      }
      return out
    }

    /** Height and texture coordinates read off a triangle's plane at some XZ point. */
    private class PlaneSample(val y: Float, val u: Float, val v: Float, val inside: Boolean)

    /**
     * Reads a triangle's plane at an XZ point, reporting whether the point is actually within the
     * triangle. Points outside still return a value, extrapolated along the same plane, which is
     * what lets a square be completed from the surface that dominates it.
     *
     * Returns null for faces that are vertical in XZ — a wall has no height to read at a point.
     */
    private fun samplePlane(
        tri: de.lananahwp.openmmo.mapeditor.core.NdsTri,
        x: Float,
        z: Float,
    ): PlaneSample? {
      val d = (tri.bz - tri.cz) * (tri.ax - tri.cx) + (tri.cx - tri.bx) * (tri.az - tri.cz)
      if (kotlin.math.abs(d) < 1e-9f) return null
      val wa = ((tri.bz - tri.cz) * (x - tri.cx) + (tri.cx - tri.bx) * (z - tri.cz)) / d
      val wb = ((tri.cz - tri.az) * (x - tri.cx) + (tri.ax - tri.cx) * (z - tri.cz)) / d
      val wc = 1f - wa - wb
      return PlaneSample(
          tri.ay * wa + tri.by * wb + tri.cy * wc,
          tri.u0 * wa + tri.u1 * wb + tri.u2 * wc,
          tri.v0 * wa + tri.v1 * wb + tri.v2 * wc,
          wa >= -1e-4f && wb >= -1e-4f && wc >= -1e-4f,
      )
    }

    /** Ground-plane area of a triangle, which is ~0 for a wall and ~1 for a full map square. */
    private fun footprintArea(tri: de.lananahwp.openmmo.mapeditor.core.NdsTri): Float =
        kotlin.math.abs(
            (tri.bx - tri.ax) * (tri.cz - tri.az) - (tri.cx - tri.ax) * (tri.bz - tri.az)) / 2f

    /**
     * Rebuilds one map square as a single flat quad — two triangles spanning the square exactly.
     *
     * Clipping alone returns whatever fragments the source mesh happens to be built from, which is
     * awkward to work with afterwards: a square can come back as a five-sided sliver fan, and any
     * wall standing in the square comes along with it. Here the square's own four corners are
     * sampled off the surface that covers most of it, so the result is always two triangles with
     * the corners exactly on the tile boundary.
     *
     * Walls are excluded for free: a vertical face projects to no ground-plane area at all, so it
     * is never a candidate.
     *
     * Which surface a square is built from is decided by [pickedY] — the height the pointer met
     * the mesh at. Maps stack surfaces over the same square (a tree canopy over its own ground,
     * tall grass over the floor it grows from), and choosing purely by footprint area picked the
     * canopy roughly a fifth of the time on National Park: the square came out up in the leaves
     * instead of on the ground the user had clicked. With no reference height the lowest surface
     * wins, which is the walkable floor.
     */
    private fun squareForCell(
        triangles: List<de.lananahwp.openmmo.mapeditor.core.NdsTri>,
        cellX: Int,
        cellZ: Int,
        pickedY: Float? = null,
    ): List<de.lananahwp.openmmo.mapeditor.core.NdsTri> {
      val x0 = cellX.toFloat()
      val z0 = cellZ.toFloat()
      class Candidate(
          val tri: de.lananahwp.openmmo.mapeditor.core.NdsTri,
          val area: Float,
          val height: Float,
      )
      val candidates = ArrayList<Candidate>()
      for (tri in triangles) {
        if (maxOf(tri.ax, tri.bx, tri.cx) <= x0 || minOf(tri.ax, tri.bx, tri.cx) >= x0 + 1f) continue
        if (maxOf(tri.az, tri.bz, tri.cz) <= z0 || minOf(tri.az, tri.bz, tri.cz) >= z0 + 1f) continue
        var area = 0f
        var height = 0f
        var weight = 0f
        for (piece in clipTriangleToCell(tri, x0, z0)) {
          val a = footprintArea(piece)
          area += a
          height += (piece.ay + piece.by + piece.cy) / 3f * a
          weight += a
        }
        // Ignore surfaces that only graze the square, so a brush overhanging the map edge does not
        // manufacture tiles out of slivers.
        if (area < 0.05f) continue
        candidates += Candidate(tri, area, if (weight > 0f) height / weight else 0f)
      }
      if (candidates.isEmpty()) return emptyList()

      val source = if (pickedY != null) {
        // The surface the pointer actually landed on, ties broken toward the larger one.
        candidates.minWithOrNull(
            compareBy<Candidate> { kotlin.math.abs(it.height - pickedY) }
                .thenByDescending { it.area })!!.tri
      } else {
        // No reference: take the floor, not whatever is stacked above it.
        candidates.minWithOrNull(
            compareBy<Candidate> { it.height }.thenByDescending { it.area })!!.tri
      }

      val corners = arrayOf(
          x0 to z0,
          x0 + 1f to z0,
          x0 + 1f to z0 + 1f,
          x0 to z0 + 1f,
      )
      val samples = corners.map { (cx, cz) ->
        val fromSource = samplePlane(source, cx, cz)
        if (fromSource != null && fromSource.inside) return@map fromSource
        // The chosen surface does not reach this corner, so prefer a neighbour that does and
        // shares its texture; that keeps a square straddling two coplanar quads continuous. The
        // neighbour must also sit at about the same height, or a corner could snap up to a canopy
        // stacked over the same square.
        val neighbour = triangles.asSequence()
            .filter { it !== source && it.texture == source.texture }
            .mapNotNull { samplePlane(it, cx, cz) }
            .filter { it.inside }
            .firstOrNull { fromSource == null || kotlin.math.abs(it.y - fromSource.y) < 0.5f }
        neighbour ?: fromSource ?: return emptyList()
      }

      fun corner(index: Int) = Triple(corners[index].first, samples[index].y, corners[index].second)
      val p0 = corner(0); val p1 = corner(1); val p2 = corner(2); val p3 = corner(3)
      return listOf(
          source.copy(
              ax = p0.first, ay = p0.second, az = p0.third, u0 = samples[0].u, v0 = samples[0].v,
              bx = p1.first, by = p1.second, bz = p1.third, u1 = samples[1].u, v1 = samples[1].v,
              cx = p2.first, cy = p2.second, cz = p2.third, u2 = samples[2].u, v2 = samples[2].v,
          ),
          source.copy(
              ax = p0.first, ay = p0.second, az = p0.third, u0 = samples[0].u, v0 = samples[0].v,
              bx = p2.first, by = p2.second, bz = p2.third, u1 = samples[2].u, v1 = samples[2].v,
              cx = p3.first, cy = p3.second, cz = p3.third, u2 = samples[3].u, v2 = samples[3].v,
          ),
      )
    }

    /** One vertex carried through clipping: position plus the attributes that vary across a face. */
    private class ClipVertex(val x: Float, val y: Float, val z: Float, val u: Float, val v: Float)

    /**
     * Sutherland-Hodgman clip of one triangle against a single map square in XZ, re-triangulated.
     *
     * Height and texture coordinates vary linearly across a triangle, so interpolating them by the
     * same edge parameter used for the position keeps the cut piece sitting flush in the original
     * surface with its texture unbroken.
     */
    private fun clipTriangleToCell(
        tri: de.lananahwp.openmmo.mapeditor.core.NdsTri,
        cellX: Float,
        cellZ: Float,
    ): List<de.lananahwp.openmmo.mapeditor.core.NdsTri> {
      var poly = listOf(
          ClipVertex(tri.ax, tri.ay, tri.az, tri.u0, tri.v0),
          ClipVertex(tri.bx, tri.by, tri.bz, tri.u1, tri.v1),
          ClipVertex(tri.cx, tri.cy, tri.cz, tri.u2, tri.v2),
      )
      // Signed distance into the square for each of its four edges; positive means "keep".
      val planes = listOf<(ClipVertex) -> Float>(
          { it.x - cellX },
          { (cellX + 1f) - it.x },
          { it.z - cellZ },
          { (cellZ + 1f) - it.z },
      )
      for (inside in planes) {
        if (poly.isEmpty()) return emptyList()
        val next = ArrayList<ClipVertex>(poly.size + 2)
        for (i in poly.indices) {
          val current = poly[i]
          val previous = poly[(i + poly.size - 1) % poly.size]
          val dCurrent = inside(current)
          val dPrevious = inside(previous)
          if (dCurrent >= 0f) {
            if (dPrevious < 0f) next += lerpClip(previous, current, dPrevious / (dPrevious - dCurrent))
            next += current
          } else if (dPrevious >= 0f) {
            next += lerpClip(previous, current, dPrevious / (dPrevious - dCurrent))
          }
        }
        poly = next
      }
      if (poly.size < 3) return emptyList()

      val out = ArrayList<de.lananahwp.openmmo.mapeditor.core.NdsTri>(poly.size - 2)
      for (i in 1 until poly.size - 1) {
        val a = poly[0]
        val b = poly[i]
        val c = poly[i + 1]
        // Drop slivers produced by clipping exactly along an existing edge.
        val area = kotlin.math.abs(
            (b.x - a.x) * (c.z - a.z) - (c.x - a.x) * (b.z - a.z)) / 2f
        val vertical = kotlin.math.abs(
            (b.x - a.x) * (c.y - a.y) - (c.x - a.x) * (b.y - a.y)) +
            kotlin.math.abs((b.z - a.z) * (c.y - a.y) - (c.z - a.z) * (b.y - a.y))
        if (area < 1e-6f && vertical < 1e-6f) continue
        out += tri.copy(
            ax = a.x, ay = a.y, az = a.z, u0 = a.u, v0 = a.v,
            bx = b.x, by = b.y, bz = b.z, u1 = b.u, v1 = b.v,
            cx = c.x, cy = c.y, cz = c.z, u2 = c.u, v2 = c.v,
        )
      }
      return out
    }

    private fun lerpClip(from: ClipVertex, to: ClipVertex, t: Float): ClipVertex {
      val k = t.coerceIn(0f, 1f)
      return ClipVertex(
          from.x + (to.x - from.x) * k,
          from.y + (to.y - from.y) * k,
          from.z + (to.z - from.z) * k,
          from.u + (to.u - from.u) * k,
          from.v + (to.v - from.v) * k,
      )
    }

    /**
     * Recentres selected geometry onto its own origin: centred in XZ, resting on y=0.
     *
     * This is what [createProp] expects of catalog geometry, so a placement lands where it is
     * clicked instead of back at the source map's coordinates.
     */
    fun recentreSurfaceTriangles(
        selected: List<de.lananahwp.openmmo.mapeditor.core.NdsTri>,
    ): List<de.lananahwp.openmmo.mapeditor.core.NdsTri> {
      if (selected.isEmpty()) return emptyList()
      var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
      var minY = Float.MAX_VALUE
      var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
      for (t in selected) {
        for (v in floatArrayOf(t.ax, t.bx, t.cx)) { minX = minOf(minX, v); maxX = maxOf(maxX, v) }
        for (v in floatArrayOf(t.ay, t.by, t.cy)) { minY = minOf(minY, v) }
        for (v in floatArrayOf(t.az, t.bz, t.cz)) { minZ = minOf(minZ, v); maxZ = maxOf(maxZ, v) }
      }
      val centerX = (minX + maxX) / 2f
      val centerZ = (minZ + maxZ) / 2f
      return selected.map { t ->
        t.copy(
            ax = t.ax - centerX, ay = t.ay - minY, az = t.az - centerZ,
            bx = t.bx - centerX, by = t.by - minY, bz = t.bz - centerZ,
            cx = t.cx - centerX, cy = t.cy - minY, cz = t.cz - centerZ,
            // The snapshot is its own model now; a source-map group id would be meaningless here.
            editGroup = "",
        )
      }
    }
  }

  data class PropModelInfo(
      val key: String,
      val label: String,
      val imported: Boolean,
      val category: String = if (imported) "Imported" else "Scenery",
  ) {
    override fun toString(): String {
      val id = key.removePrefix("rom:").takeIf { key.startsWith("rom:") }
      return if (id == null) label else "$label (#$id)"
    }
  }

  data class PropModelPreview(
      val triangles: List<de.lananahwp.openmmo.mapeditor.core.NdsTri>,
      val textures: Map<String, de.lananahwp.openmmo.mapeditor.core.NdsTexture>,
      val palettes: Map<String, IntArray>,
  )
  val decomp = Gen4Decomp(rootDir)
  val family get() = decomp.family

  /** A matching NDS ROM with the actual map data, when one is available. */
  val rom: de.lananahwp.openmmo.mapeditor.core.NdsRom? by lazy { discoverRom() }

  val hasRom: Boolean get() = rom != null

  /** Map names from the ROM when the decomp does not expose any (unbuilt Platinum decomp). */
  private val romMapNames: List<String>? by lazy {
    val r = rom ?: return@lazy null
    if (!r.has("fielddata/maptable/mapname.bin")) return@lazy null
    de.lananahwp.openmmo.mapeditor.core.NdsMapNames.parse(
            r.read("fielddata/maptable/mapname.bin"))
        .filterIndexed { index, _ -> index >= 2 }
        .filter { it.isNotBlank() }
  }

  private val baseMapNames: List<String> by lazy {
    val decompNames = decomp.mapIds.entries.sortedBy { it.value }.map { it.key }
    if (decompNames.isNotEmpty()) decompNames
    else romMapNames ?: emptyList()
  }

  /** ROM/decomp maps plus maps created by the editor in this project. */
  val mapNames: List<String>
    get() = (baseMapNames + customMapNames()).distinct()

  private val maps = LinkedHashMap<String, NdsMap>()
  private val narcCache = HashMap<String, List<ByteArray>>()
  private val propTriangleCache = HashMap<String, List<de.lananahwp.openmmo.mapeditor.core.NdsTri>>()
  private val propTexturePackCache = HashMap<String, List<ByteArray>>()
  private val terrainTriangleCache = HashMap<String, List<de.lananahwp.openmmo.mapeditor.core.NdsTri>>()

  /** Baked meshes for extracted props; a null value caches "this key is not an extracted prop". */
  private val propMeshCache = HashMap<String, de.lananahwp.openmmo.mapeditor.core.NdsMeshSnapshot?>()

  data class TerrainObjectSelection(
      val groupId: String,
      val triangleCount: Int,
      val minX: Float,
      val minY: Float,
      val minZ: Float,
      val maxX: Float,
      val maxY: Float,
      val maxZ: Float,
      val collisionCells: List<Pair<Int, Int>>,
  )

  private data class BuildingTextureIndex(
      val textures: Map<String, de.lananahwp.openmmo.mapeditor.core.NdsTexture>,
      val palettes: Map<String, IntArray>,
  )

  /** Cross-area fallback used when a catalog prop is placed outside its original area. */
  private val buildingTextureIndex: BuildingTextureIndex by lazy {
    val textures = LinkedHashMap<String, de.lananahwp.openmmo.mapeditor.core.NdsTexture>()
    val palettes = LinkedHashMap<String, IntArray>()
    for (bytes in buildingTextureFiles()) {
      val pack = de.lananahwp.openmmo.mapeditor.core.NdsNsbtx.parsePack(bytes)
      for (texture in pack.textures) {
        textures.putIfAbsent(texture.name, texture)
        val base = baseTextureName(texture.name)
        if (base != texture.name) textures.putIfAbsent(base, texture)
      }
      for ((name, colors) in pack.palettes) palettes.putIfAbsent(name, colors)
    }
    BuildingTextureIndex(textures, palettes)
  }

  /** Cached NARC files (avoids re-reading multi-MB archives on every map open). */
  private fun narcFiles(path: String): List<ByteArray>? {
    val r = rom ?: return null
    if (path !in narcCache) {
      narcCache[path] = if (r.has(path)) r.narc(path) else emptyList()
    }
    return narcCache[path]
  }

  fun hasMap(name: String): Boolean =
      decomp.mapIds.containsKey(name) || romMapNames?.contains(name) == true || customManifestFile(name).isFile

  fun mapIdInUse(id: Int): Boolean = id in knownMapIds()

  fun suggestedMapId(): Int {
    val used = knownMapIds()
    val afterLast = (used.maxOrNull() ?: -1) + 1
    if (afterLast <= 0xFFFF) return afterLast
    return (0..0xFFFF).firstOrNull { it !in used } ?: 0xFFFF
  }

  private fun knownMapIds(): Set<Int> {
    val ids = decomp.mapIds.values.toMutableSet()
    if (ids.isEmpty()) ids += romMapNames.orEmpty().indices
    for (name in customMapNames()) {
      try {
        JsonParser.parse(customManifestFile(name).readText()).asObj()?.int("mapId")?.let(ids::add)
      } catch (_: Throwable) {}
    }
    return ids
  }

  /** Loads (and caches) a map by its identifier. */
  fun loadMap(name: String): NdsMap? {
    maps[name]?.let { return it }
    loadCustomMap(name)?.let {
      maps[name] = it
      return it
    }
    val loaded = decomp.loadMap(name) ?: run {
      // ROM-only maps (unbuilt Platinum decomp): build from headerless data.
      val index = romMapNames?.indexOf(name) ?: return null
      if (index < 0) return null
      NdsMap(name, index, de.lananahwp.openmmo.mapeditor.model.NdsMapHeader().also {
        it.name = name
      }, de.lananahwp.openmmo.mapeditor.model.NdsEvents(), de.lananahwp.openmmo.mapeditor.model.NdsGrid())
    }
    populateGridFromRom(loaded)
    loadGridOverride(loaded)
    loadProps(loaded)
    maps[name] = loaded
    return loaded
  }

  /** World-matrix cells, including project-local maps explicitly placed in the overworld. */
  fun matrixCells(): List<Gen4Decomp.MatrixCell> = decomp.matrixCells() + customMatrixCells()

  fun suggestedMatrixOrigin(): Pair<Int, Int> {
    val cells = matrixCells()
    return if (cells.isEmpty()) 0 to 0 else 0 to (cells.maxOf { it.y } + 2)
  }

  fun matrixPlacementConflicts(x: Int, y: Int, width: Int, height: Int): Boolean {
    val occupied = matrixCells().map { it.x to it.y }.toHashSet()
    return (0 until height).any { dy ->
      (0 until width).any { dx -> (x + dx to y + dy) in occupied }
    }
  }

  /** One matrix cell of a map: its grid position and the map-file index it loads. */
  data class MapCell(
      val cellX: Int,
      val cellY: Int,
      val fileIndex: Int,
      val altitude: Int,
  )

  /** The map matrix NARC entries (indexed by the map's matrix id). */
  private val matrixFiles: List<ByteArray>? by lazy {
    val path =
        if (family == de.lananahwp.openmmo.mapeditor.core.NdsFamily.PLATINUM)
          "fielddata/mapmatrix/map_matrix.narc"
        else "a/0/4/1"
    narcFiles(path)
  }

  private fun matrixFor(map: NdsMap): de.lananahwp.openmmo.mapeditor.core.NdsMatrix? =
      matrixFiles?.getOrNull(map.header.matrixId)
          ?.let(de.lananahwp.openmmo.mapeditor.core.NdsMatrix::parse)

  /**
   * The matrix cells a map occupies. A single game map (e.g. Oreburgh City) is a union of
   * one or more 32x32 matrix cells, each backed by its own map-file entry. Indoor maps use a
   * headerless (often 1x1) matrix whose cells are the map itself.
   */
  fun resolveCells(map: NdsMap): List<MapCell> {
    if (map.isCustom) {
      return map.matrixCells.map { (x, y) -> MapCell(x, y, -1, 0) }
    }
    val m = matrixFor(map) ?: return emptyList()
    val byHeader =
        m.cells().filter { m.hasHeaders && m.headerAt(it[0], it[1]) == map.mapId }
            .map { MapCell(it[0], it[1], it[2], m.altitudeAt(it[0], it[1])) }
    if (byHeader.isNotEmpty()) return byHeader
    if (!m.hasHeaders) {
      // Headerless matrix (indoor rooms / buildings): the whole matrix is this map.
      return m.cells().map { MapCell(it[0], it[1], it[2], m.altitudeAt(it[0], it[1])) }
    }
    return listOf(MapCell(0, 0, map.mapId, 0))
  }

  private fun mapFileIndex(fileIndex: Int): de.lananahwp.openmmo.mapeditor.core.NdsMapData? =
      if (family == de.lananahwp.openmmo.mapeditor.core.NdsFamily.PLATINUM) {
        narcFiles("fielddata/land_data/land_data.narc")?.getOrNull(fileIndex)
            ?.let { de.lananahwp.openmmo.mapeditor.core.NdsMapData.parse(it, hasBgs = false) }
      } else {
        narcFiles("a/0/6/5")?.getOrNull(fileIndex)
            ?.let { de.lananahwp.openmmo.mapeditor.core.NdsMapData.parse(it, hasBgs = true) }
      }

  /** Grid dimensions (in tiles) covering all of the map's matrix cells. */
  private fun footprint(cells: List<MapCell>): IntArray {
    if (cells.isEmpty()) return intArrayOf(0, 0, 32, 32)
    val minX = cells.minOf { it.cellX }
    val maxX = cells.maxOf { it.cellX }
    val minY = cells.minOf { it.cellY }
    val maxY = cells.maxOf { it.cellY }
    return intArrayOf(minX, minY, (maxX - minX + 1) * 32, (maxY - minY + 1) * 32)
  }

  // ---- ROM map data --------------------------------------------------------

  private fun discoverRom(): de.lananahwp.openmmo.mapeditor.core.NdsRom? {
    val candidates = mutableListOf<File>()
    var dir: File? = rootDir
    repeat(7) {
      val d = dir ?: return@repeat
      d.listFiles()?.forEach {
        if (it.isFile && it.extension.equals("nds", true)) candidates += it
      }
      for (romsDir in listOf(File(d, "roms"), File(d, "openmmo/roms"))) {
        if (romsDir.isDirectory) {
          romsDir.listFiles()?.forEach {
            if (it.isFile && it.extension.equals("nds", true)) candidates += it
          }
        }
      }
      dir = d.parentFile
    }
    val wants =
        if (family == de.lananahwp.openmmo.mapeditor.core.NdsFamily.PLATINUM)
          listOf("fielddata/land_data/land_data.narc")
        else listOf("fielddata/build_model/bm_field.narc", "a/0/6/5")
    for (f in candidates.distinct()) {
      try {
        val r = de.lananahwp.openmmo.mapeditor.core.NdsRom(f)
        System.out.println("[NdsProject] candidate ROM ${f.name} code=${r.gameCode} has=${wants.map { w -> "$w=${r.has(w)}" }}")
        if (wants.all(r::has)) {
          System.out.println("[NdsProject] matched ROM ${f.name} (${r.gameCode}) for ${family.displayName}")
          return r
        }
      } catch (e: Throwable) {
        System.out.println("[NdsProject] candidate ROM ${f.name} rejected: ${e.message}")
      }
    }
    System.out.println("[NdsProject] no ROM matched for ${family.displayName}; candidates=${candidates.map { it.name }}")
    return null
  }

  /** Populates grid collisions/permissions from the ROM (Platinum land data / HGSS maps). */
  private fun populateGridFromRom(map: NdsMap) {
    try {
      val cells = resolveCells(map)
      if (cells.isEmpty()) return
      val (minX, minY, cols, rows) = footprint(cells)
      if (map.grid.cols != cols || map.grid.rows != rows) {
        map.grid = de.lananahwp.openmmo.mapeditor.model.NdsGrid(cols, rows)
      }
      for (c in cells) {
        val data = mapFileIndex(c.fileIndex) ?: continue
        val ox = (c.cellX - minX) * 32
        val oy = (c.cellY - minY) * 32
        for (y in 0 until 32) {
          for (x in 0 until 32) {
            map.grid.setPermission(ox + x, oy + y, data.permissionAt(x, y))
            map.grid.setCollision(ox + x, oy + y, data.collisionAt(x, y))
          }
        }
      }
    } catch (_: Throwable) {}
  }

  /**
   * The scale/offset that maps a cell's model (native units) onto its 32x32 tile cell.
   *
   * Gen 4 land models use a fixed local origin: (-4, -4)..(4, 4) is the nominal 32x32-cell
   * footprint. Geometry is allowed to extend beyond that footprint for border transitions, so
   * its decoded bounds must not be used to recenter or stitch the cell.
   */
  private data class CellFit(val scale: Float, val cx: Float, val cz: Float, val groundY: Float, val dx: Float, val dz: Float)

  private data class Bounds(
      val minX: Float,
      val minY: Float,
      val minZ: Float,
      val maxX: Float,
      val maxY: Float,
      val maxZ: Float,
  )

  private fun boundsOf(tris: List<de.lananahwp.openmmo.mapeditor.core.NdsTri>): Bounds? {
    if (tris.isEmpty()) return null
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var minZ = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    var maxZ = -Float.MAX_VALUE
    for (t in tris) {
      for (v in floatArrayOf(t.ax, t.bx, t.cx)) {
        minX = minOf(minX, v)
        maxX = maxOf(maxX, v)
      }
      for (v in floatArrayOf(t.ay, t.by, t.cy)) {
        minY = minOf(minY, v)
        maxY = maxOf(maxY, v)
      }
      for (v in floatArrayOf(t.az, t.bz, t.cz)) {
        minZ = minOf(minZ, v)
        maxZ = maxOf(maxZ, v)
      }
    }
    return Bounds(minX, minY, minZ, maxX, maxY, maxZ)
  }

  fun seamDiagnostics(map: NdsMap): String {
    val cells = resolveCells(map)
    if (cells.isEmpty()) return "${map.name}: no matrix cells"
    val (minCellX, minCellY, _, _) = footprint(cells)
    val globalMinY =
        cells.mapNotNull { mapFileIndex(it.fileIndex)?.modelBytes?.let(::cellMinY) }
            .minOrNull() ?: 0f
    data class Entry(
        val cell: MapCell,
        val raw: Bounds,
        val placed: Bounds,
        val fit: CellFit,
    )
    val fits = placementFits(cells, minCellX, minCellY, globalMinY)
    val entries =
        cells.mapNotNull { cell ->
          val model = mapFileIndex(cell.fileIndex)?.modelBytes ?: return@mapNotNull null
          val rawTris = de.lananahwp.openmmo.mapeditor.core.NdsNsbmd.decode(model)
          val raw = boundsOf(rawTris) ?: return@mapNotNull null
          val fit = fits[cell.cellX to cell.cellY] ?: return@mapNotNull null
          val placed = boundsOf(applyFit(rawTris, fit)) ?: return@mapNotNull null
          Entry(cell, raw, placed, fit)
        }
    fun f(value: Float) = "%.4f".format(java.util.Locale.ROOT, value)
    return buildString {
      appendLine("Map ${map.name}: cells=${entries.size}")
      appendLine("  Bounds relation: positive = uncovered gap, negative = authored overlap/overhang")
      for (entry in entries.sortedWith(compareBy({ it.cell.cellY }, { it.cell.cellX }))) {
        appendLine("Section file=${entry.cell.fileIndex}")
        appendLine("  matrixCell = (${entry.cell.cellX}, ${entry.cell.cellY})")
        appendLine("  matrixOrigin = (${f((entry.cell.cellX - minCellX) * 32f)}, ${f((entry.cell.cellY - minCellY) * 32f)})")
        appendLine("  localOrigin = (${f(entry.fit.cx)}, ${f(entry.fit.cz)})")
        appendLine("  translation = (${f(entry.fit.dx)}, ${f(entry.fit.dz)})")
        appendLine("  worldOrigin = (${f(16f + entry.fit.dx - entry.fit.cx * entry.fit.scale)}, ${f(16f + entry.fit.dz - entry.fit.cz * entry.fit.scale)})")
        appendLine("  sourceAltitude = ${entry.cell.altitude}")
        appendLine("  rawOrigin = (${f(entry.raw.minX)}, ${f(entry.raw.minZ)})")
        appendLine("  rawSize = (${f(entry.raw.maxX - entry.raw.minX)}, ${f(entry.raw.maxZ - entry.raw.minZ)})")
        appendLine("  fittedScale = ${f(entry.fit.scale)}")
        appendLine("  worldBounds = (${f(entry.placed.minX)}, ${f(entry.placed.minZ)})..(${f(entry.placed.maxX)}, ${f(entry.placed.maxZ)})")
      }
      val byCell = entries.associateBy { it.cell.cellX to it.cell.cellY }
      for (a in entries.sortedWith(compareBy({ it.cell.cellY }, { it.cell.cellX }))) {
        byCell[a.cell.cellX + 1 to a.cell.cellY]?.let { b ->
          appendLine("Seam (${a.cell.cellX},${a.cell.cellY}) -> (${b.cell.cellX},${b.cell.cellY}) X")
          appendLine("  leftMax = ${f(a.placed.maxX)}")
          appendLine("  rightMin = ${f(b.placed.minX)}")
          appendLine("  boundsDelta = ${f(b.placed.minX - a.placed.maxX)}")
        }
        byCell[a.cell.cellX to a.cell.cellY + 1]?.let { b ->
          appendLine("Seam (${a.cell.cellX},${a.cell.cellY}) -> (${b.cell.cellX},${b.cell.cellY}) Z")
          appendLine("  topMax = ${f(a.placed.maxZ)}")
          appendLine("  bottomMin = ${f(b.placed.minZ)}")
          appendLine("  boundsDelta = ${f(b.placed.minZ - a.placed.maxZ)}")
        }
      }
    }
  }

  private fun cellSpan(model: ByteArray?): Float {
    if (model == null) return 0f
    val tris = de.lananahwp.openmmo.mapeditor.core.NdsNsbmd.decode(model)
    if (tris.isEmpty()) return 0f
    var b0x = Float.MAX_VALUE; var b1x = -Float.MAX_VALUE
    var b0z = Float.MAX_VALUE; var b1z = -Float.MAX_VALUE
    for (t in tris) {
      for (v in floatArrayOf(t.ax, t.bx, t.cx)) { if (v < b0x) b0x = v; if (v > b1x) b1x = v }
      for (v in floatArrayOf(t.az, t.bz, t.cz)) { if (v < b0z) b0z = v; if (v > b1z) b1z = v }
    }
    return maxOf(b1x - b0x, b1z - b0z)
  }

  /** The minimum Y of a cell's model (its lowest point). */
  private fun cellMinY(model: ByteArray?): Float {
    if (model == null) return 0f
    val tris = de.lananahwp.openmmo.mapeditor.core.NdsNsbmd.decode(model)
    if (tris.isEmpty()) return 0f
    var b0y = Float.MAX_VALUE
    for (t in tris) {
      for (v in floatArrayOf(t.ay, t.by, t.cy)) { if (v < b0y) b0y = v }
    }
    return b0y
  }

  private fun cellFit(model: ByteArray?, cellX: Int, cellY: Int, minX: Int, minY: Int, groundY: Float): CellFit? {
    if (model == null) return null
    val tris = de.lananahwp.openmmo.mapeditor.core.NdsNsbmd.decode(model)
    if (tris.isEmpty()) return null
    // Fixed scale and fixed origin are both part of the land-model coordinate convention.
    // Bounds such as -4.25..6 are intentional overhang, not a larger off-center cell.
    return CellFit(
        TILE_SCALE,
        0f,
        0f,
        groundY,
        (cellX - minX) * 32f,
        (cellY - minY) * 32f,
    )
  }

  private fun placementFits(
      cells: List<MapCell>,
      minX: Int,
      minY: Int,
      groundY: Float,
  ): Map<Pair<Int, Int>, CellFit> {
    // Matrix placement is authoritative. Pulling a cell toward its neighbour based on outer
    // model bounds compounds intentional overhang and shifts terrain/buildings off the tile grid.
    return cells.mapNotNull { cell ->
      val model = mapFileIndex(cell.fileIndex)?.modelBytes ?: return@mapNotNull null
      val fit = cellFit(model, cell.cellX, cell.cellY, minX, minY, groundY)
          ?: return@mapNotNull null
      (cell.cellX to cell.cellY) to fit
    }.toMap()
  }

  private fun applyFit(tris: List<de.lananahwp.openmmo.mapeditor.core.NdsTri>, fit: CellFit): List<de.lananahwp.openmmo.mapeditor.core.NdsTri> =
      tris.map {
        it.copy(
            ax = (it.ax - fit.cx) * fit.scale + 16f + fit.dx, ay = (it.ay - fit.groundY) * fit.scale, az = (it.az - fit.cz) * fit.scale + 16f + fit.dz,
            bx = (it.bx - fit.cx) * fit.scale + 16f + fit.dx, by = (it.by - fit.groundY) * fit.scale, bz = (it.bz - fit.cz) * fit.scale + 16f + fit.dz,
            cx = (it.cx - fit.cx) * fit.scale + 16f + fit.dx, cy = (it.cy - fit.groundY) * fit.scale, cz = (it.cz - fit.cz) * fit.scale + 16f + fit.dz,
        )
      }

  private data class TerrainBounds(
      var minX: Float = Float.MAX_VALUE,
      var minY: Float = Float.MAX_VALUE,
      var minZ: Float = Float.MAX_VALUE,
      var maxX: Float = -Float.MAX_VALUE,
      var maxY: Float = -Float.MAX_VALUE,
      var maxZ: Float = -Float.MAX_VALUE,
  ) {
    fun include(x: Float, y: Float, z: Float) {
      minX = minOf(minX, x); minY = minOf(minY, y); minZ = minOf(minZ, z)
      maxX = maxOf(maxX, x); maxY = maxOf(maxY, y); maxZ = maxOf(maxZ, z)
    }
  }

  /** Groups disconnected pieces within a map cell into stable, clickable terrain objects. */
  private fun assignTerrainGroups(
      tris: List<de.lananahwp.openmmo.mapeditor.core.NdsTri>,
      prefix: String,
  ): List<de.lananahwp.openmmo.mapeditor.core.NdsTri> {
    if (tris.isEmpty()) return tris
    val parent = IntArray(tris.size) { it }
    fun find(v: Int): Int {
      var x = v
      while (parent[x] != x) {
        parent[x] = parent[parent[x]]
        x = parent[x]
      }
      return x
    }
    fun union(a: Int, b: Int) {
      val ra = find(a); val rb = find(b)
      if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb)
    }
    data class VertexKey(val x: Int, val y: Int, val z: Int)
    fun key(x: Float, y: Float, z: Float) = VertexKey(
        kotlin.math.round(x * 1024f).toInt(),
        kotlin.math.round(y * 1024f).toInt(),
        kotlin.math.round(z * 1024f).toInt(),
    )
    val owner = HashMap<VertexKey, Int>(tris.size * 2)
    tris.forEachIndexed { index, tri ->
      for (vertex in arrayOf(
          key(tri.ax, tri.ay, tri.az),
          key(tri.bx, tri.by, tri.bz),
          key(tri.cx, tri.cy, tri.cz),
      )) {
        owner.putIfAbsent(vertex, index)?.let { union(index, it) }
      }
    }
    val strict = LinkedHashMap<Int, MutableList<Int>>()
    for (i in tris.indices) strict.getOrPut(find(i)) { mutableListOf() } += i
    val parts = strict.values.sortedBy { it.first() }
    val bounds = parts.map { indices ->
      TerrainBounds().also { b ->
        for (i in indices) {
          val t = tris[i]
          b.include(t.ax, t.ay, t.az); b.include(t.bx, t.by, t.bz); b.include(t.cx, t.cy, t.cz)
        }
      }
    }

    // Tree trunks and crowns often use different materials and therefore do not share vertices.
    // Join vertically adjacent small parts only when their horizontal centers nearly coincide;
    // this keeps neighboring trees in a row independently editable.
    val partParent = IntArray(parts.size) { it }
    fun partFind(v: Int): Int {
      var x = v
      while (partParent[x] != x) {
        partParent[x] = partParent[partParent[x]]
        x = partParent[x]
      }
      return x
    }
    fun partUnion(a: Int, b: Int) {
      val ra = partFind(a); val rb = partFind(b)
      if (ra != rb) partParent[maxOf(ra, rb)] = minOf(ra, rb)
    }
    fun verticalGap(a: TerrainBounds, b: TerrainBounds): Float =
        maxOf(0f, maxOf(a.minY, b.minY) - minOf(a.maxY, b.maxY))
    for (a in parts.indices) for (b in a + 1 until parts.size) {
      val aa = bounds[a]; val bb = bounds[b]
      val aSmall = aa.maxX - aa.minX <= 12f && aa.maxZ - aa.minZ <= 12f
      val bSmall = bb.maxX - bb.minX <= 12f && bb.maxZ - bb.minZ <= 12f
      if (!aSmall || !bSmall) continue
      val centerDx = kotlin.math.abs((aa.minX + aa.maxX) - (bb.minX + bb.maxX)) / 2f
      val centerDz = kotlin.math.abs((aa.minZ + aa.maxZ) - (bb.minZ + bb.maxZ)) / 2f
      if (centerDx <= 0.75f && centerDz <= 0.75f && verticalGap(aa, bb) <= 0.35f) {
        partUnion(a, b)
      }
    }
    val objectRoots = parts.indices.map(::partFind).distinct().sortedBy { root ->
      parts.indices.filter { partFind(it) == root }.minOf { parts[it].first() }
    }
    val objectNumber = objectRoots.withIndex().associate { it.value to it.index }
    val triangleGroup = Array(tris.size) { "" }
    for (part in parts.indices) {
      val group = "$prefix:${objectNumber.getValue(partFind(part))}"
      for (triangle in parts[part]) triangleGroup[triangle] = group
    }
    return tris.mapIndexed { index, tri -> tri.copy(editGroup = triangleGroup[index]) }
  }

  /** Decodes the map's 3D model (NSBMD) into triangles, from the ROM. */
  fun trianglesFor(map: NdsMap): List<de.lananahwp.openmmo.mapeditor.core.NdsTri> {
    val hidden = map.terrainRemovals.map { it.groupId }.toHashSet()
    val transforms = map.terrainTransforms.associateBy { it.groupId }
    return rawTerrainTriangles(map).asSequence()
        .filter { it.editGroup !in hidden }
        .map { tri ->
          val transform = transforms[tri.editGroup] ?: return@map tri
          tri.copy(
              ax = tri.ax + transform.offsetX, ay = tri.ay + transform.offsetY, az = tri.az + transform.offsetZ,
              bx = tri.bx + transform.offsetX, by = tri.by + transform.offsetY, bz = tri.bz + transform.offsetZ,
              cx = tri.cx + transform.offsetX, cy = tri.cy + transform.offsetY, cz = tri.cz + transform.offsetZ,
          )
        }
        .toList()
  }

  private fun rawTerrainTriangles(map: NdsMap): List<de.lananahwp.openmmo.mapeditor.core.NdsTri> {
    terrainTriangleCache[map.name]?.let { return it }
    return try {
      val imported = importedModelFile(map.name)
      if (imported.isFile) {
        val result = assignTerrainGroups(
            fitImportedModel(
                de.lananahwp.openmmo.mapeditor.core.NdsNsbmd.decode(imported.readBytes()), map.grid),
            "imported",
        )
        terrainTriangleCache[map.name] = result
        return result
      }
      if (map.isCustom) return emptyList()
      val cells = resolveCells(map)
      if (cells.isEmpty()) return emptyList()
      val (minX, minY, _, _) = footprint(cells)
      // One uniform ground reference across all cells so cells with lower terrain stay lower.
      val globalMinY = cells.mapNotNull { mapFileIndex(it.fileIndex)?.modelBytes?.let { m -> cellMinY(m) } }.minOrNull() ?: 0f
      val fits = placementFits(cells, minX, minY, globalMinY)
      val out = mutableListOf<de.lananahwp.openmmo.mapeditor.core.NdsTri>()
      for (c in cells) {
        val data = mapFileIndex(c.fileIndex) ?: continue
        val fit = fits[c.cellX to c.cellY] ?: continue
        out += assignTerrainGroups(
            applyFit(de.lananahwp.openmmo.mapeditor.core.NdsNsbmd.decode(data.modelBytes!!), fit),
            "cell-${c.fileIndex}-${c.cellX}-${c.cellY}",
        )
      }
      terrainTriangleCache[map.name] = out
      out
    } catch (_: Throwable) {
      emptyList()
    }
  }

  /** Finds the nearest elevated connected terrain object beneath a grid click. */
  fun terrainObjectAt(map: NdsMap, x: Float, z: Float): TerrainObjectSelection? {
    val candidates = trianglesFor(map)
        .filter { it.editGroup.isNotEmpty() }
        .groupBy { it.editGroup }
        .mapNotNull { (groupId, tris) ->
          val selection = terrainSelection(map, groupId, tris) ?: return@mapNotNull null
          val b = TerrainBounds(
              selection.minX, selection.minY, selection.minZ,
              selection.maxX, selection.maxY, selection.maxZ,
          )
          val centerX = (b.minX + b.maxX) / 2f
          val centerZ = (b.minZ + b.maxZ) / 2f
          val dx = centerX - x
          val dz = centerZ - z
          val inside = x in (b.minX - 0.35f)..(b.maxX + 0.35f) &&
              z in (b.minZ - 0.35f)..(b.maxZ + 0.35f)
          val distance = dx * dx + dz * dz
          if (!inside && distance > 4f) return@mapNotNull null
          Triple(
              selection,
              if (inside) 0 else 1,
              distance,
          )
        }
    return candidates.minWithOrNull(
        compareBy<Triple<TerrainObjectSelection, Int, Float>> { it.second }
            .thenBy { it.third }
            .thenBy { (it.first.maxX - it.first.minX) * (it.first.maxZ - it.first.minZ) },
    )?.first
  }

  /** Resolves an exact terrain mesh hit while retaining the large-ground safety checks. */
  fun terrainObject(map: NdsMap, groupId: String): TerrainObjectSelection? {
    if (groupId.isEmpty() || map.terrainRemovals.any { it.groupId == groupId }) return null
    val tris = trianglesFor(map).filter { it.editGroup == groupId }
    return terrainSelection(map, groupId, tris)
  }

  fun terrainObjectOffset(map: NdsMap, groupId: String): Pair<Float, Float> =
      map.terrainTransforms.firstOrNull { it.groupId == groupId }
          ?.let { it.offsetX to it.offsetZ } ?: (0f to 0f)

  // ---- Surface selection ---------------------------------------------------
  //
  // Deliberately independent of the TerrainObjectSelection machinery above. That system answers
  // "which connected scenery object did I click", which is the wrong question for ground surfaces:
  // a path is welded to the map floor, so connected-component grouping hands back the entire
  // terrain, and terrainSelection() then rejects it anyway for being flat (height < 0.35) and
  // large (span > 14). Surface selection instead works on individual triangles keyed by map tile,
  // so you pick the few squares you actually want and nothing else.

  /** Terrain triangles sitting on the given tiles. See [filterSurfaceTriangles]. */
  fun surfaceTriangles(
      map: NdsMap,
      cells: Set<Long>,
      textureFilter: String? = null,
      cut: SurfaceCut = SurfaceCut.SQUARES,
      pickedHeights: Map<Long, Float>? = null,
  ): List<de.lananahwp.openmmo.mapeditor.core.NdsTri> =
      filterSurfaceTriangles(trianglesFor(map), cells, textureFilter, cut, pickedHeights)

  /**
   * Bakes selected terrain into a standalone mesh: geometry recentred on its own origin plus every
   * texture and palette it references, resolved through the source map's texture packs.
   */
  fun buildSurfaceExtraction(
      map: NdsMap,
      cells: Set<Long>,
      textureFilter: String? = null,
      cut: SurfaceCut = SurfaceCut.SQUARES,
      pickedHeights: Map<Long, Float>? = null,
  ): de.lananahwp.openmmo.mapeditor.core.NdsMeshSnapshot? {
    val triangles =
        recentreSurfaceTriangles(surfaceTriangles(map, cells, textureFilter, cut, pickedHeights))
    if (triangles.isEmpty()) return null

    val referencedTextures = triangles.map { it.texture }.filter { it.isNotEmpty() }.toSet()
    val referencedPalettes = triangles.map { it.palette }.filter { it.isNotEmpty() }.toSet()
    val mapTextures = texturesFor(map)
    val mapPalettes = palettesFor(map)
    val textures = LinkedHashMap<String, de.lananahwp.openmmo.mapeditor.core.NdsTexture>()
    for (name in referencedTextures) {
      (mapTextures[name] ?: mapTextures[baseTextureName(name)])?.let { textures[name] = it }
    }
    val palettes = LinkedHashMap<String, IntArray>()
    for (name in referencedPalettes) mapPalettes[name]?.let { palettes[name] = it }
    return de.lananahwp.openmmo.mapeditor.core.NdsMeshSnapshot(triangles, textures, palettes)
  }

  /** Stores a baked surface extraction in the reusable prop catalog under a user-chosen name. */
  fun saveExtractedProp(
      label: String,
      snapshot: de.lananahwp.openmmo.mapeditor.core.NdsMeshSnapshot,
      sourceMap: String,
  ): PropModelInfo {
    require(snapshot.triangles.isNotEmpty()) { "The selection contains no geometry to save" }
    var base = label.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
    if (base.isEmpty()) base = "surface"
    var key = "extracted:$base"
    var suffix = 2
    while (propModelManifest(key).exists()) key = "extracted:${base}_${suffix++}"
    val dir = propModelDir(key)
    dir.mkdirs()
    de.lananahwp.openmmo.mapeditor.core.NdsMeshSnapshot.write(propMeshFile(key), snapshot)
    val resolvedLabel = label.trim().ifEmpty { "Surface from $sourceMap" }
    val manifest = Json.JObj(linkedMapOf(
        "version" to Json.JNum(1.0),
        "key" to Json.JStr(key),
        "label" to Json.JStr(resolvedLabel),
        "source" to Json.JStr("extracted"),
        "sourceMap" to Json.JStr(sourceMap),
        "triangles" to Json.JNum(snapshot.triangles.size.toDouble()),
    ))
    propModelManifest(key).writeText(JsonWriter.writePretty(manifest) + "\n")
    propTriangleCache.remove(key)
    propTexturePackCache.remove(key)
    propMeshCache.remove(key)
    return PropModelInfo(key, resolvedLabel, true, EXTRACTED_CATEGORY)
  }

  /** The baked mesh behind an extracted catalog prop, or null for ROM/imported models. */
  private fun extractedMesh(modelKey: String): de.lananahwp.openmmo.mapeditor.core.NdsMeshSnapshot? {
    if (modelKey.startsWith("rom:")) return null
    if (modelKey in propMeshCache) return propMeshCache[modelKey]
    val loaded = de.lananahwp.openmmo.mapeditor.core.NdsMeshSnapshot.read(propMeshFile(modelKey))
    propMeshCache[modelKey] = loaded
    return loaded
  }

  /** Moves one connected object that is baked into the terrain without modifying the source model. */
  fun moveTerrainObject(map: NdsMap, groupId: String, offsetX: Float, offsetZ: Float): Boolean {
    if (terrainObject(map, groupId) == null) return false
    val existing = map.terrainTransforms.firstOrNull { it.groupId == groupId }
    if (kotlin.math.abs(offsetX) < 0.0001f && kotlin.math.abs(offsetZ) < 0.0001f) {
      if (existing != null) map.terrainTransforms.remove(existing)
    } else if (existing == null) {
      map.terrainTransforms += NdsTerrainTransform(groupId, offsetX = offsetX, offsetZ = offsetZ)
    } else {
      existing.offsetX = offsetX
      existing.offsetZ = offsetZ
    }
    return true
  }

  private fun terrainSelection(
      map: NdsMap,
      groupId: String,
      tris: List<de.lananahwp.openmmo.mapeditor.core.NdsTri>,
  ): TerrainObjectSelection? {
    if (tris.isEmpty()) return null
    val b = TerrainBounds()
    for (t in tris) {
      b.include(t.ax, t.ay, t.az); b.include(t.bx, t.by, t.bz); b.include(t.cx, t.cy, t.cz)
    }
    val height = b.maxY - b.minY
    val spanX = b.maxX - b.minX
    val spanZ = b.maxZ - b.minZ
    if (height < 0.35f || spanX > 14f || spanZ > 14f) return null
    return TerrainObjectSelection(
        groupId,
        tris.size,
        b.minX, b.minY, b.minZ,
        b.maxX, b.maxY, b.maxZ,
        collisionCellsForObject(map, tris, b),
    )
  }

  private fun collisionCellsForObject(
      map: NdsMap,
      tris: List<de.lananahwp.openmmo.mapeditor.core.NdsTri>,
      bounds: TerrainBounds,
  ): List<Pair<Int, Int>> {
    val lowLimit = bounds.minY + minOf(1f, (bounds.maxY - bounds.minY) * 0.25f).coerceAtLeast(0.35f)
    val low = TerrainBounds()
    var foundLow = false
    fun include(x: Float, y: Float, z: Float) {
      if (y <= lowLimit) {
        low.include(x, y, z)
        foundLow = true
      }
    }
    for (t in tris) {
      include(t.ax, t.ay, t.az); include(t.bx, t.by, t.bz); include(t.cx, t.cy, t.cz)
    }
    val b = if (foundLow) low else bounds
    val cells = mutableListOf<Pair<Int, Int>>()
    val minX = kotlin.math.floor(b.minX.toDouble()).toInt().coerceAtLeast(0)
    val maxX = kotlin.math.floor(b.maxX.toDouble()).toInt().coerceAtMost(map.grid.cols - 1)
    val minZ = kotlin.math.floor(b.minZ.toDouble()).toInt().coerceAtLeast(0)
    val maxZ = kotlin.math.floor(b.maxZ.toDouble()).toInt().coerceAtMost(map.grid.rows - 1)
    for (z in minZ..maxZ) for (x in minX..maxX) {
      val cx = x + 0.5f
      val cz = z + 0.5f
      if (cx in (b.minX - 0.2f)..(b.maxX + 0.2f) &&
          cz in (b.minZ - 0.2f)..(b.maxZ + 0.2f)) cells += x to z
    }
    if (cells.isEmpty()) {
      val x = kotlin.math.floor(((bounds.minX + bounds.maxX) / 2f).toDouble()).toInt()
          .coerceIn(0, map.grid.cols - 1)
      val z = kotlin.math.floor(((bounds.minZ + bounds.maxZ) / 2f).toDouble()).toInt()
          .coerceIn(0, map.grid.rows - 1)
      cells += x to z
    }
    return cells.distinct()
  }

  fun removeTerrainObject(
      map: NdsMap,
      selection: TerrainObjectSelection,
      clearCollision: Boolean,
  ): NdsTerrainRemoval? {
    if (map.terrainRemovals.any { it.groupId == selection.groupId }) return null
    val restore = mutableListOf<NdsCollisionRestore>()
    if (clearCollision) {
      for ((x, z) in selection.collisionCells) {
        val previous = map.grid.collisionAt(x, z)
        if (previous == 0) continue
        restore += NdsCollisionRestore(x, z, previous)
        map.grid.setCollision(x, z, 0)
      }
    }
    return NdsTerrainRemoval(
        id = "terrain-${System.currentTimeMillis()}-${(Math.random() * 1_000_000).toInt()}",
        groupId = selection.groupId,
        clearedCollision = restore,
    ).also { map.terrainRemovals += it }
  }

  /** Removes a placed ROM/custom prop through the same reversible scenery-removal history. */
  fun removePropObject(map: NdsMap, propId: String, clearCollision: Boolean): NdsTerrainRemoval? {
    val prop = map.props.firstOrNull { it.id == propId } ?: return null
    val groupId = "prop:${prop.id}"
    val tris = editablePropTriangles(map).filter { it.editGroup == groupId }
    val restore = mutableListOf<NdsCollisionRestore>()
    if (clearCollision && tris.isNotEmpty()) {
      val bounds = TerrainBounds()
      for (tri in tris) {
        bounds.include(tri.ax, tri.ay, tri.az)
        bounds.include(tri.bx, tri.by, tri.bz)
        bounds.include(tri.cx, tri.cy, tri.cz)
      }
      for ((x, z) in collisionCellsForObject(map, tris, bounds)) {
        val previous = map.grid.collisionAt(x, z)
        if (previous == 0) continue
        restore += NdsCollisionRestore(x, z, previous)
        map.grid.setCollision(x, z, 0)
      }
    }
    map.props.remove(prop)
    return NdsTerrainRemoval(
        id = "prop-removal-${System.currentTimeMillis()}-${(Math.random() * 1_000_000).toInt()}",
        groupId = "",
        clearedCollision = restore,
        removedProp = prop.copy(),
    ).also { map.terrainRemovals += it }
  }

  fun restoreLastTerrainObject(map: NdsMap): NdsTerrainRemoval? {
    val removal = map.terrainRemovals.removeLastOrNull() ?: return null
    removal.removedProp?.let { prop ->
      if (map.props.none { it.id == prop.id }) map.props += prop.copy()
    }
    for (cell in removal.clearedCollision) {
      map.grid.setCollision(cell.x, cell.z, cell.collision)
    }
    return removal
  }

  /** Fits an arbitrary imported map model into the editable map footprint without distortion. */
  private fun fitImportedModel(
      tris: List<de.lananahwp.openmmo.mapeditor.core.NdsTri>,
      grid: NdsGrid,
  ): List<de.lananahwp.openmmo.mapeditor.core.NdsTri> {
    val b = boundsOf(tris) ?: return emptyList()
    val spanX = (b.maxX - b.minX).coerceAtLeast(0.0001f)
    val spanZ = (b.maxZ - b.minZ).coerceAtLeast(0.0001f)
    val scale = minOf(grid.cols / spanX, grid.rows / spanZ)
    val offsetX = (grid.cols - spanX * scale) / 2f - b.minX * scale
    val offsetZ = (grid.rows - spanZ * scale) / 2f - b.minZ * scale
    return tris.map {
      it.copy(
          ax = it.ax * scale + offsetX, ay = (it.ay - b.minY) * scale, az = it.az * scale + offsetZ,
          bx = it.bx * scale + offsetX, by = (it.by - b.minY) * scale, bz = it.bz * scale + offsetZ,
          cx = it.cx * scale + offsetX, cy = (it.cy - b.minY) * scale, cz = it.cz * scale + offsetZ,
      )
    }
  }

  /** The building model NARC entries (indexed by building model id). */
  private val buildModelFiles: List<ByteArray>? by lazy {
    val path =
        if (family == de.lananahwp.openmmo.mapeditor.core.NdsFamily.PLATINUM)
          "fielddata/build_model/build_model.narc"
        else "a/0/4/0"
    narcFiles(path)
  }

  /**
   * Decodes the placed buildings/objects on the map into triangles, using DSPRE's
   * ScaleTranslateRotateBuilding transform. Buildings have their own model ids and a
   * per-cell local position; the result is in the same world-tile space as [trianglesFor].
   */
  fun buildingTrianglesFor(map: NdsMap): List<de.lananahwp.openmmo.mapeditor.core.NdsTri> {
    return editablePropTriangles(map)
  }

  /** Built-in Tile-mode paint, baked into the same map-space geometry as the ROM terrain. */
  fun builtInTileTrianglesFor(map: NdsMap): List<NdsTri> {
    val surface = tileSurfaceHeights(map)
    val out = ArrayList<NdsTri>()
    for (layer in 0 until NdsGrid.LAYERS) {
      for (x in 0 until map.grid.cols) for (z in 0 until map.grid.rows) {
        val tile = map.grid.tileAt(layer, x, z)
        if (tile < 0 || NdsTileset.isCustom(tile)) continue
        val def = NdsTileset.tiles.getOrNull(tile) ?: continue
        val ground = surface[x][z].takeUnless(Float::isNaN) ?: 0f
        val base = ground + map.grid.heightAt(layer, x, z)
        val top = shadeTileColor(def.topColor, layer)
        when (def.shape) {
          TileShape.FLAT ->
              addTileQuad(
                  out,
                  x.toFloat(),
                  base + 0.01f,
                  z.toFloat(),
                  (x + 1).toFloat(),
                  base + 0.01f,
                  (z + 1).toFloat(),
                  top,
              )
          TileShape.CUBE, TileShape.BLOCK -> {
            val x0 = x.toFloat()
            val x1 = (x + 1).toFloat()
            val z0 = z.toFloat()
            val z1 = (z + 1).toFloat()
            val y1 = base + def.height
            addTileQuad(out, x0, y1, z0, x1, y1, z1, top)
            val side = shadeTileColor(def.sideColor, layer)
            addTileQuad(out, x0, base, z0, x1, y1, z0, side)
            addTileQuad(out, x0, base, z1, x1, y1, z1, side)
            addTileQuad(out, x0, base, z0, x0, y1, z1, side)
            addTileQuad(out, x1, base, z0, x1, y1, z1, side)
          }
        }
      }
    }
    return out
  }

  /** Matches the editor's per-cell terrain lookup, but keeps values in exported map space. */
  private fun tileSurfaceHeights(map: NdsMap): Array<FloatArray> {
    val out = Array(map.grid.cols) { FloatArray(map.grid.rows) { Float.NaN } }
    for (tri in trianglesFor(map)) {
      val minX = maxOf(0, floor(minOf(tri.ax, tri.bx, tri.cx)).toInt())
      val maxX = minOf(map.grid.cols - 1, floor(maxOf(tri.ax, tri.bx, tri.cx)).toInt())
      val minZ = maxOf(0, floor(minOf(tri.az, tri.bz, tri.cz)).toInt())
      val maxZ = minOf(map.grid.rows - 1, floor(maxOf(tri.az, tri.bz, tri.cz)).toInt())
      if (minX > maxX || minZ > maxZ) continue
      val top = maxOf(tri.ay, tri.by, tri.cy)
      for (x in minX..maxX) for (z in minZ..maxZ) {
        if (out[x][z].isNaN() || top > out[x][z]) out[x][z] = top
      }
    }
    return out
  }

  private fun shadeTileColor(color: Color, layer: Int): Int {
    val shade = 1f - layer * 0.06f
    return Color(
            (color.red * shade).toInt().coerceIn(0, 255),
            (color.green * shade).toInt().coerceIn(0, 255),
            (color.blue * shade).toInt().coerceIn(0, 255),
        )
        .rgb
  }

  /** Adds a horizontal or vertical quad described by two opposite corners. */
  private fun addTileQuad(
      out: MutableList<NdsTri>,
      x0: Float,
      y0: Float,
      z0: Float,
      x1: Float,
      y1: Float,
      z1: Float,
      color: Int,
  ) {
    val vertices =
        when {
          y0 == y1 -> arrayOf(floatArrayOf(x0, y0, z0), floatArrayOf(x1, y0, z0), floatArrayOf(x1, y1, z1), floatArrayOf(x0, y1, z1))
          z0 == z1 -> arrayOf(floatArrayOf(x0, y0, z0), floatArrayOf(x1, y0, z0), floatArrayOf(x1, y1, z1), floatArrayOf(x0, y1, z1))
          else -> arrayOf(floatArrayOf(x0, y0, z0), floatArrayOf(x0, y0, z1), floatArrayOf(x1, y1, z1), floatArrayOf(x1, y1, z0))
        }
    fun triangle(a: FloatArray, b: FloatArray, c: FloatArray) =
        NdsTri(
            a[0], a[1], a[2],
            b[0], b[1], b[2],
            c[0], c[1], c[2],
            color,
            0f, 0f, 0f, 0f, 0f, 0f,
        )
    out += triangle(vertices[0], vertices[1], vertices[2])
    out += triangle(vertices[0], vertices[2], vertices[3])
  }

  /** Painted shared-library tiles, positioned exactly as the editor's 3D views position them. */
  fun customTileTrianglesFor(map: NdsMap): List<de.lananahwp.openmmo.mapeditor.core.NdsTri> {
    val geometry = NdsCustomTileStore.viewGeometry()
    if (geometry.isEmpty()) return emptyList()
    val out = ArrayList<de.lananahwp.openmmo.mapeditor.core.NdsTri>()
    for (layer in 0 until de.lananahwp.openmmo.mapeditor.model.NdsGrid.LAYERS) {
      for (x in 0 until map.grid.cols) for (z in 0 until map.grid.rows) {
        val tile = map.grid.tileAt(layer, x, z)
        if (!de.lananahwp.openmmo.mapeditor.core.NdsTileset.isCustom(tile)) continue
        val base = map.grid.heightAt(layer, x, z).toFloat()
        for (triangle in geometry[tile].orEmpty()) {
          out +=
              triangle.copy(
                  ax = x + triangle.ax,
                  ay = base + triangle.ay,
                  az = z + triangle.az,
                  bx = x + triangle.bx,
                  by = base + triangle.by,
                  bz = z + triangle.bz,
                  cx = x + triangle.cx,
                  cy = base + triangle.cy,
                  cz = z + triangle.cz,
              )
        }
      }
    }
    return out
  }

  /** Baked texture data belonging to the custom tiles actually painted on this map. */
  fun customTileTexturesFor(
      map: NdsMap,
  ): Map<String, de.lananahwp.openmmo.mapeditor.core.NdsTexture> {
    val used = usedCustomTiles(map)
    return buildMap {
      for (index in used) {
        val prefix = NdsCustomTileStore.texturePrefix(index)
        for ((name, texture) in NdsCustomTileStore.mesh(index)?.textures.orEmpty()) {
          put(prefix + name, texture)
        }
      }
    }
  }

  fun customTilePalettesFor(map: NdsMap): Map<String, IntArray> {
    val used = usedCustomTiles(map)
    return buildMap {
      for (index in used) {
        val prefix = NdsCustomTileStore.texturePrefix(index)
        for ((name, palette) in NdsCustomTileStore.mesh(index)?.palettes.orEmpty()) {
          put(prefix + name, palette)
        }
      }
    }
  }

  private fun usedCustomTiles(map: NdsMap): Set<Int> = buildSet {
    for (layer in 0 until de.lananahwp.openmmo.mapeditor.model.NdsGrid.LAYERS) {
      for (x in 0 until map.grid.cols) for (z in 0 until map.grid.rows) {
        map.grid.tileAt(layer, x, z).takeIf(
            de.lananahwp.openmmo.mapeditor.core.NdsTileset::isCustom)?.let(::add)
      }
    }
  }

  private fun editablePropTriangles(map: NdsMap): List<de.lananahwp.openmmo.mapeditor.core.NdsTri> {
    val out = mutableListOf<de.lananahwp.openmmo.mapeditor.core.NdsTri>()
    for (prop in map.props) {
      val imported = !prop.modelKey.startsWith("rom:")
      val namespaceTextures = imported && propHasOwnTextures(prop.modelKey)
      for (tri in propModelTriangles(prop.modelKey)) {
        fun transform(x: Float, y: Float, z: Float): FloatArray {
          var px = x.toDouble(); var py = y.toDouble(); var pz = z.toDouble()
          var c = kotlin.math.cos(Math.toRadians(prop.rotationX.toDouble()))
          var s = kotlin.math.sin(Math.toRadians(prop.rotationX.toDouble()))
          val y1 = py * c - pz * s; val z1 = py * s + pz * c; py = y1; pz = z1
          c = kotlin.math.cos(Math.toRadians(prop.rotationY.toDouble()))
          s = kotlin.math.sin(Math.toRadians(prop.rotationY.toDouble()))
          val x2 = px * c + pz * s; val z2 = -px * s + pz * c; px = x2; pz = z2
          c = kotlin.math.cos(Math.toRadians(prop.rotationZ.toDouble()))
          s = kotlin.math.sin(Math.toRadians(prop.rotationZ.toDouble()))
          val x3 = px * c - py * s; val y3 = px * s + py * c; px = x3; py = y3
          return floatArrayOf(
              prop.x + px.toFloat() * prop.scaleX,
              prop.y + py.toFloat() * prop.scaleY,
              prop.z + pz.toFloat() * prop.scaleZ,
          )
        }
        val a = transform(tri.ax, tri.ay, tri.az)
        val b = transform(tri.bx, tri.by, tri.bz)
        val c = transform(tri.cx, tri.cy, tri.cz)
        val prefix = if (namespaceTextures) "${prop.modelKey}::" else ""
        out += tri.copy(
            ax = a[0], ay = a[1], az = a[2],
            bx = b[0], by = b[1], bz = b[2],
            cx = c[0], cy = c[1], cz = c[2],
            texture = if (tri.texture.isEmpty()) "" else prefix + tri.texture,
            palette = if (tri.palette.isEmpty()) "" else prefix + tri.palette,
            editGroup = "prop:${prop.id}",
        )
      }
    }
    return out
  }

  /** The map's area-data entry (buildingsTileset u16 at 0, mapTileset u16 at 2). */
  private fun areaDataFor(map: NdsMap): ByteArray? {
    val platinum = family == de.lananahwp.openmmo.mapeditor.core.NdsFamily.PLATINUM
    val areaNarc = if (platinum) "fielddata/areadata/area_data.narc" else "a/0/4/2"
    val areaIndex = if (platinum) map.header.areaDataArchiveID else map.header.areaDataBank
    return narcFiles(areaNarc)?.getOrNull(areaIndex)
  }

  /** The building texture pack (areabm_texset.narc) for the map's area, if any. */
  private fun buildingTextureFiles(): List<ByteArray> {
    val platinum = family == de.lananahwp.openmmo.mapeditor.core.NdsFamily.PLATINUM
    val texNarc = if (platinum) "fielddata/areadata/area_build_model/areabm_texset.narc" else "a/0/7/0"
    return narcFiles(texNarc).orEmpty()
  }

  private fun buildingTexturePack(map: NdsMap): ByteArray? {
    val area = areaDataFor(map) ?: return null
    if (area.size < 2) return null
    val buildingsTileset =
        (area[0].toInt() and 0xFF) or ((area[1].toInt() and 0xFF) shl 8)
    return buildingTextureFiles().getOrNull(buildingsTileset)
  }

  /** Decoded textures (by name) for a map, from the ROM's area texture pack. */
  fun texturesFor(map: NdsMap): Map<String, de.lananahwp.openmmo.mapeditor.core.NdsTexture> {
    return try {
      val terrainNames = trianglesFor(map).map { it.texture }.filter { it.isNotEmpty() }.toSet()
      val modelNames = terrainNames +
          buildingTrianglesFor(map).map { it.texture }.filter { it.isNotEmpty() }
      if (modelNames.isEmpty()) return emptyMap()

      val packs = importedTexturePacks(map) + orderedPacks(map).orEmpty()

      // Merge matching textures across the area pack AND every other pack, also under their
      // base name (stripping time-of-day suffixes like _d/_g/_m/_re).
      val merged = LinkedHashMap<String, de.lananahwp.openmmo.mapeditor.core.NdsTexture>()
      fun add(tex: de.lananahwp.openmmo.mapeditor.core.NdsTexture) {
        if (tex.name in terrainNames) merged.putIfAbsent(tex.name, tex)
        val base = baseTextureName(tex.name)
        if (base != tex.name && base in terrainNames) merged.putIfAbsent(base, tex)
      }
      for (f in packs) {
        for (tex in de.lananahwp.openmmo.mapeditor.core.NdsNsbtx.parse(f)) add(tex)
      }
      // Building textures come from a separate pack; add them all by name.
      buildingTexturePack(map)?.let { bp ->
        for (tex in de.lananahwp.openmmo.mapeditor.core.NdsNsbtx.parse(bp)) {
          merged.putIfAbsent(tex.name, tex)
        }
      }
      for (key in map.props.map { it.modelKey }.filter { !it.startsWith("rom:") }.distinct()) {
        // An extracted prop carries its textures in its own baked mesh rather than an NSBTX pack.
        for ((name, tex) in extractedPropTextures(key)) merged.putIfAbsent(name, tex)
        for (packBytes in propTexturePacks(key)) {
          for (tex in de.lananahwp.openmmo.mapeditor.core.NdsNsbtx.parse(packBytes)) {
            merged.putIfAbsent("$key::${tex.name}", tex)
          }
        }
      }
      // A ROM catalog prop can be placed on a map whose area pack never used that model.
      // Keep the current area's textures authoritative, then fill only unresolved names from
      // the complete building-texture catalog.
      for (name in modelNames) {
        if (name !in merged && "::" !in name) {
          buildingTextureIndex.textures[name]?.let { merged[name] = it }
        }
      }
      for (name in modelNames) {
        if (name !in merged) {
          val base = baseTextureName(name)
          merged[base]?.let { merged[name] = it }
        }
      }
      if (merged.isNotEmpty()) return merged
      emptyMap()
    } catch (_: Throwable) {
      emptyMap()
    }
  }

  /** Palettes (by name) for a map's texture packs, preferring the map's own area pack. */
  fun palettesFor(map: NdsMap): Map<String, IntArray> {
    return try {
      val modelPaletteNames =
          buildingTrianglesFor(map).map { it.palette }.filter { it.isNotEmpty() }.toSet()
      val packs = importedTexturePacks(map) + orderedPacks(map).orEmpty()
      val out = LinkedHashMap<String, IntArray>()
      for (f in packs) {
        val pack = de.lananahwp.openmmo.mapeditor.core.NdsNsbtx.parsePack(f)
        for ((name, colors) in pack.palettes) out.putIfAbsent(name, colors)
      }
      buildingTexturePack(map)?.let { bp ->
        val pack = de.lananahwp.openmmo.mapeditor.core.NdsNsbtx.parsePack(bp)
        for ((name, colors) in pack.palettes) out.putIfAbsent(name, colors)
      }
      for (key in map.props.map { it.modelKey }.filter { !it.startsWith("rom:") }.distinct()) {
        // An extracted prop carries its palettes in its own baked mesh rather than an NSBTX pack.
        for ((name, colors) in extractedPropPalettes(key)) out.putIfAbsent(name, colors)
        for (packBytes in propTexturePacks(key)) {
          val pack = de.lananahwp.openmmo.mapeditor.core.NdsNsbtx.parsePack(packBytes)
          for ((name, colors) in pack.palettes) out.putIfAbsent("$key::$name", colors)
        }
      }
      for (name in modelPaletteNames) {
        if (name !in out && "::" !in name) {
          buildingTextureIndex.palettes[name]?.let { out[name] = it }
        }
      }
      out
    } catch (_: Throwable) {
      emptyMap()
    }
  }

  /** A separate NSBTX is authoritative; embedded TEX0 in NSBMD is the fallback. */
  private fun importedTexturePacks(map: NdsMap): List<ByteArray> =
      listOf(importedTextureFile(map.name), importedModelFile(map.name))
          .filter { it.isFile }
          .map { it.readBytes() }
          .filter { de.lananahwp.openmmo.mapeditor.core.NdsNsbtx.parsePack(it).textures.isNotEmpty() }

  private fun propTexturePacks(modelKey: String): List<ByteArray> =
      propTexturePackCache.getOrPut(modelKey) {
        listOf(propTextureFile(modelKey), propModelFile(modelKey))
            .filter { it.isFile }
            .map { it.readBytes() }
            .filter { de.lananahwp.openmmo.mapeditor.core.NdsNsbtx.parsePack(it).textures.isNotEmpty() }
      }

  /** Texture packs with the area's declared pack first, then fallbacks ordered by name match. */
  private fun orderedPacks(map: NdsMap): List<ByteArray>? {
    val platinum = family == de.lananahwp.openmmo.mapeditor.core.NdsFamily.PLATINUM
    val texNarc = if (platinum) "fielddata/areadata/area_map_tex/map_tex_set.narc" else "a/0/4/4"
    val texFiles = narcFiles(texNarc) ?: return null
    val modelNames = trianglesFor(map).map { it.texture }.filter { it.isNotEmpty() }.toSet()
    val area = areaDataFor(map)
    val preferredIndex =
        if (area != null && area.size >= 4) {
          (area[2].toInt() and 0xFF) or ((area[3].toInt() and 0xFF) shl 8)
        } else {
          -1
        }

    // Score fallbacks by how many model texture names they provide. The declared
    // area pack remains authoritative when another area's pack has the same names
    // but different pixels or palettes.
    val scored = ArrayList<Triple<Boolean, Int, ByteArray>>()
    for ((index, f) in texFiles.withIndex()) {
      var score = 0
      try {
        for (tex in de.lananahwp.openmmo.mapeditor.core.NdsNsbtx.parse(f)) {
          if (tex.name in modelNames) score++
          else {
            val base = baseTextureName(tex.name)
            if (base in modelNames) score++
          }
        }
      } catch (_: Throwable) {}
      scored += Triple(index == preferredIndex, score, f)
    }
    scored.sortWith(compareByDescending<Triple<Boolean, Int, ByteArray>> { it.first }
        .thenByDescending { it.second })
    return scored.map { it.third }
  }

  /** Strips a trailing time-of-day suffix, e.g. `treekn_re` -> `treekn`. */
  private fun baseTextureName(name: String): String {
    for (suffix in listOf("_d", "_g", "_m", "_n", "_e", "_re", "_rg", "_rd", "_rm")) {
      if (name.endsWith(suffix) && name.length > suffix.length) {
        return name.dropLast(suffix.length)
      }
    }
    return name
  }

  // ---- Persistence ---------------------------------------------------------

  private fun overrideDir(): File = File(overrideRoot, "nds")

  private fun safeName(name: String): String = name.replace(Regex("[^A-Za-z0-9_.-]"), "_")

  private fun customMapsDir(): File = File(overrideDir(), "maps")

  private fun mapOverrideDir(name: String): File = File(customMapsDir(), safeName(name))

  private fun customManifestFile(name: String): File = File(mapOverrideDir(name), "map.json")

  private fun importedModelFile(name: String): File = File(mapOverrideDir(name), "model.nsbmd")

  private fun importedTextureFile(name: String): File = File(mapOverrideDir(name), "textures.nsbtx")

  private fun customEventsFile(name: String): File = File(mapOverrideDir(name), "events.json")

  private fun propsFile(name: String): File = File(mapOverrideDir(name), "props.json")

  private fun propModelsDir(): File = File(overrideDir(), "prop-models")

  private fun propModelDir(key: String): File = File(propModelsDir(), safeName(key))

  private fun propModelManifest(key: String): File = File(propModelDir(key), "model.json")

  private fun propModelFile(key: String): File = File(propModelDir(key), "model.nsbmd")

  private fun propTextureFile(key: String): File = File(propModelDir(key), "textures.nsbtx")

  private fun propMeshFile(key: String): File = File(propModelDir(key), "mesh.bin")

  private fun customMapNames(): List<String> =
      customMapsDir().listFiles()
          ?.filter { it.isDirectory && File(it, "map.json").isFile }
          ?.mapNotNull {
            try {
              JsonParser.parse(File(it, "map.json").readText()).asObj()?.str("name")
            } catch (_: Throwable) {
              null
            }
          }
          ?.sorted()
          .orEmpty()

  private fun customMatrixCells(): List<Gen4Decomp.MatrixCell> = customMapNames().flatMap { name ->
    try {
      val root = JsonParser.parse(customManifestFile(name).readText()).asObj()
          ?: return@flatMap emptyList()
      val id = root.int("mapId") ?: return@flatMap emptyList()
      root.arr("matrixCells")?.items.orEmpty().mapNotNull { item ->
        val cell = item.asObj() ?: return@mapNotNull null
        val x = cell.int("x") ?: return@mapNotNull null
        val y = cell.int("y") ?: return@mapNotNull null
        Gen4Decomp.MatrixCell(x, y, id)
      }
    } catch (_: Throwable) {
      emptyList()
    }
  }

  private fun gridFile(name: String): File =
      if (customManifestFile(name).isFile) File(mapOverrideDir(name), "grid.json")
      else File(overrideDir(), "$name.json")

  data class ModelImportResult(val triangles: Int, val textures: Int, val hasSeparateTextures: Boolean)

  /** Creates a new editable DS map without modifying the source ROM/decomp. */
  fun createMap(
      name: String,
      displayName: String,
      mapId: Int,
      cellsWide: Int,
      cellsHigh: Int,
      matrixX: Int? = null,
      matrixY: Int? = null,
      templateName: String? = null,
      modelFile: File? = null,
      textureFile: File? = null,
  ): NdsMap {
    require(name.matches(Regex("MAP_[A-Z0-9_]+"))) { "Map name must be MAP_UPPER_SNAKE_CASE" }
    require(!hasMap(name)) { "A map named '$name' already exists" }
    require(mapId in 0..0xFFFF) { "Map ID must be between 0 and 65535" }
    require(!mapIdInUse(mapId)) { "Map ID $mapId is already in use" }
    require(cellsWide in 1..32 && cellsHigh in 1..32) { "Map size must be 1–32 matrix cells" }
    require((matrixX == null) == (matrixY == null)) { "World matrix X and Y must be supplied together" }
    if (matrixX != null && matrixY != null) {
      require(matrixX >= 0 && matrixY >= 0) { "World matrix coordinates cannot be negative" }
      require(matrixX + cellsWide <= 256 && matrixY + cellsHigh <= 256) {
        "The map must fit inside the 256×256 world-matrix coordinate range"
      }
      require(!matrixPlacementConflicts(matrixX, matrixY, cellsWide, cellsHigh)) {
        "That world-matrix area overlaps an existing map"
      }
    }
    validateImportFiles(modelFile, textureFile)

    val template = templateName?.let(::loadMap)
    val header = template?.header?.let(::copyHeader) ?: NdsMapHeader().also {
      it.regionNo = if (family == de.lananahwp.openmmo.mapeditor.core.NdsFamily.PLATINUM)
        "MAP_REGION_SINNOH" else "MAP_REGION_JOHTO"
    }
    header.name = name
    header.eventsFile = "events_${name.removePrefix("MAP_").lowercase()}"
    val map = NdsMap(
        name,
        mapId,
        header,
        NdsEvents(),
        NdsGrid(cellsWide * NdsGrid.COLS, cellsHigh * NdsGrid.ROWS),
        displayName.ifBlank { name },
        true,
    )
    if (matrixX != null && matrixY != null) {
      for (y in 0 until cellsHigh) for (x in 0 until cellsWide) {
        map.matrixCells += (matrixX + x) to (matrixY + y)
      }
    }
    saveCustomMap(map)
    if (modelFile != null) importModel(map, modelFile, textureFile)
    maps[name] = map
    return map
  }

  fun hasImportedModel(map: NdsMap): Boolean = importedModelFile(map.name).isFile

  /** Installs an external NSBMD and optional NSBTX as this map's model override. */
  fun importModel(map: NdsMap, modelFile: File, textureFile: File? = null): ModelImportResult {
    val checked = validateImportFiles(modelFile, textureFile)
    val dir = mapOverrideDir(map.name)
    dir.mkdirs()
    val modelTarget = importedModelFile(map.name)
    if (modelFile.canonicalFile != modelTarget.canonicalFile) modelFile.copyTo(modelTarget, overwrite = true)
    val textureTarget = importedTextureFile(map.name)
    if (textureFile != null) {
      if (textureFile.canonicalFile != textureTarget.canonicalFile) textureFile.copyTo(textureTarget, overwrite = true)
    } else if (textureTarget.isFile) {
      textureTarget.delete()
    }
    terrainTriangleCache.remove(map.name)
    map.terrainRemovals.clear()
    map.terrainTransforms.clear()
    return ModelImportResult(checked.first, checked.second, textureFile != null)
  }

  /** ROM building models plus reusable models imported into this project. */
  fun propModels(): List<PropModelInfo> {
    val imported = propModelsDir().listFiles()
        ?.filter { it.isDirectory && File(it, "model.json").isFile }
        ?.mapNotNull { dir ->
          try {
            val o = JsonParser.parse(File(dir, "model.json").readText()).asObj() ?: return@mapNotNull null
            // An imported prop is an NSBMD on disk; an extracted one is a baked mesh snapshot.
            val extracted = o.str("source") == "extracted"
            val payload = if (extracted) File(dir, "mesh.bin") else File(dir, "model.nsbmd")
            if (!payload.isFile) return@mapNotNull null
            PropModelInfo(
                o.str("key") ?: dir.name,
                o.str("label") ?: dir.name,
                true,
                if (extracted) EXTRACTED_CATEGORY else "Imported",
            )
          } catch (_: Throwable) {
            null
          }
        }
        ?.sortedBy { it.label.lowercase() }
        .orEmpty()
    val romModels = buildModelFiles.orEmpty().indices.drop(1).map { id ->
      val description = NdsPropCatalog.describe(family, id, propModelTriangles("rom:$id"))
      PropModelInfo("rom:$id", description.name, false, description.category)
    }
    return imported + romModels
  }

  /** Decodes one catalog entry with the texture set used by the currently open map. */
  fun propModelPreview(modelKey: String, map: NdsMap?): PropModelPreview {
    val triangles = propModelTriangles(modelKey)
    if (triangles.isEmpty()) return PropModelPreview(emptyList(), emptyMap(), emptyMap())
    // An extracted prop is fully self-describing; its snapshot already holds exactly the textures
    // and palettes its triangles name, under those same (un-namespaced) names.
    extractedMesh(modelKey)?.let { mesh ->
      return PropModelPreview(mesh.triangles, mesh.textures, mesh.palettes)
    }
    val referencedTextures = triangles.map { it.texture }.filter { it.isNotEmpty() }.toSet()
    val referencedPalettes = triangles.map { it.palette }.filter { it.isNotEmpty() }.toSet()

    val packs =
        if (modelKey.startsWith("rom:")) {
          buildList {
            map?.let(::buildingTexturePack)?.let(::add)
            propModelBytes(modelKey)?.let(::add)
          }
        } else {
          propTexturePacks(modelKey)
        }
    val textures = LinkedHashMap<String, de.lananahwp.openmmo.mapeditor.core.NdsTexture>()
    val palettes = LinkedHashMap<String, IntArray>()
    for (bytes in packs) {
      val pack = de.lananahwp.openmmo.mapeditor.core.NdsNsbtx.parsePack(bytes)
      for (texture in pack.textures) {
        if (texture.name in referencedTextures) textures.putIfAbsent(texture.name, texture)
        val base = baseTextureName(texture.name)
        if (base in referencedTextures) textures.putIfAbsent(base, texture)
      }
      for ((name, colors) in pack.palettes) {
        if (name in referencedPalettes) palettes.putIfAbsent(name, colors)
      }
    }
    for (name in referencedTextures) {
      if (name !in textures) buildingTextureIndex.textures[name]?.let { textures[name] = it }
    }
    for (name in referencedPalettes) {
      if (name !in palettes) buildingTextureIndex.palettes[name]?.let { palettes[name] = it }
    }
    return PropModelPreview(triangles, textures, palettes)
  }

  /** Adds an external NSBMD/NSBTX pair to the reusable prop catalog. */
  fun importPropModel(label: String, modelFile: File, textureFile: File? = null): PropModelInfo {
    validateImportFiles(modelFile, textureFile)
    var base = label.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
    if (base.isEmpty()) base = modelFile.nameWithoutExtension.lowercase()
    var key = "custom:$base"
    var suffix = 2
    while (propModelManifest(key).exists()) key = "custom:${base}_${suffix++}"
    val dir = propModelDir(key)
    dir.mkdirs()
    modelFile.copyTo(propModelFile(key), overwrite = true)
    if (textureFile != null) textureFile.copyTo(propTextureFile(key), overwrite = true)
    val manifest = Json.JObj(linkedMapOf(
        "version" to Json.JNum(1.0),
        "key" to Json.JStr(key),
        "label" to Json.JStr(label.trim().ifEmpty { modelFile.nameWithoutExtension }),
    ))
    propModelManifest(key).writeText(JsonWriter.writePretty(manifest) + "\n")
    return PropModelInfo(key, manifest.str("label")!!, true, "Imported")
  }

  /** Creates a grounded one-tile prop placement from a catalog entry. */
  fun createProp(modelKey: String, x: Float, z: Float): NdsProp {
    val raw = propModelTriangles(modelKey)
    require(raw.isNotEmpty()) { "The selected prop model contains no supported geometry" }
    val b = boundsOf(raw) ?: error("The selected prop has no bounds")
    // An arbitrary NSBMD import has no meaningful relationship to tile size, so it gets normalised
    // to a single tile. Extracted geometry was already measured in map tiles when it was lifted,
    // so scaling it would shrink a 3x3 patch of path down to one square.
    val scale = if (extractedMesh(modelKey) != null) {
      1f
    } else {
      val span = maxOf(b.maxX - b.minX, b.maxZ - b.minZ, 0.0001f)
      1f / span
    }
    return NdsProp(
        id = "prop-${System.currentTimeMillis()}-${(Math.random() * 1_000_000).toInt()}",
        modelKey = modelKey,
        x = x,
        y = -b.minY * scale,
        z = z,
        scaleX = scale,
        scaleY = scale,
        scaleZ = scale,
    )
  }

  fun duplicateProp(source: NdsProp): NdsProp = source.copy(
      id = "prop-${System.currentTimeMillis()}-${(Math.random() * 1_000_000).toInt()}",
      x = source.x + 1f,
      z = source.z + 1f,
  )

  /** Picks a prop by its transformed ground footprint, with a little tolerance for narrow models. */
  fun propAt(map: NdsMap, x: Float, z: Float, tolerance: Float = 0.75f): NdsProp? {
    data class Candidate(val prop: NdsProp, val distanceSquared: Float, val area: Float)
    val propsById = map.props.associateBy { it.id }
    val bounds = LinkedHashMap<String, TerrainBounds>()
    for (tri in editablePropTriangles(map)) {
      val id = tri.editGroup.removePrefix("prop:")
      if (id !in propsById) continue
      val b = bounds.getOrPut(id) { TerrainBounds() }
      b.include(tri.ax, tri.ay, tri.az)
      b.include(tri.bx, tri.by, tri.bz)
      b.include(tri.cx, tri.cy, tri.cz)
    }
    return bounds.mapNotNull { (id, b) ->
      val dx = when {
        x < b.minX -> b.minX - x
        x > b.maxX -> x - b.maxX
        else -> 0f
      }
      val dz = when {
        z < b.minZ -> b.minZ - z
        z > b.maxZ -> z - b.maxZ
        else -> 0f
      }
      val distanceSquared = dx * dx + dz * dz
      if (distanceSquared > tolerance * tolerance) null
      else Candidate(
          propsById.getValue(id),
          distanceSquared,
          (b.maxX - b.minX).coerceAtLeast(0.001f) * (b.maxZ - b.minZ).coerceAtLeast(0.001f),
      )
    }.minWithOrNull(compareBy<Candidate> { it.distanceSquared }.thenBy { it.area })?.prop
  }

  private fun propModelBytes(modelKey: String): ByteArray? =
      if (modelKey.startsWith("rom:")) {
        modelKey.removePrefix("rom:").toIntOrNull()?.let { buildModelFiles?.getOrNull(it) }
      } else {
        propModelFile(modelKey).takeIf { it.isFile }?.readBytes()
      }

  private fun propModelTriangles(modelKey: String): List<de.lananahwp.openmmo.mapeditor.core.NdsTri> =
      propTriangleCache.getOrPut(modelKey) {
        extractedMesh(modelKey)?.triangles
            ?: propModelBytes(modelKey)?.let {
              de.lananahwp.openmmo.mapeditor.core.NdsNsbmd.decode(it, worldScale = false)
            }.orEmpty()
      }

  /**
   * Whether a catalog prop ships its own textures, and therefore needs its texture/palette names
   * namespaced per model so two props cannot collide on a shared name like `tex0`.
   */
  private fun propHasOwnTextures(modelKey: String): Boolean =
      extractedMesh(modelKey) != null || propTexturePacks(modelKey).isNotEmpty()

  /** Namespaced textures contributed by an extracted prop, matching [editablePropTriangles]. */
  private fun extractedPropTextures(
      modelKey: String,
  ): Map<String, de.lananahwp.openmmo.mapeditor.core.NdsTexture> =
      extractedMesh(modelKey)?.textures.orEmpty().mapKeys { "$modelKey::${it.key}" }

  private fun extractedPropPalettes(modelKey: String): Map<String, IntArray> =
      extractedMesh(modelKey)?.palettes.orEmpty().mapKeys { "$modelKey::${it.key}" }

  private fun validateImportFiles(modelFile: File?, textureFile: File?): Pair<Int, Int> {
    if (modelFile == null) {
      require(textureFile == null) { "Choose an NSBMD model before choosing a texture pack" }
      return 0 to 0
    }
    require(modelFile.isFile && modelFile.canRead()) { "Cannot read model ${modelFile.path}" }
    val modelBytes = modelFile.readBytes()
    require(modelBytes.take(4).toByteArray().toString(Charsets.US_ASCII) == "BMD0") {
      "${modelFile.name} is not an NSBMD (BMD0) file"
    }
    val triangles = de.lananahwp.openmmo.mapeditor.core.NdsNsbmd.decode(modelBytes).size
    require(triangles > 0) { "${modelFile.name} contains no supported model geometry" }
    var textures = de.lananahwp.openmmo.mapeditor.core.NdsNsbtx.parsePack(modelBytes).textures.size
    if (textureFile != null) {
      require(textureFile.isFile && textureFile.canRead()) { "Cannot read textures ${textureFile.path}" }
      val textureBytes = textureFile.readBytes()
      require(textureBytes.take(4).toByteArray().toString(Charsets.US_ASCII) == "BTX0") {
        "${textureFile.name} is not an NSBTX (BTX0) file"
      }
      textures = de.lananahwp.openmmo.mapeditor.core.NdsNsbtx.parsePack(textureBytes).textures.size
      require(textures > 0) { "${textureFile.name} contains no supported textures" }
    }
    return triangles to textures
  }

  private fun copyHeader(source: NdsMapHeader): NdsMapHeader = NdsMapHeader().also { h ->
    h.name = source.name
    h.wildEncounterBank = source.wildEncounterBank
    h.areaDataBank = source.areaDataBank
    h.areaDataArchiveID = source.areaDataArchiveID
    h.moveModelBank = source.moveModelBank
    h.worldMapX = source.worldMapX; h.worldMapY = source.worldMapY; h.matrixId = source.matrixId
    h.scriptsBank = source.scriptsBank; h.scriptHeaderBank = source.scriptHeaderBank; h.msgBank = source.msgBank
    h.dayMusicId = source.dayMusicId; h.nightMusicId = source.nightMusicId; h.eventsFile = source.eventsFile
    h.mapsec = source.mapsec; h.areaIcon = source.areaIcon; h.momCallIntroParam = source.momCallIntroParam
    h.regionNo = source.regionNo; h.weather = source.weather; h.mapType = source.mapType; h.cameraType = source.cameraType
    h.followMode = source.followMode; h.battleBg = source.battleBg
    h.bikeAllowed = source.bikeAllowed; h.runningAllowed = source.runningAllowed
    h.escapeRopeAllowed = source.escapeRopeAllowed; h.flyAllowed = source.flyAllowed
    h.outgoingCalls = source.outgoingCalls; h.incomingCalls = source.incomingCalls; h.radioSignal = source.radioSignal
  }

  /** Saves grid, header, and event edits back to the decomp. */
  fun save(map: NdsMap) {
    if (map.isCustom) {
      saveCustomMap(map)
      return
    }
    saveHeader(map)
    saveEvents(map)
    saveGrid(map)
    saveProps(map)
  }

  fun saveHeader(map: NdsMap) {
    if (map.isCustom) return
    if (family != de.lananahwp.openmmo.mapeditor.core.NdsFamily.HEART_GOLD) return
    val file = headerFile()
    if (!file.exists()) return
    val lines = file.readLines().toMutableList()
    val entryRe = Regex("""\[(${map.name})]\s*=\s*\{""")
    var blockStart = -1
    var blockEnd = lines.size
    for (i in lines.indices) {
      if (entryRe.find(lines[i]) != null) {
        blockStart = i
        var depth = 0
        for (j in i until lines.size) {
          depth += lines[j].count { it == '{' } - lines[j].count { it == '}' }
          if (depth <= 0 && j > i) {
            blockEnd = j + 1
            break
          }
        }
        break
      }
    }
    if (blockStart < 0) return
    val replacement = renderHeader(map.header)
    val updated = ArrayList<String>(lines.subList(0, blockStart))
    updated += replacement
    updated += lines.subList(blockEnd, lines.size)
    file.writeText(updated.joinToString("\n"))
  }

  fun saveEvents(map: NdsMap) {
    if (map.isCustom) {
      val file = customEventsFile(map.name)
      file.parentFile.mkdirs()
      file.writeText(renderEvents(map) + "\n")
      return
    }
    val base = eventsBankName(map) ?: return
    val file = eventsFile(base)
    file.parentFile.mkdirs()
    file.writeText(renderEvents(map) + "\n")
  }

  fun saveGrid(map: NdsMap) {
    val file = gridFile(map.name)
    file.parentFile.mkdirs()
    file.writeText(renderGrid(map) + "\n")
  }

  fun saveProps(map: NdsMap) {
    val file = propsFile(map.name)
    file.parentFile.mkdirs()
    file.writeText(renderProps(map) + "\n")
  }

  private fun saveCustomMap(map: NdsMap) {
    val manifest = customManifestFile(map.name)
    manifest.parentFile.mkdirs()
    manifest.writeText(renderCustomManifest(map) + "\n")
    saveEvents(map)
    saveGrid(map)
    saveProps(map)
  }

  // ---- Reading helpers -----------------------------------------------------

  private fun loadCustomMap(name: String): NdsMap? {
    val file = customManifestFile(name)
    if (!file.isFile) return null
    val root = JsonParser.parse(file.readText()).asObj() ?: return null
    val storedName = root.str("name") ?: return null
    if (storedName != name) return null
    val cols = root.int("cols")?.coerceAtLeast(1) ?: NdsGrid.COLS
    val rows = root.int("rows")?.coerceAtLeast(1) ?: NdsGrid.ROWS
    val header = root.obj("header")?.let(::headerFromJson) ?: NdsMapHeader().also { it.name = name }
    val events = loadCustomEvents(name)
    val map = NdsMap(
        name,
        root.int("mapId") ?: 0,
        header,
        events,
        NdsGrid(cols, rows),
        root.str("displayName") ?: name,
        true,
    )
    root.arr("matrixCells")?.items?.forEach { item ->
      item.asObj()?.let { cell -> map.matrixCells += (cell.int("x") ?: 0) to (cell.int("y") ?: 0) }
    }
    loadGridOverride(map)
    loadProps(map)
    return map
  }

  private fun loadCustomEvents(name: String): NdsEvents {
    val result = NdsEvents()
    val file = customEventsFile(name)
    if (!file.isFile) return result
    val root = JsonParser.parse(file.readText()).asObj() ?: return result
    result.header = root.str("header") ?: ""
    root.arr("objects")?.items?.forEach { item ->
      val o = item.asObj() ?: return@forEach
      result.objects += NdsObject(
          id = o.str("id") ?: "", spriteId = o.str("spriteId") ?: "SPRITE_NONE",
          movement = o.int("movement") ?: 0, type = o.int("type") ?: 0,
          eventFlag = o.str("eventFlag") ?: "FLAG_NOTHING", scriptId = o.str("scriptId") ?: "0",
          facingDirection = o.int("facingDirection") ?: 0, param0 = o.int("param0") ?: 0,
          param1 = o.int("param1") ?: 0, param2 = o.int("param2") ?: 0,
          xRange = o.int("xRange") ?: 0, yRange = o.int("yRange") ?: 0,
          x = o.int("x") ?: 0, z = o.int("z") ?: 0, y = o.int("y") ?: 0,
      )
    }
    root.arr("warps")?.items?.forEach { item ->
      val o = item.asObj() ?: return@forEach
      result.warps += NdsWarp(
          x = o.int("x") ?: 0, z = o.int("z") ?: 0, header = o.str("header") ?: "MAP_NOTHING",
          anchor = o.int("anchor") ?: 0, y = o.int("y") ?: 0,
      )
    }
    root.arr("coords")?.items?.forEach { item ->
      val o = item.asObj() ?: return@forEach
      result.triggers += NdsTrigger(
          scriptId = o.str("scriptId") ?: "0", x = o.int("x") ?: 0, z = o.int("z") ?: 0,
          w = o.int("w") ?: 1, h = o.int("h") ?: 1, y = o.int("y") ?: 0,
          variable = o.str("var") ?: "VAR_TEMP_x4000", value = o.int("val") ?: 0,
      )
    }
    root.arr("bgs")?.items?.forEach { item ->
      val o = item.asObj() ?: return@forEach
      result.bgEvents += NdsBgEvent(
          scriptId = o.str("scriptId") ?: "0", type = o.int("type") ?: 0,
          x = o.int("x") ?: 0, z = o.int("z") ?: 0, y = o.int("y") ?: 0, dir = o.int("dir") ?: 4,
      )
    }
    return result
  }

  /** Loads a saved prop override, or converts the ROM's building placements on first open. */
  private fun loadProps(map: NdsMap) {
    map.props.clear()
    map.terrainRemovals.clear()
    map.terrainTransforms.clear()
    val file = propsFile(map.name)
    if (file.isFile) {
      val root = JsonParser.parse(file.readText()).asObj() ?: return
      root.arr("props")?.items?.forEach { item ->
        val o = item.asObj() ?: return@forEach
        propFromJson(o)?.let(map.props::add)
      }
      root.arr("terrainRemovals")?.items?.forEach { item ->
        val o = item.asObj() ?: return@forEach
        val cleared = mutableListOf<NdsCollisionRestore>()
        o.arr("clearedCollision")?.items?.forEach { cellItem ->
          val cell = cellItem.asObj() ?: return@forEach
          cleared += NdsCollisionRestore(
              cell.int("x") ?: return@forEach,
              cell.int("z") ?: return@forEach,
              cell.int("collision") ?: 0,
          )
        }
        map.terrainRemovals += NdsTerrainRemoval(
            id = o.str("id") ?: return@forEach,
            groupId = o.str("groupId") ?: return@forEach,
            clearedCollision = cleared,
            removedProp = o.obj("removedProp")?.let(::propFromJson),
        )
      }
      root.arr("terrainTransforms")?.items?.forEach { item ->
        val o = item.asObj() ?: return@forEach
        map.terrainTransforms += NdsTerrainTransform(
            groupId = o.str("groupId") ?: return@forEach,
            offsetX = (o.double("offsetX") ?: 0.0).toFloat(),
            offsetY = (o.double("offsetY") ?: 0.0).toFloat(),
            offsetZ = (o.double("offsetZ") ?: 0.0).toFloat(),
        )
      }
      return
    }
    if (!map.isCustom) populatePropsFromRom(map)
  }

  private fun propFromJson(o: Json.JObj): NdsProp? {
    val id = o.str("id") ?: return null
    val modelKey = o.str("modelKey") ?: return null
    return NdsProp(
        id = id,
        modelKey = modelKey,
        x = (o.double("x") ?: 0.0).toFloat(),
        y = (o.double("y") ?: 0.0).toFloat(),
        z = (o.double("z") ?: 0.0).toFloat(),
        rotationX = (o.double("rotationX") ?: 0.0).toFloat(),
        rotationY = (o.double("rotationY") ?: 0.0).toFloat(),
        rotationZ = (o.double("rotationZ") ?: 0.0).toFloat(),
        scaleX = (o.double("scaleX") ?: 1.0).toFloat(),
        scaleY = (o.double("scaleY") ?: 1.0).toFloat(),
        scaleZ = (o.double("scaleZ") ?: 1.0).toFloat(),
    )
  }

  private fun propToJson(p: NdsProp): Json.JObj =
      Json.JObj(linkedMapOf(
          "id" to Json.JStr(p.id),
          "modelKey" to Json.JStr(p.modelKey),
          "x" to Json.JNum(p.x.toDouble()),
          "y" to Json.JNum(p.y.toDouble()),
          "z" to Json.JNum(p.z.toDouble()),
          "rotationX" to Json.JNum(p.rotationX.toDouble()),
          "rotationY" to Json.JNum(p.rotationY.toDouble()),
          "rotationZ" to Json.JNum(p.rotationZ.toDouble()),
          "scaleX" to Json.JNum(p.scaleX.toDouble()),
          "scaleY" to Json.JNum(p.scaleY.toDouble()),
          "scaleZ" to Json.JNum(p.scaleZ.toDouble()),
      ))

  private fun populatePropsFromRom(map: NdsMap) {
    val cells = resolveCells(map)
    if (cells.isEmpty()) return
    val (minX, minY, _, _) = footprint(cells)
    val terrainGround = cells.mapNotNull {
      mapFileIndex(it.fileIndex)?.modelBytes?.let(::cellMinY)
    }.minOrNull() ?: 0f
    for (cell in cells) {
      val buildings = mapFileIndex(cell.fileIndex)?.buildings.orEmpty()
      for ((index, b) in buildings.withIndex()) {
        val model = buildModelFiles?.getOrNull(b.modelId) ?: continue
        val modelScale = de.lananahwp.openmmo.mapeditor.core.NdsNsbmd.modelScaleOf(model)
        if (modelScale <= 0f) continue
        fun fraction(value: Int): Float = value / 65536f
        map.props += NdsProp(
            id = "rom-${cell.fileIndex}-$index",
            modelKey = "rom:${b.modelId}",
            x = (cell.cellX - minX) * 32f + 16f + b.xPosition + fraction(b.xFraction),
            y = b.yPosition + fraction(b.yFraction) - terrainGround * TILE_SCALE,
            z = (cell.cellY - minY) * 32f + 16f + b.zPosition + fraction(b.zFraction),
            rotationX = b.xRotation * 360f / 65536f,
            rotationY = b.yRotation * 360f / 65536f,
            rotationZ = b.zRotation * 360f / 65536f,
            scaleX = modelScale / 256f * b.width,
            scaleY = modelScale / 256f * b.height,
            scaleZ = modelScale / 256f * b.length,
        )
      }
    }
  }

  private fun headerFromJson(o: Json.JObj): NdsMapHeader = NdsMapHeader().also { h ->
    h.name = o.str("name") ?: h.name
    h.wildEncounterBank = o.str("wildEncounterBank") ?: h.wildEncounterBank
    h.areaDataBank = o.int("areaDataBank") ?: h.areaDataBank
    h.areaDataArchiveID = o.int("areaDataArchiveID") ?: h.areaDataArchiveID
    h.moveModelBank = o.int("moveModelBank") ?: h.moveModelBank
    h.worldMapX = o.int("worldMapX") ?: h.worldMapX; h.worldMapY = o.int("worldMapY") ?: h.worldMapY
    h.matrixId = o.int("matrixId") ?: h.matrixId
    h.scriptsBank = o.str("scriptsBank") ?: h.scriptsBank; h.scriptHeaderBank = o.str("scriptHeaderBank") ?: h.scriptHeaderBank
    h.msgBank = o.str("msgBank") ?: h.msgBank; h.dayMusicId = o.str("dayMusicId") ?: h.dayMusicId
    h.nightMusicId = o.str("nightMusicId") ?: h.nightMusicId; h.eventsFile = o.str("eventsFile") ?: h.eventsFile
    h.mapsec = o.str("mapsec") ?: h.mapsec; h.areaIcon = o.int("areaIcon") ?: h.areaIcon
    h.momCallIntroParam = o.int("momCallIntroParam") ?: h.momCallIntroParam; h.regionNo = o.str("regionNo") ?: h.regionNo
    h.weather = o.int("weather") ?: h.weather; h.mapType = o.int("mapType") ?: h.mapType; h.cameraType = o.int("cameraType") ?: h.cameraType
    h.followMode = o.str("followMode") ?: h.followMode; h.battleBg = o.str("battleBg") ?: h.battleBg
    h.bikeAllowed = o.get("bikeAllowed")?.asBool() ?: h.bikeAllowed
    h.runningAllowed = o.get("runningAllowed")?.asBool() ?: h.runningAllowed
    h.escapeRopeAllowed = o.get("escapeRopeAllowed")?.asBool() ?: h.escapeRopeAllowed
    h.flyAllowed = o.get("flyAllowed")?.asBool() ?: h.flyAllowed
    h.outgoingCalls = o.get("outgoingCalls")?.asBool() ?: h.outgoingCalls
    h.incomingCalls = o.get("incomingCalls")?.asBool() ?: h.incomingCalls
    h.radioSignal = o.get("radioSignal")?.asBool() ?: h.radioSignal
  }

  private fun headerFile(): File =
      if (family == de.lananahwp.openmmo.mapeditor.core.NdsFamily.HEART_GOLD)
        File(rootDir, "src/data/map_headers.h")
      else File(rootDir, "include/data/map_headers.h")

  private fun eventsBankName(map: NdsMap): String? {
    val header = map.header
    if (family == de.lananahwp.openmmo.mapeditor.core.NdsFamily.HEART_GOLD) {
      val idx = header.eventsFile.lastIndexOf("_bin")
      val base = if (idx >= 0) header.eventsFile.substring(0, idx) else header.eventsFile
      return base.substringAfterLast("zone_event_").takeIf { it.isNotBlank() }
    }
    return header.eventsFile.takeIf { it != "events_empty" }
  }

  private fun eventsFile(name: String): File =
      if (family == de.lananahwp.openmmo.mapeditor.core.NdsFamily.HEART_GOLD)
        File(rootDir, "files/fielddata/eventdata/zone_event/$name.json")
      else File(rootDir, "res/field/events/$name.json")

  private fun loadGridOverride(map: NdsMap) {
    val file = gridFile(map.name)
    if (!file.exists()) return
    val root = JsonParser.parse(file.readText()).asObj() ?: return
    val grid = map.grid
    for (layer in 0 until de.lananahwp.openmmo.mapeditor.model.NdsGrid.LAYERS) {
      root.arr("layer_$layer")?.items?.forEachIndexed { i, cell ->
        val c = cell.asObj() ?: return@forEachIndexed
        val x = i % grid.cols
        val y = i / grid.cols
        grid.setTile(layer, x, y, c.int("tile") ?: -1)
        grid.setHeight(layer, x, y, c.int("height") ?: 0)
      }
    }
    root.arr("collisions")?.items?.forEachIndexed { i, v ->
      val x = i % grid.cols
      val y = i / grid.cols
      grid.setCollision(x, y, v.asInt() ?: 0)
    }
    root.arr("permissions")?.items?.forEachIndexed { i, v ->
      val x = i % grid.cols
      val y = i / grid.cols
      grid.setPermission(x, y, v.asInt() ?: 0)
    }
  }

  // ---- Serialization -------------------------------------------------------

  private fun renderCustomManifest(map: NdsMap): String {
    val root = Json.JObj(linkedMapOf(
        "version" to Json.JNum(1.0),
        "name" to Json.JStr(map.name),
        "displayName" to Json.JStr(map.displayName),
        "mapId" to Json.JNum(map.mapId.toDouble()),
        "cols" to Json.JNum(map.grid.cols.toDouble()),
        "rows" to Json.JNum(map.grid.rows.toDouble()),
        "matrixCells" to Json.JArr(map.matrixCells.map { (x, y) ->
          Json.JObj(linkedMapOf("x" to Json.JNum(x.toDouble()), "y" to Json.JNum(y.toDouble())))
        }),
        "header" to headerToJson(map.header),
    ))
    return JsonWriter.writePretty(root)
  }

  private fun headerToJson(h: NdsMapHeader): Json.JObj = Json.JObj(linkedMapOf(
      "name" to Json.JStr(h.name),
      "wildEncounterBank" to Json.JStr(h.wildEncounterBank),
      "areaDataBank" to Json.JNum(h.areaDataBank.toDouble()),
      "areaDataArchiveID" to Json.JNum(h.areaDataArchiveID.toDouble()),
      "moveModelBank" to Json.JNum(h.moveModelBank.toDouble()),
      "worldMapX" to Json.JNum(h.worldMapX.toDouble()),
      "worldMapY" to Json.JNum(h.worldMapY.toDouble()),
      "matrixId" to Json.JNum(h.matrixId.toDouble()),
      "scriptsBank" to Json.JStr(h.scriptsBank),
      "scriptHeaderBank" to Json.JStr(h.scriptHeaderBank),
      "msgBank" to Json.JStr(h.msgBank),
      "dayMusicId" to Json.JStr(h.dayMusicId),
      "nightMusicId" to Json.JStr(h.nightMusicId),
      "eventsFile" to Json.JStr(h.eventsFile),
      "mapsec" to Json.JStr(h.mapsec),
      "areaIcon" to Json.JNum(h.areaIcon.toDouble()),
      "momCallIntroParam" to Json.JNum(h.momCallIntroParam.toDouble()),
      "regionNo" to Json.JStr(h.regionNo),
      "weather" to Json.JNum(h.weather.toDouble()),
      "mapType" to Json.JNum(h.mapType.toDouble()),
      "cameraType" to Json.JNum(h.cameraType.toDouble()),
      "followMode" to Json.JStr(h.followMode),
      "battleBg" to Json.JStr(h.battleBg),
      "bikeAllowed" to Json.JBool(h.bikeAllowed),
      "runningAllowed" to Json.JBool(h.runningAllowed),
      "escapeRopeAllowed" to Json.JBool(h.escapeRopeAllowed),
      "flyAllowed" to Json.JBool(h.flyAllowed),
      "outgoingCalls" to Json.JBool(h.outgoingCalls),
      "incomingCalls" to Json.JBool(h.incomingCalls),
      "radioSignal" to Json.JBool(h.radioSignal),
  ))

  private fun renderGrid(map: NdsMap): String {
    val grid = map.grid
    val root = Json.JObj(linkedMapOf())
    for (layer in 0 until de.lananahwp.openmmo.mapeditor.model.NdsGrid.LAYERS) {
      val cells =
          (0 until grid.cols * grid.rows).map { i ->
            val x = i % grid.cols
            val y = i / grid.cols
            Json.JObj(
                linkedMapOf(
                    "tile" to Json.JNum(grid.tileAt(layer, x, y).toDouble()),
                    "height" to Json.JNum(grid.heightAt(layer, x, y).toDouble()),
                ))
          }
      root.entries["layer_$layer"] = Json.JArr(cells)
    }
    root.entries["collisions"] =
        Json.JArr(
            (0 until grid.cols * grid.rows).map { i ->
              val x = i % grid.cols
              val y = i / grid.cols
              Json.JNum(grid.collisionAt(x, y).toDouble())
            })
    root.entries["permissions"] =
        Json.JArr(
            (0 until grid.cols * grid.rows).map { i ->
              val x = i % grid.cols
              val y = i / grid.cols
              Json.JNum(grid.permissionAt(x, y).toDouble())
            })
    return JsonWriter.writePretty(root)
  }

  private fun renderProps(map: NdsMap): String = JsonWriter.writePretty(
      Json.JObj(linkedMapOf(
          "version" to Json.JNum(3.0),
          "props" to Json.JArr(map.props.map(::propToJson)),
          "terrainRemovals" to Json.JArr(map.terrainRemovals.map { removal ->
            Json.JObj(linkedMapOf<String, Json>(
                "id" to Json.JStr(removal.id),
                "groupId" to Json.JStr(removal.groupId),
                "clearedCollision" to Json.JArr(removal.clearedCollision.map { cell ->
                  Json.JObj(linkedMapOf(
                      "x" to Json.JNum(cell.x.toDouble()),
                      "z" to Json.JNum(cell.z.toDouble()),
                      "collision" to Json.JNum(cell.collision.toDouble()),
                  ))
                }),
            ).also { entries ->
              removal.removedProp?.let { entries["removedProp"] = propToJson(it) }
            })
          }),
          "terrainTransforms" to Json.JArr(map.terrainTransforms.map { transform ->
            Json.JObj(linkedMapOf(
                "groupId" to Json.JStr(transform.groupId),
                "offsetX" to Json.JNum(transform.offsetX.toDouble()),
                "offsetY" to Json.JNum(transform.offsetY.toDouble()),
                "offsetZ" to Json.JNum(transform.offsetZ.toDouble()),
            ))
          }),
      )))

  private fun renderEvents(map: NdsMap): String {
    val e = map.events
    val root =
        Json.JObj(
            linkedMapOf(
                "header" to Json.JStr(e.header),
                "objects" to
                    Json.JArr(
                        e.objects.map {
                          Json.JObj(
                              linkedMapOf(
                                  "id" to Json.JStr(it.id),
                                  "spriteId" to Json.JStr(it.spriteId),
                                  "movement" to Json.JNum(it.movement.toDouble()),
                                  "type" to Json.JNum(it.type.toDouble()),
                                  "eventFlag" to Json.JStr(it.eventFlag),
                                  "scriptId" to Json.JStr(it.scriptId),
                                  "facingDirection" to Json.JNum(it.facingDirection.toDouble()),
                                  "param0" to Json.JNum(it.param0.toDouble()),
                                  "param1" to Json.JNum(it.param1.toDouble()),
                                  "param2" to Json.JNum(it.param2.toDouble()),
                                  "xRange" to Json.JNum(it.xRange.toDouble()),
                                  "yRange" to Json.JNum(it.yRange.toDouble()),
                                  "x" to Json.JNum(it.x.toDouble()),
                                  "z" to Json.JNum(it.z.toDouble()),
                                  "y" to Json.JNum(it.y.toDouble()),
                              ))
                        }),
                "warps" to
                    Json.JArr(
                        e.warps.map {
                          Json.JObj(
                              linkedMapOf(
                                  "x" to Json.JNum(it.x.toDouble()),
                                  "z" to Json.JNum(it.z.toDouble()),
                                  "header" to Json.JStr(it.header),
                                  "anchor" to Json.JNum(it.anchor.toDouble()),
                                  "y" to Json.JNum(it.y.toDouble()),
                              ))
                        }),
                "coords" to
                    Json.JArr(
                        e.triggers.map {
                          Json.JObj(
                              linkedMapOf(
                                  "scriptId" to Json.JStr(it.scriptId),
                                  "x" to Json.JNum(it.x.toDouble()),
                                  "z" to Json.JNum(it.z.toDouble()),
                                  "w" to Json.JNum(it.w.toDouble()),
                                  "h" to Json.JNum(it.h.toDouble()),
                                  "y" to Json.JNum(it.y.toDouble()),
                                  "var" to Json.JStr(it.variable),
                                  "val" to Json.JNum(it.value.toDouble()),
                              ))
                        }),
                "bgs" to
                    Json.JArr(
                        e.bgEvents.map {
                          Json.JObj(
                              linkedMapOf(
                                  "scriptId" to Json.JStr(it.scriptId),
                                  "type" to Json.JNum(it.type.toDouble()),
                                  "x" to Json.JNum(it.x.toDouble()),
                                  "z" to Json.JNum(it.z.toDouble()),
                                  "y" to Json.JNum(it.y.toDouble()),
                                  "dir" to Json.JNum(it.dir.toDouble()),
                              ))
                        }),
            ))
    return JsonWriter.writePretty(root)
  }

  private fun renderHeader(h: NdsMapHeader): List<String> {
    fun b(v: Boolean) = if (v) "TRUE" else "FALSE"
    return listOf(
        "    [${h.name}] = {",
        "                        .wildEncounterBank = ${h.wildEncounterBank},",
        "                        .areaDataBank = ${h.areaDataBank},",
        "                        .moveModelBank = ${h.moveModelBank},",
        "                        .worldMapX = ${h.worldMapX},",
        "                        .worldMapY = ${h.worldMapY},",
        "                        .matrixId = ${h.matrixId},",
        "                        .scriptsBank = ${h.scriptsBank},",
        "                        .scriptHeaderBank = ${h.scriptHeaderBank},",
        "                        .msgBank = ${h.msgBank},",
        "                        .dayMusicId = ${h.dayMusicId},",
        "                        .nightMusicId = ${h.nightMusicId},",
        "                        .eventsBank = ${h.eventsFile},",
        "                        .mapsec = ${h.mapsec},",
        "                        .areaIcon = ${h.areaIcon},",
        "                        .momCallIntroParam = ${h.momCallIntroParam},",
        "                        .regionNo = ${h.regionNo},",
        "                        .weather = ${h.weather},",
        "                        .mapType = ${h.mapType},",
        "                        .cameraType = ${h.cameraType},",
        "                        .followMode = ${h.followMode},",
        "                        .battleBg = ${h.battleBg},",
        "                        .bikeAllowed = ${b(h.bikeAllowed)},",
        "                        .runningAllowed_Unused = ${b(h.runningAllowed)},",
        "                        .escapeRopeAllowed = ${b(h.escapeRopeAllowed)},",
        "                        .flyAllowed = ${b(h.flyAllowed)},",
        "                        .outgoingCalls = ${b(h.outgoingCalls)},",
        "                        .incomingCalls = ${b(h.incomingCalls)},",
        "                        .radioSignal = ${b(h.radioSignal)},",
        "                        },",
    )
  }
}
