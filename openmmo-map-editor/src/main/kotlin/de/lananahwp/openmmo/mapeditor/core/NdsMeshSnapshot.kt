package de.lananahwp.openmmo.mapeditor.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * A self-contained chunk of geometry lifted out of a map's baked terrain, together with every
 * texture and palette it references.
 *
 * There is no NSBMD/NSBTX *encoder* in this project (only [NdsNsbmd.decode] / [NdsNsbtx.parsePack]),
 * so an extracted piece of scenery cannot be written back out as a real DS model file. It does not
 * need to be: everything downstream of decoding — the prop catalog, both map views, the exporter —
 * consumes plain [NdsTri] lists plus name->texture/palette maps. [NdsTri] and [NdsTexture] are both
 * pure data, so the decoded form serializes directly.
 *
 * Storing a baked snapshot (rather than a reference back to the source map and edit-group) means an
 * extracted prop keeps the shape it had when it was taken. Editing, moving, or deleting scenery on
 * the source map afterwards cannot silently reshape props already placed elsewhere.
 */
class NdsMeshSnapshot(
    val triangles: List<NdsTri>,
    val textures: Map<String, NdsTexture>,
    val palettes: Map<String, IntArray>,
) {
  companion object {
    private const val MAGIC = "OMEXMESH"
    private const val VERSION = 1

    fun write(file: File, snapshot: NdsMeshSnapshot) {
      file.parentFile?.mkdirs()
      DataOutputStream(file.outputStream().buffered()).use { out ->
        out.writeUTF(MAGIC)
        out.writeInt(VERSION)

        out.writeInt(snapshot.triangles.size)
        for (t in snapshot.triangles) {
          out.writeFloat(t.ax); out.writeFloat(t.ay); out.writeFloat(t.az)
          out.writeFloat(t.bx); out.writeFloat(t.by); out.writeFloat(t.bz)
          out.writeFloat(t.cx); out.writeFloat(t.cy); out.writeFloat(t.cz)
          out.writeInt(t.color)
          out.writeFloat(t.u0); out.writeFloat(t.v0)
          out.writeFloat(t.u1); out.writeFloat(t.v1)
          out.writeFloat(t.u2); out.writeFloat(t.v2)
          out.writeUTF(t.texture)
          out.writeUTF(t.palette)
          out.writeFloat(t.scaleS); out.writeFloat(t.scaleT)
          out.writeBoolean(t.repeatS); out.writeBoolean(t.repeatT)
          out.writeBoolean(t.flipS); out.writeBoolean(t.flipT)
        }

        out.writeInt(snapshot.textures.size)
        for ((name, tex) in snapshot.textures) {
          out.writeUTF(name)
          out.writeUTF(tex.name)
          out.writeInt(tex.format)
          out.writeInt(tex.width)
          out.writeInt(tex.height)
          writeBytes(out, tex.texdata)
          // spdata is only present for the 4bpp-compressed format; -1 marks "absent".
          if (tex.spdata == null) out.writeInt(-1) else writeBytes(out, tex.spdata)
          writeInts(out, tex.palette)
          out.writeBoolean(tex.color0)
        }

        out.writeInt(snapshot.palettes.size)
        for ((name, colors) in snapshot.palettes) {
          out.writeUTF(name)
          writeInts(out, colors)
        }
      }
    }

    /** Reads a snapshot, or returns null when [file] is absent, truncated, or not a snapshot. */
    fun read(file: File): NdsMeshSnapshot? {
      if (!file.isFile) return null
      return try {
        DataInputStream(file.inputStream().buffered()).use { input ->
          if (input.readUTF() != MAGIC) return null
          if (input.readInt() != VERSION) return null

          val triangleCount = input.readInt()
          val triangles = ArrayList<NdsTri>(triangleCount.coerceIn(0, 1 shl 20))
          repeat(triangleCount) {
            triangles += NdsTri(
                ax = input.readFloat(), ay = input.readFloat(), az = input.readFloat(),
                bx = input.readFloat(), by = input.readFloat(), bz = input.readFloat(),
                cx = input.readFloat(), cy = input.readFloat(), cz = input.readFloat(),
                color = input.readInt(),
                u0 = input.readFloat(), v0 = input.readFloat(),
                u1 = input.readFloat(), v1 = input.readFloat(),
                u2 = input.readFloat(), v2 = input.readFloat(),
                texture = input.readUTF(),
                palette = input.readUTF(),
                scaleS = input.readFloat(), scaleT = input.readFloat(),
                repeatS = input.readBoolean(), repeatT = input.readBoolean(),
                flipS = input.readBoolean(), flipT = input.readBoolean(),
            )
          }

          val textures = LinkedHashMap<String, NdsTexture>()
          repeat(input.readInt()) {
            val key = input.readUTF()
            textures[key] = NdsTexture(
                name = input.readUTF(),
                format = input.readInt(),
                width = input.readInt(),
                height = input.readInt(),
                texdata = readBytes(input),
                spdata = readNullableBytes(input),
                palette = readInts(input),
                color0 = input.readBoolean(),
            )
          }

          val palettes = LinkedHashMap<String, IntArray>()
          repeat(input.readInt()) {
            val key = input.readUTF()
            palettes[key] = readInts(input)
          }

          NdsMeshSnapshot(triangles, textures, palettes)
        }
      } catch (_: Throwable) {
        null
      }
    }

    private fun writeBytes(out: DataOutputStream, bytes: ByteArray) {
      out.writeInt(bytes.size)
      out.write(bytes)
    }

    private fun writeInts(out: DataOutputStream, values: IntArray) {
      out.writeInt(values.size)
      for (v in values) out.writeInt(v)
    }

    private fun readBytes(input: DataInputStream): ByteArray {
      val size = input.readInt()
      require(size >= 0) { "negative byte-array length in mesh snapshot" }
      return ByteArray(size).also(input::readFully)
    }

    private fun readNullableBytes(input: DataInputStream): ByteArray? {
      val size = input.readInt()
      if (size < 0) return null
      return ByteArray(size).also(input::readFully)
    }

    private fun readInts(input: DataInputStream): IntArray {
      val size = input.readInt()
      require(size >= 0) { "negative int-array length in mesh snapshot" }
      return IntArray(size) { input.readInt() }
    }
  }
}
