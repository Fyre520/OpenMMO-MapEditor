package de.lananahwp.openmmo.mapeditor.core

import java.io.File

/** Finds billboard-sized NSBTX resources in a read-only local ROM. */
fun main(args: Array<String>) {
  require(args.size == 1) { "Usage: NdsSpriteDiagnostic ROM" }
  val rom = NdsRom(File(args[0]).canonicalFile)
  for (path in rom.paths) {
    val bytes = runCatching { rom.read(path) }.getOrNull() ?: continue
    if (bytes.containsAscii("hero")) println("contains hero: $path size=${bytes.size}")
    val files =
        if (bytes.startsWith("NARC")) runCatching { Narc(bytes).files }.getOrNull() ?: continue
        else listOf(bytes)
    for ((member, payload) in files.withIndex()) {
      if (path == "a/0/8/1" && payload.containsAscii("hero")) {
        println("hero member: $member size=${payload.size} magic=${payload.magic()}")
        if (member == 69 || member == 70) {
          val pack = NdsNsbtx.parseStrict(payload)
          println("  decoded textures=${pack.textures.size} palettes=${pack.palettes.size}")
        }
      }
      if (!payload.startsWith("BTX0") && !payload.startsWith("BMD0")) continue
      val textures = NdsNsbtx.parse(payload)
      if (textures.any { it.name.equals("hero", true) || it.name.equals("heroine", true) }) {
        println("$path member=$member textures=${textures.size}")
        for ((index, texture) in textures.withIndex()) println(
            "  frame=$index name=${texture.name} size=${texture.width}x${texture.height} " +
                "format=${texture.format}")
      }
    }
  }
}

private fun ByteArray.startsWith(ascii: String): Boolean =
    size >= ascii.length && ascii.indices.all { this[it] == ascii[it].code.toByte() }

private fun ByteArray.containsAscii(ascii: String): Boolean {
  val needle = ascii.encodeToByteArray()
  return (0..size - needle.size).any { offset ->
    needle.indices.all { this[offset + it] == needle[it] }
  }
}

private fun ByteArray.magic(): String =
    take(4).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
