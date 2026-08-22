package de.lananahwp.openmmo.mapeditor

import de.lananahwp.openmmo.mapeditor.core.NdsMeshSnapshot
import de.lananahwp.openmmo.mapeditor.core.NdsTexture
import de.lananahwp.openmmo.mapeditor.core.NdsTri
import de.lananahwp.openmmo.mapeditor.project.NdsProject
import java.io.File
import java.nio.file.Files

/**
 * Checks the surface-picking maths and the extracted-mesh file format.
 *
 * Deliberately free of ROM/decomp assets so it runs anywhere: every helper under test is pure, and
 * the snapshot round-trip only needs a temp directory.
 */
fun main(args: Array<String>) {
  testBrushCells()
  testRectCells()
  testTriangleFiltering()
  testOversizedTriangleIsClipped()
  testSquareCutProducesWholeSquares()
  testSquareCutPicksTheClickedLayer()
  testRecentring()
  testSnapshotRoundTrip()
  testCustomTiles()

  // The end-to-end pass needs a real map to lift geometry off, so it only runs when the decomp
  // (and its ROM) are present locally, matching how the other ROM-dependent checks are gated.
  val decomp = args.firstOrNull()?.let { File(it, "decomp/pokeheartgold") }
  if (decomp?.isDirectory == true) testEndToEndExtraction(decomp) else {
    println("surface extraction: skipping end-to-end pass (no pokeheartgold decomp)")
  }

  println("surface extraction: all checks passed")
}

/**
 * Project-defined tiles. The property that matters most here is index stability: grids persist the
 * tile *index*, so a number that shifts or gets reused silently repaints saved maps.
 */
private fun testCustomTiles() {
  val base = de.lananahwp.openmmo.mapeditor.core.NdsTileset.CUSTOM_TILE_BASE
  check(!de.lananahwp.openmmo.mapeditor.core.NdsTileset.isCustom(base - 1))
  check(de.lananahwp.openmmo.mapeditor.core.NdsTileset.isCustom(base))
  check(base > de.lananahwp.openmmo.mapeditor.core.NdsTileset.tiles.size) {
    "custom tiles must start past every built-in, or the two collide"
  }

  // A square cut somewhere out on the map, at some arbitrary height and size.
  val snapshot = NdsMeshSnapshot(
      listOf(
          NdsTri(
              ax = 20f, ay = 7f, az = 30f, bx = 22f, by = 7f, bz = 30f, cx = 22f, cy = 7f, cz = 32f,
              color = -1, u0 = 0f, v0 = 0f, u1 = 16f, v1 = 0f, u2 = 16f, v2 = 16f,
              texture = "grass", palette = "grass_pl"),
          NdsTri(
              ax = 20f, ay = 7f, az = 30f, bx = 22f, by = 7f, bz = 32f, cx = 20f, cy = 7f, cz = 32f,
              color = -1, u0 = 0f, v0 = 0f, u1 = 16f, v1 = 16f, u2 = 0f, v2 = 16f,
              texture = "grass", palette = "grass_pl"),
      ),
      linkedMapOf("grass" to NdsTexture("grass", 3, 4, 4, ByteArray(8), null, intArrayOf(1, 2), false)),
      linkedMapOf("grass_pl" to intArrayOf(1, 2)),
  )

  val store = de.lananahwp.openmmo.mapeditor.project.NdsCustomTileStore
  val previousRoot = store.rootDir
  val root = Files.createTempDirectory("openmmo-custom-tiles-").toFile()
  try {
    // Never touch the real user directory from a test.
    store.rootDir = root
    check(store.tiles().isEmpty()) { "a fresh store should have no tiles" }

    val first = store.add("Olivine path", snapshot)
    check(first.index == base) { "first tile should take the base index, got ${first.index}" }
    val second = store.add("Park grass", snapshot)
    check(second.index == base + 1) { "indices must advance, got ${second.index}" }

    // Reloading from disk must preserve both the order and the numbers.
    store.invalidate()
    val listed = store.tiles()
    check(listed.map { it.index } == listOf(base, base + 1)) { "tile indices changed on reload" }
    check(listed.map { it.name } == listOf("Olivine path", "Park grass")) { "tile order changed" }

    // Geometry must arrive in tile space: spanning 0..1 in X and Z, resting on y=0, or a painted
    // tile would land offset from its square and at the source map's scale.
    val mesh = store.mesh(base) ?: error("tile mesh did not reload")
    val xs = mesh.triangles.flatMap { listOf(it.ax, it.bx, it.cx) }
    val ys = mesh.triangles.flatMap { listOf(it.ay, it.by, it.cy) }
    val zs = mesh.triangles.flatMap { listOf(it.az, it.bz, it.cz) }
    check(kotlin.math.abs(xs.min()) < 1e-4f && kotlin.math.abs(xs.max() - 1f) < 1e-4f) {
      "tile X should span 0..1, got ${xs.min()}..${xs.max()}"
    }
    check(kotlin.math.abs(zs.min()) < 1e-4f && kotlin.math.abs(zs.max() - 1f) < 1e-4f) {
      "tile Z should span 0..1, got ${zs.min()}..${zs.max()}"
    }
    check(kotlin.math.abs(ys.min()) < 1e-4f) { "tile should rest on y=0, got ${ys.min()}" }
    check(mesh.textures.containsKey("grass")) { "tile lost its texture" }

    // Adding a third after a reload must not reuse a number already handed out.
    val third = store.add("Third", snapshot)
    check(third.index == base + 2) { "index was reused after reload: ${third.index}" }

    // Textures are namespaced per tile so two tiles cut from different maps cannot collide on a
    // shared texture name -- the shared store makes that reachable in a way per-project never was.
    val geometry = store.viewGeometry()
    check(geometry.keys.containsAll(setOf(base, base + 1, base + 2)))
    check(geometry.getValue(base).all { it.texture.startsWith(store.texturePrefix(base)) }) {
      "tile textures were not namespaced"
    }
    check(geometry.getValue(base + 1).none { it.texture.startsWith(store.texturePrefix(base)) }) {
      "two tiles shared a texture namespace"
    }
  } finally {
    store.rootDir = previousRoot
    root.deleteRecursively()
  }
}

