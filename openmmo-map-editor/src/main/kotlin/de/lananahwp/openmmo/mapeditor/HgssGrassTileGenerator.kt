package de.lananahwp.openmmo.mapeditor

import de.lananahwp.openmmo.mapeditor.core.NdsMeshSnapshot
import de.lananahwp.openmmo.mapeditor.project.NdsProject
import java.io.File

/** Builds the compact, material-based HGSS grass palette from authentic route geometry. */
fun main(args: Array<String>) {
  val parent = File(args.getOrElse(0) { "." })
  val root = File(parent, "decomp/pokeheartgold")
  val write = args.any { it == "--write" }
  val project = NdsProject(root)

  val interior = findGrassCell(project, "egrass")
  val edge = findGrassCell(project, "egrass_u")
  val outerCorner = findGrassCell(project, "egrass_ro")
  val innerCorner = findGrassCell(project, "egrass_ri")
  val edge90 = rotateSnapshot(edge, 1)
  val edge180 = rotateSnapshot(edge, 2)
  val edge270 = rotateSnapshot(edge, 3)
  val outer90 = rotateSnapshot(outerCorner, 1)
  val circle = combineSnapshots(listOf(
      translated(interior, 1, 1),
      translated(edge, 1, 0), translated(edge90, 2, 1),
      translated(edge180, 1, 2), translated(edge270, 0, 1),
      translated(outerCorner, 0, 0), translated(outer90, 2, 0),
      translated(rotateSnapshot(outerCorner, 2), 2, 2),
      translated(rotateSnapshot(outerCorner, 3), 0, 2),
  ))
  val forms = listOf(
      GrassForm(1005, "Grass Interior (egrass)", interior, overlay = true, hidden = true),
      GrassForm(1009, "Grass Edge 0deg (egrass_u)", edge, overlay = true, hidden = true),
      GrassForm(1011, "Grass Edge 90deg (egrass_u)", edge90, overlay = true, hidden = true),
      GrassForm(1015, "Grass Edge 180deg (egrass_u)", edge180, overlay = true, hidden = true),
      GrassForm(1020, "Grass Edge 270deg (egrass_u)", edge270, overlay = true, hidden = true),
      GrassForm(1031, "Grass Outer Corner 0deg (egrass_ro)", outerCorner, overlay = true, hidden = true),
      GrassForm(1039, "Grass Outer Corner 90deg (egrass_ro)", outer90, overlay = true, hidden = true),
      GrassForm(1051, "Grass Inner Corner (egrass_ri)", innerCorner, overlay = true, hidden = true),
      GrassForm(1052, "Grass Patch", circle, overlay = true, width = 3, height = 3),
  )
  check(forms.all { it.snapshot.triangles.isNotEmpty() }) { "A compact grass form is empty" }
  println("Built ${forms.size} authentic, single-material HGSS grass forms:")
  forms.forEach { form ->
    println("  ${form.preferredIndex} ${form.name}: " +
        "${form.snapshot.triangles.size} tris, ${form.snapshot.triangles.map { it.texture }.distinct()}")
  }
  if (!write) {
    println("Dry run only; pass --write to update ${project.customTileStore.rootDir}")
    return
  }

  var existing = project.customTileStore.tiles()
  val keptIndices = forms.map { it.preferredIndex }.toSet()
  val obsolete = existing.filter { tile ->
    isGeneratedGrassName(tile.name) && tile.index !in keptIndices
  }.map { it.index }.toSet()
  project.customTileStore.remove(obsolete)
  existing = project.customTileStore.tiles()

  var added = 0
  var replaced = 0
  for (form in forms) {
    val prior = existing.firstOrNull { it.index == form.preferredIndex }
        ?.takeIf { isGeneratedGrassName(it.name) }
        ?: existing.firstOrNull { it.name == form.name }
    if (prior == null) {
      project.customTileStore.add(
          form.name, form.snapshot, width = form.width, height = form.height,
          overlay = form.overlay, hidden = form.hidden)
      existing = project.customTileStore.tiles()
      added++
    } else {
      project.customTileStore.replace(
          prior.index, form.name, form.snapshot, overlay = form.overlay,
          width = form.width, height = form.height, hidden = form.hidden)
      replaced++
    }
  }
  println("Removed ${obsolete.size}, added $added, and restored $replaced compact grass forms")
}

