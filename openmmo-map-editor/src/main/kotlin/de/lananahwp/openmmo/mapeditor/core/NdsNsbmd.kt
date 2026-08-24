package de.lananahwp.openmmo.mapeditor.core

/** Little-endian cursor reader over a byte array. */
internal class NsBmdReader(val data: ByteArray) {
  var pos = 0

  fun u8(): Int {
    if (pos >= data.size) return 0
    return data[pos++].toInt() and 0xFF
  }
  fun u16(): Int {
    if (pos + 2 > data.size) return 0
    val v = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
    pos += 2
    return v
  }
  fun i16(): Int = u16().let { if (it and 0x8000 != 0) it or -0x10000 else it }
  fun u32(): Int {
    if (pos + 4 > data.size) return 0
    val v =
        (data[pos].toInt() and 0xFF) or
            ((data[pos + 1].toInt() and 0xFF) shl 8) or
            ((data[pos + 2].toInt() and 0xFF) shl 16) or
            ((data[pos + 3].toInt() and 0xFF) shl 24)
    pos += 4
    return v
  }
  fun i32(): Int = u32()
  fun skip(n: Int) { pos = (pos + n).coerceAtMost(data.size) }
  fun seek(p: Int) { pos = p.coerceIn(0, data.size) }

  fun name(): String {
    var end = pos
    while (end < pos + 16 && end < data.size && data[end].toInt() != 0) end++
    val s = String(data, pos, end - pos, Charsets.US_ASCII)
    pos += 16
    return s
  }

  fun remaining(): Int = data.size - pos
}

/** Column-major 4x4 matrix (port of DSPRE's MTX44). */
internal class NdsMtx {
  val m = FloatArray(16)

  fun loadIdentity() {
    m.fill(0f)
    m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f
  }

  fun clone(): NdsMtx {
    val c = NdsMtx()
    System.arraycopy(m, 0, c.m, 0, 16)
    return c
  }

  fun multMatrix(b: NdsMtx): NdsMtx {
    val out = NdsMtx()
    for (i in 0 until 4) {
      for (j in 0 until 4) {
        var acc = 0f
        for (k in 0 until 4) acc += m[k * 4 + j] * b.m[i * 4 + k]
        out.m[i * 4 + j] = acc
      }
    }
    return out
  }

  fun multVector(v: FloatArray): FloatArray =
      floatArrayOf(
          v[0] * m[0] + v[1] * m[4] + v[2] * m[8] + m[12],
          v[0] * m[1] + v[1] * m[5] + v[2] * m[9] + m[13],
          v[0] * m[2] + v[1] * m[6] + v[2] * m[10] + m[14],
      )

  fun copyTo(other: NdsMtx) {
    System.arraycopy(m, 0, other.m, 0, 16)
  }

  fun translate(x: Float, y: Float, z: Float) {
    val b = NdsMtx()
    b.loadIdentity()
    b.m[12] = x; b.m[13] = y; b.m[14] = z
    System.arraycopy(multMatrix(b).m, 0, m, 0, 16)
  }

  fun scale(x: Float, y: Float, z: Float) {
    val b = NdsMtx()
    b.loadIdentity()
    b.m[0] = x; b.m[5] = y; b.m[10] = z
    System.arraycopy(multMatrix(b).m, 0, m, 0, 16)
  }
}

/** Parsed material from an NSBMD model. */
internal class NdsNsbmdMaterial {
  var repeatS = 0
  var repeatT = 0
  var flipS = 0
  var flipT = 0
  var diffuseColor = 0xFFFFFFFF.toInt()
  var alpha = 31
  var width = 8
  var height = 8
  var scaleS = 1f
  var scaleT = 1f
  var format = 0
  var texname = ""
  var palname = ""
}

internal class NdsNsbmdObject {
  var parentId = -1
  var stackId = -1
  var restoreId = -1
  var visible = true
  val childs = mutableListOf<Int>()
  var materix = NdsMtx().also { it.loadIdentity() }
}

internal class NdsNsbmdPolygon {
  var matId = -1
  var stackId = -1
  var jointId = -1
  var name = ""
  var polyData = ByteArray(0)
}

