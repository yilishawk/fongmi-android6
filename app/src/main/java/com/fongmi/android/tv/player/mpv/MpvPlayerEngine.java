package com.fongmi.android.tv.player.mpv;

import androidx.annotation.NonNull;
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

    private final MpvErrorMessageProvider provider;
    private final MpvPlayerEffect effect;
    private final MpvPlayer player;
    private PlaySpec spec;

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
        player.setMediaItem(MediaItemFactory.from(spec), startPositionMs);
        prepareAndPlay();
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
