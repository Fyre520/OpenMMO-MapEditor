package de.lananahwp.openmmo.mapeditor

import de.lananahwp.openmmo.mapeditor.core.NdsMeshSnapshot
import java.io.File
import kotlin.math.floor

/** Dumps the shape of a saved extracted-prop mesh, for diagnosing bad selections. */
fun main(args: Array<String>) {
  val file = File(args.first())
  val mesh = NdsMeshSnapshot.read(file) ?: error("not a readable snapshot: $file")
  val xs = mesh.triangles.flatMap { listOf(it.ax, it.bx, it.cx) }
  val ys = mesh.triangles.flatMap { listOf(it.ay, it.by, it.cy) }
  val zs = mesh.triangles.flatMap { listOf(it.az, it.bz, it.cz) }
  println("triangles=${mesh.triangles.size} textures=${mesh.textures.keys} palettes=${mesh.palettes.keys}")
  for ((name, texture) in mesh.textures) {
    val paletteName = mesh.triangles.firstOrNull { it.texture == name }?.palette.orEmpty()
    val pixels = mesh.palettes[paletteName]?.let(texture::decodeWith) ?: texture.decode()
    if (pixels != null) {
      val transparent = pixels.count { it ushr 24 == 0 }
      val translucent = pixels.count { (it ushr 24) in 1..254 }
      println("texture $name ${texture.width}x${texture.height} color0=${texture.color0} " +
          "transparent=$transparent translucent=$translucent opaque=${pixels.size - transparent - translucent}")
      if (texture.width <= 32 && texture.height <= 32) {
        for (y in 0 until texture.height) {
          println("    " + (0 until texture.width).joinToString("") { x ->
            if (pixels[y * texture.width + x] ushr 24 == 0) "." else "#"
          })
        }
      }
    }
  }
  println("bbox X %.2f..%.2f  Y %.2f..%.2f  Z %.2f..%.2f".format(
      xs.min(), xs.max(), ys.min(), ys.max(), zs.min(), zs.max()))
  println("extent (tiles) X=%.2f Y=%.2f Z=%.2f".format(
      xs.max() - xs.min(), ys.max() - ys.min(), zs.max() - zs.min()))

  val sized = mesh.triangles.map { t ->
    val w = maxOf(t.ax, t.bx, t.cx) - minOf(t.ax, t.bx, t.cx)
    val d = maxOf(t.az, t.bz, t.cz) - minOf(t.az, t.bz, t.cz)
    val h = maxOf(t.ay, t.by, t.cy) - minOf(t.ay, t.by, t.cy)
    Triple(maxOf(w, d), h, t.texture)
  }.sortedByDescending { it.first }
  println("largest horizontal footprints:")
  for ((span, h, tex) in sized.take(12)) {
    println("   span=%.2f tiles  height=%.2f  tex=%s".format(span, h, tex))
  }
  println("triangles wider than 2 tiles: ${sized.count { it.first > 2f }} of ${sized.size}")
  if (mesh.triangles.size <= 8) for (triangle in mesh.triangles) {
    println("  wrap repeat=${triangle.repeatS},${triangle.repeatT} flip=${triangle.flipS},${triangle.flipT}")
    println("  A %.2f,%.2f uv %.2f,%.2f; B %.2f,%.2f uv %.2f,%.2f; C %.2f,%.2f uv %.2f,%.2f".format(
        triangle.ax, triangle.az, triangle.u0, triangle.v0,
        triangle.bx, triangle.bz, triangle.u1, triangle.v1,
        triangle.cx, triangle.cz, triangle.u2, triangle.v2))
  }

  println("unit-cell materials and UV bounds:")
  val cells = mesh.triangles.groupBy { triangle ->
    floor(minOf(triangle.ax, triangle.bx, triangle.cx) + 1e-4f).toInt() to
        floor(minOf(triangle.az, triangle.bz, triangle.cz) + 1e-4f).toInt()
  }
  for ((cell, triangles) in cells.toSortedMap(compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first })) {
    val materials = triangles.groupBy { it.texture }.entries.joinToString { (texture, tris) ->
      val us = tris.flatMap { listOf(it.u0, it.u1, it.u2) }
      val vs = tris.flatMap { listOf(it.v0, it.v1, it.v2) }
      "%s[%d] uv=%.2f..%.2f,%.2f..%.2f".format(
          texture, tris.size, us.min(), us.max(), vs.min(), vs.max())
    }
    println("  ${cell.first},${cell.second}: $materials")
  }
}
