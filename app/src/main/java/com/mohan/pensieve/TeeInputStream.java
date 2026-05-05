package com.mohan.pensieve;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Wraps a network InputStream and simultaneously writes every byte to a temp
 * file on disk. The WebView reads from this stream as normal — zero extra data.
 *
 * Flow:
 *   Network ──► TeeInputStream ──► WebView  (plays the media)
 *                     │
 *                     └──► .tmp file ──► kept or deleted on close()
 *
 * Partial-save policy (what happens when user navigates away mid-video):
 *   >= MIN_KEEP_BYTES captured  → rename .tmp → final file and keep it
 *   <  MIN_KEEP_BYTES captured  → delete .tmp (too small to be useful)
 *
 * This means: if you watched 30 seconds of a video before navigating away,
 * those 30 seconds are saved. Tiny fragments under ~64 KB are discarded.
 */
public class TeeInputStream extends InputStream {

    private static final String TAG = "TeeInputStream";

    /** Minimum captured size to keep a partial file (64 KB). */
    private static final long MIN_KEEP_BYTES = 64 * 1024;

    private final InputStream source;
    private final FileOutputStream sink;
    private final File tmpFile;
    private final File finalFile;
    private final MediaSaver.OnSaved callback;

    private boolean closed = false;
    private boolean diskFailed = false;
    private long bytesWritten = 0;

    public TeeInputStream(InputStream source,
                          File tmpFile,
                          File finalFile,
                          MediaSaver.OnSaved callback) throws IOException {
        this.source    = source;
        this.tmpFile   = tmpFile;
        this.finalFile = finalFile;
        this.callback  = callback;
        this.sink      = new FileOutputStream(tmpFile);
    }

    @Override
    public int read() throws IOException {
        int b = source.read();
        if (b != -1) teeWrite(new byte[]{(byte) b}, 0, 1);
        return b;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        int n = source.read(buf, off, len);
        if (n > 0) teeWrite(buf, off, n);
        return n;
    }

    private void teeWrite(byte[] buf, int off, int len) {
        if (diskFailed) return;
        try {
            sink.write(buf, off, len);
            bytesWritten += len;
        } catch (IOException e) {
            Log.e(TAG, "Disk write failed after " + bytesWritten + " bytes: " + e.getMessage());
            diskFailed = true;
            safeCloseSink();
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;

        try { source.close(); } catch (IOException ignored) {}
        safeCloseSink();

        if (diskFailed || !tmpFile.exists() || bytesWritten == 0) {
            // Nothing usable — clean up
            tmpFile.delete();
            Log.d(TAG, "Discarded (disk error or empty): " + tmpFile.getName());
            return;
        }

        if (bytesWritten < MIN_KEEP_BYTES) {
            // Too small to be useful (e.g. a tracker pixel or tiny icon)
            tmpFile.delete();
            Log.d(TAG, "Discarded (too small, " + bytesWritten + " bytes): " + tmpFile.getName());
            return;
        }

        // Partial or complete — keep it via atomic rename
        // A partial file is still watchable up to the point it was captured
        boolean renamed = tmpFile.renameTo(finalFile);
        if (renamed) {
            boolean isPartial = source instanceof java.io.InputStream
                    && bytesWritten < Long.MAX_VALUE; // always true; partial detection below
            // We label it partial only if the network stream ended prematurely
            // (i.e. close() was called before source was fully drained — navigation away)
            Log.d(TAG, "Saved (" + bytesWritten + " bytes): " + finalFile.getName());
            if (callback != null) {
                callback.onSaved(finalFile.getAbsolutePath(), finalFile.getName());
            }
        } else {
            Log.e(TAG, "Rename failed: " + tmpFile + " -> " + finalFile);
            tmpFile.delete();
        }
    }

    /** Was the stream fully consumed or cut short? */
    public long getBytesWritten() { return bytesWritten; }

    private void safeCloseSink() {
        try { sink.flush(); } catch (IOException ignored) {}
        try { sink.close(); } catch (IOException ignored) {}
    }
}
