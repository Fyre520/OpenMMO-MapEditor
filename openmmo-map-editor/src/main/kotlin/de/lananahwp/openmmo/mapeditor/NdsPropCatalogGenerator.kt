package de.lananahwp.openmmo.mapeditor

import de.lananahwp.openmmo.mapeditor.model.NdsGrid
import de.lananahwp.openmmo.mapeditor.project.NdsProject
import de.lananahwp.openmmo.mapeditor.ui.NdsSoftwareMapView
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Development utility that renders every ROM prop into labeled contact sheets for cataloging. */
fun main(args: Array<String>) {
  System.setProperty("java.awt.headless", "true")
  val repository = File(args.getOrNull(0) ?: ".").canonicalFile
  val output = File(args.getOrNull(1) ?: "build/prop-catalog").canonicalFile
  output.mkdirs()
  val targets = listOf(
      Triple("hgss", File(repository, "decomp/pokeheartgold"), "MAP_GOLDENROD"),
      Triple("platinum", File(repository, "decomp/pokeplatinum"), "MAP_HEADER_JUBILIFE_CITY"),
  )
  for ((family, root, contextName) in targets) {
    if (!root.isDirectory) continue
    renderCatalog(family, NdsProject(root), contextName, File(output, family))
  }
  println("Prop catalog sheets: ${output.absolutePath}")
}

private fun renderCatalog(family: String, project: NdsProject, contextName: String, output: File) {
  output.mkdirs()
  val context = project.loadMap(contextName)
      ?: project.mapNames.asSequence().mapNotNull(project::loadMap).firstOrNull()
      ?: error("No context map for $family")
  val models = project.propModels().filter { it.key.startsWith("rom:") }
  val usage = LinkedHashMap<String, MutableList<String>>()
  for (name in project.mapNames) {
    val map = try { project.loadMap(name) } catch (_: Throwable) { null } ?: continue
    for (prop in map.props) usage.getOrPut(prop.modelKey) { mutableListOf() }.add(name)
  }

  val metadata = StringBuilder("id\ttriangles\tspanX\tspanY\tspanZ\ttextures\tpalettes\tusage\n")
  val tileWidth = 320
  val tileHeight = 210
  val columns = 4
  val rows = 5
  val perSheet = columns * rows
  for ((sheetIndex, batch) in models.chunked(perSheet).withIndex()) {
    val sheet = BufferedImage(tileWidth * columns, tileHeight * rows, BufferedImage.TYPE_INT_ARGB)
    val graphics = sheet.createGraphics()
    graphics.color = Color(24, 27, 33)
    graphics.fillRect(0, 0, sheet.width, sheet.height)
    graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 15)
    for ((slot, model) in batch.withIndex()) {
      val preview = project.propModelPreview(model.key, context)
      val id = model.key.removePrefix("rom:").toInt()
      val x = (slot % columns) * tileWidth
      val y = (slot / columns) * tileHeight
      val first = renderPreview(preview, 150, 155, 20.0)
      val second = renderPreview(preview, 150, 155, 65.0)
      graphics.drawImage(first, x + 5, y + 5, null)
      graphics.drawImage(second, x + 160, y + 5, null)
      graphics.color = Color(242, 244, 248)
      graphics.drawString("#$id", x + 8, y + 177)
      graphics.font = Font(Font.MONOSPACED, Font.PLAIN, 10)
      val textureText = preview.triangles.map { it.texture }.filter { it.isNotEmpty() }.distinct()
          .joinToString(", ").ifEmpty { "(untextured)" }
      graphics.drawString(textureText.take(48), x + 48, y + 177)
      val used = usage[model.key].orEmpty().distinct()
      graphics.drawString(used.take(2).joinToString(", ").take(48), x + 8, y + 195)
      graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 15)

      val tris = preview.triangles
      val xs = tris.flatMap { listOf(it.ax, it.bx, it.cx) }
      val ys = tris.flatMap { listOf(it.ay, it.by, it.cy) }
      val zs = tris.flatMap { listOf(it.az, it.bz, it.cz) }
      fun span(values: List<Float>) = if (values.isEmpty()) 0f else values.max() - values.min()
      metadata.append(id).append('\t').append(tris.size).append('\t')
          .append("%.3f".format(span(xs))).append('\t')
          .append("%.3f".format(span(ys))).append('\t')
          .append("%.3f".format(span(zs))).append('\t')
          .append(textureText).append('\t')
          .append(preview.triangles.map { it.palette }.filter { it.isNotEmpty() }.distinct().joinToString(", "))
          .append('\t').append(used.take(8).joinToString(", ")).append('\n')
    }
    graphics.dispose()
    ImageIO.write(sheet, "png", File(output, "sheet-%02d.png".format(sheetIndex + 1)))
    println("$family sheet ${sheetIndex + 1}/${(models.size + perSheet - 1) / perSheet}")
  }
  File(output, "metadata.tsv").writeText(metadata.toString())
}

private fun renderPreview(
    preview: NdsProject.PropModelPreview,
    width: Int,
    height: Int,
    yaw: Double,
): BufferedImage {
  val view = NdsSoftwareMapView({ _, _, _ -> }, { _, _, _ -> }).apply {
    grid = NdsGrid(0, 0)
    showGrid = false
    showCollision = false
    modelTriangles = preview.triangles
    modelTextures = preview.textures
    modelPalettes = preview.palettes
    this.yaw = yaw
    pitch = 22.0
    distance = 43.0
    setSize(width, height)
  }
  return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { image ->
    val graphics = image.createGraphics()
    view.paint(graphics)
    graphics.dispose()
  }
}