/** One decoded triangle (positions already in world space). */
data class NdsTri(
    val ax: Float, val ay: Float, val az: Float,
    val bx: Float, val by: Float, val bz: Float,
    val cx: Float, val cy: Float, val cz: Float,
    val color: Int,
    val u0: Float, val v0: Float,
    val u1: Float, val v1: Float,
    val u2: Float, val v2: Float,
    val texture: String = "",
    val palette: String = "",
    val scaleS: Float = 1f,
    val scaleT: Float = 1f,
    val repeatS: Boolean = false,
    val repeatT: Boolean = false,
    val flipS: Boolean = false,
    val flipT: Boolean = false,
    /** Stable editor-side terrain component identifier; empty for ordinary decoded models. */
    val editGroup: String = "",
)

/** One model-side texture definition: the material ids that use it, plus its name. */
internal class NdsNsbmdTexDef(val texmatid: MutableList<Int> = mutableListOf(), var texname: String = "")

/** One model-side palette definition: the material ids that use it, plus its name. */
internal class NdsNsbmdPalDef(val palmatid: MutableList<Int> = mutableListOf(), var palname: String = "")

/** Parsed NSBMD model ready for geometry decoding. */
internal class NdsNsbmdModel {
  val objects = mutableListOf<NdsNsbmdObject>()
  val materials = mutableListOf<NdsNsbmdMaterial>()
  val polygons = mutableListOf<NdsNsbmdPolygon>()
  val matrixStack = Array(31) { NdsMtx().also { it.loadIdentity() } }
  val textureDefs = mutableListOf<NdsNsbmdTexDef>()
  val paletteDefs = mutableListOf<NdsNsbmdPalDef>()
  var modelScale = 1f
  var lastStackId = 0
  var codeoffset = 0
  var texpaloffset = 0
}

private fun sign(data: Int, size: Int): Int =
    if ((data and (1 shl (size - 1))) != 0) data or (-1 shl size) else data

/** Parses an NSBMD (BMD0) byte array and decodes its geometry (port of DSPRE). */
object NdsNsbmd {

  /** The model's scale factor (MDL0 header, /4096). */
  internal fun modelScaleOf(bytes: ByteArray): Float {
    val parse = parse(bytes) ?: return 1f
    return parse.modelScale
  }

  /** Parses and decodes all polygons into world-space triangles (1 world unit = 1 tile). */
  fun decode(bytes: ByteArray): List<NdsTri> = decode(bytes, worldScale = true)

  /** Parses and decodes all polygons into triangles. [worldScale] applies the modelScale/64
   *  world-scale factor (1 world unit = 1 tile); building models use their own transform. */
  fun decode(bytes: ByteArray, worldScale: Boolean): List<NdsTri> {
    val model = parse(bytes) ?: return emptyList()
    buildMatrixStack(model)
    // DSPRE renders the model with glScalef(modelScale/64): that converts the model's
    // native units into DS world tiles, so each matrix cell spans 32x32 tiles.
    val factor = if (worldScale) model.modelScale / 64f else 1f
    val out = mutableListOf<NdsTri>()
    for (poly in model.polygons) {
      for (t in decodePolygon(poly, model)) {
        if (factor == 1f) {
          out += t
        } else {
          out +=
              t.copy(
                  ax = t.ax * factor, ay = t.ay * factor, az = t.az * factor,
                  bx = t.bx * factor, by = t.by * factor, bz = t.bz * factor,
                  cx = t.cx * factor, cy = t.cy * factor, cz = t.cz * factor,
              )
        }
      }
    }
    return out
  }

  internal fun parse(bytes: ByteArray): NdsNsbmdModel? {
    try {
      return parseInner(bytes)
    } catch (_: Exception) {
      return null
    }
  }

  private fun parseInner(bytes: ByteArray): NdsNsbmdModel {
    val reader = NsBmdReader(bytes)
    // BMD0 block table: u16 numBlock at 0x0E, then numBlock u32 offsets (relative to file start).
    // The first block is the MDL0 model; later blocks (TEX0 etc.) may follow.
    var mdl0Offset = 0x14
    if (bytes.size >= 0x10) {
      val numBlock = u16At(bytes, 0x0E)
      for (i in 0 until numBlock.coerceAtMost(64)) {
        val off = u32At(bytes, 0x10 + i * 4)
        if (off in 0..(bytes.size - 4) &&
            bytes[off] == 'M'.code.toByte() &&
            bytes[off + 1] == 'D'.code.toByte() &&
            bytes[off + 2] == 'L'.code.toByte() &&
            bytes[off + 3] == '0'.code.toByte()
        ) {
          mdl0Offset = off
          break
        }
      }
    }
    reader.seek(mdl0Offset)
    return readMdl0(reader, mdl0Offset)
  }

