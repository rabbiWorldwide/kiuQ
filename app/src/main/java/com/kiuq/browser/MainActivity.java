package com.kiuq.browser;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.util.ArrayList;

public class MainActivity extends Activity {

    private static final String HOME = "https://duckduckgo.com";
    private static final String SEARCH = "https://duckduckgo.com/?q=";

    private final ArrayList<WebView> tabs = new ArrayList<>();
    private int currentIndex = 0;

    private FrameLayout webContainer;
    private EditText urlBar;
    private ProgressBar progress;
    private FrameLayout fullscreen;
    private View topBar;
    private View bottomBar;
    private Button btnTabs;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;

    private final WebViewClient tabClient = new WebViewClient() {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String scheme = uri.getScheme();
            if (scheme == null) return false;
            if (scheme.equals("http") || scheme.equals("https") || scheme.equals("about")
                    || scheme.equals("data") || scheme.equals("blob")
                    || scheme.equals("javascript")) {
                return false;
            }
            try {
                Intent i;
                if (scheme.equals("intent")) {
                    i = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
                    i.addCategory(Intent.CATEGORY_BROWSABLE);
                    i.setComponent(null);
                    i.setSelector(null);
                } else {
                    i = new Intent(Intent.ACTION_VIEW, uri);
                }
                startActivity(i);
            } catch (Exception e) {
                toast("Can't open this link");
            }
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (view != current()) return;
            progress.setVisibility(View.VISIBLE);
            if (!urlBar.hasFocus()) urlBar.setText(url);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (view != current()) return;
            progress.setVisibility(View.GONE);
            if (!urlBar.hasFocus()) urlBar.setText(url);
        }
    };

    private final WebChromeClient tabChrome = new WebChromeClient() {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            if (view != current()) return;
            progress.setProgress(newProgress);
            progress.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog,
                                      boolean isUserGesture, Message resultMsg) {
            WebView w = newTab(null);
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(w);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public void onCloseWindow(WebView window) {
            webContainer.post(() -> {
                int i = tabs.indexOf(window);
                if (i >= 0) closeTab(i);
            });
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) {
                callback.onCustomViewHidden();
                return;
            }
            customView = view;
            customCallback = callback;
            fullscreen.addView(view);
            fullscreen.setVisibility(View.VISIBLE);
            webContainer.setVisibility(View.GONE);
            topBar.setVisibility(View.GONE);
            bottomBar.setVisibility(View.GONE);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }

        @Override
        public void onHideCustomView() {
            exitFullscreen();
        }
    };

    private final DownloadListener downloader =
            (url, userAgent, contentDisposition, mimeType, length) -> {
                try {
                    String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
                    DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                    req.setMimeType(mimeType);
                    String cookie = CookieManager.getInstance().getCookie(url);
                    if (cookie != null) req.addRequestHeader("Cookie", cookie);
                    if (userAgent != null) req.addRequestHeader("User-Agent", userAgent);
                    req.setTitle(name);
                    req.setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                    ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(req);
                    toast("Downloading " + name);
                } catch (Exception e) {
                    toast("Download failed");
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webContainer = findViewById(R.id.webContainer);
        urlBar = findViewById(R.id.urlBar);
        progress = findViewById(R.id.progress);
        fullscreen = findViewById(R.id.fullscreen);
        topBar = findViewById(R.id.topBar);
        bottomBar = findViewById(R.id.bottomBar);
        btnTabs = findViewById(R.id.btnTabs);

        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }

        CookieManager.getInstance().setAcceptCookie(true);
        setupControls();

        String start = HOME;
        Uri data = getIntent().getData();
        if (data != null) start = data.toString();
        newTab(start);
    }

    private WebView current() {
        return tabs.isEmpty() ? null : tabs.get(currentIndex);
    }

    private WebView newTab(String url) {
        WebView w = new WebView(this);
        w.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        configure(w);
        tabs.add(w);
        webContainer.addView(w);
        switchTo(tabs.size() - 1);
        if (url != null) w.loadUrl(url);
        return w;
    }

    private void configure(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setAllowFileAccess(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));

        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);

        w.setWebViewClient(tabClient);
        w.setWebChromeClient(tabChrome);
        w.setDownloadListener(downloader);
    }

    private void switchTo(int index) {
        if (index < 0 || index >= tabs.size()) return;
        currentIndex = index;
        for (int i = 0; i < tabs.size(); i++) {
            WebView w = tabs.get(i);
            if (i == index) {
                w.setVisibility(View.VISIBLE);
                w.onResume();
            } else {
                w.setVisibility(View.GONE);
                w.onPause();
            }
        }
        String u = tabs.get(index).getUrl();
        urlBar.setText(u == null ? "" : u);
        progress.setVisibility(View.GONE);
        updateTabCount();
    }

    private void closeTab(int idx) {
        if (idx < 0 || idx >= tabs.size()) return;
        WebView w = tabs.remove(idx);
        webContainer.removeView(w);
        w.stopLoading();
        w.destroy();
        if (tabs.isEmpty()) {
            currentIndex = 0;
            newTab(HOME);
            return;
        }
        int next = currentIndex;
        if (idx < currentIndex) {
            next--;
        } else if (idx == currentIndex) {
            next = Math.min(idx, tabs.size() - 1);
        }
        switchTo(next);
    }

    private void updateTabCount() {
        btnTabs.setText(String.valueOf(tabs.size()));
    }

    private void showTabs() {
        CharSequence[] titles = new CharSequence[tabs.size()];
        for (int i = 0; i < tabs.size(); i++) {
            WebView w = tabs.get(i);
            String t = w.getTitle();
            if (t == null || t.isEmpty()) t = w.getUrl();
            if (t == null || t.isEmpty()) t = "New tab";
            if (t.length() > 45) t = t.substring(0, 45) + "...";
            titles[i] = (i == currentIndex ? "\u25CF " : "") + t;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Tabs (" + tabs.size() + ") - hold to close");
        b.setItems(titles, (dlg, which) -> switchTo(which));
        b.setPositiveButton("+ New tab", (dlg, which) -> newTab(HOME));
        final AlertDialog d = b.create();
        d.show();
        d.getListView().setOnItemLongClickListener((parent, view, position, id) -> {
            closeTab(position);
            d.dismiss();
            showTabs();
            return true;
        });
    }

    private void setupControls() {
        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                loadFromBar();
                return true;
            }
            return false;
        });

        btnTabs.setOnClickListener(v -> showTabs());
        findViewById(R.id.btnNewTab).setOnClickListener(v -> newTab(HOME));
        findViewById(R.id.btnBack).setOnClickListener(v -> {
            if (current().canGoBack()) current().goBack();
        });
        findViewById(R.id.btnForward).setOnClickListener(v -> {
            if (current().canGoForward()) current().goForward();
        });
        findViewById(R.id.btnHome).setOnClickListener(v -> current().loadUrl(HOME));
        findViewById(R.id.btnReload).setOnClickListener(v -> current().reload());
    }

    private void loadFromBar() {
        current().loadUrl(toUrl(urlBar.getText().toString()));
        urlBar.clearFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
    }

    private String toUrl(String input) {
        String t = input.trim();
        if (t.isEmpty()) return HOME;
        if (t.startsWith("http://") || t.startsWith("https://")) return t;
        if (!t.contains(" ") && (t.contains(".") || t.startsWith("localhost"))) {
            return "https://" + t;
        }
        return SEARCH + Uri.encode(t);
    }

    private void exitFullscreen() {
        if (customView == null) return;
        fullscreen.removeView(customView);
        fullscreen.setVisibility(View.GONE);
        customView = null;
        if (customCallback != null) customCallback.onCustomViewHidden();
        customCallback = null;
        webContainer.setVisibility(View.VISIBLE);
        topBar.setVisibility(View.VISIBLE);
        bottomBar.setVisibility(View.VISIBLE);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_VISIBLE | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getData() != null) newTab(intent.getData().toString());
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            exitFullscreen();
        } else if (current().canGoBack()) {
            current().goBack();
        } else if (tabs.size() > 1) {
            closeTab(currentIndex);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (current() != null) current().onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (current() != null) current().onResume();
    }

    @Override
    protected void onDestroy() {
        for (WebView w : tabs) {
            w.destroy();
        }
        tabs.clear();
        super.onDestroy();
    }
    }
