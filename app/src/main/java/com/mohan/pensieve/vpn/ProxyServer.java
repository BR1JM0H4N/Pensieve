package com.mohan.pensieve.vpn;

import android.content.Context;
import android.util.Log;

import com.mohan.pensieve.MediaSaver;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import javax.net.ssl.KeyManagerFactory;

/**
 * Local HTTP/HTTPS proxy running on localhost:8888.
 *
 * HTTP:  Intercepts full request/response — tees media to disk
 * HTTPS: CONNECT tunnel → TLS handshake with issued cert → tees media to disk
 *
 * This is the key to zero-extra-data: we sit in the path of every request,
 * read bytes ONCE, save media copy, forward to browser. No second download.
 */
public class ProxyServer {
    private static final String TAG = "ProxyServer";
    public static final int PORT = 8888;

    private final Context context;
    private final CertificateManager certManager;
    private final MediaSaver saver;
    private ServerSocket serverSocket;
    private final ExecutorService threadPool = Executors.newCachedThreadPool();
    private volatile boolean running = false;

    public ProxyServer(Context context, CertificateManager certManager, MediaSaver saver) {
        this.context = context;
        this.certManager = certManager;
        this.saver = saver;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress("127.0.0.1", PORT));
        running = true;
        Log.d(TAG, "Proxy listening on port " + PORT);

