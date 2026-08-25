package de.lananahwp.openmmo.mapeditor

import de.lananahwp.openmmo.mapeditor.project.NdsProject
import java.io.File

/**
 * Reports what surface selection actually returns over a cliff on a real map.
 *
 * Written because a cliff kept coming out of the picker in pieces and the synthetic fixtures in
 * surfaceExtractionTest said the machinery was fine. They test one wall standing on one square;
 * real cliff geometry is a run of faces of varying size, welded to the ground at both ends, and
 * the question is how much of it a square-keyed selection can actually reach.
 *
 * Usage: gradlew cliffDiagnostic [-Pmap=MAP_NAME]
 */
fun main(args: Array<String>) {
  val root = args.firstOrNull() ?: "."
  val decomp = File(root, "decomp/pokeheartgold")
  if (!decomp.isDirectory) {
    println("cliff diagnostic: no pokeheartgold decomp under $root")
    return
  }
  val project = NdsProject(decomp)
  if (!project.hasRom) {
    println("cliff diagnostic: no ROM alongside the decomp")
    return
  }

  val wanted = args.getOrNull(1)?.takeIf { it.isNotEmpty() }
  val map = (if (wanted != null) sequenceOf(wanted) else project.mapNames.asSequence())
      .mapNotNull(project::loadMap)
      .firstOrNull { candidate ->
        val tris = project.trianglesFor(candidate)
        tris.isNotEmpty() && tris.any { NdsProject.isVerticalFace(it) }
      }
  if (map == null) {
    println("cliff diagnostic: no map with vertical faces found")
    return
  }
  val terrain = project.trianglesFor(map)
  val walls = terrain.filter { NdsProject.isVerticalFace(it) }
  println("${map.name}: ${terrain.size} triangles, ${walls.size} of them vertical faces")

  // Where do those faces sit relative to the square grid? A face lying exactly on a square edge
  // is the case that used to fall between two squares and be reachable from neither.
  var onEdge = 0
  var inside = 0
  for (wall in walls) {
    val minX = minOf(wall.ax, wall.bx, wall.cx)
    val maxX = maxOf(wall.ax, wall.bx, wall.cx)
    val minZ = minOf(wall.az, wall.bz, wall.cz)
    val maxZ = maxOf(wall.az, wall.bz, wall.cz)
    val flatX = kotlin.math.abs(maxX - minX) < 1e-3f
    val flatZ = kotlin.math.abs(maxZ - minZ) < 1e-3f
    val edgeX = flatX && kotlin.math.abs(minX - kotlin.math.round(minX)) < 1e-3f
    val edgeZ = flatZ && kotlin.math.abs(minZ - kotlin.math.round(minZ)) < 1e-3f
    if (edgeX || edgeZ) onEdge++ else inside++
  }
  println("  faces on a square edge: $onEdge, faces inside a square: $inside")

  // How steep is each triangle, independent of how big it is? footprintArea is the triangle's
  // area projected onto the ground, so the current wall test is an ABSOLUTE measure: a large
  // face that leans slightly still projects a big shadow and reads as a surface, while a small
  // flat triangle projects a tiny one. Normalising by the triangle's own area gives the angle
  // instead, which is what "is this a wall" actually asks.
  var deadZone = 0
  var steep = 0
  var steepMissed = 0
  for (t in terrain) {
    val ux = t.bx - t.ax; val uy = t.by - t.ay; val uz = t.bz - t.az
    val vx = t.cx - t.ax; val vy = t.cy - t.ay; val vz = t.cz - t.az
    val nx = uy * vz - uz * vy
    val ny = uz * vx - ux * vz
    val nz = ux * vy - uy * vx
    val len = kotlin.math.sqrt((nx * nx + ny * ny + nz * nz).toDouble()).toFloat()
    if (len < 1e-9f) continue
    val steepness = kotlin.math.abs(ny) / len
    val area = kotlin.math.abs(ny) / 2f
    if (steepness < 0.35f) {
      steep++
      if (!NdsProject.isVerticalFace(t)) steepMissed++
    }
    // Orphaned: not collected as a wall, and dropped as a sliver by squareForCell, which
    // ignores anything covering less than 0.05 of a square. Nothing should land here.
    if (!NdsProject.isVerticalFace(t) && area < 0.05f) deadZone++
  }
  println("  steep faces (>70 deg): $steep, of which the area test misses $steepMissed")
  println("  orphaned triangles (no wall, no surface): $deadZone")

  // Take the squares around the tallest face and see what selection hands back.
  val tallest = walls.maxByOrNull { t ->
    maxOf(t.ay, t.by, t.cy) - minOf(t.ay, t.by, t.cy)
  } ?: return
  val cx = kotlin.math.floor((((tallest.ax + tallest.bx + tallest.cx) / 3f).toDouble())).toInt()
  val cz = kotlin.math.floor((((tallest.az + tallest.bz + tallest.cz) / 3f).toDouble())).toInt()
  val height = maxOf(tallest.ay, tallest.by, tallest.cy) - minOf(tallest.ay, tallest.by, tallest.cy)
  println("  tallest face is %.2f tiles, around square ($cx, $cz)".format(height))

  val patch = buildSet {
    for (dz in -1..1) for (dx in -1..1) add(NdsProject.surfaceCellKey(cx + dx, cz + dz))
  }
  for (cut in listOf(NdsProject.SurfaceCut.SQUARES, NdsProject.SurfaceCut.FREEFORM)) {
    for (includeWalls in listOf(false, true)) {
      val picked = NdsProject.filterSurfaceTriangles(
          terrain, patch, null, cut, null, includeWalls)
      val verticals = picked.count { NdsProject.isVerticalFace(it) }
      println(
          "  cut=%-9s walls=%-5s -> %3d triangles, %2d vertical".format(
              cut.name.lowercase(), includeWalls.toString(), picked.size, verticals))
    }
  }

  // And how much of the cliff a whole run of squares along it can reach.
  val wallCells = walls.flatMap { wall ->
    val minX = kotlin.math.floor(minOf(wall.ax, wall.bx, wall.cx).toDouble()).toInt()
    val maxX = kotlin.math.floor(maxOf(wall.ax, wall.bx, wall.cx).toDouble()).toInt()
    val minZ = kotlin.math.floor(minOf(wall.az, wall.bz, wall.cz).toDouble()).toInt()
    val maxZ = kotlin.math.floor(maxOf(wall.az, wall.bz, wall.cz).toDouble()).toInt()
    buildList {
      for (z in minZ..maxZ) for (x in minX..maxX) add(NdsProject.surfaceCellKey(x, z))
    }
  }.toSet()
  val all = NdsProject.filterSurfaceTriangles(
      terrain, wallCells, null, NdsProject.SurfaceCut.FREEFORM, null, true)
  val reached = all.count { NdsProject.isVerticalFace(it) }
  println(
      "  selecting every square any face touches (${wallCells.size} squares) reaches " +
          "$reached vertical pieces of ${walls.size}")
}
