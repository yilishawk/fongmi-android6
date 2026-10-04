package com.fongmi.android.tv.player.vlc;

import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.player.effect.PlayerEffect;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.media.MediaItemFactory;
import com.fongmi.android.tv.player.media.PlaySpec;

import org.videolan.libvlc.LibVLC;

/**
 * VLC 引擎 —— {@link PlayerEngine} 对 {@link VlcPlayer} 的包装。
 *
 * <p>结构与 {@code MpvPlayerEngine} 一一对应（同一套 {@code PlayerEngine} 契约），
 * 差别只在底层：mpv 走 {@code MpvUtil.buildPlayer()}，这里走 {@link VlcUtil#create}
 * + {@link VlcPlayer}。</p>
 *
 * <h2>可用性门禁</h2>
 *
 * {@link #isAvailable()} 只做一件事：问 {@link VlcUtil#isLoadable()}「libvlc/libvlcjni
 * 能不能进这个进程」。这是<b>唯一</b>能在不触发 {@code LibVLC.loadLibraries()} 那个
 * 不可捕获的 {@code System.exit(1)} 的前提下拿到的判据。
 *
 * <p>⇒ native 不可用时，引擎工厂会退回 EXO，与"这个 App 没有 VLC 引擎"完全一致；
 * 用户即使把设置选成 VLC，也不会崩、不会黑屏，只是拿到的其实是 EXO。</p>
 */
public class VlcPlayerEngine implements PlayerEngine {

    private static final String TAG = "VlcPlayerEngine";

    private final VlcErrorMessageProvider provider = new VlcErrorMessageProvider();
    private final VlcPlayer player;

    /** 仅用于错误恢复判断；VLC 侧目前不重试，保留字段是为了与 MpvPlayerEngine 结构对齐。 */
    private PlaySpec spec;

    public VlcPlayerEngine(int decode, Player.Listener listener) {
        LibVLC libVlc = VlcUtil.create(App.get());
        if (libVlc == null) throw new IllegalStateException("libVLC is not loadable");
        this.player = new VlcPlayer(Looper.getMainLooper(), libVlc, decode);
        this.player.addListener(listener);
    }

    /**
     * native 层能不能用。引擎工厂在 {@code resolve()} 与 {@code create()} 两处都会问它。
     *
     * <p>注意：这是"能加载"，<b>不等于</b>"能出画" —— 后者只有真机能答
     * （见 {@code .gradle-user/VLC接入-甲方案设计与回滚.md} §5.2/§5.3）。</p>
     */
    public static boolean isAvailable() {
        return VlcUtil.isLoadable();
    }

    @Override
    public Type getType() {
        return Type.VLC;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    @Override
    public int getAudioChannelCount() {
        return player.getAudioChannelCount();
    }

    @Override
    public PlayerEffect getEffect() {
        // VLC 这一侧没有 mpv 那套「视频效果链」（着色器 / 锐化 / 音频均衡）。
        // 返回 NONE ⇒ 上层 VideoSettingPanel 会整体走「不支持」分支，
        // 不会出现「面板能开、实际没效果」的假象。
        return PlayerEffect.NONE;
    }

    @Override
    public void release() {
        player.release();
    }

    @Override
    public void setDecode(int decode) {
        player.setDecode(decode);
    }

    @Override
    public void start(PlaySpec spec, long startPositionMs) {
        this.spec = spec;
        if (spec == null || TextUtils.isEmpty(spec.getUrl())) {
            Log.w(TAG, "start skipped: empty spec");
            return;
        }
        try {
            MediaItem item = MediaItemFactory.from(spec);
            player.setMediaItem(item, startPositionMs);
            player.prepare();
            player.play();
        } catch (Throwable e) {
            Log.e(TAG, "start failed", e);
        }
    }

    @Override
    public void stop() {
        try {
            player.stop();
        } catch (Throwable e) {
            Log.e(TAG, "stop failed", e);
        }
    }

    @Override
    public void applySubtitleStyle() {
        // VLC 的字幕样式由 libvlc 自己（sub-style / libass）决定，本引擎不下发。
    }

    @Override
    public boolean addSubtitle(Sub sub) {
        // 首版不接外挂字幕，见 VlcPlayer.addSubtitle 的说明。
        // 返回 false ⇒ PlayerManager.setSub() 会走 startCurrent() 重建播放，功能不丢。
        return false;
    }

    @Override
    public String getErrorMessage(PlaybackException e) {
        return provider.get(e);
    }

    @Override
    public ErrorAction handleError(PlaybackException e) {
        // VLC 的错误不细分到「解码器 / IO / 容器」这一层，且本引擎没有可用的重试路径
        // （不像 mpv 那条 retryHls 的 M3U8 重试）。统一交给上层按致命错误处理。
        return ErrorAction.FATAL;
    }
}
