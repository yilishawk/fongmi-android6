package com.fongmi.android.tv;

import android.os.Build;
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
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;

import org.json.JSONObject;

import java.io.File;

public class Updater implements Download.Callback, UpdateListener {

    private static final String BASE_APP_ID = "com.fongmi.android.tv";

    // ⚠ download 不是 final：API ≤ 23 首次下载前要先拿到 WRITE_EXTERNAL_STORAGE，权限到手后
    // 下载目标会从私有 cache 变成公共 Download/（而 Download 的目标在构造时就固定了）⇒ 必须重建。
    private Download download;
    private UpdateDialog dialog;
    private FragmentActivity activity;

    private Updater() {
        this.download = Download.create(getDownloadUrl(), getFile());
    }

    public static Updater create() {
        return new Updater();
    }

    private File getFile() {
        // ⭐ API ≤ 23：安装器只认 file://，而且它拿到 URI 之后是 `new File(uri.getPath())`
        // **自己按路径读文件**（AOSP PackageInstallerActivity，详见 FileUtil.openFile 的注释）
        // ⇒ 包必须落在安装器读得到的地方。应用私有目录和 Android/data/<pkg> 都不行，
        // 只有共享外部存储的公共目录可以（判据集中在 FileUtil.getInstallStagingDir）。
        // 有写权限就直接下到那里，**一次拷贝都不需要**；没有就退回私有目录，
        // 安装前再由 FileUtil 兜底搬运（搬不动就保持旧行为，不额外制造故障面）。
        File dir = FileUtil.getInstallStagingDir();
        return dir == null ? Path.cache("update.apk") : new File(dir, "update.apk");
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
            // 2026-10-01：原来是直接 return（静默）。用户点「版本」后只看到「正在检测更新…」，
            // 之后什么都不发生，分不清是「已是最新」还是「请求失败」。这里补一句明确反馈。
            // 注意：**不要**在这里动 Setting.putUpdate —— 那是持久化的「自动检查更新」偏好
            // （只有 UpdateDialog 的 onCancel 才置 false），在此置 false 会把用户的自动检查一并关掉。
            if (code <= BuildConfig.VERSION_CODE) {
                App.post(() -> Notify.show(R.string.update_latest));
                return;
            }
            App.post(() -> show(activity, name, desc));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void show(FragmentActivity activity, String version, String desc) {
        dismiss();
        this.activity = activity;
        dialog = UpdateDialog.create().title(ResUtil.getString(R.string.update_version, version)).desc(desc).listener(this).show(activity);
    }

    @Override
    public void onConfirm(View view) {
        view.setEnabled(false);
        // ⭐ API ≤ 23：装包必须经过**共享外部存储的公共目录**（见 FileUtil.getInstallStagingDir）——
        // 应用私有目录和 Android/data/<pkg> 安装器都读不到，下完了也装不上。而写公共目录需要
        // WRITE_EXTERNAL_STORAGE。所以先要权限；要不到也继续走（退回私有目录，
        // 至少不改变"能下、能提示"的既有行为）。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N && !Setting.hasFileAccess() && activity != null && !activity.isFinishing()) {
            PermissionUtil.requestFile(activity, granted -> prepareDownload());
            return;
        }
        prepareDownload();
    }

    /** 权限到手后下载目标可能变了 ⇒ 重建 Download 再开始下（目标在构造时固定，不能复用）。 */
    private void prepareDownload() {
        download = Download.create(getDownloadUrl(), getFile());
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
