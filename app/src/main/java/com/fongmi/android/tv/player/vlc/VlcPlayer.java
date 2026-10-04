package com.fongmi.android.tv.player.vlc;

import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.Util;

import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.interfaces.IVLCVout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * libVLC 的 media3 {@link Player} 适配层（甲方案）。
 *
 * <h2>为什么是这个形状</h2>
 *
 * media3 的 {@link SimpleBasePlayer} 把 {@code Player} 接口的 ~200 个方法收窄成
 * 「一个 {@link #getState()} + 若干 {@code handle*} 钩子」。本类实现这两部分。
 *
 * <p>⚠ <b>关键机制（决定了本类能不能出画）</b>：{@code handle*} 的默认实现是
 * {@code throw new IllegalStateException("Missing implementation to handle COMMAND_xxx")}，
 * 而它<b>只在对应 {@code COMMAND_*} 出现在 {@link #getState()} 的
 * {@code availableCommands} 里时才会被调用</b>。所以「声明哪些命令」和「实现哪些钩子」
 * 必须严格成对 —— 声明了不实现 ⇒ 调用时崩；不声明 ⇒ 该能力整体不可用。</p>
 *
 * <p>⭐ 其中 {@link Player#COMMAND_SET_VIDEO_SURFACE}(27) 是<b>出画的前提</b>：
 * 项目自己的 {@code PlayerView.setRender()}（FongMi 给 media3-ui fork 加的方法）
 * 用 {@code if (player != null && player.isCommandAvailable(27))} 守着整段 surface 下发逻辑 ——
 * 命令不可用 ⇒ <b>View 根本不会交给播放器</b> ⇒ 黑屏 / 只有声音。</p>
 *
 * <h2>线程模型</h2>
 *
 * {@link SimpleBasePlayer} 要求所有调用发生在构造时传入的 {@code applicationLooper} 线程；
 * 而 VLC 的 {@code MediaPlayer.EventListener} 回调默认走主线程 Handler
 * （{@code VLCObject.setEventListener(listener)} 且 handler 为 null 时取
 * {@code Looper.getMainLooper()}）。本类<b>固定用主线程</b>，两条线因此天然对齐 ——
 * 事件回调里可以直接 {@link #invalidateState()}。</p>
 *
 * <h2>与 {@code MpvPlayer} 的关系</h2>
 *
 * 结构上刻意对齐 {@code androidx.media3.mpvplayer.MpvPlayer}（同样的 27 个命令集，
 * 见 {@code MpvAvailableCommands}）。差别只在驱动侧：mpv 走自己的 JNI 桥，
 * 这里走 {@code org.videolan.libvlc} 的 Java API。
 */
public final class VlcPlayer extends SimpleBasePlayer {

    private static final String TAG = "VlcPlayer";

    /**
     * 可用的 {@link Player.Command} 集合。
     *
     * <p>刻意与 mpv 侧对齐（{@code MpvAvailableCommands} 的 22 个 PERMANENT + seek 系列），
     * 这样上层 UI（{@code PlaybackAction} / {@code PlayerManager} / {@code VideoSettingPanel}）
     * 在 VLC 引擎下走的分支与 MPV 一致，不会出现「MPV 有的按钮 VLC 没有」这种体验割裂。</p>
     *
     * <p>两点说明：</p>
     * <ul>
     *   <li>⭐ 含 {@link Player#COMMAND_CHANGE_MEDIA_ITEMS}：{@code PlayerManager.setMetadata()}
     *       会调 {@code player.replaceMediaItem(...)}，不声明就会抛 {@code UnsupportedOperationException}。</li>
     *   <li>不含 {@code COMMAND_SEEK_TO_PREVIOUS}/{@code _TO_NEXT} 等「跨条目跳转」：
     *       本引擎是单条播放模型，集数切换由 {@code PlayerManager} 走 {@code setMediaItem} 完成
     *       （{@code PlaybackService} 也自己 override 了那几个方法，不落到这里）。</li>
     * </ul>
     */
    private static final Player.Commands AVAILABLE_COMMANDS = new Player.Commands.Builder()
            .addAll(
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_PREPARE,
                    Player.COMMAND_STOP,
                    Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                    Player.COMMAND_SEEK_BACK,
                    Player.COMMAND_SEEK_FORWARD,
                    Player.COMMAND_SET_SPEED_AND_PITCH,
                    Player.COMMAND_SET_REPEAT_MODE,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_TIMELINE,
                    Player.COMMAND_GET_METADATA,
                    Player.COMMAND_SET_MEDIA_ITEM,
                    Player.COMMAND_CHANGE_MEDIA_ITEMS,
                    Player.COMMAND_GET_AUDIO_ATTRIBUTES,
                    Player.COMMAND_GET_VOLUME,
                    Player.COMMAND_SET_VOLUME,
                    Player.COMMAND_SET_AUDIO_ATTRIBUTES,
                    Player.COMMAND_SET_VIDEO_SURFACE,
                    Player.COMMAND_GET_TEXT_OFFSET,
                    Player.COMMAND_SET_TEXT_OFFSET,
                    Player.COMMAND_GET_AUDIO_OFFSET,
                    Player.COMMAND_SET_AUDIO_OFFSET,
                    Player.COMMAND_GET_TRACKS,
                    Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS,
                    Player.COMMAND_RELEASE)
            .build();

    private final LibVLC libVlc;
    private final org.videolan.libvlc.MediaPlayer vlc;
    private final IVLCVout vout;

    /** 当前 playlist（本引擎实际只用第 currentIndex 条，其余为占位，供 CHANGE_MEDIA_ITEMS 语义完整）。 */
    private final List<MediaItem> playlist = new ArrayList<>();

    // ---- 镜像给 getState() 的状态（唯一真源是 VLC，这里是它的投影） ----
    private int currentIndex = C.INDEX_UNSET;
    private int playbackState = Player.STATE_IDLE;
    private boolean playWhenReady;
    @Nullable
    private PlaybackException playerError;
    private PlaybackParameters playbackParameters = PlaybackParameters.DEFAULT;
    private int repeatMode = Player.REPEAT_MODE_OFF;
    private float volume = 1f;
    private AudioAttributes audioAttributes = AudioAttributes.DEFAULT;
    private TrackSelectionParameters trackSelectionParameters = TrackSelectionParameters.DEFAULT;
    private long audioOffsetMs;
    private long textOffsetMs;
    private VideoSize videoSize = VideoSize.UNKNOWN;
    private Tracks tracks = Tracks.EMPTY;
    private boolean seekable;
    private long durationMs = C.TIME_UNSET;
    private long positionMs;
    private long bufferedPositionMs = C.TIME_UNSET;

    /** 当前挂着的视频输出对象（SurfaceView / TextureView / SurfaceHolder / Surface）。 */
    @Nullable
    private Object videoOutput;

    /** 起播位置：VLC 的 setTime 必须等媒体真的开始才生效，所以先存着，Playing 事件里再补。 */
    private long pendingStartPositionMs = C.TIME_UNSET;

    private boolean hardwareDecode = true;
    private boolean released;

    VlcPlayer(Looper looper, LibVLC libVlc, int decode) {
        super(looper);
        this.libVlc = libVlc;
        this.hardwareDecode = decode != PlayerEngine.SOFT;
        this.vlc = new org.videolan.libvlc.MediaPlayer(libVlc);
        this.vout = vlc.getVLCVout();
        this.vlc.setEventListener(this::onVlcEvent);
    }

    // ================================================================ getState

    @Override
    protected State getState() {
        int state = resolvePlaybackState();
        int index = resolveIndex();
        State.Builder builder = new State.Builder()
                .setAvailableCommands(AVAILABLE_COMMANDS)
                .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setPlaybackState(state)
                .setPlaybackParameters(playbackParameters)
                .setRepeatMode(repeatMode)
                .setVolume(volume)
                .setAudioAttributes(audioAttributes)
                .setTrackSelectionParameters(trackSelectionParameters)
                .setAudioOffsetMs(audioOffsetMs)
                .setTextOffsetMs(textOffsetMs)
                .setVideoSize(videoSize)
                .setPlaylist(buildPlaylistData())
                .setCurrentMediaItemIndex(index);

        if (playlist.isEmpty()) {
            builder.setContentPositionMs(C.TIME_UNSET)
                    .setContentBufferedPositionMs(PositionSupplier.getConstant(C.TIME_UNSET));
        } else {
            builder.setContentPositionMs(positionMs)
                    .setContentBufferedPositionMs(PositionSupplier.getConstant(bufferedPositionMs));
        }

        // ⚠ 两道防御，对应的都是 SimpleBasePlayer.State 构造器里会 **抛异常** 的硬校验
        // （见 SimpleBasePlayer.java 的 `private State(Builder)`）：
        //   ① "Player error only allowed in STATE_IDLE"
        //   ② "currentMediaItemIndex must be less than playlist.size()"
        // 内部状态在「事件回调」与「命令处理」之间可能短暂不自洽，而 getState() 抛异常
        // 等于整个播放器崩掉 —— 所以这里做最后一道收敛，绝不把不自洽的状态交出去。
        if (playerError != null && state == Player.STATE_IDLE) builder.setPlayerError(playerError);
        return builder.build();
    }

    private int resolvePlaybackState() {
        // 空 playlist 只能是 IDLE / ENDED（State 构造器的硬约束）。
        return playlist.isEmpty() ? Player.STATE_IDLE : playbackState;
    }

    private int resolveIndex() {
        if (playlist.isEmpty()) return C.INDEX_UNSET;
        if (currentIndex == C.INDEX_UNSET) return 0;
        return Math.min(currentIndex, playlist.size() - 1);
    }

    private List<MediaItemData> buildPlaylistData() {
        if (playlist.isEmpty()) return ImmutableList.of();
        ImmutableList.Builder<MediaItemData> out = ImmutableList.builder();
        for (int i = 0; i < playlist.size(); i++) {
            MediaItem item = playlist.get(i);
            MediaItemData.Builder builder = new MediaItemData.Builder(uidOf(item, i)).setMediaItem(item);
            if (i == currentIndex) {
                builder.setTracks(tracks)
                        .setDurationUs(durationMs == C.TIME_UNSET ? C.TIME_UNSET : Util.msToUs(durationMs))
                        .setIsSeekable(seekable)
                        .setIsDynamic(!seekable);
            } else {
                builder.setDurationUs(C.TIME_UNSET);
            }
            out.add(builder.build());
        }
        return out.build();
    }

    /** uid 必须唯一（{@code setPlaylist} 会 checkArgument）。带上索引即可。 */
    private static Object uidOf(MediaItem item, int index) {
        return (TextUtils.isEmpty(item.mediaId) ? "item" : item.mediaId) + "#" + index;
    }

    // ================================================================ 播放控制

    @Override
    protected ListenableFuture<?> handleSetPlayWhenReady(boolean playWhenReady) {
        this.playWhenReady = playWhenReady;
        try {
            if (playWhenReady) vlc.play();
            else vlc.pause();
        } catch (Throwable e) {
            Log.e(TAG, "setPlayWhenReady failed", e);
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handlePrepare() {
        // VLC 的「准备」与「播放」是同一个动作（play() 内部完成 open + prepare + play）。
        // 若此刻还没挂 media，什么都不做；挂上之后 play() 即可。
        try {
            if (!playlist.isEmpty()) {
                playerError = null;
                playbackState = Player.STATE_BUFFERING;
                vlc.play();
            }
        } catch (Throwable e) {
            Log.e(TAG, "prepare failed", e);
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleStop() {
        try {
            vlc.stop();
        } catch (Throwable e) {
            Log.e(TAG, "stop failed", e);
        }
        playbackState = Player.STATE_IDLE;
        playWhenReady = false;
        positionMs = 0;
        invalidateState();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleRelease() {
        releaseInternal();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSeek(int mediaItemIndex, long positionMs, int seekCommand) {
        if (positionMs == C.TIME_UNSET) return Futures.immediateVoidFuture();
        try {
            // media3 的 BasePlayer 已经把「上一集 / 快退 N 秒」等语义解析成了目标位置，
            // 这里只需要落到 VLC 的时间轴上。不可 seek 的源（直播）直接忽略。
            if (vlc.isSeekable()) {
                vlc.setTime(positionMs);
                this.positionMs = positionMs;
                invalidateState();
            }
        } catch (Throwable e) {
            Log.e(TAG, "seek failed", e);
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetPlaybackParameters(PlaybackParameters playbackParameters) {
        this.playbackParameters = playbackParameters;
        try {
            vlc.setRate(playbackParameters.speed);
        } catch (Throwable e) {
            Log.e(TAG, "setRate failed", e);
        }
        invalidateState();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetRepeatMode(int repeatMode) {
        // VLC 没有内建 repeat；本引擎是单条模型，ENDED 之后的重播由上层决定。
        // 只记录状态，保证 getState() 与上层读回的值一致。
        this.repeatMode = repeatMode;
        invalidateState();
        return Futures.immediateVoidFuture();
    }

    /**
     * ⚠ 必须实现**带 {@code volumeOperationType} 的**这个重载。
     *
     * <p>{@code handleSetVolume(float)} 在本版 media3 里已 {@code @Deprecated}，
     * 且带类型参数的版本默认就是转发到它 —— 也就是说 {@code Player.setVolume(float)}
     * 最终会落到<b>带类型参数</b>的这一个上。只实现旧的窄版本会拿到弃用警告，
     * 而且不是 media3 期望的扩展点。</p>
     */
    @Override
    protected ListenableFuture<?> handleSetVolume(float volume, @C.VolumeOperationType int volumeOperationType) {
        this.volume = volume;
        try {
            // VLC 的音量是 0..100 的整数，media3 是 0..1 的 float。
            vlc.setVolume(Math.round(volume * 100f));
        } catch (Throwable e) {
            Log.e(TAG, "setVolume failed", e);
        }
        invalidateState();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetAudioAttributes(AudioAttributes audioAttributes, boolean handleAudioFocus) {
        this.audioAttributes = audioAttributes;
        invalidateState();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetAudioOffsetMs(long audioOffsetMs) {
        this.audioOffsetMs = audioOffsetMs;
        try {
            vlc.setAudioDelay(Util.msToUs(audioOffsetMs));
        } catch (Throwable e) {
            Log.e(TAG, "setAudioDelay failed", e);
        }
        invalidateState();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetTextOffsetMs(long textOffsetMs) {
        this.textOffsetMs = textOffsetMs;
        try {
            vlc.setSpuDelay(Util.msToUs(textOffsetMs));
        } catch (Throwable e) {
            Log.e(TAG, "setSpuDelay failed", e);
        }
        invalidateState();
        return Futures.immediateVoidFuture();
    }

    // ================================================================ 媒体与播放列表

    @Override
    protected ListenableFuture<?> handleSetMediaItems(List<MediaItem> mediaItems, int startIndex, long startPositionMs) {
        playlist.clear();
        playlist.addAll(mediaItems);
        currentIndex = startIndex == C.INDEX_UNSET || startIndex < 0 || startIndex >= playlist.size() ? (playlist.isEmpty() ? C.INDEX_UNSET : 0) : startIndex;
        loadCurrent(startPositionMs);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleAddMediaItems(int index, List<MediaItem> mediaItems) {
        int at = Math.max(0, Math.min(index, playlist.size()));
        playlist.addAll(at, mediaItems);
        if (currentIndex != C.INDEX_UNSET && at <= currentIndex) currentIndex += mediaItems.size();
        invalidateState();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleRemoveMediaItems(int fromIndex, int toIndex) {
        int from = Math.max(0, Math.min(fromIndex, playlist.size()));
        int to = Math.max(from, Math.min(toIndex, playlist.size()));
        if (from == to) return Futures.immediateVoidFuture();
        boolean removedCurrent = currentIndex != C.INDEX_UNSET && currentIndex >= from && currentIndex < to;
        playlist.subList(from, to).clear();
        if (playlist.isEmpty()) {
            currentIndex = C.INDEX_UNSET;
            resetPlaybackState();
        } else if (removedCurrent) {
            currentIndex = Math.min(from, playlist.size() - 1);
            loadCurrent(C.TIME_UNSET);
        } else if (currentIndex != C.INDEX_UNSET && currentIndex >= to) {
            currentIndex -= (to - from);
        }
        invalidateState();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleMoveMediaItems(int fromIndex, int toIndex, int newIndex) {
        int from = Math.max(0, Math.min(fromIndex, playlist.size()));
        int to = Math.max(from, Math.min(toIndex, playlist.size()));
        if (from == to) return Futures.immediateVoidFuture();
        List<MediaItem> moved = new ArrayList<>(playlist.subList(from, to));
        MediaItem current = currentIndex == C.INDEX_UNSET ? null : playlist.get(currentIndex);
        playlist.subList(from, to).clear();
        int target = Math.max(0, Math.min(newIndex, playlist.size()));
        playlist.addAll(target, moved);
        if (current != null) currentIndex = playlist.indexOf(current);
        invalidateState();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleReplaceMediaItems(int fromIndex, int toIndex, List<MediaItem> mediaItems) {
        int from = Math.max(0, Math.min(fromIndex, playlist.size()));
        int to = Math.max(from, Math.min(toIndex, playlist.size()));
        playlist.subList(from, to).clear();
        playlist.addAll(from, mediaItems);
        if (currentIndex != C.INDEX_UNSET && currentIndex >= from && currentIndex < from + mediaItems.size()) {
            // ⭐ PlayerManager.setMetadata() 走的就是这条路（只换 metadata，不换地址）。
            // 重新 setMedia 会让 VLC 从头再解一次 —— 对「只改标题」来说代价太大，
            // 所以这里只更新列表里的 MediaItem，不动播放器。
            invalidateState();
        } else {
            invalidateState();
        }
        return Futures.immediateVoidFuture();
    }

    /** 把当前条交给 VLC。 */
    private void loadCurrent(long startPositionMs) {
        releaseMedia();
        resetPlaybackState();
        pendingStartPositionMs = startPositionMs;
        if (currentIndex == C.INDEX_UNSET || currentIndex >= playlist.size()) return;
        MediaItem item = playlist.get(currentIndex);
        Uri uri = item.localConfiguration != null ? item.localConfiguration.uri : null;
        if (uri == null) {
            Log.w(TAG, "media item has no uri");
            return;
        }
        try {
            Media media = new Media(libVlc, uri);
            applyHeaders(media, item);
            media.setHWDecoderEnabled(hardwareDecode, false);
            media.setDefaultMediaPlayerOptions();
            vlc.setMedia(media);
            // setMedia 内部 retain 了一次，这里放掉自己那份引用（否则 native 侧泄漏）。
            media.release();
            playbackState = Player.STATE_BUFFERING;
            invalidateState();
        } catch (Throwable e) {
            Log.e(TAG, "load media failed", e);
            playerError = error("VLC 无法打开媒体：" + uri, e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
            playbackState = Player.STATE_IDLE;
            invalidateState();
        }
    }

    /**
     * 把 media3 的请求头转成 VLC 的 per-media 选项。
     *
     * <p>VLC 只认 {@code :http-referrer} / {@code :http-user-agent} / {@code :http-header} 三个入口，
     * 没有「任意 header 字典」的概念，所以按名字分流。</p>
     */
    private static void applyHeaders(Media media, MediaItem item) {
        Bundle extras = item.requestMetadata.extras;
        if (extras == null) return;
        for (String key : extras.keySet()) {
            String value = extras.getString(key);
            if (TextUtils.isEmpty(value)) continue;
            if ("User-Agent".equalsIgnoreCase(key)) media.addOption(":http-user-agent=" + value);
            else if ("Referer".equalsIgnoreCase(key)) media.addOption(":http-referrer=" + value);
            else media.addOption(":http-header=" + key + ": " + value);
        }
    }

    // ================================================================ Surface 握手

    /**
     * ⭐ 甲方案最容易踩坑的一处：media3 交给这里的是四选一
     * （{@code SurfaceView} / {@code TextureView} / {@code SurfaceHolder} / {@code Surface}），
     * 而 {@link IVLCVout} 恰好为每一类都提供了对应入口 —— 一一映射，无歧义。
     *
     * <p>（{@code MpvSurfaceController} 对四类都写了分支，本类同样四类齐全；
     * 「手机 texture 只有声音」那件事已被证明不在适配层，见
     * {@code .gradle-user/PlayerView-setRender-与TextureView路径-取证.md}。）</p>
     */
    @Override
    protected ListenableFuture<?> handleSetVideoOutput(Object videoOutput) {
        verifyApplicationThread();
        this.videoOutput = videoOutput;
        try {
            if (videoOutput instanceof SurfaceView view) {
                vout.setVideoView(view);
            } else if (videoOutput instanceof TextureView view) {
                vout.setVideoView(view);
            } else if (videoOutput instanceof SurfaceHolder holder) {
                vout.setVideoSurface(holder.getSurface(), holder);
            } else if (videoOutput instanceof Surface surface) {
                vout.setVideoSurface(surface, null);
            } else if (videoOutput instanceof SurfaceTexture texture) {
                vout.setVideoSurface(texture);
            } else {
                Log.w(TAG, "unsupported video output: " + (videoOutput == null ? "null" : videoOutput.getClass().getName()));
                return Futures.immediateVoidFuture();
            }
            if (!vout.areViewsAttached()) vout.attachViews();
        } catch (Throwable e) {
            Log.e(TAG, "attach video output failed", e);
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleClearVideoOutput(@Nullable Object videoOutput) {
        verifyApplicationThread();
        if (videoOutput != null && videoOutput != this.videoOutput) return Futures.immediateVoidFuture();
        this.videoOutput = null;
        try {
            if (vout.areViewsAttached()) vout.detachViews();
        } catch (Throwable e) {
            Log.e(TAG, "detach video output failed", e);
        }
        return Futures.immediateVoidFuture();
    }

    // ================================================================ 轨道

    @Override
    protected ListenableFuture<?> handleSetTrackSelectionParameters(TrackSelectionParameters parameters) {
        this.trackSelectionParameters = parameters;
        applyTrackSelection(parameters);
        invalidateState();
        return Futures.immediateVoidFuture();
    }

    /**
     * 把 media3 的「显式选择」（{@link TrackSelectionParameters#overrides}）映射到 VLC 的轨道 id。
     *
     * <p>只处理显式 override —— 语言偏好（{@code preferredAudioLanguages}）交给 VLC 自己的
     * 自动选择，不在这里猜。找不到对应轨道时保持原状，绝不乱切。</p>
     */
    private void applyTrackSelection(TrackSelectionParameters parameters) {
        try {
            // ⭐ 直接遍历 overrides 的 entrySet：key 就是 TrackGroup，
            // 不需要再去 TrackSelectionOverride 上取（media3 这个版本里
            // TrackSelectionOverride 暴露的是 mediaTrackGroup 字段，没有 getTrackGroup()）。
            for (Map.Entry<TrackGroup, TrackSelectionOverride> entry : parameters.overrides.entrySet()) {
                TrackGroup group = entry.getKey();
                if (group.length == 0) continue;
                int trackId = parseInt(group.getFormat(0).id);
                if (trackId == Integer.MIN_VALUE) continue;
                if (isAudioTrack(trackId)) vlc.setAudioTrack(trackId);
                else if (isTextTrack(trackId)) vlc.setSpuTrack(trackId);
            }
        } catch (Throwable e) {
            Log.e(TAG, "applyTrackSelection failed", e);
        }
    }

    private boolean isAudioTrack(int id) {
        return containsId(vlc.getAudioTracks(), id);
    }

    private boolean isTextTrack(int id) {
        return containsId(vlc.getSpuTracks(), id);
    }

    private static boolean containsId(org.videolan.libvlc.MediaPlayer.TrackDescription[] descriptions, int id) {
        if (descriptions == null) return false;
        for (org.videolan.libvlc.MediaPlayer.TrackDescription description : descriptions) {
            if (description.id == id) return true;
        }
        return false;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Throwable e) {
            return Integer.MIN_VALUE;
        }
    }

    /** 音轨声道数（供 {@code PlayerEngine.getAudioChannelCount()}）。 */
    public int getAudioChannelCount() {
        try {
            IMedia media = vlc.getMedia();
            if (media == null) return 0;
            int current = vlc.getAudioTrack();
            for (int i = 0; i < media.getTrackCount(); i++) {
                IMedia.Track track = media.getTrack(i);
                if (track instanceof IMedia.AudioTrack audio && audio.id == current) return audio.channels;
            }
        } catch (Throwable e) {
            Log.e(TAG, "getAudioChannelCount failed", e);
        }
        return 0;
    }

    /**
     * 外挂字幕 —— 首版**故意不接**。
     *
     * <p>原因：{@code MediaPlayer.addSlave(int type, Uri, boolean)} 的 {@code type} 取值
     * 在 {@code org.videolan.libvlc} 里没有可引用的公开常量（{@code IMedia.Slave} 只有
     * {@code type}/{@code priority}/{@code uri} 三个字段，没有 Type 常量，已反汇编确认）。
     * 猜一个魔数写进去，属于"没有证据的改动" —— 而它一旦猜错，破坏的是<b>本来能播的源</b>。</p>
     *
     * <p>⇒ 首版交给 {@code PlayerEngine} 的默认实现（返回 false），
     * {@code PlayerManager.setSub()} 会因此走 {@code startCurrent()} 重建播放，
     * 外挂字幕依然可用，只是要走一次重载。</p>
     */
    public boolean addSubtitle(Uri uri) {
        return false;
    }

    public void setDecode(int decode) {
        this.hardwareDecode = decode != PlayerEngine.SOFT;
    }

    // ================================================================ VLC 事件

    private void onVlcEvent(org.videolan.libvlc.MediaPlayer.Event event) {
        if (released) return;
        switch (event.type) {
            case org.videolan.libvlc.MediaPlayer.Event.Opening -> playbackState = Player.STATE_BUFFERING;
            case org.videolan.libvlc.MediaPlayer.Event.Buffering -> {
                if (event.getBuffering() >= 100f && playbackState == Player.STATE_BUFFERING) playbackState = Player.STATE_READY;
            }
            case org.videolan.libvlc.MediaPlayer.Event.Playing -> {
                playbackState = Player.STATE_READY;
                seekable = vlc.isSeekable();
                applyPendingStartPosition();
            }
            case org.videolan.libvlc.MediaPlayer.Event.Paused -> playbackState = Player.STATE_READY;
            case org.videolan.libvlc.MediaPlayer.Event.Stopped -> {
                playbackState = Player.STATE_IDLE;
                playWhenReady = false;
            }
            case org.videolan.libvlc.MediaPlayer.Event.EndReached -> {
                playbackState = Player.STATE_ENDED;
                positionMs = durationMs == C.TIME_UNSET ? positionMs : durationMs;
            }
            case org.videolan.libvlc.MediaPlayer.Event.EncounteredError -> {
                playbackState = Player.STATE_IDLE;
                playWhenReady = false;
                playerError = error("VLC 播放失败（EncounteredError）", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
            }
            case org.videolan.libvlc.MediaPlayer.Event.TimeChanged -> {
                positionMs = event.getTimeChanged();
                bufferedPositionMs = positionMs;
            }
            case org.videolan.libvlc.MediaPlayer.Event.LengthChanged -> {
                durationMs = event.getLengthChanged();
                seekable = vlc.isSeekable();
            }
            case org.videolan.libvlc.MediaPlayer.Event.SeekableChanged -> seekable = event.getSeekable();
            case org.videolan.libvlc.MediaPlayer.Event.Vout -> {
                if (event.getVoutCount() > 0) refreshVideoSize();
            }
            case org.videolan.libvlc.MediaPlayer.Event.ESAdded,
                 org.videolan.libvlc.MediaPlayer.Event.ESDeleted,
                 org.videolan.libvlc.MediaPlayer.Event.ESSelected -> refreshTracks();
            default -> {
                return;
            }
        }
        // 播放一旦重新推进（非 IDLE），上一次的错误就该消失 ——
        // 既符合语义，也满足 State 构造器「playerError 只能出现在 STATE_IDLE」的硬校验。
        if (playbackState != Player.STATE_IDLE) playerError = null;
        invalidateState();
    }

    private void applyPendingStartPosition() {
        if (pendingStartPositionMs == C.TIME_UNSET || pendingStartPositionMs <= 0) {
            pendingStartPositionMs = C.TIME_UNSET;
            return;
        }
        long target = pendingStartPositionMs;
        pendingStartPositionMs = C.TIME_UNSET;
        try {
            vlc.setTime(target);
        } catch (Throwable e) {
            Log.e(TAG, "apply start position failed", e);
        }
    }

    private void refreshVideoSize() {
        try {
            IMedia media = vlc.getMedia();
            if (media == null) return;
            int current = vlc.getVideoTrack();
            for (int i = 0; i < media.getTrackCount(); i++) {
                IMedia.Track track = media.getTrack(i);
                if (track instanceof IMedia.VideoTrack video && video.id == current && video.width > 0 && video.height > 0) {
                    videoSize = new VideoSize(video.width, video.height);
                    return;
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "refreshVideoSize failed", e);
        }
    }

    /**
     * 把 VLC 的三类轨道读成 media3 的 {@link Tracks}。
     *
     * <p>刻意用 {@code getVideoTracks()} / {@code getAudioTracks()} / {@code getSpuTracks()}
     * 三个 API 分类，而不是去读 {@code IMedia.Track.type} —— 前者天然按类返回，
     * 不需要依赖 type 常量的取值。</p>
     */
    private void refreshTracks() {
        try {
            ImmutableList.Builder<Tracks.Group> groups = ImmutableList.builder();
            addGroup(groups, vlc.getVideoTracks(), vlc.getVideoTrack(), C.TRACK_TYPE_VIDEO, "video");
            addGroup(groups, vlc.getAudioTracks(), vlc.getAudioTrack(), C.TRACK_TYPE_AUDIO, "audio");
            addGroup(groups, vlc.getSpuTracks(), vlc.getSpuTrack(), C.TRACK_TYPE_TEXT, "text");
            ImmutableList<Tracks.Group> list = groups.build();
            Tracks next = list.isEmpty() ? Tracks.EMPTY : new Tracks(list);
            if (!next.equals(tracks)) tracks = next;
        } catch (Throwable e) {
            Log.e(TAG, "refreshTracks failed", e);
        }
    }

    private void addGroup(ImmutableList.Builder<Tracks.Group> out, org.videolan.libvlc.MediaPlayer.TrackDescription[] descriptions, int selectedId, @C.TrackType int trackType, String prefix) {
        if (descriptions == null || descriptions.length == 0) return;
        Format[] formats = new Format[descriptions.length];
        boolean[] selected = new boolean[descriptions.length];
        int[] support = new int[descriptions.length];
        for (int i = 0; i < descriptions.length; i++) {
            org.videolan.libvlc.MediaPlayer.TrackDescription description = descriptions[i];
            formats[i] = new Format.Builder()
                    .setId(String.valueOf(description.id))
                    .setLabel(description.name)
                    .setSampleMimeType(mimeOf(trackType))
                    .build();
            selected[i] = description.id == selectedId;
            support[i] = C.FORMAT_HANDLED;
        }
        out.add(new Tracks.Group(new TrackGroup(prefix + "-" + selectedId, formats), false, support, selected));
    }

    private static String mimeOf(@C.TrackType int trackType) {
        return switch (trackType) {
            case C.TRACK_TYPE_AUDIO -> MimeTypes.AUDIO_UNKNOWN;
            case C.TRACK_TYPE_TEXT -> MimeTypes.TEXT_UNKNOWN;
            default -> MimeTypes.VIDEO_UNKNOWN;
        };
    }

    // ================================================================ 生命周期

    private void resetPlaybackState() {
        playbackState = Player.STATE_IDLE;
        playerError = null;
        durationMs = C.TIME_UNSET;
        positionMs = 0;
        bufferedPositionMs = C.TIME_UNSET;
        seekable = false;
        tracks = Tracks.EMPTY;
        videoSize = VideoSize.UNKNOWN;
    }

    private void releaseMedia() {
        try {
            if (vlc.hasMedia()) vlc.setMedia(null);
        } catch (Throwable e) {
            Log.e(TAG, "releaseMedia failed", e);
        }
    }

    private void releaseInternal() {
        if (released) return;
        released = true;
        try {
            vlc.setEventListener(null);
        } catch (Throwable ignored) {
        }
        try {
            if (vout.areViewsAttached()) vout.detachViews();
        } catch (Throwable ignored) {
        }
        try {
            vlc.stop();
        } catch (Throwable ignored) {
        }
        try {
            vlc.release();
        } catch (Throwable e) {
            Log.e(TAG, "vlc release failed", e);
        }
        try {
            libVlc.release();
        } catch (Throwable e) {
            Log.e(TAG, "libVlc release failed", e);
        }
        playlist.clear();
        currentIndex = C.INDEX_UNSET;
        resetPlaybackState();
    }

    private static PlaybackException error(String message, @Nullable Throwable cause, int errorCode) {
        return new PlaybackException(message, cause, errorCode);
    }
}
