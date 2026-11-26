package com.truelockmc.streambert;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.util.List;

@OptIn(markerClass = UnstableApi.class)
final class PlaybackView extends FrameLayout {
    record Source(Uri uri, String kind, String id, String label) {}
    record Result(long position, long duration, String sourceId) {}

    private final FrameLayout videoSurface;
    private final TextView message;
    private final List<Source> servers;
    private Source source;
    private PopupMenu serverMenu;
    private View fullScreen;
    private WebChromeClient.CustomViewCallback fullScreenCallback;
    private WebView web;
    private PlayerView playerView;
    private ExoPlayer player;
    private long position;
    private long duration;
    private boolean playWhenReady = true;

    PlaybackView(Context context, Source source, List<Source> servers, String title, long start, Runnable onClose) {
        super(context);
        this.source = source;
        this.servers = servers;
        position = start;
        setBackgroundColor(Color.BLACK);
        ViewCompat.setAccessibilityPaneTitle(this, title);
        videoSurface = new FrameLayout(context);
        addView(videoSurface, -1, -1);

        message = new TextView(context);
        message.setVisibility(View.GONE);
        message.setGravity(Gravity.CENTER);
        message.setTextColor(Color.WHITE);
        message.setBackgroundColor(0xdd151515);
        message.setPadding(dp(16), dp(12), dp(16), dp(12));
        FrameLayout.LayoutParams alert = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        alert.setMargins(dp(24), dp(24), dp(24), dp(24));
        addView(message, alert);

        ImageButton close = new ImageButton(context);
        close.setContentDescription(context.getString(R.string.close_player));
        close.setImageResource(R.drawable.ic_close_player);
        close.setBackgroundResource(R.drawable.player_close_background);
        close.setOnClickListener(v -> onClose.run());
        FrameLayout.LayoutParams closeLayout =
                new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.START);
        setControlMargins(closeLayout, 0, 0, true);
        addView(close, closeLayout);

        Button serverButton = new Button(context);
        FrameLayout.LayoutParams serverLayout =
                new FrameLayout.LayoutParams(-2, dp(48), Gravity.TOP | Gravity.END);
        if (servers.size() > 1) {
            serverButton.setText(R.string.player_servers);
            serverButton.setAllCaps(false);
            serverButton.setTextColor(Color.WHITE);
            serverButton.setPadding(dp(16), 0, dp(16), 0);
            serverButton.setBackgroundResource(R.drawable.player_server_background);
            serverButton.setOnClickListener(this::showServers);
            setControlMargins(serverLayout, 0, 0, false);
            addView(serverButton, serverLayout);
        }
        ViewCompat.setOnApplyWindowInsetsListener(this, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.displayCutout());
            boolean rtl = ViewCompat.getLayoutDirection(view) == ViewCompat.LAYOUT_DIRECTION_RTL;
            setControlMargins(closeLayout, safe.top, rtl ? safe.right : safe.left, true);
            close.setLayoutParams(closeLayout);
            if (serverButton.getParent() != null) {
                setControlMargins(serverLayout, safe.top, rtl ? safe.left : safe.right, false);
                serverButton.setLayoutParams(serverLayout);
            }
            return insets;
        });
        openSource();
    }

    private void setControlMargins(LayoutParams layout, int top, int side, boolean start) {
        layout.topMargin = top + dp(12);
        if (start) layout.setMarginStart(side + dp(12));
        else layout.setMarginEnd(side + dp(12));
    }

    private void showServers(View anchor) {
        PopupMenu menu = new PopupMenu(getContext(), anchor, Gravity.END);
        serverMenu = menu;
        for (int index = 0; index < servers.size(); index++) {
            Source option = servers.get(index);
            menu.getMenu().add(Menu.NONE, index + 1, index, option.label())
                    .setCheckable(true)
                    .setChecked(option.id().equals(source.id()));
        }
        menu.setOnMenuItemClickListener(item -> {
            Source selected = servers.get(item.getItemId() - 1);
            if (!selected.id().equals(source.id())) {
                releaseSurface();
                source = selected;
                position = 0;
                duration = 0;
                playWhenReady = true;
                openSource();
            }
            return true;
        });
        menu.setOnDismissListener(popup -> serverMenu = null);
        menu.show();
    }

    private void openSource() {
        message.setVisibility(View.GONE);
        if ("page".equals(source.kind())) {
            openWebPlayer(source.uri());
        } else {
            playerView = new PlayerView(getContext());
            playerView.setKeepScreenOn(true);
            videoSurface.addView(playerView, -1, -1);
            startNativePlayer();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void startNativePlayer() {
        if (playerView == null || player != null) return;
        MediaItem.Builder item = new MediaItem.Builder().setUri(source.uri());
        if ("hls".equals(source.kind())) item.setMimeType(MimeTypes.APPLICATION_M3U8);
        player = new ExoPlayer.Builder(getContext())
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
        WebView provider = new WebView(getContext());
        web = provider;
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
                if (web == view && request.isForMainFrame()) showMessage("This provider could not be opened.");
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                    android.webkit.WebResourceResponse response) {
                if (web == view && request.isForMainFrame()) {
                    showMessage("The provider returned HTTP " + response.getStatusCode() + ".");
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (web != provider || fullScreen != null) {
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
                if (web == provider) exitFullScreen();
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
        WebChromeClient.CustomViewCallback callback = fullScreenCallback;
        fullScreenCallback = null;
        callback.onCustomViewHidden();
    }

    private void capturePosition() {
        if (player == null) return;
        position = player.getCurrentPosition();
        duration = Math.max(0, player.getDuration());
        playWhenReady = player.getPlayWhenReady();
    }

    void resume() {
        if (web != null) web.onResume();
        startNativePlayer();
    }

    void pause() {
        if (serverMenu != null) serverMenu.dismiss();
        if (web != null) {
            web.evaluateJavascript("document.querySelectorAll('video,audio').forEach(e=>e.pause())", null);
            web.onPause();
        }
        stopNativePlayer();
    }

    private void stopNativePlayer() {
        if (player != null) {
            capturePosition();
            playerView.setPlayer(null);
            player.release();
            player = null;
        }
    }

    Result release() {
        if (serverMenu != null) serverMenu.dismiss();
        releaseSurface();
        return new Result(position, duration, source.id());
    }

    private void releaseSurface() {
        exitFullScreen();
        if (web != null) {
            WebView previous = web;
            web = null;
            previous.stopLoading();
            videoSurface.removeView(previous);
            previous.destroy();
        }
        stopNativePlayer();
        if (playerView != null) {
            videoSurface.removeView(playerView);
            playerView = null;
        }
    }
}
