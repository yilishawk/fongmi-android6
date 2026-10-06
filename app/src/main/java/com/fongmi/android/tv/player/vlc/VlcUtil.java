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

            // ⚠⚠ 探针 6（578）`--vout=android-opaque` —— 2026-10-06 11:05 **判读失败并回退**，勿再启用。
            //   实测（`.gradle-user/2026-10-06-探针6-578判读.md`，10:55 起 205,225 行，零例外）：
            //     looking for vout display module matching "android-opaque": 5 candidates   ×4129
            //     no vout display modules matched                                           ×4129
            //     decoder: Opaque Vout request failed                                       ×7
            //     decoder: MediaCodec via NDK closed = 7 / opened = 0                       ← 硬解被关
            //     event Vout: count=0 ×4130 / count=1 ×0
            //
            //   ⭐⭐⭐⭐ 2026-10-06 13:3x **根因定案后修正本段结论**（原文见 git 历史）：
            //     `android-opaque` **确实是** `display.c` 里 `add_submodule()` 出来的正规
            //     `vout display` 模块（`set_capability("vout display", 280)`，比 gles2 还高）。
            //     574/576/578 全失败的真因是 **`OpenCommon()` 在
            //     `AWindowHandler_canSetVideoLayout()` 门禁处静默返回 EGENERIC**（因为我们没注册
            //     `IVLCVout.OnNewVideoLayoutListener`），**不是**「名字不对 / 不在候选池」。
            //     ⚠ VLC 的 `no vout display modules matched` **在 Open() 失败时也会打**，措辞误导。
            //     `decoder: Opaque Vout request failed` 来自 `mediacodec.c`（解码器），
            //     且 **577 基线同样存在**，不是 578 独有。
            //   判读全文：`.gradle-user/2026-10-06-根因定案-android-display门禁.md`
            //
            //   ⇒ **仍成立**的部分：`vlc_module_load(strict=true)` 不回退 ⇒ 指定一个**打不开**的
            //     `--vout` 名比不指定更糟（578 把硬解也一并关掉）。故这三次探针仍作废。

            // ⭐⭐⭐ 探针 7（579）`--android-display-chroma=RV16`（**等号**形式）—— 唯一变量。
            //
            // 依据（三条实测证据，不是推测）：
            //   ① libvlc.so 里 `android-display-chroma` 真实存在（1 命中）⇒ 合法选项。
            //   ② 但 `LibVLC` 构造器自动追加的是**空格形式**：`--android-display-chroma` 与 `RV16`
            //      是两个独立 argv 元素（见 577 日志
            //      `options=[..., --aout=android_audiotrack, --android-display-chroma, RV16]`）。
            //      VLC 的命令行解析按 `=` 取值，空格形式很可能**没把 RV16 绑上去**；
            //      而 577/578 日志里 `Unknown option` 均为 **0 次** ⇒ **不能据此判定空格形式生效**
            //      —— 无 `-` 前缀的 `RV16` 会被当作 MRL 静默丢弃，不报错。
            //   ③ ⭐ 决定性反证：§44 的 SurfaceFlinger 取证显示 **VLC 提交的 buffer = 32bpp RGBA**，
            //      而 RV16 = **16bpp RGB565** ⇒ **RV16 实际没有生效**（否则应是 16bpp）。
            //
            // 假设：VLC 以 RGBA（带 alpha）渲染；若 alpha 未按不透明处理 ⇒ 合成结果纯黑。
            //       这与「VLC 以 ~25 fps 稳定提交帧、但 screencap 纯黑」完全吻合。
            //
            // ⭐ 判据**不依赖画面**（故不是哑探针，这一点与 578 不同）：
            //   ✅ 生效   ⇒ `dumpsys SurfaceFlinger` 里该 layer 由 **32bpp → 16bpp**（画面可能同时出来）
            //   ❌ 未生效 ⇒ 仍 32bpp ⇒ 排除该假设
            //
            // ⚠ 579 真机判读（2026-10-06 12:00）：**该假设不成立** —— options 确认变成
            //   `[--verbose=2, --android-display-chroma=RV16, --aout=...]`（等号形式且顶掉了自动追加的
            //   空格形式），但 vout 链与 577 **逐行相同**：`using vout display module "gles2"` +
            //   `original format sz 1088x544, 4cc ANOP`（`4cc` 仍 ANOP，未变 RV16），画面仍黑。
            //   ⇒ **`--android-display-chroma` 对 `gles2` 无效**（该模块不认它 / 不据此改输出 chroma）。
            //   ⇒ 580 已删掉本行，转 `gles2`/libplacebo 色彩管线（见下）。
            // options.add("--android-display-chroma=RV16");   // 579 已证无效，删

            // ⚠⚠ 探针 8（580）`--target-prim/trc=bt709` + `--tone-mapping=disabled` 已于
            //   2026-10-06 判读为**无效并删除**：三个选项都进了 runtime、`Unknown option`=0，
            //   但 `gles2` 逐行无变化、`4cc` 仍 ANOP ⇒ **色彩映射假设不成立**。
            //   ⇒ **根因不在这里**（不是 gles2 的 bug）—— 见
            //     `VlcPlayer.handleSetVideoOutput()` 的 `attachViews(listener)` 与
            //     `.gradle-user/2026-10-06-根因定案-android-display门禁.md`。
            // 回退：本块无 active 选项；options 现在只剩 `--verbose=2`（+ LibVLC 自动追加的
            //       `--aout=*` 与 `--android-display-chroma RV16`），即 577 的选项基线。

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
