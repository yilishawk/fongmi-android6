package com.fongmi.android.tv.player.vlc;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;

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

            // ⚠⚠ 探针 5（576）`--vout=android-display` 已于 2026-10-06 **判读为失败并回退**，勿再启用：
            //   实测（`.gradle-user/live576.txt`）：
            //     looking for vout display module matching "android-display": 5 candidates
            //     no vout display modules matched                                  ×802
            //     event Vout: count=0                                              ×802
            //   ⇒ **连字符也匹配不到**（574 的下划线同样失败）⇒ 该模块**运行时不在候选池**，
            //     **不是拼写问题**。`.so` 里有 `android-display` shortcut 字符串，但
            //     **字符串在 ≠ 模块已注册**。
            //   ⚠⚠ 更糟的是：`vlc_module_load(strict=true)` **不回退** ⇒ 575（无 `--vout`）
            //     是 `using vout display module "gles2"` / `count=1`；576 变成 **`count=0`**。
            //     ⇒ **指定一个不存在的 `--vout` 名，比不指定更糟。**
            //   判读全文：`.gradle-user/2026-10-06-576判读-android-display不可选.md`

            // ⭐⭐⭐ 探针 6（578）`--vout=android-opaque` —— 2026-10-06 10:40 凯哥批准（唯一变量）。
            //
            // 依据（`.so` 取证）：libvlc.so 里有**两个** Android 视频输出模块的描述串 ——
            //     "Android video output"        （旁有短名串 `ANW`）
            //     "Android opaque video output" （shortcut 串 `android-opaque`）
            //   `android_display`（`android-display`）已被 574/576 **实测**证明不在运行时候选池；
            //   但 **`android-opaque` 是另一个模块，从未试过**。
            //
            // ⚠ 影响面（与 576 同型风险）：若它同样不在候选池，`vlc_module_load(strict=true)`
            //   **不回退** ⇒ 会退化成 `no vout display modules matched` + `event Vout: count=0`。
            //   但用户可见表现与现状一致（本来就黑），且**判据一眼可辨**：
            //     ✅ 可选 ⇒ `using vout display module "android-opaque"`（走直通路径，与 EXO 同类）
            //     ❌ 不可选 ⇒ `looking for vout display module matching "android-opaque": 5 candidates`
            //                 + `no vout display modules matched`
            //   ⚠ 二者之间还有第三种可能：已注册但 `Open` 失败 —— 该情形本日志**无法区分**。
            //
            // 回退：删掉本行即回到 577 状态（`gles2`），无其它耦合。
            options.add("--vout=android-opaque");

            // ⭐⭐ 577 的**真正发现**（不是 vout，是**解码器**）—— 来自 576 的 `VLC-std`：
            //   looking for video decoder module matching "mediacodec_ndk,all": 14 candidates
            //   W VLC: Exception occurred in MediaCodecInfo.getCapabilitiesForType   ← 硬解能力识别失败
            //   using ffmpeg Lavc58.134.100 / allowing 6 thread(s) for decoding
            //   using video decoder module "avcodec"                                 ← 退回软解
            //   [h264] get_buffer() failed / thread_get_buffer() failed / no frame!  ← 软解也解不出帧
            //   而 **EXO 用同一个 MTK 硬解器（c2.mtk.avc.decoder）正常出画**
            //   ⇒ 系统硬解没坏，断点在「VLC 怎么问解码器能力」。
            //   ⇒ 所以本轮不再折腾 vout，改为**把解码器清单一次性打进日志**（见下方自检）。

            // ⭐ 解码器自检：只读枚举，不参与播放决策（见方法注释）。
            dumpVideoDecoders();

            LibVLC libVlc = new LibVLC(context.getApplicationContext(), options);
            VlcLog.d("new LibVLC OK: version=" + LibVLC.version() + " options=" + options);
            return libVlc;
        } catch (Throwable e) {
            VlcLog.e("new LibVLC failed: " + describe(e));
            return null;
        }
    }

    /** 自检只跑一次（create 可能被多次调用）。 */
    private static boolean decodersDumped;

    /**
     * ⭐ 视频解码器自检（诊断用，**只读**，不参与任何播放决策）。
     *
     * <h2>为什么加它</h2>
     *
     * 576 现场日志（`.gradle-user/live576.txt`）显示断点在**解码器**，不在 vout：
     * <pre>
     * looking for video decoder module matching "mediacodec_ndk,all": 14 candidates
     * W VLC: Exception occurred in MediaCodecInfo.getCapabilitiesForType   ← 硬解能力识别失败
     * using ffmpeg Lavc58.134.100 / allowing 6 thread(s) for decoding
     * using video decoder module "avcodec"                                 ← 退回软解
     * [h264] get_buffer() failed / thread_get_buffer() failed / no frame!  ← 软解也解不出帧
     * </pre>
     *
     * 而 <b>EXO 用同一个 MTK 硬解器（{@code c2.mtk.avc.decoder}）正常出画</b> ⇒
     * 系统硬解没坏，问题在「谁去问能力、怎么问」。
     *
     * <h2>它回答什么</h2>
     * <ol>
     *   <li>MTK 硬解器是否存在、是否被标记为硬件加速；</li>
     *   <li>{@code getCapabilitiesForType("video/avc")} 在本机是否抛异常、抛的是什么；</li>
     *   <li>若抛异常，是「个别解码器」还是「全部」——后者指向平台/API 层面。</li>
     * </ol>
     *
     * <h2>边界</h2>
     * 只做枚举与查询：<b>不创建 codec、不 configure、不改任何播放参数</b>；
     * 任何异常都被吞掉，绝不影响播放。{@code isHardwareAccelerated()} 是 API 29+，
     * 低版本返回 {@code "?"}（不猜）。
     */
    static void dumpVideoDecoders() {
        if (decodersDumped) return;
        decodersDumped = true;
        try {
            MediaCodecInfo[] infos = new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
            VlcLog.d("[自检] 枚举 codec 共 " + infos.length + " 个，筛 video/avc");
            int hit = 0;
            for (MediaCodecInfo info : infos) {
                boolean hasAvc = false;
                for (String t : info.getSupportedTypes()) {
                    if ("video/avc".equalsIgnoreCase(t)) {
                        hasAvc = true;
                        break;
                    }
                }
                if (!hasAvc) continue;
                hit++;
                String caps;
                try {
                    MediaCodecInfo.CodecCapabilities c = info.getCapabilitiesForType("video/avc");
                    caps = "caps=OK(profiles=" + (c.profileLevels == null ? 0 : c.profileLevels.length)
                            + ", colorFormats=" + (c.colorFormats == null ? 0 : c.colorFormats.length) + ")";
                } catch (Throwable e) {
                    caps = "caps=EXCEPTION " + describe(e);
                }
                VlcLog.d("[自检] #" + hit + " " + info.getName() + " hw=" + hwFlag(info) + " " + caps);
            }
            VlcLog.d("[自检] video/avc 解码器共 " + hit + " 个");
        } catch (Throwable e) {
            VlcLog.e("[自检] dumpVideoDecoders 失败: " + describe(e));
        }
    }

    /** {@code isHardwareAccelerated()} 需要 API 29；低版本不猜，返回 "?"。 */
    private static String hwFlag(MediaCodecInfo info) {
        if (android.os.Build.VERSION.SDK_INT < 29) return "?";
        try {
            return String.valueOf(info.isHardwareAccelerated());
        } catch (Throwable e) {
            return "ERR";
        }
    }

    static String describe(Throwable e) {
        String msg = e.getMessage();
        return e.getClass().getName() + (msg == null ? "" : ": " + msg);
    }
}
