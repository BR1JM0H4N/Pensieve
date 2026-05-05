package com.mohan.pensieve;

import android.content.Context;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ResourceInterceptor {
    private static final String TAG = "ResourceInterceptor";
    private final MediaSaver saver;

    // Track seen URLs to avoid duplicates — use ConcurrentHashMap for thread safety
    private final Set<String> seenUrls = ConcurrentHashMap.newKeySet();

    // Non-media extensions to always skip
    private static final Set<String> SKIP_EXTENSIONS = new HashSet<>(Arrays.asList(
            "ico", "svg", "woff", "woff2", "ttf", "eot", "css", "js", "json",
            "xml", "vtt", "srt", "ttml", "dfxp"  // subtitle formats
    ));

    // Stream manifest/chunk extensions — need special handling
    private static final Set<String> STREAM_EXTENSIONS = new HashSet<>(Arrays.asList(
            "m3u8", "mpd"  // manifests
    ));

    // Chunk extensions — video/audio but part of a stream
    private static final Set<String> CHUNK_EXTENSIONS = new HashSet<>(Arrays.asList(
            "ts", "m4s", "m4v", "m4a", "fmp4", "cmf", "cmfa", "cmfv"
    ));

    public ResourceInterceptor(Context context) {
        this.saver = new MediaSaver(context);
    }

    public WebResourceResponse intercept(WebResourceRequest request) {
        if (request == null) return null;
        String url = request.getUrl().toString();
        // Pass request headers so saver can mimic the original request
        processUrl(url, null, request.getRequestHeaders());
        return null;
    }

    public void processUrl(String url, String mimeTypeHint) {
        processUrl(url, mimeTypeHint, null);
    }

    public void processUrl(String url, String mimeTypeHint, Map<String, String> headers) {
        if (url == null || url.startsWith("data:") || url.startsWith("blob:")) return;
        if (seenUrls.contains(url)) return;
        seenUrls.add(url);

        String urlLower = url.toLowerCase().split("\\?")[0]; // strip query params for ext check

        // Always skip non-media extensions
        for (String ext : SKIP_EXTENSIONS) {
            if (urlLower.endsWith("." + ext)) return;
        }

        // --- Detect what kind of URL this is ---

        // 1. Stream manifests (.m3u8 / .mpd) — save them, they're the index
        for (String ext : STREAM_EXTENSIONS) {
            if (urlLower.endsWith("." + ext) || urlLower.contains("." + ext + "?")) {
                Log.d(TAG, "Stream manifest: " + url);
                saver.saveStreamFile(url, ext, headers,
                        (path, name) -> Log.d(TAG, "Manifest saved: " + name));
                return;
            }
        }

        // 2. Init segments — CRITICAL, must be saved first and correctly named
        //    e.g. init.mp4, init_0.m4s, initialization.mp4, seg-init.m4s
        boolean isInit = urlLower.contains("init") &&
                (urlLower.endsWith(".mp4") || urlLower.endsWith(".m4s") ||
                 urlLower.endsWith(".m4a") || urlLower.endsWith(".m4v") ||
                 urlLower.endsWith(".cmf") || urlLower.endsWith(".cmfa") ||
                 urlLower.endsWith(".cmfv") || urlLower.endsWith(".fmp4"));
        if (isInit) {
            Log.d(TAG, "Init segment: " + url);
            saver.saveStreamFile(url, guessChunkExt(urlLower), headers,
                    (path, name) -> Log.d(TAG, "Init saved: " + name));
            return;
        }

        // 3. Chunk segments (.ts, .m4s, etc.)
        for (String ext : CHUNK_EXTENSIONS) {
            if (urlLower.endsWith("." + ext) || urlLower.contains("." + ext + "?")) {
                Log.d(TAG, "Chunk segment: " + url);
                saver.saveStreamFile(url, ext, headers,
                        (path, name) -> Log.d(TAG, "Chunk saved: " + name));
                return;
            }
        }

        // 4. Regular media files — determine MIME
        String mime = mimeTypeHint;
        if (mime == null || mime.isEmpty()) mime = MediaSaver.guessMimeFromUrl(url);
        if (!MediaSaver.isMediaMime(mime)) return;
        if (url.length() < 20) return;

        Log.d(TAG, "Media file: " + mime + " -> " + url);
        saver.saveFromUrl(url, mime, headers,
                (path, name) -> Log.d(TAG, "Saved: " + name));
    }

    private static String guessChunkExt(String urlLower) {
        if (urlLower.endsWith(".m4a") || urlLower.endsWith(".cmfa")) return "m4a";
        if (urlLower.endsWith(".m4v") || urlLower.endsWith(".cmfv")) return "m4v";
        if (urlLower.endsWith(".m4s") || urlLower.endsWith(".cmf"))  return "m4s";
        if (urlLower.endsWith(".fmp4")) return "fmp4";
        return "mp4";
    }

    public void shutdown() {
        saver.shutdown();
    }
}
