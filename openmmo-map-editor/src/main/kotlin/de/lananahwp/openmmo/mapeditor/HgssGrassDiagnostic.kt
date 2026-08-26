package de.lananahwp.openmmo.mapeditor

import de.lananahwp.openmmo.mapeditor.core.NdsTri
import de.lananahwp.openmmo.mapeditor.project.NdsProject
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

/** Inventories the surfaces used by every outdoor HGSS route cell marked as grass. */
fun main(args: Array<String>) {
  val root = File(args.getOrElse(0) { "." }, "decomp/pokeheartgold")
  val project = NdsProject(root)
  val globalTextures = linkedMapOf<String, Int>()
  val masks = linkedMapOf<Int, MutableList<String>>()

  for (route in 1..48) {
    if (route == 23) continue
    val name = "MAP_ROUTE_$route"
    val map = project.loadMap(name) ?: continue
    val terrain = project.trianglesFor(map)
    val textureArea = linkedMapOf<String, Double>()
    var grassCells = 0
    for (x in 0 until map.grid.cols) for (z in 0 until map.grid.rows) {
      if (map.grid.permissionAt(x, z) !in 2..3) continue
      grassCells++
      val pieces = NdsProject.filterSurfaceTriangles(
          terrain, setOf(NdsProject.surfaceCellKey(x, z)), cut = NdsProject.SurfaceCut.FREEFORM)
      for (tri in pieces) {
        val area = horizontalArea(tri)
        if (area <= 1e-5) continue
        textureArea[tri.texture] = (textureArea[tri.texture] ?: 0.0) + area
        globalTextures[tri.texture] = (globalTextures[tri.texture] ?: 0) + 1
      }
      val mask = grassMask(map.grid.cols, map.grid.rows, x, z) { px, pz ->
        map.grid.permissionAt(px, pz) in 2..3
      }
      masks.getOrPut(mask) { mutableListOf() }.add("$name:$x,$z")
    }
    if (grassCells > 0) {
      val grassTri = terrain.firstOrNull { it.texture == "egrass" }
      val grassTexture = project.texturesFor(map)["egrass"]
      val grassPalette = grassTri?.palette?.let { project.palettesFor(map)[it] }
      val family = listOfNotNull(
          grassTexture?.texdata?.let(::shortHash),
          grassPalette?.let { colors -> shortHash(colors.flatMap(BitConverter::intBytes).toByteArray()) },
      ).joinToString("/")
      val top = textureArea.entries.sortedByDescending { it.value }.take(8)
          .joinToString { "%s=%.1f".format(it.key.ifEmpty { "<plain>" }, it.value) }
      println("$name ${map.grid.cols}x${map.grid.rows} grass=$grassCells family=$family textures: $top")
    }
  }

  println("\nGrass-cell texture names:")
  globalTextures.entries.sortedByDescending { it.value }.forEach { println("  ${it.key}=${it.value}") }
  println("\nNeighbour masks (${masks.size} distinct):")
  masks.entries.sortedBy { it.key }.forEach { (mask, examples) ->
    println("  %02X count=%d example=%s".format(mask, examples.size, examples.first()))
  }
}

private object BitConverter {
  fun intBytes(value: Int): List<Byte> = listOf(
      value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte())
}

private fun shortHash(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).take(5)
        .joinToString("") { "%02x".format(it) }

private fun horizontalArea(tri: NdsTri): Double =
    abs(((tri.bx - tri.ax) * (tri.cz - tri.az) - (tri.cx - tri.ax) * (tri.bz - tri.az)).toDouble()) / 2.0

/** Eight neighbours clockwise from north, one bit per grass neighbour. */
private fun grassMask(
    cols: Int,
    rows: Int,
    x: Int,
    z: Int,
    grass: (Int, Int) -> Boolean,
): Int {
  val offsets = arrayOf(
      0 to -1, 1 to -1, 1 to 0, 1 to 1,
      0 to 1, -1 to 1, -1 to 0, -1 to -1,
  )
  var mask = 0
  offsets.forEachIndexed { bit, (dx, dz) ->
    val px = x + dx; val pz = z + dz
    if (px in 0 until cols && pz in 0 until rows && grass(px, pz)) mask = mask or (1 shl bit)
  }
  return mask
}
