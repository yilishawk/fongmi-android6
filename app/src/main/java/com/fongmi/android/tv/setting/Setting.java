package com.fongmi.android.tv.setting;

import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.text.TextUtils;
import android.webkit.WebSettings;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.ResUtil;
import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Prefers;

public class Setting {

    private static final int MIN_WALL = 0;
    private static final int MAX_WALL = 4;
    private static final int MIN_WALL_TYPE = 0;
    private static final int MAX_WALL_TYPE = 2;
    private static final int MIN_SITE_MODE = 0;
    private static final int MAX_SITE_MODE = 1;
    private static final int MIN_SYNC_MODE = 0;
    private static final int MAX_SYNC_MODE = 2;

    public static String getSwitch(boolean value) {
        return ResUtil.getString(value ? R.string.setting_on : R.string.setting_off);
    }

    public static String getDoh() {
        return Prefers.getString("doh");
    }

    public static void putDoh(String doh) {
        Prefers.put("doh", doh);
    }

    public static String getUa() {
        return Prefers.getString("ua");
    }

    public static void putUa(String ua) {
        Prefers.put("ua", ua);
    }

    public static String getKeyword() {
        return Prefers.getString("keyword");
    }

    public static void putKeyword(String keyword) {
        Prefers.put("keyword", keyword);
    }

    public static String getHot() {
        return Prefers.getString("hot");
    }

    public static void putHot(String hot) {
        Prefers.put("hot", hot);
    }

    public static int getWall() {
        return Math.clamp(Prefers.getInt("wall", 1), MIN_WALL, MAX_WALL);
    }

    public static void putWall(int wall) {
        Prefers.put("wall", Math.clamp(wall, MIN_WALL, MAX_WALL));
    }

    public static int getWallType() {
        return Math.clamp(Prefers.getInt("wall_type", 0), MIN_WALL_TYPE, MAX_WALL_TYPE);
    }

    public static void putWallType(int type) {
        Prefers.put("wall_type", Math.clamp(type, MIN_WALL_TYPE, MAX_WALL_TYPE));
    }

    public static int getThemeColor() {
        return Prefers.getInt("theme_color", -1);
    }

    public static void putThemeColor(int color) {
        Prefers.put("theme_color", color);
    }

    public static int getWallColor() {
        return Prefers.getInt("wall_color", 0);
    }

    public static void putWallColor(int color) {
        Prefers.put("wall_color", color);
    }

    public static int getDynamicColor() {
        int color = getThemeColor();
        if (color == -1) return 0;
        return color != 0 ? color : getWallColor();
    }

    public static int getSiteMode() {
        return Math.clamp(Prefers.getInt("site_mode"), MIN_SITE_MODE, MAX_SITE_MODE);
    }

    public static void putSiteMode(int mode) {
        Prefers.put("site_mode", Math.clamp(mode, MIN_SITE_MODE, MAX_SITE_MODE));
    }

    public static int getSyncMode() {
        return Math.clamp(Prefers.getInt("sync_mode"), MIN_SYNC_MODE, MAX_SYNC_MODE);
    }

    public static void putSyncMode(int mode) {
        Prefers.put("sync_mode", Math.clamp(mode, MIN_SYNC_MODE, MAX_SYNC_MODE));
    }

    public static boolean isIncognito() {
        return Prefers.getBoolean("incognito");
    }

    public static void putIncognito(boolean incognito) {
        Prefers.put("incognito", incognito);
    }

    public static boolean getUpdate() {
        return Prefers.getBoolean("update", true);
    }

    public static void putUpdate(boolean update) {
        Prefers.put("update", update);
    }

    // =========================================================================
    // 更新下载加速前缀（2026-09-30 新增）
    //
    // 注意语义：这是 **URL 前缀**，不是 HTTP/SOCKS 代理。gh-proxy 这类服务的用法是
    //   https://gh-proxy.org/https://github.com/<owner>/<repo>/releases/download/...
    // 所以 Updater 里是字符串拼接，与走 OkHttp.selector() 的壳代理（isShellProxy）
    // 完全是两回事，别混用。
    //
    // 范围：只作用于 Updater 下载 APK；更新检测用的 release JSON 不套前缀。
    // 默认空串 = 直连。
    // =========================================================================

    public static String getUpdateProxy() {
        return Prefers.getString("update_proxy");
    }

    public static void putUpdateProxy(String proxy) {
        Prefers.put("update_proxy", proxy == null ? "" : proxy.trim());
    }

    public static boolean isAdblock() {
        return Prefers.getBoolean("adblock", true);
    }

    public static void putAdblock(boolean adblock) {
        Prefers.put("adblock", adblock);
    }

    public static boolean isZhuyin() {
        return Prefers.getBoolean("zhuyin");
    }

