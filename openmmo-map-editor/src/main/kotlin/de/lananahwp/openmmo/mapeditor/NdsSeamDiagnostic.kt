package de.lananahwp.openmmo.mapeditor

import de.lananahwp.openmmo.mapeditor.project.NdsProject
import java.io.File

fun main(args: Array<String>) {
  val repository = File(args.firstOrNull() ?: "..").canonicalFile
  val cases =
      listOf(
          File(repository, "decomp/pokeheartgold") to
              listOf("MAP_ROUTE_1", "MAP_ROUTE_3", "MAP_ROUTE_28", "MAP_VIRIDIAN", "MAP_CERULEAN", "MAP_ROUTE_40"),
          File(repository, "decomp/pokeplatinum") to
              listOf(
                  "MAP_HEADER_CANALAVE_CITY",
                  "MAP_HEADER_JUBILIFE_CITY",
                  "MAP_HEADER_OREBURGH_CITY",
                  "MAP_HEADER_ETERNA_CITY",
              ),
      )
  for ((root, names) in cases) {
    val project = NdsProject(root)
    for (name in names) {
      val map = project.loadMap(name)
      println(map?.let(project::seamDiagnostics) ?: "$name: unavailable")
    }
  }
}
