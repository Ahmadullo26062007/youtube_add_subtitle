package uz.ahmadullo.subtitle

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

// extension/lib.js dagi parser mantig'ining Kotlin ko'chirmasi.
// Android API ishlatilmaydi — JVM unit testlarda sinaladi.

data class Cue(val s: Double, val e: Double, val t: String)

data class ParsedSubtitle(val format: String, val cues: List<Cue>, val encoding: String)

/** Matn bo'lagi va uning uslubi (<i>, <b>, <u>). */
data class Segment(
    val text: String,
    val italic: Boolean,
    val bold: Boolean,
    val underline: Boolean
)

object SubtitleParser {

    // --------------------------------------------------
    // Faylni o'qish (kodlashni avtomatik aniqlash)
    // --------------------------------------------------

    fun decode(bytes: ByteArray): Pair<String, String> {
        // Ko'p SRT fayllar UTF-8 emas (Windows-1251 / 1254). Avval UTF-8 ni
        // qat'iy rejimda sinaymiz — xato bersa, boshqa kodlashlarga o'tamiz.
        try {
            val text = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
            return text to "utf-8"
        } catch (e: CharacterCodingException) {
            // davom etamiz
        }

        for (name in listOf("windows-1251", "windows-1254", "windows-1252")) {
            try {
                return String(bytes, Charset.forName(name)) to name
            } catch (e: Exception) {
                // davom etamiz
            }
        }

        return String(bytes, Charsets.UTF_8) to "utf-8"
    }

    fun parseBytes(bytes: ByteArray): ParsedSubtitle {
        val (text, encoding) = decode(bytes)
        val (format, cues) = parse(text)
        return ParsedSubtitle(format, cues, encoding)
    }

    // --------------------------------------------------
    // Vaqt
    // --------------------------------------------------

    private val TIME_RE = Regex("""^(?:(\d+):)?(\d{1,2}):(\d{1,2})(?:[.,](\d{1,3}))?$""")

    // 00:01:05,230 / 00:01:05.230 / 01:05.230 / 1:05:03
    fun parseTime(value: String): Double? {
        val m = TIME_RE.find(value.trim()) ?: return null

        val hours = m.groupValues[1].ifEmpty { "0" }.toLong()
        val minutes = m.groupValues[2].toLong()
        val seconds = m.groupValues[3].toLong()
        val ms = m.groupValues[4].ifEmpty { "0" }.padEnd(3, '0').toLong()

        return hours * 3600.0 + minutes * 60 + seconds + ms / 1000.0
    }

    fun formatOffset(seconds: Double): String {
        val sign = if (seconds > 0) "+" else ""
        return sign + String.format(java.util.Locale.US, "%.1f", seconds) + "s"
    }

    // --------------------------------------------------
    // Parser — SRT va VTT ikkalasi uchun
    // --------------------------------------------------

    fun detectFormat(text: String): String {
        if (Regex("""^\s*WEBVTT""").containsMatchIn(text)) return "vtt"

        // SRT vergul ishlatadi (00:00:05,230), VTT nuqta (00:00:05.230)
        if (Regex("""\d:\d{2}:\d{2},\d{1,3}""").containsMatchIn(text)) return "srt"
        if (Regex("""\d:\d{2}[:.]\d{2}\.\d{1,3}""").containsMatchIn(text)) return "vtt"

        return "srt"
    }

