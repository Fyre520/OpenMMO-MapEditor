package de.lananahwp.openmmo.mapeditor.model

data class MetatileBrush(
    val width: Int,
    val height: Int,
    val blocks: IntArray,
    val includesAttributes: Boolean = false,
) {
  init {
    require(width > 0 && height > 0)
    require(blocks.size == width * height)
  }

  fun blockAt(x: Int, y: Int): Int = blocks[y * width + x]

  companion object {
    fun single(metatileId: Int) = MetatileBrush(1, 1, intArrayOf(metatileId))
  }
}
