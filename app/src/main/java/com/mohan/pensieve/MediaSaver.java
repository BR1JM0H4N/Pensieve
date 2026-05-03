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
    private final ExecutorService executor = Executors.newFixedThreadPool(3);

    public interface SaveCallback {
        void onSaved(String filePath, String fileName);
        void onError(String message);
    }

    public MediaSaver(Context context) {
        this.context = context;
    }

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
            default: return "Other";
        }
    }

    public static boolean isMediaMime(String mimeType) {
        if (mimeType == null) return false;
        return mimeType.startsWith("image/") ||
               mimeType.startsWith("video/") ||
               mimeType.startsWith("audio/");
    }

    public static String guessMimeFromUrl(String url) {
        String ext = MimeTypeMap.getFileExtensionFromUrl(url);
        if (ext != null && !ext.isEmpty()) {
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase());
            if (mime != null) return mime;
        }
        // Common patterns
        if (url.contains(".jpg") || url.contains(".jpeg")) return "image/jpeg";
        if (url.contains(".png")) return "image/png";
        if (url.contains(".gif")) return "image/gif";
        if (url.contains(".webp")) return "image/webp";
        if (url.contains(".mp4")) return "video/mp4";
        if (url.contains(".webm")) return "video/webm";
        if (url.contains(".mp3")) return "audio/mpeg";
        if (url.contains(".ogg")) return "audio/ogg";
        if (url.contains(".wav")) return "audio/wav";
        if (url.contains(".m4a")) return "audio/mp4";
        return null;
    }

    public File getOrCreateDir(MediaItem.Type type) {
        File baseDir;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            baseDir = new File(context.getExternalFilesDir(null), BASE_DIR);
        } else {
            baseDir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), BASE_DIR);
        }
        File subDir = new File(baseDir, getSubDir(type));
        if (!subDir.exists()) subDir.mkdirs();
        return subDir;
    }

    public File getRootDir() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            return new File(context.getExternalFilesDir(null), BASE_DIR);
        } else {
            return new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), BASE_DIR);
        }
    }

    public boolean alreadySaved(String url) {
        // Check if we already have this URL saved (by hashing the url as filename prefix)
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

    public void saveFromUrl(String urlStr, String mimeType, SaveCallback callback) {
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
                String fileName = hash + "_" + timestamp + "." + ext;
                File outFile = new File(dir, fileName);

                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");

                // Skip huge files (>50MB)
                int contentLength = conn.getContentLength();
                if (contentLength > 50 * 1024 * 1024) {
                    conn.disconnect();
                    return;
                }

                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(outFile)) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        out.write(buffer, 0, n);
                    }
                }
                conn.disconnect();

                if (callback != null) callback.onSaved(outFile.getAbsolutePath(), fileName);
                Log.d(TAG, "Saved: " + fileName);

            } catch (IOException e) {
                Log.e(TAG, "Failed to save: " + urlStr, e);
                if (callback != null) callback.onError(e.getMessage());
            }
        });
    }

    public void saveBytes(byte[] data, String mimeType, String suggestedName, SaveCallback callback) {
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
                if (callback != null) callback.onError(e.getMessage());
            }
        });
    }

    public void shutdown() {
        executor.shutdown();
    }
}
