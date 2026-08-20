package de.lananahwp.openmmo.mapeditor

import de.lananahwp.openmmo.mapeditor.core.DecompBase
import de.lananahwp.openmmo.mapeditor.core.MapRenderer
import de.lananahwp.openmmo.mapeditor.json.JsonParser
import de.lananahwp.openmmo.mapeditor.project.DecompProject
import de.lananahwp.openmmo.mapeditor.project.NdsExporter
import de.lananahwp.openmmo.mapeditor.project.NdsProject
import de.lananahwp.openmmo.mapeditor.project.OpenmmoExporter
import de.lananahwp.openmmo.mapeditor.ui.EditorFrame
import de.lananahwp.openmmo.mapeditor.ui.MapCanvas
import de.lananahwp.openmmo.mapeditor.ui.MapEventMarker
import de.lananahwp.openmmo.mapeditor.ui.MapEventType
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
  var moved: Pair<MapEventMarker, Pair<Int, Int>>? = null
  var context: Pair<MapEventMarker?, Pair<Int, Int>>? = null
  val testCanvas =
      MapCanvas(
          { _, _, _ -> painted++ },
          { _, _ -> },
          { x, y -> picked = x to y },
          { marker, x, y -> moved = marker to (x to y) },
          { marker, x, y, _, _ -> context = marker to (x to y) },
      )
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
  val marker = MapEventMarker(0, 0, MapEventType.PERSON, 0)
  testCanvas.eventMarkers = listOf(marker)
  testCanvas.eventEditingEnabled = true
  testCanvas.dispatchEvent(mouse(testCanvas, MouseEvent.MOUSE_PRESSED, 4, 4, MouseEvent.BUTTON1))
  testCanvas.dispatchEvent(
      mouse(testCanvas, MouseEvent.MOUSE_DRAGGED, 20, 4, MouseEvent.NOBUTTON, InputEvent.BUTTON1_DOWN_MASK))
  testCanvas.dispatchEvent(mouse(testCanvas, MouseEvent.MOUSE_RELEASED, 20, 4, MouseEvent.BUTTON1))
  check(painted == 1)
  check(moved == marker to (1 to 0))
  testCanvas.dispatchEvent(mouse(testCanvas, MouseEvent.MOUSE_PRESSED, 4, 4, MouseEvent.BUTTON3))
  check(context == marker to (0 to 0))
  testCanvas.dispatchEvent(mouse(testCanvas, MouseEvent.MOUSE_PRESSED, 20, 20, MouseEvent.BUTTON3))
  check(context == null to (1 to 1))
  println("canvas painting, picking, event dragging, and menus OK")

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

  // 7. Gen 4 DS maps: parse HeartGold decomp, edit grid, export NDS-style.
  run {
    val candidates =
        listOf(
            File("../openmmo/decomp/pokeheartgold"),
            File("../decomp/pokeheartgold"),
        )
    val hgRoot = candidates.firstOrNull { it.isDirectory } ?: return@run
    val ndsProject = NdsProject(hgRoot)
    val mapNames = ndsProject.mapNames
    check(mapNames.isNotEmpty()) { "HeartGold decomp exposes no maps" }
    println("DS maps parsed: ${mapNames.size} (family ${ndsProject.family.displayName})")
    if (!ndsProject.hasRom) {
      println("HG project has no local ROM; ROM-backed map checks skipped")
      return@run
    }
    println("HG project ROM discovered: ${ndsProject.rom?.gameCode}")

    val route1 = ndsProject.loadMap("MAP_ROUTE_1")!!
    check(route1.mapId >= 0)
    check(route1.header.matrixId >= 0)
    println("  Route1 mapId=${route1.mapId} matrixId=${route1.header.matrixId} grid=${route1.grid.cols}x${route1.grid.rows}")
    val route1Cells = ndsProject.resolveCells(route1)
    println("  Route1 cells (x,y,file): ${route1Cells.map { "(${it.cellX},${it.cellY},${it.fileIndex})" }}")
    check(route1.events.warps.isNotEmpty() || route1.events.objects.isNotEmpty()) {
      "MAP_ROUTE_1 should expose events from its zone_event file"
    }
    // MAP_PALLET and MAP_ROUTE_1 must resolve to different map models.
    if (ndsProject.hasMap("MAP_PALLET")) {
      val pallet = ndsProject.loadMap("MAP_PALLET")!!
      println("  Pallet mapId=${pallet.mapId} matrixId=${pallet.header.matrixId}")
      val palletCells = ndsProject.resolveCells(pallet)
      println("  Pallet cells (x,y,file): ${palletCells.map { "(${it.cellX},${it.cellY},${it.fileIndex})" }}")
      val palletTris = ndsProject.trianglesFor(pallet)
      val routeTris = ndsProject.trianglesFor(route1)
      println("  Pallet tris=${palletTris.size} vs Route1 tris=${routeTris.size} distinct=${palletTris.map { "${it.texture}:${it.ax},${it.az}" }.toSet() != routeTris.map { "${it.texture}:${it.ax},${it.az}" }.toSet()}")
      check(palletTris.isNotEmpty())
    }

    val hgTextures = ndsProject.texturesFor(route1)
    // Debug: parse all HGSS texture packs and look for the route-1 texture names.
    run {
      val rom = ndsProject.rom
      if (rom != null && rom.has("a/0/4/4")) {
        val packs = rom.narc("a/0/4/4")
        var parsed = 0
        val names = mutableSetOf<String>()
        for (p in packs) {
          val ts = de.lananahwp.openmmo.mapeditor.core.NdsNsbtx.parse(p)
          if (ts.isNotEmpty()) parsed++
          ts.forEach { names += it.name }
        }
        val targets = listOf("wallkn_d", "wallkn_g", "sea_rock", "sea_rock_m", "cliffkn", "treekn", "treekn_re")
        println("  HG a/0/4/4: packs=${packs.size} parsed=$parsed totalNames=${names.size} targetPresent=${targets.map { it to (it in names) }}")
        // Compare wallkn time variants: decode each with its own palette and report brightness.
        for (variant in listOf("wallkn_d", "wallkn_g", "wallkn_h")) {
          for (p in packs) {
            val t = de.lananahwp.openmmo.mapeditor.core.NdsNsbtx.parse(p).firstOrNull { it.name == variant } ?: continue
            val px = t.decode() ?: continue
            var lum = 0.0
            for (v in px) {
              val r = (v shr 16) and 0xFF
              val g = (v shr 8) and 0xFF
              val b = v and 0xFF
              if (r == 0 && g == 0 && b == 0 && v != 0xFF000000.toInt()) continue
              lum += r * 0.3 + g * 0.6 + b * 0.1
            }
            lum /= px.size
            val hist = HashMap<Int, Int>()
            for (v in px) hist[v and 0xFFFFFF] = (hist[v and 0xFFFFFF] ?: 0) + 1
            val top = hist.entries.sortedByDescending { it.value }.take(3).map { "0x%06X:%d".format(it.key, it.value) }
            println("  $variant ${t.width}x${t.height} fmt=${t.format} avgLum=%.0f top=%s".format(lum, top))
          }
        }
      }
    }
    val route1Tris = ndsProject.trianglesFor(route1)
    val route1Texnames = route1Tris.map { it.texture }.filter { it.isNotEmpty() }.toSet()
    println(
        "  MAP_ROUTE_1 id=${route1.mapId} matrix=${route1.header.matrixId} " +
            "weather=${route1.header.weather} type=${route1.header.mapType} " +
            "events=${route1.events.warps.size}W/${route1.events.objects.size}O " +
            "textures=${hgTextures.size} modelTexnames=${route1Texnames.size} overlap=${route1Texnames.intersect(hgTextures.keys).size} unmatched=${route1Texnames - hgTextures.keys}")

    // Grid editing round-trips in memory.
    route1.grid.setTile(0, 0, 0, 6)
    route1.grid.setHeight(0, 0, 0, 2)
    route1.grid.setCollision(4, 4, 0x80)
    check(route1.grid.tileAt(0, 0, 0) == 6)
    check(route1.grid.heightAt(0, 0, 0) == 2)
    check(route1.grid.collisionAt(4, 4) == 0x80)

    // Grid persistence round-trips to the project override file.
    ndsProject.saveGrid(route1)
    val overrideFile = File(hgRoot, ".openmmo/nds/MAP_ROUTE_1.json")
    check(overrideFile.isFile)
    check(overrideFile.readText().contains("\"layer_0\""))
    overrideFile.delete()
    File(hgRoot, ".openmmo").deleteRecursively()
    println("  grid persistence round-trip OK")

    val exporter = NdsExporter(ndsProject)
    val json = JsonParser.parse(exporter.renderMap(route1)).asObj()!!
    check(json.int("romType") == 4)
    check(json.int("regionId") == 4)
    check(json.get("downloadData")!!.asBool() == true)
    check(json.int("mapMatrixId") == route1.header.matrixId)
    println("  NDS export: bank=${json.int("bankId")} map=${json.int("mapId")} " +
        "romType=${json.int("romType")} downloadData=${json.get("downloadData")}")

    // Matrix cells are attached to maps that appear in the world matrix.
    val cells = ndsProject.matrixCells()
    check(cells.isNotEmpty())
    println("  world matrix cells: ${cells.size}")

    // A map with real data renders grid + decoded model triangles through the project path.
    var found = false
    for (name in listOf("MAP_ROUTE_1") + ndsProject.mapNames) {
      if (!ndsProject.hasMap(name)) continue
      val m = ndsProject.loadMap(name) ?: continue
      var any = 0
      for (y in 0 until 32) for (x in 0 until 32) {
        if (m.grid.permissionAt(x, y) != 0 || m.grid.collisionAt(x, y) != 0) any++
      }
      val tris = ndsProject.trianglesFor(m)
      println("  HG map $name (areaDataBank=${m.header.areaDataBank}): grid cells=$any model triangles=${tris.size}")
      if (tris.isNotEmpty()) {
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (t in tris.take(2000)) {
          for (v in floatArrayOf(t.ax, t.bx, t.cx)) { minX = minOf(minX, v); maxX = maxOf(maxX, v) }
          for (v in floatArrayOf(t.ay, t.by, t.cy)) { minY = minOf(minY, v); maxY = maxOf(maxY, v) }
          for (v in floatArrayOf(t.az, t.bz, t.cz)) { minZ = minOf(minZ, v); maxZ = maxOf(maxZ, v) }
        }
        println("  coord range x=[$minX,$maxX] y=[$minY,$maxY] z=[$minZ,$maxZ] firstColor=${"%08X".format(tris.first().color)}")
      }
      if (any > 0) {
        found = true
        break
      }
    }
    check(found) { "No HGSS map has grid data" }

    // HeartGold ROM maps archive (a/0/6/5) provides real permission grids.
    val hgRom = File("../openmmo/roms/Pokemon - HeartGold Version (Europe).nds")
    if (hgRom.isFile) {
      val rom = de.lananahwp.openmmo.mapeditor.core.NdsRom(hgRom)
      check(rom.has("a/0/6/5")) { "HG ROM lacks the maps archive" }
      val mapFiles = rom.narc("a/0/6/5")
      check(mapFiles.size > 400)
      val newBark =
          de.lananahwp.openmmo.mapeditor.core.NdsMapData.parse(mapFiles[60], hasBgs = true)!!
      check(newBark.permissions.size == 32 * 32)
      println("HG ROM: maps archive ${mapFiles.size} files, map[60] perms parsed OK")

      // Decode the map model (NSBMD) into 3D geometry.
      val model = newBark.modelBytes
      if (model != null) {
        val parsed = de.lananahwp.openmmo.mapeditor.core.NdsNsbmd.parse(model)
        val texnames = parsed?.materials?.mapNotNull { it.texname.takeIf { n -> n.isNotBlank() } } ?: emptyList()
        println("HG map[60] material texnames: ${texnames.take(6)}")
        val tris = de.lananahwp.openmmo.mapeditor.core.NdsNsbmd.decode(model)
        println("HG map[60] NSBMD decoded triangles: ${tris.size}")
        check(tris.isNotEmpty()) { "NSBMD decode produced no triangles" }
        val someColor = tris.first().color
        println("  sample triangle color = 0x${"%08X".format(someColor)}")
      }
    }
  }

  // 8. Gen 4 ROM data: filesystem, map names, and Platinum land-data grids.
  run {
    val romFile = File("../openmmo/roms/Pokemon - Platinum Version (USA) (Rev 1).nds")
    if (!romFile.isFile) return@run
    val rom = de.lananahwp.openmmo.mapeditor.core.NdsRom(romFile)
    check(rom.has("fielddata/land_data/land_data.narc"))
    check(rom.has("fielddata/maptable/mapname.bin"))
    check(rom.has("fielddata/areadata/area_data.narc"))
    check(rom.has("fielddata/areadata/area_map_tex/map_tex_set.narc"))
    // NSBTX texture decoding from the Platinum texture pack.
    run {
      val texNarc = rom.narc("fielddata/areadata/area_map_tex/map_tex_set.narc")
      check(texNarc.isNotEmpty())
      var decoded = 0
      for (f in texNarc.take(4)) {
        val textures = de.lananahwp.openmmo.mapeditor.core.NdsNsbtx.parse(f)
        for (t in textures) {
          val px = t.decode() ?: continue
          var opaque = 0
          val seen = HashSet<Int>()
          for (i in px) {
            if ((i ushr 24) and 0xFF > 0) opaque++
            seen.add(i and 0xFFFFFF)
          }
          println("NSBTX ${t.name} ${t.width}x${t.height} fmt=${t.format} opaquePx=$opaque colors=${seen.size}")
          if (opaque > 30 && seen.size > 2) decoded++
        }
      }
      check(decoded > 0) { "No NSBTX texture decoded into meaningful pixels" }
    }
    val names = de.lananahwp.openmmo.mapeditor.core.NdsMapNames.parse(
        rom.read("fielddata/maptable/mapname.bin"))
    check(names.size > 400) { "Platinum ROM map names too few" }
    check(names[3].isNotBlank())
    val landFiles = rom.narc("fielddata/land_data/land_data.narc")
    check(landFiles.size > 100)
    val firstMapFile = landFiles.first { it.size > 16 }
    val mapData = de.lananahwp.openmmo.mapeditor.core.NdsMapData.parse(firstMapFile, hasBgs = false)!!
    check(mapData.permissions.size == 32 * 32)
    check(mapData.collisions.size == 32 * 32)
    println(
        "Platinum ROM: ${names.size} map names, land files ${landFiles.size}, " +
            "first map perms parsed OK")

    // Full project integration: initialized Platinum decomp + ROM map data.
    val ptDecomp = File("../openmmo/decomp/pokeplatinum")
    if (ptDecomp.isDirectory) {
      val project = NdsProject(ptDecomp)
      check(project.mapNames.isNotEmpty())
      check(project.hasRom)
      val name = project.mapNames.getOrNull(3) ?: project.mapNames.first()
      val map = project.loadMap(name)!!
      var any = 0
      for (y in 0 until 32) for (x in 0 until 32) {
        if (map.grid.permissionAt(x, y) != 0 || map.grid.collisionAt(x, y) != 0) any++
      }
      check(any > 0) { "Platinum map $name has no ROM grid data" }
      val tris = project.trianglesFor(map)
      val textures = project.texturesFor(map)
      val palettes = project.palettesFor(map)
      val modelTexnames = tris.map { it.texture }.filter { it.isNotEmpty() }.toSet()
      val modelPalnames = tris.map { it.palette }.filter { it.isNotEmpty() }.toSet()
      println("Platinum project: ${project.mapNames.size} maps, $name grid cells with data = $any, model triangles = ${tris.size}, textures=${textures.size}, palettes=${palettes.size}, model texnames=${modelTexnames.size}, overlap=${modelTexnames.intersect(textures.keys).size}, model palnames=${modelPalnames.size}, palOverlap=${modelPalnames.intersect(palettes.keys).size}")
      if (modelPalnames.isNotEmpty()) {
        println("  model palnames: ${modelPalnames.take(8)}")
        println("  pack palnames: ${palettes.keys.take(8)}")
        val unmatched = modelPalnames - palettes.keys
        println("  unmatched palnames: $unmatched")
      }
      // Decode the grass texture with the grass palette and inspect colors.
      val grassTex = textures["nectgrass"] ?: textures["grass"]
      val grassPal = palettes["grass"] ?: palettes.values.firstOrNull()
      if (grassTex != null && grassPal != null) {
        val px = grassTex.decodeWith(grassPal) ?: IntArray(0)
        val hist = HashMap<Int, Int>()
        for (p in px) {
          val key = (p and 0xFFFFFF)
          hist[key] = (hist[key] ?: 0) + 1
        }
        val top = hist.entries.sortedByDescending { it.value }.take(6)
        println("  grass tex ${grassTex.width}x${grassTex.height} fmt=${grassTex.format}: top colors=" + top.map { (k, c) -> "0x%06X(%d)".format(k, c) })
      }

      // Render the populated Platinum map through the 3D view.
      val view =
          de.lananahwp.openmmo.mapeditor.ui.NdsSoftwareMapView({ _, _ -> }, { _, _, _ -> })
      view.grid = map.grid
      view.setSize(320, 240)
      val img = BufferedImage(320, 240, BufferedImage.TYPE_INT_ARGB)
      val vg = img.createGraphics()
      view.paint(vg)
      vg.dispose()
      val distinct = HashSet<Int>()
      for (y in 0 until img.height) for (x in 0 until img.width) {
        val rgb = img.getRGB(x, y)
        if (((rgb shr 16) and 0xFF) > 90 || ((rgb shr 8) and 0xFF) > 90 || (rgb and 0xFF) > 90) {
          distinct += rgb
        }
      }
      check(distinct.size > 2) { "Platinum map render has no terrain variation" }
      println("  rendered $name: distinct terrain colors = ${distinct.size}")
    }

    // Fresh submodule (no ROM beside it) must still discover openmmo/roms.
    val freshDecomp = File("../decomp/pokeplatinum")
    if (freshDecomp.isDirectory) {
      val fp = NdsProject(freshDecomp)
      check(fp.mapNames.isNotEmpty()) { "Fresh Platinum decomp exposes no maps" }
      check(fp.hasRom) { "Fresh Platinum decomp must discover the ROM" }
      val fm = fp.loadMap(fp.mapNames.first())!!
      var any = 0
      for (y in 0 until 32) for (x in 0 until 32) {
        if (fm.grid.permissionAt(x, y) != 0 || fm.grid.collisionAt(x, y) != 0) any++
      }
      check(any > 0) { "Fresh Platinum map has no ROM grid data" }
      println("Fresh Platinum submodule: ${fp.mapNames.size} maps, ROM found, grid data present")
    }
  }

  // 9. DS 3D view renders grid pixels offscreen.
  run {
    val view =
        de.lananahwp.openmmo.mapeditor.ui.NdsSoftwareMapView({ _, _ -> }, { _, _, _ -> })
    val g = de.lananahwp.openmmo.mapeditor.model.NdsGrid()
    g.setTile(0, 8, 8, 0)
    g.setPermission(4, 4, 0x10)
    g.setCollision(4, 4, 0x80)
    view.grid = g
    view.modelTriangles =
        listOf(
            de.lananahwp.openmmo.mapeditor.core.NdsTri(
                0f, 0f, 0f, 8f, 0f, 0f, 0f, 8f, 0f, 0xFFFF3333.toInt(), 0f, 0f, 0f, 0f, 0f, 0f),
            de.lananahwp.openmmo.mapeditor.core.NdsTri(
                0f, 0f, 8f, 8f, 0f, 8f, 8f, 4f, 8f, 0xFF33FF33.toInt(), 0f, 0f, 0f, 0f, 0f, 0f),
        )
    view.setSize(400, 300)
    val img = BufferedImage(400, 300, BufferedImage.TYPE_INT_ARGB)
    val g2 = img.createGraphics()
    view.paint(g2)
    g2.dispose()
    var nonBg = 0
    var ground = 0
    for (y in 0 until img.height) for (x in 0 until img.width) {
      val rgb = img.getRGB(x, y)
      if ((rgb and 0xFF000000.toInt()) != 0) nonBg++
      val r = (rgb shr 16) and 0xFF
      val g = (rgb shr 8) and 0xFF
      val b = rgb and 0xFF
      // Background is (30,33,40); ground/tiles use much brighter colors.
      if (g > 90 || b > 90 || r > 90) ground++
    }
    println("DS 3D render: opaque=$nonBg bright(ground)=$ground (expect ground > 1000)")
    check(ground > 1000) { "DS 3D view drew nothing" }
  }

  // 10. Editor discovery + real model rendering path.
  run {
    val roots = findDecompRoots(File(".."))
    for (root in roots) {
      val isDs =
          File(root, "src/data/map_headers.h").isFile ||
              File(root, "include/data/map_headers.h").isFile
      if (!isDs) continue
      val p = NdsProject(root)
      println("discovered DS decomp: $root hasRom=${p.hasRom} rom=${p.rom?.gameCode} maps=${p.mapNames.size}")
      check(p.mapNames.isNotEmpty()) { "Discovered DS decomp $root exposes no maps" }
      if (!p.hasRom) {
        println("  no local ROM; ROM-backed rendering checks skipped")
        continue
      }
      // Find a map with real data, then render grid + model through the actual view.
      for (name in p.mapNames) {
        val m = p.loadMap(name) ?: continue
        var any = 0
        for (y in 0 until 32) for (x in 0 until 32) {
          if (m.grid.permissionAt(x, y) != 0 || m.grid.collisionAt(x, y) != 0) any++
        }
        if (any == 0) continue
        val view =
            de.lananahwp.openmmo.mapeditor.ui.NdsSoftwareMapView({ _, _ -> }, { _, _, _ -> })
        view.grid = m.grid
        view.modelTriangles = p.trianglesFor(m)
        view.setSize(320, 240)
        val img = BufferedImage(320, 240, BufferedImage.TYPE_INT_ARGB)
        val vg = img.createGraphics()
        view.paint(vg)
        vg.dispose()
        var bright = 0
        var modelish = 0
        for (y in 0 until img.height) for (x in 0 until img.width) {
          val rgb = img.getRGB(x, y)
          val r = (rgb shr 16) and 0xFF
          val g = (rgb shr 8) and 0xFF
          val b = rgb and 0xFF
          if (g > 90 || b > 90 || r > 90) bright++
          // Model colors are near-neutral (R≈G≈B) grays; terrain is saturated.
          if (kotlin.math.abs(r - g) < 25 && kotlin.math.abs(g - b) < 25 && r > 100) modelish++
        }
        println(
            "  rendered $name: bright=$bright modelGray=$modelish tris=${p.trianglesFor(m).size}")
        check(modelish > 200) { "No model pixels rendered for $name" }
        break
      }
    }
  }

  // 11. JOGL availability check (non-fatal).
  run {
    try {
      val profile = com.jogamp.opengl.GLProfile.get(com.jogamp.opengl.GLProfile.GL2)
      val caps = com.jogamp.opengl.GLCapabilities(profile)
      val canvas = com.jogamp.opengl.awt.GLCanvas(caps)
      println("JOGL available: profile=${profile} canvas=${canvas.javaClass.simpleName}")
    } catch (t: Throwable) {
      println("JOGL unavailable: ${t.message}")
    }
  }

  // 12. Targeted texture diagnosis for Oreburgh.
  run {
    val ore = NdsProject(File("../decomp/pokeplatinum"))
    if (!ore.hasRom) {
      println("No local Platinum ROM; targeted ROM diagnostics skipped")
      return@run
    }
    if (!ore.hasMap("MAP_HEADER_OREBURGH_CITY")) return@run
    val m = ore.loadMap("MAP_HEADER_OREBURGH_CITY")!!
    println("Oreburgh areaDataArchiveID=${m.header.areaDataArchiveID} areaDataBank=${m.header.areaDataBank} matrixId=${m.header.matrixId}")
    val cells = ore.resolveCells(m)
    println("Oreburgh cells (x,y,file): ${cells.map { "(${it.cellX},${it.cellY},${it.fileIndex})" }}")
    println("Oreburgh grid: ${m.grid.cols}x${m.grid.rows}")
    val blds = ore.buildingTrianglesFor(m)
    println("Oreburgh building tris: ${blds.size}")
    val bldTexNames = blds.map { it.texture }.filter { it.isNotEmpty() }.distinct()
    val bldTex = ore.texturesFor(m)
    val bldCovered = bldTexNames.count { it in bldTex || ore.palettesFor(m).keys.any { _ -> false } }
    println("  building textures: ${bldTexNames.take(12)} covered=${bldTexNames.count { it in bldTex }}/${bldTexNames.size} totalTextures=${bldTex.size}")
    if (blds.isNotEmpty()) {
      val xs = blds.flatMap { listOf(it.ax, it.bx, it.cx) }
      val zs = blds.flatMap { listOf(it.az, it.bz, it.cz) }
      println("  building coords x=[%.1f,%.1f] z=[%.1f,%.1f]".format(xs.min(), xs.max(), zs.min(), zs.max()))
    }
    val trisOre = ore.trianglesFor(m)
    if (trisOre.isNotEmpty()) {
      val ys = trisOre.flatMap { listOf(it.ay, it.by, it.cy) }
      println("  Oreburgh y range=[%.3f,%.3f]".format(ys.min(), ys.max()))
    }

    // 13. Indoor map resolution: Mining Museum must resolve to its own (indoor) map, not an
    // unrelated outdoor area.
    run {
      val ore2 = NdsProject(File("../decomp/pokeplatinum"))
      if (!ore2.hasMap("MAP_HEADER_MINING_MUSEUM")) return@run
      val mm = ore2.loadMap("MAP_HEADER_MINING_MUSEUM")!!
      val cells = ore2.resolveCells(mm)
      val tris = ore2.trianglesFor(mm)
      println("MiningMuseum headerId=${mm.mapId} areaId=${mm.header.areaDataArchiveID} matrixId=${mm.header.matrixId}")
      println("  cells (x,y,file): ${cells.map { "(${it.cellX},${it.cellY},${it.fileIndex})" }}")
      println("  grid=${mm.grid.cols}x${mm.grid.rows} tris=${tris.size}")
      if (tris.isNotEmpty()) {
        val ys = tris.flatMap { listOf(it.ay, it.by, it.cy) }
        println("  museum y range=[%.3f,%.3f]".format(ys.min(), ys.max()))
        // raw (pre-fit) y range
        val raw = de.lananahwp.openmmo.mapeditor.core.NdsNsbmd.decode(
            de.lananahwp.openmmo.mapeditor.core.NdsMapData.parse(
                ore2.rom!!.narc("fielddata/land_data/land_data.narc")[217], hasBgs = false)!!.modelBytes!!)
        val rawY = raw.flatMap { listOf(it.ay, it.by, it.cy) }
        println("  museum raw y range=[%.3f,%.3f]".format(rawY.min(), rawY.max()))
        // Floor triangles: near the minimum y.
        val minY = ys.min()
        val floorTris = tris.filter { it.ay < minY + 0.1 && it.by < minY + 0.1 && it.cy < minY + 0.1 }
        val floorTex = floorTris.map { it.texture }.filter { it.isNotEmpty() }.distinct()
        val texM = ore2.texturesFor(mm)
        println("  museum floor tris=${floorTris.size} textures=$floorTex")
        for (ft in floorTex.take(6)) {
          val t = texM[ft]
          if (t != null) {
            val px = t.decode()
            val alphaPx = px?.filter { (it ushr 24) and 0xFF == 0 }?.size ?: 0
            println("    floor tex $ft fmt=${t.format} c0=${t.color0} alpha=$alphaPx/${px?.size}")
          } else {
            println("    floor tex $ft MISSING")
          }
        }
      }
      // Survey: how many maps resolve via matrix header-match vs fallback?
      var byHeader = 0; var fallback = 0; var empty = 0
      val fallbackExamples = mutableListOf<String>()
      for (name in ore2.mapNames.take(400)) {
        val map = ore2.loadMap(name) ?: continue
        val cells2 = ore2.resolveCells(map)
        if (cells2.isEmpty()) { empty++; continue }
        if (cells2.size == 1 && cells2[0].cellX == 0 && cells2[0].cellY == 0 && cells2[0].fileIndex == map.mapId) {
          fallback++
          if (fallbackExamples.size < 8) fallbackExamples += "$name id=${map.mapId} mtx=${map.header.matrixId}"
        } else {
          byHeader++
        }
      }
      println("  resolve survey: byMatrix=${byHeader} fallback=${fallback} empty=$empty")
      println("  fallback examples: $fallbackExamples")
    }

    // 15. Horizontal stitching: print post-fit x ranges of adjacent cells for Eterna City.
    run {
      val proj = NdsProject(File("../decomp/pokeplatinum"))
      val eternaName = proj.mapNames.firstOrNull { it.contains("ETERNA") && it.contains("CITY") }
      if (eternaName != null) {
        val map = proj.loadMap(eternaName)!!
        val cells = proj.resolveCells(map)
        val minX = cells.minOf { it.cellX }
        val minY = cells.minOf { it.cellY }
        val globalMinY = cells.mapNotNull {
          val m = proj.rom?.narc("fielddata/land_data/land_data.narc")?.getOrNull(it.fileIndex)?.let { d ->
            de.lananahwp.openmmo.mapeditor.core.NdsMapData.parse(d, hasBgs = false)
          }?.modelBytes ?: return@mapNotNull null
          val raw = de.lananahwp.openmmo.mapeditor.core.NdsNsbmd.decode(m)
          raw.map { t -> minOf(t.ay, t.by, t.cy) }.minOrNull()
        }.minOrNull() ?: 0f
        println("Eterna($eternaName) cells (groundY=$globalMinY):")
        for (c in cells.sortedWith(compareBy({ it.cellY }, { it.cellX }))) {
          val model = proj.rom?.narc("fielddata/land_data/land_data.narc")?.getOrNull(c.fileIndex)?.let { d ->
            de.lananahwp.openmmo.mapeditor.core.NdsMapData.parse(d, hasBgs = false)
          }?.modelBytes ?: continue
          val raw = de.lananahwp.openmmo.mapeditor.core.NdsNsbmd.decode(model)
          if (raw.isEmpty()) continue
          var b0x = Float.MAX_VALUE; var b1x = -Float.MAX_VALUE
          var b0z = Float.MAX_VALUE; var b1z = -Float.MAX_VALUE
          var b0y = Float.MAX_VALUE
          for (t in raw) {
            for (v in floatArrayOf(t.ax, t.bx, t.cx)) { if (v < b0x) b0x = v; if (v > b1x) b1x = v }
            for (v in floatArrayOf(t.ay, t.by, t.cy)) { if (v < b0y) b0y = v }
            for (v in floatArrayOf(t.az, t.bz, t.cz)) { if (v < b0z) b0z = v; if (v > b1z) b1z = v }
          }
          // Ground-level triangles: those whose vertices are near the cell's minimum y.
          val ground = raw.filter { t ->
            minOf(t.ay, t.by, t.cy) < b0y + 0.05
          }
          var g0x = Float.MAX_VALUE; var g1x = -Float.MAX_VALUE
          var g0z = Float.MAX_VALUE; var g1z = -Float.MAX_VALUE
          for (t in ground) {
            for (v in floatArrayOf(t.ax, t.bx, t.cx)) { if (v < g0x) g0x = v; if (v > g1x) g1x = v }
            for (v in floatArrayOf(t.az, t.bz, t.cz)) { if (v < g0z) g0z = v; if (v > g1z) g1z = v }
          }
          println("  cell(${c.cellX},${c.cellY}) rawTris=${raw.size} groundTris=${ground.size} groundX=[%.2f,%.2f] groundZ=[%.2f,%.2f] (boundsX=[%.2f,%.2f])".format(g0x, g1x, g0z, g1z, b0x, b1x))
          // y ranges per texture (to find the water surface / gap filler)
          val byTex = raw.groupBy { it.texture }
          for ((tn, ts) in byTex.toList().sortedByDescending { it.second.size }.take(6)) {
            val tys = ts.flatMap { listOf(it.ay, it.by, it.cy) }
            val tx = ts.flatMap { listOf(it.ax, it.bx, it.cx) }
            println("      tex $tn tris=${ts.size} y=[%.2f,%.2f] x=[%.2f,%.2f]".format(tys.min(), tys.max(), tx.min(), tx.max()))
          }
        }
      }
    }

    // 16. Route 1 (HGSS) stitching: raw + fitted cell positions and ground coverage.
    run {
      val rgProj = NdsProject(File("../decomp/pokeheartgold"))
      if (!rgProj.hasMap("MAP_ROUTE_1")) return@run
      val rgMap = rgProj.loadMap("MAP_ROUTE_1")!!
      val rgCells = rgProj.resolveCells(rgMap)
      val rMinX = rgCells.minOf { it.cellX }
      val rMinY = rgCells.minOf { it.cellY }
      println("Route1 cells=${rgCells.map { "(${it.cellX},${it.cellY},${it.fileIndex})" }} footprint=${rgMap.grid.cols}x${rgMap.grid.rows}")
      for (rc in rgCells) {
        val rModel = rgProj.rom?.narc("a/0/6/5")?.getOrNull(rc.fileIndex)?.let { d ->
          de.lananahwp.openmmo.mapeditor.core.NdsMapData.parse(d, hasBgs = true)
        }?.modelBytes ?: continue
        val rRaw = de.lananahwp.openmmo.mapeditor.core.NdsNsbmd.decode(rModel)
        if (rRaw.isEmpty()) continue
        var b0x = Float.MAX_VALUE; var b1x = -Float.MAX_VALUE
        var b0z = Float.MAX_VALUE; var b1z = -Float.MAX_VALUE
        for (tt in rRaw) {
          for (v in floatArrayOf(tt.ax, tt.bx, tt.cx)) { if (v < b0x) b0x = v; if (v > b1x) b1x = v }
          for (v in floatArrayOf(tt.az, tt.bz, tt.cz)) { if (v < b0z) b0z = v; if (v > b1z) b1z = v }
        }
        val span = maxOf(b1x - b0x, b1z - b0z).coerceAtLeast(1f)
        val scale = 4f
        val cx = (b0x + b1x) / 2f; val cz = (b0z + b1z) / 2f
        val dx = (rc.cellX - rMinX) * 32f; val dz = (rc.cellY - rMinY) * 32f
        val fx0 = (b0x - cx) * scale + 16f + dx; val fx1 = (b1x - cx) * scale + 16f + dx
        val fz0 = (b0z - cz) * scale + 16f + dz; val fz1 = (b1z - cz) * scale + 16f + dz
        println("  cell(${rc.cellX},${rc.cellY}) raw x=[%.2f,%.2f] z=[%.2f,%.2f] span=%.2f scale=%.2f fitted x=[%.2f,%.2f] z=[%.2f,%.2f]".format(
            b0x, b1x, b0z, b1z, span, scale, fx0, fx1, fz0, fz1))
      }
    }
    // 17. Jubilife seam ground coverage: does the walkable ground reach the cell edges?
    run {
      val jProj = NdsProject(File("../decomp/pokeplatinum"))
      val jMap = jProj.loadMap("MAP_HEADER_JUBILIFE_CITY") ?: return@run
      val jCells = jProj.resolveCells(jMap)
      // Horizontally adjacent pair: same y, x differs by 1.
      val pairs = mutableListOf<Pair<de.lananahwp.openmmo.mapeditor.project.NdsProject.MapCell, de.lananahwp.openmmo.mapeditor.project.NdsProject.MapCell>>()
      for (ca in jCells) for (cb in jCells) {
        if (ca.cellY == cb.cellY && cb.cellX == ca.cellX + 1) pairs += ca to cb
      }
      for ((ca, cb) in pairs.take(3)) {
        val jma = jProj.rom?.narc("fielddata/land_data/land_data.narc")?.getOrNull(ca.fileIndex)?.let { d ->
          de.lananahwp.openmmo.mapeditor.core.NdsMapData.parse(d, hasBgs = false)
        }?.modelBytes
        val jmb = jProj.rom?.narc("fielddata/land_data/land_data.narc")?.getOrNull(cb.fileIndex)?.let { d ->
          de.lananahwp.openmmo.mapeditor.core.NdsMapData.parse(d, hasBgs = false)
        }?.modelBytes
        if (jma == null || jmb == null) continue
        fun groundNearX(model: ByteArray, wantEdge: Float, tol: Float): Boolean {
          val raw = de.lananahwp.openmmo.mapeditor.core.NdsNsbmd.decode(model)
          var minY = Float.MAX_VALUE
          for (t in raw) for (v in floatArrayOf(t.ay, t.by, t.cy)) if (v < minY) minY = v
          return raw.any { t ->
            val low = minOf(t.ay, t.by, t.cy) < minY + 0.05
            val near = (kotlin.math.abs(t.ax - wantEdge) < tol || kotlin.math.abs(t.bx - wantEdge) < tol || kotlin.math.abs(t.cx - wantEdge) < tol)
            low && near
          }
        }
        // Left cell's right edge = +4 native; right cell's left edge = -4 native.
        println("  Jubilife seam between (${ca.cellX},${ca.cellY}) and (${cb.cellX},${cb.cellY}): leftCell groundAt+4=${groundNearX(jma, 4f, 0.15f)} rightCell groundAt-4=${groundNearX(jmb, -4f, 0.15f)}")
      }
    }
    run {
      val ore3 = NdsProject(File("../decomp/pokeplatinum"))
      for (name in listOf("MAP_HEADER_JUBILIFE_CITY", "MAP_HEADER_OREBURGH_CITY")) {
        if (!ore3.hasMap(name)) continue
        val map = ore3.loadMap(name)!!
        val tex = ore3.texturesFor(map)
        val tris = ore3.trianglesFor(map) + ore3.buildingTrianglesFor(map)
        val names = tris.map { it.texture }.filter { it.isNotEmpty() }.distinct()
        val missing = names.filter { it !in tex }
        val white = names.filter { it in tex }.mapNotNull { n ->
          val t = tex[n] ?: return@mapNotNull null
          val px = t.decode() ?: return@mapNotNull null
          val dark = px.filter { it and 0xFFFFFF == 0x000000 }.size
          val whitePx = px.filter { it and 0xFFFFFF == 0xFFFFFF }.size
          if (dark > px.size / 2) n to "BLACK($dark/${px.size})" else if (whitePx > px.size / 2) n to "WHITE($whitePx/${px.size})" else null
        }
        val road = tris.filter { it.texture.contains("road", true) || it.texture.contains("doro", true) }.map { it.texture }.distinct()
        println("[$name] textures=${tex.size} modelTex=${names.size} missing=${missing.size} $missing")
        println("  road textures: $road")
        println("  mostly-black/white textures: $white")
        for ((n, kind) in white.take(8)) {
          val t = tex[n] ?: continue
          val px = t.decode() ?: continue
          val alphaPx = px.filter { (it ushr 24) and 0xFF == 0 }.size
          val pal0 = if (t.palette.isNotEmpty()) "0x%08X".format(t.palette[0]) else "?"
          val hist = HashMap<Int, Int>()
          for (v in px) hist[v and 0xFFFFFF] = (hist[v and 0xFFFFFF] ?: 0) + 1
          val top = hist.entries.sortedByDescending { it.value }.take(3).map { "0x%06X:%d".format(it.key, it.value) }
          println("    $n fmt=${t.format} ${t.width}x${t.height} color0=${t.color0} transparent=${alphaPx} pal0=$pal0 top=$top")
        }
        if (road.isNotEmpty()) {
          for (r in road.take(4)) {
            val t = tex[r]
            val palName = tris.firstOrNull { it.texture == r }?.palette ?: "?"
            val color = tris.firstOrNull { it.texture == r }?.color
            println("    road $r inTex=${t != null} pal=$palName diffuse=0x%08X".format(color ?: 0))
          }
        }
        // Full texture list for the map (terrain + buildings) with decode top colors.
        val sortedNames = names.sorted()
        val texInfo = sortedNames.take(30).map { n ->
          val t = tex[n]
          if (t == null) "$n:MISSING"
          else {
            val px = t.decode()
            val top = if (px != null) {
              val h = HashMap<Int, Int>()
              for (v in px) h[v and 0xFFFFFF] = (h[v and 0xFFFFFF] ?: 0) + 1
              h.entries.sortedByDescending { it.value }.take(2).map { "0x%06X".format(it.key) }
            } else emptyList()
            "$n(f${t.format},${t.width}x${t.height},c0=${t.color0},${top})"
          }
        }
        println("  texnames(${sortedNames.size}): $texInfo")
        if (name.contains("JUBILIFE")) {
          val cells = ore3.resolveCells(map)
          for (c in cells.sortedWith(compareBy({ it.cellY }, { it.cellX }))) {
            val data = ore3.rom?.narc("fielddata/land_data/land_data.narc")?.getOrNull(c.fileIndex)?.let {
              de.lananahwp.openmmo.mapeditor.core.NdsMapData.parse(it, hasBgs = false)
            }
            val model = data?.modelBytes ?: continue
            val trisC = de.lananahwp.openmmo.mapeditor.core.NdsNsbmd.decode(model)
            if (trisC.isEmpty()) continue
            var b0x = Float.MAX_VALUE; var b1x = -Float.MAX_VALUE
            var b0z = Float.MAX_VALUE; var b1z = -Float.MAX_VALUE
            for (t in trisC) {
              for (v in floatArrayOf(t.ax, t.bx, t.cx)) { if (v < b0x) b0x = v; if (v > b1x) b1x = v }
              for (v in floatArrayOf(t.az, t.bz, t.cz)) { if (v < b0z) b0z = v; if (v > b1z) b1z = v }
            }
            println("    Jubilife cell(${c.cellX},${c.cellY}) file=${c.fileIndex} native x=[%.1f,%.1f] z=[%.1f,%.1f] spanX=%.1f spanZ=%.1f scale=%.2f".format(
                b0x, b1x, b0z, b1z, b1x - b0x, b1z - b0z, 32f / maxOf(b1x - b0x, b1z - b0z)))
          }
        }
        // Roof material texture scales (repeat): look at building roof textures.
        for (rname in listOf("c3_s01_a", "c3_s01_b", "c3_s02", "c3_b01a", "c3_h01", "fsloof", "pcwall")) {
          val rt = tris.filter { it.texture == rname }.map { "%.2f/%.2f u0=%.1f".format(it.scaleS, it.scaleT, it.u0) }.distinct().take(3)
          if (rt.isNotEmpty()) println("    roof $rname scaleS/T & u0: $rt")
        }
        // Road triangle materials: their palnames and whether the palette resolves.
        val palMap = ore3.palettesFor(map)
        val roadTris = tris.filter { it.texture.contains("r1", true) || it.texture.contains("road", true) }
        val roadMats = roadTris.map { "${it.texture}|${it.palette}" to it.color }.distinctBy { it.first }
        for ((k, color) in roadMats.take(12)) {
          val parts = k.split("|")
          val texName = parts[0]
          val palName = if (parts.size > 1) parts[1] else ""
          val resolved = palName.isNotEmpty() && palName in palMap
          var top = ""
          if (resolved) {
            val t = tex[texName]
            if (t != null) {
              val px = t.decodeWith(palMap[palName]!!)
              if (px != null) {
                val h = HashMap<Int, Int>()
                for (v in px) h[v and 0xFFFFFF] = (h[v and 0xFFFFFF] ?: 0) + 1
                top = h.entries.sortedByDescending { it.value }.take(3).map { "0x%06X:%d".format(it.key, it.value) }.toString()
              }
            }
          }
          println("    roadTri tex=$texName pal=$palName resolved=$resolved top=$top")
        }
      }
    }
    if (trisOre.isNotEmpty()) {
      val xs = trisOre.flatMap { listOf(it.ax, it.bx, it.cx) }
      val zs = trisOre.flatMap { listOf(it.az, it.bz, it.cz) }
      println("Oreburgh model coords x=[%.1f,%.1f] z=[%.1f,%.1f] tris=%d".format(xs.min(), xs.max(), zs.min(), zs.max(), trisOre.size))
      // Find stretched/sliver triangles: one edge much longer than the others.
      val thin = trisOre.mapNotNull { t ->
        val e1 = Math.sqrt(((t.bx - t.ax) * (t.bx - t.ax) + (t.bz - t.az) * (t.bz - t.az)).toDouble())
        val e2 = Math.sqrt(((t.cx - t.bx) * (t.cx - t.bx) + (t.cz - t.bz) * (t.cz - t.bz)).toDouble())
        val e3 = Math.sqrt(((t.ax - t.cx) * (t.ax - t.cx) + (t.az - t.cz) * (t.az - t.cz)).toDouble())
        val maxE = maxOf(e1, e2, e3)
        val minE = minOf(e1, e2, e3).coerceAtLeast(1e-4)
        val ratio = maxE / minE
        if (ratio > 8.0) t to ratio else null
      }.sortedByDescending { it.second }
      println("  stretched (ratio>8): ${thin.size} of ${trisOre.size}")
      for ((t, ratio) in thin.take(6)) {
        println("    ratio=%.0f tex=%s u=[%.1f,%.1f,%.1f] v=[%.1f,%.1f,%.1f] pts=[(%.1f,%.1f)(%.1f,%.1f)(%.1f,%.1f)]".format(
            ratio, t.texture, t.u0, t.u1, t.u2, t.v0, t.v1, t.v2, t.ax, t.az, t.bx, t.bz, t.cx, t.cz))
      }
      // Decode one cell raw (pre-fit) and check for identical-vertex triangles there.
      val rom22 = ore.rom
      if (rom22 != null) {
        val cellData = de.lananahwp.openmmo.mapeditor.core.NdsNsbmd.decode(
            de.lananahwp.openmmo.mapeditor.core.NdsMapData.parse(
                rom22.narc("fielddata/land_data/land_data.narc")[22], hasBgs = false)!!.modelBytes!!)
        val dup = cellData.filter { t ->
          ((t.ax == t.bx && t.az == t.bz) || (t.bx == t.cx && t.bz == t.cz) || (t.cx == t.ax && t.cz == t.az))
        }
        println("  raw cell22: tris=${cellData.size} identical-vertex tris=${dup.size}")
      }
    }
    val tex = ore.texturesFor(m)
    val pal = ore.palettesFor(m)
    val tris = ore.trianglesFor(m)
    val modelTexnames = tris.map { it.texture }.filter { it.isNotEmpty() }.toSet()
    val modelPalnames = tris.map { it.palette }.filter { it.isNotEmpty() }.toSet()
    println("Oreburgh model texnames: ${modelTexnames.take(15)}")
    println("Oreburgh texOverlap=${modelTexnames.intersect(tex.keys).size}/${modelTexnames.size} palOverlap=${modelPalnames.intersect(pal.keys).size}/${modelPalnames.size}")
    val names = tex.keys.filter { it.contains("grass") || it.contains("dirt") || it.contains("rock") || it.contains("floor") }
    println("Oreburgh candidate textures: ${names.take(12)}")
    for (n in names.take(3)) {
      val t = tex[n] ?: continue
      // Try the texture's own palette and a 'grass'-like palette
      for (pn in listOf(n, "grass", "dirt")) {
        val p = pal[pn]
        if (p == null) continue
        val px = t.decodeWith(p) ?: continue
        val hist = HashMap<Int, Int>()
        for (v in px) hist[v and 0xFFFFFF] = (hist[v and 0xFFFFFF] ?: 0) + 1
        val top = hist.entries.sortedByDescending { it.value }.take(4).map { "0x%06X:%d".format(it.key, it.value) }
        println("  $n fmt=${t.format} pal=$pn: ${top}")
        break
      }
    }
    // Which palettes do the ngrass triangles use, and what colors result?
    val grassTriPal = tris.filter { it.texture == "ngrass" }.map { it.palette }.distinct()
    println("  ngrass triangles use palettes: $grassTriPal")
    // Which named palette matches ngrass's own (index) palette?
    val ng0 = tex["ngrass"]
    if (ng0 != null) {
      val own = ng0.palette
      val match = pal.entries.filter { it.value.contentEquals(own) }.map { it.key }
      println("  ngrass own-palette matches named palette(s): $match")
      println("  pack palette names: ${pal.keys.filter { !it.endsWith("_pl") }.take(30)}")
      println("  ALL pack palette names: ${pal.keys.take(80)}")
      // Try decoding ngrass as 8bpp (format 4) — maybe the format field is wrong.
      val p8 = pal["grass"]
      if (p8 != null) {
        val px8 = IntArray(ng0.width * ng0.height)
        var ok = true
        try {
          for (i in 0 until ng0.width * ng0.height) {
            val idx = ng0.texdata[i].toInt() and 0xFF
            px8[i] = p8.getOrElse(idx) { 0xFFFFFFFF.toInt() }
          }
        } catch (_: Exception) { ok = false }
        if (ok) {
          val hist = HashMap<Int, Int>()
          for (v in px8) hist[v and 0xFFFFFF] = (hist[v and 0xFFFFFF] ?: 0) + 1
          val top = hist.entries.sortedByDescending { it.value }.take(4).map { "0x%06X:%d".format(it.key, it.value) }
          println("    ngrass decoded as 8bpp + 'grass': ${top}")
        }
      }
      // Dump first 16 texels as 4bpp indices.
      val idxs = (0 until 16).map { ((ng0.texdata[it / 2].toInt() ushr ((it % 2) * 4)) and 0xF) }
      println("    ngrass texdata first 16 (4bpp): $idxs")
      for (pn in listOf("nectgrass", "grass", "lgreen", "conttree")) {
        val p = pal[pn] ?: continue
        val px = ng0.decodeWith(p) ?: continue
        val hist = HashMap<Int, Int>()
        for (v in px) hist[v and 0xFFFFFF] = (hist[v and 0xFFFFFF] ?: 0) + 1
        val top = hist.entries.sortedByDescending { it.value }.take(4).map { "0x%06X:%d".format(it.key, it.value) }
        println("    ngrass + '$pn': ${top}")
      }
    }
    println("  model palnames: ${modelPalnames}")
    // For each texture: own-palette name, model palname, and the pack palette at the texture's index.
    val namesDistinct = tris.map { it.texture }.filter { it.isNotEmpty() }.distinct()
    for (n in namesDistinct.take(12)) {
      val t = tex[n] ?: continue
      val ownName = pal.entries.filter { it.value.contentEquals(t.palette) }.map { it.key }.firstOrNull() ?: "?"
      val matPal = tris.firstOrNull { it.texture == n }?.palette ?: "?"
      val px = t.decode()
      val top = if (px != null) {
        val h = HashMap<Int, Int>()
        for (v in px) h[v and 0xFFFFFF] = (h[v and 0xFFFFFF] ?: 0) + 1
        h.entries.sortedByDescending { it.value }.take(2).map { "0x%06X".format(it.key) }
      } else emptyList()
      println("    tex $n ownPal=$ownName matPal=$matPal top=$top")
    }
    val gt = tex["ngrass"]
    for (pn in grassTriPal) {
      val p = pal[pn]
      if (gt != null && p != null) {
        val px = gt.decodeWith(p) ?: continue
        val hist = HashMap<Int, Int>()
        for (v in px) hist[v and 0xFFFFFF] = (hist[v and 0xFFFFFF] ?: 0) + 1
        val top = hist.entries.sortedByDescending { it.value }.take(4).map { "0x%06X:%d".format(it.key, it.value) }
        println("    ngrass + palette '$pn': ${top}")
      }
    }
    // Diagnose road/path textures.
    val ng = tex["ngrass"]
    if (ng != null) {
      val own = ng.decode()
      if (own != null) {
        val hist = HashMap<Int, Int>()
        for (v in own) hist[v and 0xFFFFFF] = (hist[v and 0xFFFFFF] ?: 0) + 1
        val top = hist.entries.sortedByDescending { it.value }.take(4).map { "0x%06X:%d".format(it.key, it.value) }
        println("  ngrass OWN palette decode: ${top}")
      }
    }
    val grassPalColors = pal["grass"]
    if (grassPalColors != null) {
      println("  grass palette first 8: " + grassPalColors.take(8).map { "0x%08X".format(it) })
    }
    for (road in listOf("c4_road", "c4_road_k2")) {
      val rt = tex[road]
      val roadTris = tris.filter { it.texture == road }.map { it.palette }.distinct()
      println("  road '$road' fmt=${rt?.format} triangles palettes=$roadTris")
      for (pn in roadTris.take(2)) {
        val p = pal[pn]
        if (rt != null && p != null) {
          val px = rt.decodeWith(p) ?: continue
          val hist = HashMap<Int, Int>()
          for (v in px) hist[v and 0xFFFFFF] = (hist[v and 0xFFFFFF] ?: 0) + 1
          val top = hist.entries.sortedByDescending { it.value }.take(5).map { "0x%06X:%d".format(it.key, it.value) }
          println("    $road + palette '$pn': ${top}")
        }
      }
    }
  }

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
