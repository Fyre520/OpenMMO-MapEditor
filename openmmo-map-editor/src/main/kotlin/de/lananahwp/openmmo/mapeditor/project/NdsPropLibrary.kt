package de.lananahwp.openmmo.mapeditor.project

import de.lananahwp.openmmo.mapeditor.core.NdsFamily
import de.lananahwp.openmmo.mapeditor.core.NdsMeshSnapshot
import de.lananahwp.openmmo.mapeditor.json.Json
import de.lananahwp.openmmo.mapeditor.json.JsonParser
import de.lananahwp.openmmo.mapeditor.json.JsonWriter
import java.io.File

/** Disk-backed catalog of another Gen 4 family's props; no ROM remains resident after building. */
class NdsPropLibrary private constructor(
    val family: NdsFamily,
    private val cacheDir: File,
    val models: List<NdsProject.PropModelInfo>,
) {
  private val previews = object : LinkedHashMap<String, NdsProject.PropModelPreview>(16, 0.75f, true) {
    override fun removeEldestEntry(
        eldest: MutableMap.MutableEntry<String, NdsProject.PropModelPreview>?,
    ): Boolean = size > PREVIEW_CACHE_SIZE
  }

  @Synchronized
  fun preview(info: NdsProject.PropModelInfo): NdsProject.PropModelPreview {
    previews[info.catalogId]?.let { return it }
    val mesh = snapshot(info)
    val result = if (mesh == null) {
      NdsProject.PropModelPreview(emptyList(), emptyMap(), emptyMap())
    } else {
      NdsProject.PropModelPreview(mesh.triangles, mesh.textures, mesh.palettes)
    }
    previews[info.catalogId] = result
    return result
  }

  fun snapshot(info: NdsProject.PropModelInfo): NdsMeshSnapshot? =
      NdsMeshSnapshot.read(modelFile(cacheDir, info.sourceModelKey))

  companion object {
    private const val CACHE_VERSION = 1
    private const val PREVIEW_CACHE_SIZE = 12

    fun cacheRoot(): File = File(
        System.getProperty("user.home"), ".openmmo-map-editor/nds-prop-cache")

    /** Opens an already-built family cache without touching either ROM. */
    fun loadCached(family: NdsFamily, root: File = cacheRoot()): NdsPropLibrary? {
      val dir = File(root, family.name.lowercase())
      val file = File(dir, "catalog.json")
      if (!file.isFile) return null
      return try {
        val root = JsonParser.parse(file.readText()).asObj() ?: return null
        val sourceRoot = root.str("sourceRoot") ?: return null
        load(dir, sourceRoot, family)
      } catch (_: Throwable) {
        null
      }
    }
    /** Loads a valid cache, or temporarily loads the source project to rebuild it on disk. */
    fun loadOrBuild(sourceRoot: File, root: File = cacheRoot()): NdsPropLibrary {
      val canonicalRoot = sourceRoot.canonicalFile
      val family = NdsProject(canonicalRoot).family
      val dir = File(root, family.name.lowercase())
      load(dir, canonicalRoot.path, family)?.let { return it }

      // This project is intentionally local to cache generation. Once this method returns its
      // full-ROM byte array, NARC entries, and decoded triangle caches are eligible for collection.
      val source = NdsProject(canonicalRoot)
      val models = mutableListOf<NdsProject.PropModelInfo>()
      val modelDir = File(dir, "models")
      modelDir.mkdirs()
      for (entry in source.propModels().filterNot { it.imported }) {
        val snapshot = source.portablePropSnapshot(entry.sourceModelKey) ?: continue
        NdsMeshSnapshot.write(modelFile(dir, entry.sourceModelKey), snapshot)
        models += entry.copy(
            catalogId = "library:${family.name}:${entry.sourceModelKey}",
            sourceFamily = family,
            sourceModelKey = entry.sourceModelKey,
        )
      }
      dir.mkdirs()
      val catalog = Json.JObj(linkedMapOf(
          "version" to Json.JNum(CACHE_VERSION.toDouble()),
          "sourceRoot" to Json.JStr(canonicalRoot.path),
          "family" to Json.JStr(family.name),
          "models" to Json.JArr(models.map { info ->
            Json.JObj(linkedMapOf(
                "key" to Json.JStr(info.sourceModelKey),
                "label" to Json.JStr(info.label),
                "category" to Json.JStr(info.category),
            ))
          }),
      ))
      File(dir, "catalog.json").writeText(JsonWriter.writePretty(catalog) + "\n")
      return NdsPropLibrary(family, dir, models)
    }

    private fun load(dir: File, sourceRoot: String, family: NdsFamily): NdsPropLibrary? {
      val file = File(dir, "catalog.json")
      if (!file.isFile) return null
      return try {
        val root = JsonParser.parse(file.readText()).asObj() ?: return null
        if (root.int("version") != CACHE_VERSION || root.str("sourceRoot") != sourceRoot ||
            root.str("family") != family.name) return null
        val models = root.arr("models")?.items.orEmpty().mapNotNull { item ->
          val obj = item.asObj() ?: return@mapNotNull null
          val key = obj.str("key") ?: return@mapNotNull null
          if (!modelFile(dir, key).isFile) return null
          NdsProject.PropModelInfo(
              key = key,
              label = obj.str("label") ?: key,
              imported = false,
              category = obj.str("category") ?: "Scenery",
              catalogId = "library:${family.name}:$key",
              sourceFamily = family,
              sourceModelKey = key,
          )
        }
        NdsPropLibrary(family, dir, models)
      } catch (_: Throwable) {
        null
      }
    }

    private fun modelFile(cacheDir: File, modelKey: String): File =
        File(cacheDir, "models/${modelKey.replace(Regex("[^A-Za-z0-9_.-]"), "_")}.mesh")
  }
}
