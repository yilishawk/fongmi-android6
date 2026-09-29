package com.fongmi.android.tv.ui.dialog;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.utils.Notify;
import com.github.catvod.crawler.SpiderDebug;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 调试日志入口对话框。
 *
 * 与上游 webhtv 版本的差异（Android 6 分支移植说明）：
 * webhtv 用的是它自己的 LightDialog + MaterialTextView 手工拼内容，而 TV-fongmi 没有
 * LightDialog 这个类（已核对：app/src 下只有 BaseAlertDialog / BaseBottomSheetDialog）。
 * 这里改用 TV-fongmi 自身在用的 MaterialAlertDialogBuilder（见 mobile 的
 * SettingFragment.java:283），只依赖 R.string 里新增的几个字符串，不再引入 webhtv 的 UI 基类。
 * 行为保持一致：拉起本机 HTTP 服务、给出本机/局域网两个地址、可打开浏览器或复制地址。
 */
public final class DebugLogDialog {

    private DebugLogDialog() {
    }

    public static void show(Fragment fragment) {
        show(fragment.requireActivity());
    }

    public static void show(FragmentActivity activity) {
        Server.get().start();
        String localUrl = Server.get().getAddress("/debug/logs");
        String lanUrl = Server.get().getAddress(false) + "/debug/logs";
        SpiderDebug.log("debug", "logs service ready url=%s lan=%s", localUrl, lanUrl);
        String message = activity.getString(R.string.debug_log_dialog_message, lanUrl, localUrl);
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.setting_debug_log)
                .setMessage(message)
                .setPositiveButton(R.string.debug_log_open_browser, (dialog, which) -> open(activity, localUrl))
                .setNegativeButton(R.string.dialog_negative, null)
                .setNeutralButton(R.string.debug_log_copy_url, (dialog, which) -> copy(activity, lanUrl))
                .show();
    }

    private static void open(FragmentActivity activity, String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Notify.show(R.string.debug_log_no_browser);
        }
    }

    private static void copy(FragmentActivity activity, String url) {
        ClipboardManager manager = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null) return;
        manager.setPrimaryClip(ClipData.newPlainText(activity.getString(R.string.setting_debug_log), url));
        Notify.show(R.string.debug_log_url_copied);
    }
}
