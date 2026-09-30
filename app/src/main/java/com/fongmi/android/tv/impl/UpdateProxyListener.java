package com.fongmi.android.tv.impl;

/**
 * 更新下载代理输入框的回调。
 *
 * 与 {@link com.fongmi.android.tv.impl.SubtitleListener} 同构：对话框只负责收集字符串，
 * 由承载它的界面（leanback 的 SettingActivity / mobile 的 SettingFragment）落盘并刷新行文本。
 */
public interface UpdateProxyListener {

    void setUpdateProxy(String proxy);
}
