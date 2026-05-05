package com.mohan.pensieve.vpn;

import android.util.Log;

import com.mohan.pensieve.MediaSaver;
import com.mohan.pensieve.MediaItem;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Tees a response stream: writes bytes to disk AND forwards to the caller
 * simultaneously. This is the zero-extra-data core — bytes are read ONCE
 * from the network, split to two destinations.
 */
public class MediaTee {
    private static final String TAG = "MediaTee";

    /**
     * Reads all bytes from `source`, writes them to `dest` (the client/browser)
     * AND saves a copy to disk if the content type is media.
     *
     * @param source      Network input stream (bytes from remote server)
     * @param dest        Output stream going back to the browser/app
     * @param contentType HTTP Content-Type header value
     * @param url         Original request URL (for naming/dedup)
     * @param saveDir     Directory to save media files into
     */
    public static void tee(InputStream source, OutputStream dest,
                           String contentType, String url, File saveDir) throws IOException {

        boolean isMedia = isMediaContentType(contentType);
        FileOutputStream fileSink = null;
        File outFile = null;

        if (isMedia && saveDir != null) {
            try {
                String ext = extensionFromContentType(contentType, url);
                String ts  = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
                int hash   = Math.abs(url.hashCode());
                String name = hash + "_" + ts + "." + ext;
                outFile = new File(saveDir, name);
                fileSink = new FileOutputStream(outFile);
                Log.d(TAG, "Tee saving: " + name + " [" + contentType + "]");
            } catch (IOException e) {
                Log.e(TAG, "Could not open file sink: " + e.getMessage());
                fileSink = null;
            }
        }

        try {
            byte[] buf = new byte[16384];
            int n;
            while ((n = source.read(buf)) != -1) {
                // Forward to browser — always
                dest.write(buf, 0, n);
                // Write to disk simultaneously
                if (fileSink != null) {
                    fileSink.write(buf, 0, n);
                }
            }
            dest.flush();
            if (fileSink != null) fileSink.flush();
            if (outFile != null) Log.d(TAG, "Tee complete: " + outFile.length() + " bytes");

        } finally {
            if (fileSink != null) {
                try { fileSink.close(); } catch (IOException ignored) {}
                // If file is empty (connection dropped immediately), delete it
                if (outFile != null && outFile.length() == 0) outFile.delete();
            }
        }
    }

    public static boolean isMediaContentType(String ct) {
        if (ct == null) return false;
        String c = ct.toLowerCase().split(";")[0].trim();
        return c.startsWith("image/") ||
               c.startsWith("video/") ||
               c.startsWith("audio/") ||
               c.equals("application/x-mpegurl") ||         // HLS manifest
               c.equals("application/vnd.apple.mpegurl") || // HLS
               c.equals("application/dash+xml") ||           // DASH manifest
               c.equals("application/octet-stream") &&
                   looksLikeMedia(c);
    }

    private static boolean looksLikeMedia(String url) {
        String u = url.toLowerCase();
        return u.contains(".mp4") || u.contains(".m4s") || u.contains(".ts") ||
               u.contains(".m3u8") || u.contains(".mpd") || u.contains(".webm");
    }

    public static String extensionFromContentType(String contentType, String url) {
        String ct = contentType.toLowerCase().split(";")[0].trim();
        switch (ct) {
            case "image/jpeg":   return "jpg";
            case "image/png":    return "png";
            case "image/gif":    return "gif";
            case "image/webp":   return "webp";
            case "video/mp4":    return "mp4";
            case "video/webm":   return "webm";
            case "video/x-msvideo": return "avi";
            case "audio/mpeg":   return "mp3";
            case "audio/mp4":    return "m4a";
            case "audio/ogg":    return "ogg";
            case "audio/wav":    return "wav";
            case "application/x-mpegurl":
            case "application/vnd.apple.mpegurl": return "m3u8";
            case "application/dash+xml": return "mpd";
            default:
                // Fall back to URL extension
                String u = url.split("\\?")[0].toLowerCase();
                if (u.endsWith(".m4s"))  return "m4s";
                if (u.endsWith(".ts"))   return "ts";
                if (u.endsWith(".m4a"))  return "m4a";
                if (u.endsWith(".m4v"))  return "m4v";
                if (u.endsWith(".webm")) return "webm";
                return "bin";
        }
    }

    /** Returns the right save subdirectory based on content type */
    public static File getSaveDir(String contentType, File baseDir) {
        String ct = (contentType == null ? "" : contentType.toLowerCase().split(";")[0].trim());
        String sub;
        if (ct.startsWith("image/"))          sub = "Images";
        else if (ct.startsWith("video/") ||
                 ct.equals("application/x-mpegurl") ||
                 ct.equals("application/vnd.apple.mpegurl") ||
                 ct.equals("application/dash+xml")) sub = "Videos";
        else if (ct.startsWith("audio/"))     sub = "Audio";
        else                                   sub = "Other";
        File dir = new File(baseDir, sub);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }
}
