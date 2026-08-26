package de.lananahwp.openmmo.mapeditor.model

/** Summary shown before and after a destructive custom-map crop. */
data class NdsMapCropImpact(
    val propsRemoved: Int,
    val objectsRemoved: Int,
    val warpsRemoved: Int,
    val triggersRemoved: Int,
    val triggersClipped: Int,
    val bgEventsRemoved: Int,
    val outputWidth: Int,
    val outputHeight: Int,
) {
  val eventsRemoved: Int
    get() = objectsRemoved + warpsRemoved + triggersRemoved + bgEventsRemoved
}

/** Coordinate-based cropping for editor-created maps. Coordinates and sizes are local map tiles. */
object NdsMapCropper {
  fun impact(
      map: NdsMap,
      startX: Int,
      startZ: Int,
      width: Int,
      height: Int,
  ): NdsMapCropImpact {
    val bounds = validate(map, startX, startZ, width, height)
    fun pointInside(x: Number, z: Number): Boolean =
        x.toDouble() >= bounds.left && x.toDouble() < bounds.right &&
            z.toDouble() >= bounds.top && z.toDouble() < bounds.bottom

    var removedTriggers = 0
    var clippedTriggers = 0
    for (trigger in map.events.triggers) {
      val intersection = triggerIntersection(trigger, bounds)
      if (intersection == null) removedTriggers++
      else if (intersection.first != trigger.w.coerceAtLeast(1) ||
          intersection.second != trigger.h.coerceAtLeast(1)) clippedTriggers++
    }
    return NdsMapCropImpact(
        propsRemoved = map.props.count { !pointInside(it.x, it.z) },
        objectsRemoved = map.events.objects.count { !pointInside(it.x, it.z) },
        warpsRemoved = map.events.warps.count { !pointInside(it.x, it.z) },
        triggersRemoved = removedTriggers,
        triggersClipped = clippedTriggers,
        bgEventsRemoved = map.events.bgEvents.count { !pointInside(it.x, it.z) },
        outputWidth = bounds.outputWidth,
        outputHeight = bounds.outputHeight,
    )
  }

