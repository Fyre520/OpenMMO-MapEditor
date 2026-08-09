package de.lananahwp.openmmo.mapeditor.core

/**
 * Normalized tile behavior, mirroring the server's TileBehavior enum. Only the behaviors the
 * server acts on are named; everything else is [NORMAL].
 */
enum class TileBehavior {
  NORMAL,
  TALL_GRASS,
  LONG_GRASS,
  JUMP_EAST,
  JUMP_WEST,
  JUMP_NORTH,
  JUMP_SOUTH,
  DOOR,
  NON_ANIMATED_DOOR,
  LADDER,
  STAIR_WARP_EAST,
  STAIR_WARP_WEST,
  NORTH_ARROW_WARP,
  SOUTH_ARROW_WARP,
  EAST_ARROW_WARP,
  WEST_ARROW_WARP,
}

/**
 * Classifies an MB_* metatile behavior name into a [TileBehavior]. Ported from the OpenMMO
 * codegen so editor output and the server agree on behavior semantics.
 */
fun classifyBehavior(name: String): TileBehavior? =
    when (name) {
      "MB_TALL_GRASS" -> TileBehavior.TALL_GRASS
      "MB_LONG_GRASS" -> TileBehavior.LONG_GRASS
      "MB_JUMP_EAST" -> TileBehavior.JUMP_EAST
      "MB_JUMP_WEST" -> TileBehavior.JUMP_WEST
      "MB_JUMP_NORTH" -> TileBehavior.JUMP_NORTH
      "MB_JUMP_SOUTH" -> TileBehavior.JUMP_SOUTH
      "MB_ANIMATED_DOOR",
      "MB_WARP_DOOR" -> TileBehavior.DOOR
      "MB_NON_ANIMATED_DOOR",
      "MB_WATER_DOOR",
      "MB_CAVE_DOOR" -> TileBehavior.NON_ANIMATED_DOOR
      "MB_LADDER",
      "MB_UP_ESCALATOR",
      "MB_DOWN_ESCALATOR",
      "MB_REGULAR_WARP",
      "MB_FALL_WARP",
      "MB_DEEP_SOUTH_WARP",
      "MB_UNION_ROOM_WARP",
      "MB_BRIDGE_OVER_OCEAN",
      "MB_LAVARIDGE_GYM_B1F_WARP",
      "MB_LAVARIDGE_GYM_1F_WARP",
      "MB_LAVARIDGE_1F_WARP",
      "MB_AQUA_HIDEOUT_WARP",
      "MB_MT_PYRE_HOLE",
      "MB_MOSSDEEP_GYM_WARP" -> TileBehavior.LADDER
      "MB_UP_RIGHT_STAIR_WARP",
      "MB_DOWN_RIGHT_STAIR_WARP" -> TileBehavior.STAIR_WARP_EAST
      "MB_UP_LEFT_STAIR_WARP",
      "MB_DOWN_LEFT_STAIR_WARP" -> TileBehavior.STAIR_WARP_WEST
      "MB_NORTH_ARROW_WARP" -> TileBehavior.NORTH_ARROW_WARP
      "MB_SOUTH_ARROW_WARP",
      "MB_WATER_SOUTH_ARROW_WARP" -> TileBehavior.SOUTH_ARROW_WARP
      "MB_EAST_ARROW_WARP" -> TileBehavior.EAST_ARROW_WARP
      "MB_WEST_ARROW_WARP" -> TileBehavior.WEST_ARROW_WARP
      else -> null
    }

/** True when the behavior prevents movement. */
fun TileBehavior.blocks(): Boolean =
    this == TileBehavior.NORMAL ||
        this == TileBehavior.TALL_GRASS ||
        this == TileBehavior.LONG_GRASS
