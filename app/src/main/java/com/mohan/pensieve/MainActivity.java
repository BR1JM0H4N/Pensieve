package com.mohan.pensieve;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.webkit.DownloadListener;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.webkit.CookieManager;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {

    private static final String HOME_URL = "https://www.google.com";
    private static final int FILE_CHOOSER_CODE = 1;
    private static final int CREATE_FILE_CODE = 100;

    private WebView webView;
    private ResourceInterceptor interceptor;
    private MediaSaver saver;
    private static ValueCallback<Uri[]> mUploadMessageArr;
    private byte[] fileDataToSave;
    private int savedCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Transparent status bar
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
            getWindow().setStatusBarColor(Color.TRANSPARENT);
        }

        interceptor = new ResourceInterceptor(this);
        saver = new MediaSaver(this);

        webView = findViewById(R.id.webView);
        setupWebView();

        // Gallery FAB
        FloatingActionButton fabGallery = findViewById(R.id.fab_gallery);
        fabGallery.setOnClickListener(v ->
                startActivity(new Intent(this, GalleryActivity.class)));

        webView.loadUrl(HOME_URL);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36");

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new PensieveWebViewClient());
        webView.setWebChromeClient(new PensieveWebChromeClient());
        webView.setDownloadListener(downloadListener);
        webView.addJavascriptInterface(new BlobHandler(), "AndroidInterface");
    }

    // ── WebViewClient ────────────────────────────────────────────────────────
    class PensieveWebViewClient extends WebViewClient {

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            // Intercept every resource — save media without redownloading
            interceptor.intercept(request);
            return null; // Always return null = WebView loads normally
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleUri(view, Uri.parse(url));
        }

        @TargetApi(Build.VERSION_CODES.N)
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleUri(view, request.getUrl());
        }

        private boolean handleUri(WebView view, Uri uri) {
            String scheme = uri.getScheme();
            if ("http".equals(scheme) || "https".equals(scheme) || "file".equals(scheme)) {
                return false; // Load in WebView
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(MainActivity.this, "Can't open this link", Toast.LENGTH_SHORT).show();
            }
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            // Inject JS to catch blob URLs and HTML5 media src
            injectMediaCatcher();
        }
    }

    // ── Inject JS to catch media src attributes ──────────────────────────────
    private void injectMediaCatcher() {
        String js =
            "(function() {" +
            "  function reportUrl(url, mime) {" +
            "    if (!url || url.startsWith('data:')) return;" +
            "    window.AndroidInterface.onMediaUrl(url, mime || '');" +
            "  }" +
            // Observe new elements
            "  var observer = new MutationObserver(function(muts) {" +
            "    muts.forEach(function(m) {" +
            "      m.addedNodes.forEach(function(n) {" +
            "        if (n.tagName) {" +
            "          var t = n.tagName.toLowerCase();" +
            "          if (t==='img') reportUrl(n.src,'image/jpeg');" +
            "          if (t==='video'||t==='audio') {" +
            "            reportUrl(n.src, t==='video'?'video/mp4':'audio/mpeg');" +
            "            n.querySelectorAll('source').forEach(function(s){reportUrl(s.src,s.type);});" +
            "          }" +
            "        }" +
            "      });" +
            "    });" +
            "  });" +
            "  observer.observe(document.body, {childList:true, subtree:true});" +
            // Scan existing elements
            "  document.querySelectorAll('img').forEach(function(e){reportUrl(e.src,'image/jpeg');});" +
            "  document.querySelectorAll('video,audio').forEach(function(e){" +
            "    var mime = e.tagName.toLowerCase()==='video'?'video/mp4':'audio/mpeg';" +
            "    reportUrl(e.src, mime);" +
            "    e.querySelectorAll('source').forEach(function(s){reportUrl(s.src,s.type);});" +
            "  });" +
            "})();";
        webView.evaluateJavascript(js, null);
    }

    // ── JavaScript Interface ─────────────────────────────────────────────────
    private class BlobHandler {
        @android.webkit.JavascriptInterface
        public void onMediaUrl(String url, String mimeType) {
            if (url == null || url.isEmpty()) return;
            String mime = (mimeType != null && !mimeType.isEmpty())
                    ? mimeType : MediaSaver.guessMimeFromUrl(url);
            if (!MediaSaver.isMediaMime(mime)) return;

            interceptor.processUrl(url, mime);
        }

        @android.webkit.JavascriptInterface
        public void saveBlob(String base64Data, String fileName) {
            fileDataToSave = Base64.decode(base64Data, Base64.DEFAULT);
            String sanitized = fileName.replaceAll("[^a-zA-Z0-9._\\-]", "_");
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/octet-stream");
            intent.putExtra(Intent.EXTRA_TITLE, sanitized);
            startActivityForResult(intent, CREATE_FILE_CODE);
        }
    }

    // ── WebChromeClient ──────────────────────────────────────────────────────
    public class PensieveWebChromeClient extends WebChromeClient {
        @SuppressLint("NewApi")
        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> valueCallback,
                                         FileChooserParams fileChooserParams) {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
            startActivityForResult(Intent.createChooser(intent, "Choose file"), FILE_CHOOSER_CODE);
            mUploadMessageArr = valueCallback;
            return true;
        }
    }

    // ── Download Listener ────────────────────────────────────────────────────
    DownloadListener downloadListener = (url, userAgent, contentDisposition, mimetype, contentLength) -> {
        if (url.startsWith("blob:")) {
            // Handle blob: URLs via JS
            webView.evaluateJavascript(
                "(async function() {" +
                "  const blob = await fetch('" + url + "').then(r => r.blob());" +
                "  const reader = new FileReader();" +
                "  reader.onload = function() {" +
                "    const b64 = reader.result.split(',')[1];" +
                "    window.AndroidInterface.saveBlob(b64, typeof fileName!=='undefined'?fileName:'media.bin');" +
                "  };" +
                "  reader.readAsDataURL(blob);" +
                "})()", null);
        } else {
            // For non-blob, save directly if it's media
            if (MediaSaver.isMediaMime(mimetype)) {
                saver.saveFromUrl(url, mimetype, (filePath, fileName) ->
                        runOnUiThread(() -> showSavedSnackbar(fileName)));
            } else {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            }
        }
    };

    private void showSavedSnackbar(String fileName) {
        savedCount++;
        View root = findViewById(android.R.id.content);
        Snackbar.make(root, "✓ Saved: " + fileName, Snackbar.LENGTH_SHORT)
                .setAction("Gallery", v -> startActivity(new Intent(this, GalleryActivity.class)))
                .show();
    }

    // ── Activity Result ──────────────────────────────────────────────────────
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == CREATE_FILE_CODE && resultCode == Activity.RESULT_OK) {
            if (data != null && data.getData() != null) {
                try (OutputStream os = getContentResolver().openOutputStream(data.getData())) {
                    if (os != null) os.write(fileDataToSave);
                    Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show();
                } catch (IOException e) {
                    Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show();
                }
            }
        } else if (requestCode == FILE_CHOOSER_CODE) {
            if (mUploadMessageArr != null) {
                Uri[] results = null;
                if (resultCode == Activity.RESULT_OK && data != null) {
                    if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        results = new Uri[count];
                        for (int i = 0; i < count; i++)
                            results[i] = data.getClipData().getItemAt(i).getUri();
                    } else if (data.getData() != null) {
                        results = new Uri[]{data.getData()};
                    }
                }
                mUploadMessageArr.onReceiveValue(results);
                mUploadMessageArr = null;
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        interceptor.shutdown();
        saver.shutdown();
        CookieManager.getInstance().flush();
    }
}
