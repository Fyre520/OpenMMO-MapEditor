package de.lananahwp.openmmo.mapeditor.project

import de.lananahwp.openmmo.mapeditor.core.NdsFamily
import de.lananahwp.openmmo.mapeditor.core.NdsTri

/** Human-facing descriptions for the Gen IV build-model archives. */
internal object NdsPropCatalog {
  data class Entry(val name: String, val category: String)

  fun describe(family: NdsFamily, id: Int, triangles: List<NdsTri>): Entry {
    val curated = if (family == NdsFamily.PLATINUM) platinum[id] else hgss[id]
    if (curated != null) return curated

    if (family == NdsFamily.PLATINUM) {
      when (id) {
        in 321..328 -> return Entry("Underground base floor ${id - 320}", "Underground")
        in 329..336 -> return Entry("Underground base wall ${id - 328}", "Underground")
        in 337..360 -> return Entry("Underground trap ${id - 336}", "Underground trap")
        in 361..380 -> return Entry("Underground treasure ${id - 360}", "Underground treasure")
        in 381..417 -> return Entry("Underground Pokémon statue ${id - 380}", "Underground statue")
        in 457..464 -> return Entry("Gym lift platform ${id - 456}", "Mechanism")
        in 508..514 -> return Entry("Spear Pillar column ${id - 507}", "Structure")
      }
    }

    if (triangles.isEmpty()) return Entry("Empty / unsupported helper", "Helper")
    val tokens = triangles.map { it.texture.lowercase() }.filter { it.isNotBlank() }.distinct()
    if (tokens.isEmpty()) return Entry("Untextured geometry", "Helper")
    val all = tokens.joinToString(" ")

    fun has(vararg fragments: String) = fragments.any(all::contains)
    fun entry(name: String, category: String) = Entry(name, category)

    if (has("en_pc", "gs_pc", "pcloof", "pcwall")) return entry("Pokémon Center", "Building")
    if (has("en_fs", "gs_fs", "fsloof", "fswall")) return entry("Poké Mart", "Building")
    if (has("gym_door", "gymdoor")) return entry("Gym door", "Door")
    if (has("gym")) return entry("Gym building / room piece", "Building")
    if (has("school")) return entry("Trainer School", "Building")
    if (has("pokeark", "park_mark")) return entry("Pokéathlon Park building", "Building")
    if (has("league")) return entry("Pokémon League building / room piece", "Building")
    if (has("station")) return entry("Station building / room piece", "Building")
    if (has("lighthouse")) return entry("Lighthouse room piece", "Room piece")
    if (has("radio")) return entry("Radio Tower building / room piece", "Building")
    if (has("tower")) return entry("Tower building / room piece", "Building")
    if (has("gate_", "gate", "arch")) return entry("Gate / archway", "Structure")
    if (has("bridge", "overpass")) return entry("Bridge / overpass", "Structure")
    if (has("stair", "esca", "slope")) return entry("Stairs / slope", "Structure")
    if (has("fence")) return entry("Fence", "Structure")
    if (has("column", "colum", "pillar")) return entry("Column / pillar", "Structure")
    if (has("tree", "antree", "saf_tool")) return entry("Tree", "Nature")
    if (has("flower", "hana")) return entry("Flowers", "Nature")
    if (has("plant")) return entry("Potted plant", "Furniture")
    if (has("rock", "iwa")) return entry("Rock", "Nature")
    if (has("grass")) return entry("Grass / ground object", "Nature")
    if (has("funsui", "funt", "fun1", "c1_fun")) return entry("Fountain", "Effect")
    if (has("fall_base", "wfall", "taki")) return entry("Waterfall", "Effect")
    if (has("uzushio")) return entry("Whirlpool", "Effect")
    if (has("water", "lake", "washadow")) return entry("Water surface / shadow", "Effect")
    if (has("magma")) return entry("Lava surface", "Effect")
    if (has("smoke")) return entry("Smoke effect", "Effect")
    if (has("wind")) return entry("Wind effect", "Effect")
    if (has("sky")) return entry("Sky backdrop", "Backdrop")
    if (has("light", "beam")) return entry("Light effect", "Effect")
    if (has("door")) return entry("Door", "Door")
    if (has("board", "poster", "photo", "camera", "radio", "sign", "mark")) {
      return entry("Sign / display", "Sign / display")
    }
    if (has("bench")) return entry("Bench", "Furniture")
    if (has("chair")) return entry("Chair", "Furniture")
    if (has("table")) return entry("Table", "Furniture")
    if (has("sofa")) return entry("Sofa", "Furniture")
    if (has("shelf")) return entry("Shelf / cabinet", "Furniture")
    if (has("bed")) return entry("Bed", "Furniture")
    if (has("piano")) return entry("Piano", "Furniture")
    if (has("clock")) return entry("Clock", "Furniture")
    if (has("tv", "audio")) return entry("TV / audio equipment", "Furniture")
    if (has("container")) return entry("Container", "Furniture")
    if (has("yacht", "aqua", "cruiser", "ferry")) return entry("Ship / boat", "Vehicle")
    if (has("truck", "car")) return entry("Vehicle", "Vehicle")
    if (has("warp")) return entry("Warp pad", "Effect")
    if (has("pole")) return entry("Streetlight / pole", "Structure")
    if (has("bell")) return entry("Bell", "Structure")
    if (has("house", "h01", "s01", "s02", "s03", "s04")) return entry("Building / room piece", "Building")

    val useful = tokens.firstOrNull { token ->
      token !in setOf("h_kage", "h_mado", "h_in", "f_kage") &&
          !token.endsWith("_kage") && !token.startsWith("obj_")
    } ?: tokens.first()
    return Entry("Scenery — ${humanize(useful)}", "Scenery")
  }

