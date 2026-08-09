package de.lananahwp.openmmo.mapeditor

import de.lananahwp.openmmo.mapeditor.core.DecompBase
import de.lananahwp.openmmo.mapeditor.core.MapRenderer
import de.lananahwp.openmmo.mapeditor.json.JsonParser
import de.lananahwp.openmmo.mapeditor.project.DecompProject
import de.lananahwp.openmmo.mapeditor.project.OpenmmoExporter
import de.lananahwp.openmmo.mapeditor.ui.EditorFrame
import de.lananahwp.openmmo.mapeditor.ui.MapCanvas
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import java.util.Base64

fun main(args: Array<String>) {
  val root = args.firstOrNull()?.let(::File) ?: File("../decomp/pokeemerald").canonicalFile
  require(File(root, "data/maps/map_groups.json").isFile) { "Missing pokeemerald decomp at $root" }

  var painted = 0
  var picked: Pair<Int, Int>? = null
  val testCanvas = MapCanvas({ _, _, _ -> painted++ }, { _, _ -> }, { x, y -> picked = x to y })
  testCanvas.mapImage = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
  testCanvas.blockWidth = 2
  testCanvas.blockHeight = 2
  testCanvas.dispatchEvent(mouse(testCanvas, MouseEvent.MOUSE_PRESSED, 4, 4, MouseEvent.BUTTON1))
  testCanvas.dispatchEvent(
      mouse(testCanvas, MouseEvent.MOUSE_DRAGGED, 5, 5, MouseEvent.NOBUTTON, InputEvent.BUTTON1_DOWN_MASK))
  testCanvas.dispatchEvent(mouse(testCanvas, MouseEvent.MOUSE_RELEASED, 5, 5, MouseEvent.BUTTON1))
  testCanvas.dispatchEvent(mouse(testCanvas, MouseEvent.MOUSE_PRESSED, 20, 4, MouseEvent.BUTTON3))
  check(painted == 1)
  check(picked == 1 to 0)
  println("canvas stroke deduplication + eyedropper OK")

  // 1. Frame constructs, opens project, loads first map, renders.
  val frame = EditorFrame(listOf(root))
  frame.dispose()
  println("frame constructed + disposed OK")

  val source = DecompBase(root)
  val project = DecompProject(root, source)
  val renderer = MapRenderer(source)
  val exporter = OpenmmoExporter(project)

  val runtimeDir = File(System.getProperty("java.io.tmpdir"), "openmmo-map-export")
  runtimeDir.deleteRecursively()
  val littlerootFile = exporter.exportMap(project.loadMap("LittlerootTown")!!, runtimeDir)
  val littlerootJson = JsonParser.parse(littlerootFile.readText()).asObj()!!
  val firstWarp = littlerootJson.arr("warps")!!.items.first().asObj()!!
  check(littlerootFile.extension == "json")
  check(littlerootJson.str("mapType") == "UNKNOWN_0x01")
  check(firstWarp.int("targetBankId") == 51)
  check(littlerootJson.get("downloadData")!!.asBool() == true)
  println("runtime export: ${littlerootFile.absolutePath}")

  // 2. Building maps resolve their tiles.
  val gym = project.loadMap("DewfordTown_Gym")!!
  val gymImg = renderer.renderMap(gym.layout)
  var distinct = 0
  val seen = HashSet<Int>()
  for (y in 0 until gymImg.height step 16) for (x in 0 until gymImg.width step 16) {
    val c = gymImg.getRGB(x, y)
    if (seen.add(c)) distinct++
  }
  println("DewfordTown_Gym ${gymImg.width}x${gymImg.height} top-left colors at block corners: $distinct (should be > 1, no all-black)")

  // 3. Edit a block, verify image + export reflect it.
  val map = project.loadMap("PetalburgCity")!!
  val before = renderer.renderMap(map.layout)
  val origBlock = map.layout.tileAt(0, 0) ?: 0
  val otherMetatile = if ((origBlock and 0x3FF) != 0x001) 0x001 else 0x002
  map.layout.setTile(0, 0, otherMetatile)
  val after = renderer.renderMap(map.layout)
  var diffPx = 0
  for (y in 0 until 16) for (x in 0 until 16) if (before.getRGB(x, y) != after.getRGB(x, y)) diffPx++
  println("edit reflected in render: diff px in 16x16 corner = $diffPx (expect > 0)")

  val text = exporter.renderMap(map)
  val bd = JsonParser.parse(text).asObj()!!.str("blockData")!!
  val bytes = Base64.getDecoder().decode(bd)
  val diskLow = bytes[0].toInt() and 0xFF
  println("exported blockData low byte at tile 0 (pre-save, from disk): 0x${diskLow.toString(16)}")

  // 4. Save through a temporary decomp copy.
  val tmp = File(System.getProperty("java.io.tmpdir"), "openmmo-map-editor/decomp-copy")
  tmp.deleteRecursively()
  val layoutFile = File(root, "data/layouts/PetalburgCity/map.bin")
  val rel = "data/layouts/PetalburgCity"
  val dest = File(tmp, rel)
  dest.mkdirs()
  java.nio.file.Files.copy(layoutFile.toPath(), File(dest, "map.bin").toPath())
  java.nio.file.Files.copy(File(root, "data/layouts/PetalburgCity/border.bin").toPath(), File(dest, "border.bin").toPath())
  File(tmp, "data/layouts").mkdirs()
  File(tmp, "data/layouts/layouts.json").writeText(File(root, "data/layouts/layouts.json").readText())
  File(tmp, "data/maps").mkdirs()
  File(tmp, "data/maps/PetalburgCity").mkdirs()
  File(tmp, "data/maps/PetalburgCity/map.json").writeText(map.toJsonString())
  File(tmp, "data/maps/map_groups.json").writeText(File(root, "data/maps/map_groups.json").readText())

  val proj2 = DecompProject(tmp, DecompBase(tmp))
  val m2 = proj2.loadMap("PetalburgCity")!!
  m2.layout.setTile(0, 0, otherMetatile)
  proj2.save(m2)
  val saved = File(dest, "map.bin").readBytes()
  val savedRaw = (saved[0].toInt() and 0xFF) or ((saved[1].toInt() and 0xFF) shl 8)
  println("save round-trip: map.bin byte0 u16=0x${savedRaw.toString(16)} (expect 0x${otherMetatile.toString(16)})")
  // Exporter reads saved bytes from disk.
  val text2 = OpenmmoExporter(proj2).renderMap(m2)
  val bd2 = JsonParser.parse(text2).asObj()!!.str("blockData")!!
  val bytes2 = Base64.getDecoder().decode(bd2)
  val savedLow = bytes2[0].toInt() and 0xFF
  println("post-save exported blockData low byte at tile 0: 0x${savedLow.toString(16)} (expect 0x${(otherMetatile and 0xFF).toString(16)})")

  // 5. Create a new map and layout.
  val layoutId = "LAYOUT_TEST_TOWN"
  proj2.createLayout(layoutId, 10, 8, "gTileset_General", "gTileset_Petalburg", 0x001)
  val created = proj2.createMap(
      groupIndex = 0,
      index = 0,
      dirName = "TestTown",
      id = "MAP_TEST_TOWN",
      name = "Test Town",
      layoutId = layoutId,
      music = "MUS_NONE",
      mapsec = "MAPSEC_NONE",
      weather = "WEATHER_SUNNY",
      mapType = "MAP_TYPE_TOWN",
      requiresFlash = false,
  )
  println("created map: ${created.dirName} at group0 index0, layout=${created.layout.name} ${created.layout.width}x${created.layout.height} blocks=${created.layout.blocks.size}")
  println("  blocks all grass: ${created.layout.blocks.all { it and 0x3FF == 0x001 }}")
  check(created.layout.border.size == 4)
  val mapJsonExists = File(tmp, "data/maps/TestTown/map.json").exists()
  println("  map.json written: $mapJsonExists")
  println("  id unique lookup: ${proj2.addressOf("MAP_TEST_TOWN")?.mapIndex} ${proj2.mapExists("MAP_TEST_TOWN")}")
  // Reload from a fresh project to prove persistence.
  val proj3 = DecompProject(tmp, DecompBase(tmp))
  val reloaded = proj3.loadMap("TestTown")
  println("  reloaded after re-open: ${reloaded != null} group=${reloaded?.groupName} index=${reloaded?.mapIndex}")
  // Export the new map.
  val newExport = OpenmmoExporter(proj3).renderMap(reloaded!!)
  val newJson = JsonParser.parse(newExport).asObj()!!
  println("  export has bank/mapId: ${newJson.int("bankId") == proj3.wireBank(0)} ${newJson.int("mapId") == 0}")

  // 6. Create a map in bank 84.
  proj3.ensureGroup(84 - proj3.region.gbaBankOffset)
  println("  groupCount after ensure: ${proj3.groupOrder.size}")
  val outOfRange = proj3.createMap(
      groupIndex = 84 - proj3.region.gbaBankOffset,
      index = 0,
      dirName = "CustomRoom",
      id = "MAP_CUSTOM_ROOM",
      name = "Custom Room",
      layoutId = layoutId,
      music = "MUS_NONE",
      mapsec = "MAPSEC_NONE",
      weather = "WEATHER_NONE",
      mapType = "MAP_TYPE_INDOOR",
      requiresFlash = false,
  )
  println("  out-of-range map: bank=${proj3.wireBank(outOfRange.groupIndex)} id=${outOfRange.mapIndex} = ${outOfRange.dirName}")
  println("  reload after save: ${proj3.loadMap("CustomRoom") != null}")

  val original = proj3.loadMap("PetalburgCity")!!
  val originalMapFile = File(tmp, "data/maps/PetalburgCity/map.json")
  val originalLayoutFile = File(tmp, "data/layouts/PetalburgCity/map.bin")
  val originalMapText = originalMapFile.readText()
  val originalLayoutBytes = originalLayoutFile.readBytes()
  val duplicate = proj3.duplicateAsOverride(original, "PetalburgCity_Custom", "Petalburg Custom")
  check(duplicate.groupIndex == original.groupIndex)
  check(duplicate.mapIndex != original.mapIndex)
  check(duplicate.exportGroupIndex == original.groupIndex)
  check(duplicate.exportMapIndex == original.mapIndex)
  check(duplicate.warps.size == original.warps.size)
  check(duplicate.objects.size == original.objects.size)
  check(duplicate.layout.blocks == original.layout.blocks)
  check(originalMapFile.readText() == originalMapText)
  check(originalLayoutFile.readBytes().contentEquals(originalLayoutBytes))
  val duplicateExport = JsonParser.parse(OpenmmoExporter(proj3).renderMap(duplicate)).asObj()!!
  check(duplicateExport.int("bankId") == proj3.wireBank(original.groupIndex))
  check(duplicateExport.int("mapId") == original.mapIndex)
  val exportAllDir = File(System.getProperty("java.io.tmpdir"), "openmmo-map-export-all")
  exportAllDir.deleteRecursively()
  val targetBank = proj3.wireBank(original.groupIndex)
  val matching =
      OpenmmoExporter(proj3).exportAll(exportAllDir).filter { file ->
        val json = JsonParser.parse(file.readText()).asObj()!!
        json.int("regionId") == proj3.region.regionId &&
            json.int("bankId") == targetBank &&
            json.int("mapId") == original.mapIndex
      }
  check(matching.size == 1)
  check(matching.single().nameWithoutExtension == duplicate.dirName)
  println("runtime override copy preserves its original")
  println("done")
}

private fun mouse(
    source: MapCanvas,
    id: Int,
    x: Int,
    y: Int,
    button: Int,
    modifiers: Int = 0,
): MouseEvent = MouseEvent(source, id, 0L, modifiers, x, y, 1, false, button)
