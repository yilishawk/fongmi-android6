package com.fongmi.android.tv.player.mpv;

import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.mpvplayer.MpvPlayer;

import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.player.effect.PlayerEffect;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.media.MediaItemFactory;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.player.mpv.hls.MpvHlsProxy;
import com.github.catvod.utils.Path;

import java.util.Locale;

// 2026-09-28 —— 副字幕（secondary subtitle）在本分支的 MPV 引擎上不再支持。
//
// 原因不是设计取舍，是依赖缺口：
//   FongMi/TV 5.6.3 随包发的 app/libs/lib-*.aar（.gitignore 里是 `lib-*.aar`，未入 Git）
//   来自一个**比公开的 FongMi/media 更新的修订**。公开的
//   FongMi/media@release-1.11.0-fongmi 里，libraries/mpvplayer 的 MpvPlayer /
//   MpvSubtitleOptions 没有这套 API（已用 GitHub 原文与本地检出逐字核对，两边一致）：
//       MpvPlayer.getPrimaryTextTrackSelectionOverride()
//       MpvPlayer.getSecondaryTextTrackSelectionOverride()
//       MpvPlayer.getSecondaryTextTrackSelectionOverrides()
//       MpvPlayer.isSecondaryTextTrackSuppressed()
//       MpvPlayer.setSecondaryTextTrackSelectionOverride(TrackSelectionOverride)
//       MpvPlayer.resetSecondaryTextTrackSelection()
//       MpvPlayer.setSecondaryTextTrackAutoSelectionEnabled(boolean)
//
// 这里不写替身实现，而是让 MPV 引擎退回 PlayerEngine 的默认实现
// （getSecondarySubtitleState() -> SecondarySubtitleState.EMPTY，
//   setSecondarySubtitleSelection() -> 空操作）。接口本来就为"不支持的引擎"留了这个口子，
//   所以这是框架内的一等状态，不是临时补丁。
//
// 影响边界（说清楚）：
//   - 只影响 MPV 引擎。Exo 引擎的副字幕由 app 源码自己实现
//     （ExoSubtitleController -> androidx.media3.exoplayer.libass.LibassSubtitleController），
//     完全不受影响。
//   - 用户仍可在自己的 mpv.conf 里写 `secondary-sid` / `secondary-sub-pos`：
//     MpvUtil.getManagedOptionNames() 把这两个选项名保留在 App 管理清单里，
//     MpvConfigFile 会把它们从用户配置里透传给 mpv。
public class MpvPlayerEngine implements PlayerEngine, Player.Listener {

    private static final String TAG = "MpvPlayerEngine";

    /**
     * HLS 剥壳代理的**运行时总开关**（一个空文件就够）。
     *
     * <p>在 mpv 配置目录（`/sdcard/TV/mpv/`，与 `mpv.conf` 同级）下建一个名为
     * {@code hls-proxy.disabled} 的空文件 ⇒ 本引擎**完全不挂代理**，
     * 行为与改动前逐字节一致。删掉该文件即恢复。</p>
     *
     * <p>为什么要有这个开关：代理会插进**所有 HLS 源**的取流路径。虽然它对正常分片是
     * 原样透传（只在「188 对齐的 TS 同步字节」探测命中时才改写），但终究是新引入的一跳。
     * 有这个文件，凯哥在电视上不用重新打包就能一键对比「挂 / 不挂」。</p>
     */
    private static final String HLS_PROXY_DISABLE_FILE = "hls-proxy.disabled";

    private final MpvErrorMessageProvider provider;
    private final MpvPlayerEffect effect;
    private final MpvPlayer player;
    private PlaySpec spec;

    /** 当前 HLS 代理会话 id；null = 没挂代理。 */
    private String proxySessionId;

    public MpvPlayerEngine(int decode, Player.Listener listener) {
        this.player = MpvUtil.buildPlayer(decode, listener);
        this.provider = new MpvErrorMessageProvider();
        this.effect = new MpvPlayerEffect(player);
        this.player.setAudioOutputListener(effect::applyAudioEffect);
        this.player.addListener(this);
    }

    public static boolean isAvailable() {
        return MpvUtil.isAvailable();
    }

    @Override
    public Type getType() {
        return Type.MPV;
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
        return effect;
    }

    @Override
    public void release() {
        detachHlsProxy();
        player.removeListener(this);
        player.setAudioOutputListener(null);
        player.release();
    }

