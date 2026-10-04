package com.fongmi.android.tv.player.vlc;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.github.catvod.crawler.DebugLogStore;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.MediaPlayer;

import java.util.ArrayList;

/**
 * VLC 接入第 1 步：最小探针（不接 UI、不接 PlayerEngine）。
 *
 * <p>目的只有一个 —— 回答「{@code libvlc.so} 在这台 Android 6 电视上到底能不能加载起来」。
 * 这是甲方案里最大的一块不确定性（见 {@code .gradle-user/VLC接入-甲方案设计与回滚.md} §5.1）：
 * 符号审计能证明它<b>可以</b>加载（必要条件），但证明不了它<b>真的</b>加载成功（充分条件）。
 *
 * <p>结果写到 {@link DebugLogStore}，tag = {@code VlcProbe}，从 9978 端口的
 * {@code /debug/logs} 取。同时打一份到 logcat（tag {@code VlcProbe}）——
 * 因为本探针最可能的失败形态是"进程直接消失"，那时只有 logcat 还留着现场。
 *
 * <h2>⚠ 为什么必须先自己 dlopen 一次，而不是直接 new LibVLC()</h2>
 *
 * {@code LibVLC.loadLibraries()} 在 {@code libvlc}/{@code libvlcjni} 加载失败时
 * <b>会直接调用 {@code System.exit(1)}</b>。这是反汇编实测，不是推测
 * （{@code .gradle-user/vlcapi2/libvlc_full.txt}，方法偏移 79 / 112）：
 *
 * <pre>
 *   try { System.loadLibrary("vlc"); System.loadLibrary("vlcjni"); }
 *   catch (UnsatisfiedLinkError e) { Log.e(...); System.exit(1); }
 *   catch (SecurityException   e) { Log.e(...); System.exit(1); }
 * </pre>
 *
 * 而 {@code System.exit} 是<b>捕获不了</b>的 —— 谁在探针里直接构造 {@code LibVLC}，
 * 谁就会在加载失败时把整个 App 当场打死，且什么都留不下来。
 * {@code System.loadLibrary} 抛的 {@code UnsatisfiedLinkError} 则可捕获，
 * 所以"先自己试一次"是唯一能在不弄死 App 的前提下拿到失败原因的做法。
 *
 * <p>反过来，如果预载成功，{@code loadLibraries()} 里那两次 {@code loadLibrary}
 * 就是幂等空操作（bionic 对同一 ClassLoader 已加载过的库不重复解析），不会触发 exit。
 *
 * <p>另外：{@code LibVLC} 的静态块只有一句 {@code sLoaded = false}（同上反汇编，偏移 0-4），
 * <b>不</b>调用 {@code loadLibraries()}。所以单纯"碰到这个类"是安全的，只有构造函数才危险。
 */
public final class VlcProbe {

    private static final String TAG = "VlcProbe";

    private static volatile boolean started;

    private VlcProbe() {
    }

    /**
     * 幂等触发。不阻塞调用线程（探针跑在自己的线程上）。
     *
     * <p>调用方（{@code App.onCreate()}）只在调试日志开着时才调 —— 关着时整条链路
     * 一次都不执行，行为与加探针之前完全一致。
     */
    public static void run(Context context) {
        if (started) return;
        started = true;
        final Context app = context.getApplicationContext();
        Thread thread = new Thread(() -> probe(app), "vlc-probe");
        thread.setDaemon(true);
        thread.start();
    }

    private static void probe(Context context) {
        log("start sdk=" + Build.VERSION.SDK_INT + " abis=" + join(Build.SUPPORTED_ABIS));

        // 1) 自己先 dlopen（可捕获的失败），再决定要不要碰 LibVLC（不可捕获的失败）。
        if (!load("vlc")) return;
        if (!load("vlcjni")) return;
        log("native 预载成功：libvlc.so + libvlcjni.so 都进了进程");

        // 2) 到这里才允许构造。此时 loadLibraries() 里的两次 loadLibrary 是空操作。
        LibVLC libVlc = null;
        MediaPlayer player = null;
        try {
            libVlc = new LibVLC(context, new ArrayList<String>());
            log("LibVLC 构造成功 version=" + LibVLC.version() + " changeset=" + LibVLC.changeset());
            player = new MediaPlayer(libVlc);
            log("MediaPlayer 构造成功");
        } catch (Throwable e) {
            log("初始化失败：" + describe(e));
        } finally {
            try {
                if (player != null) player.release();
            } catch (Throwable e) {
                log("player.release 异常：" + describe(e));
            }
            try {
                if (libVlc != null) libVlc.release();
            } catch (Throwable e) {
                log("libVlc.release 异常：" + describe(e));
            }
            log("release 完成");
        }
    }

    private static boolean load(String name) {
        try {
            System.loadLibrary(name);
            log("System.loadLibrary(\"" + name + "\") OK");
            return true;
        } catch (Throwable e) {
            log("System.loadLibrary(\"" + name + "\") 失败：" + describe(e));
            return false;
        }
    }

    private static String describe(Throwable e) {
        String msg = e.getMessage();
        return e.getClass().getName() + (msg == null ? "" : ": " + msg);
    }

    private static String join(String[] values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) builder.append(',');
            builder.append(values[i]);
        }
        return builder.toString();
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
        DebugLogStore.add(TAG, msg);
    }
}
