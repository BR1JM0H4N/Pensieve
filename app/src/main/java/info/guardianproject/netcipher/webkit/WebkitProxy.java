package info.guardianproject.netcipher.webkit;

import android.content.Context;

/**
 * Stub for info.guardianproject.netcipher WebkitProxy.
 * Proxy setting is no-op — Tor support disabled.
 */
public class WebkitProxy {
    public static boolean setProxy(String appClass, Context ctx, Object webView,
                                   String host, int port) { return false; }
    public static boolean resetProxy(String appClass, Context ctx) { return false; }
}
