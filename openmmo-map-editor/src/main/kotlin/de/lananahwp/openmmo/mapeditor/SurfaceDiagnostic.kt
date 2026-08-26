package de.lananahwp.openmmo.mapeditor

import de.lananahwp.openmmo.mapeditor.project.NdsProject
import java.io.File

/**
 * Reports, square by square, what the surface picker sees on a real map — how many source
 * triangles overlap, how much of the square the best one actually covers, and whether the
 * squares cut produced a tile. Written to explain squares that refuse to select.
 */
fun main(args: Array<String>) {
  val decomp = if (args.getOrElse(2) { "hgss" }.equals("pt", ignoreCase = true))
    "pokeplatinum" else "pokeheartgold"
  val root = File(args.getOrElse(0) { "." }, "decomp/$decomp")
  val mapName = args.getOrElse(1) { "MAP_NATIONAL_PARK" }
  val project = NdsProject(root)
  val map = project.loadMap(mapName) ?: error("cannot load $mapName")
  val terrain = project.trianglesFor(map)
  val textures = project.texturesFor(map)
  val palettes = project.palettesFor(map)
  println("$mapName: ${terrain.size} terrain triangles, grid ${map.grid.cols}x${map.grid.rows}")

  var produced = 0
  var empty = 0
  val emptyExamples = mutableListOf<String>()
  val textureCount = HashMap<String, Int>()

  for (x in 0 until map.grid.cols) {
    for (z in 0 until map.grid.rows) {
      val cell = setOf(NdsProject.surfaceCellKey(x, z))
      val squares = NdsProject.filterSurfaceTriangles(terrain, cell, cut = NdsProject.SurfaceCut.SQUARES)
      // What free-form sees for the same square, as the ground truth for "is there anything here".
      val free = NdsProject.filterSurfaceTriangles(terrain, cell, cut = NdsProject.SurfaceCut.FREEFORM)
      val freeArea = free.sumOf { t ->
        kotlin.math.abs(
            (t.bx - t.ax) * (t.cz - t.az) - (t.cx - t.ax) * (t.bz - t.az)).toDouble() / 2.0
      }
      if (squares.isNotEmpty()) {
        produced++
        squares.firstOrNull()?.let { textureCount[it.texture] = (textureCount[it.texture] ?: 0) + 1 }
      } else if (free.isNotEmpty()) {
        // Geometry is present but no square came out: this is the case that looks like a hole.
        empty++
        if (emptyExamples.size < 12) {
          val biggest = free.maxOf { t ->
            kotlin.math.abs(
                (t.bx - t.ax) * (t.cz - t.az) - (t.cx - t.ax) * (t.bz - t.az)) / 2f
          }
          emptyExamples += "($x,$z) freeTris=${free.size} totalArea=%.3f biggestTri=%.4f tex=%s"
              .format(freeArea, biggest, free.map { it.texture }.distinct())
        }
      }
    }
  }

  println("squares produced: $produced")
  println("squares SKIPPED but free-form found geometry: $empty")
  emptyExamples.forEach { println("   $it") }
  println("top textures chosen: " +
      textureCount.entries.sortedByDescending { it.value }.take(8).joinToString { "${it.key}=${it.value}" })

  println()
  println("--- transparent terrain materials ---")
  val materialTriangles = terrain.filter { it.texture.isNotEmpty() }.groupBy { it.texture }
  for ((name, triangles) in materialTriangles.toSortedMap()) {
    val texture = textures[name] ?: continue
    val paletteName = triangles.firstOrNull()?.palette.orEmpty()
    val pixels = palettes[paletteName]?.let(texture::decodeWith) ?: texture.decode() ?: continue
    val transparent = pixels.count { it ushr 24 == 0 }
    if (transparent > 0) {
      println("  $name: $transparent/${pixels.size} transparent pixels, tris=${triangles.size}")
      val cells = triangles.asSequence().map { triangle ->
        kotlin.math.floor((triangle.ax + triangle.bx + triangle.cx) / 3f).toInt() to
            kotlin.math.floor((triangle.az + triangle.bz + triangle.cz) / 3f).toInt()
      }.filter { (x, z) -> x in 0 until map.grid.cols && z in 0 until map.grid.rows }
          .distinct().take(2).toList()
      for ((x, z) in cells) {
        val present = NdsProject.filterSurfaceTriangles(
            terrain, setOf(NdsProject.surfaceCellKey(x, z)), cut = NdsProject.SurfaceCut.FREEFORM)
            .groupBy { it.texture }
            .map { (material, faces) ->
              val low = faces.minOf { minOf(it.ay, it.by, it.cy) }
              val high = faces.maxOf { maxOf(it.ay, it.by, it.cy) }
              "$material@%.2f..%.2f".format(low, high)
            }
        println("    cell $x,$z also contains $present")
      }
    }
  }

  // How often does the chosen surface sit above another one in the same square? That is the case
  // where the square is built from an overhead layer (canopy, tall grass) instead of the floor.
  println()
  println("--- layering: squares where the chosen surface is NOT the lowest ---")
  var layered = 0
  val layeredExamples = mutableListOf<String>()
  for (x in 0 until map.grid.cols) {
    for (z in 0 until map.grid.rows) {
      val cell = setOf(NdsProject.surfaceCellKey(x, z))
      val squares = NdsProject.filterSurfaceTriangles(terrain, cell, cut = NdsProject.SurfaceCut.SQUARES)
      if (squares.isEmpty()) continue
      val free = NdsProject.filterSurfaceTriangles(terrain, cell, cut = NdsProject.SurfaceCut.FREEFORM)
      val chosenY = squares.flatMap { listOf(it.ay, it.by, it.cy) }.average()
      // Only count near-horizontal surfaces as "floors"; walls are not alternatives.
      val floors = free.filter { t ->
        val a = kotlin.math.abs(
            (t.bx - t.ax) * (t.cz - t.az) - (t.cx - t.ax) * (t.bz - t.az)) / 2f
        a > 0.02f
      }
      if (floors.isEmpty()) continue
      val lowestY = floors.minOf { minOf(it.ay, it.by, it.cy) }
      if (chosenY > lowestY + 0.25f) {
        layered++
        if (layeredExamples.size < 10) {
          val byTex = floors.groupBy { it.texture }
              .map { (t, list) -> "%s@%.2f".format(t, list.minOf { minOf(it.ay, it.by, it.cy) }) }
          layeredExamples += "($x,$z) chose %s at y=%.2f; floor y=%.2f; present: %s"
              .format(squares.first().texture, chosenY, lowestY, byTex)
        }
      }
    }
  }
  println("squares built from an overhead layer instead of the floor: $layered of $produced")
  layeredExamples.forEach { println("   $it") }
}