  private fun u16At(bytes: ByteArray, off: Int): Int =
      (bytes.getOrElse(off) { 0 }.toInt() and 0xFF) or
          ((bytes.getOrElse(off + 1) { 0 }.toInt() and 0xFF) shl 8)

  private fun u32At(bytes: ByteArray, off: Int): Int =
      u16At(bytes, off) or (u16At(bytes, off + 2) shl 16)

  private fun readMdl0(reader: NsBmdReader, blockOffset: Int = 0x14): NdsNsbmdModel {
    reader.seek(blockOffset)
    reader.skip(4) // "MDL0"
    val blockSize = reader.u32()
    reader.skip(1)
    val num = reader.u8()
    val models = ArrayList<NdsNsbmdModel>(num)
    repeat(num) { models.add(NdsNsbmdModel()) }

    reader.skip(10 + 4 + num * 4)
    val modelOffsets = IntArray(num)
    for (i in 0 until num) modelOffsets[i] = reader.u32() + blockOffset
    for (i in 0 until num) reader.name()

    reader.u32() // totalsize
    val codeoffsetBase = reader.u32()
    val texpaloffsetBase = reader.u32()
    val polyoffsetBase = reader.u32()
    reader.u32() // polyend
    reader.skip(4)
    val matnum = reader.u8()
    val polynum = reader.u8()
    val laststack = reader.u8()
    reader.skip(1)
    val modelScale = reader.i32() / 4096f
    reader.skip(4) // boundscale
    reader.skip(8) // vertex/surface/triangle/quad counts
    reader.skip(12) // bounding box (6 x i16)

    val mod = models[0]
    mod.modelScale = modelScale
    mod.lastStackId = laststack

    val modelOffset = modelOffsets[0]
    val codeoffset = codeoffsetBase + modelOffset
    val texpaloffset = texpaloffsetBase + modelOffset
    val polyoffset = polyoffsetBase + modelOffset
    mod.codeoffset = codeoffset
    mod.texpaloffset = texpaloffset

    // ---- Objects ----
    reader.seek(modelOffset)
    reader.skip(5 * 4 + 4 + 2 + 38)
    val objDatabase = reader.pos
    reader.skip(1)
    val objnum = reader.u8()
    reader.skip(14 + objnum * 4)
    val objdataoffset = IntArray(objnum)
    for (j in 0 until objnum) objdataoffset[j] = reader.u32() + objDatabase
    for (j in 0 until objnum) reader.name()
    for (j in 0 until objnum) mod.objects.add(NdsNsbmdObject())
    for (j in 0 until objnum) {
      val size = if (j + 1 < objnum) objdataoffset[j + 1] - objdataoffset[j] else codeoffset - objdataoffset[j]
      if (size <= 4) continue
      reader.seek(objdataoffset[j])
      parseNsbmdObject(reader, mod.objects[j], modelScale)
    }

    // ---- Materials ----
    reader.seek(texpaloffset)
    val texoffset = reader.u16() + texpaloffset
    val paloffset = reader.u16() + texpaloffset
    repeat(matnum + 1) { mod.materials.add(NdsNsbmdMaterial()) }
    reader.seek(texpaloffset + 4 + 16 + matnum * 4)
    for (j in 0 until matnum) {
      val blockPtr = reader.pos
      val r = reader.u32() + texpaloffset
      reader.seek(r)
      reader.skip(2) // itemTag
      reader.skip(2) // section size
      val unknown1 = reader.i32()
      reader.skip(4) // unknown2
      val unknown3 = reader.i32()
      reader.skip(4)
      reader.skip(2)
      val texImageParam = reader.u16()
      reader.skip(4)
      reader.skip(2) // texture palette base
      val materialFlags = reader.u16()
      val matWidth = reader.i16()
      val matHeight = reader.i16()
      reader.skip(4) // magnification W
      reader.skip(4) // magnification H

      val mat = mod.materials[j]
      mat.repeatS = texImageParam and 1
      mat.repeatT = (texImageParam shr 1) and 1
      mat.flipS = (texImageParam shr 2) and 1
      mat.flipT = (texImageParam shr 3) and 1
      // Texture SRT values are optional and their presence is described by the
      // Nitro material flags.  The texture-generation mode in TEXIMAGE_PARAM
      // does not describe the byte layout: treating it that way makes HGSS's
      // omitted scale fields read as translation/rotation values.
      if ((materialFlags and 0x0001) != 0) { // NNS_G3D_MATFLAG_TEXMTX_USE
        if ((materialFlags and 0x0002) == 0) { // scale is not implicitly one
          mat.scaleS = reader.i32() / 4096f
          mat.scaleT = reader.i32() / 4096f
        }
        if ((materialFlags and 0x0004) == 0) reader.skip(4) // sinR, cosR (fx16)
        if ((materialFlags and 0x0008) == 0) reader.skip(8) // transS, transT (fx32)
      }
      mat.width = 8 shl ((texImageParam shr 4) and 7)
      mat.height = 8 shl ((texImageParam shr 7) and 7)
      mat.diffuseColor = bgr15ToColor(unknown1 and 0x7FFF)
      mat.alpha = (unknown3 shr 16) and 31
      mat.format = (texImageParam shr 10) and 7
      mat.width = matWidth
      mat.height = matHeight
      reader.seek(blockPtr + 4)
    }
    for (j in 0 until matnum) reader.name()

    // ---- Material texture names (model-side texture section) ----
    reader.seek(texoffset)
    reader.skip(1)
    val modelTexnum = reader.u8()
    if (modelTexnum > 0) {
      reader.seek(texoffset + 2 + 14 + modelTexnum * 4)
      for (j in 0 until modelTexnum) {
        val flags = reader.u32()
        val numPairs = (flags shr 16) and 0xF
        val blockPtr = reader.pos
        val texMatIds = mutableListOf<Int>()
        reader.seek((flags and 0xFFFF) + texpaloffset)
        for (k in 0 until numPairs) texMatIds += reader.u8()
        reader.seek(blockPtr)
        mod.textureDefs += NdsNsbmdTexDef(texMatIds, "")
      }
      for (j in 0 until modelTexnum) {
        val name = reader.name()
        mod.textureDefs.getOrNull(j)?.texname = name
      }
    }

    // ---- Material palette names (model-side palette section) ----
    reader.seek(paloffset)
    reader.skip(1)
    val modelPalnum = reader.u8()
    if (modelPalnum > 0) {
      reader.seek(paloffset + 2 + 14 + modelPalnum * 4)
      // Each palette's flags list which materials reference it (DSPRE palmatid membership).
      for (j in 0 until modelPalnum) {
        val flags = reader.u32()
        val numPairs = (flags shr 16) and 0xF
        val blockPtr = reader.pos
        val palMatIds = mutableListOf<Int>()
        reader.seek((flags and 0xFFFF) + texpaloffset)
        for (k in 0 until numPairs) palMatIds += reader.u8()
        reader.seek(blockPtr)
        mod.paletteDefs += NdsNsbmdPalDef(palMatIds, "")
      }
      for (j in 0 until modelPalnum) {
        val name = reader.name()
        mod.paletteDefs.getOrNull(j)?.palname = name
      }
    }

    // ---- Polygons ----
    reader.seek(polyoffset)
    reader.skip(1)
    reader.u8()
    repeat(polynum + 1) { mod.polygons.add(NdsNsbmdPolygon()) }
    reader.skip(14 + polynum * 4)
    val polyOffsets = IntArray(polynum)
    for (j in 0 until polynum) polyOffsets[j] = reader.u32() + polyoffset
    for (j in 0 until polynum) mod.polygons[j].name = reader.name()
    val polyDataSize = IntArray(polynum)
    for (j in 0 until polynum) {
      reader.skip(4)
      reader.skip(4)
      polyOffsets[j] += reader.u32()
      polyDataSize[j] = reader.u32()
    }
    for (j in 0 until polynum) {
      reader.seek(polyOffsets[j])
      val n = polyDataSize[j].coerceAtMost(reader.remaining())
      mod.polygons[j].polyData = reader.data.copyOfRange(reader.pos, reader.pos + n)
    }

    // ---- Bind polygons to materials/joints ----
    decodeCode(reader, mod)
    return mod
  }

