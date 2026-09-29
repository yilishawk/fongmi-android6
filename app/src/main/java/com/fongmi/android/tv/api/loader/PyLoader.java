package com.fongmi.android.tv.api.loader;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.chaquo.Loader;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Python 加载器 —— 与上游 webhtv 一致（本分支原先的 SpiderNull 桩已移除）。
 *
 * 上游用 Chaquopy 执行 .py 爬虫源。本分支跑在 API 23 上，而 Chaquopy 17 硬要求 minSdk >= 24，
 * 唯一支持 API 21 的 15.0.1 又无法在 Gradle 9 上加载，所以**不再用 Chaquopy 插件**，
 * 改成把运行时资产预先构建好放进 :chaquo 模块（详见 settings.gradle / chaquo/build.gradle）。
 * 资产是 Chaquopy 15.0.1 的，但 Android 侧的调用面与上游完全相同，故本类与上游逐行一致。
 */
public class PyLoader {

    private final ConcurrentHashMap<String, Spider> spiders;
    private final Loader loader;
    private volatile String recent;

    public PyLoader() {
        spiders = new ConcurrentHashMap<>();
        loader = new Loader();
    }

    public void clear() {
        spiders.values().forEach(Spider::destroy);
        spiders.clear();
        recent = null;
    }

    public void setRecent(String recent) {
        this.recent = recent;
    }

    public Spider getSpider(String key, String api, String ext) {
        return spiders.computeIfAbsent(key, k -> {
            try {
                Spider spider = loader.spider(api);
                spider.siteKey = key;
                spider.init(App.get(), normalizeExt(ext));
                return spider;
            } catch (Throwable e) {
                e.printStackTrace();
                return new SpiderNull();
            }
        });
    }

    private String normalizeExt(String ext) {
        String value = TextUtils.isEmpty(ext) ? "" : ext.trim();
        // Many live Python spiders treat ext as an option object and call .get().
        // TV-style configs commonly express an empty extension as [], which would
        // otherwise deserialize to a list and crash those spiders during init.
        return "[]".equals(value) ? "{}" : ext;
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        if (recent == null) return null;
        Spider spider = spiders.get(recent);
        return spider != null ? spider.proxy(params) : null;
    }
}
