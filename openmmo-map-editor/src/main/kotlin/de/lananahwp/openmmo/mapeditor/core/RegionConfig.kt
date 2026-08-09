package de.lananahwp.openmmo.mapeditor.core

/**
 * Per-region constants for wire bank/palette offsets and value mappings, ported from the OpenMMO
 * codegen so editor output matches what the server expects.
 */
data class RegionConfig(
    val name: String,
    val regionId: Int,
    val romType: Int,
    val gbaBankOffset: Int,
    val gbaPaletteOffset: Int,
    val defaultVisibleNpcs: Map<String, List<Int>>,
)

val REGIONS: Map<String, RegionConfig> =
    listOf(
            RegionConfig(
                name = "kanto",
                regionId = 0,
                romType = 0,
                gbaBankOffset = 0,
                gbaPaletteOffset = 0,
                defaultVisibleNpcs = emptyMap(),
            ),
            RegionConfig(
                name = "hoenn",
                regionId = 1,
                romType = 1,
                // Emerald uses offset map and tileset namespaces.
                gbaBankOffset = 50,
                gbaPaletteOffset = 100,
                defaultVisibleNpcs =
                    mapOf(
                        "LittlerootTown_MaysHouse_1F" to listOf(0),
                        "LittlerootTown_BrendansHouse_1F" to listOf(3, 5),
                        "LittlerootTown_ProfessorBirchsLab" to listOf(0),
                    ),
            ),
        )
        .associateBy { it.name }

val DIR_MAP =
    mapOf(
        "down" to "Direction.DOWN",
        "up" to "Direction.UP",
        "left" to "Direction.LEFT",
        "right" to "Direction.RIGHT",
        "dive" to "Direction.DIVE",
        "emerge" to "Direction.EMERGE",
    )

val WEATHER_MAP =
    mapOf(
        "WEATHER_NONE" to "Weather.IN_HOUSE_WEATHER",
        "WEATHER_SUNNY" to "Weather.REGULAR_WEATHER",
        "WEATHER_RAIN" to "Weather.RAINY_WEATHER",
        "WEATHER_SNOW" to "Weather.THREE_SNOW_FLAKES",
        "WEATHER_FOG_HORIZONTAL" to "Weather.STEADY_MIST",
        "WEATHER_FOG_DIAGONAL" to "Weather.STEADY_MIST",
        "WEATHER_SHADE" to "Weather.CLOUDY",
        "WEATHER_UNDERWATER_BUBBLES" to "Weather.UNDERWATER_MIST",
        "WEATHER_VOLCANIC_ASH" to "Weather.DENSE_BRIGHT_MIST",
    )

val MAP_TYPE_MAP =
    mapOf(
        "MAP_TYPE_INDOOR" to "MapType.INSIDE",
        "MAP_TYPE_TOWN" to "MapType.UNKNOWN_0x01",
        "MAP_TYPE_CITY" to "MapType.CITY",
        "MAP_TYPE_ROUTE" to "MapType.ROUTE",
        "MAP_TYPE_UNDERGROUND" to "MapType.UNDERGROUND",
        "MAP_TYPE_UNDERWATER" to "MapType.UNDERWATER",
        "MAP_TYPE_OCEAN_ROUTE" to "MapType.ROUTE",
        "MAP_TYPE_SECRET_BASE" to "MapType.SECRET_BASE",
    )

val ENCOUNTER_MAP =
    mapOf(
        "MAP_TYPE_INDOOR" to "EncounterType.RANDOM",
        "MAP_TYPE_TOWN" to "EncounterType.RANDOM",
        "MAP_TYPE_CITY" to "EncounterType.RANDOM",
        "MAP_TYPE_ROUTE" to "EncounterType.RANDOM",
        "MAP_TYPE_UNDERGROUND" to "EncounterType.RANDOM",
        "MAP_TYPE_UNDERWATER" to "EncounterType.UNKNOWN_0x03",
        "MAP_TYPE_OCEAN_ROUTE" to "EncounterType.RANDOM",
        "MAP_TYPE_SECRET_BASE" to "EncounterType.UNKNOWN_0x03",
    )

val ENCOUNTER_METHODS =
    listOf(
        "land_mons" to "EncounterMethod.LAND",
        "water_mons" to "EncounterMethod.WATER",
        "rock_smash_mons" to "EncounterMethod.ROCK_SMASH",
        "fishing_mons" to "EncounterMethod.FISHING",
    )