  private fun parseNsbmdObject(reader: NsBmdReader, obj: NdsNsbmdObject, modelScale: Float) {
    val v = reader.u16()
    val divide = sign(reader.i16(), 16)
    obj.stackId = (v shr 12) and 0xF
    val t = NdsMtx(); t.loadIdentity()
    val r = NdsMtx(); r.loadIdentity()
    val s = NdsMtx(); s.loadIdentity()
    if ((v and 1) == 0) {
      val x = reader.i32() / 4096f / modelScale
      val y = reader.i32() / 4096f / modelScale
      val z = reader.i32() / 4096f / modelScale
      t.translate(x, y, z)
    }
    if (((v shr 3) and 1) == 1) {
      val a = sign(reader.i16(), 16) / 4096f
      val b = sign(reader.i16(), 16) / 4096f
      val pivot = (v shr 4) and 0xF
      val neg = (v shr 8) and 0xF
      r.copyTo(mtxPivot(a, b, pivot, neg))
    }
    if ((v shr 1 and 1) == 0 && (v shr 3 and 1) == 0) {
      val arr = FloatArray(16)
      arr[0] = 1f; arr[5] = 1f; arr[10] = 1f; arr[15] = 1f
      val rotate = FloatArray(8)
      for (j in 0 until 8) rotate[j] = sign(reader.i16(), 16) / 4096f
      arr[0] = divide / 4096f
      arr[1] = rotate[0]; arr[2] = rotate[1]
      arr[4] = rotate[2]; arr[5] = rotate[3]; arr[6] = rotate[4]
      arr[8] = rotate[5]; arr[9] = rotate[6]; arr[10] = rotate[7]
      r.copyTo(NdsMtx().also { System.arraycopy(arr, 0, it.m, 0, 16) })
    }
    if ((v shr 2 and 1) == 0) {
      s.scale(reader.i32() / 4096f, reader.i32() / 4096f, reader.i32() / 4096f)
    }
    var m = NdsMtx(); m.loadIdentity()
    m = m.multMatrix(t)
    m = m.multMatrix(r)
    m = m.multMatrix(s)
    obj.materix = m
  }

