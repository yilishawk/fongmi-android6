package com.fongmi.android.tv.utils;

public class Github {

    // 指向**本分支自己的 release**（上游是 FongMi/Release）。
    // releases/latest/download/<asset> 是 GitHub 的稳定别名：永远解析到最新一次 release 的资产，
    // 所以 App 侧不需要记录 tag，也不会因为发新版而失效。
    //
    // 资产命名（由 .github/workflows/android-release.yml 产出）：
    //   <flavor>.json                例：leanback.json / mobile.json
    //   <flavor>-<abi>-<suffix>.apk  例：leanback-arm64_v8a-b6.apk
    // 其中 <suffix> 由 -PcoexistSuffix 推导（.b6 -> -b6），Updater 按包名后缀算出。
    public static final String URL = "https://github.com/yilishawk/fongmi-android6/releases/latest/download";

    private static String getUrl(String name) {
        return URL + "/" + name;
    }

    public static String getJson(String name) {
        return getUrl(name + ".json");
    }

    public static String getApk(String name) {
        return getUrl(name + ".apk");
    }
}
