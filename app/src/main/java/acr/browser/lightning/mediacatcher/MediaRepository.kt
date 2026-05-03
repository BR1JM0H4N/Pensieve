package acr.browser.lightning.mediacatcher

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lightweight persistent store for MediaItems using SharedPreferences + JSON.
 * No Room/SQLite dependency needed — keeps the patch minimal.
 */
class MediaRepository(context: Context) {

    companion object {
        private const val PREFS_NAME = "media_catcher_db"
        private const val KEY_ITEMS  = "items"
        private const val KEY_URLS   = "known_urls"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Set of URLs already captured (fast dedup check) */
    private val knownUrls: MutableSet<String> by lazy {
        prefs.getStringSet(KEY_URLS, mutableSetOf())!!.toMutableSet()
    }

    fun hasUrl(url: String): Boolean = url in knownUrls

    fun save(item: MediaItem) {
        synchronized(this) {
            val array = loadJsonArray()
            array.put(item.toJson())
            knownUrls.add(item.url)
            prefs.edit()
                .putString(KEY_ITEMS, array.toString())
                .putStringSet(KEY_URLS, knownUrls)
                .apply()
        }
    }

    fun loadAll(): List<MediaItem> {
        return try {
            val array = loadJsonArray()
            (0 until array.length()).mapNotNull {
                try { MediaItem.fromJson(array.getJSONObject(it)) }
                catch (_: Exception) { null }
            }.sortedByDescending { it.capturedAt }
        } catch (_: Exception) { emptyList() }
    }

    fun delete(item: MediaItem) {
        synchronized(this) {
            val array = loadJsonArray()
            val filtered = JSONArray()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (obj.optLong("id") != item.id) filtered.put(obj)
            }
            knownUrls.remove(item.url)
            prefs.edit()
                .putString(KEY_ITEMS, filtered.toString())
                .putStringSet(KEY_URLS, knownUrls)
                .apply()
        }
    }

    fun deleteAll() {
        synchronized(this) {
            knownUrls.clear()
            prefs.edit()
                .putString(KEY_ITEMS, "[]")
                .putStringSet(KEY_URLS, emptySet())
                .apply()
        }
    }

    fun countByType(type: MediaType): Int =
        loadAll().count { it.mediaType == type }

    private fun loadJsonArray(): JSONArray {
        val raw = prefs.getString(KEY_ITEMS, "[]") ?: "[]"
        return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }

    // --- JSON serialization helpers on MediaItem ---
    private fun MediaItem.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("url", url)
        put("localPath", localPath)
        put("mimeType", mimeType)
        put("mediaType", mediaType.name)
        put("sourcePageUrl", sourcePageUrl)
        put("fileSizeBytes", fileSizeBytes)
        put("capturedAt", capturedAt)
        put("fileName", fileName)
    }

    companion object {
        fun MediaItem.Companion.fromJson(obj: JSONObject): MediaItem = MediaItem(
            id            = obj.getLong("id"),
            url           = obj.getString("url"),
            localPath     = obj.getString("localPath"),
            mimeType      = obj.getString("mimeType"),
            mediaType     = MediaType.valueOf(obj.getString("mediaType")),
            sourcePageUrl = obj.optString("sourcePageUrl", ""),
            fileSizeBytes = obj.getLong("fileSizeBytes"),
            capturedAt    = obj.getLong("capturedAt"),
            fileName      = obj.getString("fileName")
        )
    }
}
