package de.lananahwp.openmmo.mapeditor.core

import java.util.Base64

/**
 * Maps raw metatile attribute words to [TileBehavior] and produces the one-behavior-byte-per-tile
 * payload the server expects. Emerald packs attributes as 16-bit words (behavior in bits 0-7),
 * FireRed as 32-bit words (behavior in bits 0-8). Behaviors are classified by name so the two
 * games' differing numeric values do not matter. Ported from the OpenMMO codegen.
 */
class BehaviorTable(
    val behaviorMask: Int,
    private val idToCategory: Map<Int, TileBehavior>,
) {

  fun behaviorOf(attribute: Long): TileBehavior =
      idToCategory[(attribute and behaviorMask.toLong()).toInt()] ?: TileBehavior.NORMAL

  /**
   * Base64 of one behavior ordinal byte per tile, in the same order as the block data. The arrays
   * hold one ordinal per metatile for the primary and secondary tilesets.
   */
  fun behaviorData(
      primaryCount: Int,
      primary: IntArray?,
      secondary: IntArray?,
      blockBytes: ByteArray,
  ): String {
    val tileCount = blockBytes.size / 2
    if (tileCount == 0) return ""
    val out = ByteArray(tileCount)
    for (i in 0 until tileCount) {
      val raw = (blockBytes[i * 2].toInt() and 0xFF) or ((blockBytes[i * 2 + 1].toInt() and 0xFF) shl 8)
      val metatileId = raw and 0x3FF
      val ordinal =
          if (metatileId < primaryCount) primary?.getOrNull(metatileId)
          else secondary?.getOrNull(metatileId - primaryCount)
      out[i] = (ordinal ?: TileBehavior.NORMAL.ordinal).toByte()
    }
    return Base64.getEncoder().encodeToString(out)
  }

  companion object {
    fun fromNames(behaviorIds: Map<String, Int>, behaviorMask: Int): BehaviorTable {
      val idToCategory =
          behaviorIds.mapNotNull { (name, id) -> classifyBehavior(name)?.let { id to it } }.toMap()
      return BehaviorTable(behaviorMask, idToCategory)
    }
  }
}