/**
 * Lifts real geometry off a real map, saves it to a catalog, and reopens it as a placeable prop.
 *
 * The catalog is a throwaway temp project so the source decomp is only ever read from.
 */
private fun testEndToEndExtraction(decompRoot: File) {
  val source = NdsProject(decompRoot)
  if (!source.hasRom) {
    println("surface extraction: skipping end-to-end pass (no ROM alongside the decomp)")
    return
  }

  val map = sequenceOf("MAP_OLIVINE", "MAP_NEW_BARK", "MAP_CHERRYGROVE_CITY")
      .plus(source.mapNames.asSequence())
      .mapNotNull(source::loadMap)
      .firstOrNull { source.trianglesFor(it).isNotEmpty() }
  if (map == null) {
    println("surface extraction: skipping end-to-end pass (no map with terrain geometry)")
    return
  }
  val terrain = source.trianglesFor(map)

  // Pick the single busiest square, the way a user clicking a path would land on one.
  val byCell = terrain.groupBy {
    NdsProject.surfaceCellKey(
        kotlin.math.floor((((it.ax + it.bx + it.cx) / 3f).toDouble())).toInt(),
        kotlin.math.floor((((it.az + it.bz + it.cz) / 3f).toDouble())).toInt(),
    )
  }
  val (cell, cellTriangles) = byCell.maxByOrNull { it.value.size }!!
  val cells = setOf(cell)

  val picked = source.surfaceTriangles(map, cells)
  check(picked.isNotEmpty()) { "one square yielded no geometry" }
  check(picked.size < terrain.size) {
    "a one-square pick must not take the whole map (${picked.size} of ${terrain.size})"
  }

  // The property that matters, and the one the centroid rule used to break: whatever comes back
  // must physically fit inside the square that was picked. Real terrain uses quads far larger
  // than one tile, so this only holds because the selection clips them.
  val cellX = NdsProject.surfaceCellX(cell)
  val cellZ = NdsProject.surfaceCellZ(cell)
  for (t in picked) {
    val xs = listOf(t.ax, t.bx, t.cx)
    val zs = listOf(t.az, t.bz, t.cz)
    check(xs.min() >= cellX - 1e-3f && xs.max() <= cellX + 1f + 1e-3f) {
      "clipped geometry escaped its square in X: ${xs.min()}..${xs.max()} for cell $cellX"
    }
    check(zs.min() >= cellZ - 1e-3f && zs.max() <= cellZ + 1f + 1e-3f) {
      "clipped geometry escaped its square in Z: ${zs.min()}..${zs.max()} for cell $cellZ"
    }
  }
  println(
      "  one-square pick on ${map.name}: ${picked.size} clipped triangles " +
          "from ${cellTriangles.size} overlapping source triangles")

  // The texture filter must actually narrow things when a square carries more than one surface.
  val texture = picked.map { it.texture }.firstOrNull { it.isNotEmpty() }
  if (texture != null) {
    val filtered = source.surfaceTriangles(map, cells, texture)
    check(filtered.isNotEmpty()) { "texture filter dropped everything for '$texture'" }
    check(filtered.all { it.texture == texture })
    check(filtered.size <= picked.size)
  }

  val snapshot = source.buildSurfaceExtraction(map, cells)
      ?: error("extraction produced no mesh for ${map.name} at cell $cell")
  check(snapshot.triangles.size == picked.size)
  check(snapshot.triangles.all { it.editGroup.isEmpty() })
  val referenced = snapshot.triangles.map { it.texture }.filter { it.isNotEmpty() }.toSet()
  check(snapshot.textures.keys.containsAll(referenced)) {
    "extraction is missing textures ${referenced - snapshot.textures.keys}"
  }

  // Save into a throwaway catalog, then reopen it the way the editor would on next launch.
  val catalogRoot = Files.createTempDirectory("openmmo-surface-catalog-").toFile()
  try {
    val saved = NdsProject(catalogRoot).saveExtractedProp("Test Path Chunk", snapshot, map.name)
    check(saved.category == NdsProject.EXTRACTED_CATEGORY)

    val reopened = NdsProject(catalogRoot)
    val listed = reopened.propModels().singleOrNull { it.key == saved.key }
        ?: error("the extracted prop is missing from the reopened catalog")
    check(listed.label == "Test Path Chunk")
    check(listed.imported)
    check(listed.category == NdsProject.EXTRACTED_CATEGORY)

    val preview = reopened.propModelPreview(saved.key, null)
    check(preview.triangles.size == snapshot.triangles.size) {
      "preview lost geometry: ${preview.triangles.size} vs ${snapshot.triangles.size}"
    }
    check(preview.textures.keys.containsAll(referenced)) {
      "preview cannot resolve the extracted textures"
    }

    // Extracted geometry is already sized in map tiles, so placement must not rescale it.
    val placed = reopened.createProp(saved.key, 8f, 9f)
    check(placed.scaleX == 1f && placed.scaleY == 1f && placed.scaleZ == 1f) {
      "extracted props must place at 1:1 scale, got ${placed.scaleX}"
    }
    check(placed.x == 8f && placed.z == 9f)
    check(kotlin.math.abs(placed.y) < 1e-4f) { "extracted props should rest on the ground" }

    println(
        "surface extraction end-to-end: ${map.name} cell " +
            "(${NdsProject.surfaceCellX(cell)}, ${NdsProject.surfaceCellZ(cell)}) -> " +
            "${snapshot.triangles.size} triangles, ${snapshot.textures.size} textures, " +
            "${snapshot.palettes.size} palettes: OK")
  } finally {
    catalogRoot.deleteRecursively()
  }
}

