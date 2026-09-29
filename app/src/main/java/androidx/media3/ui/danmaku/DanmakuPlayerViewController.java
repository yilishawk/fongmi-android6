package androidx.media3.ui.danmaku;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.ui.PlayerView;

import okhttp3.OkHttpClient;

/**
 * Adapter for the newer FongMi {@code DanmakuPlayerViewController}, implemented on top of the
 * danmaku facade that {@link PlayerView} already exposes in this media3 build.
 *
 * <p><b>Why this exists.</b> FongMi's private media3 refactored danmaku control: older builds drive
 * it through {@code PlayerView.setDanmaku*()} (which is what fish2018/webhtv uses), while this
 * project's source constructs a standalone {@code DanmakuPlayerViewController} and binds it to a
 * view. The two are the same machinery — {@code PlayerView} is itself only a facade:
 *
 * <pre>
 *   PlayerView.setDanmakuSource(uri)   -> danmakuController.setDataSource(uri)
 *   PlayerView.setDanmakuConfig(c)     -> danmakuController.setConfig(c)
 *   PlayerView.setDanmakuEnabled(b)    -> danmakuController.setEnabled(b)
 *   PlayerView.setDanmakuOkHttpClient(c) -> danmakuController.setOkHttpClient(c)
 *   PlayerView.sendDanmaku(text)       -> danmakuController.sendNow(text)
 * </pre>
 *
 * <p>So this class delegates to that facade rather than stubbing anything out. <b>Danmaku keeps
 * working</b> — including Bilibili/Youku/QQ/iQiyi/MGTV fetching and parsing, which live in the
 * {@code media3-ui-danmaku} module this project already depends on.
 *
 * <p>The only subtlety is ordering. {@code PlaybackActivity} calls the setters from
 * {@code configurePlayerView()} <i>before</i> {@code bind()} happens in {@code syncPlayerView()},
 * so values are cached here and flushed on bind.
 *
 * <p>If a real {@code lib-*.aar} supplying the genuine class is ever restored, delete this file —
 * nothing else references it.
 */
public final class DanmakuPlayerViewController {

    private @Nullable PlayerView playerView;
    private @Nullable OkHttpClient okHttpClient;
    private @Nullable DanmakuConfig config;
    private @Nullable Uri dataSource;
    private boolean dataSourceSet;
    private boolean enabled;

    /** Binds the view this controller drives, replaying any settings applied beforehand. */
    public void bind(@Nullable PlayerView playerView) {
        this.playerView = playerView;
        if (playerView == null) return;
        if (okHttpClient != null) playerView.setDanmakuOkHttpClient(okHttpClient);
        if (config != null) playerView.setDanmakuConfig(config);
        if (dataSourceSet) playerView.setDanmakuSource(dataSource);
        playerView.setDanmakuEnabled(enabled);
    }

    /** The bound view, or {@code null}. */
    @Nullable
    public PlayerView getPlayerView() {
        return playerView;
    }

    /** Sets the HTTP client used to fetch remote danmaku sources. */
    public void setOkHttpClient(@Nullable OkHttpClient okHttpClient) {
        this.okHttpClient = okHttpClient;
        if (playerView != null) playerView.setDanmakuOkHttpClient(okHttpClient);
    }

    /** Enables or disables danmaku rendering. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (playerView != null) playerView.setDanmakuEnabled(enabled);
    }

    /** Applies the danmaku rendering configuration. */
    public void setConfig(@Nullable DanmakuConfig config) {
        this.config = config;
        if (playerView != null) playerView.setDanmakuConfig(config);
    }

    /**
     * Sets the danmaku source, or {@code null} to clear the loaded items. Applied immediately when
     * a view is bound, otherwise replayed on the next {@link #bind}.
     */
    public void setDataSource(@Nullable Uri uri) {
        this.dataSource = uri;
        this.dataSourceSet = true;
        if (playerView != null) playerView.setDanmakuSource(uri);
    }

    /** Sends a danmaku item at the current playback position. */
    public void sendNow(String text) {
        if (playerView != null) playerView.sendDanmaku(text);
    }

    /** Detaches from the view. The view keeps whatever danmaku state it already holds. */
    public void close() {
        playerView = null;
    }
}
