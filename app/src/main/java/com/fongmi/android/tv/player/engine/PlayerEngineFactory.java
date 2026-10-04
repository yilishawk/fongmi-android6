package com.fongmi.android.tv.player.engine;

import static com.fongmi.android.tv.player.engine.PlayerEngine.Type.EXO;
import static com.fongmi.android.tv.player.engine.PlayerEngine.Type.MPV;
import static com.fongmi.android.tv.player.engine.PlayerEngine.Type.VLC;

import android.util.Log;

import androidx.media3.common.Player;

import com.fongmi.android.tv.player.exo.ExoPlayerEngine;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.player.mpv.MpvPlayerEngine;
import com.fongmi.android.tv.player.vlc.VlcPlayerEngine;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.utils.UrlUtil;

public final class PlayerEngineFactory {

    private static final String TAG = "PlayerEngineFactory";

    public static PlayerEngine create(int decode, Player.Listener listener) {
        return create(decode, resolve(), listener);
    }

    public static PlayerEngine create(int decode, PlaySpec spec, Player.Listener listener) {
        return create(decode, resolve(spec), listener);
    }

    public static PlayerEngine create(int decode, PlayerEngine.Type type, Player.Listener listener) {
        return switch (type) {
            case EXO -> new ExoPlayerEngine(decode, listener);
            case MPV -> new MpvPlayerEngine(decode, listener);
            case VLC -> createVlc(decode, listener);
        };
    }

    /**
     * VLC 引擎的构造带上**降级兜底**。
     *
     * <p>为什么不像 MPV 那样直接 {@code new}：VLC 的初始化要跨 native 边界
     * （{@code LibVLC} + {@code MediaPlayer} 各一次 JNI 构造），失败面比 MPV 大。
     * 而 {@code PlayerManager.ensureEngine()} 是<b>不 catch</b> 的 —— 一旦这里抛出去，
     * 整个播放页就崩了。</p>
     *
     * <p>⇒ 初始化失败时静默退回 EXO：用户看到的是"这个源用 EXO 播了"，
     * 而不是"App 崩了"。{@link VlcPlayerEngine#isAvailable()} 已在 {@link #resolve}
     * 里做过一轮门禁，这里是第二道。</p>
     */
    private static PlayerEngine createVlc(int decode, Player.Listener listener) {
        try {
            return new VlcPlayerEngine(decode, listener);
        } catch (Throwable e) {
            Log.e(TAG, "VLC engine init failed, falling back to EXO", e);
            return new ExoPlayerEngine(decode, listener);
        }
    }

    public static boolean matches(PlayerEngine engine, PlaySpec spec) {
        return engine != null && engine.getType() == resolve(spec) && !engine.needsRebuild();
    }

    private static PlayerEngine.Type resolve(PlaySpec spec) {
        if (requiresExo(spec)) return EXO;
        if (isVlcReady()) return VLC;
        if (!isMpvReady()) return EXO;
        return MPV;
    }

    private static PlayerEngine.Type resolve() {
        if (isVlcReady()) return VLC;
        return isMpvReady() ? MPV : EXO;
    }

    private static boolean requiresExo(PlaySpec spec) {
        return spec.getDrm() != null || "smb".equals(UrlUtil.scheme(spec.getUrl()));
    }

    private static boolean isMpvReady() {
        return PlayerSetting.isMpv() && MpvPlayerEngine.isAvailable();
    }

    /**
     * 用户选了 VLC <b>且</b> libvlc 在这台机器上能加载。
     *
     * <p>两个条件缺一不可：前者是用户意图，后者是硬约束 —— native 加载不了时
     * 退回 EXO，行为与"没有 VLC 这个选项"一致（见 {@code VlcPlayerEngine} 类注释）。</p>
     */
    private static boolean isVlcReady() {
        return PlayerSetting.isVlc() && VlcPlayerEngine.isAvailable();
    }
}