/** A flat unit-square-ish triangle sitting on tile (x, z) at height y. */
private fun tileTri(x: Int, z: Int, y: Float = 0f, texture: String = "grass"): NdsTri = NdsTri(
    ax = x + 0.1f, ay = y, az = z + 0.1f,
    bx = x + 0.9f, by = y, bz = z + 0.1f,
    cx = x + 0.5f, cy = y, cz = z + 0.9f,
    color = -1,
    u0 = 0f, v0 = 0f, u1 = 1f, v1 = 0f, u2 = 0.5f, v2 = 1f,
    texture = texture,
    palette = "$texture-pal",
)

private fun testBrushCells() {
  // Size 1 is the headline case: one click takes exactly the square under the pointer.
  val one = NdsProject.surfaceBrushCells(4.7f, 9.2f, 1)
  check(one == setOf(NdsProject.surfaceCellKey(4, 9))) { "brush size 1 selected $one" }

  val three = NdsProject.surfaceBrushCells(4.5f, 9.5f, 3)
  check(three.size == 9) { "brush size 3 should cover 9 squares, got ${three.size}" }
  check(NdsProject.surfaceCellKey(4, 9) in three) { "brush must include the clicked square" }
  check(NdsProject.surfaceCellKey(3, 8) in three && NdsProject.surfaceCellKey(5, 10) in three) {
    "brush size 3 should be centred on the clicked square"
  }

  val two = NdsProject.surfaceBrushCells(4.5f, 9.5f, 2)
  check(two.size == 4) { "brush size 2 should cover 4 squares, got ${two.size}" }
  check(NdsProject.surfaceCellKey(4, 9) in two) { "even brushes must still include the click" }

  // Negative coordinates must floor, not truncate toward zero.
  check(NdsProject.surfaceBrushCells(-0.5f, -0.5f, 1) == setOf(NdsProject.surfaceCellKey(-1, -1)))

  val key = NdsProject.surfaceCellKey(-3, 7)
  check(NdsProject.surfaceCellX(key) == -3 && NdsProject.surfaceCellZ(key) == 7) {
    "cell key round-trip failed for negative coordinates"
  }
}

