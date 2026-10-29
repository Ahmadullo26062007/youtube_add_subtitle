package uz.ahmadullo.subtitle

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Saqlangan subtitle'lar. Matn filesDir/subs/{id}.txt da, ro'yxat
 * SharedPreferences'da JSON sifatida.
 *
 * Har bir subtitle YouTube video nomiga (MediaSession title) bog'lanadi.
 * title bo'sh bo'lsa — keyingi ochilgan (bog'lanmagan) videoga ulanadi.
 */
class SubtitleStore(context: Context) {

    data class Record(
        val id: String,
        val name: String,
        val title: String,
        val offset: Double,
        val count: Int,
        val encoding: String,
        val addedAt: Long
    )

    private val dir = File(context.applicationContext.filesDir, "subs").apply { mkdirs() }

    val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(): List<Record> {
        val raw = prefs.getString(KEY_INDEX, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Record(
                    id = o.getString("id"),
                    name = o.optString("name"),
                    title = o.optString("title"),
                    offset = o.optDouble("offset", 0.0),
                    count = o.optInt("count"),
                    encoding = o.optString("encoding", "utf-8"),
                    addedAt = o.optLong("addedAt")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(records: List<Record>) {
        val arr = JSONArray()
        for (r in records) {
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("name", r.name)
                    .put("title", r.title)
                    .put("offset", r.offset)
                    .put("count", r.count)
                    .put("encoding", r.encoding)
                    .put("addedAt", r.addedAt)
            )
        }
        prefs.edit().putString(KEY_INDEX, arr.toString()).apply()
    }

    /** Yangi subtitle qo'shadi. Shu nomga bog'langan eskisi almashtiriladi. */
    fun add(name: String, title: String, parsed: ParsedSubtitle, text: String): Record {
        val cleanTitle = normalize(title)
        val record = Record(
            id = System.currentTimeMillis().toString(36),
            name = name,
            title = cleanTitle,
            offset = 0.0,
            count = parsed.cues.size,
            encoding = parsed.encoding,
            addedAt = System.currentTimeMillis()
        )

        File(dir, "${record.id}.txt").writeText(text)

        val others = list().filter { cleanTitle.isEmpty() || it.title != cleanTitle }
        val all = listOf(record) + others

        // Eng eskilarini o'chiramiz
        all.drop(MAX_SAVED).forEach { File(dir, "${it.id}.txt").delete() }
        save(all.take(MAX_SAVED))

        return record
    }

    fun remove(id: String) {
        File(dir, "$id.txt").delete()
        save(list().filter { it.id != id })
    }

    fun update(id: String, change: (Record) -> Record) {
        save(list().map { if (it.id == id) change(it) else it })
    }

    /** Subtitle'ni shu videoga ulaydi (boshqa yozuvlardagi shu nom olib tashlanadi). */
    fun bind(id: String, title: String) {
        val clean = normalize(title)
        save(list().map {
            when {
                it.id == id -> it.copy(title = clean)
                clean.isNotEmpty() && it.title == clean -> it.copy(title = "")
                else -> it
            }
        })
    }

    fun setOffset(id: String, offset: Double) = update(id) { it.copy(offset = offset) }

    /**
     * Video nomi bo'yicha subtitle topadi. Topilmasa — nomi bo'sh (kutilayotgan)
     * eng yangi yozuvni shu videoga ulaydi.
     */
    fun resolve(title: String): Record? {
        val clean = normalize(title)
        if (clean.isEmpty()) return null

        val records = list()
        records.firstOrNull { it.title == clean }?.let { return it }

        val pending = records.firstOrNull { it.title.isEmpty() } ?: return null
        bind(pending.id, clean)
        return pending.copy(title = clean)
    }

    fun loadCues(record: Record): List<Cue> {
        val file = File(dir, "${record.id}.txt")
        if (!file.exists()) return emptyList()
        return SubtitleParser.parse(file.readText()).second
    }

    companion object {
        const val PREFS = "subtitles"
        const val KEY_INDEX = "index"
        const val MAX_SAVED = 20

        fun normalize(title: String?) = title?.trim()?.replace(Regex("""\s+"""), " ") ?: ""
    }
}