    fun parse(raw: String): Pair<String, List<Cue>> {
        val text = raw
            .removePrefix("\uFEFF")
            .replace(Regex("""\r\n?"""), "\n")

        val format = detectFormat(text)
        val cues = ArrayList<Cue>()

        for (block in text.split(Regex("""\n{2,}"""))) {
            val lines = block.split("\n").filter { it.isNotBlank() }
            if (lines.isEmpty()) continue

            // WEBVTT sarlavhasi, NOTE / STYLE / REGION bloklari — "-->" yo'q
            val timeIndex = lines.indexOfFirst { it.contains("-->") }
            if (timeIndex == -1) continue

            val parts = lines[timeIndex].split("-->")
            if (parts.size < 2) continue

            val start = parseTime(parts[0])

            // VTT'da vaqtdan keyin sozlamalar bo'lishi mumkin:
            // "00:00:07.330 align:middle position:50%"
            val end = parseTime(parts[1].trim().split(Regex("""\s+"""))[0])

            if (start == null || end == null) continue

            val body = lines.subList(timeIndex + 1, lines.size).joinToString("\n").trim()
            if (body.isEmpty()) continue

            cues.add(Cue(round3(start), round3(maxOf(end, start + 0.2)), body))
        }

        cues.sortWith(compareBy<Cue> { it.s }.thenBy { it.e })

        return format to cues
    }

    private fun round3(v: Double) = Math.round(v * 1000) / 1000.0

    // --------------------------------------------------
    // Vaqt bo'yicha qidirish
    // --------------------------------------------------

    fun activeCues(cues: List<Cue>, time: Double): List<Cue> {
        if (cues.isEmpty()) return emptyList()

        // start <= time bo'lgan oxirgi cue
        var left = 0
        var right = cues.size - 1
        var found = -1

        while (left <= right) {
            val mid = (left + right) ushr 1
            if (cues[mid].s <= time) {
                found = mid
                left = mid + 1
            } else {
                right = mid - 1
            }
        }

        if (found == -1) return emptyList()

        // VTT'da cue'lar ustma-ust tushishi mumkin — bir nechtasini tekshiramiz
        val result = ArrayList<Cue>()
        var i = found
        while (i >= 0 && i > found - 8) {
            if (cues[i].e > time) result.add(0, cues[i])
            i--
        }

        return if (result.size > 3) result.subList(result.size - 3, result.size) else result
    }

    // --------------------------------------------------
    // Teglar (<i>, <b>, <u> saqlanadi, qolgani olib tashlanadi)
    // --------------------------------------------------

    private val ALLOWED_TAGS = mapOf(
        "i" to "i", "b" to "b", "u" to "u", "em" to "i", "strong" to "b"
    )

    private val ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00a0"
    )

    private val ENTITY_RE = Regex("""&(#x[0-9a-fA-F]+|#\d+|[a-zA-Z]+);""")

    // Teglar, VTT vaqt belgilari (<00:00:01.000>) va ASS teglari ({\i1})
    private val TAG_RE = Regex("""<\s*(/?)\s*([a-zA-Z][a-zA-Z0-9]*)[^>]*>|<[^>]*>|\{\\[^}]*\}""")

    fun decodeEntities(value: String): String =
        ENTITY_RE.replace(value) { m ->
            val body = m.groupValues[1]
            if (body.startsWith("#")) {
                val code = if (body.length > 1 && (body[1] == 'x' || body[1] == 'X'))
                    body.substring(2).toIntOrNull(16)
                else
                    body.substring(1).toIntOrNull()
                if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code))
                else m.value
            } else {
                ENTITIES[body.lowercase()] ?: m.value
            }
        }

    /** Cue matnini uslubli bo'laklarga ajratadi. */
    fun segments(text: String): List<Segment> {
        val result = ArrayList<Segment>()
        val stack = ArrayList<String>()
        var last = 0

        fun push(chunk: String) {
            if (chunk.isEmpty()) return
            result.add(
                Segment(
                    decodeEntities(chunk),
                    italic = "i" in stack,
                    bold = "b" in stack,
                    underline = "u" in stack
                )
            )
        }

        for (m in TAG_RE.findAll(text)) {
            push(text.substring(last, m.range.first))
            last = m.range.last + 1

            // <v Speaker>, <c.yellow>, vaqt belgilari — olib tashlanadi
            val mapped = ALLOWED_TAGS[m.groupValues[2].lowercase()] ?: continue

            if (m.groupValues[1] == "/") {
                val idx = stack.lastIndexOf(mapped)
                if (idx >= 0) stack.removeAt(idx)
            } else {
                stack.add(mapped)
            }
        }

        push(text.substring(last))
        return result
    }
}
