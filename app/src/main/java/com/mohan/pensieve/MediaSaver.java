package com.mohan.pensieve;

import android.content.Context;
import android.os.Environment;
import android.util.Log;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public class MediaSaver {
    private static final String TAG = "MediaSaver";
    private static final String BASE_DIR = "Pensieve";

    private final Context context;

    // Single-threaded executor per stream key prevents chunk interleaving/race conditions
    // Key = stream identifier (domain + path prefix), Value = dedicated executor
    private final ConcurrentHashMap<String, ExecutorService> streamExecutors = new ConcurrentHashMap<>();

    // General executor for non-stream files
    private final ExecutorService generalExecutor = Executors.newFixedThreadPool(3);

    // Per-stream sequence counter so chunks are named in arrival order
    private final ConcurrentHashMap<String, AtomicLong> streamCounters = new ConcurrentHashMap<>();

    public interface OnSaved {
        void onSaved(String filePath, String fileName);
    }

    public MediaSaver(Context context) {
        this.context = context;
    }

    // ── Type helpers ──────────────────────────────────────────────────────────

    public static MediaItem.Type getTypeFromMime(String mimeType) {
        if (mimeType == null) return MediaItem.Type.OTHER;
        if (mimeType.startsWith("image/")) return MediaItem.Type.IMAGE;
        if (mimeType.startsWith("video/")) return MediaItem.Type.VIDEO;
        if (mimeType.startsWith("audio/")) return MediaItem.Type.AUDIO;
        return MediaItem.Type.OTHER;
    }

    public static String getSubDir(MediaItem.Type type) {
        switch (type) {
            case IMAGE: return "Images";
            case VIDEO: return "Videos";
            case AUDIO: return "Audio";
            default:    return "Other";
        }
    }

    public static boolean isMediaMime(String mimeType) {
        if (mimeType == null) return false;
        return mimeType.startsWith("image/") ||
               mimeType.startsWith("video/") ||
               mimeType.startsWith("audio/");
    }

    public static String guessMimeFromUrl(String url) {
        // Strip query string before guessing
        String clean = url.split("\\?")[0].split("#")[0].toLowerCase();
        String ext = MimeTypeMap.getFileExtensionFromUrl(clean);
        if (ext != null && !ext.isEmpty()) {
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null) return mime;
        }
        if (clean.contains(".jpg") || clean.contains(".jpeg")) return "image/jpeg";
        if (clean.contains(".png"))  return "image/png";
        if (clean.contains(".gif"))  return "image/gif";
        if (clean.contains(".webp")) return "image/webp";
        if (clean.contains(".mp4"))  return "video/mp4";
        if (clean.contains(".webm")) return "video/webm";
        if (clean.contains(".mp3"))  return "audio/mpeg";
        if (clean.contains(".ogg"))  return "audio/ogg";
        if (clean.contains(".wav"))  return "audio/wav";
        if (clean.contains(".m4a"))  return "audio/mp4";
        if (clean.contains(".m3u8")) return "application/x-mpegurl";
        if (clean.contains(".mpd"))  return "application/dash+xml";
        return null;
    }

    // ── Directory helpers ─────────────────────────────────────────────────────

    public File getOrCreateDir(MediaItem.Type type) {
        File base = getBaseDir();
        File sub = new File(base, getSubDir(type));
        if (!sub.exists()) sub.mkdirs();
        return sub;
    }

    public File getRootDir() { return getBaseDir(); }

    private File getBaseDir() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            return new File(context.getExternalFilesDir(null), BASE_DIR);
        } else {
            return new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                BASE_DIR);
        }
    }

    public boolean alreadySaved(String url) {
        int hash = Math.abs(url.hashCode());
        for (MediaItem.Type type : MediaItem.Type.values()) {
            File dir = getOrCreateDir(type);
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().startsWith(String.valueOf(hash))) return true;
                }
            }
        }
        return false;
    }

    // ── Stream key — identifies which stream a chunk belongs to ──────────────
    // Uses domain + first two path segments, e.g. "r3---sn-foo.googlevideo.com/videoplayback"
    private String streamKeyFromUrl(String urlStr) {
        try {
            URL u = new URL(urlStr);
            String host = u.getHost();
            String[] parts = u.getPath().split("/");
            // Take host + first meaningful path segment
            String seg = parts.length > 1 ? parts[1] : "stream";
            return (host + "_" + seg).replaceAll("[^a-zA-Z0-9_\\-]", "_");
        } catch (Exception e) {
            return "stream_" + Math.abs(urlStr.hashCode() % 10000);
        }
    }

    // Get or create a dedicated single-thread executor for this stream
    private ExecutorService getStreamExecutor(String streamKey) {
        return streamExecutors.computeIfAbsent(streamKey,
                k -> Executors.newSingleThreadExecutor());
    }

    // Per-stream incrementing counter for sequential chunk naming
    private long nextChunkIndex(String streamKey) {
        return streamCounters
                .computeIfAbsent(streamKey, k -> new AtomicLong(0))
                .getAndIncrement();
    }

    // ── Save stream file (manifest, init segment, chunk) ─────────────────────
    // These all go through a per-stream single-thread executor so they're
    // written one at a time in arrival order — no interleaving, no race condition.
    public void saveStreamFile(String urlStr, String ext,
                               Map<String, String> requestHeaders, OnSaved callback) {
        String streamKey = streamKeyFromUrl(urlStr);
        long index = nextChunkIndex(streamKey);

        getStreamExecutor(streamKey).execute(() -> {
            try {
                // Stream chunks go in Videos/Streams/<streamKey>/
                File streamDir = new File(getOrCreateDir(MediaItem.Type.VIDEO),
                        "Streams" + File.separator + streamKey);
                if (!streamDir.exists()) streamDir.mkdirs();

                // Naming: 00000_init.mp4, 00001_chunk.ts, 00002_chunk.ts ...
                // init segments always get index 0 prefix so they sort first
                boolean isInit = urlStr.toLowerCase().contains("init");
                String prefix = isInit
                        ? String.format(Locale.US, "%05d_init", index)
                        : String.format(Locale.US, "%05d_chunk", index);

                // Preserve correct extension — critical for FFmpeg
                String cleanExt = ext != null ? ext : "bin";
                String fileName = prefix + "." + cleanExt;
                File outFile = new File(streamDir, fileName);

                // Don't re-save exact same filename
                if (outFile.exists() && outFile.length() > 0) return;

                HttpURLConnection conn = openConnection(urlStr, requestHeaders);
                if (conn == null) return;

                // Write directly — no temp file, so partial data is kept on nav away
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[16384]; // larger buffer for chunks
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                    }
                    out.flush();
                } finally {
                    conn.disconnect();
                }

                Log.d(TAG, "Stream chunk saved: " + fileName + " (" + outFile.length() + "b)");
                if (callback != null) callback.onSaved(outFile.getAbsolutePath(), fileName);

            } catch (IOException e) {
                // Network errors are expected (user navigated away) — just log
                Log.d(TAG, "Stream chunk partial/failed: " + e.getMessage());
            }
        });
    }

    // ── Save regular media file (images, mp4, mp3, etc.) ─────────────────────
    public void saveFromUrl(String urlStr, String mimeType,
                            Map<String, String> requestHeaders, OnSaved callback) {
        generalExecutor.execute(() -> {
            try {
                if (alreadySaved(urlStr)) return;

                MediaItem.Type type = getTypeFromMime(mimeType);
                File dir = getOrCreateDir(type);

                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                int hash = Math.abs(urlStr.hashCode());

                // Get extension from URL (strip query string first)
                String cleanUrl = urlStr.split("\\?")[0];
                String ext = MimeTypeMap.getFileExtensionFromUrl(cleanUrl);
                if (ext == null || ext.isEmpty()) {
                    ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
                }
                if (ext == null || ext.isEmpty()) ext = "bin";

                String fileName = hash + "_" + timestamp + "." + ext;
                File outFile = new File(dir, fileName);

                HttpURLConnection conn = openConnection(urlStr, requestHeaders);
                if (conn == null) return;

                // Skip huge files > 200MB
                int len = conn.getContentLength();
                if (len > 200 * 1024 * 1024) { conn.disconnect(); return; }

                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                    }
                    out.flush();
                } finally {
                    conn.disconnect();
                }

                if (callback != null) callback.onSaved(outFile.getAbsolutePath(), fileName);
                Log.d(TAG, "Saved: " + fileName);

            } catch (IOException e) {
                Log.e(TAG, "Save failed: " + e.getMessage());
            }
        });
    }

    // Backwards compat overload without headers
    public void saveFromUrl(String urlStr, String mimeType, OnSaved callback) {
        saveFromUrl(urlStr, mimeType, null, callback);
    }

    // ── Save raw bytes (blob handler) ─────────────────────────────────────────
    public void saveBytes(byte[] data, String mimeType, String suggestedName, OnSaved callback) {
        generalExecutor.execute(() -> {
            try {
                MediaItem.Type type = getTypeFromMime(mimeType);
                File dir = getOrCreateDir(type);
                String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                String fileName = (suggestedName != null && !suggestedName.isEmpty())
                        ? suggestedName : ts + "_media";
                File out = new File(dir, fileName);
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    fos.write(data);
                    fos.flush();
                }
                if (callback != null) callback.onSaved(out.getAbsolutePath(), fileName);
            } catch (IOException e) {
                Log.e(TAG, "saveBytes error: " + e.getMessage());
            }
        });
    }

    // ── HTTP connection helper ────────────────────────────────────────────────
    private HttpURLConnection openConnection(String urlStr,
                                              Map<String, String> requestHeaders) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000); // longer for video chunks
            conn.setInstanceFollowRedirects(true);

            // Mirror original request headers so the server accepts us
            // (range requests, auth tokens, origin headers etc.)
            if (requestHeaders != null) {
                for (Map.Entry<String, String> h : requestHeaders.entrySet()) {
                    String key = h.getKey();
                    // Skip headers that would cause issues
                    if (key.equalsIgnoreCase("Accept-Encoding")) continue;
                    if (key.equalsIgnoreCase("If-None-Match")) continue;
                    if (key.equalsIgnoreCase("If-Modified-Since")) continue;
                    try { conn.setRequestProperty(key, h.getValue()); }
                    catch (Exception ignored) {}
                }
            }

            // Always set a reasonable User-Agent if not already set
            if (requestHeaders == null || !requestHeaders.containsKey("User-Agent")) {
                conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
            }

            int status = conn.getResponseCode();
            if (status == 403 || status == 401) {
                // Server rejected — likely needs cookies/auth we can't replicate
                Log.d(TAG, "HTTP " + status + " for " + urlStr);
                conn.disconnect();
                return null;
            }

            return conn;
        } catch (IOException e) {
            Log.e(TAG, "openConnection failed: " + e.getMessage());
            return null;
        }
    }

    public void shutdown() {
        generalExecutor.shutdown();
        for (ExecutorService ex : streamExecutors.values()) ex.shutdown();
    }
}
