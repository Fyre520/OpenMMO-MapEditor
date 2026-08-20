package de.lananahwp.openmmo.mapeditor

import de.lananahwp.openmmo.mapeditor.core.NdsNsbtx
import de.lananahwp.openmmo.mapeditor.core.NdsTexture
import de.lananahwp.openmmo.mapeditor.project.NdsProject
import java.io.File

private fun u16(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

private fun sameTexture(a: NdsTexture, b: NdsTexture): Boolean =
    a.format == b.format &&
        a.width == b.width &&
        a.height == b.height &&
        a.color0 == b.color0 &&
        a.texdata.contentEquals(b.texdata) &&
        (a.spdata?.contentEquals(b.spdata ?: ByteArray(0)) ?: (b.spdata == null))

fun main(args: Array<String>) {
  val repository = File(args.firstOrNull() ?: "..").canonicalFile
  val project = NdsProject(File(repository, "decomp/pokeheartgold"))
  val rom = project.rom ?: error("No matching HGSS ROM")
  val areaFiles = rom.narc("a/0/4/2")
  val mapTextureFiles = rom.narc("a/0/4/4")
  val buildingTextureFiles = rom.narc("a/0/7/0")
  println(
      "HGSS packs: area=${areaFiles.size} mapTextures=${mapTextureFiles.size} " +
          "buildingTextures=${buildingTextureFiles.size}")

  for (name in
      listOf(
          "MAP_NEW_BARK",
          "MAP_CHERRYGROVE",
          "MAP_GOLDENROD",
          "MAP_VIRIDIAN",
          "MAP_CERULEAN",
      )) {
    val map = project.loadMap(name) ?: continue
    val areaIndex = map.header.areaDataBank
    val area = areaFiles.getOrNull(areaIndex) ?: continue
    val buildingPackIndex = if (area.size >= 2) u16(area, 0) else -1
    val mapPackIndex = if (area.size >= 4) u16(area, 2) else -1
    val mapPack = mapTextureFiles.getOrNull(mapPackIndex)?.let(NdsNsbtx::parsePack)
    val buildingPack =
        buildingTextureFiles.getOrNull(buildingPackIndex)?.let(NdsNsbtx::parsePack)
    val terrain = project.trianglesFor(map)
    val buildings = project.buildingTrianglesFor(map)
    val terrainTextures = terrain.map { it.texture }.filter { it.isNotEmpty() }.toSet()
    val buildingTextures = buildings.map { it.texture }.filter { it.isNotEmpty() }.toSet()
    val terrainPalettes = terrain.map { it.palette }.filter { it.isNotEmpty() }.toSet()
    val buildingPalettes = buildings.map { it.palette }.filter { it.isNotEmpty() }.toSet()
    val resolvedTextures = project.texturesFor(map)
    val resolvedPalettes = project.palettesFor(map)
    val mapTextureNames = mapPack?.textures?.map { it.name }?.toSet().orEmpty()
    val buildingTextureNames = buildingPack?.textures?.map { it.name }?.toSet().orEmpty()
    val mapPaletteNames = mapPack?.palettes?.keys.orEmpty()
    val buildingPaletteNames = buildingPack?.palettes?.keys.orEmpty()
    val mapTexturesByName = mapPack?.textures?.associateBy { it.name }.orEmpty()
    val buildingTexturesByName = buildingPack?.textures?.associateBy { it.name }.orEmpty()
    val terrainWrong =
        terrainTextures.filter { textureName ->
          val expected = mapTexturesByName[textureName] ?: return@filter false
          val actual = resolvedTextures[textureName] ?: return@filter false
          !sameTexture(expected, actual)
        }
    val buildingWrong =
        buildingTextures.filter { textureName ->
          val expected = buildingTexturesByName[textureName] ?: return@filter false
          val actual = resolvedTextures[textureName] ?: return@filter false
          !sameTexture(expected, actual)
        }
    val differingTextureCollisions =
        mapTextureNames.intersect(buildingTextureNames).filter { textureName ->
          !sameTexture(mapTexturesByName.getValue(textureName), buildingTexturesByName.getValue(textureName))
        }
    val differingPaletteCollisions =
        mapPaletteNames.intersect(buildingPaletteNames).filter { paletteName ->
          !mapPack!!.palettes.getValue(paletteName).contentEquals(
              buildingPack!!.palettes.getValue(paletteName))
        }

    println("\n$name areaDataBank=$areaIndex areaDataArchiveID=${map.header.areaDataArchiveID}")
    println(
        "  area=${area.take(16).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }} " +
            "buildingPack=$buildingPackIndex mapPack=$mapPackIndex")
    println(
        "  terrain: tris=${terrain.size} tex=${terrainTextures.size} " +
            "authoritative=${terrainTextures.intersect(mapTextureNames).size} " +
            "resolved=${terrainTextures.intersect(resolvedTextures.keys).size} " +
            "missing=${terrainTextures - resolvedTextures.keys}")
    println(
        "  buildings: tris=${buildings.size} tex=${buildingTextures.size} " +
            "authoritative=${buildingTextures.intersect(buildingTextureNames).size} " +
            "resolved=${buildingTextures.intersect(resolvedTextures.keys).size} " +
            "missing=${buildingTextures - resolvedTextures.keys}")
    println(
        "  palettes: terrain=${terrainPalettes.size}/${terrainPalettes.intersect(resolvedPalettes.keys).size} " +
            "buildings=${buildingPalettes.size}/${buildingPalettes.intersect(resolvedPalettes.keys).size}")
    println(
        "  pack collisions: textures=${mapTextureNames.intersect(buildingTextureNames)} " +
            "palettes=${mapPaletteNames.intersect(buildingPaletteNames)}")
    println(
        "  wrong resolved: terrain=$terrainWrong buildings=$buildingWrong; " +
            "differing collisions: textures=$differingTextureCollisions " +
            "palettes=$differingPaletteCollisions")
    println(
        "  scales terrain=${terrain.map { it.scaleS to it.scaleT }.distinct().take(12)} " +
            "buildings=${buildings.map { it.scaleS to it.scaleT }.distinct().take(12)}")
    println(
        "  wrap terrain=${terrain.groupingBy { listOf(it.repeatS, it.repeatT, it.flipS, it.flipT) }.eachCount()} " +
            "buildings=${buildings.groupingBy { listOf(it.repeatS, it.repeatT, it.flipS, it.flipT) }.eachCount()}")
  }
}
