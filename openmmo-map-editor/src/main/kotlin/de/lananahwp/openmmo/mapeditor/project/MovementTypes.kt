package de.lananahwp.openmmo.mapeditor.project

import java.io.File

/** Maps decomp movement-type names to the server's MovementType ref, matching the codegen. */
class MovementTypes(private val ids: Map<String, Int>) {

  fun ref(name: String): String =
      if (name in ids) "MovementType.${name.removePrefix("MOVEMENT_TYPE_")}"
      else "MovementType.NONE"

  fun facingRef(name: String): String = movementToFacingRef(ids[name] ?: 0)

  companion object {
    private const val HEADER = "include/constants/event_object_movement.h"

    fun read(dir: File): MovementTypes = MovementTypes(readMovementDefines(File(dir, HEADER)))

    private fun readMovementDefines(file: File): Map<String, Int> {
      if (!file.exists()) return emptyMap()
      val pattern = Regex("""^#define\s+(MOVEMENT_TYPE_\w+)\s+(0x[0-9A-Fa-f]+|\d+)""")
      return file
          .readLines()
          .mapNotNull { pattern.find(it.trim()) }
          .associate {
            val value = it.groupValues[2]
            it.groupValues[1] to
                if (value.startsWith("0x")) value.substring(2).toInt(16) else value.toInt()
          }
    }
  }
}

private val FACING_REFS =
    listOf("Direction.DOWN", "Direction.UP", "Direction.LEFT", "Direction.RIGHT")

internal fun movementToFacingRef(movementType: Int): String =
    FACING_REFS[
        when (movementType) {
          7 -> 1
          8 -> 0
          9 -> 2
          10 -> 3
          in 64..67 -> movementType - 64
          in 68..71 -> movementType - 68
          in 72..75 -> movementType - 72
          else -> 0
        }]
