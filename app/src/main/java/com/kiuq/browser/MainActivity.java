package com.kiuq.browser;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String START_PAGE = "file:///android_asset/home.html";
    private static final String HOME = START_PAGE;
    private static final String SEARCH = "https://search.brave.com/search?q=";
    private static final int MAX_HISTORY = 500;
    private static final int FILE_CHOOSER = 1001;

    private static class HistoryItem {
        String title = "";
        String url = "";
        long time = 0;
    }

    private final ArrayList<WebView> tabs = new ArrayList<>();
    private final ArrayList<HistoryItem> history = new ArrayList<>();
    private int currentIndex = 0;
    private SharedPreferences prefs;
    private ValueCallback<Uri[]> filePathCallback;

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
            if (scheme.equals("file")) {
                // local pages may link to other local files; web pages may not
                String cur = view.getUrl();
                return !(cur != null && cur.startsWith("file:"));
            }
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
            if (!urlBar.hasFocus()) urlBar.setText(shown(url));
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            addHistory(url, view.getTitle());
            if (view != current()) return;
            progress.setVisibility(View.GONE);
            if (!urlBar.hasFocus()) urlBar.setText(shown(url));
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
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(null);
            }
            filePathCallback = callback;
            try {
                startActivityForResult(params.createIntent(), FILE_CHOOSER);
            } catch (Exception e) {
                filePathCallback = null;
                toast("Can't open file picker");
                return false;
            }
            return true;
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

        prefs = getSharedPreferences("kiuq", Context.MODE_PRIVATE);
        loadHistory();

        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }

        CookieManager.getInstance().setAcceptCookie(true);
        setupControls();

        Uri data = getIntent().getData();
        if (data != null) {
            openIncoming(data);
        } else {
            newTab(HOME);
        }
    }

    // ---------- Start page ----------

    private String shown(String url) {
        if (url == null || url.startsWith("file:///android_asset/")) return "";
        return url;
    }

    // ---------- Local files ----------

    private boolean hasFileAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        if (Build.VERSION.SDK_INT >= 29) return false;
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void askAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            new AlertDialog.Builder(this)
                    .setTitle("Allow file access")
                    .setMessage("To open local HTML files together with their CSS and JS, "
                            + "turn on \"Allow access to manage all files\" for kiuQ, "
                            + "then open the file again.")
                    .setPositiveButton("Open settings", (dlg, which) -> {
                        try {
                            startActivity(new Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Exception e) {
                            startActivity(new Intent(
                                    Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } else if (Build.VERSION.SDK_INT < 29) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        } else {
            toast("Local file access is not supported on Android 10");
        }
    }

    private String guessPath(Uri uri) {
        try {
            String auth = uri.getAuthority();
            if ("com.android.externalstorage.documents".equals(auth)) {
                String docId = DocumentsContract.getDocumentId(uri);
                if (docId != null && docId.startsWith("primary:")) {
                    return Environment.getExternalStorageDirectory().getPath()
                            + "/" + docId.substring(8);
                }
                return null;
            }
            String decoded = Uri.decode(uri.toString());
            int idx = decoded.indexOf("file:///");
            if (idx >= 0) return decoded.substring(idx + 7);
            String p = uri.getPath();
            if (p != null) {
                int j = p.indexOf("/storage/emulated/0/");
                if (j >= 0) return p.substring(j);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void openIncoming(Uri data) {
        String scheme = data.getScheme();
        String target = data.toString();
        if ("file".equals(scheme)) {
            if (!hasFileAccess()) {
                askAllFilesAccess();
                newTab(HOME);
                return;
            }
        } else if ("content".equals(scheme)) {
            String path = guessPath(data);
            if (path != null) {
                File f = new File(path);
                if (hasFileAccess() && f.exists()) {
                    target = Uri.fromFile(f).toString();
                } else if (!prefs.getBoolean("askedFiles", false)) {
                    prefs.edit().putBoolean("askedFiles", true).apply();
                    askAllFilesAccess();
                }
            }
        }
        newTab(target);
    }

    // ---------- History ----------

    private void loadHistory() {
        history.clear();
        try {
            JSONArray arr = new JSONArray(prefs.getString("history", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                HistoryItem h = new HistoryItem();
                h.title = o.optString("t", "");
                h.url = o.optString("u", "");
                h.time = o.optLong("d", 0);
                if (!h.url.isEmpty()) history.add(h);
            }
        } catch (Exception ignored) {
        }
    }

    private void saveHistory() {
        try {
            JSONArray arr = new JSONArray();
            for (HistoryItem h : history) {
                JSONObject o = new JSONObject();
                o.put("t", h.title);
                o.put("u", h.url);
                o.put("d", h.time);
                arr.put(o);
            }
            prefs.edit().putString("history", arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private void addHistory(String url, String title) {
        if (url == null) return;
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return;
        if (title == null) title = "";
        long now = System.currentTimeMillis();
        if (!history.isEmpty() && history.get(0).url.equals(url)) {
            HistoryItem top = history.get(0);
            top.time = now;
            if (!title.isEmpty()) top.title = title;
            saveHistory();
            return;
        }
        HistoryItem h = new HistoryItem();
        h.title = title;
        h.url = url;
        h.time = now;
        history.add(0, h);
        while (history.size() > MAX_HISTORY) {
            history.remove(history.size() - 1);
        }
        saveHistory();
    }

    private void showHistory() {
        if (history.isEmpty()) {
            toast("History is empty");
            return;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault());
        CharSequence[] items = new CharSequence[history.size()];
        for (int i = 0; i < history.size(); i++) {
            HistoryItem h = history.get(i);
            String t = h.title.isEmpty() ? h.url : h.title;
            if (t.length() > 60) t = t.substring(0, 60) + "...";
            String u = h.url;
            if (u.length() > 60) u = u.substring(0, 60) + "...";
            items[i] = t + "\n" + fmt.format(new Date(h.time)) + " - " + u;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("History (" + history.size() + ") - hold to delete");
        b.setItems(items, (dlg, which) -> current().loadUrl(history.get(which).url));
        b.setNegativeButton("Clear all", (dlg, which) -> confirmClearHistory());
        final AlertDialog d = b.create();
        d.show();
        d.getListView().setOnItemLongClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < history.size()) {
                history.remove(position);
                saveHistory();
            }
            d.dismiss();
            showHistory();
            return true;
        });
    }

    private void confirmClearHistory() {
        new AlertDialog.Builder(this)
                .setTitle("Clear all history?")
                .setPositiveButton("Clear", (dlg, which) -> {
                    history.clear();
                    saveHistory();
                    toast("History cleared");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------- Tabs ----------

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
        s.setAllowFileAccess(true);
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
        urlBar.setText(shown(tabs.get(index).getUrl()));
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
        b.setNeutralButton("History", (dlg, which) -> showHistory());
        final AlertDialog d = b.create();
        d.show();
        d.getListView().setOnItemLongClickListener((parent, view, position, id) -> {
            closeTab(position);
            d.dismiss();
            showTabs();
            return true;
        });
    }

    // ---------- Controls ----------

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
        View home = findViewById(R.id.btnHome);
        home.setOnClickListener(v -> current().loadUrl(HOME));
        home.setOnLongClickListener(v -> {
            showHistory();
            return true;
        });
        findViewById(R.id.btnReload).setOnClickListener(v -> current().reload());
    }

    private void loadFromBar() {
        String url = toUrl(urlBar.getText().toString());
        urlBar.clearFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
        if (url.startsWith("file://") && !url.startsWith(START_PAGE) && !hasFileAccess()) {
            askAllFilesAccess();
            return;
        }
        current().loadUrl(url);
    }

    private String toUrl(String input) {
        String t = input.trim();
        if (t.isEmpty()) return HOME;
        if (t.startsWith("http://") || t.startsWith("https://")) return t;
        if (t.startsWith("file://")) return t;
        if (t.startsWith("/storage/") || t.startsWith("/sdcard/")) {
            return "file://" + t.replace(" ", "%20");
        }
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
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER && filePathCallback != null) {
            filePathCallback.onReceiveValue(
                    WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            filePathCallback = null;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getData() != null) openIncoming(intent.getData());
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
