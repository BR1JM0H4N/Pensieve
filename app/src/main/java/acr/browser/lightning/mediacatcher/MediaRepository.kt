package acr.browser.lightning.mediacatcher

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lightweight persistent store for MediaItems using SharedPreferences + JSON.
 */
class MediaRepository(context: Context) {

    companion object {
        private const val PREFS_NAME = "media_catcher_db"
        private const val KEY_ITEMS  = "items"
        private const val KEY_URLS   = "known_urls"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val knownUrls: MutableSet<String> by lazy {
        prefs.getStringSet(KEY_URLS, mutableSetOf())!!.toMutableSet()
    }

    fun hasUrl(url: String): Boolean = url in knownUrls

    fun save(item: MediaItem) {
        synchronized(this) {
            val array = loadJsonArray()
            array.put(mediaItemToJson(item))
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
                try { mediaItemFromJson(array.getJSONObject(it)) }
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

    private fun loadJsonArray(): JSONArray {
        val raw = prefs.getString(KEY_ITEMS, "[]") ?: "[]"
        return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }
}

// Top-level helpers — avoids kapt stub generation issues with companion extensions
internal fun mediaItemToJson(item: MediaItem): JSONObject = JSONObject().apply {
    put("id", item.id)
    put("url", item.url)
    put("localPath", item.localPath)
    put("mimeType", item.mimeType)
    put("mediaType", item.mediaType.name)
    put("sourcePageUrl", item.sourcePageUrl)
    put("fileSizeBytes", item.fileSizeBytes)
    put("capturedAt", item.capturedAt)
    put("fileName", item.fileName)
}

internal fun mediaItemFromJson(obj: JSONObject): MediaItem = MediaItem(
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