private fun testRectCells() {
  // Corners given in either order must describe the same box.
  val forward = NdsProject.surfaceRectCells(2.2f, 3.9f, 4.1f, 5.5f)
  val backward = NdsProject.surfaceRectCells(4.1f, 5.5f, 2.2f, 3.9f)
  check(forward == backward) { "rectangle must not depend on drag direction" }
  check(forward.size == 3 * 3) { "expected a 3x3 box, got ${forward.size}" }
  check(NdsProject.surfaceCellKey(2, 3) in forward && NdsProject.surfaceCellKey(4, 5) in forward)
}

private fun testTriangleFiltering() {
  val triangles = listOf(
      tileTri(1, 1, texture = "path"),
      tileTri(1, 1, y = 0.5f, texture = "grass"),
      tileTri(2, 1, texture = "path"),
      tileTri(5, 5, texture = "path"),
  )
  val cells = setOf(NdsProject.surfaceCellKey(1, 1), NdsProject.surfaceCellKey(2, 1))

  // Free-form, so this exercises which triangles are selected without squaring rebuilding them.
  val free = NdsProject.SurfaceCut.FREEFORM
  val all = NdsProject.filterSurfaceTriangles(triangles, cells, cut = free)
  check(all.size == 3) { "expected 3 triangles on the selected squares, got ${all.size}" }
  check(all.none { it.ax > 5f }) { "selection leaked into an unselected square" }

  // The texture filter is what separates a path from the ground it is welded to.
  val pathOnly = NdsProject.filterSurfaceTriangles(triangles, cells, "path", free)
  check(pathOnly.size == 2) { "texture filter should keep 2 path triangles, got ${pathOnly.size}" }
  check(pathOnly.all { it.texture == "path" })

  check(NdsProject.filterSurfaceTriangles(triangles, emptySet(), cut = free).isEmpty())
  check(NdsProject.filterSurfaceTriangles(triangles, cells, "nonexistent", free).isEmpty())

  // The texture filter must work in squares mode too, since that is the default: picking a path
  // square must yield the path surface, not the grass sharing the square.
  val pathSquare = NdsProject.filterSurfaceTriangles(
      triangles, setOf(NdsProject.surfaceCellKey(1, 1)), "path", NdsProject.SurfaceCut.SQUARES)
  check(pathSquare.isNotEmpty() && pathSquare.all { it.texture == "path" }) {
    "squares mode ignored the texture filter: ${pathSquare.map { it.texture }}"
  }
}

