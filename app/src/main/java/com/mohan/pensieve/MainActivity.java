package com.mohan.pensieve;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.DownloadListener;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.Toast;
import android.webkit.CookieManager;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import java.io.IOException;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {

    private static final String HOME_URL = "https://www.google.com";
    private static final int FILE_CHOOSER_CODE = 1;
    private static final int CREATE_FILE_CODE = 100;

    private WebView webView;
    private EditText etUrl;
    private ImageButton btnBack, btnForward, btnRefresh;
    private ImageButton btnBackBottom, btnForwardBottom;
    private ImageButton btnGallery, btnShare, btnMenu, btnClearUrl;
    private ImageView ivLock;
    private LinearProgressIndicator progressBar;
    private SwipeRefreshLayout swipeRefresh;
    private View statusBarSpacer, navBarSpacer;

    private ResourceInterceptor interceptor;
    private MediaSaver saver;
    private static ValueCallback<Uri[]> mUploadMessageArr;
    private byte[] fileDataToSave;
    private boolean isLoading = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        interceptor = new ResourceInterceptor(this);
        saver = new MediaSaver(this);

        bindViews();
        applyWindowInsets();
        setupSwipeRefresh();
        setupWebView();
        setupUrlBar();
        setupButtons();

        webView.loadUrl(HOME_URL);
    }

    // ── Bind ─────────────────────────────────────────────────────────────────
    private void bindViews() {
        etUrl          = findViewById(R.id.et_url);
        btnBack        = findViewById(R.id.btn_back);
        btnForward     = findViewById(R.id.btn_forward);
        btnRefresh     = findViewById(R.id.btn_refresh);
        btnBackBottom  = findViewById(R.id.btn_back_bottom);
        btnForwardBottom = findViewById(R.id.btn_forward_bottom);
        btnGallery     = findViewById(R.id.btn_gallery);
        btnShare       = findViewById(R.id.btn_share);
        btnMenu        = findViewById(R.id.btn_menu);
        btnClearUrl    = findViewById(R.id.btn_clear_url);
        ivLock         = findViewById(R.id.iv_lock);
        progressBar    = findViewById(R.id.progress_bar);
        swipeRefresh   = findViewById(R.id.swipe_refresh);
        statusBarSpacer = findViewById(R.id.status_bar_spacer);
        navBarSpacer   = findViewById(R.id.nav_bar_spacer);
        webView        = findViewById(R.id.webView);
    }

    // ── Window Insets (fixes status bar overlay) ─────────────────────────────
    private void applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.main_container), (v, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            int bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;

            ViewGroup.LayoutParams spTop = statusBarSpacer.getLayoutParams();
            spTop.height = top;
            statusBarSpacer.setLayoutParams(spTop);

            ViewGroup.LayoutParams spBot = navBarSpacer.getLayoutParams();
            spBot.height = bottom;
            navBarSpacer.setLayoutParams(spBot);

            return insets;
        });
    }

    // ── SwipeRefresh ─────────────────────────────────────────────────────────
    private void setupSwipeRefresh() {
        swipeRefresh.setOnRefreshListener(() -> {
            webView.reload();
            swipeRefresh.setRefreshing(false);
        });
    }

    // ── WebView Setup ─────────────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setUserAgentString(
            "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new PensieveWebViewClient());
        webView.setWebChromeClient(new PensieveWebChromeClient());
        webView.setDownloadListener(downloadListener);
        webView.addJavascriptInterface(new BlobHandler(), "AndroidInterface");
    }

    // ── URL Bar ───────────────────────────────────────────────────────────────
    private void setupUrlBar() {
        etUrl.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                navigateTo(etUrl.getText().toString().trim());
                return true;
            }
            return false;
        });

        etUrl.setOnFocusChangeListener((v, hasFocus) -> {
            btnClearUrl.setVisibility(hasFocus ? View.VISIBLE : View.GONE);
            if (hasFocus) {
                etUrl.setText(webView.getUrl());
                etUrl.selectAll();
            } else {
                etUrl.setText(cleanUrl(webView.getUrl()));
            }
        });

        btnClearUrl.setOnClickListener(v -> {
            etUrl.setText("");
            etUrl.requestFocus();
        });
    }

    private void navigateTo(String input) {
        if (input == null || input.isEmpty()) return;
        hideKeyboard();
        etUrl.clearFocus();

        String url;
        if (input.startsWith("http://") || input.startsWith("https://")) {
            url = input;
        } else if (input.contains(".") && !input.contains(" ")) {
            url = "https://" + input;
        } else {
            url = "https://www.google.com/search?q=" + Uri.encode(input);
        }
        webView.loadUrl(url);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etUrl.getWindowToken(), 0);
    }

    // ── Buttons ───────────────────────────────────────────────────────────────
    private void setupButtons() {
        btnBack.setOnClickListener(v -> { if (webView.canGoBack()) webView.goBack(); });
        btnForward.setOnClickListener(v -> { if (webView.canGoForward()) webView.goForward(); });
        btnBackBottom.setOnClickListener(v -> { if (webView.canGoBack()) webView.goBack(); });
        btnForwardBottom.setOnClickListener(v -> { if (webView.canGoForward()) webView.goForward(); });

        btnRefresh.setOnClickListener(v -> {
            if (isLoading) webView.stopLoading();
            else webView.reload();
        });

        btnGallery.setOnClickListener(v ->
                startActivity(new Intent(this, GalleryActivity.class)));

        btnShare.setOnClickListener(v -> {
            String url = webView.getUrl();
            if (url != null) {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_TEXT, url);
                intent.putExtra(Intent.EXTRA_SUBJECT, webView.getTitle());
                startActivity(Intent.createChooser(intent, "Share page"));
            }
        });

        btnMenu.setOnClickListener(v -> showMenu(v));
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "Home");
        menu.getMenu().add(0, 2, 0, "Reload");
        menu.getMenu().add(0, 3, 0, "Copy URL");
        menu.getMenu().add(0, 4, 0, "Open in browser");
        menu.getMenu().add(0, 5, 0, "Desktop site");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: webView.loadUrl(HOME_URL); return true;
                case 2: webView.reload(); return true;
                case 3:
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("url", webView.getUrl()));
                    Toast.makeText(this, "URL copied", Toast.LENGTH_SHORT).show();
                    return true;
                case 4:
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(webView.getUrl()))); }
                    catch (Exception e) { }
                    return true;
                case 5:
                    WebSettings ws = webView.getSettings();
                    boolean isDesktop = ws.getUserAgentString().contains("Windows");
                    if (isDesktop) {
                        ws.setUserAgentString(
                            "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
                        ws.setUseWideViewPort(true);
                        item.setTitle("Desktop site");
                    } else {
                        ws.setUserAgentString(
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
                        ws.setUseWideViewPort(true);
                    }
                    webView.reload();
                    return true;
            }
            return false;
        });
        menu.show();
    }

    private void updateNavButtons() {
        float backAlpha = webView.canGoBack() ? 1.0f : 0.35f;
        float fwdAlpha = webView.canGoForward() ? 1.0f : 0.35f;
        btnBack.setAlpha(backAlpha);
        btnBackBottom.setAlpha(backAlpha);
        btnForward.setAlpha(fwdAlpha);
        btnForwardBottom.setAlpha(fwdAlpha);
    }

    private String cleanUrl(String url) {
        if (url == null) return "";
        return url.replaceFirst("https://", "").replaceFirst("http://", "");
    }

    // ── WebViewClient ─────────────────────────────────────────────────────────
    class PensieveWebViewClient extends WebViewClient {

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            interceptor.intercept(request);
            return null;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleUri(view, request.getUrl());
        }

        private boolean handleUri(WebView view, Uri uri) {
            String scheme = uri.getScheme();
            if ("http".equals(scheme) || "https".equals(scheme) || "file".equals(scheme)) return false;
            try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
            catch (ActivityNotFoundException e) {
                Toast.makeText(MainActivity.this, "Can't open this link", Toast.LENGTH_SHORT).show();
            }
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            isLoading = true;
            btnRefresh.setImageResource(R.drawable.ic_close);
            progressBar.setVisibility(View.VISIBLE);
            swipeRefresh.setRefreshing(false);
            if (!etUrl.isFocused()) etUrl.setText(cleanUrl(url));
            ivLock.setVisibility(url != null && url.startsWith("https://") ? View.VISIBLE : View.GONE);
            updateNavButtons();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            isLoading = false;
            btnRefresh.setImageResource(R.drawable.ic_refresh);
            progressBar.setVisibility(View.GONE);
            swipeRefresh.setRefreshing(false);
            if (!etUrl.isFocused()) etUrl.setText(cleanUrl(url));
            updateNavButtons();
            injectMediaCatcher();
        }
    }

    // ── WebChromeClient ───────────────────────────────────────────────────────
    class PensieveWebChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            progressBar.setProgressCompat(newProgress, true);
            if (newProgress == 100) progressBar.setVisibility(View.GONE);
        }

        @SuppressLint("NewApi")
        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> valueCallback,
                                          FileChooserParams fileChooserParams) {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(Intent.createChooser(intent, "Choose file"), FILE_CHOOSER_CODE);
            mUploadMessageArr = valueCallback;
            return true;
        }
    }

    // ── JS Injection ──────────────────────────────────────────────────────────
    private void injectMediaCatcher() {
        String js =
            "(function() {" +
            "  function report(url, mime) {" +
            "    if (!url || url.startsWith('data:') || url.startsWith('blob:')) return;" +
            "    window.AndroidInterface.onMediaUrl(url, mime || '');" +
            "  }" +
            "  var obs = new MutationObserver(function(muts) {" +
            "    muts.forEach(function(m) {" +
            "      m.addedNodes.forEach(function(n) {" +
            "        if (!n.tagName) return;" +
            "        var t = n.tagName.toLowerCase();" +
            "        if (t==='img') report(n.src,'image/jpeg');" +
            "        if (t==='video'||t==='audio') {" +
            "          report(n.src,t==='video'?'video/mp4':'audio/mpeg');" +
            "          n.querySelectorAll('source').forEach(function(s){report(s.src,s.type);});" +
            "        }" +
            "      });" +
            "    });" +
            "  });" +
            "  if (document.body) obs.observe(document.body,{childList:true,subtree:true});" +
            "  document.querySelectorAll('img').forEach(function(e){report(e.src,'image/jpeg');});" +
            "  document.querySelectorAll('video,audio').forEach(function(e){" +
            "    var m=e.tagName.toLowerCase()==='video'?'video/mp4':'audio/mpeg';" +
            "    report(e.src,m);" +
            "    e.querySelectorAll('source').forEach(function(s){report(s.src,s.type);});" +
            "  });" +
            "})();";
        webView.evaluateJavascript(js, null);
    }

    // ── JS Interface ──────────────────────────────────────────────────────────
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
            String safe = fileName.replaceAll("[^a-zA-Z0-9._\\-]", "_");
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/octet-stream");
            intent.putExtra(Intent.EXTRA_TITLE, safe);
            startActivityForResult(intent, CREATE_FILE_CODE);
        }
    }

    // ── Download Listener ─────────────────────────────────────────────────────
    DownloadListener downloadListener = (url, userAgent, contentDisposition, mimetype, contentLength) -> {
        if (url.startsWith("blob:")) {
            webView.evaluateJavascript(
                "(async function() {" +
                "  const blob = await fetch('" + url + "').then(r=>r.blob());" +
                "  const reader = new FileReader();" +
                "  reader.onload = function() {" +
                "    window.AndroidInterface.saveBlob(reader.result.split(',')[1],'media.bin');" +
                "  };" +
                "  reader.readAsDataURL(blob);" +
                "})()", null);
        } else if (MediaSaver.isMediaMime(mimetype)) {
            saver.saveFromUrl(url, mimetype, (filePath, fileName) ->
                    runOnUiThread(() -> showSavedSnackbar(fileName)));
        } else {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
            catch (Exception e) { Toast.makeText(this, "Can't open download", Toast.LENGTH_SHORT).show(); }
        }
    };

    private void showSavedSnackbar(String fileName) {
        View root = findViewById(android.R.id.content);
        Snackbar.make(root, "✓ Saved: " + fileName, Snackbar.LENGTH_SHORT)
                .setAction("View", v -> startActivity(new Intent(this, GalleryActivity.class)))
                .show();
    }

    // ── Activity Results ──────────────────────────────────────────────────────
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
