package de.lananahwp.openmmo.mapeditor

import de.lananahwp.openmmo.mapeditor.project.NdsProject
import java.io.File

/**
 * Resolves every map from one licensed read-only ROM into an ignored LumiMMO derived cache. The ROM
 * project is constructed once so batch export does not repeatedly parse the same archives.
 */
fun main(args: Array<String>) {
  require(args.size in 2..3) {
    "Usage: LumiSceneBatchExport DECOMP_ROOT OUTPUT_ROOT [READ_ONLY_ROM]"
  }
  val project =
      NdsProject(
          File(args[0]).canonicalFile,
          args.getOrNull(2)?.let(::File),
      )
  require(project.hasRom) { "A matching read-only NDS ROM is required under the editor project" }
  val outputRoot = File(args[1]).canonicalFile.also(File::mkdirs)
  val maps = project.mapNames.mapNotNull(project::loadMap).filterNot { it.isCustom }
  val worldMatrixCells = lumiWorldMatrixCells(project)
  val failures = mutableListOf<Pair<String, String>>()
  var exported = 0
  maps.forEachIndexed { index, map ->
    val directory = File(outputRoot, map.name.toSceneDirectoryName())
    runCatching { exportLumiScene(project, map.name, directory, worldMatrixCells) }
        .onSuccess { exported++ }
        .onFailure { failure ->
          failures += map.name to (failure.message ?: failure::class.simpleName.orEmpty())
          System.err.println("Skipped ${map.name}: ${failures.last().second}")
        }
    if ((index + 1) % 25 == 0) {
      println("Processed ${index + 1}/${maps.size} maps")
    }
  }
  File(outputRoot, "batch-report.txt").writeText(
      buildString {
        appendLine("family=${project.family.regionName}")
        appendLine("discovered=${maps.size}")
        appendLine("exported=$exported")
        appendLine("failed=${failures.size}")
        failures.forEach { (map, reason) -> appendLine("$map\t$reason") }
      },
      Charsets.UTF_8,
  )
  check(exported > 0) { "No scenes could be exported" }
  println(
      "Batch export complete: $exported/${maps.size} scenes; " +
          "${failures.size} skipped. Report: ${File(outputRoot, "batch-report.txt").path}")
}

private fun String.toSceneDirectoryName(): String =
    removePrefix("MAP_HEADER_")
        .removePrefix("MAP_")
        .lowercase()
        .replace('_', '-')