/**
 * Regression for the real defect: DS terrain draws a flat stretch of ground as one big quad, so a
 * whole-triangle rule turned a one-square pick into a 5x5-tile slab. Picking must clip.
 */
private fun testOversizedTriangleIsClipped() {
  // One 5x5-tile quad spanning tiles 0..5, as two triangles, exactly like Olivine's ground.
  val quad = listOf(
      NdsTri(
          ax = 0f, ay = 2f, az = 0f, bx = 5f, by = 2f, bz = 0f, cx = 5f, cy = 2f, cz = 5f,
          color = -1, u0 = 0f, v0 = 0f, u1 = 32f, v1 = 0f, u2 = 32f, v2 = 32f,
          texture = "ground", palette = "ground_pl"),
      NdsTri(
          ax = 0f, ay = 2f, az = 0f, bx = 5f, by = 2f, bz = 5f, cx = 0f, cy = 2f, cz = 5f,
          color = -1, u0 = 0f, v0 = 0f, u1 = 32f, v1 = 32f, u2 = 0f, v2 = 32f,
          texture = "ground", palette = "ground_pl"),
  )

  val one = NdsProject.filterSurfaceTriangles(quad, setOf(NdsProject.surfaceCellKey(2, 3)))
  check(one.isNotEmpty()) { "clipping dropped the square entirely" }
  for (t in one) {
    val xs = listOf(t.ax, t.bx, t.cx)
    val zs = listOf(t.az, t.bz, t.cz)
    check(xs.min() >= 2f - 1e-4f && xs.max() <= 3f + 1e-4f) { "X escaped: ${xs.min()}..${xs.max()}" }
    check(zs.min() >= 3f - 1e-4f && zs.max() <= 4f + 1e-4f) { "Z escaped: ${zs.min()}..${zs.max()}" }
    check(kotlin.math.abs(t.ay - 2f) < 1e-4f) { "clipping moved the surface height" }
  }

  // Area must be conserved: one tile out of a 5x5 quad is 1/25th of it.
  fun area(list: List<NdsTri>) = list.sumOf { t ->
    kotlin.math.abs((t.bx - t.ax) * (t.cz - t.az) - (t.cx - t.ax) * (t.bz - t.az)).toDouble() / 2.0
  }
  check(kotlin.math.abs(area(one) - 1.0) < 1e-3) { "expected 1 tile of area, got ${area(one)}" }

  // Texture coordinates must be interpolated, not copied, or the cut piece would show the whole
  // texture squeezed into one tile instead of the part that belongs there.
  val us = one.flatMap { listOf(it.u0, it.u1, it.u2) }
  check(us.max() <= 32f + 1e-3f && us.min() >= 0f - 1e-3f) { "UVs left the source range" }
  check(us.max() - us.min() < 32f) { "UVs were copied wholesale instead of interpolated" }

  // A 2x2 selection over the same quad is four times the area, still confined to those squares.
  val four = NdsProject.filterSurfaceTriangles(quad, NdsProject.surfaceBrushCells(2.5f, 3.5f, 2))
  check(kotlin.math.abs(area(four) - 4.0) < 1e-3) { "expected 4 tiles of area, got ${area(four)}" }

  // Squares with nothing on them contribute nothing.
  check(NdsProject.filterSurfaceTriangles(quad, setOf(NdsProject.surfaceCellKey(40, 40))).isEmpty())
}

/**
 * The guarantee the squares cut is supposed to give: every picked square comes back as exactly two
 * triangles covering that square completely, with corners on the tile boundary.
 */