  private fun mtxPivot(a: Float, b: Float, pv: Int, neg: Int): NdsMtx {
    val data = FloatArray(16)
    data[15] = 1f
    var one = 1f
    var a2 = a
    var b2 = b
    if (neg and 1 == 1) one = -1f
    if (neg and 2 == 2) b2 = -b2
    if (neg and 4 == 4) a2 = -a2
    when (pv) {
      0 -> { data[0] = one; data[5] = a; data[6] = b; data[9] = b2; data[10] = a2 }
      1 -> { data[1] = one; data[4] = a; data[6] = b; data[8] = b2; data[10] = a2 }
      2 -> { data[2] = one; data[4] = a; data[5] = b; data[8] = b2; data[9] = a2 }
      3 -> { data[4] = one; data[1] = a; data[2] = b; data[9] = b2; data[10] = a2 }
      4 -> { data[5] = one; data[0] = a; data[2] = b; data[8] = b2; data[10] = a2 }
      5 -> { data[6] = one; data[0] = a; data[1] = b; data[8] = b2; data[9] = a2 }
      6 -> { data[8] = one; data[1] = a; data[2] = b; data[5] = b2; data[6] = a2 }
      7 -> { data[9] = one; data[0] = a; data[2] = b; data[4] = b2; data[6] = a2 }
      8 -> { data[10] = one; data[0] = a; data[1] = b; data[4] = b2; data[5] = a2 }
      9 -> { data[0] = -a }
    }
    return NdsMtx().also { System.arraycopy(data, 0, it.m, 0, 16) }
  }

  private fun bgr15ToColor(v: Int): Int {
    val r = (v and 31) * 255 / 31
    val g = ((v shr 5) and 31) * 255 / 31
    val b = ((v shr 10) and 31) * 255 / 31
    return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
  }

  /** Builds the per-object matrix stack (RenderModel matrix prep in DSPRE). */
  private fun buildMatrixStack(model: NdsNsbmdModel) {
    for (obj in model.objects) {
      if (obj.restoreId != -1 && obj.restoreId in model.matrixStack.indices) {
        model.matrixStack[obj.restoreId].copyTo(model.matrixStack[obj.stackId.let { if (it >= 0) it else 0 }])
      }
      if (obj.stackId != -1) {
        var m = NdsMtx(); m.loadIdentity()
        if (obj.restoreId != -1 && obj.restoreId in model.matrixStack.indices) {
          m = model.matrixStack[obj.restoreId].clone()
        }
        m = m.multMatrix(obj.materix)
        if (obj.stackId in model.matrixStack.indices) {
          m.copyTo(model.matrixStack[obj.stackId])
        }
      }
    }
  }

