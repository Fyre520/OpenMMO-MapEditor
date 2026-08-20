package de.lananahwp.openmmo.mapeditor

import de.lananahwp.openmmo.mapeditor.ui.EditorFrame
import java.io.File

/** Starts the editor with discovered decomp projects. */
fun main(args: Array<String>) {
  val dirs =
      if (args.isNotEmpty()) listOf(File(args[0]))
      else findDecompRoots(File("."))
  EditorFrame.show(dirs)
}

/** Finds supported decomp submodules (GBA and Gen 4 DS). */
fun findDecompRoots(start: File): List<File> {
  var dir = start.absoluteFile
  repeat(10) {
    val decomp = File(dir, "decomp")
    val found =
        listOf("pokeemerald", "pokefirered").mapNotNull { name ->
          val f = File(decomp, name)
          if (File(f, "data/maps/map_groups.json").exists()) f else null
        } +
            listOf("pokeheartgold", "pokeplatinum", "pokeblack").mapNotNull { name ->
              val f = File(decomp, name)
              val isHg = File(f, "src/data/map_headers.h").isFile &&
                  File(f, "include/constants/maps.h").isFile
              val isPt = File(f, "include/data/map_headers.h").isFile
              if (isHg || isPt) f else null
            }
    if (found.isNotEmpty()) return found
    dir = dir.parentFile ?: return emptyList()
  }
  return emptyList()
}
