package de.lananahwp.openmmo.mapeditor

import de.lananahwp.openmmo.mapeditor.core.NdsMapData
import de.lananahwp.openmmo.mapeditor.json.JsonParser
import de.lananahwp.openmmo.mapeditor.model.NdsWarp
import de.lananahwp.openmmo.mapeditor.project.NdsExporter
import de.lananahwp.openmmo.mapeditor.project.NdsProject
import de.lananahwp.openmmo.mapeditor.ui.NdsPickRay
import de.lananahwp.openmmo.mapeditor.ui.NdsPointerHit
import de.lananahwp.openmmo.mapeditor.ui.NdsScreenPickView
import de.lananahwp.openmmo.mapeditor.ui.NdsSoftwareMapView
import de.lananahwp.openmmo.mapeditor.ui.pickNdsModelGroup
import de.lananahwp.openmmo.mapeditor.ui.pickNdsModelGroupAtScreen
import de.lananahwp.openmmo.mapeditor.ui.projectNdsPoint
import java.io.File
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.nio.file.Files

/** Read/write round-trip for project-local DS maps and external model overrides. */
fun main(args: Array<String>) {
  val temp = Files.createTempDirectory("openmmo-nds-editing-").toFile()
  try {
    val project = NdsProject(temp)
    val map = project.createMap(
        "MAP_TEST_CUSTOM", "Test Custom", 900, 2, 1, matrixX = 200, matrixY = 200)
    map.header.weather = 7
    map.grid.setCollision(63, 31, 0x80)
    map.grid.setPermission(40, 20, 12)
    map.events.warps += NdsWarp(3, 4, "MAP_NOTHING", 0, 1)
    project.save(map)

    val reopened = NdsProject(temp)
    check("MAP_TEST_CUSTOM" in reopened.mapNames)
    val loaded = reopened.loadMap("MAP_TEST_CUSTOM")!!
    check(loaded.isCustom)
    check(loaded.displayName == "Test Custom")
    check(loaded.grid.cols == 64 && loaded.grid.rows == 32)
    check(loaded.grid.collisionAt(63, 31) == 0x80)
    check(loaded.grid.permissionAt(40, 20) == 12)
    check(loaded.header.weather == 7)
    check(loaded.events.warps.single().x == 3)
    check(loaded.matrixCells == listOf(200 to 200, 201 to 200))
    val neighbor = reopened.createMap(
        "MAP_TEST_NEIGHBOR", "Test Neighbor", 901, 1, 1, matrixX = 202, matrixY = 200)
    neighbor.events.warps += NdsWarp(1, 2, loaded.name, 0, 0)
    reopened.save(neighbor)
    val exporter = NdsExporter(reopened)
    val loadedJson = JsonParser.parse(exporter.renderMap(loaded)).asObj()!!
    check(loadedJson.arr("ndsMapCells")!!.items.size == 2)
    check(loadedJson.arr("borderConnections")!!.items.any {
      it.asObj()?.int("value") == neighbor.mapId
    })
    val neighborJson = JsonParser.parse(exporter.renderMap(neighbor)).asObj()!!
    check(neighborJson.arr("warps")!!.items.single().asObj()?.int("targetBankId") == (loaded.mapId and 0xFF))

    val repository = args.firstOrNull()?.let(::File)
    val sourceRoot = repository?.let { File(it, "decomp/pokeheartgold") }
    if (sourceRoot?.isDirectory == true) {
      val source = NdsProject(sourceRoot)
      val hgssCatalog = source.propModels().filterNot { it.imported }
      check(hgssCatalog.size == 339)
      check(hgssCatalog.none { it.label.startsWith("ROM prop") })
      check(hgssCatalog.single { it.key == "rom:136" }.label == "Evergreen tree")
      check(hgssCatalog.single { it.key == "rom:227" }.label == "Streetlight")
      check(hgssCatalog.all { it.category.isNotBlank() })
      println("HGSS named prop catalog: ${hgssCatalog.size} entries")
      source.loadMap("MAP_NEW_BARK")?.let { previewMap ->
        val terrain = source.trianglesFor(previewMap)
        val allTriangles = terrain + source.buildingTrianglesFor(previewMap)
        val xs = allTriangles.flatMap { listOf(it.ax, it.bx, it.cx) }
        val ys = allTriangles.flatMap { listOf(it.ay, it.by, it.cy) }
        val zs = allTriangles.flatMap { listOf(it.az, it.bz, it.cz) }
        val scale = 30f / maxOf(
            (xs.max() - xs.min()).coerceAtLeast(previewMap.grid.cols.toFloat()),
            (zs.max() - zs.min()).coerceAtLeast(previewMap.grid.rows.toFloat()),
        )
        val pickView = NdsScreenPickView(
            800, 600, 45.0, 30.0, 46.0, 16.0, 16.0,
            scale, previewMap.grid.cols / 2f, previewMap.grid.rows / 2f, ys.min(),
        )
        val visibleTree = terrain.asSequence()
            .filter { it.texture.startsWith("tree") }
            .mapNotNull { tri ->
              val point = projectNdsPoint(
                  pickView,
                  (tri.ax + tri.bx + tri.cx) / 3f,
                  (tri.ay + tri.by + tri.cy) / 3f,
                  (tri.az + tri.bz + tri.cz) / 3f,
              ) ?: return@mapNotNull null
              val picked = pickNdsModelGroupAtScreen(
                  allTriangles, point[0].toInt(), point[1].toInt(), pickView)
              tri.takeIf { picked == tri.editGroup }
            }.firstOrNull()
        check(visibleTree != null) { "No visible HGSS tree billboard was screen-pickable" }
        val treeScreen = projectNdsPoint(
            pickView,
            (visibleTree.ax + visibleTree.bx + visibleTree.cx) / 3f,
            (visibleTree.ay + visibleTree.by + visibleTree.cy) / 3f,
            (visibleTree.az + visibleTree.bz + visibleTree.cz) / 3f,
        )!!
        val outsideScreen = projectNdsPoint(
            pickView, -1f, ys.min(), previewMap.grid.rows / 2f)!!
        val pointerEvents = mutableListOf<Pair<NdsPointerHit, Boolean>>()
        val interactionView = NdsSoftwareMapView(
            { _, _ -> }, { _, _, _ -> }, { hit, dragging ->
              pointerEvents += hit to dragging
              true
            }).apply {
          grid = previewMap.grid
          modelTriangles = allTriangles
          setSize(800, 600)
        }
        val press = MouseEvent(
            interactionView, MouseEvent.MOUSE_PRESSED, 1L, 0,
            treeScreen[0].toInt(), treeScreen[1].toInt(), 1, false, MouseEvent.BUTTON1)
        interactionView.mouseListeners.forEach { it.mousePressed(press) }
        val drag = MouseEvent(
            interactionView, MouseEvent.MOUSE_DRAGGED, 2L, InputEvent.BUTTON1_DOWN_MASK,
            outsideScreen[0].toInt(), outsideScreen[1].toInt(), 0, false, MouseEvent.NOBUTTON)
        interactionView.mouseMotionListeners.forEach { it.mouseDragged(drag) }
        check(pointerEvents.first().first.modelGroup == visibleTree.editGroup)
        val outsideHit = pointerEvents.last().first
        check(pointerEvents.last().second && outsideHit.cellX == null)
        check(kotlin.math.abs((outsideHit.groundX ?: 99f) - -1f) < 0.15f)
        println("continuous viewport object dragging beyond grid: OK")
        val selectedTree = source.terrainObject(previewMap, visibleTree.editGroup)
        check(selectedTree != null)
        val beforeTreeRemoval = source.trianglesFor(previewMap).size
        check(source.removeTerrainObject(previewMap, selectedTree, clearCollision = false) != null)
        check(source.trianglesFor(previewMap).size < beforeTreeRemoval)
        check(source.restoreLastTerrainObject(previewMap) != null)
        check(source.trianglesFor(previewMap).size == beforeTreeRemoval)
        println("HGSS tree billboard screen-pick/remove/restore: OK")
        val modelKey = previewMap.props.firstOrNull()?.modelKey ?: "rom:1"
        val preview = source.propModelPreview(modelKey, previewMap)
        check(preview.triangles.isNotEmpty())
        val textureNames = preview.triangles.map { it.texture }.filter { it.isNotEmpty() }.toSet()
        check(textureNames.isEmpty() || textureNames.any { it in preview.textures })
        println("prop catalog preview: ${preview.triangles.size} triangles, ${preview.textures.size} textures")
      }
      source.loadMap("MAP_ROUTE_1")?.let { route1 ->
        route1.props += source.createProp("rom:227", route1.grid.cols / 2f, route1.grid.rows / 2f)
        val prop227 = source.propModelPreview("rom:227", route1).triangles
        val textures = source.texturesFor(route1)
        val palettes = source.palettesFor(route1)
        val missingTextures = prop227.map { it.texture }.filter { it.isNotEmpty() && it !in textures }.toSet()
        val missingPalettes = prop227.map { it.palette }.filter { it.isNotEmpty() && it !in palettes }.toSet()
        check(missingTextures.isEmpty()) { "Route 1 prop #227 textures missing: $missingTextures" }
        check(missingPalettes.isEmpty()) { "Route 1 prop #227 palettes missing: $missingPalettes" }
        println("cross-area ROM prop textures: OK")
      }
      val model = source.rom?.narc("a/0/6/5")
          ?.asSequence()
          ?.mapNotNull { NdsMapData.parse(it, hasBgs = true)?.modelBytes }
          ?.firstOrNull()
      if (model != null) {
        val input = File(temp, "outside-model.nsbmd")
        input.writeBytes(model)
        val imported = reopened.importModel(loaded, input)
        check(imported.triangles > 0)
        check(reopened.trianglesFor(loaded).isNotEmpty())
        println("external NSBMD import: ${imported.triangles} triangles")

        val terrainObject = sequence {
          for (z in 0 until loaded.grid.rows) for (x in 0 until loaded.grid.cols) {
            reopened.terrainObjectAt(loaded, x + 0.5f, z + 0.5f)?.let { yield(it) }
          }
        }.firstOrNull()
        check(terrainObject != null) { "Imported terrain exposed no removable connected objects" }
        check(reopened.terrainObject(loaded, terrainObject.groupId)?.groupId == terrainObject.groupId)
        val originalMinX = terrainObject.minX
        check(reopened.moveTerrainObject(loaded, terrainObject.groupId, 1.25f, -0.75f))
        val movedTerrainObject = reopened.terrainObject(loaded, terrainObject.groupId)!!
        check(kotlin.math.abs(movedTerrainObject.minX - originalMinX - 1.25f) < 0.001f)
        val collisionCell = movedTerrainObject.collisionCells.first()
        loaded.grid.setCollision(collisionCell.first, collisionCell.second, 0x80)
        val beforeRemoval = reopened.trianglesFor(loaded).size
        val removal = reopened.removeTerrainObject(loaded, movedTerrainObject, clearCollision = true)!!
        check(reopened.trianglesFor(loaded).size < beforeRemoval)
        check(loaded.grid.collisionAt(collisionCell.first, collisionCell.second) == 0)
        reopened.saveGrid(loaded)
        reopened.saveProps(loaded)
        val terrainReloadedProject = NdsProject(temp)
        val terrainReloaded = terrainReloadedProject.loadMap(loaded.name)!!
        check(terrainReloaded.terrainRemovals.single().groupId == removal.groupId)
        check(terrainReloadedProject.terrainObjectOffset(terrainReloaded, removal.groupId) == (1.25f to -0.75f))
        check(terrainReloadedProject.trianglesFor(terrainReloaded).size < beforeRemoval)
        check(terrainReloadedProject.restoreLastTerrainObject(terrainReloaded) != null)
        check(terrainReloadedProject.trianglesFor(terrainReloaded).size == beforeRemoval)
        check(terrainReloaded.grid.collisionAt(collisionCell.first, collisionCell.second) == 0x80)
        println("terrain object remove/collision/persistence/restore: OK")

        val catalogModel = reopened.importPropModel("Test imported prop", input)
        val prop = reopened.createProp(catalogModel.key, 8.5f, 9.5f)
        prop.rotationY = 45f
        prop.scaleY *= 2f
        loaded.props += prop
        check(reopened.buildingTrianglesFor(loaded).isNotEmpty())
        for (z in 0 until loaded.grid.rows) for (x in 0 until loaded.grid.cols) {
          loaded.grid.setCollision(x, z, 0x80)
        }
        val propRemoval = reopened.removePropObject(loaded, prop.id, clearCollision = true)!!
        check(loaded.props.isEmpty())
        check(reopened.buildingTrianglesFor(loaded).isEmpty())
        check(propRemoval.removedProp?.rotationY == 45f)
        check(propRemoval.clearedCollision.isNotEmpty())
        val propCollisionCell = propRemoval.clearedCollision.first()
        check(loaded.grid.collisionAt(propCollisionCell.x, propCollisionCell.z) == 0)
        reopened.saveGrid(loaded)
        reopened.saveProps(loaded)
        val propsReloaded = NdsProject(temp).loadMap(loaded.name)!!
        check(propsReloaded.props.isEmpty())
        check(propsReloaded.terrainRemovals.last().removedProp?.x == 8.5f)
        check(NdsProject(temp).buildingTrianglesFor(propsReloaded).isEmpty())
        val restoredProp = NdsProject(temp).restoreLastTerrainObject(propsReloaded)?.removedProp
        check(restoredProp?.rotationY == 45f)
        check(propsReloaded.props.single().id == prop.id)
        check(propsReloaded.grid.collisionAt(propCollisionCell.x, propCollisionCell.z) == 0x80)
        println("prop place/remove/collision/persistence/restore: OK")
      }
    }
    val platinumRoot = repository?.let { File(it, "decomp/pokeplatinum") }
    if (platinumRoot?.isDirectory == true) {
      val platinum = NdsProject(platinumRoot)
      val platinumCatalog = platinum.propModels().filterNot { it.imported }
      check(platinumCatalog.size == 589)
      check(platinumCatalog.none { it.label.startsWith("ROM prop") })
      check(platinumCatalog.single { it.key == "rom:1" }.label == "Tree")
      check(platinumCatalog.single { it.key == "rom:337" }.category == "Underground trap")
      check(platinumCatalog.single { it.key == "rom:589" }.label == "Turnback Cave portal")
      println("Platinum named prop catalog: ${platinumCatalog.size} entries")
      platinum.loadMap("MAP_HEADER_OREBURGH_CITY")?.let { oreburgh ->
        val propTriangles = platinum.buildingTrianglesFor(oreburgh)
            .filter { it.editGroup.startsWith("prop:") }
        check(propTriangles.isNotEmpty() && oreburgh.props.isNotEmpty())
        val byGroup = propTriangles.groupBy { it.editGroup }
        val arenaGroup = byGroup.maxByOrNull { (_, tris) ->
          val xs = tris.flatMap { listOf(it.ax, it.bx, it.cx) }
          val zs = tris.flatMap { listOf(it.az, it.bz, it.cz) }
          (xs.max() - xs.min()) * (zs.max() - zs.min())
        }!!
        val arenaId = arenaGroup.key.removePrefix("prop:")
        val arena = oreburgh.props.first { it.id == arenaId }
        val farTriangle = arenaGroup.value.maxBy { tri ->
          val x = (tri.ax + tri.bx + tri.cx) / 3f
          val z = (tri.az + tri.bz + tri.cz) / 3f
          (x - arena.x) * (x - arena.x) + (z - arena.z) * (z - arena.z)
        }
        val clickX = (farTriangle.ax + farTriangle.bx + farTriangle.cx) / 3f
        val clickY = (farTriangle.ay + farTriangle.by + farTriangle.cy) / 3f
        val clickZ = (farTriangle.az + farTriangle.bz + farTriangle.cz) / 3f
        val edge1 = doubleArrayOf(
            (farTriangle.bx - farTriangle.ax).toDouble(),
            (farTriangle.by - farTriangle.ay).toDouble(),
            (farTriangle.bz - farTriangle.az).toDouble(),
        )
        val edge2 = doubleArrayOf(
            (farTriangle.cx - farTriangle.ax).toDouble(),
            (farTriangle.cy - farTriangle.ay).toDouble(),
            (farTriangle.cz - farTriangle.az).toDouble(),
        )
        val normal = doubleArrayOf(
            edge1[1] * edge2[2] - edge1[2] * edge2[1],
            edge1[2] * edge2[0] - edge1[0] * edge2[2],
            edge1[0] * edge2[1] - edge1[1] * edge2[0],
        )
        val normalLength = kotlin.math.sqrt(normal.sumOf { it * it })
        check(normalLength > 1e-8)
        for (i in normal.indices) normal[i] /= normalLength
        val ray = NdsPickRay(
            doubleArrayOf(clickX + normal[0], clickY + normal[1], clickZ + normal[2]),
            doubleArrayOf(-normal[0], -normal[1], -normal[2]),
        )
        check(pickNdsModelGroup(platinum.trianglesFor(oreburgh) + propTriangles, ray, "") == arenaGroup.key)
        check(platinum.propAt(oreburgh, clickX, clickZ, tolerance = 0f)?.id == arenaId)
        val distanceFromOrigin = kotlin.math.sqrt(
            (clickX - arena.x) * (clickX - arena.x) + (clickZ - arena.z) * (clickZ - arena.z))
        check(distanceFromOrigin > 2f) {
          "Oreburgh's largest prop did not exercise picking beyond the old two-tile radius"
        }
        println("Oreburgh large-prop mesh/footprint picking: OK (grab distance %.1f tiles)"
            .format(distanceFromOrigin))
      }
    }
    println("custom DS map create/save/reload: OK")
  } finally {
    temp.deleteRecursively()
  }
}