    public static void putZhuyin(boolean zhuyin) {
        Prefers.put("zhuyin", zhuyin);
    }

    // =========================================================================
    // 调试日志（从 webhtv 移植；三处 Android 6 适配见 logDebugEnvironment 上方注释）
    // =========================================================================

    public static boolean isDebugLog() {
        return DebugLogStore.isEnabled();
    }

    public static void putDebugLog(boolean debugLog) {
        DebugLogStore.setEnabled(debugLog);
        if (debugLog) logDebugEnvironment("enable");
    }

    public static void logDebugEnvironment(String reason) {
        boolean hardwareAccelerated = (App.get().getApplicationInfo().flags & ApplicationInfo.FLAG_HARDWARE_ACCELERATED) != 0;
        SpiderDebug.log("env", "reason=%s app=%s(%s) flavor=%s debug=%s hardware=%s android=%s sdk=%s incremental=%s manufacturer=%s brand=%s model=%s device=%s product=%s supportedAbis=%s",
                reason,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
                BuildConfig.FLAVOR,
                BuildConfig.DEBUG,
                hardwareAccelerated,
                Build.VERSION.RELEASE,
                Build.VERSION.SDK_INT,
                Build.VERSION.INCREMENTAL,
                Build.MANUFACTURER,
                Build.BRAND,
                Build.MODEL,
                Build.DEVICE,
                Build.PRODUCT,
                TextUtils.join(",", Build.SUPPORTED_ABIS));
        logWebViewProvider();
    }

    // 与上游 webhtv 的三处差异（都是为了能在 API 23 上编译/运行）：
    //  1) 上游打 `mode=%s abi=%s` 两个 BuildConfig 字段，那是 webhtv 自己的 flavor 维度。
    //     TV-fongmi 只有 device 一个维度（leanback/mobile），**没有** FLAVOR_mode /
    //     FLAVOR_abi，照抄编译不过。这里改打 BuildConfig.FLAVOR；ABI 已由 supportedAbis 覆盖。
    //  2) 上游用 String.join(",", Build.SUPPORTED_ABIS) 拼 ABI。String.join 是 **API 26**
    //     才有的平台方法，且在 java.lang.String 上 —— desugar_jdk_libs 不遮蔽 java.lang.*，
    //     所以在 API 23 上会 NoSuchMethodError。改用 android.text.TextUtils.join（API 1）。
    //  3) 上游调 WebViewUtil.logProvider()，TV-fongmi 的 WebViewUtil 没有这个方法
    //     （已核对：只有 spoof()/support()）。这里就地取 WebSettings 的默认 UA 代替，
    //     UA 里含 "Version/4.0 Chrome/<版本>"，信息等价且不依赖 WebView 已被初始化。
    private static void logWebViewProvider() {
        try {
            SpiderDebug.log("webview", "provider ua=%s", WebSettings.getDefaultUserAgent(App.get()));
        } catch (Throwable e) {
            SpiderDebug.log("webview", "provider unavailable error=%s", e.getClass().getSimpleName());
        }
    }

    // =========================================================================
    // 壳代理（从 webhtv 移植）
    // 注意边界：本分支按"只移功能层"的约定**没有搬** webhtv 的 ShellProxyDialog，
    // 所以这 9 个方法目前没有界面入口 —— 改配置要走 Prefers 或后续补的接口。
    // 每次 put* 都会调 ProxySetting.apply()，让改动立即生效（含踢掉旧连接）。
    // =========================================================================

    public static boolean isShellProxy() {
        return Prefers.getBoolean("shell_proxy");
    }

    public static void putShellProxy(boolean shellProxy) {
        Prefers.put("shell_proxy", shellProxy);
        ProxySetting.apply();
    }

    public static String getShellProxyRules() {
        return Prefers.getString("shell_proxy_rules");
    }

    public static void putShellProxyRules(String rules) {
        Prefers.put("shell_proxy_rules", rules);
        ProxySetting.apply();
    }

    public static void putShellProxyConfig(String url, String rules) {
        Prefers.put("shell_proxy_url", url);
        Prefers.put("shell_proxy_rules", rules);
        Prefers.put("shell_proxy_hosts", "*");
        ProxySetting.apply();
    }

    public static String getShellProxyUrl() {
        return Prefers.getString("shell_proxy_url");
    }

    public static void putShellProxyUrl(String url) {
        Prefers.put("shell_proxy_url", url);
        ProxySetting.apply();
    }

    public static String getShellProxyHosts() {
        return Prefers.getString("shell_proxy_hosts", "*");
    }

    public static void putShellProxyHosts(String hosts) {
        Prefers.put("shell_proxy_hosts", hosts);
        ProxySetting.apply();
    }
}