    @Override
    public void applySubtitleStyle() {
        MpvUtil.applySubtitleStyle(player);
    }

    @Override
    public boolean addSubtitle(Sub sub) {
        if (sub == null || sub.isEmpty() || player.getCurrentMediaItem() == null) return false;
        if (player.getPlaybackState() == Player.STATE_IDLE || player.getPlaybackState() == Player.STATE_ENDED) return false;
        return player.addSubtitle(MediaItemFactory.buildSubConfig(sub));
    }

    @Override
    public void setDecode(int decode) {
        player.setDecode(decode);
    }

    @Override
    public void onTracksChanged(@NonNull Tracks tracks) {
        effect.applyVideoEffect();
    }

    @Override
    public void start(PlaySpec spec, long startPositionMs) {
        this.spec = spec;
        startInternal(startPositionMs);
    }

    private void startInternal(long startPositionMs) {
        effect.applyVideoEffect();
        effect.clearAudioEffect();
        // ⭐ HLS 剥壳代理挂载点。
        // spec.setUrl() 只在「构建 MediaItem 的那一瞬间」需要生效，随后立刻还原 ——
        // 这样 PlaySpec 对外（PlayerManager / UI）始终是**原始地址**，
        // 不会把 127.0.0.1 的代理地址泄漏出去。
        String originalUrl = spec == null ? null : spec.getUrl();
        attachHlsProxy();
        MediaItem item = MediaItemFactory.from(spec);
        if (originalUrl != null) spec.setUrl(originalUrl);
        player.setMediaItem(item, startPositionMs);
        prepareAndPlay();
    }

    // ---------------------------------------------------------------- HLS 剥壳代理

    /**
     * 把 HLS 源改道到本机代理（{@link MpvHlsProxy}），让它有机会剥掉分片上的假图片头。
     *
     * <p>任何一步失败都**静默退回直连** —— 代理是"锦上添花"，绝不允许它把本来能播的源搞坏。</p>
     */
    private void attachHlsProxy() {
        if (spec == null) return;
        String url = spec.getUrl();
        if (TextUtils.isEmpty(url)) return;
        if (MpvHlsProxy.isProxyUrl(url)) return;
        if (!isHlsProxyEnabled()) return;
        if (!isHls(url, spec.getFormat())) return;
        detachHlsProxy();
        String proxyUrl = MpvHlsProxy.proxy(url, spec.getHeaders());
        if (proxyUrl == null) return;
        proxySessionId = MpvHlsProxy.sessionIdOf(proxyUrl);
        spec.setUrl(proxyUrl);
        Log.i(TAG, "hls proxy attached " + url + " -> " + proxyUrl);
    }

    private void detachHlsProxy() {
        if (proxySessionId != null) MpvHlsProxy.release(proxySessionId);
        proxySessionId = null;
    }

    /** 只在明确是 HLS 时才改道；拿不准就不动（宁可少修，不可误伤）。 */
    private static boolean isHls(String url, String format) {
        if (MimeTypes.APPLICATION_M3U8.equals(format)) return true;
        String lower = url.toLowerCase(Locale.US);
        int query = lower.indexOf('?');
        if (query >= 0) lower = lower.substring(0, query);
        return lower.endsWith(".m3u8") || lower.endsWith(".m3u");
    }

    private static boolean isHlsProxyEnabled() {
        try {
            return !Path.mpv(HLS_PROXY_DISABLE_FILE).exists();
        } catch (Throwable e) {
            return true;
        }
    }

    private void prepareAndPlay() {
        player.prepare();
        player.play();
    }

    @Override
    public void stop() {
        player.stop();
    }

    @Override
    public String getErrorMessage(PlaybackException e) {
        return provider.get(e);
    }

    @Override
    public ErrorAction handleError(PlaybackException e) {
        return switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED -> ErrorAction.DECODE;
            case PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> retryHls();
            default -> ErrorAction.FATAL;
        };
    }

    private ErrorAction retryHls() {
        if (spec == null || MimeTypes.APPLICATION_M3U8.equals(spec.getFormat())) return ErrorAction.FATAL;
        spec.setFormat(MimeTypes.APPLICATION_M3U8);
        startInternal(player.getCurrentPosition());
        return ErrorAction.RECOVERED;
    }
}
