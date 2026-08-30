package de.lananahwp.openmmo.mapeditor

import de.lananahwp.openmmo.mapeditor.core.NdsMapData
import de.lananahwp.openmmo.mapeditor.core.NdsRom
import java.io.File

/** Read-only whole-archive validator for the Gen 4 BDHC parser. */
fun main(args: Array<String>) {
  require(args.size >= 2) { "Usage: bdhcDiagnostic <rom.nds> <pt|hgss>" }
  val romFile = File(args[0])
  require(romFile.isFile) { "ROM not found: ${romFile.absolutePath}" }
  val hgss = when (args[1].lowercase()) {
    "pt", "platinum" -> false
    "hgss", "heartgold", "soulsilver" -> true
    else -> error("Family must be pt or hgss")
  }
  val rom = NdsRom(romFile)
  val archivePath = if (hgss) "a/0/6/5" else "fielddata/land_data/land_data.narc"
  require(rom.has(archivePath)) { "${romFile.name} (${rom.gameCode}) has no $archivePath" }
  val files = rom.narc(archivePath)

  var parsedMaps = 0
  var parsedBdhc = 0
  var points = 0L
  var normals = 0L
  var plates = 0L
  var flatPlates = 0L
  val failures = mutableListOf<Int>()
  files.forEachIndexed { index, bytes ->
    val data = NdsMapData.parse(bytes, hasBgs = hgss)
    if (data == null) {
      failures += index
      return@forEachIndexed
    }
    parsedMaps++
    val bdhc = data.bdhc
    if (bdhc == null) {
      failures += index
      return@forEachIndexed
    }
    parsedBdhc++
    points += bdhc.points.size
    normals += bdhc.normals.size
    plates += bdhc.plates.size
    flatPlates += bdhc.plates.count { plate ->
      val normal = bdhc.normals[plate.normalIndex]
      normal.x == 0 && normal.z == 0
    }
  }

  println(
      "${if (hgss) "HGSS" else "Pt"} ${rom.gameCode}: " +
          "$parsedBdhc/${files.size} BDHC sections valid, points=$points, normals=$normals, " +
          "plates=$plates (flat=$flatPlates, sloped=${plates - flatPlates})")
  check(parsedMaps == files.size && parsedBdhc == files.size) {
    "Failed map-file/BDHC indices: ${failures.joinToString()}"
  }
}