private fun testSquareCutProducesWholeSquares() {
  // A 5x5 quad of ground, plus a vertical wall standing inside one of its squares.
  val ground = listOf(
      NdsTri(
          ax = 0f, ay = 2f, az = 0f, bx = 5f, by = 2f, bz = 0f, cx = 5f, cy = 2f, cz = 5f,
          color = -1, u0 = 0f, v0 = 0f, u1 = 32f, v1 = 0f, u2 = 32f, v2 = 32f,
          texture = "ground", palette = "ground_pl"),
      NdsTri(
          ax = 0f, ay = 2f, az = 0f, bx = 5f, by = 2f, bz = 5f, cx = 0f, cy = 2f, cz = 5f,
          color = -1, u0 = 0f, v0 = 0f, u1 = 32f, v1 = 32f, u2 = 0f, v2 = 32f,
          texture = "ground", palette = "ground_pl"),
  )
  // Standing inside the square rather than on its boundary, which the bbox test would exclude.
  val wall = NdsTri(
      ax = 2.2f, ay = 2f, az = 3.5f, bx = 2.8f, by = 2f, bz = 3.5f, cx = 2.2f, cy = 6f, cz = 3.5f,
      color = -1, u0 = 0f, v0 = 0f, u1 = 8f, v1 = 0f, u2 = 0f, v2 = 8f,
      texture = "wall", palette = "wall_pl")
  val mesh = ground + wall

  val cell = setOf(NdsProject.surfaceCellKey(2, 3))
  val square = NdsProject.filterSurfaceTriangles(mesh, cell, cut = NdsProject.SurfaceCut.SQUARES)

  check(square.size == 2) { "a square must be exactly two triangles, got ${square.size}" }

  // Corners must land on the tile boundary, not wherever the source mesh happened to be cut.
  val xs = square.flatMap { listOf(it.ax, it.bx, it.cx) }.map { kotlin.math.round(it * 1000f) / 1000f }
  val zs = square.flatMap { listOf(it.az, it.bz, it.cz) }.map { kotlin.math.round(it * 1000f) / 1000f }
  check(xs.toSet() == setOf(2f, 3f)) { "X corners should be exactly 2 and 3, got ${xs.toSet()}" }
  check(zs.toSet() == setOf(3f, 4f)) { "Z corners should be exactly 3 and 4, got ${zs.toSet()}" }

  // Full coverage: two triangles of a unit square total exactly 1 tile of area.
  val area = square.sumOf { t ->
    kotlin.math.abs((t.bx - t.ax) * (t.cz - t.az) - (t.cx - t.ax) * (t.bz - t.az)).toDouble() / 2.0
  }
  check(kotlin.math.abs(area - 1.0) < 1e-3) { "square must cover the whole tile, got area $area" }

  // The wall shares the square but must not be picked: it has no ground-plane area, so it cannot
  // dominate, and squaring keeps only the surface.
  check(square.all { it.texture == "ground" }) {
    "squares must take the floor, not a wall standing on it: ${square.map { it.texture }}"
  }
  check(square.all { kotlin.math.abs(it.ay - 2f) < 1e-4f }) { "square left the ground plane" }

  // Free-form over the same square keeps the wall and the raw fragments.
  val free = NdsProject.filterSurfaceTriangles(mesh, cell, cut = NdsProject.SurfaceCut.FREEFORM)
  check(free.any { it.texture == "wall" }) { "free-form should keep the wall" }

  // A brush square hanging off the edge of the map yields nothing rather than a sliver.
  val outside = setOf(NdsProject.surfaceCellKey(40, 40))
  check(NdsProject.filterSurfaceTriangles(mesh, outside, cut = NdsProject.SurfaceCut.SQUARES).isEmpty())

  // Every square of a multi-square pick is whole, so a path comes out as a tidy strip.
  val strip = NdsProject.filterSurfaceTriangles(
      mesh, NdsProject.surfaceRectCells(1.5f, 1.5f, 3.5f, 1.5f), cut = NdsProject.SurfaceCut.SQUARES)
  check(strip.size == 3 * 2) { "a 3-square strip should be 6 triangles, got ${strip.size}" }
  val stripArea = strip.sumOf { t ->
    kotlin.math.abs((t.bx - t.ax) * (t.cz - t.az) - (t.cx - t.ax) * (t.bz - t.az)).toDouble() / 2.0
  }
  check(kotlin.math.abs(stripArea - 3.0) < 1e-3) { "strip should cover 3 tiles, got $stripArea" }
}