  /** Decodes the polygon/material binding command stream. */
  private fun decodeCode(reader: NsBmdReader, mod: NdsNsbmdModel) {
    reader.seek(mod.codeoffset)
    var polyStack = -1
    var polyStack2 = -1
    var curJoint = -1
    var matId = -1
    var emptyStack = mod.lastStackId
    while (reader.pos < mod.texpaloffset) {
      when (val c = reader.u8()) {
        0x06 -> {
          val d = reader.u8(); val e = reader.u8(); reader.u8()
          val obj = mod.objects.getOrNull(d) ?: continue
          obj.parentId = e
          emptyStack++
          obj.stackId = emptyStack
          polyStack2 = emptyStack
          obj.restoreId = -1
        }
        0x26 -> {
          val d = reader.u8(); val e = reader.u8(); reader.u8(); val g = reader.u8()
          val obj = mod.objects.getOrNull(d) ?: continue
          obj.parentId = e
          obj.stackId = g
          obj.restoreId = -1
          polyStack2 = g
        }
        0x46 -> {
          val d = reader.u8(); val e = reader.u8(); reader.u8(); val g = reader.u8()
          val obj = mod.objects.getOrNull(d) ?: continue
          obj.parentId = e
          emptyStack++
          obj.stackId = emptyStack
          obj.restoreId = g
          polyStack2 = emptyStack
        }
        0x66 -> {
          val d = reader.u8(); val e = reader.u8(); reader.u8(); val g = reader.u8(); val h = reader.u8()
          val obj = mod.objects.getOrNull(d) ?: continue
          obj.parentId = e
          obj.stackId = g
          obj.restoreId = h
          polyStack2 = g
        }
        0x02 -> {
          val d = reader.u8(); val e = reader.u8()
          curJoint = d
          mod.objects.getOrNull(d)?.let { it.visible = e == 1 }
        }
        0x03 -> polyStack = reader.u8()
        0x07, 0x08 -> reader.u8()
        0x09 -> {
          polyStack = reader.u8()
          val e = reader.u8()
          reader.skip(e * 3 + 1)
        }
        0x0B, 0x2B -> {}
        0x04, 0x24, 0x44 -> matId = reader.u8()
        0x05 -> {
          val d = reader.u8()
          val poly = mod.polygons.getOrNull(d)
          if (poly != null) {
            poly.matId = matId
            poly.stackId = if (polyStack != -1) polyStack else polyStack2
            poly.jointId = curJoint
          }
          matId = -1
        }
        0x0C -> reader.u8()
        0x01 -> return
        0x00 -> {}
        else -> return
      }
    }
  }

