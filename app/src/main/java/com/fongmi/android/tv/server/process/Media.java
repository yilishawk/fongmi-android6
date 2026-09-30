package com.fongmi.android.tv.server.process;

import android.net.Uri;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.server.impl.Process;
import com.fongmi.android.tv.service.PlaybackService;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;

public class Media implements Process {

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith("/media");
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        PlaybackService service = Server.get().getService();
        if (service == null) return Nano.ok("{}");
        // 原实现用 CompletableFuture 把主线程的结果搬回本线程。但 CompletableFuture 是
        // **API 24** 才有的平台类（desugar_jdk_libs 2.1.5 的 desugar.json 里没有它，
        // 包内也没有对应类定义），本分支 minSdk=23 ⇒ NoClassDefFoundError。
        // 这里换成 API 1 就有的等价组合：主线程算好结果 -> 计数归零 -> 本线程取走。
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>("{}");
        App.post(() -> {
            result.set(build(service.player()).toString());
            latch.countDown();
        });
        try {
            latch.await();
        } catch (InterruptedException ignored) {
        }
        return Nano.ok(result.get());
    }

    private JsonObject build(PlayerManager player) {
        if (player.isReleased()) return new JsonObject();
        MediaItem item = player.getCurrentMediaItem();
        MediaMetadata meta = item != null ? item.mediaMetadata : MediaMetadata.EMPTY;
        JsonObject result = new JsonObject();
        result.addProperty("state", getState(player));
        result.addProperty("speed", player.getSpeed());
        result.addProperty("duration", player.getDuration());
        result.addProperty("position", player.getPosition());
        result.addProperty("url", getString(player.getUrl()));
        result.addProperty("title", getString(meta.title));
        result.addProperty("artist", getString(meta.artist));
        result.addProperty("artwork", getString(meta.artworkUri));
        return result;
    }

    private int getState(PlayerManager player) {
        if (player.isPlaying()) return 3;
        int state = player.getPlaybackState();
        if (state == Player.STATE_BUFFERING) return 6;
        if (state == Player.STATE_READY) return 2;
        return 1;
    }

    private String getString(CharSequence text) {
        return text != null ? text.toString() : "";
    }

    private String getString(Uri uri) {
        return uri != null ? uri.toString() : "";
    }
}