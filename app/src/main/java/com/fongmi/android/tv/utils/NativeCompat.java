package com.fongmi.android.tv.utils;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 让 API 24 之前（本分支的目标是 Android 6 / API 23）也能加载随 APK 分发的预编译原生库。
 *
 * <p>为什么需要它：APK 里的 {@code libmpv.so}、{@code libavcodec.so}、{@code libavfilter.so}、
 * {@code libavformat.so} 都是对着 android-24 以上的 sysroot 编译的，因此允许引用
 * Android 7.0 才出现的 bionic 符号，而且这些引用带版本节点 {@code LIBC_N}。
 * Android 6 的 libc.so 里根本没有 LIBC_N 这个节点，而 bionic 是 {@code RTLD_NOW} 解析的，
 * 只要有一个符号解析不了，整个 {@code dlopen()} 就失败 —— 表现为播放器"静默不可用"，
 * 没有崩溃、日志里也没有任何线索。
 *
 * <p>本项目的具体缺口是实测出来的，不是猜的（用 webhtv 那套带版本节点的审计工具
 * {@code apk-check/audit_sym_versions.py} 扫 APK 自己的原生库 + NDK 的 API-23 stub）：
 *
 * <pre>
 *   armeabi-v7a
 *     libmpv.so       __write_chk, getifaddrs, freeifaddrs, fseeko64
 *     libavcodec.so   __aeabi_memcpy/memmove/memset/memclr 及其 4/8 后缀形式
 *     libavfilter.so  （同上 12 个）
 *     libavformat.so  （同上 12 个）
 *   arm64-v8a
 *     libmpv.so       __write_chk, getifaddrs, freeifaddrs
 *                     —— 没有 fseeko64（off_t 本来就是 64 位），也没有 __aeabi_mem*
 *                     （aarch64 根本没有 ARM EABI 运行时助手）
 * </pre>
 *
 * <p>另外 {@code libmpv.so} 的 DT_NEEDED 里还有 {@code libvulkan.so}，而 Vulkan 到 API 24 才有 ——
 * Android 6 上这个<b>文件</b>不存在。垫片补的是符号、补不了文件，所以另外随 assets 发一个桩，
 * 见 {@link #preloadVulkanStub}。
 *
 * <p>两类动作在 API 24 及以上都是空操作：那些符号已经是 libc 的一部分，系统的 libvulkan.so
 * 也是真的，预载桩反而会把它遮蔽掉。
 */
public final class NativeCompat {

    private static final String TAG = "NativeCompat";

    /** CMake 目标名，见 app/src/main/cpp/CMakeLists.txt。 */
    private static final String SHIM_LIB = "tvshim";

    /** assets 下按 ABI 分目录放的 libvulkan.so 桩。 */
    private static final String VULKAN_ASSET_ROOT = "vulkan-libs";
    private static final String VULKAN_STUB = "vulkan";

    /** 桩落地的私有目录名（{@code Context.getDir} 的子目录）。 */
    private static final String VULKAN_DIR = "vulkan-stub";

    private static Boolean ready;
    private static Throwable error;

    private NativeCompat() {
    }

    /**
     * 幂等地完成两件事：加载 libc 垫片、预载 vulkan 桩。
     *
     * <p>必须在任何播放器被创建<b>之前</b>调用 —— 目前在 {@code App.onCreate()} 里。
     * 永远不会抛异常：失败只会让 MPV 不可用，而不是把 app 打崩。
     */
    public static synchronized void ensure(Context context) {
        if (ready != null) return;
        try {
            loadLibcShim();
            preloadVulkanStub(context.getApplicationContext());
            ready = Boolean.TRUE;
        } catch (Throwable e) {
            ready = Boolean.FALSE;
            error = e;
            Log.w(TAG, "原生兼容层初始化失败；API < 24 上 MPV 可能无法加载", e);
        }
    }

    /** {@link #ensure} 失败的原因，或 null。仅供诊断。 */
    public static synchronized Throwable getError() {
        return error;
    }

    /**
     * 加载 libc 垫片（{@code libtvshim.so}）。
     *
     * <p>垫片用 {@code -Wl,-z,global} 把自己标成 {@code DF_1_GLOBAL}，所以 bionic 会在加载时
     * 无条件把它放进全局符号组 —— 不依赖 {@code System.loadLibrary()} 传不传
     * {@code RTLD_GLOBAL}（那是实现细节，各版本改过）。
     *
     * @return 垫片是否可用。API 24 起恒为 true，那里没什么要加载的。
     */
    static boolean loadLibcShim() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) return true;
        try {
            System.loadLibrary(SHIM_LIB);
            Log.i(TAG, "libc 垫片已加载（API " + Build.VERSION.SDK_INT + "）");
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "libc 垫片加载失败，API < 24 上 libmpv.so 可能无法加载", e);
            return false;
        }
    }

    /**
     * 从 assets 取 libvulkan.so 桩，落到私有目录后按绝对路径加载 —— 只在 API 24 之前做。
     *
     * <p>为什么不放 {@code lib/<abi>/}：放在那里会进入 app 的原生库搜索路径，在有 Vulkan 的
     * 设备上会<b>遮蔽系统的真 libvulkan.so</b>，把整个 app 的 Vulkan 弄坏。放 assets 里、
     * 只在 API < 24 用绝对路径预载就没有这个问题。
     *
     * <p>按绝对路径加载就够用，是因为 {@code libmpv.so} 把 libvulkan.so 声明在 DT_NEEDED 里 ——
     * bionic 会先看名字已经在当前命名空间里加载过的库，于是直接复用我们这份。
     */
    static void preloadVulkanStub(Context context) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) return;
        AssetManager assets = context.getAssets();
        String abi = pickBundledAbi(assets);
        if (abi == null) {
            Log.w(TAG, "assets 里没有匹配当前设备的 vulkan 桩，跳过预载");
            return;
        }
        File dir = new File(context.getDir(VULKAN_DIR, Context.MODE_PRIVATE), abi);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建 " + dir);
        File out = new File(dir, System.mapLibraryName(VULKAN_STUB));
        String assetPath = VULKAN_ASSET_ROOT + "/" + abi + "/" + System.mapLibraryName(VULKAN_STUB);
        copyIfChanged(assets, assetPath, out);
        System.load(out.getAbsolutePath());
        Log.i(TAG, "vulkan 桩已预载：" + abi);
    }

    /**
     * 按 {@link Build#SUPPORTED_ABIS} 的顺序挑第一个 assets 里真有的 ABI。
     * 不能直接用 {@code SUPPORTED_ABIS[0]}：那可能是个我们没有对应目录的 ABI。
     */
    private static String pickBundledAbi(AssetManager assets) {
        for (String abi : Build.SUPPORTED_ABIS) {
            try {
                String[] entries = assets.list(VULKAN_ASSET_ROOT + "/" + abi);
                if (entries != null && entries.length > 0) return abi;
            } catch (IOException ignored) {
                // 目录不存在就是没有，继续看下一个 ABI
            }
        }
        return null;
    }

    /** 长度一致就认为内容一致 —— 桩只有十几 KB，但没必要每次启动都写一遍。 */
    private static void copyIfChanged(AssetManager assets, String assetPath, File out) throws IOException {
        try (InputStream in = assets.open(assetPath)) {
            int available = in.available();
            if (out.exists() && out.length() == available) return;
            try (OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
        }
    }
}
