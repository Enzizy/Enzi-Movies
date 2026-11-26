package com.truelockmc.streambert;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
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
    private FrameLayout videoSurface;
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
        setTitle(getIntent().getStringExtra("title"));
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        videoSurface = new FrameLayout(this);
        frame.addView(videoSurface, -1, -1);

        message = new TextView(this);
        message.setVisibility(View.GONE);
        message.setGravity(Gravity.CENTER);
        message.setTextColor(Color.WHITE);
        message.setBackgroundColor(0xdd151515);
        message.setPadding(dp(16), dp(12), dp(16), dp(12));
        FrameLayout.LayoutParams alert = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        alert.setMargins(dp(24), dp(24), dp(24), dp(24));
        frame.addView(message, alert);

        ImageButton close = new ImageButton(this);
        close.setContentDescription(getString(R.string.close_player));
        close.setImageResource(R.drawable.ic_close_player);
        close.setBackgroundResource(R.drawable.player_close_background);
        close.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams closeLayout =
                new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.START);
        closeLayout.topMargin = dp(12);
        closeLayout.setMarginStart(dp(12));
        frame.addView(close, closeLayout);
        ViewCompat.setOnApplyWindowInsetsListener(frame, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.displayCutout());
            boolean rtl = ViewCompat.getLayoutDirection(view) == ViewCompat.LAYOUT_DIRECTION_RTL;
            closeLayout.topMargin = safe.top + dp(12);
            closeLayout.setMarginStart((rtl ? safe.right : safe.left) + dp(12));
            close.setLayoutParams(closeLayout);
            return insets;
        });
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
            videoSurface.addView(playerView, -1, -1);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            WindowInsetsControllerCompat controller =
                    WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsetsCompat.Type.systemBars());
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
        web.setKeepScreenOn(true);
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
                web.setVisibility(View.INVISIBLE);
                videoSurface.addView(view, -1, -1);
            }

            @Override
            public void onHideCustomView() {
                exitFullScreen();
            }
        });
        videoSurface.addView(web, -1, -1);
        web.loadUrl(uri.toString());
    }

    private void showMessage(String text) {
        message.setText(text);
        message.setVisibility(View.VISIBLE);
    }

    private void exitFullScreen() {
        if (fullScreen == null) return;
        videoSurface.removeView(fullScreen);
        fullScreen = null;
        web.setVisibility(View.VISIBLE);
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
        finish();
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
            videoSurface.removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }
}
