package de.lananahwp.openmmo.mapeditor

import de.lananahwp.openmmo.mapeditor.core.Gen4Decomp
import de.lananahwp.openmmo.mapeditor.core.NdsTexture
import de.lananahwp.openmmo.mapeditor.core.NdsTri
import de.lananahwp.openmmo.mapeditor.json.Json
import de.lananahwp.openmmo.mapeditor.json.JsonWriter
import de.lananahwp.openmmo.mapeditor.project.NdsProject
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Resolves one map against the user's local ROM into an uncommitted renderer-neutral cache. */
fun main(args: Array<String>) {
  require(args.size in 3..4) {
    "Usage: LumiSceneExport DECOMP_ROOT MAP_NAME OUTPUT_DIRECTORY [READ_ONLY_ROM]"
  }
  val project =
      NdsProject(
          File(args[0]).canonicalFile,
          args.getOrNull(3)?.let(::File),
      )
  exportLumiScene(project, args[1], File(args[2]))
}

/** Shares one read-only ROM project across single-map and batch derived-cache exports. */
internal fun exportLumiScene(project: NdsProject, mapName: String, outputDirectory: File): File {
  return exportLumiScene(project, mapName, outputDirectory, lumiWorldMatrixCells(project))
}

internal fun exportLumiScene(
    project: NdsProject,
    mapName: String,
    outputDirectory: File,
    worldMatrixCells: List<Gen4Decomp.MatrixCell>,
): File {
  require(project.hasRom) { "A matching read-only NDS ROM is required under the editor project" }
  val map = requireNotNull(project.loadMap(mapName)) { "Unknown NDS map $mapName" }
  val output = outputDirectory.canonicalFile.also(File::mkdirs)
  val texturesDir = File(output, "textures").also(File::mkdirs)
  val triangles =
      project.trianglesFor(map) +
          project.builtInTileTrianglesFor(map) +
          project.customTileTrianglesFor(map) +
          project.buildingTrianglesFor(map)
  val textures = project.texturesFor(map) + project.customTileTexturesFor(map)
  val palettes = project.palettesFor(map) + project.customTilePalettesFor(map)
  val keys =
      triangles.asSequence().filter { it.texture.isNotEmpty() && it.texture in textures }
          .map { it.texture to it.palette }.distinct().toList()
  val materialIds = keys.withIndex().associate { (index, key) -> key to index }
  val materials = keys.mapIndexedNotNull { index, key ->
    materialJson(index, key, textures, palettes, texturesDir)
  }
  val eventOrigin =
      if (map.isCustom) {
        0 to 0
      } else {
        project.resolveCells(map).let { cells ->
          if (cells.isEmpty()) 0 to 0
          else cells.minOf { it.cellX } * 32 to cells.minOf { it.cellY } * 32
        }
      }
  val connections =
      if (map.isCustom || map.header.matrixId != 0) emptyList()
      else deriveLumiConnections(map.mapId, worldMatrixCells)
  val root =
      obj(
          "schemaVersion" to num(1),
          "derivedLocalAsset" to Json.JBool(true),
          "family" to str(project.family.regionName),
          "regionId" to num(project.family.romType),
          "bankId" to num(map.mapId and 0xFF),
          "mapId" to num(map.mapId ushr 8),
          "sourceMap" to str(map.name),
          "displayName" to str(map.displayName),
          "cols" to num(map.grid.cols),
          "rows" to num(map.grid.rows),
          "cameraType" to num(map.header.cameraType),
          "collisions" to arr(gridValues(map.grid.cols, map.grid.rows, map.grid::collisionAt)),
          "permissions" to arr(gridValues(map.grid.cols, map.grid.rows, map.grid::permissionAt)),
          "warps" to
              arr(
                  map.events.warps.mapIndexedNotNull { sourceIndex, warp ->
                    val destination = project.loadMap(warp.header) ?: return@mapIndexedNotNull null
                    val localX = warp.x - eventOrigin.first
                    val localZ = warp.z - eventOrigin.second
                    require(localX in 0 until map.grid.cols && localZ in 0 until map.grid.rows) {
                      "Warp " + sourceIndex + " on " + map.name +
                          " resolves outside its local grid: source=(" + warp.x + "," + warp.z +
                          "), origin=" + eventOrigin + ", local=(" + localX + "," + localZ +
                          "), grid=" + map.grid.cols + "x" + map.grid.rows
                    }
                    obj(
                        "sourceIndex" to num(sourceIndex),
                        "x" to num(localX),
                        "z" to num(localZ),
                        "destinationRegionId" to num(project.family.romType),
                        "destinationBankId" to num(destination.mapId and 0xFF),
                        "destinationMapId" to num(destination.mapId ushr 8),
                        "destinationWarp" to num(warp.anchor),
                    )
                  }),
          "connections" to
              arr(
                  connections.map { connection ->
                    obj(
                        "edge" to str(connection.edge),
                        "sourceStart" to num(connection.sourceStart),
                        "length" to num(connection.length),
                        "destinationRegionId" to num(project.family.romType),
                        "destinationBankId" to num(connection.destinationMapId and 0xFF),
                        "destinationMapId" to num(connection.destinationMapId ushr 8),
                        "destinationStart" to num(connection.destinationStart),
                    )
                  }),
          "materials" to arr(materials),
          "triangles" to
              arr(
                  triangles.map {
                    triangleJson(it, materialIds[it.texture to it.palette] ?: -1)
                  }),
      )
  val sceneFile = File(output, "scene.json")
  sceneFile.writeText(JsonWriter.writePretty(root) + "\n", Charsets.UTF_8)
  println(
      "Exported ${map.displayName}: ${triangles.size} triangles, " +
          "${materials.size} materials -> ${output.path}")
  return sceneFile
}

