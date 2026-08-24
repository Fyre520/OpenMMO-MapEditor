package de.lananahwp.openmmo.mapeditor.core

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Exports the normal Ethan/Lyra billboard frames from a read-only local HGSS ROM. */
fun main(args: Array<String>) {
  require(args.size == 2) { "Usage: NdsPlayerSpriteExport ROM OUTPUT_DIRECTORY" }
  val rom = NdsRom(File(args[0]).canonicalFile)
  require(rom.gameCode in setOf("IPGE", "IPKE")) { "Expected a US HGSS ROM" }
  val members = rom.narc("a/0/8/1")
  val output = File(args[1]).canonicalFile.also(File::mkdirs)
  exportSheet(members[69], File(output, "hgss-male.png"))
  exportSheet(members[70], File(output, "hgss-female.png"))
}

private fun exportSheet(bytes: ByteArray, output: File) {
  val textures = NdsNsbtx.parseStrict(bytes).textures
  require(textures.size == 32) { "Expected 32 normal player frames, got ${textures.size}" }
  require(textures.all { it.width == 32 && it.height == 32 }) { "Unexpected player frame size" }
  val image = BufferedImage(32, 32 * textures.size, BufferedImage.TYPE_INT_ARGB)
  for ((index, texture) in textures.withIndex()) {
    val pixels = requireNotNull(texture.decode()) { "Could not decode player frame $index" }
    image.setRGB(0, index * 32, 32, 32, pixels, 0, 32)
  }
  check(ImageIO.write(image, "png", output))
  println("Exported ${textures.size} player frames -> ${output.path}")
}
