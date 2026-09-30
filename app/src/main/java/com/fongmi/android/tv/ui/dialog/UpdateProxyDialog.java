package com.fongmi.android.tv.ui.dialog;

import android.content.DialogInterface;
import android.text.TextUtils;
import android.view.inputmethod.EditorInfo;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogUpdateProxyBinding;
import com.fongmi.android.tv.impl.UpdateProxyListener;
import com.fongmi.android.tv.setting.Setting;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 自定义"更新下载代理"输入框（凯哥 2026-09-30 要求）。
 *
 * 语义边界（必须说清，否则容易接错地方）：
 *  这个值**不是** HTTP/SOCKS 代理，而是**URL 前缀**，用法是直接拼在下载地址前面
 *  （例如 https://gh-proxy.org/ + https://github.com/...）。
 *  它只作用于更新用的 APK 下载（Updater.getDownloadUrl()）；
 *  更新检测拉取的 release JSON 不走它（Updater 里走 Github.getJson() 直连）。
 *  所以这里**不碰** ProxySetting / OkHttp.selector()，那是壳代理的另一套东西。
 *
 * 结构照抄 SubtitleApiDialog（同样是"一个输入框写一个设置项"），
 * 区别只在 onPositive 落盘的字段与展示的行。
 */
public class UpdateProxyDialog extends BaseAlertDialog {

    private DialogUpdateProxyBinding binding;

    public static void show(Fragment fragment) {
        new UpdateProxyDialog().show(fragment.getChildFragmentManager(), null);
    }

    public static void show(FragmentActivity activity) {
        new UpdateProxyDialog().show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogUpdateProxyBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setTitle(R.string.setting_update_proxy).setView(getBinding().getRoot()).setPositiveButton(R.string.dialog_positive, this::onPositive).setNegativeButton(R.string.dialog_negative, null);
    }

    @Override
    protected void initView() {
        String text;
        binding.text.setText(text = Setting.getUpdateProxy());
        binding.text.setSelection(TextUtils.isEmpty(text) ? 0 : text.length());
    }

    @Override
    protected void initEvent() {
        binding.text.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) onPositive(null, 0);
            return true;
        });
    }

    private void onPositive(DialogInterface dialog, int which) {
        CharSequence text = binding.text.getText();
        getListener().setUpdateProxy(text == null ? "" : text.toString().trim());
        dismiss();
    }

    private UpdateProxyListener getListener() {
        Fragment parent = getParentFragment();
        if (parent instanceof UpdateProxyListener) return (UpdateProxyListener) parent;
        return (UpdateProxyListener) requireActivity();
    }
}
