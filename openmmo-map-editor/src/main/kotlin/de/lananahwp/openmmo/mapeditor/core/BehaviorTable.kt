package de.lananahwp.openmmo.mapeditor.core

import java.util.Base64

/** Converts metatile attributes into server behavior data. */
class BehaviorTable(
    val behaviorMask: Int,
    private val idToCategory: Map<Int, TileBehavior>,
) {

  fun behaviorOf(attribute: Long): TileBehavior =
      idToCategory[(attribute and behaviorMask.toLong()).toInt()] ?: TileBehavior.NORMAL

  /** Encodes one behavior byte per map tile. */
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
