package com.fongmi.android.tv.player.vlc;

import android.content.Context;

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
            // ⚠ 这条是「手机版只有声音」的第一嫌疑：isLoadable()==false 时
            //   引擎工厂会**静默退回 mpv**，用户以为在测 VLC，其实跑的是 mpv
            //   （而 mpv + TextureView 在手机上已知就是「只有声音」）。
            VlcLog.e("loadLibrary failed -> VLC 引擎将不可用（会退回 mpv/EXO）: " + describe(e));
            ok = false;
        }
        loadable = ok;
        VlcLog.d("isLoadable=" + ok + " abi=" + android.os.Build.SUPPORTED_ABIS[0]);
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
            VlcLog.d("create skipped: native not loadable");
            return null;
        }
        try {
            // 参数列表默认留空 —— 走 VLC 默认配置。若将来要下发选项（如 :network-caching），
            // 从这里加，不要散落到调用点。
            ArrayList<String> options = new ArrayList<>();

            // ⚠⚠ 诊断探针（临时，验完必须删）⚠⚠
            // 目的：把 native 日志从默认的 error 级抬到 debug 级，才能看到
            //   vout 模块名 / chroma / display / ANativeWindow 这些上屏链路的关键行。
            //
            // 取值依据是**从本 AAR 的 libvlc.so 里读出来的选项帮助文本**，不是记忆：
            //   @5174006  "This is the verbosity level
            //              (0=only errors and standard messages, 1=warnings, 2=debug)."
            //   @4038658  "Verbosity (0,1,2)"
            //   @5655980  "invalid verbosity level %i"
            // ⇒ 2 == debug。
            //
            // 输出通路：libvlc.so 里有 android_logger 模块 + __android_log_print
            //   ⇒ 直接进 logcat（tag=VLC）。这条通路上一轮已经真机验证过能拿到 native 日志。
            //
            // 自证性：万一该选项被 libvlc_new 拒绝，日志里会出现
            //   "Unknown option '%s'"（libvlc.so @4006606，error 级）——
            //   也就是说这个探针要么给出 debug 日志，要么明确告诉你它没生效，不会静默。
            options.add("--verbose=2");

            // ⚠⚠ 诊断探针 2（临时，验完必须删）⚠⚠
            // 目的：把 MediaCodec 的输出从 **opaque buffer（直通 Surface）** 改成普通 buffer，
            //   从而绕开 glconv_android（MediaCodec surface → GL 纹理）这一层。
            //   用来判定 lzm3u8「有声无画」的断点在 glconv_android，还是在 gles2 → AWindow。
            //
            // 依据（从本 AAR 的 libvlc.so 里读出的选项帮助文本，不是记忆）：
            //   "mediacodec"     → "Video decoder using Android MediaCodec via NDK"
            //   "mediacodec-dr"  → "Android direct rendering"
            //                      "Enable Android direct rendering using opaque buffers."
            // ⇒ 默认开启；--no-mediacodec-dr 关闭 ⇒ 输出不再走 opaque
            //   （对应 §35 日志里 video output 的 "4cc ANOP"）。
            //
            // 自证性：若选项被拒，日志会出现 "Unknown option '%s'"（error 级），不会静默。
            options.add("--no-mediacodec-dr");

            LibVLC libVlc = new LibVLC(context.getApplicationContext(), options);
            VlcLog.d("new LibVLC OK: version=" + LibVLC.version() + " options=" + options);
            return libVlc;
        } catch (Throwable e) {
            VlcLog.e("new LibVLC failed: " + describe(e));
            return null;
        }
    }

    static String describe(Throwable e) {
        String msg = e.getMessage();
        return e.getClass().getName() + (msg == null ? "" : ": " + msg);
    }
}
