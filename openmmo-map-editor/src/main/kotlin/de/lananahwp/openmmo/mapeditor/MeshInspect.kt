package de.lananahwp.openmmo.mapeditor

import de.lananahwp.openmmo.mapeditor.core.NdsMeshSnapshot
import java.io.File

/** Dumps the shape of a saved extracted-prop mesh, for diagnosing bad selections. */
fun main(args: Array<String>) {
  val file = File(args.first())
  val mesh = NdsMeshSnapshot.read(file) ?: error("not a readable snapshot: $file")
  val xs = mesh.triangles.flatMap { listOf(it.ax, it.bx, it.cx) }
  val ys = mesh.triangles.flatMap { listOf(it.ay, it.by, it.cy) }
  val zs = mesh.triangles.flatMap { listOf(it.az, it.bz, it.cz) }
  println("triangles=${mesh.triangles.size} textures=${mesh.textures.keys} palettes=${mesh.palettes.keys}")
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
}
