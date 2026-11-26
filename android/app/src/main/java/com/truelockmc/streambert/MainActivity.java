package com.truelockmc.streambert;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final int PICK_MEDIA = 1;

    private final ExecutorService workers = Executors.newFixedThreadPool(4);
    private FrameLayout root;
    private FrameLayout catalog;
    private WebView web;
    private DownloadManager downloads;
    private SharedPreferences downloadRecords;
    private TokenStore tokens;
    private PlaybackView playback;
    private Pending playbackReply;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            TextView message = new TextView(this);
            message.setText(R.string.webview_update_required);
            message.setPadding(32, 64, 32, 32);
            setContentView(message);
            return;
        }
        downloads = getSystemService(DownloadManager.class);
        downloadRecords = getSharedPreferences("downloads", MODE_PRIVATE);
        tokens = new TokenStore(getSharedPreferences("credentials", MODE_PRIVATE));
        WebViewAssetLoader assets = new WebViewAssetLoader.Builder()
                .addPathHandler("/app/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(17, 17, 17));
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                WebResourceResponse response = assets.shouldInterceptRequest(request.getUrl());
                if (response != null) {
                    Map<String, String> headers = new HashMap<>();
                    if (response.getResponseHeaders() != null) headers.putAll(response.getResponseHeaders());
                    headers.put("Content-Security-Policy", "frame-src 'none'; frame-ancestors 'none'; object-src 'none'; base-uri 'none'");
                    response.setResponseHeaders(headers);
                }
                return response;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (isAppPage(request.getUrl())) return false;
                if (request.isForMainFrame() && request.hasGesture()) openExternal(request.getUrl());
                return true;
            }
        });
        WebViewCompat.addWebMessageListener(web, "StreambertAndroid", Collections.singleton(ORIGIN),
                (view, message, source, mainFrame, proxy) -> {
                    if (!mainFrame || !ORIGIN.equals(source.toString()) || message.getData() == null) return;
                    receive(message.getData(), proxy);
                });
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attributes = getWindow().getAttributes();
            attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attributes);
        }
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        catalog = new FrameLayout(this);
        catalog.addView(web, -1, -1);
        root.addView(catalog, -1, -1);
        ViewCompat.setOnApplyWindowInsetsListener(catalog, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.ime() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });
        setContentView(root);
        web.loadUrl(ORIGIN + "/app/index.html");
    }

    private boolean isAppPage(Uri uri) {
        return "https".equals(uri.getScheme()) && "appassets.androidplatform.net".equals(uri.getHost())
                && uri.getPath() != null && uri.getPath().startsWith("/app/");
    }

    private void receive(String message, JavaScriptReplyProxy proxy) {
        if (message.length() > 16_384) return;
        try {
            JSONObject request = new JSONObject(message);
            Pending pending = new Pending(request.getInt("id"), proxy);
            switch (request.getString("op")) {
                case "tmdb":
                    workers.execute(() -> {
                        try {
                            JSONObject response = NativeCore.invoke(request);
                            runOnUiThread(() -> reply(pending, response, null));
                        } catch (Exception e) {
                            runOnUiThread(() -> reply(pending, null, "TMDB request failed"));
                        }
                    });
                    return;
                case "secureGet":
                    reply(pending, "apikey".equals(request.optString("key")) ? tokens.get() : null, null);
                    return;
                case "secureSet":
                    if ("apikey".equals(request.optString("key"))) tokens.set(request.optString("value"));
                    break;
                case "openExternal":
                    openExternal(Uri.parse(request.getString("url")));
                    break;
                case "play":
                    PlaybackView.Source source = remoteSource(request.optString("sourceId"), "",
                            request.getString("url"));
                    launchPlayer(source, playbackServers(request.optJSONArray("servers")),
                            request.optString("title", getString(R.string.app_name)),
                            request.optDouble("start", 0), pending);
                    return;
                case "pickMedia":
                    if (playback != null) throw new IllegalStateException("A player is already open");
                    Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("video/*");
                    startActivityForResult(picker, PICK_MEDIA);
                    break;
                case "download":
                    reply(pending, enqueue(request.getString("url")), null);
                    return;
                case "downloads":
                    reply(pending, listDownloads(), null);
                    return;
                case "playDownload":
                    long id = downloadId(request);
                    Uri item = downloads.getUriForDownloadedFile(id);
                    if (item == null) throw new IllegalArgumentException("Download is unavailable");
                    launchPlayer(new PlaybackView.Source(item, "file", "", ""), Collections.emptyList(),
                            downloadRecords.getString(Long.toString(id), "Download"), 0, pending);
                    return;
                case "removeDownload":
                    long removeId = downloadId(request);
                    downloads.remove(removeId);
                    downloadRecords.edit().remove(Long.toString(removeId)).apply();
                    break;
                default:
                    reply(pending, null, "Android action is unavailable");
                    return;
            }
            reply(pending, true, null);
        } catch (Exception e) {
            try {
                JSONObject request = new JSONObject(message);
                String reason = e instanceof IllegalArgumentException || e instanceof IllegalStateException
                        ? e.getMessage() : "Android action failed";
                reply(new Pending(request.getInt("id"), proxy), null, reason);
            } catch (JSONException ignored) {
                // Messages without an ID cannot receive a response.
            }
        }
    }

    private static JSONObject mediaRequest(String url) throws JSONException {
        return new JSONObject().put("op", "media").put("url", url);
    }

    private void openExternal(Uri uri) {
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException ignored) {
            android.widget.Toast.makeText(this, "No browser is available", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private static PlaybackView.Source remoteSource(String id, String label, String url) throws JSONException {
        JSONObject media = NativeCore.invoke(mediaRequest(url));
        return new PlaybackView.Source(Uri.parse(media.getString("url")), media.getString("kind"), id, label);
    }

    private static List<PlaybackView.Source> playbackServers(JSONArray entries) {
        if (entries == null) return Collections.emptyList();
        List<PlaybackView.Source> servers = new ArrayList<>();
        for (int index = 0; index < entries.length(); index++) {
            try {
                JSONObject entry = entries.getJSONObject(index);
                servers.add(remoteSource(entry.getString("id"), entry.getString("label"), entry.getString("url")));
            } catch (JSONException | IllegalArgumentException ignored) {
                // An unavailable alternative must not block the selected server.
            }
        }
        return servers;
    }

    private void launchPlayer(PlaybackView.Source source, List<PlaybackView.Source> servers,
            String title, double start, Pending pending) {
        if (playback != null) throw new IllegalStateException("A player is already open");
        playback = new PlaybackView(this, source, servers, title,
                (long) (Math.max(0, start) * 1000), this::closePlayback);
        playbackReply = pending;
        InputMethodManager keyboard = getSystemService(InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(web.getWindowToken(), 0);
        catalog.setVisibility(View.INVISIBLE);
        root.addView(playback, -1, -1);
        ViewCompat.requestApplyInsets(playback);
        hidePlayerBars();
    }

    private WindowInsetsControllerCompat windowInsetsController() {
        return WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
    }

    private void hidePlayerBars() {
        WindowInsetsControllerCompat controller = windowInsetsController();
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());
    }

    private void closePlayback() {
        if (playback == null) return;
        PlaybackView.Result result = playback.release();
        root.removeView(playback);
        playback = null;
        catalog.setVisibility(View.VISIBLE);
        windowInsetsController().show(WindowInsetsCompat.Type.systemBars());
        Pending pending = playbackReply;
        playbackReply = null;
        try {
            reply(pending, new JSONObject()
                    .put("position", result.position())
                    .put("duration", result.duration())
                    .put("sourceId", result.sourceId()), null);
        } catch (JSONException e) {
            reply(pending, null, "Could not read playback progress");
        }
    }

    private long enqueue(String url) throws JSONException {
        JSONObject media = NativeCore.invoke(mediaRequest(url));
        if (!"file".equals(media.getString("kind"))) {
            throw new IllegalArgumentException("Downloads require a direct media file link");
        }
        Uri uri = Uri.parse(media.getString("url"));
        String name = uri.getLastPathSegment();
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("File name is missing");
        name = name.replaceAll("[^\\p{L}\\p{N} ._()-]", "_");
        if (name.length() > 100) name = name.substring(name.length() - 100);
        String destination = UUID.randomUUID() + "-" + name;
        DownloadManager.Request request = new DownloadManager.Request(uri)
                .setTitle(name)
                .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_MOVIES, destination)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
        long id = downloads.enqueue(request);
        if (!downloadRecords.edit().putString(Long.toString(id), name).commit()) {
            downloads.remove(id);
            throw new IllegalStateException("Could not save download");
        }
        return id;
    }

    private JSONArray listDownloads() throws JSONException {
        JSONArray entries = new JSONArray();
        List<Long> ids = new ArrayList<>();
        for (String key : downloadRecords.getAll().keySet()) ids.add(Long.parseLong(key));
        ids.sort(Comparator.reverseOrder());
        for (long id : ids) {
            String key = Long.toString(id);
            try (Cursor cursor = downloads.query(new DownloadManager.Query().setFilterById(id))) {
                if (cursor == null || !cursor.moveToFirst()) continue;
                int state = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                String status = switch (state) {
                    case DownloadManager.STATUS_SUCCESSFUL -> "complete";
                    case DownloadManager.STATUS_FAILED -> "failed";
                    case DownloadManager.STATUS_PAUSED -> "paused";
                    case DownloadManager.STATUS_RUNNING -> "downloading";
                    default -> "queued";
                };
                entries.put(new JSONObject().put("id", key)
                        .put("name", downloadRecords.getString(key, "Download"))
                        .put("status", status)
                        .put("received", cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)))
                        .put("total", cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))));
            }
        }
        return entries;
    }

    private long downloadId(JSONObject request) throws JSONException {
        String id = request.getString("downloadId");
        if (!downloadRecords.contains(id)) throw new IllegalArgumentException("Download is unavailable");
        return Long.parseLong(id);
    }

    private void reply(Pending pending, Object value, String error) {
        if (isDestroyed() || pending == null) return;
        try {
            JSONObject response = new JSONObject().put("id", pending.id);
            if (error == null) response.put("value", value == null ? JSONObject.NULL : value);
            else response.put("error", error);
            pending.proxy.postMessage(response.toString());
        } catch (JSONException ignored) {
            // The values above are JSON primitives or validated JSON objects.
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_MEDIA && result == RESULT_OK && data != null
                && data.getData() != null && playback == null) {
            launchPlayer(new PlaybackView.Source(data.getData(), "file", "", ""),
                    Collections.emptyList(), "Local video", 0, null);
        }
    }

    @Override
    public void onBackPressed() {
        if (playback != null) {
            closePlayback();
        } else if (web == null) {
            super.onBackPressed();
        } else {
            web.evaluateJavascript("window.streambertBack?.() === true", handled -> {
                if (!"true".equals(handled)) super.onBackPressed();
            });
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && playback != null) hidePlayerBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (playback != null) playback.resume();
    }

    @Override
    protected void onPause() {
        if (playback != null) playback.pause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        workers.shutdownNow();
        if (playback != null) playback.release();
        if (web != null) {
            ((android.view.ViewGroup) web.getParent()).removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }

    private record Pending(int id, JavaScriptReplyProxy proxy) {}
}
