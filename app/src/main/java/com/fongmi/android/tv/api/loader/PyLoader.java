package com.fongmi.android.tv.api.loader;

import android.util.Log;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;

import java.util.Map;

/**
 * Android 6 (API 23) 分支的 Python 加载器桩。
 *
 * 上游用 Chaquopy 执行 .py 爬虫源，但存在不可兼得的硬约束：
 *   - Chaquopy 17 硬要求 minSdk >= 24；
 *   - 支持 API 21 的 Chaquopy 15 在 Gradle 9 上无法加载
 *     （org.gradle.util.VersionNumber 已被 Gradle 9 移除，实测 NoClassDefFoundError）。
 * 本分支选择 API 23，故移除 Python 引擎。
 *
 * 这里保留与上游完全一致的公开方法签名，使 BaseLoader 无需改动；
 * 所有 .py 源一律返回 SpiderNull（与上游"加载失败"的既有行为一致）。
 * .js（quickjs）、jar（DexClassLoader）、csp 源不受影响。
 */
public class PyLoader {

    private static final String TAG = "PyLoader";

    public PyLoader() {
    }

    public void clear() {
    }

    public void setRecent(String recent) {
    }

    public Spider getSpider(String key, String api, String ext) {
        Log.w(TAG, "Python source is not supported in this API 23 build: " + api);
        return new SpiderNull();
    }

    public Object[] proxy(Map<String, String> params) {
        return null;
    }
}
