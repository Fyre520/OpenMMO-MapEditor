package de.lananahwp.openmmo.mapeditor.core

import java.io.File

/** Reads files out of a raw Nintendo DS ROM image. */
class NdsRom(private val data: ByteArray) {
  constructor(file: File) : this(file.readBytes())

  private fun u8(offset: Int) = data[offset].toInt() and 0xFF

  private fun u16(offset: Int) = u8(offset) or (u8(offset + 1) shl 8)

  private fun u32(offset: Int): Int =
      u8(offset) or (u8(offset + 1) shl 8) or (u8(offset + 2) shl 16) or (u8(offset + 3) shl 24)

  val gameCode: String
  private val fntOffset: Int
  private val fatOffset: Int
  private val fatLength: Int

  init {
    require(data.size > 0x200) { "Not an NDS image" }
    gameCode = String(data, 0x0C, 4, Charsets.US_ASCII)
    fntOffset = u32(0x40)
    fatOffset = u32(0x48)
    fatLength = u32(0x4C)
  }

  private val files: Map<String, Int> by lazy {
    val result = LinkedHashMap<String, Int>()
    walk(0, "", result, 0)
    result
  }

  /** Relative paths of every file in the ROM. */
  val paths: List<String> by lazy { files.keys.toList() }

  private fun walk(directory: Int, prefix: String, result: MutableMap<String, Int>, depth: Int) {
    require(depth < 16) { "Cyclic NDS filename table" }
    val entry = fntOffset + (directory and 0xFFF) * 8
    var cursor = fntOffset + u32(entry)
    var fileId = u16(entry + 4)
    while (true) {
      val type = u8(cursor++)
      if (type == 0) return
      val length = type and 0x7F
      val name = String(data, cursor, length, Charsets.US_ASCII)
      cursor += length
      if (type and 0x80 != 0) {
        val child = u16(cursor)
        cursor += 2
        walk(child, "$prefix$name/", result, depth + 1)
      } else {
        result["$prefix$name"] = fileId++
      }
    }
  }

  fun has(path: String): Boolean = path in files

  fun read(path: String): ByteArray {
    val id = files[path] ?: error("ROM has no file $path")
    val entry = fatOffset + id * 8
    require(entry + 8 <= fatOffset + fatLength) { "Invalid FAT entry for $path" }
    val start = u32(entry)
    val end = u32(entry + 4)
    require(start in 0..end && end <= data.size) { "Invalid ROM range for $path" }
    return data.copyOfRange(start, end)
  }

  fun narc(path: String): List<ByteArray> = Narc(read(path)).files
}