  /** Decodes polygon geometry commands into triangles. */
  internal fun decodePolygon(poly: NdsNsbmdPolygon, model: NdsNsbmdModel): List<NdsTri> {
    val data = poly.polyData
    if (data.isEmpty()) return emptyList()
    val reader = NsBmdReader(data)
    val out = mutableListOf<NdsTri>()
    val points = mutableListOf<FloatArray>()
    val colors = mutableListOf<Int>()
    val uvs = mutableListOf<FloatArray>()
    val currentMatrix = NdsMtx()
    if (poly.stackId in model.matrixStack.indices) {
      model.matrixStack[poly.stackId].copyTo(currentMatrix)
    } else {
      currentMatrix.loadIdentity()
    }
    val mat = model.materials.getOrNull(poly.matId)
    // DSPRE's MatchTextures: find the texture/palette whose texmatid/palmatid list contains this
    // polygon's material id, then use its name (which matches the external pack by name).
    val matTexname =
        model.textureDefs.firstOrNull { poly.matId in it.texmatid }?.texname
            ?: mat?.texname ?: ""
    val matPalname =
        model.paletteDefs.firstOrNull { poly.matId in it.palmatid }?.palname
            ?: mat?.palname ?: ""
    val materialAlpha = ((mat?.alpha ?: 31) * 255 + 15) / 31
    var currentColor =
        ((materialAlpha and 0xFF) shl 24) or
            ((mat?.diffuseColor ?: 0xFFFFFFFF.toInt()) and 0x00FFFFFF)
    val matScaleS = mat?.scaleS ?: 1f
    val matScaleT = mat?.scaleT ?: 1f
    val matRepeatS = mat?.repeatS == 1
    val matRepeatT = mat?.repeatT == 1
    val matFlipS = mat?.flipS == 1
    val matFlipT = mat?.flipT == 1
    var currentUv = floatArrayOf(0f, 0f)
    // Persistent local-space last vertex (DSPRE's vtx_state): survives primitive (0x41)
    // boundaries so relative vertex commands keep the correct base.
    val lastVtx = floatArrayOf(0f, 0f, 0f)
    fun setLocal(v: FloatArray) {
      lastVtx[0] = v[0]; lastVtx[1] = v[1]; lastVtx[2] = v[2]
    }
    var typ = -1
    while (reader.pos < data.size) {
      // Commands are packed four-opcodes-then-params (DSPRE's GXFIFO reader).
      val cmd = IntArray(4)
      for (i in 0 until 4) {
        cmd[i] = if (reader.pos < data.size) reader.u8() else 0xFF
      }
      for (i in 0 until 4) {
        if (reader.pos >= data.size) break
        when (val c = cmd[i]) {
        0x00 -> {}
        0x10, 0x12, 0x13 -> reader.skip(4)
        0x11 -> {}
        0x14 -> {
          val stack = reader.u32() and 0x1F
          if (stack in model.matrixStack.indices) model.matrixStack[stack].copyTo(currentMatrix)
        }
        0x15 -> currentMatrix.loadIdentity()
        0x16 -> { for (j in 0 until 16) currentMatrix.m[j] = reader.i32() / 4096f }
        0x17 -> { for (j in 0 until 4) for (k in 0 until 3) currentMatrix.m[k * 4 + j] = reader.i32() / 4096f }
        0x18 -> {
          val f = NdsMtx(); f.loadIdentity()
          for (j in 0 until 16) f.m[j] = reader.i32() / 4096f
          System.arraycopy(currentMatrix.multMatrix(f).m, 0, currentMatrix.m, 0, 16)
        }
        0x19 -> {
          val f = NdsMtx(); f.loadIdentity()
          for (j in 0 until 4) for (k in 0 until 3) f.m[k * 4 + j] = reader.i32() / 4096f
          System.arraycopy(currentMatrix.multMatrix(f).m, 0, currentMatrix.m, 0, 16)
        }
        0x1A -> {
          val f = NdsMtx(); f.loadIdentity()
          for (j in 0 until 3) for (k in 0 until 3) f.m[k * 4 + j] = reader.i32() / 4096f
          System.arraycopy(currentMatrix.multMatrix(f).m, 0, currentMatrix.m, 0, 16)
        }
        0x1B -> {
          val x = reader.i32(); val y = reader.i32(); val z = reader.i32()
          currentMatrix.scale(x / 4096f / model.modelScale, y / 4096f / model.modelScale, z / 4096f / model.modelScale)
        }
        0x1C -> {
          val x = reader.i32(); val y = reader.i32(); val z = reader.i32()
          currentMatrix.translate(sign(x, 32) / 4096f / model.modelScale, sign(y, 32) / 4096f / model.modelScale, sign(z, 32) / 4096f / model.modelScale)
        }
        0x20 -> {
          val rgb = reader.u32()
          val r = (rgb shr 0) and 0x1F
          val g = (rgb shr 5) and 0x1F
          val b = (rgb shr 10) and 0x1F
          currentColor =
              (currentColor and 0xFF000000.toInt()) or
                  ((r * 255 / 31) shl 16) or
                  ((g * 255 / 31) shl 8) or
                  (b * 255 / 31)
        }
        0x21 -> reader.skip(4)
        0x22 -> {
          val st = reader.u32()
          val s = sign((st shr 0) and 0xFFFF, 16)
          val t = sign((st shr 16) and 0xFFFF, 16)
          currentUv = floatArrayOf(s / 16f, t / 16f)
        }
        0x23 -> {
          val p1 = reader.u32(); val p2 = reader.u32()
          val x = sign((p1 shr 0) and 0xFFFF, 16) / 4096f
          val y = sign((p1 shr 16) and 0xFFFF, 16) / 4096f
          val z = sign(p2 and 0xFFFF, 16) / 4096f
          setLocal(floatArrayOf(x, y, z))
          addVertex(points, colors, uvs, currentMatrix, lastVtx, currentColor, currentUv)
        }
        0x24 -> {
          val xyz = reader.u32()
          val x = sign((xyz shr 0) and 0x3FF, 10) / 64f
          val y = sign((xyz shr 10) and 0x3FF, 10) / 64f
          val z = sign((xyz shr 20) and 0x3FF, 10) / 64f
          setLocal(floatArrayOf(x, y, z))
          addVertex(points, colors, uvs, currentMatrix, lastVtx, currentColor, currentUv)
        }
        0x25 -> {
          val xy = reader.u32()
          val x = sign((xy shr 0) and 0xFFFF, 16) / 4096f
          val y = sign((xy shr 16) and 0xFFFF, 16) / 4096f
          setLocal(floatArrayOf(x, y, lastVtx[2]))
          addVertex(points, colors, uvs, currentMatrix, lastVtx, currentColor, currentUv)
        }
        0x26 -> {
          val xz = reader.u32()
          val x = sign((xz shr 0) and 0xFFFF, 16) / 4096f
          val z = sign((xz shr 16) and 0xFFFF, 16) / 4096f
          setLocal(floatArrayOf(x, lastVtx[1], z))
          addVertex(points, colors, uvs, currentMatrix, lastVtx, currentColor, currentUv)
        }
        0x27 -> {
          val yz = reader.u32()
          val y = sign((yz shr 0) and 0xFFFF, 16) / 4096f
          val z = sign((yz shr 16) and 0xFFFF, 16) / 4096f
          setLocal(floatArrayOf(lastVtx[0], y, z))
          addVertex(points, colors, uvs, currentMatrix, lastVtx, currentColor, currentUv)
        }
        0x28 -> {
          val xyz = reader.u32()
          val x = lastVtx[0] + sign((xyz shr 0) and 0x3FF, 10) / 4096f
          val y = lastVtx[1] + sign((xyz shr 10) and 0x3FF, 10) / 4096f
          val z = lastVtx[2] + sign((xyz shr 20) and 0x3FF, 10) / 4096f
          setLocal(floatArrayOf(x, y, z))
          addVertex(points, colors, uvs, currentMatrix, lastVtx, currentColor, currentUv)
        }
        0x29, 0x2A, 0x2B -> reader.skip(4)
        0x30, 0x31, 0x32, 0x33 -> reader.skip(4)
        0x34 -> reader.skip(128)
        0x40 -> typ = reader.u32()
        0x41 -> {
          emitPrimitives(
              out,
              points,
              colors,
              uvs,
              typ,
              matTexname,
              matPalname,
              matScaleS,
              matScaleT,
              matRepeatS,
              matRepeatT,
              matFlipS,
              matFlipT,
          )
          points.clear(); colors.clear(); uvs.clear()
        }
        0x50, 0x60 -> reader.skip(4)
        0x70 -> reader.skip(12)
        0x71 -> reader.skip(8)
        0x72 -> reader.skip(4)
        else -> {}
      }
      }
    }
    return out
  }

