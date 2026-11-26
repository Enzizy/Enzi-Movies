package com.truelockmc.streambert;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

@OptIn(markerClass = UnstableApi.class)
public final class PlayerActivity extends Activity {
    private FrameLayout frame;
    private LinearLayout screen;
    private TextView message;
    private View fullScreen;
    private WebChromeClient.CustomViewCallback fullScreenCallback;
    private WebView web;
    private PlayerView playerView;
    private ExoPlayer player;
    private long position;
    private long duration;
    private boolean playWhenReady = true;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        frame.addView(screen, -1, -1);
        if (Build.VERSION.SDK_INT >= 35) {
            frame.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                screen.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        Button back = new Button(this);
        back.setText("Back");
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        TextView title = new TextView(this);
        title.setText(getIntent().getStringExtra("title"));
        title.setTextSize(16);
        title.setPadding(16, 0, 16, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        screen.addView(bar);
        message = new TextView(this);
        message.setVisibility(View.GONE);
        message.setPadding(24, 24, 24, 24);
        screen.addView(message, -1, -2);
        setContentView(frame);

        Uri uri = getIntent().getData();
        if (uri == null) {
            finish();
            return;
        }
        position = state == null ? getIntent().getLongExtra("start", 0) : state.getLong("position");
        playWhenReady = state == null || state.getBoolean("playWhenReady", true);
        if ("page".equals(getIntent().getStringExtra("kind"))) {
            openWebPlayer(uri);
        } else {
            playerView = new PlayerView(this);
            playerView.setKeepScreenOn(true);
            screen.addView(playerView, new LinearLayout.LayoutParams(-1, 0, 1));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (playerView == null) return;
        MediaItem.Builder item = new MediaItem.Builder().setUri(getIntent().getData());
        if ("hls".equals(getIntent().getStringExtra("kind"))) item.setMimeType(MimeTypes.APPLICATION_M3U8);
        player = new ExoPlayer.Builder(this)
                .setAudioAttributes(AudioAttributes.DEFAULT, true)
                .setHandleAudioBecomingNoisy(true)
                .build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                showMessage("Could not play this media. Check the link or file and try again.");
            }
        });
        player.setMediaItem(item.build(), position);
        player.setPlayWhenReady(playWhenReady);
        player.prepare();
    }

    private void openWebPlayer(Uri uri) {
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setSupportMultipleWindows(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri target = request.getUrl();
                if (!"https".equals(target.getScheme())) return true;
                if (!request.isForMainFrame()) return false;
                return !uri.getHost().equalsIgnoreCase(target.getHost());
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) showMessage("This provider could not be opened.");
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                    android.webkit.WebResourceResponse response) {
                if (request.isForMainFrame()) showMessage("The provider returned HTTP " + response.getStatusCode() + ".");
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullScreen != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                fullScreen = view;
                fullScreenCallback = callback;
                screen.setVisibility(View.GONE);
                frame.addView(view, -1, -1);
            }

            @Override
            public void onHideCustomView() {
                exitFullScreen();
            }
        });
        screen.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        web.loadUrl(uri.toString());
    }

    private void showMessage(String text) {
        message.setText(text);
        message.setVisibility(View.VISIBLE);
    }

    private void exitFullScreen() {
        if (fullScreen == null) return;
        frame.removeView(fullScreen);
        fullScreen = null;
        screen.setVisibility(View.VISIBLE);
        fullScreenCallback.onCustomViewHidden();
        fullScreenCallback = null;
    }

    private void capturePosition() {
        if (player == null) return;
        position = player.getCurrentPosition();
        duration = Math.max(0, player.getDuration());
        playWhenReady = player.getPlayWhenReady();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onPause() {
        if (web != null) {
            web.evaluateJavascript("document.querySelectorAll('video,audio').forEach(e=>e.pause())", null);
            web.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (player != null) {
            capturePosition();
            playerView.setPlayer(null);
            player.release();
            player = null;
        }
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        capturePosition();
        state.putLong("position", position);
        state.putBoolean("playWhenReady", playWhenReady);
        super.onSaveInstanceState(state);
    }

    @Override
    public void onBackPressed() {
        if (fullScreen != null) exitFullScreen();
        else if (web != null && web.canGoBack()) web.goBack();
        else finish();
    }

    @Override
    public void finish() {
        capturePosition();
        setResult(RESULT_OK, new Intent().putExtra("position", position).putExtra("duration", duration));
        super.finish();
    }

    @Override
    protected void onDestroy() {
        exitFullScreen();
        if (web != null) {
            screen.removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }
}
