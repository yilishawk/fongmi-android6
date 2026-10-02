package com.fongmi.android.tv.utils;

import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.text.TextUtils;

import androidx.core.content.FileProvider;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.utils.Path;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.URLConnection;
import java.text.DecimalFormat;
import java.util.Enumeration;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class FileUtil {

    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    /**
     * 打开 / 安装一个文件。当前调用点**全部是 APK**：应用内更新（Updater）、
     * 服务端推送（Action）、局域网推送，所以这里按"安装"来设计。
     *
     * ⭐ 必须按 API 等级分支 —— 两个平台各只认一种，不是兼容性洁癖：
     *
     * <p>API ≥ 24：{@code file://} 会抛 FileUriExposedException ⇒ 只能用 FileProvider 的 content://。
     *
     * <p>API ≤ 23：系统安装器（AOSP android-6.0.1_r81 的
     * {@code packages/apps/PackageInstaller/AndroidManifest.xml}）intent-filter 是
     * <pre>
     *   &lt;action android:name="android.intent.action.VIEW" /&gt;
     *   &lt;action android:name="android.intent.action.INSTALL_PACKAGE" /&gt;
     *   &lt;data android:scheme="file" /&gt;
     *   &lt;data android:mimeType="application/vnd.android.package-archive" /&gt;
     * </pre>
     * <b>没有 content</b> ⇒ 传 content:// 时**没有任何组件匹配** ⇒ ActivityNotFoundException。
     * （2026-10-02 电视端 Android 6.0.1 实测崩溃。）而且即使匹配上了，那个 Activity 也会
     * {@code Log.w("Unsupported scheme content")} → INSTALL_FAILED_INVALID_URI → finish
     * —— 它在 Android 6 上**根本不支持 content://**，不是我们哪里配错了。
     * （android-7.1.2_r36 的同一文件才加上 {@code <data android:scheme="content" />}。）
     *
     * <p>⭐ 第二重（只换 scheme 还不够）：同一个 Activity 是
     * {@code new File(mPackageURI.getPath())} —— **自己按路径读文件**，它没有把 content://
     * 复制到临时文件的逻辑。所以 API ≤ 23 时文件还必须落在**安装器进程读得到**的位置，
     * 而这一点比看上去窄得多，两个"看起来能行"的地方都不行：
     * <ul>
     *   <li>应用私有目录 /data/data/&lt;pkg&gt;/cache：0700，别的 uid 读不到；</li>
     *   <li>getExternalFilesDir()/getExternalCacheDir()（在 Android/data/&lt;pkg&gt; 下）：
     *       Android 6 的 sdcard.c 对 Android/data|obb|media 做了**按包 uid 隔离**，也不行。</li>
     * </ul>
     * 只有**共享外部存储的公共目录**可以。判据与实现见 {@link #getInstallStagingDir}。
     */
    public static void openFile(File file) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            startInstall(file, getShareUri(file));
            return;
        }
        if (readableByInstaller(file)) {
            startInstall(file, Uri.fromFile(file));
            return;
        }
        // 需要搬运。上百 MB 不能在主线程拷（Updater 就是从 App.post 里调进来的）⇒ 后台搬完再回主线程发 Intent。
        Task.execute(() -> {
            File staged = stageForInstaller(file);
            File opened = staged == null ? file : staged;
            if (staged != null && !staged.equals(file)) Task.schedule(() -> Path.clear(staged), 30, TimeUnit.MINUTES);
            App.post(() -> startInstall(opened, Uri.fromFile(opened)));
        });
    }

    private static void startInstall(File file, Uri uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setDataAndType(uri, FileUtil.getMimeType(file.getName()));
        try {
            App.get().startActivity(intent);
        } catch (ActivityNotFoundException e) {
            // 以前这里直接崩，只带 ANFE —— 看不出是「没人能接这个 Intent」还是「文件路径安装器读不到」。
            // 现在转成一条能读的提示：宁可少一个功能，不要再交一份崩溃。
            e.printStackTrace();
            Notify.show(R.string.update_install_unavailable);
        }
    }

    /**
     * API ≤ 23 时**安装器进程读得到**的目录；API ≥ 24 或拿不到写权限时返回 null。
     *
     * <p>必须是**共享外部存储里的公共目录**（这里用 Download/），两个理由缺一不可：
     *
     * <p>① 不在 {@code Android/} 下。Android 6 的 sdcard.c 对 {@code Android/data|obb|media}
     * 做了按包隔离：{@code derive_permissions_locked()} 把 {@code Android/data/<pkg>} 这个节点的
     * uid 直接设成**该包自己的 uid**，{@code attr_from_stat()} 再去掉 "other" 位
     * （{@code visible_mode & ~0006/0007}），挂载参数带 {@code default_permissions}
     * ⇒ **由内核强制**。所以 getExternalFilesDir()/getExternalCacheDir() 看着像"共享存储"，
     * 实际安装器读不到 —— 这一点极易误判，实测依据是 AOSP android-6.0.1_r81 system/core/sdcard/sdcard.c。
     *
     * <p>② 在共享存储里 ⇒ 持有 READ_EXTERNAL_STORAGE 的安装器读得到（公共目录不隔离）。
     *
     * <p>代价是写它需要 WRITE_EXTERNAL_STORAGE（运行时权限）。拿不到就返回 null，
     * 调用方退回私有目录 —— 行为不比改动前更差，且日志里看得出来。
     */
    public static File getInstallStagingDir() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) return null;
        if (!Setting.hasFileAccess()) return null;
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) return null;
            if (!dir.exists() && !dir.mkdirs()) return null;
            return dir;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /** 安装器读不读得到这个文件。判据 = 在共享外部存储里 **且** 不在 Android/ 下（那里按包隔离）。 */
    private static boolean readableByInstaller(File file) {
        File external = Environment.getExternalStorageDirectory();
        if (external == null) return false;
        String path = file.getAbsolutePath();
        String root = external.getAbsolutePath();
        if (!path.startsWith(root + File.separator)) return false;
        return !path.startsWith(root + File.separator + "Android" + File.separator);
    }

    /** 把文件搬到安装器读得到的地方。失败返回 null —— 调用方退回原文件。 */
    private static File stageForInstaller(File file) {
        try {
            File dir = getInstallStagingDir();
            if (dir == null) return null;
            File target = new File(dir, file.getName());
            if (target.length() == file.length() && target.lastModified() >= file.lastModified()) return target;
            Path.clear(target);
            Path.copy(file, target);
            return target.length() == file.length() ? target : null;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static InputStream openInputStream(Uri uri) throws IOException {
        InputStream input = App.get().getContentResolver().openInputStream(uri);
        if (input == null) throw new IOException("Unable to open source file");
        return input;
    }

    public static void copyAtomically(Uri source, File target) throws IOException {
        try (InputStream input = openInputStream(source)) {
            copyAtomically(input, target);
        }
    }

    public static void copyAtomically(File source, File target) throws IOException {
        if (source.getCanonicalFile().equals(target.getCanonicalFile())) return;
        try (InputStream input = new FileInputStream(source)) {
            copyAtomically(input, target);
        }
    }

    public static void writeAtomically(byte[] data, File target) throws IOException {
        try (InputStream input = new ByteArrayInputStream(data)) {
            copyAtomically(input, target);
        }
    }

    private static void copyAtomically(InputStream input, File target) throws IOException {
        File temp = createTempFile(target);
        try {
            copyToFile(input, temp);
            Path.move(temp, target);
        } finally {
            Path.clear(temp);
        }
    }

    private static File createTempFile(File target) throws IOException {
        File parent = target.getAbsoluteFile().getParentFile();
        if (parent == null) throw new IOException("Target has no parent directory");
        if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) throw new IOException("Unable to create target directory");
        return File.createTempFile("copy-", ".tmp", parent);
    }

    private static void copyToFile(InputStream input, File target) throws IOException {
        try (FileOutputStream output = new FileOutputStream(target)) {
            transfer(input, output, Long.MAX_VALUE);
            output.flush();
            output.getFD().sync();
        }
    }

    private static void transfer(InputStream input, OutputStream output, long maxBytes) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        long totalBytes = 0L;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Canceled");
            totalBytes += count;
            if (totalBytes > maxBytes) throw new IOException("File is too large");
            output.write(buffer, 0, count);
        }
    }

    public static String getDisplayName(Uri uri) {
        return getDisplayName(uri, uri.toString());
    }

    public static String getDisplayName(Uri uri, String fallback) {
        String name = ContentResolver.SCHEME_CONTENT.equalsIgnoreCase(uri.getScheme()) ? queryDisplayName(uri) : null;
        if (!TextUtils.isEmpty(name)) return name;
        name = uri.getLastPathSegment();
        return TextUtils.isEmpty(name) ? fallback : name;
    }

    private static String queryDisplayName(Uri uri) {
        String[] projection = {OpenableColumns.DISPLAY_NAME};
        try (Cursor cursor = App.get().getContentResolver().query(uri, projection, null, null, null)) {
            return cursor == null || !cursor.moveToFirst() ? null : cursor.getString(0);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static boolean gzipCompress(byte[] data, File target) {
        File temp = null;
        try {
            temp = createTempFile(target);
            try (FileOutputStream fileOutput = new FileOutputStream(temp); GZIPOutputStream output = new GZIPOutputStream(fileOutput)) {
                output.write(data);
                output.finish();
                fileOutput.getFD().sync();
            }
            Path.move(temp, target);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        } finally {
            Path.clear(temp);
        }
    }

    public static boolean gzipDecompress(File source, File target) {
        File temp = null;
        try {
            temp = createTempFile(target);
            try (GZIPInputStream input = new GZIPInputStream(new BufferedInputStream(new FileInputStream(source))); FileOutputStream fileOutput = new FileOutputStream(temp); BufferedOutputStream output = new BufferedOutputStream(fileOutput)) {
                transfer(input, output, Long.MAX_VALUE);
                output.flush();
                fileOutput.getFD().sync();
            }
            Path.move(temp, target);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        } finally {
            Path.clear(temp);
        }
    }

    public static String readGzip(File file) {
        try (GZIPInputStream is = new GZIPInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            return Path.read(is);
        } catch (IOException e) {
            e.printStackTrace();
            return "";
        }
    }

    public static boolean zipDecompress(File target, File path) {
        return zipDecompress(target, path, Integer.MAX_VALUE, Long.MAX_VALUE);
    }

    public static boolean zipDecompress(File target, File path, int maxEntries, long maxBytes) {
        try (ZipFile zip = new ZipFile(target)) {
            Enumeration<?> entries = zip.entries();
            String root = path.getCanonicalPath() + File.separator;
            byte[] buffer = new byte[16384];
            long totalBytes = 0;
            int totalEntries = 0;
            while (entries.hasMoreElements()) {
                ZipEntry entry = (ZipEntry) entries.nextElement();
                if (++totalEntries > maxEntries) throw new IOException("Archive entry limit exceeded");
                File out = new File(path, entry.getName());
                if (!out.getCanonicalPath().startsWith(root)) continue;
                if (entry.isDirectory()) out.mkdirs();
                else try (BufferedInputStream is = new BufferedInputStream(zip.getInputStream(entry)); BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(Path.create(out)))) {
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        totalBytes += read;
                        if (totalBytes > maxBytes) throw new IOException("Archive size limit exceeded");
                        os.write(buffer, 0, read);
                    }
                }
            }
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public static void clearCache(Callback callback) {
        Task.execute(() -> {
            Path.clear(Path.cache());
            App.post(callback::success);
        });
    }

    public static void getCacheSize(Callback callback) {
        Task.execute(() -> {
            String usage = byteCountToDisplaySize(Path.size(Path.cache()));
            App.post(() -> callback.success(usage));
        });
    }

    public static Uri getShareUri(String path) {
        return getShareUri(new File(path.replace("file://", "")));
    }

    public static Uri getShareUri(File file) {
        return FileProvider.getUriForFile(App.get(), App.get().getPackageName() + ".provider", file);
    }

    private static String getMimeType(String fileName) {
        String mimeType = URLConnection.guessContentTypeFromName(fileName);
        return TextUtils.isEmpty(mimeType) ? "*/*" : mimeType;
    }

    public static String byteCountToDisplaySize(long size) {
        if (size <= 0) return ResUtil.getString(R.string.none);
        String[] units = new String[]{"bytes", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        return new DecimalFormat("#,##0.#").format(size / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }
}
