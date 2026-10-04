package com.fongmi.android.tv.player.vlc;

import android.util.Log;

import com.github.catvod.crawler.DebugLogStore;

/**
 * VLC 引擎的取证日志口 —— <b>同时写 logcat 与 {@link DebugLogStore}</b>。
 *
 * <h2>为什么需要它</h2>
 *
 * 2026-10-04 凯哥真机反馈「手机版 VLC 只有声音，日志没有任何报错」。
 * 当时的实际情况是：{@code VlcPlayer} / {@code VlcPlayerEngine} / {@code PlayerEngineFactory}
 * / {@code VlcUtil} <b>一行都没写进 {@link DebugLogStore}</b>（只写 logcat），
 * VLC native 的 {@code VLC_MSG_*} 也没有接 ——
 * <b>所以「日志没报错」这句话当时没有任何信息量</b>。
 *
 * <p>本类把「写 logcat」与「写 DebugLogStore」合成一个动作，
 * 避免以后再加日志时只写一半。</p>
 *
 * <h2>边界</h2>
 *
 * <ul>
 *   <li>{@link DebugLogStore#add} 在未开启调试日志时第一行就 return，开销可忽略。</li>
 *   <li>本类<b>不</b>接管 VLC native 的日志（那需要 {@code libvlc} 的 log 回调，
 *       本代没接）—— 这里记的全是 <b>Java 侧</b>的调用点与返回值。</li>
 *   <li>任何写日志的异常都被吞掉：取证日志绝不能影响播放。</li>
 * </ul>
 */
public final class VlcLog {

    /** DebugLogStore 里的分类标签；9978 的 {@code /debug/logs} 按它分段。 */
    public static final String TAG = "vlc";

    private VlcLog() {
    }

    public static void d(String message) {
        Log.i(TAG, message);
        try {
            if (DebugLogStore.isEnabled()) DebugLogStore.add(TAG, message);
        } catch (Throwable ignored) {
        }
    }

    /** 失败路径 —— 同时上 logcat 的 error 级，方便 adb 直接抓。 */
    public static void e(String message) {
        Log.e(TAG, message);
        try {
            if (DebugLogStore.isEnabled()) DebugLogStore.add(TAG, "E " + message);
        } catch (Throwable ignored) {
        }
    }

    public static void e(String message, Throwable cause) {
        Log.e(TAG, message, cause);
        try {
            if (DebugLogStore.isEnabled()) DebugLogStore.add(TAG, "E " + message + " | " + VlcUtil.describe(cause));
        } catch (Throwable ignored) {
        }
    }
}
