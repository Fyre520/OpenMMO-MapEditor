package de.lananahwp.openmmo.mapeditor.project

import de.lananahwp.openmmo.mapeditor.core.RegionSource
import de.lananahwp.openmmo.mapeditor.json.Json
import de.lananahwp.openmmo.mapeditor.json.JsonParser
import de.lananahwp.openmmo.mapeditor.json.JsonWriter
import de.lananahwp.openmmo.mapeditor.model.EditorLayout
import de.lananahwp.openmmo.mapeditor.model.EditorMap
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Identifier tables read from a decomp. */
class Tables(
    val musicIds: Map<String, Int>,
    val mapsecIds: Map<String, Int>,
    val gfxIds: Map<String, Int>,
    val speciesIds: Map<String, Int>,
    val nationalDex: Map<Int, Int>,
    val tilesetPaletteIds: Map<String, Int>,
    val movementTypes: MovementTypes,
)

/** Loads editable maps from a PRET decomp. */
class DecompProject(
    val rootDir: File,
    val source: RegionSource,
) {
  val tables: Tables = readTables()

  var groupOrder: List<String> = emptyList()
    private set
  val groupMaps: MutableMap<String, MutableList<String>> = LinkedHashMap()
  val layouts: MutableMap<String, Json.JObj> = LinkedHashMap()

  /** Resolved map address. */
  data class Address(val groupIndex: Int, val mapIndex: Int, val mapDirName: String)

  private val addressesByMap: MutableMap<String, Address> = LinkedHashMap()

  private val mapIdCache = HashMap<String, String?>()
  private val editorMapCache = HashMap<String, EditorMap>()
  private val layoutBlockCache = HashMap<String, EditorLayout>()

  init {
    readMapGroups()
    readLayouts()
  }

  val region get() = source.region

  private fun readMapGroups() {
    val file = File(rootDir, "data/maps/map_groups.json")
    require(file.exists()) {
      "Decomp not initialized at $rootDir (missing ${file.path}). Run git submodule update --init."
    }
    val mapGroups = JsonParser.parse(file.readText()).asObj() ?: error("bad map_groups.json")
    groupOrder = mapGroups.arr("group_order")?.items.orEmpty().mapNotNull { it.asStr() }
    groupMaps.clear()
    for (group in groupOrder) {
      groupMaps[group] =
          mapGroups.arr(group)?.items.orEmpty().mapNotNull { it.asStr() }.toMutableList()
    }
    addressesByMap.clear()
    for ((groupIndex, groupName) in groupOrder.withIndex()) {
      val maps = groupMaps[groupName] ?: continue
      for ((mapIndex, dirName) in maps.withIndex()) {
        val addr = Address(groupIndex, mapIndex, dirName)
        addressesByMap[dirName] = addr
        readMapId(dirName)?.let { addressesByMap[it] = addr }
      }
    }
  }

  private fun readMapId(dirName: String): String? =
      mapIdCache.getOrPut(dirName) {
        val f = File(rootDir, "data/maps/$dirName/map.json")
        if (!f.exists()) null
        else JsonParser.parse(f.readText()).asObj()?.str("id")
      }

  private fun readLayouts() {
    val file = File(rootDir, "data/layouts/layouts.json")
    if (!file.exists()) return
    val layoutsArr =
        JsonParser.parse(file.readText()).asObj()?.arr("layouts")?.items.orEmpty()
    layouts.clear()
    for (item in layoutsArr) {
      val obj = item.asObj() ?: continue
      val id = obj.str("id") ?: continue
      layouts[id] = obj
    }
  }

  private fun readTables(): Tables {
    fun defineTable(file: File, prefix: String): Map<String, Int> {
      if (!file.exists()) return emptyMap()
      val pattern = Regex("""^#define\s+($prefix\w+)\s+(\d+)""")
      return file
          .readLines()
          .mapNotNull { pattern.find(it.trim()) }
          .associate { it.groupValues[1] to it.groupValues[2].toInt() }
    }
    fun readMapsecIds(): Map<String, Int> {
      val file = File(rootDir, "src/data/region_map/region_map_sections.json")
      if (!file.exists()) return emptyMap()
      val sections = JsonParser.parse(file.readText()).asObj()?.arr("map_sections")?.items.orEmpty()
      return sections
          .mapIndexedNotNull { i, el -> el.asObj()?.str("id")?.let { it to i } }
          .toMap()
    }
    fun readGfxIds(): Map<String, Int> {
      val gfx = defineTable(File(rootDir, "include/constants/event_objects.h"), "OBJ_EVENT_GFX_")
          .toMutableMap()
      val varsBase =
          gfx["OBJ_EVENT_GFX_VARS"]
              ?: defineNumber(File(rootDir, "include/constants/event_objects.h"), "NUM_OBJ_EVENT_GFX")
                  ?.plus(1)
              ?: return gfx
      val varPattern =
          Regex(
              """^#define\s+(OBJ_EVENT_GFX_VAR_[0-9A-Fa-f]+)\s+\(OBJ_EVENT_GFX_VARS\s*\+\s*0x([0-9A-Fa-f]+)\)""")
      File(rootDir, "include/constants/event_objects.h").forEachLine { raw ->
        val m = varPattern.find(raw.trim()) ?: return@forEachLine
        gfx[m.groupValues[1]] = varsBase + m.groupValues[2].toInt(16)
      }
      return gfx
    }
    fun readTilesetPaletteIds(): Map<String, Int> {
      val file = File(rootDir, "src/data/tilesets/headers.h")
      if (!file.exists()) return emptyMap()
      val pattern = Regex("""^const struct Tileset\s+(gTileset_\w+)\s*=""")
      var index = region.gbaPaletteOffset
      val out = mutableMapOf<String, Int>()
      file.forEachLine { raw ->
        val m = pattern.find(raw.trim()) ?: return@forEachLine
        out[m.groupValues[1]] = index
        index++
      }
      return out
    }
    fun readNationalDex(): Map<Int, Int> {
      val internalBySuffix =
          defineTable(File(rootDir, "include/constants/species.h"), "SPECIES_").mapKeys {
            it.key.removePrefix("SPECIES_")
          }
      val nationalBySuffix = enumTable(File(rootDir, "include/constants/pokedex.h"), "NATIONAL_DEX_")
      return internalBySuffix
          .mapNotNull { (suffix, internal) -> nationalBySuffix[suffix]?.let { internal to it } }
          .toMap()
    }
    val speciesIds = defineTable(File(rootDir, "include/constants/species.h"), "SPECIES_")
    return Tables(
        musicIds = defineTable(File(rootDir, "include/constants/songs.h"), "MUS_"),
        mapsecIds = readMapsecIds(),
        gfxIds = readGfxIds(),
        speciesIds = speciesIds,
        nationalDex = readNationalDex(),
        tilesetPaletteIds = readTilesetPaletteIds(),
        movementTypes = MovementTypes.read(rootDir),
    )
  }

  private fun defineNumber(file: File, name: String): Int? {
    if (!file.exists()) return null
    val pattern = Regex("""^#define\s+$name\s+(\d+)""")
    return file.readLines().firstNotNullOfOrNull { pattern.find(it.trim())?.groupValues?.get(1)?.toInt() }
  }

  // National Pokédex values increment sequentially.
  private fun enumTable(file: File, prefix: String): Map<String, Int> {
    if (!file.exists()) return emptyMap()
    val re = Regex("""^$prefix(\w+)\s*(?:=\s*(\d+))?,?$""")
    val out = LinkedHashMap<String, Int>()
    var next = 0
    var inEnum = false
    for (raw in file.readLines()) {
      val line = raw.trim()
      if (line.startsWith("enum")) {
        inEnum = true
        continue
      }
      if (!inEnum) continue
      if (line.startsWith("}")) break
      val m = re.find(line) ?: continue
      val id = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: next
      out[m.groupValues[1]] = id
      next = id + 1
    }
    return out
  }

  fun addressOf(mapDirName: String): Address? = addressesByMap[mapDirName]

  /** Checks map identifier availability. */
  fun mapExists(name: String): Boolean = addressesByMap.containsKey(name)

  /** Reads matching header constants. */
  fun constants(filePath: String, prefix: String): List<String> {
    val f = File(rootDir, filePath)
    if (!f.exists()) return emptyList()
    val re = Regex("""#define\s+($prefix\w+)\s+""")
    return f.readLines().mapNotNull { re.find(it.trim())?.groupValues?.get(1) }.distinct().sorted()
  }

  fun wireBank(groupIndex: Int): Int = groupIndex + region.gbaBankOffset

  fun groupIndexOf(wireBank: Int): Int = wireBank - region.gbaBankOffset

  fun mapDir(groupName: String, mapIndex: Int): String? = groupMaps[groupName]?.getOrNull(mapIndex)

  fun mapDir(mapConstant: String): String? = addressesByMap[mapConstant]?.mapDirName

  /** Reads map JSON directly. */
  fun readMapJson(dirName: String): Json.JObj? {
    val f = File(rootDir, "data/maps/$dirName/map.json")
    return if (f.exists()) JsonParser.parse(f.readText()).asObj() else null
  }

  /** Loads a map by directory name. */
fun loadMap(dirName: String): EditorMap? {
    editorMapCache[dirName]?.let { return it }
    val addr = addressesByMap[dirName] ?: return null
    val mapJsonFile = File(rootDir, "data/maps/$dirName/map.json")
    if (!mapJsonFile.exists()) return null
    val mapJson = JsonParser.parse(mapJsonFile.readText()).asObj() ?: return null
    val layoutId = mapJson.str("layout") ?: return null
    val layoutJson = layouts[layoutId] ?: return null
    val layout = loadLayout(layoutId)
    val override = readOverrideMetadata(dirName)
    val map = EditorMap(
        dirName = dirName,
        groupName = groupOrder[addr.groupIndex],
        groupIndex = addr.groupIndex,
        mapIndex = addr.mapIndex,
        mapJson = mapJson,
        layout = layout,
        exportGroupIndex = override?.int("source_group_index") ?: addr.groupIndex,
        exportMapIndex = override?.int("source_map_index") ?: addr.mapIndex,
        sourceDirName = override?.str("source_dir") ?: dirName,
        sourceMapId = override?.str("source_map_id"),
    )
    editorMapCache[dirName] = map
    return map
  }

  private fun readOverrideMetadata(dirName: String): Json.JObj? {
    val file = File(rootDir, "data/maps/$dirName/.openmmo-override.json")
    return if (file.isFile) JsonParser.parse(file.readText()).asObj() else null
  }

  fun loadLayout(layoutId: String): EditorLayout {
    layoutBlockCache[layoutId]?.let { return it }
    val layoutJson = layouts[layoutId] ?: error("Unknown layout '$layoutId'")
    val blocksPath = layoutJson.str("blockdata_filepath") ?: "data/layouts/$layoutId/map.bin"
    val borderPath = layoutJson.str("border_filepath") ?: "data/layouts/$layoutId/border.bin"
    val blocks = readU16List(File(rootDir, blocksPath))
    val border = readU16List(File(rootDir, borderPath))
    return EditorLayout(name = layoutId, layoutJson = layoutJson, blocks = blocks, border = border).also {
      layoutBlockCache[layoutId] = it
    }
  }

  fun readU16List(file: File): MutableList<Int> {
    if (!file.exists()) return mutableListOf()
    val bytes = file.readBytes()
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val out = mutableListOf<Int>()
    while (buf.remaining() >= 2) out += buf.short.toInt() and 0xFFFF
    return out
  }

  /** Saves layout blocks and metadata. */
  fun saveLayout(map: EditorMap) {
    val layout = map.layout
    val layoutJson = layout.layoutJson
    val blocksPath = layoutJson.str("blockdata_filepath") ?: "data/layouts/${layout.name}/map.bin"
    val borderPath = layoutJson.str("border_filepath") ?: "data/layouts/${layout.name}/border.bin"
    writeU16List(File(rootDir, blocksPath), layout.blocks)
    writeU16List(File(rootDir, borderPath), layout.border)
    // Width/height/tileset changes live in the shared layouts.json.
    layoutJson.entries["width"] = Json.JNum(layout.width.toDouble())
    layoutJson.entries["height"] = Json.JNum(layout.height.toDouble())
  }

  fun writeU16List(file: File, values: List<Int>) {
    val buf = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    values.forEach { buf.putShort((it and 0xFFFF).toShort()) }
    file.parentFile.mkdirs()
    file.writeBytes(buf.array())
  }

/** Saves map and layout changes. */
  fun save(map: EditorMap) {
    saveLayout(map)
    val mapJsonFile = File(rootDir, "data/maps/${map.dirName}/map.json")
    mapJsonFile.writeText(map.toJsonString())
    writeLayoutsJson()
    layoutBlockCache.remove(map.layout.name)
    editorMapCache[map.dirName] = map
  }

  fun saveLayoutsJson() = writeLayoutsJson()

  /** Creates and registers a layout. */
  fun createLayout(
      layoutId: String,
      width: Int,
      height: Int,
      primary: String,
      secondary: String,
      fillBlock: Int,
  ): Json.JObj {
    require(layoutId !in layouts) { "Layout '$layoutId' already exists" }
    val dir = File(rootDir, "data/layouts/$layoutId")
    dir.mkdirs()
    writeU16List(File(dir, "map.bin"), List(width * height) { fillBlock and 0x3FF })
    writeU16List(File(dir, "border.bin"), List(4) { fillBlock and 0x3FF })
    val entry =
        Json.JObj(
            linkedMapOf(
                "id" to Json.JStr(layoutId),
                "name" to Json.JStr("${layoutId.removePrefix("LAYOUT_")}_Layout"),
                "width" to Json.JNum(width.toDouble()),
                "height" to Json.JNum(height.toDouble()),
                "border_width" to Json.JNum(2.0),
                "border_height" to Json.JNum(2.0),
                "primary_tileset" to Json.JStr(primary),
                "secondary_tileset" to Json.JStr(secondary),
                "border_filepath" to Json.JStr("data/layouts/$layoutId/border.bin"),
                "blockdata_filepath" to Json.JStr("data/layouts/$layoutId/map.bin"),
            ))
    layouts[layoutId] = entry
    saveLayoutsJson()
    return entry
  }

  fun duplicateAsOverride(source: EditorMap, dirName: String, name: String): EditorMap {
    require(dirName.matches(Regex("[A-Za-z][A-Za-z0-9_]*"))) { "Invalid directory name" }
    refresh()
    require(!mapExists(dirName)) { "Map directory '$dirName' already exists" }
    val layoutId = "LAYOUT_$dirName"
    require(layoutId !in layouts) { "Layout '$layoutId' already exists" }
    val groupMaps = groupMaps[source.groupName] ?: error("Unknown map group '${source.groupName}'")
    val mapId = availableMapConstant(dirName)

    createLayout(
        layoutId,
        source.layout.width,
        source.layout.height,
        source.layout.primaryTileset,
        source.layout.secondaryTileset,
        0,
    )
    val copiedLayout = JsonParser.parse(JsonWriter.write(source.layout.layoutJson)).asObj()!!
    copiedLayout.entries["id"] = Json.JStr(layoutId)
    copiedLayout.entries["name"] = Json.JStr("${dirName}_Layout")
    copiedLayout.entries["border_filepath"] =
        Json.JStr("data/layouts/$layoutId/border.bin")
    copiedLayout.entries["blockdata_filepath"] =
        Json.JStr("data/layouts/$layoutId/map.bin")
    layouts[layoutId] = copiedLayout
    writeLayoutsJson()
    val duplicate =
        createMap(
            source.groupIndex,
            groupMaps.size,
            dirName,
            mapId,
            name,
            layoutId,
            source.music,
            source.mapsec,
            source.weather,
            source.mapType,
            source.requiresFlash,
        )
    val copiedJson = JsonParser.parse(JsonWriter.write(source.mapJson)).asObj()!!
    copiedJson.entries["id"] = Json.JStr(mapId)
    copiedJson.entries["name"] = Json.JStr(name)
    copiedJson.entries["layout"] = Json.JStr(layoutId)
    duplicate.mapJson.entries.clear()
    duplicate.mapJson.entries.putAll(copiedJson.entries)
    duplicate.layout.blocks.clear()
    duplicate.layout.blocks.addAll(source.layout.blocks)
    duplicate.layout.border.clear()
    duplicate.layout.border.addAll(source.layout.border)
    duplicate.layout.borderWidth = source.layout.borderWidth
    duplicate.layout.borderHeight = source.layout.borderHeight
    save(duplicate)

    val metadata =
        Json.JObj(
            linkedMapOf(
                "source_dir" to Json.JStr(source.sourceDirName),
                "source_map_id" to Json.JStr(source.sourceMapId ?: source.id),
                "source_group_index" to Json.JNum(source.exportGroupIndex.toDouble()),
                "source_map_index" to Json.JNum(source.exportMapIndex.toDouble()),
            ))
    val metadataFile = File(rootDir, "data/maps/$dirName/.openmmo-override.json")
    metadataFile.writeText(JsonWriter.writePretty(metadata) + "\n")
    refresh()
    return loadMap(dirName) ?: error("Could not reload '$dirName'")
  }

  private fun availableMapConstant(dirName: String): String {
    val snake =
        dirName
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .replace(Regex("[^A-Za-z0-9]+"), "_")
            .uppercase()
            .trim('_')
    val base = "MAP_$snake"
    var candidate = base
    var suffix = 2
    while (mapExists(candidate)) candidate = "${base}_${suffix++}"
    return candidate
  }

  /** Creates and registers a map. */
  fun createMap(
      groupIndex: Int,
      index: Int,
      dirName: String,
      id: String,
      name: String,
      layoutId: String,
      music: String,
      mapsec: String,
      weather: String,
      mapType: String,
      requiresFlash: Boolean,
  ): EditorMap {
    require(!mapExists(dirName) && !mapExists(id)) { "A map '$dirName'/'$id' already exists" }
    val group = ensureGroup(groupIndex)
    val maps = groupMaps[group] ?: error("Unknown map group '$group'")

    val dir = File(rootDir, "data/maps/$dirName")
    dir.mkdirs()
    val mapJson =
        Json.JObj(
            linkedMapOf(
                "id" to Json.JStr(id),
                "name" to Json.JStr(name),
                "layout" to Json.JStr(layoutId),
                "music" to Json.JStr(music),
                "region_map_section" to Json.JStr(mapsec),
                "requires_flash" to Json.JBool(requiresFlash),
                "weather" to Json.JStr(weather),
                "map_type" to Json.JStr(mapType),
                "allow_cycling" to Json.JBool(true),
                "allow_escaping" to Json.JBool(false),
                "allow_running" to Json.JBool(true),
                "show_map_name" to Json.JBool(true),
                "battle_scene" to Json.JStr("MAP_BATTLE_SCENE_NORMAL"),
                "connections" to Json.JArr(emptyList()),
                "object_events" to Json.JArr(emptyList()),
                "warp_events" to Json.JArr(emptyList()),
                "coord_events" to Json.JArr(emptyList()),
                "bg_events" to Json.JArr(emptyList()),
            ))
    File(dir, "map.json").writeText(JsonWriter.writePretty(mapJson) + "\n")

    val pos = index.coerceIn(0, maps.size)
    maps.add(pos, dirName)
    saveMapGroups()

    // Reload addresses after insertion.
    readMapGroups()
    return EditorMap(
        dirName = dirName,
        groupName = group,
        groupIndex = groupIndex,
        mapIndex = pos,
        mapJson = mapJson,
        layout = loadLayout(layoutId),
    )
  }

  /** Saves map group ordering. */
  fun saveMapGroups() {
    val root =
        Json.JObj(linkedMapOf("group_order" to Json.JArr(groupOrder.map { Json.JStr(it) })))
    for (group in groupOrder) {
      root.entries[group] =
          Json.JArr((groupMaps[group] ?: emptyList()).map { Json.JStr(it) })
    }
    val file = File(rootDir, "data/maps/map_groups.json")
    file.parentFile.mkdirs()
    file.writeText(JsonWriter.writePretty(root) + "\n")
  }

/** Reloads indexes after creating maps or layouts. */
  fun refresh() {
    clearCaches()
    readMapGroups()
    readLayouts()
  }

  /** Invalidates all cached data. Call after structural changes. */
  fun clearCaches() {
    mapIdCache.clear()
    editorMapCache.clear()
    layoutBlockCache.clear()
  }

  /** Creates missing map groups through [groupIndex]. */
  fun ensureGroup(groupIndex: Int): String {
    require(groupIndex >= 0) { "Map bank is below the region offset" }
    val offset = region.gbaBankOffset
    while (groupOrder.size <= groupIndex) {
      val bank = groupOrder.size + offset
      val name = "gMapGroup_Bank$bank"
      groupOrder = groupOrder + name
      groupMaps[name] = mutableListOf()
    }
    return groupOrder[groupIndex]
  }

  private fun writeLayoutsJson() {
    val entries =
        Json.JArr(layouts.values.toList().map { it as Json })
    val root = Json.JObj(linkedMapOf("layouts" to entries))
    val file = File(rootDir, "data/layouts/layouts.json")
    file.parentFile.mkdirs()
    file.writeText(JsonWriter.write(root) + "\n")
  }
}
