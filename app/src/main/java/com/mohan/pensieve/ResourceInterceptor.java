package com.mohan.pensieve;

import android.content.Context;
import android.util.Log;
import android.webkit.MimeTypeMap;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Intercepts WebView resource requests for media.
 *
 * KEY PRINCIPLE — Zero extra data:
 *   Old approach: return null → WebView downloads normally → saveFromUrl() downloads AGAIN (2×)
 *   New approach: we open one connection, wrap the stream in TeeInputStream,
 *                 return that stream TO the WebView — 1× download total.
 *
 * Also fixes:
 *   - Race condition: ConcurrentHashMap.add() is atomic
 *   - Wrong extension: reads real Content-Type from response headers
 *   - Corrupt files:  .tmp → atomic rename only on successful close()
 */
public class ResourceInterceptor {

    private static final String TAG = "ResourceInterceptor";
    private final MediaSaver saver;

    // Atomic seen-set: add() on a ConcurrentHashMap-backed set is thread-safe
    private final Set<String> seenUrls = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static final Set<String> SKIP_EXTENSIONS = new HashSet<>(Arrays.asList(
            "ico", "svg", "woff", "woff2", "ttf", "eot", "css", "js",
            "json", "xml", "vtt", "srt"
    ));

    // HLS / DASH manifests — let WebView handle these normally, we capture segments
    private static final Set<String> MANIFEST_EXTENSIONS = new HashSet<>(Arrays.asList(
            "m3u8", "mpd"
    ));

    private static final Set<String> SEGMENT_EXTENSIONS = new HashSet<>(Arrays.asList(
            "ts", "m4s", "fmp4", "mp4", "webm", "m4v", "m4a", "mp3",
            "ogg", "wav", "aac"
    ));

    public ResourceInterceptor(Context context) {
        this.saver = new MediaSaver(context);
    }

    /**
     * Call from WebViewClient.shouldInterceptRequest().
     * Returns a WebResourceResponse backed by a TeeInputStream for media URLs,
     * null for everything else (WebView handles those normally).
     */
    public WebResourceResponse intercept(WebResourceRequest request) {
        if (request == null) return null;
        return processUrl(request.getUrl().toString(), null, request);
    }

    /**
     * Called from the JS media-catcher for <video>/<audio> src attributes
     * discovered after page load. Only saves if the interceptor missed the URL.
     */
    public void processUrlFromJs(String url, String mimeTypeHint) {
        if (url == null || url.startsWith("data:") || url.startsWith("blob:")) return;
        if (seenUrls.contains(url)) return; // interceptor already handled it
        String mime = resolveMime(url, mimeTypeHint);
        if (mime == null || !MediaSaver.isMediaMime(mime)) return;
        if (isManifest(url)) return;
        if (seenUrls.add(url)) {
            // Fallback: one download for URLs the interceptor missed (rare)
            saver.saveFromUrl(url, mime,
                    (filePath, fileName) -> Log.d(TAG, "JS-saved: " + fileName));
        }
    }

    // ── Core tee logic ────────────────────────────────────────────────────────

    private WebResourceResponse processUrl(String url, String mimeTypeHint,
                                           WebResourceRequest originalRequest) {
        if (url == null || url.startsWith("data:") || url.startsWith("blob:")) return null;

        String mime = resolveMime(url, mimeTypeHint);
        if (mime == null || !MediaSaver.isMediaMime(mime)) return null;
        if (isSkipped(url)) return null;
        if (isManifest(url)) return null;

        // Atomic claim — only the first thread proceeds for this URL
        if (!seenUrls.add(url)) return null;

        if (saver.alreadySaved(url)) {
            Log.d(TAG, "Already saved, skipping tee: " + url);
            return null; // WebView loads from cache/network normally
        }

        try {
            HttpURLConnection conn = openConnection(url, originalRequest);
            int status = conn.getResponseCode();

            // Use the server's real Content-Type (fixes wrong-extension bug)
            String serverMime = conn.getContentType();
            if (serverMime != null && !serverMime.isEmpty()) {
                mime = serverMime.split(";")[0].trim();
            }

            String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            if (ext == null || ext.isEmpty()) ext = guessExtFromUrl(url);
            if (ext == null || ext.isEmpty()) ext = "bin";

            File dir = saver.getOrCreateDir(MediaSaver.getTypeFromMime(mime));
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            int hash = Math.abs(url.hashCode());
            File finalFile = new File(dir, hash + "_" + timestamp + "." + ext);
            File tmpFile   = new File(dir, hash + "_" + timestamp + "." + ext + ".tmp");

            InputStream networkStream = conn.getInputStream();
            TeeInputStream tee = new TeeInputStream(networkStream, tmpFile, finalFile,
                    (filePath, fileName) -> Log.d(TAG, "Tee-saved: " + fileName));

            // Pass response headers back to WebView so it can play correctly
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", mime);
            copyHeader(conn, headers, "Content-Length");
            copyHeader(conn, headers, "Accept-Ranges");
            copyHeader(conn, headers, "Content-Range");
            copyHeader(conn, headers, "Cache-Control");

            Log.d(TAG, "Tee-intercept: " + mime + " -> " + url);
            return new WebResourceResponse(mime, "binary", status, "OK", headers, tee);

        } catch (IOException e) {
            Log.e(TAG, "Tee setup failed: " + url + " — " + e.getMessage());
            seenUrls.remove(url); // allow WebView to retry normally
            return null;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private HttpURLConnection openConnection(String urlStr,
                                             WebResourceRequest original) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");

        // Forward key headers so CDNs don't reject the request
        if (original != null && original.getRequestHeaders() != null) {
            for (Map.Entry<String, String> h : original.getRequestHeaders().entrySet()) {
                String key = h.getKey();
                if (key.equalsIgnoreCase("Referer") ||
                    key.equalsIgnoreCase("Origin")  ||
                    key.equalsIgnoreCase("Range")    ||
                    key.equalsIgnoreCase("Accept")) {
                    conn.setRequestProperty(key, h.getValue());
                }
            }
        }
        conn.connect();
        return conn;
    }

    private void copyHeader(HttpURLConnection conn, Map<String, String> out, String name) {
        String val = conn.getHeaderField(name);
        if (val != null) out.put(name, val);
    }

    private String resolveMime(String url, String hint) {
        if (hint != null && !hint.isEmpty() && MediaSaver.isMediaMime(hint)) return hint;
        return MediaSaver.guessMimeFromUrl(url);
    }

    private boolean isSkipped(String url) {
        String lower = url.toLowerCase();
        for (String ext : SKIP_EXTENSIONS) {
            if (lower.contains("." + ext + "?") || lower.endsWith("." + ext)) return true;
        }
        return url.length() < 20;
    }

    private boolean isManifest(String url) {
        String lower = url.toLowerCase();
        for (String ext : MANIFEST_EXTENSIONS) {
            if (lower.contains("." + ext + "?") || lower.endsWith("." + ext)) return true;
        }
        return false;
    }

    private String guessExtFromUrl(String url) {
        String lower = url.toLowerCase();
        for (String ext : SEGMENT_EXTENSIONS) {
            if (lower.contains("." + ext + "?") || lower.endsWith("." + ext)) return ext;
        }
        return null;
    }

    public void shutdown() {
        saver.shutdown();
    }
}
