package de.lananahwp.openmmo.mapeditor

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
  require(args.size == 3) { "Usage: LumiSceneExport DECOMP_ROOT MAP_NAME OUTPUT_DIRECTORY" }
  val project = NdsProject(File(args[0]).canonicalFile)
  require(project.hasRom) { "A matching read-only NDS ROM is required under the editor project" }
  val map = requireNotNull(project.loadMap(args[1])) { "Unknown NDS map ${args[1]}" }
  val output = File(args[2]).canonicalFile.also(File::mkdirs)
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
          "materials" to arr(materials),
          "triangles" to
              arr(
                  triangles.map {
                    triangleJson(it, materialIds[it.texture to it.palette] ?: -1)
                  }),
      )
  File(output, "scene.json").writeText(JsonWriter.writePretty(root) + "\n", Charsets.UTF_8)
  println(
      "Exported ${map.displayName}: ${triangles.size} triangles, " +
          "${materials.size} materials -> ${output.path}")
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
    )

private fun num(value: Number): Json.JNum = Json.JNum(value.toDouble())
private fun str(value: String): Json.JStr = Json.JStr(value)
private fun arr(items: List<Json>): Json.JArr = Json.JArr(items)
private fun obj(vararg entries: Pair<String, Json>): Json.JObj = Json.JObj(linkedMapOf(*entries))