  private fun e(name: String, category: String) = Entry(name, category)

  private val hgss = mapOf(
      1 to e("Poké Mart", "Building"),
      2 to e("Pokémon Center", "Building"),
      3 to e("Gatehouse", "Building"),
      5 to e("Gym", "Building"),
      8 to e("Traditional house", "Building"),
      9 to e("Bell Tower exterior", "Building"),
      10 to e("Traditional shop", "Building"),
      18 to e("Mountain backdrop", "Backdrop"),
      19 to e("Autumn sky backdrop", "Backdrop"),
      27 to e("Ceiling fan", "Furniture"),
      28 to e("Wind effect", "Effect"),
      29 to e("Signboard – narrow", "Sign / display"),
      30 to e("Signboard – standard", "Sign / display"),
      31 to e("Signboard – wide", "Sign / display"),
      32 to e("Signboard – slim", "Sign / display"),
      33 to e("Signboard – tall", "Sign / display"),
      34 to e("Signboard – double", "Sign / display"),
      43 to e("Whirlpool", "Effect"),
      48 to e("Stone well", "Structure"),
      51 to e("Flower bed", "Nature"),
      58 to e("Trainer School", "Building"),
      59 to e("Arched bridge", "Structure"),
      64 to e("Round table", "Furniture"),
      66 to e("Wall ladder", "Structure"),
      67 to e("Wall ladder", "Structure"),
      69 to e("Cave ladder", "Structure"),
      70 to e("Cave ladder", "Structure"),
      71 to e("House ladder", "Structure"),
      74 to e("Magnet Train station interior", "Room piece"),
      75 to e("Magnet Train carriage", "Vehicle"),
      78 to e("Fountain", "Effect"),
      79 to e("Game Corner exterior", "Building"),
      85 to e("Railway overpass", "Structure"),
      89 to e("Goldenrod Radio Tower", "Building"),
      90 to e("Photo cutout board", "Sign / display"),
      93 to e("Souvenir shop machine", "Furniture"),
      95 to e("Streetlight", "Structure"),
      96 to e("Flagpole", "Structure"),
      97 to e("Decorative arch", "Structure"),
      99 to e("Bell", "Structure"),
      106 to e("Photo board", "Sign / display"),
      112 to e("Route 39 house", "Building"),
      113 to e("Gym monument", "Structure"),
      115 to e("Bench", "Furniture"),
      119 to e("Sailboat", "Vehicle"),
      121 to e("Festival bunting", "Decoration"),
      122 to e("Night sky backdrop", "Backdrop"),
      125 to e("Tree-shadow overlay", "Effect"),
      126 to e("Waterfall base", "Effect"),
      127 to e("Tree-shadow overlay", "Effect"),
      128 to e("Tree-shadow overlay", "Effect"),
      129 to e("Tree-shadow strip", "Effect"),
      130 to e("Tree-shadow strip", "Effect"),
      131 to e("Whirlpool", "Effect"),
      132 to e("Magnet Train station", "Building"),
      136 to e("Evergreen tree", "Nature"),
      137 to e("Battle Tower antenna", "Structure"),
      140 to e("Goldenrod Radio Tower observation deck", "Building"),
      142 to e("Display pedestal", "Furniture"),
      143 to e("Fountain", "Effect"),
      144 to e("Waterfall", "Effect"),
      147 to e("Park gate", "Structure"),
      148 to e("Flower patch", "Nature"),
      168 to e("S.S. Aqua", "Vehicle"),
      171 to e("Safari Zone entrance", "Building"),
      172 to e("Safari Zone arch", "Structure"),
      173 to e("Market stall – blue", "Decoration"),
      174 to e("Market stall – pink", "Decoration"),
      175 to e("Market stall – produce", "Decoration"),
      176 to e("Market stall – blue", "Decoration"),
      178 to e("Battle Frontier bench", "Furniture"),
      179 to e("Battle Frontier counter", "Furniture"),
      180 to e("Battle Frontier display tables", "Furniture"),
      185 to e("Sliding glass doors", "Door"),
      189 to e("Large planter", "Nature"),
      190 to e("Red flower display", "Nature"),
      191 to e("Pink flower display", "Nature"),
      192 to e("Small tree", "Nature"),
      193 to e("Tree stump", "Nature"),
      194 to e("Wooden debris", "Decoration"),
      195 to e("Rock ledge", "Nature"),
      196 to e("Rock ledge", "Nature"),
      197 to e("Rock ledge with grass", "Nature"),
      198 to e("Puddle", "Effect"),
      199 to e("Indoor fountain", "Effect"),
      204 to e("Gym seating row", "Furniture"),
      205 to e("Gym seating block", "Furniture"),
      206 to e("Signboard", "Sign / display"),
      209 to e("Floor lever – yellow", "Mechanism"),
      210 to e("Floor lever – yellow", "Mechanism"),
      211 to e("Floor lever – brown", "Mechanism"),
      214 to e("Tall plant", "Nature"),
      224 to e("Pokéathlon Dome screens", "Sign / display"),
      226 to e("Pokéathlon Dome arena", "Structure"),
      227 to e("Streetlight", "Structure"),
      228 to e("Water-shadow overlay", "Effect"),
      229 to e("Water-shadow overlay", "Effect"),
      230 to e("Water-shadow overlay", "Effect"),
      231 to e("Water-shadow overlay", "Effect"),
      232 to e("Water-shadow overlay", "Effect"),
      233 to e("Water-shadow overlay", "Effect"),
      234 to e("Tower antenna", "Structure"),
      235 to e("Large rock", "Nature"),
      239 to e("Fountain", "Effect"),
      249 to e("Wooden tower frame", "Structure"),
      262 to e("Battle Tower interior", "Room piece"),
      263 to e("Battle Tower exterior", "Building"),
      264 to e("Battle Frontier pavilion", "Building"),
      265 to e("Battle Frontier arena platform", "Structure"),
      266 to e("Battle Frontier arena wall", "Structure"),
      267 to e("Ice barrier", "Nature"),
      268 to e("Ice boulder", "Nature"),
      270 to e("Battle Frontier banner", "Decoration"),
      271 to e("Untextured platform", "Helper"),
      283 to e("Power Plant exterior", "Building"),
      291 to e("Ceiling fan", "Furniture"),
      293 to e("Trainer School exterior", "Building"),
      295 to e("Pokéathlon Park building", "Building"),
      296 to e("Whirl Islands waterfall", "Effect"),
      303 to e("Stage screen", "Sign / display"),
      304 to e("Cave grass mound", "Nature"),
      305 to e("Cave grass wall", "Nature"),
      317 to e("Trainer house exterior", "Building"),
      321 to e("Mt. Silver summit stairs", "Structure"),
      322 to e("Sinjoh Ruins event platform", "Structure"),
      323 to e("Waterfall fragment", "Effect"),
      324 to e("Embedded Tower – Kyogre marking", "Decoration"),
      325 to e("Embedded Tower – Groudon marking", "Decoration"),
      326 to e("Embedded Tower – Rayquaza marking", "Decoration"),
      328 to e("Safari Zone entrance pillars", "Structure"),
      329 to e("Viridian Gym statue", "Decoration"),
      330 to e("Saffron Gym statue", "Decoration"),
      332 to e("Celadon Gym statue", "Decoration"),
      333 to e("Fuchsia Gym statue", "Decoration"),
      334 to e("Pewter Gym statue", "Decoration"),
      337 to e("Cianwood Gym ropes", "Decoration"),
      338 to e("Radio sign", "Sign / display"),
  )

