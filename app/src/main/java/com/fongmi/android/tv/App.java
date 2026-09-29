package com.fongmi.android.tv;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.os.HandlerCompat;

import com.fongmi.android.tv.setting.ProxySetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.NativeCompat;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PreviousProcessExitLogger;
import com.fongmi.hook.Hook;
import com.github.catvod.Init;
import com.github.catvod.crawler.DebugLogStore;
import com.google.gson.Gson;

public class App extends Application implements Application.ActivityLifecycleCallbacks {

    private static volatile App instance;

    private final Handler handler;
    private final Gson gson;
    private final long time;

    private Activity activity;
    private Hook hook;

    public App() {
        instance = this;
        gson = new Gson();
        time = System.currentTimeMillis();
        handler = HandlerCompat.createAsync(Looper.getMainLooper());
    }

    public static App get() {
        return instance;
    }

    public static Gson gson() {
        return get().gson;
    }

    public static long time() {
        return get().time;
    }

    public static Activity activity() {
        return get().activity;
    }

    public static void post(Runnable runnable) {
        get().handler.post(runnable);
    }

    public static void post(Runnable runnable, long delayMillis) {
        get().handler.removeCallbacks(runnable);
        if (delayMillis >= 0) get().handler.postDelayed(runnable, delayMillis);
    }

    public static void removeCallbacks(Runnable runnable) {
        get().handler.removeCallbacks(runnable);
    }

    public static void removeCallbacks(Runnable... runnable) {
        for (Runnable r : runnable) get().handler.removeCallbacks(r);
    }

    public void setHook(Hook hook) {
        this.hook = hook;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Init.set(base);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Android 6 (API 23) 分支：libmpv.so 与它的 FFmpeg 栈是对着 android-24+ 编的，
        // 会引用 API 24 才有的 bionic 符号（带 LIBC_N 版本节点），且 DT_NEEDED 里的
        // libvulkan.so 在 API 23 上根本不存在。必须在任何播放器被创建之前把垫片和
        // vulkan 桩准备好，否则 MPV 会静默不可用（不崩、日志无痕）。
        // API 24 及以上是空操作。见 utils/NativeCompat.java。
        NativeCompat.ensure(this);
        Notify.createChannel();
        registerActivityLifecycleCallbacks(this);
        // 调试日志 / 壳代理（从 webhtv 移植，见 setting/Setting.java 与 setting/ProxySetting.java）。
        // 顺序有讲究：
        //   1) 先把上次的开关读回来（日志总开关落在 Prefers 里）；
        //   2) 只有开着才记环境信息、才回看上一条进程退出原因 —— 否则这段本身就不该产生任何日志；
        //   3) 壳代理规则在最后 apply，让已经存下来的配置在启动时就生效（含踢掉旧连接）。
        // PreviousProcessExitLogger 只在 API 30+ 真正查询，API 23 上是空操作。
        DebugLogStore.restoreEnabled();
        if (DebugLogStore.isEnabled()) {
            Setting.logDebugEnvironment("restore");
            PreviousProcessExitLogger.log(this);
        }
        ProxySetting.apply();
    }

    @Override
    public PackageManager getPackageManager() {
        return hook != null ? hook : getBaseContext().getPackageManager();
    }

    @Override
    public String getPackageName() {
        return hook != null ? hook.getPackageName() : getBaseContext().getPackageName();
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        if (activity != activity()) this.activity = activity;
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        if (activity == activity()) this.activity = null;
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
    }
}