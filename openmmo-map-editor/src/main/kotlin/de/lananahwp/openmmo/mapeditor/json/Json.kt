package de.lananahwp.openmmo.mapeditor.json

/**
 * Minimal dependency-free JSON parser. The decomp data files are machine-generated, so a
 * standards-following recursive descent parser is sufficient.
 */
sealed interface Json {
  data object JNull : Json
  data class JBool(val value: Boolean) : Json
  data class JNum(val value: Double) : Json
  data class JStr(val value: String) : Json
  data class JArr(val items: List<Json>) : Json
  data class JObj(val entries: LinkedHashMap<String, Json>) : Json

  fun asObj(): JObj? = this as? JObj
  fun asArr(): JArr? = this as? JArr
  fun asStr(): String? = (this as? JStr)?.value
  fun asInt(): Int? = (this as? JNum)?.value?.toInt()
  fun asBool(): Boolean? = (this as? JBool)?.value

  fun get(key: String): Json? = (this as? JObj)?.entries?.get(key)
  fun str(key: String): String? = get(key)?.asStr()
  fun int(key: String): Int? = get(key)?.asInt()
  fun arr(key: String): JArr? = get(key)?.asArr()
  fun obj(key: String): JObj? = get(key)?.asObj()
}

object JsonParser {
  fun parse(text: String): Json {
    val p = Parser(text)
    val value = p.parseValue()
    p.skipWs()
    if (!p.atEnd()) throw IllegalArgumentException("Trailing content at offset ${p.pos}")
    return value
  }

  private class Parser(private val text: String) {
    var pos = 0

    fun atEnd(): Boolean = pos >= text.length
    fun skipWs() {
      while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    private fun fail(msg: String): Nothing =
        throw IllegalArgumentException("$msg at offset $pos: ...${text.drop(pos).take(40)}")

    private fun expect(c: Char) {
      skipWs()
      if (pos >= text.length || text[pos] != c) fail("Expected '$c'")
      pos++
    }

    fun parseValue(): Json {
      skipWs()
      if (pos >= text.length) fail("Unexpected end of input")
      return when (text[pos]) {
        '{' -> parseObject()
        '[' -> parseArray()
        '"' -> Json.JStr(parseString())
        't' -> { expectWord("true"); Json.JBool(true) }
        'f' -> { expectWord("false"); Json.JBool(false) }
        'n' -> { expectWord("null"); Json.JNull }
        else -> parseNumber()
      }
    }

    private fun expectWord(word: String) {
      skipWs()
      if (!text.startsWith(word, pos)) fail("Expected '$word'")
      pos += word.length
    }

    private fun parseObject(): Json.JObj {
      expect('{')
      val map = LinkedHashMap<String, Json>()
      skipWs()
      if (pos < text.length && text[pos] == '}') { pos++; return Json.JObj(map) }
      while (true) {
        skipWs()
        if (pos >= text.length || text[pos] != '"') fail("Expected string key")
        val key = parseString()
        expect(':')
        map[key] = parseValue()
        skipWs()
        when {
          pos >= text.length -> fail("Unterminated object")
          text[pos] == ',' -> pos++
          text[pos] == '}' -> { pos++; return Json.JObj(map) }
          else -> fail("Expected ',' or '}'")
        }
      }
    }

    private fun parseArray(): Json.JArr {
      expect('[')
      val list = mutableListOf<Json>()
      skipWs()
      if (pos < text.length && text[pos] == ']') { pos++; return Json.JArr(list) }
      while (true) {
        list.add(parseValue())
        skipWs()
        when {
          pos >= text.length -> fail("Unterminated array")
          text[pos] == ',' -> pos++
          text[pos] == ']' -> { pos++; return Json.JArr(list) }
          else -> fail("Expected ',' or ']'")
        }
      }
    }

    private fun parseString(): String {
      expect('"')
      val sb = StringBuilder()
      while (true) {
        if (pos >= text.length) fail("Unterminated string")
        val c = text[pos++]
        when (c) {
          '"' -> return sb.toString()
          '\\' -> {
            if (pos >= text.length) fail("Unterminated escape")
            when (val e = text[pos++]) {
              '"' -> sb.append('"')
              '\\' -> sb.append('\\')
              '/' -> sb.append('/')
              'b' -> sb.append('\b')
              'f' -> sb.append('\u000C')
              'n' -> sb.append('\n')
              'r' -> sb.append('\r')
              't' -> sb.append('\t')
              'u' -> {
                if (pos + 4 > text.length) fail("Bad unicode escape")
                sb.append(text.substring(pos, pos + 4).toInt(16).toChar())
                pos += 4
              }
              else -> fail("Bad escape '\\$e'")
            }
          }
          else -> sb.append(c)
        }
      }
    }

    private fun parseNumber(): Json {
      skipWs()
      val start = pos
      if (pos < text.length && text[pos] == '-') pos++
      while (pos < text.length && text[pos].isDigit()) pos++
      if (pos < text.length && text[pos] == '.') {
        pos++
        while (pos < text.length && text[pos].isDigit()) pos++
      }
      if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
        pos++
        if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
        while (pos < text.length && text[pos].isDigit()) pos++
      }
      if (pos == start) fail("Expected a value")
      return Json.JNum(text.substring(start, pos).toDouble())
    }
  }
}

/** Serializes [Json] back to text, preserving key order. Numbers that are integral print without a decimal point. */
object JsonWriter {
  fun write(value: Json): String {
    val sb = StringBuilder()
    writeValue(sb, value)
    return sb.toString()
  }

  private fun writeValue(sb: StringBuilder, value: Json) {
    when (value) {
      is Json.JNull -> sb.append("null")
      is Json.JBool -> sb.append(if (value.value) "true" else "false")
      is Json.JNum -> {
        val d = value.value
        if (d == d.toLong().toDouble() && d in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()) {
          sb.append(d.toLong())
        } else {
          sb.append(d.toString())
        }
      }
      is Json.JStr -> writeString(sb, value.value)
      is Json.JArr -> {
        sb.append('[')
        value.items.forEachIndexed { i, item ->
          if (i > 0) sb.append(',')
          writeValue(sb, item)
        }
        sb.append(']')
      }
      is Json.JObj -> {
        sb.append('{')
        value.entries.entries.forEachIndexed { i, (k, v) ->
          if (i > 0) sb.append(',')
          writeString(sb, k)
          sb.append(':')
          writeValue(sb, v)
        }
        sb.append('}')
      }
    }
  }

  private fun writeString(sb: StringBuilder, s: String) {
    sb.append('"')
    for (c in s) {
      when (c) {
        '"' -> sb.append("\\\"")
        '\\' -> sb.append("\\\\")
        '\n' -> sb.append("\\n")
        '\r' -> sb.append("\\r")
        '\t' -> sb.append("\\t")
        '\b' -> sb.append("\\b")
        '\u000C' -> sb.append("\\f")
        else -> if (c < ' ') sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
      }
    }
    sb.append('"')
  }
}