  private fun addVertex(
      points: MutableList<FloatArray>,
      colors: MutableList<Int>,
      uvs: MutableList<FloatArray>,
      matrix: NdsMtx,
      local: FloatArray,
      color: Int,
      uv: FloatArray,
  ) {
    points.add(matrix.multVector(local))
    colors.add(color)
    uvs.add(uv)
  }

  private fun emitPrimitives(
      out: MutableList<NdsTri>,
      points: MutableList<FloatArray>,
      colors: MutableList<Int>,
      uvs: MutableList<FloatArray>,
      typ: Int,
      texname: String,
      palname: String,
      scaleS: Float,
      scaleT: Float,
      repeatS: Boolean,
      repeatT: Boolean,
      flipS: Boolean,
      flipT: Boolean,
  ) {
    fun uv(i: Int): FloatArray = uvs.getOrElse(i) { floatArrayOf(0f, 0f) }
    fun tri(i: Int, j: Int, k: Int) {
      val a = points[i]; val b = points[j]; val c = points[k]
      val ua = uv(i); val ub = uv(j); val uc = uv(k)
      out.add(
          NdsTri(
              a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2],
              colors.getOrElse(i) { 0xFFFFFFFF.toInt() },
              ua[0], ua[1], ub[0], ub[1], uc[0], uc[1],
              texname,
              palname,
              scaleS,
              scaleT,
              repeatS,
              repeatT,
              flipS,
              flipT,
          ))
    }
    when (typ) {
      0 -> { var i = 0; while (i + 2 < points.size) { tri(i, i + 1, i + 2); i += 3 } }
      1 -> { var i = 0; while (i + 3 < points.size) { tri(i, i + 1, i + 2); tri(i, i + 2, i + 3); i += 4 } }
      2 -> { var i = 0; while (i + 2 < points.size) { if (i % 2 == 0) tri(i, i + 1, i + 2) else tri(i + 1, i, i + 2); i++ } }
      3 -> {
        // Quad strip: each quad is (i, i+1, i+3, i+2); split along the i->i+3 diagonal.
        var i = 0
        while (i + 3 < points.size) {
          tri(i, i + 1, i + 3)
          tri(i, i + 3, i + 2)
          i += 2
        }
      }
    }
  }
}