/**
 * Regression for squares coming out of the wrong layer. Maps stack surfaces over one square — a
 * tree canopy above its own ground, tall grass above the floor — and picking by footprint area
 * alone rebuilt roughly a fifth of National Park's squares up in the canopy.
 */
private fun testSquareCutPicksTheClickedLayer() {
  fun quad(y: Float, texture: String) = listOf(
      NdsTri(
          ax = 0f, ay = y, az = 0f, bx = 2f, by = y, bz = 0f, cx = 2f, cy = y, cz = 2f,
          color = -1, u0 = 0f, v0 = 0f, u1 = 16f, v1 = 0f, u2 = 16f, v2 = 16f,
          texture = texture, palette = "${texture}_pl"),
      NdsTri(
          ax = 0f, ay = y, az = 0f, bx = 2f, by = y, bz = 2f, cx = 0f, cy = y, cz = 2f,
          color = -1, u0 = 0f, v0 = 0f, u1 = 16f, v1 = 16f, u2 = 0f, v2 = 16f,
          texture = texture, palette = "${texture}_pl"),
  )
  // Ground at y=0 with a canopy directly above it, exactly as National Park is built.
  val mesh = quad(0f, "grass") + quad(1.29f, "canopy")
  val cell = NdsProject.surfaceCellKey(1, 1)
  val cells = setOf(cell)

  // Clicking the ground must give the ground, even though the canopy covers the square just as
  // fully. This is the case that regressed.
  val fromGround = NdsProject.filterSurfaceTriangles(
      mesh, cells, cut = NdsProject.SurfaceCut.SQUARES, pickedHeights = mapOf(cell to 0f))
  check(fromGround.isNotEmpty() && fromGround.all { it.texture == "grass" }) {
    "clicking the ground selected ${fromGround.map { it.texture }.distinct()}"
  }
  check(fromGround.all { kotlin.math.abs(it.ay) < 1e-3f }) { "ground square built at the wrong height" }

  // Clicking the canopy must still give the canopy — the height is a choice, not a floor clamp.
  val fromCanopy = NdsProject.filterSurfaceTriangles(
      mesh, cells, cut = NdsProject.SurfaceCut.SQUARES, pickedHeights = mapOf(cell to 1.29f))
  check(fromCanopy.isNotEmpty() && fromCanopy.all { it.texture == "canopy" }) {
    "clicking the canopy selected ${fromCanopy.map { it.texture }.distinct()}"
  }

  // With no reference height at all, the floor wins rather than whatever is stacked on top.
  val noReference = NdsProject.filterSurfaceTriangles(mesh, cells, cut = NdsProject.SurfaceCut.SQUARES)
  check(noReference.all { it.texture == "grass" }) {
    "without a picked height the floor should win, got ${noReference.map { it.texture }.distinct()}"
  }

  // A height between the two layers resolves to the nearer one.
  val nearCanopy = NdsProject.filterSurfaceTriangles(
      mesh, cells, cut = NdsProject.SurfaceCut.SQUARES, pickedHeights = mapOf(cell to 1.0f))
  check(nearCanopy.all { it.texture == "canopy" }) { "expected the nearer layer to win" }
}

