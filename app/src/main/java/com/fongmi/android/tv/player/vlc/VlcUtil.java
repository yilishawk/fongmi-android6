package com.fongmi.android.tv.player.vlc;

import android.content.Context;
import android.util.Log;

import org.videolan.libvlc.LibVLC;

import java.util.ArrayList;

/**
 * libVLC 的加载门禁与实例创建 —— {@link VlcPlayer} 与 {@link VlcPlayerEngine} 的公共前置。
 *
 * <h2>⚠ 为什么这里要"先自己 dlopen，再构造 LibVLC"</h2>
 *
 * {@code LibVLC.loadLibraries()} 在 {@code libvlc} / {@code libvlcjni} 加载失败时
 * <b>直接调用 {@code System.exit(1)}</b>（反汇编实测，{@code .gradle-user/vlcapi2/libvlc_full.txt}，
 * 方法偏移 79 / 112；详见 {@link VlcProbe} 的类注释与
 * {@code .gradle-user/VLC接入-第1步探针.md} §2）。
 *
 * <p>{@code System.exit} <b>捕获不了</b> —— 谁在加载失败的情况下直接构造 {@code LibVLC}，
 * 谁就会把整个 App 当场打死。而 {@code System.loadLibrary} 抛的
 * {@code UnsatisfiedLinkError} 是可捕获的。</p>
 *
 * <p>⇒ 本类把"能不能加载"变成一个<b>可查询的布尔值</b>：{@link #isLoadable()} 只做
 * {@code System.loadLibrary}，失败返回 false；只有它返回 true 之后，{@link #create(Context)}
 * 才允许构造 {@code LibVLC}（此时 {@code loadLibraries()} 里那两次 {@code loadLibrary}
 * 是幂等空操作，不会走到 exit）。</p>
 *
 * <p>边界：{@code isLoadable()} 证明的是"native 库能被进程加载"，
 * <b>不等于</b>"VLC 一定能出画" —— 后者只有真机能答。</p>
 */
public final class VlcUtil {

    private static final String TAG = "VlcUtil";

    /** 三态：null = 还没试过。避免重复 dlopen（也避免重复打日志）。 */
    private static Boolean loadable;

    private VlcUtil() {
    }

    /**
     * libvlc / libvlcjni 能否加载进本进程。幂等，结果缓存。
     *
     * <p>⚠ 这是 {@code VlcPlayerEngine.isAvailable()} 的唯一判据 —— 返回 false 时
     * 引擎工厂会退回 EXO，与"没有 VLC 这个引擎"完全一致。</p>
     */
    public static synchronized boolean isLoadable() {
        if (loadable != null) return loadable;
        boolean ok;
        try {
            System.loadLibrary("vlc");
            System.loadLibrary("vlcjni");
            ok = true;
        } catch (Throwable e) {
            Log.e(TAG, "loadLibrary failed: " + describe(e));
            ok = false;
        }
        loadable = ok;
        return ok;
    }

    /**
     * 创建 {@code LibVLC} 实例。调用方必须已经确认 {@link #isLoadable()} == true。
     *
     * <p>这里**不再兜底**判断：如果 loadable 为 false 还硬构造，会触发那个不可捕获的
     * {@code System.exit(1)}。宁可让调用方在构造前返回 null。</p>
     *
     * @return 新实例；native 不可用或构造异常时返回 {@code null}（绝不抛）。
     */
    public static LibVLC create(Context context) {
        if (!isLoadable()) {
            Log.w(TAG, "create skipped: native not loadable");
            return null;
        }
        try {
            // 参数列表留空 —— 走 VLC 默认配置。若将来要下发选项（如 :network-caching），
            // 从这里加，不要散落到调用点。
            return new LibVLC(context.getApplicationContext(), new ArrayList<String>());
        } catch (Throwable e) {
            Log.e(TAG, "new LibVLC failed: " + describe(e));
            return null;
        }
    }

    static String describe(Throwable e) {
        String msg = e.getMessage();
        return e.getClass().getName() + (msg == null ? "" : ": " + msg);
    }
}