        threadPool.execute(() -> {
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    threadPool.execute(() -> handleClient(client));
                } catch (IOException e) {
                    if (running) Log.e(TAG, "Accept error: " + e.getMessage());
                }
            }
        });
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        threadPool.shutdown();
    }

    // ── Handle incoming connection ────────────────────────────────────────────
    private void handleClient(Socket client) {
        try {
            client.setSoTimeout(30000);
            InputStream in   = client.getInputStream();
            OutputStream out = client.getOutputStream();

            // Read first line: e.g. "GET http://example.com/img.jpg HTTP/1.1"
            //                  or  "CONNECT example.com:443 HTTP/1.1"
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) { client.close(); return; }

            Map<String, String> headers = readHeaders(in);

            if (requestLine.startsWith("CONNECT")) {
                handleConnect(requestLine, headers, client, in, out);
            } else {
                handleHttp(requestLine, headers, in, out);
                client.close();
            }
        } catch (Exception e) {
            Log.d(TAG, "Client error: " + e.getMessage());
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    // ── HTTP (plain) ──────────────────────────────────────────────────────────
    private void handleHttp(String requestLine, Map<String, String> headers,
                             InputStream clientIn, OutputStream clientOut) throws Exception {
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) return;
        String method = parts[0];
        String urlStr = parts[1];

        URL url = new URL(urlStr);
        String host = url.getHost();
        int port = url.getPort() == -1 ? 80 : url.getPort();

        try (Socket remote = new Socket(host, port)) {
            remote.setSoTimeout(30000);
            OutputStream remoteOut = remote.getOutputStream();
            InputStream  remoteIn  = remote.getInputStream();

            // Forward request
            PrintWriter pw = new PrintWriter(remoteOut);
            pw.print(method + " " + url.getFile() + " HTTP/1.1\r\n");
            for (Map.Entry<String, String> h : headers.entrySet()) {
                pw.print(h.getKey() + ": " + h.getValue() + "\r\n");
            }
            pw.print("\r\n");
            pw.flush();

            // Read response status line + headers
            String statusLine = readLine(remoteIn);
            Map<String, String> respHeaders = readHeaders(remoteIn);

            // Forward status + headers to client
            PrintWriter cpw = new PrintWriter(clientOut);
            cpw.print(statusLine + "\r\n");
            for (Map.Entry<String, String> h : respHeaders.entrySet()) {
                cpw.print(h.getKey() + ": " + h.getValue() + "\r\n");
            }
            cpw.print("\r\n");
            cpw.flush();

            // TEE the body
            String contentType = respHeaders.get("Content-Type");
            if (contentType == null) contentType = respHeaders.get("content-type");
            File saveDir = MediaTee.isMediaContentType(contentType)
                    ? MediaTee.getSaveDir(contentType, saver.getRootDir()) : null;

            MediaTee.tee(remoteIn, clientOut, contentType, urlStr, saveDir);
        }
    }

    // ── HTTPS CONNECT tunnel ──────────────────────────────────────────────────
    private void handleConnect(String requestLine, Map<String, String> headers,
                                Socket clientSocket,
                                InputStream clientIn, OutputStream clientOut) throws Exception {
        // e.g. "CONNECT example.com:443 HTTP/1.1"
        String hostPort = requestLine.split(" ")[1];
        String host = hostPort.contains(":") ? hostPort.split(":")[0] : hostPort;
        int    port = hostPort.contains(":") ? Integer.parseInt(hostPort.split(":")[1]) : 443;

        // Tell client tunnel is ready
        PrintWriter pw = new PrintWriter(clientOut);
        pw.print("HTTP/1.1 200 Connection Established\r\n\r\n");
        pw.flush();

        // Issue a fake cert for this host
        X509Certificate fakeCert = certManager.issueCertFor(host);

        // Build SSLContext with our fake cert
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("cert", certManager.getCaKeyPair().getPrivate(),
                "pensieve".toCharArray(),
                new java.security.cert.Certificate[]{fakeCert, certManager.getCaCert()});

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "pensieve".toCharArray());

        SSLContext sslCtx = SSLContext.getInstance("TLS");
        sslCtx.init(kmf.getKeyManagers(), null, null);

        // Wrap client socket in TLS — we present our fake cert
        SSLSocket sslClient = (SSLSocket) sslCtx.getSocketFactory()
                .createSocket(clientSocket, clientIn, true);
        sslClient.setUseClientMode(false);
        sslClient.startHandshake();

        // Now connect to the real server
        SSLContext remoteSsl = SSLContext.getInstance("TLS");
        remoteSsl.init(null, null, null);
        SSLSocket sslRemote = (SSLSocket) remoteSsl.getSocketFactory()
                .createSocket(host, port);
        sslRemote.startHandshake();

        InputStream  sslIn  = sslClient.getInputStream();
        OutputStream sslOut = sslClient.getOutputStream();
        InputStream  remIn  = sslRemote.getInputStream();
        OutputStream remOut = sslRemote.getOutputStream();

        // Read decrypted HTTPS request from client
        String decryptedRequestLine = readLine(sslIn);
        if (decryptedRequestLine == null) { sslClient.close(); sslRemote.close(); return; }
        Map<String, String> decryptedHeaders = readHeaders(sslIn);

        // Forward to real server
        PrintWriter rpw = new PrintWriter(remOut);
        rpw.print(decryptedRequestLine + "\r\n");
        for (Map.Entry<String, String> h : decryptedHeaders.entrySet()) {
            rpw.print(h.getKey() + ": " + h.getValue() + "\r\n");
        }
        rpw.print("\r\n");
        rpw.flush();

        // Read real server response
        String statusLine    = readLine(remIn);
        Map<String, String> respHeaders = readHeaders(remIn);

        // Forward back to client
        PrintWriter cpw = new PrintWriter(sslOut);
        cpw.print(statusLine + "\r\n");
        for (Map.Entry<String, String> h : respHeaders.entrySet()) {
            cpw.print(h.getKey() + ": " + h.getValue() + "\r\n");
        }
        cpw.print("\r\n");
        cpw.flush();

        // TEE body — zero extra data
        String contentType = respHeaders.get("Content-Type");
        if (contentType == null) contentType = respHeaders.get("content-type");
        String fullUrl = "https://" + host + decryptedRequestLine.split(" ")[1];
        File saveDir = MediaTee.isMediaContentType(contentType)
                ? MediaTee.getSaveDir(contentType, saver.getRootDir()) : null;

        MediaTee.tee(remIn, sslOut, contentType, fullUrl, saveDir);

        sslClient.close();
        sslRemote.close();
    }

    // ── IO helpers ────────────────────────────────────────────────────────────
    private String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\r') { in.read(); break; } // skip \n
            if (c == '\n') break;
            sb.append((char) c);
        }
        return sb.toString();
    }

    private Map<String, String> readHeaders(InputStream in) throws IOException {
        Map<String, String> headers = new HashMap<>();
        String line;
        while (!(line = readLine(in)).isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                String key = line.substring(0, colon).trim();
                String val = line.substring(colon + 1).trim();
                headers.put(key, val);
            }
        }
        return headers;
    }
}
