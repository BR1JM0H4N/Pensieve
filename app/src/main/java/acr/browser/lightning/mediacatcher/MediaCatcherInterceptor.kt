package acr.browser.lightning.mediacatcher

import android.content.Context
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * MediaCatcherInterceptor
 *
 * Hooks into LightningWebClient.shouldInterceptRequest().
 * It transparently proxies the request, tees the response bytes to disk
 * IF the Content-Type is image/video/audio — and returns the same bytes
 * back to WebView so the page renders normally.
 *
 * ✅ Zero extra bandwidth — bytes are downloaded ONCE, tapped mid-stream.
 * ✅ No CORS issues — runs at native network layer, below JS sandbox.
 */
class MediaCatcherInterceptor(private val context: Context) {

    companion object {
        private const val TAG = "MediaCatcher"

        // MIME prefixes we care about
        private val CAPTURE_MIME_PREFIXES = listOf(
            "image/",
            "video/",
            "audio/"
        )

        // URL extension hints (fallback when Content-Type header is absent)
        private val IMAGE_EXTS = setOf("jpg","jpeg","png","gif","webp","bmp","svg","ico","avif","heic")
        private val VIDEO_EXTS = setOf("mp4","webm","ogg","mkv","mov","avi","m4v","3gp","ts","m3u8")
        private val AUDIO_EXTS = setOf("mp3","aac","wav","flac","m4a","opus","oga","weba")

        // Min sizes to avoid tiny icons/tracking pixels
        private const val MIN_IMAGE_BYTES = 2048L    // 2 KB
        private const val MIN_VIDEO_BYTES = 10240L   // 10 KB
        private const val MIN_AUDIO_BYTES = 4096L    // 4 KB
    }

    private val executor = Executors.newFixedThreadPool(3)
    private val repo = MediaRepository(context)

    /** Storage dir inside app private files — no WRITE_EXTERNAL needed on Android 10+ */
    private val saveDir: File by lazy {
        File(context.filesDir, "mediacatcher").also { it.mkdirs() }
    }

    /**
     * Call this from LightningWebClient.shouldInterceptRequest().
     * Returns a WebResourceResponse if we decide to intercept, or null to let
     * WebView handle it normally (pass-through for non-media).
     */
    fun intercept(
        view: WebView,
        request: WebResourceRequest,
        currentPageUrl: String
    ): WebResourceResponse? {
        val urlStr = request.url.toString()

        // Only intercept GET requests
        if (request.method != "GET") return null

        // Quick pre-filter by URL extension before making a network call
        val ext = urlStr.substringAfterLast('.').substringBefore('?').lowercase()
        val looksLikeMedia = ext in IMAGE_EXTS || ext in VIDEO_EXTS || ext in AUDIO_EXTS

        // We'll always attempt for likely-media URLs; for others we skip
        // to avoid adding latency to every JS/CSS request.
        if (!looksLikeMedia && !urlStr.contains("image") && !urlStr.contains("video")
            && !urlStr.contains("audio") && !urlStr.contains("media")) {
            return null
        }

        return try {
            fetchAndMaybeSave(urlStr, request, currentPageUrl)
        } catch (e: Exception) {
            Log.w(TAG, "intercept error for $urlStr: ${e.message}")
            null // fall back to normal WebView loading
        }
    }

    private fun fetchAndMaybeSave(
        urlStr: String,
        request: WebResourceRequest,
        pageUrl: String
    ): WebResourceResponse? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 15000
            instanceFollowRedirects = true
            // Forward original request headers (cookies, referer, etc.) so
            // the server thinks the request came from the browser normally
            request.requestHeaders?.forEach { (k, v) ->
                try { setRequestProperty(k, v) } catch (_: Exception) {}
            }
        }

        val responseCode = conn.responseCode
        if (responseCode !in 200..299) {
            conn.disconnect()
            return null
        }

        val contentType = conn.contentType?.lowercase() ?: ""
        val mimeType = contentType.substringBefore(';').trim()

        // Check if this is media we want
        val isMedia = CAPTURE_MIME_PREFIXES.any { mimeType.startsWith(it) }
        if (!isMedia) {
            conn.disconnect()
            return null
        }

        val mediaType = when {
            mimeType.startsWith("image/") -> MediaType.IMAGE
            mimeType.startsWith("video/") -> MediaType.VIDEO
            mimeType.startsWith("audio/") -> MediaType.AUDIO
            else -> MediaType.OTHER
        }

        // Read the response body into a byte array (tee to disk)
        val bodyBytes = conn.inputStream.use { it.readBytes() }
        conn.disconnect()

        // Size filter
        val minSize = when (mediaType) {
            MediaType.IMAGE -> MIN_IMAGE_BYTES
            MediaType.VIDEO -> MIN_VIDEO_BYTES
            MediaType.AUDIO -> MIN_AUDIO_BYTES
            else -> MIN_IMAGE_BYTES
        }
        if (bodyBytes.size < minSize) {
            // Too small — return bytes to WebView but don't save
            return webResponse(mimeType, bodyBytes)
        }

        // Already saved this URL before?
        if (!repo.hasUrl(urlStr)) {
            executor.submit {
                saveToDisk(urlStr, mimeType, mediaType, bodyBytes, pageUrl)
            }
        }

        // Return the bytes we already have — WebView renders normally
        return webResponse(mimeType, bodyBytes)
    }

    private fun saveToDisk(
        urlStr: String,
        mimeType: String,
        mediaType: MediaType,
        bytes: ByteArray,
        pageUrl: String
    ) {
        try {
            val ext = extensionForMime(mimeType)
            val name = "${System.currentTimeMillis()}.$ext"
            val file = File(saveDir, name)
            file.writeBytes(bytes)

            val item = MediaItem(
                url = urlStr,
                localPath = file.absolutePath,
                mimeType = mimeType,
                mediaType = mediaType,
                sourcePageUrl = pageUrl,
                fileSizeBytes = bytes.size.toLong(),
                fileName = name
            )
            repo.save(item)
            Log.d(TAG, "Saved ${mediaType.name}: $name (${bytes.size} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "saveToDisk failed: ${e.message}")
        }
    }

    private fun webResponse(mimeType: String, bytes: ByteArray): WebResourceResponse {
        return WebResourceResponse(mimeType, "utf-8", ByteArrayInputStream(bytes))
    }

    private fun extensionForMime(mime: String): String = when {
        mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
        mime.contains("png")  -> "png"
        mime.contains("gif")  -> "gif"
        mime.contains("webp") -> "webp"
        mime.contains("svg")  -> "svg"
        mime.contains("mp4")  -> "mp4"
        mime.contains("webm") -> "webm"
        mime.contains("ogg")  -> "ogg"
        mime.contains("mp3")  || mime.contains("mpeg") -> "mp3"
        mime.contains("aac")  -> "aac"
        mime.contains("wav")  -> "wav"
        mime.contains("flac") -> "flac"
        mime.contains("m4a")  -> "m4a"
        mime.contains("opus") -> "opus"
        mime.contains("audio") -> "audio"
        mime.contains("video") -> "video"
        else -> "bin"
    }
}
