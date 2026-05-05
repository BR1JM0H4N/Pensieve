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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MediaSaver {
    private static final String TAG = "MediaSaver";
    private static final String BASE_DIR = "Pensieve";
    private final Context context;

    // Single-thread executor: disk I/O doesn't benefit from parallelism
    // and serialising writes eliminates file-interleaving race conditions.
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

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
        // Try Android's built-in mapper first
        String ext = MimeTypeMap.getFileExtensionFromUrl(url);
        if (ext != null && !ext.isEmpty()) {
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase());
            if (mime != null) return mime;
        }
        // Manual fallback (handles URLs with query strings that fool getFileExtensionFromUrl)
        String lower = url.toLowerCase();
        if (lower.contains(".jpg")  || lower.contains(".jpeg")) return "image/jpeg";
        if (lower.contains(".png"))                              return "image/png";
        if (lower.contains(".gif"))                              return "image/gif";
        if (lower.contains(".webp"))                             return "image/webp";
        if (lower.contains(".mp4")  || lower.contains(".m4v"))  return "video/mp4";
        if (lower.contains(".webm"))                             return "video/webm";
        if (lower.contains(".ts")   || lower.contains(".m4s"))  return "video/mp2t";
        if (lower.contains(".mp3"))                              return "audio/mpeg";
        if (lower.contains(".m4a"))                              return "audio/mp4";
        if (lower.contains(".ogg"))                              return "audio/ogg";
        if (lower.contains(".wav"))                              return "audio/wav";
        if (lower.contains(".aac"))                              return "audio/aac";
        return null;
    }

    // ── Directory helpers ─────────────────────────────────────────────────────

    public File getOrCreateDir(MediaItem.Type type) {
        File subDir = new File(getBaseDir(), getSubDir(type));
        if (!subDir.exists()) subDir.mkdirs();
        return subDir;
    }

    public File getRootDir() {
        return getBaseDir();
    }

    private File getBaseDir() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            return new File(context.getExternalFilesDir(null), BASE_DIR);
        } else {
            return new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), BASE_DIR);
        }
    }

    // ── Dedup check ───────────────────────────────────────────────────────────

    /**
     * Returns true if a file whose name starts with the URL's hash already exists.
     * This is a fast O(n) scan — good enough for typical gallery sizes.
     */
    public boolean alreadySaved(String url) {
        String prefix = String.valueOf(Math.abs(url.hashCode()));
        for (MediaItem.Type type : MediaItem.Type.values()) {
            File dir = getOrCreateDir(type);
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    // Ignore .tmp files — they are incomplete writes
                    if (!f.getName().endsWith(".tmp") && f.getName().startsWith(prefix)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ── Fallback download (used only when tee interception missed a URL) ──────

    /**
     * Downloads a URL and saves it to disk.
     * NOTE: This causes one extra network request. It should only be called
     * by ResourceInterceptor.processUrlFromJs() for URLs that shouldInterceptRequest()
     * never saw (e.g. dynamically assigned after page load and never re-fetched).
     * The primary save path is TeeInputStream inside ResourceInterceptor.
     */
    public void saveFromUrl(String urlStr, String mimeType, OnSaved callback) {
        executor.execute(() -> {
            try {
                if (alreadySaved(urlStr)) {
                    Log.d(TAG, "Already saved: " + urlStr);
                    return;
                }

                MediaItem.Type type = getTypeFromMime(mimeType);
                File dir = getOrCreateDir(type);

                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                int hash = Math.abs(urlStr.hashCode());
                String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
                if (ext == null || ext.isEmpty()) ext = "bin";

                File finalFile = new File(dir, hash + "_" + timestamp + "." + ext);
                File tmpFile   = new File(dir, hash + "_" + timestamp + "." + ext + ".tmp");

                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(30_000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");

                int contentLength = conn.getContentLength();
                if (contentLength > 50 * 1024 * 1024) {
                    conn.disconnect();
                    return;
                }

                // Write to .tmp first, rename on success (no corrupt partial files)
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(tmpFile)) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        out.write(buffer, 0, n);
                    }
                }
                conn.disconnect();

                if (tmpFile.length() > 0 && tmpFile.renameTo(finalFile)) {
                    if (callback != null) callback.onSaved(finalFile.getAbsolutePath(), finalFile.getName());
                    Log.d(TAG, "Saved: " + finalFile.getName());
                } else {
                    tmpFile.delete();
                    Log.e(TAG, "Rename failed or empty file: " + urlStr);
                }

            } catch (IOException e) {
                Log.e(TAG, "Failed to save: " + urlStr + " — " + e.getMessage());
            }
        });
    }

    // ── Blob save (from JS FileReader) ────────────────────────────────────────

    public void saveBytes(byte[] data, String mimeType, String suggestedName, OnSaved callback) {
        executor.execute(() -> {
            try {
                MediaItem.Type type = getTypeFromMime(mimeType);
                File dir = getOrCreateDir(type);
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                String fileName = (suggestedName != null && !suggestedName.isEmpty())
                        ? suggestedName : timestamp + "_media";
                File outFile = new File(dir, fileName);
                try (FileOutputStream out = new FileOutputStream(outFile)) {
                    out.write(data);
                }
                if (callback != null) callback.onSaved(outFile.getAbsolutePath(), fileName);
            } catch (IOException e) {
                Log.e(TAG, "saveBytes error: " + e.getMessage());
            }
        });
    }

    public void shutdown() {
        executor.shutdown();
    }
}
