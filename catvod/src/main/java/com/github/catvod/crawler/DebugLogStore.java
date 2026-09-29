package com.github.catvod.crawler;

import android.text.TextUtils;

import com.github.catvod.Init;
import com.github.catvod.utils.Prefers;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DebugLogStore {

    private static final Object LOCK = new Object();
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    // Android 6 (API 23) 适配：上游 webhtv 用的是
    //     ThreadLocal.withInitial(() -> new SimpleDateFormat(...))
    // 而 ThreadLocal.withInitial(Supplier) 是 API 24 才有的平台方法，且它在
    // java.lang.ThreadLocal 上 —— desugar_jdk_libs 不遮蔽 java.lang.*，
    // 所以在 API 23 上会 NoSuchMethodError。改成匿名子类重写 initialValue()，
    // 这是 API 1 就有的写法，语义完全相同（每个线程各持一个 SimpleDateFormat）。
    private static final ThreadLocal<SimpleDateFormat> FORMAT = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
        }
    };
    // Android 6 适配：文件名从上游的 "webhtv-debug-log.txt" 改为 fongmi 前缀。
    // 只是 cache 目录下的临时日志名，无兼容性含义（两个 App 包名不同，cache 目录本就隔离）。
    private static final String FILE_NAME = "fongmi-debug-log.txt";
    private static final String PREF_ENABLED = "debug_log";
    private static final int MAX_MESSAGE_CHARS = 12000;
    private static long version;
    private static volatile boolean enabled;

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean enabled) {
        DebugLogStore.enabled = enabled;
        Prefers.put(PREF_ENABLED, enabled);
        if (enabled) add("debug", "调试日志已开启");
        else clear();
    }

    public static void restoreEnabled() {
        enabled = Prefers.getBoolean(PREF_ENABLED);
        if (!enabled) return;
        synchronized (LOCK) {
            loadLocked();
        }
        add("debug", "调试日志已恢复");
    }

    public static void add(String tag, String msg) {
        if (!isEnabled()) return;
        if (TextUtils.isEmpty(msg)) return;
        String line = FORMAT.get().format(new Date()) + " [" + Thread.currentThread().getName() + "] " + safe(tag) + ": " + limit(msg);
        synchronized (LOCK) {
            LINES.addLast(line);
            version++;
            writeLocked(line);
        }
    }

    public static String text() {
        if (!isEnabled()) return "调试日志未开启";
        List<String> copy;
        synchronized (LOCK) {
            if (LINES.isEmpty()) loadLocked();
            copy = new ArrayList<>(LINES);
        }
        if (copy.isEmpty()) return "暂无调试日志";
        StringBuilder builder = new StringBuilder();
        for (String line : copy) builder.append(line).append('\n');
        return builder.toString();
    }

    public static List<String> snapshot() {
        synchronized (LOCK) {
            return new ArrayList<>(LINES);
        }
    }

    public static int size() {
        synchronized (LOCK) {
            if (enabled && LINES.isEmpty()) loadLocked();
            return LINES.size();
        }
    }

    public static long bytes() {
        try {
            File file = file();
            return file != null && file.exists() ? file.length() : 0;
        } catch (Throwable e) {
            return 0;
        }
    }

    public static long version() {
        return version;
    }

    public static void clear() {
        synchronized (LOCK) {
            LINES.clear();
            version++;
            delete();
        }
    }

    private static String safe(String tag) {
        return TextUtils.isEmpty(tag) ? "Debug" : tag;
    }

    private static String limit(String msg) {
        if (msg.length() <= MAX_MESSAGE_CHARS) return msg;
        return msg.substring(0, MAX_MESSAGE_CHARS) + " ...(truncated " + (msg.length() - MAX_MESSAGE_CHARS) + " chars)";
    }

    private static File file() {
        try {
            return new File(Init.context().getCacheDir(), FILE_NAME);
        } catch (Throwable e) {
            return null;
        }
    }

    private static void writeLocked(String line) {
        try {
            File file = file();
            if (file == null) return;
            try (FileOutputStream stream = new FileOutputStream(file, true)) {
                stream.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void loadLocked() {
        try {
            File file = file();
            if (file == null || !file.exists()) return;
            String text = readAll(file);
            if (TextUtils.isEmpty(text)) return;
            LINES.clear();
            for (String line : text.split("\\r?\\n")) {
                if (!TextUtils.isEmpty(line)) LINES.addLast(line);
            }
        } catch (Throwable e) {
        }
    }

    private static String readAll(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void delete() {
        try {
            File file = file();
            if (file != null && file.exists()) file.delete();
        } catch (Throwable ignored) {
        }
    }
}