  private val platinum = mapOf(
      1 to e("Tree", "Nature"),
      2 to e("Advertising board", "Sign / display"),
      3 to e("Fountain", "Effect"),
      4 to e("Pokémon Center", "Building"),
      5 to e("Poké Mart", "Building"),
      6 to e("Poké Mart roof piece", "Building"),
      7 to e("Streetlight", "Structure"),
      8 to e("Oreburgh Mine entrance", "Structure"),
      14 to e("Jubilife TV building", "Building"),
      15 to e("Shrub", "Nature"),
      16 to e("Streetlight", "Structure"),
      17 to e("Trainer School", "Building"),
      26 to e("Tree stump", "Nature"),
      31 to e("Suspension bridge", "Structure"),
      32 to e("Suspension bridge", "Structure"),
      34 to e("Canalave ferry", "Vehicle"),
      36 to e("Manhole cover", "Structure"),
      39 to e("City gatehouse", "Building"),
      40 to e("City gatehouse", "Building"),
      41 to e("Signboard – narrow", "Sign / display"),
      42 to e("Signboard – standard", "Sign / display"),
      43 to e("Signboard – wide", "Sign / display"),
      44 to e("Signboard – slim", "Sign / display"),
      45 to e("Signboard – tall", "Sign / display"),
      46 to e("Signboard – double", "Sign / display"),
      51 to e("Oreburgh excavator – front", "Vehicle"),
      52 to e("Oreburgh excavator – side", "Vehicle"),
      53 to e("Oreburgh Mine entrance", "Structure"),
      54 to e("Oreburgh museum", "Building"),
      61 to e("City emblem", "Sign / display"),
      72 to e("Lake Valor blast mark", "Effect"),
      74 to e("Lake surface", "Effect"),
      77 to e("Shrub", "Nature"),
      91 to e("Wall clock", "Furniture"),
      101 to e("Garden bench", "Furniture"),
      106 to e("Pokémon Center machine", "Furniture"),
      111 to e("Game console", "Furniture"),
      114 to e("Indoor appliance", "Furniture"),
      122 to e("Pokémon Center healing machine", "Furniture"),
      123 to e("Pokémon Center counter machine", "Furniture"),
      130 to e("Pokémon Center escalator", "Structure"),
      131 to e("Pokémon Center escalator", "Structure"),
      146 to e("Valley Windworks turbine", "Structure"),
      147 to e("Eterna Forest house", "Building"),
      148 to e("Valley Windworks exterior", "Building"),
      193 to e("Fountain", "Effect"),
      194 to e("Hearthome cathedral", "Building"),
      195 to e("Amity Square stone arch", "Structure"),
      198 to e("Solaceon house set", "Building"),
      208 to e("Storage container", "Furniture"),
      209 to e("Storage container", "Furniture"),
      210 to e("Storage container – yellow", "Furniture"),
      211 to e("Storage container – blue", "Furniture"),
      212 to e("Storage machinery", "Mechanism"),
      213 to e("Steel support tower", "Structure"),
      221 to e("Sunnyshore market", "Building"),
      224 to e("Pokémon rock carving", "Decoration"),
      228 to e("Snowpoint crane", "Mechanism"),
      229 to e("Snowpoint storage container", "Furniture"),
      233 to e("Restaurant interior", "Room piece"),
      234 to e("Guest room interior", "Room piece"),
      242 to e("Gym water floor", "Effect"),
      243 to e("Snowpoint storage container", "Furniture"),
      244 to e("Fountain", "Effect"),
      250 to e("Galactic warehouse crates", "Furniture"),
      251 to e("Galactic warehouse crates", "Furniture"),
      252 to e("Oreburgh Mine barrier", "Structure"),
      253 to e("Oreburgh Mine barrier", "Structure"),
      254 to e("Bicycle display – red", "Sign / display"),
      255 to e("Bicycle display – green", "Sign / display"),
      257 to e("Iron Island railing", "Structure"),
      258 to e("Iron Island platform", "Structure"),
      261 to e("Ice wall", "Nature"),
      262 to e("Pal Park counter", "Furniture"),
      263 to e("Gatehouse counter", "Furniture"),
      266 to e("Classroom chalkboard", "Furniture"),
      272 to e("Mine floor patch", "Decoration"),
      277 to e("Museum display stone", "Furniture"),
      281 to e("Herb shop window", "Furniture"),
      282 to e("Flower display", "Nature"),
      283 to e("Shop poster", "Sign / display"),
      284 to e("Purple floor mat", "Furniture"),
      285 to e("Flower pot", "Nature"),
      299 to e("Oreburgh bridge", "Structure"),
      300 to e("Gym floor mechanism", "Mechanism"),
      301 to e("Gym floor mechanism", "Mechanism"),
      302 to e("Gym lift mechanism", "Mechanism"),
      303 to e("Cave slope", "Structure"),
      304 to e("Cave slope", "Structure"),
      311 to e("Lake surface", "Effect"),
      315 to e("Oreburgh mine cart", "Vehicle"),
      421 to e("Observatory telescope", "Furniture"),
      424 to e("Global Terminal globe", "Furniture"),
      429 to e("Underground tunnel entrance", "Structure"),
      430 to e("Underground tunnel entrance", "Structure"),
      431 to e("Underground tunnel entrance", "Structure"),
      432 to e("Underground tunnel entrance", "Structure"),
      433 to e("Flower box", "Nature"),
      439 to e("Valley Windworks turbine", "Structure"),
      440 to e("Valley Windworks turbine", "Structure"),
      443 to e("Hearthome Contest Hall", "Building"),
      448 to e("Pastoria Croagunk standee", "Sign / display"),
      449 to e("Canalave ferry", "Vehicle"),
      452 to e("Pokémon League entrance", "Building"),
      455 to e("Resort pool", "Effect"),
      465 to e("Gym floor switch – green", "Mechanism"),
      466 to e("Gym floor switch – blue", "Mechanism"),
      467 to e("Gym floor switch – red", "Mechanism"),
      473 to e("Great Marsh boardwalk", "Structure"),
      474 to e("Great Marsh switch", "Mechanism"),
      475 to e("Oreburgh mining truck", "Vehicle"),
      485 to e("Team Galactic control panel", "Mechanism"),
      486 to e("Team Galactic warp field – yellow", "Effect"),
      487 to e("Team Galactic warp field – green", "Effect"),
      488 to e("Team Galactic computer", "Furniture"),
      489 to e("Security camera", "Furniture"),
      494 to e("Iron Island dark wall", "Room piece"),
      498 to e("Elevator wall panel", "Mechanism"),
      501 to e("Route 224 stone emblem", "Decoration"),
      502 to e("Pokémon League lift", "Mechanism"),
      506 to e("Champion room", "Room piece"),
      507 to e("Hall of Fame machine", "Mechanism"),
      517 to e("Pokémon Center wall sign", "Sign / display"),
      524 to e("Fuego Ironworks furnace", "Mechanism"),
      525 to e("Player-house rug", "Furniture"),
      526 to e("Battle Tower platform", "Mechanism"),
      536 to e("Gym balance platform", "Mechanism"),
      537 to e("Gym balance platform", "Mechanism"),
      538 to e("Battle Frontier ferry", "Vehicle"),
      539 to e("Battle Frontier castle", "Building"),
      540 to e("Battle Frontier pavilion", "Building"),
      541 to e("Battle Frontier arena platform", "Structure"),
      542 to e("Battle Frontier arena wall", "Structure"),
      543 to e("Ice barrier", "Nature"),
      545 to e("Battle facility mat", "Furniture"),
      546 to e("Battle facility mat", "Furniture"),
      547 to e("Battle facility mat", "Furniture"),
      548 to e("Battle facility object", "Decoration"),
      549 to e("Warp pad – blue", "Effect"),
      550 to e("Warp pad – green", "Effect"),
      551 to e("Warp pad – pink", "Effect"),
      553 to e("Bench", "Furniture"),
      555 to e("Battle facility machine", "Mechanism"),
      558 to e("Battle Factory conveyor", "Mechanism"),
      559 to e("Round table", "Furniture"),
      560 to e("Bed", "Furniture"),
      561 to e("Sofa", "Furniture"),
      562 to e("Bunk bed", "Furniture"),
      563 to e("Cabinet", "Furniture"),
      564 to e("TV stand", "Furniture"),
      565 to e("Audio cabinet", "Furniture"),
      566 to e("Bookshelf", "Furniture"),
      567 to e("Desk", "Furniture"),
      568 to e("Flower pot", "Nature"),
      569 to e("Computer desk", "Furniture"),
      570 to e("Fireplace", "Furniture"),
      571 to e("Food plate", "Furniture"),
      572 to e("Food plate", "Furniture"),
      573 to e("Piano", "Furniture"),
      574 to e("Dining table", "Furniture"),
      575 to e("Grandfather clock", "Furniture"),
      576 to e("Wall picture", "Furniture"),
      577 to e("Tea set", "Furniture"),
      578 to e("Carpet", "Furniture"),
      579 to e("Spear Pillar portal – yellow", "Effect"),
      580 to e("Spear Pillar portal – blue", "Effect"),
      581 to e("Spear Pillar portal – dark", "Effect"),
      582 to e("Distorted World cliff", "Nature"),
      583 to e("Distorted World cliff", "Nature"),
      584 to e("Lava surface", "Effect"),
      585 to e("Stark Mountain smoke", "Effect"),
      586 to e("Resort pool", "Effect"),
      587 to e("Ice boulder", "Nature"),
      588 to e("Pokémon Center floor strip", "Decoration"),
      589 to e("Turnback Cave portal", "Effect"),
  )

  private fun humanize(raw: String): String {
    val words = raw.removeSuffix("_pl")
        .replace(Regex("^(en|gs|as|ta|ybc|nbc|kn|hk|sk|si|mt|bf|ug)_"), "")
        .split('_')
        .filter { it.isNotBlank() && it !in setOf("h", "a", "b", "c") }
    val value = words.joinToString(" ").ifBlank { raw }
    return value.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
  }
}
