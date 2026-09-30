package com.fongmi.android.tv;

import android.text.TextUtils;
import android.view.View;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.impl.UpdateListener;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.dialog.UpdateDialog;
import com.fongmi.android.tv.utils.Download;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Github;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;

import org.json.JSONObject;

import java.io.File;

public class Updater implements Download.Callback, UpdateListener {

    private static final String BASE_APP_ID = "com.fongmi.android.tv";

    private final Download download;
    private UpdateDialog dialog;

    private Updater() {
        this.download = Download.create(getDownloadUrl(), getFile());
    }

    public static Updater create() {
        return new Updater();
    }

    private File getFile() {
        return Path.cache("update.apk");
    }

    private String getJson() {
        return Github.getJson(BuildConfig.FLAVOR);
    }

    private String getApk() {
        return Github.getApk(BuildConfig.FLAVOR + "-" + getAbi() + getCoexistSuffix());
    }

    private String getAbi() {
        return android.os.Process.is64Bit() ? "arm64_v8a" : "armeabi_v7a";
    }

    /**
     * 共存包名后缀 -> 资产文件名后缀。
     *
     * 包名后缀（applicationIdSuffix）与 CI 产出的 APK 文件名后缀同源，都来自 -PcoexistSuffix，
     * 规则也一样：去掉前导点、改成前导横线（.b6 -> -b6；见 workflow 的 APK_SUFFIX 与
     * buildSrc 的 AbiApkPackaging.apkSuffix()）。
     * 这样资产名不用把 "b6" 写死在 App 里；不带后缀的构建（applicationId 就是基础包名）得到空串。
     */
    private String getCoexistSuffix() {
        String id = BuildConfig.APPLICATION_ID;
        if (!id.startsWith(BASE_APP_ID)) return "";
        return id.substring(BASE_APP_ID.length()).replace('.', '-');
    }

    /**
     * 下载用的最终 URL：在 APK 地址前套上用户配置的加速前缀。
     *
     * gh-proxy 这类服务是「URL 前缀」而不是 HTTP/SOCKS 代理：
     * https://gh-proxy.org/https://github.com/&lt;owner&gt;/&lt;repo&gt;/releases/download/...
     * 所以这里是字符串拼接，不能走 OkHttp 的 ProxySelector。
     *
     * 范围：只作用于 APK 下载，更新检测用的 release JSON 不套前缀（2026-09-30 拍板）。
     */
    private String getDownloadUrl() {
        String url = getApk();
        String proxy = normalizeProxy(Setting.getUpdateProxy());
        return TextUtils.isEmpty(proxy) ? url : proxy + url;
    }

    private String normalizeProxy(String proxy) {
        String value = proxy == null ? "" : proxy.trim();
        if (TextUtils.isEmpty(value)) return "";
        return value.endsWith("/") ? value : value + "/";
    }

    public Updater force() {
        Notify.show(R.string.update_check);
        Setting.putUpdate(true);
        return this;
    }

    public void start(FragmentActivity activity) {
        if (!Setting.getUpdate()) return;
        Task.execute(() -> doInBackground(activity));
    }

    private void doInBackground(FragmentActivity activity) {
        try {
            JSONObject object = new JSONObject(OkHttp.string(getJson()));
            String name = object.optString("name");
            String desc = object.optString("desc");
            int code = object.optInt("code");
            if (code <= BuildConfig.VERSION_CODE) return;
            App.post(() -> show(activity, name, desc));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void show(FragmentActivity activity, String version, String desc) {
        dismiss();
        dialog = UpdateDialog.create().title(ResUtil.getString(R.string.update_version, version)).desc(desc).listener(this).show(activity);
    }

    @Override
    public void onConfirm(View view) {
        view.setEnabled(false);
        download.start(this);
    }

    @Override
    public void onCancel(View view) {
        Setting.putUpdate(false);
        download.cancel();
        dismiss();
    }

    private void dismiss() {
        try {
            if (dialog != null) dialog.dismiss();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void progress(int progress) {
        if (dialog != null) dialog.setProgress(progress);
    }

    @Override
    public void error(String msg) {
        Notify.show(msg);
        dismiss();
    }

    @Override
    public void success(File file) {
        FileUtil.openFile(file);
        dismiss();
    }
}
