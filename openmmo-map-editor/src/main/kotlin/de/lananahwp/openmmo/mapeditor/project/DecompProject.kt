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

/**
 * All identifier tables a decomp provides, in the same forms the OpenMMO codegen reads them so an
 * export matches the generated output.
 */
class Tables(
    val musicIds: Map<String, Int>,
    val mapsecIds: Map<String, Int>,
    val gfxIds: Map<String, Int>,
    val speciesIds: Map<String, Int>,
    val nationalDex: Map<Int, Int>,
    val tilesetPaletteIds: Map<String, Int>,
    val movementTypes: MovementTypes,
)

/**
 * Opens a PRET decomp checked out at [rootDir] ([RegionSource] over the same directory) and exposes
 * its map groups, layouts and tables. Maps are loaded/saved through the group maps list and the
 * shared layouts.json, exactly like the codegen parser reads them.
 */
class DecompProject(
    val rootDir: File,
    val source: RegionSource,
) {
  val tables: Tables = readTables()

  var groupOrder: List<String> = emptyList()
    private set
  val groupMaps: MutableMap<String, MutableList<String>> = LinkedHashMap()
  val layouts: MutableMap<String, Json.JObj> = LinkedHashMap()

  init {
    readMapGroups()
    readLayouts()
  }

  val region get() = source.region

  /** Map constant (id) or directory name resolving to a group index and directory name. */
  data class Address(val groupIndex: Int, val mapIndex: Int, val mapDirName: String)

  private val addressesByMap: MutableMap<String, Address> = LinkedHashMap()

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

  private fun readMapId(dirName: String): String? {
    val f = File(rootDir, "data/maps/$dirName/map.json")
    if (!f.exists()) return null
    return JsonParser.parse(f.readText()).asObj()?.str("id")
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

  // A plain enum where each NATIONAL_DEX_<suffix> takes the next value, starting at NONE = 0.
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

  fun wireBank(groupIndex: Int): Int = groupIndex + region.gbaBankOffset

  fun groupIndexOf(wireBank: Int): Int = wireBank - region.gbaBankOffset

  fun mapDir(groupName: String, mapIndex: Int): String? = groupMaps[groupName]?.getOrNull(mapIndex)

  fun mapDir(mapConstant: String): String? = addressesByMap[mapConstant]?.mapDirName

  /** Loads a map by its directory name. Returns null when the map.json or layout is missing. */
  fun loadMap(dirName: String): EditorMap? {
    val addr = addressesByMap[dirName] ?: return null
    val mapJsonFile = File(rootDir, "data/maps/$dirName/map.json")
    if (!mapJsonFile.exists()) return null
    val mapJson = JsonParser.parse(mapJsonFile.readText()).asObj() ?: return null
    val layoutId = mapJson.str("layout") ?: return null
    val layoutJson = layouts[layoutId] ?: return null
    val layout = loadLayout(layoutId)
    return EditorMap(
        dirName = dirName,
        groupName = groupOrder[addr.groupIndex],
        groupIndex = addr.groupIndex,
        mapIndex = addr.mapIndex,
        mapJson = mapJson,
        layout = layout,
    )
  }

  fun loadLayout(layoutId: String): EditorLayout {
    val layoutJson = layouts[layoutId] ?: error("Unknown layout '$layoutId'")
    val blocksPath = layoutJson.str("blockdata_filepath") ?: "data/layouts/$layoutId/map.bin"
    val borderPath = layoutJson.str("border_filepath") ?: "data/layouts/$layoutId/border.bin"
    val blocks = readU16List(File(rootDir, blocksPath))
    val border = readU16List(File(rootDir, borderPath))
    return EditorLayout(name = layoutId, layoutJson = layoutJson, blocks = blocks, border = border)
  }

  fun readU16List(file: File): MutableList<Int> {
    if (!file.exists()) return mutableListOf()
    val bytes = file.readBytes()
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val out = mutableListOf<Int>()
    while (buf.remaining() >= 2) out += buf.short.toInt() and 0xFFFF
    return out
  }

  /** Writes [map]'s layout bins and patches the layouts.json entry in place. */
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

  /** Persists all layout json edits to layouts.json and saves every map's map.json. */
  fun save(map: EditorMap) {
    saveLayout(map)
    val mapJsonFile = File(rootDir, "data/maps/${map.dirName}/map.json")
    mapJsonFile.writeText(map.toJsonString())
    writeLayoutsJson()
  }

  fun saveLayoutsJson() = writeLayoutsJson()

  private fun writeLayoutsJson() {
    val entries =
        Json.JArr(layouts.values.toList().map { it as Json })
    val root = Json.JObj(linkedMapOf("layouts" to entries))
    val file = File(rootDir, "data/layouts/layouts.json")
    file.parentFile.mkdirs()
    file.writeText(JsonWriter.write(root) + "\n")
  }
}
