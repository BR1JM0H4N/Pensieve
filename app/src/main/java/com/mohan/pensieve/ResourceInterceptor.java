package com.mohan.pensieve;

import android.content.Context;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Intercepts WebView resource requests.
 * When a media resource is detected, it is saved in background.
 * Returns null so the WebView still loads it normally — zero extra bandwidth.
 */
public class ResourceInterceptor {
    private static final String TAG = "ResourceInterceptor";
    private final MediaSaver saver;
    private final Set<String> seenUrls = new HashSet<>();

    // Skip these to avoid saving tracker pixels, tiny icons, etc.
    private static final Set<String> SKIP_EXTENSIONS = new HashSet<>(Arrays.asList(
            "ico", "svg", "woff", "woff2", "ttf", "eot", "css", "js", "json", "xml"
    ));

    public ResourceInterceptor(Context context) {
        this.saver = new MediaSaver(context);
    }

    /**
     * Call this from WebViewClient.shouldInterceptRequest().
     * Always returns null (let WebView handle the request normally).
     */
    public WebResourceResponse intercept(WebResourceRequest request) {
        if (request == null) return null;
        String url = request.getUrl().toString();
        processUrl(url, null);
        return null; // Let WebView load normally — NO extra download
    }

    public void processUrl(String url, String mimeTypeHint) {
        if (url == null || url.startsWith("data:") || url.startsWith("blob:")) return;
        if (seenUrls.contains(url)) return;
        seenUrls.add(url);

        // Determine MIME
        String mime = mimeTypeHint;
        if (mime == null || mime.isEmpty()) {
            mime = MediaSaver.guessMimeFromUrl(url);
        }

        // Skip non-media or ignored types
        if (!MediaSaver.isMediaMime(mime)) return;

        // Skip tiny images (likely icons/trackers) — can't check size without downloading
        // so we filter by extension
        String lower = url.toLowerCase();
        for (String ext : SKIP_EXTENSIONS) {
            if (lower.endsWith("." + ext)) return;
        }

        // Skip very small query-string URLs (often tracking pixels)
        if (url.length() < 20) return;

        Log.d(TAG, "Intercepted media: " + mime + " -> " + url);
        saver.saveFromUrl(url, mime, (filePath, fileName) ->
                Log.d(TAG, "Auto-saved: " + fileName));
    }

    public void shutdown() {
        saver.shutdown();
    }
}