/** Reads the ROM's header-bearing main matrix instead of relying on decomp directory conventions. */
internal fun lumiWorldMatrixCells(project: NdsProject): List<Gen4Decomp.MatrixCell> =
    project.mapNames
        .asSequence()
        .mapNotNull(project::loadMap)
        .filter { !it.isCustom && it.header.matrixId == 0 }
        .flatMap { map ->
          project.resolveCells(map).asSequence().map { cell ->
            Gen4Decomp.MatrixCell(cell.cellX, cell.cellY, map.mapId)
          }
        }
        .distinctBy { it.x to it.y }
        .toList()

internal data class LumiConnection(
    val edge: String,
    val sourceStart: Int,
    val length: Int,
    val destinationMapId: Int,
    val destinationStart: Int,
)

/**
 * Converts the ROM's main-world matrix adjacency into authoritative boundary segments. Each matrix
 * cell is 32 tiles. Door/cave transitions remain ROM event warps; this only describes walking over
 * the outer edge of an exterior map footprint.
 */
internal fun deriveLumiConnections(
    sourceMapId: Int,
    cells: List<Gen4Decomp.MatrixCell>,
): List<LumiConnection> {
  val source = cells.filter { it.mapId == sourceMapId }
  if (source.isEmpty()) return emptyList()
  val byPosition = cells.associateBy { it.x to it.y }
  val cellsByMap = cells.groupBy { it.mapId }
  val minX = source.minOf { it.x }
  val maxX = source.maxOf { it.x }
  val minY = source.minOf { it.y }
  val maxY = source.maxOf { it.y }
  val raw = mutableListOf<LumiConnection>()

  fun add(sourceCell: Gen4Decomp.MatrixCell, edge: String, dx: Int, dy: Int) {
    val destination = byPosition[sourceCell.x + dx to sourceCell.y + dy] ?: return
    if (destination.mapId == sourceMapId || destination.mapId == 0 || destination.mapId == 0xFFFF) {
      return
    }
    val destinationCells = cellsByMap[destination.mapId].orEmpty()
    if (destinationCells.isEmpty()) return
    val horizontal = edge == "UP" || edge == "DOWN"
    val sourceStart =
        if (horizontal) (sourceCell.x - minX) * 32 else (sourceCell.y - minY) * 32
    val destinationStart =
        if (horizontal) {
          (destination.x - destinationCells.minOf { it.x }) * 32
        } else {
          (destination.y - destinationCells.minOf { it.y }) * 32
        }
    raw += LumiConnection(edge, sourceStart, 32, destination.mapId, destinationStart)
  }

  source.filter { it.y == minY }.forEach { add(it, "UP", 0, -1) }
  source.filter { it.y == maxY }.forEach { add(it, "DOWN", 0, 1) }
  source.filter { it.x == minX }.forEach { add(it, "LEFT", -1, 0) }
  source.filter { it.x == maxX }.forEach { add(it, "RIGHT", 1, 0) }

  return raw.sortedWith(compareBy(LumiConnection::edge, LumiConnection::sourceStart)).fold(
      mutableListOf()) { merged, next ->
        val previous = merged.lastOrNull()
        if (previous != null &&
            previous.edge == next.edge &&
            previous.destinationMapId == next.destinationMapId &&
            previous.sourceStart + previous.length == next.sourceStart &&
            previous.destinationStart + previous.length == next.destinationStart) {
          merged[merged.lastIndex] = previous.copy(length = previous.length + next.length)
        } else {
          merged += next
        }
        merged
      }
}

