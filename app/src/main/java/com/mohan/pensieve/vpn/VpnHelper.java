package com.mohan.pensieve.vpn;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.security.KeyChain;
import android.widget.Toast;

/**
 * Helper that manages VPN lifecycle from the Activity.
 */
public class VpnHelper {
    private static final String PREFS = "pensieve_prefs";
    private static final String KEY_VPN_ENABLED = "vpn_enabled";
    public static final int VPN_PERMISSION_CODE = 500;

    private final Activity activity;

    public VpnHelper(Activity activity) {
        this.activity = activity;
    }

    public boolean isEnabled() {
        return activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_VPN_ENABLED, false);
    }

    public void toggle() {
        if (isEnabled()) {
            stop();
        } else {
            requestPermissionAndStart();
        }
    }

    public void requestPermissionAndStart() {
        Intent intent = VpnService.prepare(activity);
        if (intent != null) {
            // Need user permission
            activity.startActivityForResult(intent, VPN_PERMISSION_CODE);
        } else {
            // Already have permission
            start();
        }
    }

    public void start() {
        Intent i = new Intent(activity, PensieveVpnService.class);
        i.setAction(PensieveVpnService.ACTION_START);
        activity.startService(i);
        setEnabled(true);
        Toast.makeText(activity,
                "VPN active — media will be saved with zero extra data",
                Toast.LENGTH_LONG).show();
    }

    public void stop() {
        Intent i = new Intent(activity, PensieveVpnService.class);
        i.setAction(PensieveVpnService.ACTION_STOP);
        activity.startService(i);
        setEnabled(false);
        Toast.makeText(activity, "VPN stopped", Toast.LENGTH_SHORT).show();
    }

    public void installCertificate(CertificateManager certManager) {
        try {
            byte[] certDer = certManager.getCaCertDer();
            Intent intent = KeyChain.createInstallIntent();
            intent.putExtra(KeyChain.EXTRA_CERTIFICATE, certDer);
            intent.putExtra(KeyChain.EXTRA_NAME, "Pensieve CA");
            activity.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(activity,
                    "Install certificate manually: Settings → Security → Install certificate",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void setEnabled(boolean enabled) {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_VPN_ENABLED, enabled).apply();
    }
}
