package com.mohan.pensieve.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.mohan.pensieve.MainActivity;
import com.mohan.pensieve.MediaSaver;
import com.mohan.pensieve.R;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.FileChannel;
import java.nio.channels.Selector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * VPN service that routes all device TCP traffic through our local proxy.
 *
 * Approach: tun interface → redirect port 80 and 443 → localhost:8888 (ProxyServer)
 * The ProxyServer does the actual MITM and tees media to disk.
 *
 * We use a packet-level redirect: any TCP SYN to port 80/443 gets redirected
 * to 127.0.0.1:8888 which is our ProxyServer.
 */
public class PensieveVpnService extends VpnService {
    private static final String TAG = "PensieveVpn";
    private static final String CHANNEL_ID = "pensieve_vpn";
    public static final String ACTION_START = "com.mohan.pensieve.VPN_START";
    public static final String ACTION_STOP  = "com.mohan.pensieve.VPN_STOP";

    private ParcelFileDescriptor vpnInterface;
    private ProxyServer proxyServer;
    private CertificateManager certManager;
    private MediaSaver mediaSaver;
    private volatile boolean running = false;
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopVpn();
            return START_NOT_STICKY;
        }
        startVpn();
        return START_STICKY;
    }

    private void startVpn() {
        createNotificationChannel();
        startForeground(2, buildNotification("Pensieve VPN active — saving media"));

        executor.execute(() -> {
            try {
                // 1. Init certificate manager
                certManager = new CertificateManager(this);
                certManager.init();

                // 2. Init media saver
                mediaSaver = new MediaSaver(this);

                // 3. Start local proxy server
                proxyServer = new ProxyServer(this, certManager, mediaSaver);
                proxyServer.start();

                // 4. Configure VPN interface
                Builder builder = new Builder();
                builder.setSession("Pensieve");
                builder.addAddress("10.0.0.2", 32);
                builder.addRoute("0.0.0.0", 0);          // all IPv4
                builder.addDnsServer("8.8.8.8");
                builder.addDnsServer("8.8.4.4");
                builder.setMtu(1500);
                // Exclude our own app from the VPN to avoid loop
                builder.addDisallowedApplication(getPackageName());

                vpnInterface = builder.establish();
                if (vpnInterface == null) {
                    Log.e(TAG, "VPN interface null — permission not granted?");
                    return;
                }

                running = true;
                Log.d(TAG, "VPN interface established");

                // 5. Packet forwarding loop
                runPacketLoop();

            } catch (Exception e) {
                Log.e(TAG, "VPN start failed: " + e.getMessage(), e);
                stopSelf();
            }
        });
    }

    /**
     * Reads IP packets from the tun interface.
     * For TCP packets destined to port 80 or 443, rewrites destination to
     * 127.0.0.1:8888 (our ProxyServer) and forwards.
     * All other traffic passes through unchanged via raw socket.
     */
    private void runPacketLoop() {
        FileInputStream  in  = new FileInputStream(vpnInterface.getFileDescriptor());
        FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());
        ByteBuffer packet = ByteBuffer.allocate(32767);

        while (running) {
            try {
                packet.clear();
                int len = in.read(packet.array());
                if (len <= 0) { Thread.sleep(10); continue; }
                packet.limit(len);

                // Parse IP packet header
                byte ipVersion = (byte) ((packet.get(0) >> 4) & 0xF);
                if (ipVersion != 4) {
                    // IPv6 — pass through for now
                    out.write(packet.array(), 0, len);
                    continue;
                }

                int protocol = packet.get(9) & 0xFF;  // 6 = TCP
                if (protocol != 6) {
                    // UDP/ICMP etc — pass through
                    out.write(packet.array(), 0, len);
                    continue;
                }

                // TCP — check destination port
                int ipHeaderLen = (packet.get(0) & 0xF) * 4;
                int destPort = ((packet.get(ipHeaderLen + 2) & 0xFF) << 8) |
                               (packet.get(ipHeaderLen + 3) & 0xFF);

                if (destPort == 80 || destPort == 443) {
                    // Redirect to our local proxy by rewriting dest IP+port
                    redirectToProxy(packet.array(), len, ipHeaderLen, out);
                } else {
                    // Pass through unmodified
                    out.write(packet.array(), 0, len);
                }

            } catch (InterruptedException e) {
                break;
            } catch (IOException e) {
                if (running) Log.e(TAG, "Packet loop error: " + e.getMessage());
            }
        }
    }

    /**
     * Rewrites destination IP to 127.0.0.1 and port to 8888,
     * recalculates IP and TCP checksums, writes back to tun.
     */
    private void redirectToProxy(byte[] buf, int len, int ipHeaderLen,
                                  FileOutputStream out) throws IOException {
        // Destination IP → 127.0.0.1
        buf[16] = 127; buf[17] = 0; buf[18] = 0; buf[19] = 1;

        // Destination port → 8888 (0x22B8)
        buf[ipHeaderLen + 2] = 0x22;
        buf[ipHeaderLen + 3] = (byte) 0xB8;

        // Recalculate IP checksum
        buf[10] = 0; buf[11] = 0;
        int ipChecksum = checksum(buf, 0, ipHeaderLen);
        buf[10] = (byte) (ipChecksum >> 8);
        buf[11] = (byte) (ipChecksum & 0xFF);

        // Recalculate TCP checksum
        int tcpLen = len - ipHeaderLen;
        buf[ipHeaderLen + 16] = 0; buf[ipHeaderLen + 17] = 0;
        int tcpChecksum = tcpChecksum(buf, ipHeaderLen, tcpLen);
        buf[ipHeaderLen + 16] = (byte) (tcpChecksum >> 8);
        buf[ipHeaderLen + 17] = (byte) (tcpChecksum & 0xFF);

        out.write(buf, 0, len);
    }

    private int checksum(byte[] buf, int offset, int len) {
        int sum = 0;
        for (int i = offset; i < offset + len - 1; i += 2) {
            sum += ((buf[i] & 0xFF) << 8) | (buf[i + 1] & 0xFF);
        }
        if (len % 2 != 0) sum += (buf[offset + len - 1] & 0xFF) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return ~sum & 0xFFFF;
    }

    private int tcpChecksum(byte[] buf, int ipHeaderLen, int tcpLen) {
        // Pseudo header: src IP, dst IP, zero, protocol(6), TCP length
        byte[] pseudo = new byte[12 + tcpLen];
        System.arraycopy(buf, 12, pseudo, 0, 4); // src IP
        System.arraycopy(buf, 16, pseudo, 4, 4); // dst IP
        pseudo[8] = 0;
        pseudo[9] = 6; // TCP
        pseudo[10] = (byte) (tcpLen >> 8);
        pseudo[11] = (byte) (tcpLen & 0xFF);
        System.arraycopy(buf, ipHeaderLen, pseudo, 12, tcpLen);
        return checksum(pseudo, 0, pseudo.length);
    }

    private void stopVpn() {
        running = false;
        if (proxyServer != null) proxyServer.stop();
        try { if (vpnInterface != null) vpnInterface.close(); } catch (IOException ignored) {}
        stopForeground(true);
        stopSelf();
        Log.d(TAG, "VPN stopped");
    }

    @Override
    public void onDestroy() { stopVpn(); super.onDestroy(); }
    @Override
    public void onRevoke() { stopVpn(); super.onRevoke(); }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Pensieve VPN", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Saving media in background");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Pensieve")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_save)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}