private fun gridValues(cols: Int, rows: Int, value: (Int, Int) -> Int): List<Json> =
    (0 until cols * rows).map { num(value(it % cols, it / cols)) }

private fun materialJson(
    id: Int,
    key: Pair<String, String>,
    textures: Map<String, NdsTexture>,
    palettes: Map<String, IntArray>,
    directory: File,
): Json.JObj? {
  val texture = textures[key.first] ?: return null
  val pixels = palettes[key.second]?.let(texture::decodeWith) ?: texture.decode() ?: return null
  val fileName = "texture-%03d.png".format(id)
  val image = BufferedImage(texture.width, texture.height, BufferedImage.TYPE_INT_ARGB)
  image.setRGB(0, 0, texture.width, texture.height, pixels, 0, texture.width)
  check(ImageIO.write(image, "png", File(directory, fileName)))
  return obj(
      "id" to num(id),
      "texture" to str(key.first),
      "palette" to str(key.second),
      "file" to str("textures/$fileName"),
      "width" to num(texture.width),
      "height" to num(texture.height),
  )
}

private fun triangleJson(triangle: NdsTri, materialId: Int): Json.JObj =
    obj(
        "positions" to
            arr(
                listOf(
                    num(triangle.ax), num(triangle.ay), num(triangle.az),
                    num(triangle.bx), num(triangle.by), num(triangle.bz),
                    num(triangle.cx), num(triangle.cy), num(triangle.cz),
                )),
        "uv" to
            arr(
                listOf(
                    num(triangle.u0), num(triangle.v0),
                    num(triangle.u1), num(triangle.v1),
                    num(triangle.u2), num(triangle.v2),
                )),
        "color" to num(triangle.color.toLong()),
        "material" to num(materialId),
        "scaleS" to num(triangle.scaleS),
        "scaleT" to num(triangle.scaleT),
        "repeatS" to Json.JBool(triangle.repeatS),
        "repeatT" to Json.JBool(triangle.repeatT),
        "flipS" to Json.JBool(triangle.flipS),
        "flipT" to Json.JBool(triangle.flipT),
        "cullMode" to num(triangle.cullMode),
    )

private fun num(value: Number): Json.JNum = Json.JNum(value.toDouble())
private fun str(value: String): Json.JStr = Json.JStr(value)
private fun arr(items: List<Json>): Json.JArr = Json.JArr(items)
private fun obj(vararg entries: Pair<String, Json>): Json.JObj = Json.JObj(linkedMapOf(*entries))