private fun testRecentring() {
  // Geometry taken from tiles 10..11 must come back centred on its own origin and resting on y=0,
  // otherwise placing it would jump to the source map's coordinates.
  val selected = listOf(tileTri(10, 10, y = 4f), tileTri(11, 10, y = 4f))
  val moved = NdsProject.recentreSurfaceTriangles(selected)
  check(moved.size == 2)

  val xs = moved.flatMap { listOf(it.ax, it.bx, it.cx) }
  val ys = moved.flatMap { listOf(it.ay, it.by, it.cy) }
  val zs = moved.flatMap { listOf(it.az, it.bz, it.cz) }
  val centreX = (xs.min() + xs.max()) / 2f
  val centreZ = (zs.min() + zs.max()) / 2f
  check(kotlin.math.abs(centreX) < 1e-4f) { "expected X centred on 0, got $centreX" }
  check(kotlin.math.abs(centreZ) < 1e-4f) { "expected Z centred on 0, got $centreZ" }
  check(kotlin.math.abs(ys.min()) < 1e-4f) { "expected the mesh to rest on y=0, got ${ys.min()}" }
  check(moved.all { it.editGroup.isEmpty() }) { "extracted geometry must drop its source group id" }

  // Width must survive: two tiles across stays two tiles across, so placement is not shrunk.
  check(kotlin.math.abs((xs.max() - xs.min()) - 1.8f) < 1e-3f) {
    "recentring must not rescale geometry (width ${xs.max() - xs.min()})"
  }

  check(NdsProject.recentreSurfaceTriangles(emptyList()).isEmpty())
}

private fun testSnapshotRoundTrip() {
  val triangles = listOf(
      tileTri(0, 0, texture = "path").copy(repeatS = true, flipT = true, scaleS = 2f),
      tileTri(1, 0, texture = "grass"),
  )
  val textures = linkedMapOf(
      // spdata is non-null only for the 4bpp-compressed format, so cover both shapes.
      "path" to NdsTexture("path", 5, 8, 8, ByteArray(16) { it.toByte() }, ByteArray(8) { 1 },
          intArrayOf(0x1234, 0x5678), true),
      "grass" to NdsTexture("grass", 3, 4, 4, ByteArray(8) { (it * 3).toByte() }, null,
          intArrayOf(0, -1, 0x7FFFFFFF), false),
  )
  val palettes = linkedMapOf(
      "path-pal" to intArrayOf(1, 2, 3),
      "grass-pal" to intArrayOf(9, 8),
  )

  val dir = File(System.getProperty("java.io.tmpdir"), "openmmo-mesh-test-${System.nanoTime()}")
  val file = File(dir, "mesh.bin")
  try {
    NdsMeshSnapshot.write(file, NdsMeshSnapshot(triangles, textures, palettes))
    val read = NdsMeshSnapshot.read(file) ?: error("snapshot failed to read back")

    check(read.triangles == triangles) { "triangles did not survive the round trip" }

    check(read.textures.keys == textures.keys) { "texture names changed: ${read.textures.keys}" }
    for ((name, original) in textures) {
      val copy = read.textures.getValue(name)
      check(copy.name == original.name && copy.format == original.format)
      check(copy.width == original.width && copy.height == original.height)
      check(copy.texdata.contentEquals(original.texdata)) { "$name texdata differs" }
      val originalSp = original.spdata
      check(
          if (originalSp == null) copy.spdata == null
          else copy.spdata?.contentEquals(originalSp) == true) { "$name spdata differs" }
      check(copy.palette.contentEquals(original.palette)) { "$name palette differs" }
      check(copy.color0 == original.color0)
    }

    check(read.palettes.keys == palettes.keys)
    for ((name, colors) in palettes) {
      check(read.palettes.getValue(name).contentEquals(colors)) { "palette $name differs" }
    }

    // Anything that is not one of our snapshots must be rejected rather than misread.
    val junk = File(dir, "junk.bin")
    junk.writeBytes(ByteArray(64) { 0x42 })
    check(NdsMeshSnapshot.read(junk) == null) { "a non-snapshot file was accepted" }
    check(NdsMeshSnapshot.read(File(dir, "missing.bin")) == null) { "a missing file was accepted" }

    // A truncated snapshot must fail cleanly instead of returning half a mesh.
    val truncated = File(dir, "truncated.bin")
    truncated.writeBytes(file.readBytes().copyOf(file.length().toInt() / 2))
    check(NdsMeshSnapshot.read(truncated) == null) { "a truncated snapshot was accepted" }
  } finally {
    dir.deleteRecursively()
  }
}
