package info.guardianproject.netcipher.proxy;

import android.app.Activity;
import android.content.Context;

/**
 * Stub for info.guardianproject.netcipher OrbotHelper.
 * Tor/Orbot proxy support is disabled — returns safe no-op defaults.
 */
public class OrbotHelper {
    public static boolean isOrbotInstalled(Context context) { return false; }
    public static boolean isOrbotRunning(Context context) { return false; }
    public static void requestStartTor(Activity activity) { /* no-op */ }
}