  /**
   * Keeps the requested tile rectangle and translates it so its top-left tile is (0, 0).
   * The caller owns confirmation and persistence; this method only changes the in-memory map.
   */
  fun crop(
      map: NdsMap,
      startX: Int,
      startZ: Int,
      width: Int,
      height: Int,
  ): NdsMapCropImpact {
    val impact = impact(map, startX, startZ, width, height)
    val bounds = validate(map, startX, startZ, width, height)
    val oldGrid = map.grid
    val newGrid = NdsGrid(bounds.outputWidth, bounds.outputHeight)
    for (layer in 0 until NdsGrid.LAYERS) {
      for (x in 0 until bounds.width) for (z in 0 until bounds.height) {
        val oldX = bounds.left + x
        val oldZ = bounds.top + z
        newGrid.setTile(layer, x, z, oldGrid.tileAt(layer, oldX, oldZ))
        newGrid.setHeight(layer, x, z, oldGrid.heightAt(layer, oldX, oldZ))
      }
    }
    for (x in 0 until bounds.width) for (z in 0 until bounds.height) {
      val oldX = bounds.left + x
      val oldZ = bounds.top + z
      newGrid.setCollision(x, z, oldGrid.collisionAt(oldX, oldZ))
      newGrid.setPermission(x, z, oldGrid.permissionAt(oldX, oldZ))
    }
    map.grid = newGrid

    fun pointInside(x: Number, z: Number): Boolean =
        x.toDouble() >= bounds.left && x.toDouble() < bounds.right &&
            z.toDouble() >= bounds.top && z.toDouble() < bounds.bottom

    map.props.removeIf { prop ->
      if (!pointInside(prop.x, prop.z)) true
      else {
        prop.x -= bounds.left
        prop.z -= bounds.top
        false
      }
    }
    map.events.objects.removeIf { event ->
      if (!pointInside(event.x, event.z)) true
      else {
        event.x -= bounds.left
        event.z -= bounds.top
        false
      }
    }
    map.events.warps.removeIf { event ->
      if (!pointInside(event.x, event.z)) true
      else {
        event.x -= bounds.left
        event.z -= bounds.top
        false
      }
    }
    map.events.bgEvents.removeIf { event ->
      if (!pointInside(event.x, event.z)) true
      else {
        event.x -= bounds.left
        event.z -= bounds.top
        false
      }
    }

    val croppedTriggers = map.events.triggers.mapNotNull { trigger ->
      val size = triggerIntersection(trigger, bounds) ?: return@mapNotNull null
      val clippedX = maxOf(trigger.x, bounds.left)
      val clippedZ = maxOf(trigger.z, bounds.top)
      trigger.copy(
          x = clippedX - bounds.left,
          z = clippedZ - bounds.top,
          w = size.first,
          h = size.second,
      )
    }
    map.events.triggers.clear()
    map.events.triggers += croppedTriggers

    // Removed props and their saved collision coordinates live in the same local coordinate space.
    val croppedRemovals = map.terrainRemovals.mapNotNull { removal ->
      val removedProp = removal.removedProp?.takeIf { pointInside(it.x, it.z) }?.let {
        it.copy(x = it.x - bounds.left, z = it.z - bounds.top)
      }
      if (removal.groupId.isEmpty() && removal.removedProp != null && removedProp == null) {
        return@mapNotNull null
      }
      removal.copy(
          clearedCollision = removal.clearedCollision
              .filter { pointInside(it.x, it.z) }
              .mapTo(mutableListOf()) { it.copy(x = it.x - bounds.left, z = it.z - bounds.top) },
          removedProp = removedProp,
      )
    }
    map.terrainRemovals.clear()
    map.terrainRemovals += croppedRemovals

    // Retain the world cells intersected by the requested bounds. The copied content starts at
    // local (0,0), while the grid is padded up to complete 32x32 cells for an in-game footprint.
    if (map.matrixCells.isNotEmpty()) {
      val minX = map.matrixCells.minOf { it.first }
      val minZ = map.matrixCells.minOf { it.second }
      val startCellX = bounds.left / NdsGrid.COLS
      val startCellZ = bounds.top / NdsGrid.ROWS
      val cellsWide = bounds.outputWidth / NdsGrid.COLS
      val cellsHigh = bounds.outputHeight / NdsGrid.ROWS
      val wantedX = (minX + startCellX) until (minX + startCellX + cellsWide)
      val wantedZ = (minZ + startCellZ) until (minZ + startCellZ + cellsHigh)
      val retained = map.matrixCells.filter { it.first in wantedX && it.second in wantedZ }
      map.matrixCells.clear()
      map.matrixCells += retained
    }
    return impact
  }

  private data class TileBounds(
      val left: Int,
      val top: Int,
      val width: Int,
      val height: Int,
  ) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
    val outputWidth: Int get() = roundUpToCell(width, NdsGrid.COLS)
    val outputHeight: Int get() = roundUpToCell(height, NdsGrid.ROWS)
  }

  private fun validate(
      map: NdsMap,
      startX: Int,
      startZ: Int,
      width: Int,
      height: Int,
  ): TileBounds {
    require(map.isCustom) { "Only custom maps can be cropped" }
    require(startX in 0 until map.grid.cols && startZ in 0 until map.grid.rows) {
      "The crop start must be inside the map"
    }
    require(width > 0 && height > 0) {
      "The crop width and height must be positive"
    }
    require(startX + width <= map.grid.cols && startZ + height <= map.grid.rows) {
      "The crop rectangle extends beyond the map"
    }
    return TileBounds(startX, startZ, width, height)
  }

  private fun roundUpToCell(value: Int, cellSize: Int): Int =
      ((value + cellSize - 1) / cellSize) * cellSize

  private fun triggerIntersection(trigger: NdsTrigger, bounds: TileBounds): Pair<Int, Int>? {
    val left = maxOf(trigger.x, bounds.left)
    val top = maxOf(trigger.z, bounds.top)
    val right = minOf(trigger.x + trigger.w.coerceAtLeast(1), bounds.right)
    val bottom = minOf(trigger.z + trigger.h.coerceAtLeast(1), bounds.bottom)
    if (left >= right || top >= bottom) return null
    return (right - left) to (bottom - top)
  }
}
