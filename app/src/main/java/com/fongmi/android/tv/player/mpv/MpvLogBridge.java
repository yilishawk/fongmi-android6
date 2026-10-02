package com.fongmi.android.tv.player.mpv;

import com.github.catvod.crawler.DebugLogStore;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import is.xyz.mpv.MPVLib;

/**
 * 把 **mpv 自己的日志**接进 App 的调试日志（设置里的「调试日志」开关 → 9978 端口的
 * {@code /debug/logs} 页面）。
 *
 * <p>为什么需要它：以前要看 mpv 报什么，只能自己在 {@code /sdcard/TV/mpv/mpv.conf} 里写
 * {@code log-file=...} + {@code msg-level=all=v}，再去 sdcard 上捞文件。
 * 而「同一个源 Exo 能播、MPV 不能播」这类问题，**证据全在 mpv 侧**，
 * App 日志里一条都没有。本类把它接上。</p>
 *
 * <h3>机制（已用字节码核实，不是猜的）</h3>
 * <ul>
 *   <li>{@code is.xyz.mpv.MPVLib} 有一份 {@code CopyOnWriteArrayList<LogObserver> LOG_OBSERVERS}
 *       （{@code MPVLib.class} 的 {@code <clinit>} 只建这个 List，**不加载 native**，
 *        所以任何时机触达本类都安全）。</li>
 *   <li>native 侧（{@code libplayer.so}，含 {@code mpv_request_log_messages}）
 *       每条日志都回调静态方法 {@code MPVLib.logMessage(prefix, level, text)}，
 *       该方法**扇出**给所有已注册的 observer。</li>
 *   <li>⇒ 我们只要 {@code addLogObserver}，就能在 Java 里实时拿到 mpv 日志。</li>
 * </ul>
 *
 * <h3>⚠ 边界（说清楚，别当成"已解决"）</h3>
 * <ul>
 *   <li><b>分级转发</b>：{@code warn} 及以上（fatal/error/warn）**全部转发**；
 *       {@code info}/{@code v} 级**限量转发**（{@link #VERBOSE_QUOTA} 条）；
 *       {@code debug}/{@code trace} 级**直接丢弃**（太吵）。
 *       理由：{@link DebugLogStore} 的 {@code LINES} 队列**没有上限**，而 {@code all=v} 级别的
 *       mpv 日志实测约 60 行/秒（30 秒 1790 行）—— 一集 45 分钟 ≈ 16 万行，会把内存和
 *       {@code /debug/logs} 页面一起打爆。限量的另一个好处：**播不了的时候日志本来就少，
 *       全都会留下**；播得了的时候日志爆量，截断无所谓。</li>
 *   <li>为什么必须收 {@code v} 级：判定「电视上 gpu-next 到底有没有生效」的**唯一直接证据**
 *       就是 {@code VO: [gpu-next] ...} / {@code VO: [gpu] ...} 这一行，而它是 {@code v} 级。</li>
 *   <li><b>不下发 {@code msg-level}、不写 {@code log-file}</b> ⇒ 对 mpv 的行为**零改动**，
 *       也不覆盖用户在 {@code mpv.conf} 里的设置。</li>
 *   <li>⚠ <b>待真机确认</b>：native 请求的日志级别是**写死在 {@code libplayer.so} 里的**
 *       （全文件搜 {@code \x00v\x00} 与各种 level 名字均 **0 命中** ⇒ 静态判不出来）。
 *       如果它请求的级别本来就低，{@code v} 级也拿不到 —— 那时才需要走
 *       「App 下发 {@code msg-level}」那条路（代价见 MEMORY.md §18）。
 *       <b>静态分析到此为止，必须真机验。</b></li>
 *   <li>只影响**调试日志开启时**：{@link DebugLogStore#add} 在未开启时第一行就 return，
 *       所以关掉开关时本类的开销 ≈ 一次布尔判断。</li>
 * </ul>
 *
 * <p>回滚：删本类 + 去掉 {@code MpvUtil.buildPlayer()} 里那一行 {@code MpvLogBridge.install()}。</p>
 */
public final class MpvLogBridge implements MPVLib.LogObserver {

    /** mpv 的日志级别常量（mpv/client.h 的 MPV_LOG_LEVEL_*）。 */
    private static final int LEVEL_FATAL = 10;
    private static final int LEVEL_ERROR = 20;
    private static final int LEVEL_WARN = 30;
    private static final int LEVEL_INFO = 40;
    private static final int LEVEL_V = 50;

    /** {@code info}/{@code v} 级的转发配额（warn 及以上不受限）。见类注释的「分级转发」。 */
    private static final int VERBOSE_QUOTA = 6000;

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final AtomicInteger VERBOSE_USED = new AtomicInteger(0);

    private MpvLogBridge() {
    }

    /**
     * 注册日志观察者，并**重置本次播放的配额**。幂等，且任何异常都吞掉 —— 日志是附加品，
     * 绝不允许它影响播放。在 {@code MpvPlayer} 构建**之前**调用（native init 之后才注册会漏掉早期日志）。
     *
     * <p>配额在每次调用时重置（不是只在首次注册时）：{@link #VERBOSE_USED} 是**进程级**静态量，
     * 如果只在首次重置，那么"先正常看一集 45 分钟（配额耗尽）→ 再复现出问题的那个源"
     * 就一条 {@code v} 级日志都拿不到 —— 恰好丢掉最需要的那一次。
     * 重置放在最前面，保证即使注册已经完成、本次也能拿到新配额。</p>
     *
     * <p>用 {@code compareAndSet} 而不是 {@code get()+set()}：{@code MPVLib.addLogObserver}
     * 内部是 {@code addIfAbsent}，按 {@code equals} 去重，而两个 {@code new MpvLogBridge()}
     * **不是** equal（默认 identity）⇒ 并发下会重复注册、日志翻倍。</p>
     */
    public static void install() {
        VERBOSE_USED.set(0);
        if (!INSTALLED.compareAndSet(false, true)) return;
        try {
            MPVLib.addLogObserver(new MpvLogBridge());
        } catch (Throwable ignored) {
            // 拿不到 mpv 日志不影响播放；放开标志位，允许下次再试
            INSTALLED.set(false);
        }
    }

    @Override
    public void logMessage(String prefix, int level, String text) {
        if (level > LEVEL_V) return;
        if (!DebugLogStore.isEnabled()) return;
        if (level < LEVEL_WARN && VERBOSE_USED.incrementAndGet() > VERBOSE_QUOTA) return;
        try {
            DebugLogStore.add("mpv", "[" + levelName(level) + "][" + safe(prefix) + "] " + safe(text));
        } catch (Throwable ignored) {
        }
    }

    /** 用 mpv 自己的单字母写法（f/e/w/i/v），凯哥在 mpv.log 里看到的就是这个。 */
    private static String levelName(int level) {
        if (level <= LEVEL_FATAL) return "f";
        if (level <= LEVEL_ERROR) return "e";
        if (level <= LEVEL_WARN) return "w";
        if (level <= LEVEL_INFO) return "i";
        return "v";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