private data class GrassForm(
    val preferredIndex: Int,
    val name: String,
    val snapshot: NdsMeshSnapshot,
    val overlay: Boolean,
    val width: Int = 1,
    val height: Int = 1,
    val hidden: Boolean = false,
)

private fun isGeneratedGrassName(name: String): Boolean =
    name.startsWith("Grass ") && (
        Regex("^Grass [0-9A-F]{2} - ").containsMatchIn(name) ||
            name.startsWith("Grass Interior") ||
            name.startsWith("Grass Edge") ||
            name.startsWith("Grass Outer Corner") ||
            name.startsWith("Grass Inner Corner") ||
            name.startsWith("Grass Patch"))

private fun translated(snapshot: NdsMeshSnapshot, x: Int, z: Int): NdsMeshSnapshot =
    NdsMeshSnapshot(snapshot.triangles.map { tri ->
      tri.copy(
          ax = tri.ax + x, az = tri.az + z,
          bx = tri.bx + x, bz = tri.bz + z,
          cx = tri.cx + x, cz = tri.cz + z)
    }, snapshot.textures, snapshot.palettes)

private fun combineSnapshots(parts: List<NdsMeshSnapshot>): NdsMeshSnapshot =
    NdsMeshSnapshot(
        parts.flatMap { it.triangles },
        parts.flatMap { it.textures.entries }.associate { it.toPair() },
        parts.flatMap { it.palettes.entries }.associate { it.toPair() },
    )

private fun findGrassCell(project: NdsProject, material: String): NdsMeshSnapshot {
  for (route in 1..48) {
    if (route == 23) continue
    val map = project.loadMap("MAP_ROUTE_$route") ?: continue
    val terrain = project.trianglesFor(map).filter { it.texture == material }
    if (terrain.isEmpty()) continue
    val textures = project.texturesFor(map)
    val palettes = project.palettesFor(map)
    for (z in 0 until map.grid.rows) for (x in 0 until map.grid.cols) {
      val cell = setOf(NdsProject.surfaceCellKey(x, z))
      val local = NdsProject.cellRelativeSurfaceTriangles(
          NdsProject.filterSurfaceTriangles(
              terrain, cell, cut = NdsProject.SurfaceCut.FREEFORM),
          cell)
      if (local.isEmpty() || local.any { it.texture != material }) continue
      val ys = local.flatMap { listOf(it.ay, it.by, it.cy) }
      if (ys.max() - ys.min() > 1e-4f) continue
      val textureNames = local.map { it.texture }.filter { it.isNotEmpty() }.toSet()
      val paletteNames = local.map { it.palette }.filter { it.isNotEmpty() }.toSet()
      return NdsMeshSnapshot(
          local,
          textureNames.mapNotNull { name -> textures[name]?.let { name to it } }.toMap(),
          paletteNames.mapNotNull { name -> palettes[name]?.let { name to it } }.toMap(),
      )
    }
  }
  error("Could not find authentic $material geometry on HGSS routes")
}

private fun rotateSnapshot(snapshot: NdsMeshSnapshot, turns: Int): NdsMeshSnapshot {
  fun point(x: Float, z: Float): Pair<Float, Float> {
    var px = x; var pz = z
    repeat(turns and 3) { val nx = 1f - pz; pz = px; px = nx }
    return px to pz
  }
  return NdsMeshSnapshot(snapshot.triangles.map { tri ->
    val a = point(tri.ax, tri.az); val b = point(tri.bx, tri.bz); val c = point(tri.cx, tri.cz)
    tri.copy(
        ax = a.first, az = a.second,
        bx = b.first, bz = b.second,
        cx = c.first, cz = c.second,
    )
  }, snapshot.textures, snapshot.palettes)
}
