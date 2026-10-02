package com.fongmi.android.tv.player.mpv;

import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.media3.common.Player;
import androidx.media3.common.util.Util;
import androidx.media3.mpvplayer.MpvAndroidOptions;
import androidx.media3.mpvplayer.MpvPlayer;
import androidx.media3.mpvplayer.MpvPlayerConfig;
import androidx.media3.mpvplayer.MpvSubtitleOptions;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.player.subtitle.AndroidFontConfig;
import com.fongmi.android.tv.player.subtitle.ExternalFont;
import com.fongmi.android.tv.player.track.LangUtil;
import com.fongmi.android.tv.setting.DecodeSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.PreloadSetting;
import com.fongmi.android.tv.setting.SubtitleSetting;
import com.github.catvod.utils.Path;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class MpvUtil {

    private static final List<String> FONT_OPTIONS = List.of("sub-font", "sub-fonts-dir", "sub-ass-style-overrides");
    private static final List<String> SCALE_OPTIONS = List.of("sub-scale", "sub-scale-signs");
    private static final List<String> CACHE_OPTIONS = List.of("cache", "cache-on-disk", "demuxer-cache-dir", "cache-secs");
    private static final List<String> PLAYER_OPTIONS = List.of("vo", "gpu-api", "gpu-context", "hwdec", "audio-spdif", "android-dolby-vision-output", "demuxer-dovi-profile7");
    private static final List<String> STYLE_OPTIONS = List.of("embeddedfonts", "sub-color", "sub-back-color", "sub-border-style", "sub-outline-color", "sub-outline-size", "sub-shadow-offset", "secondary-sub-ass-override");

    private static final String ASSET_CA_FILE = "cacert.pem";
    private static final int VULKAN_1_2 = 0x00402000;
    private static final double DEFAULT_SUB_POS = 100.0;
    private static final double MIN_SUB_POS = 0.0;
    private static final double MAX_SUB_POS = 150.0;

    public static boolean isAvailable() {
        try {
            return MpvPlayer.isAvailable();
        } catch (Throwable e) {
            return false;
        }
    }

    public static boolean isVulkanSupported() {
        // hasSystemFeature(String, int) 是 **API 24** 新增重载，minSdk=23 上会 NoSuchMethodError。
        // 调用点（SettingPlayerActivity:72 / SettingPlayerFragment:75 / 本类:113）都没有门禁，
        // 所以这里补门禁：Vulkan 本身也是 API 24 才有的平台特性，API 23 返回 false 即正确语义。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        return App.get().getPackageManager().hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, VULKAN_1_2);
    }

    public static MpvPlayer buildPlayer(int decode, Player.Listener listener) {
        // 把 mpv 自己的日志接进 App 调试日志（9978 的 /debug/logs）。
        // 必须在 build() 之前 —— native init 之后再注册会漏掉起播阶段的日志。
        // 幂等 + 内部吞异常，绝不影响播放；调试日志关闭时开销 ≈ 一次布尔判断。
        MpvLogBridge.install();
        MpvPlayer player = new MpvPlayer.Builder(App.get()).setDecode(decode).setConfig(buildConfig()).build();
        setPreferredTextLanguages(player);
        player.addListener(listener);
        return player;
    }

    public static void applySubtitleStyle(MpvPlayer player) {
        player.setSubtitleOptions(buildSubtitleOptions());
    }

    static List<String> getManagedOptionNames() {
        List<String> options = new ArrayList<>(PLAYER_OPTIONS);
        boolean hasFontOverride = SubtitleSetting.getFont() != null;
        boolean hasStyleOverride = SubtitleSetting.isStyleForced();
        if (PreloadSetting.isEnabled()) options.addAll(CACHE_OPTIONS);
        if (hasFontOverride) options.addAll(FONT_OPTIONS);
        if (hasStyleOverride) options.addAll(STYLE_OPTIONS);
        if (hasFontOverride || hasStyleOverride) options.add("sub-ass-override");
        if (SubtitleSetting.isPositionSet()) options.add("sub-pos");
        if (SubtitleSetting.isScaleApplied()) options.addAll(SCALE_OPTIONS);
        if (SubtitleSetting.isSecondaryPositionSet()) options.add("secondary-sub-pos");
        if (SubtitleSetting.getSecondaryMode() != SubtitleSetting.SECONDARY_MODE_DEFAULT) options.add("secondary-sid");
        return options;
    }

    private static MpvPlayerConfig buildConfig() {
        File cacheDir = Path.mpvCache();
        AndroidFontConfig.prepare();
        MpvPlayerConfig.Builder builder = new MpvPlayerConfig.Builder().addConfigDirectory(Path.mpv()).addAndroidDefaults(buildAndroidOptions(cacheDir)).addTlsCaFileFromAsset(App.get(), ASSET_CA_FILE, Path.files(ASSET_CA_FILE)).addAndroidSubtitleOptions(App.get(), buildSubtitleOptions());
        addFontOptions(builder);
        addPreloadOptions(builder);
        return builder.build();
    }

    /**
     * 自定义字体的落地通道。
     *
     * 为什么不用 MpvSubtitleOptions.Builder.setFontFamily()/setFontsDirectory()：
     * 那两个方法只存在于 FongMi/TV 5.6.3 随包发的私有 lib-mpvplayer.aar 里，公开的
     * FongMi/media@release-1.11.0-fongmi 没有（见 MpvPlayerEngine 顶部的说明）。
     *
     * 这里改走 MpvPlayerConfig 的 subtitle 字符串选项通道，选项名与
     * {@link #getManagedOptionNames()} 的 FONT_OPTIONS 声明一致（sub-font /
     * sub-fonts-dir），所以对用户 mpv.conf 的"哪些选项归 App 管"判定没有变化。
     *
     * 边界：字符串选项是 MpvPlayerConfig 的一部分，只在**构建播放器时**生效。
     * 运行中改字体需要重建播放器（原来的 setFontFamily 可以在
     * applySubtitleStyle() 里热更新，这一点是本分支的已知退化）。
     */
    @SuppressLint("RestrictedApi")
    private static void addFontOptions(MpvPlayerConfig.Builder builder) {
        String fontFamily = SubtitleSetting.getFontFamily();
        if (fontFamily == null) return;
        builder.addSubtitleStringOption("sub-font", fontFamily);
        builder.addSubtitleStringOption("sub-fonts-dir", ExternalFont.getDirectory().getAbsolutePath());
    }

    private static MpvAndroidOptions buildAndroidOptions(File shaderCacheDirectory) {
        MpvAndroidOptions.Builder builder = new MpvAndroidOptions.Builder().setShaderCacheDirectory(shaderCacheDirectory).setDolbyVisionOutputPolicy(DecodeSetting.getDolbyVisionOutputPolicy());
        // 音频直通只在「开启」时才下发，关闭时**根本不调用**。
        //
        // 为什么不能无条件下发（原来的写法）：
        //   AAR 里 setAudioPassthroughEnabled() 会同时把 audioPassthroughEnabledSet 置 true，
        //   而 options/MpvPlayerOptionDefaults.addAudioOutputOptions() 只要看到 Set=true 就会调
        //   setRequestedPassthroughCodecs(enabled ? "ac3,dts-hd,eac3,truehd" : "")。
        //   于是「关闭」写下去的是**空串**而不是 null；options/MpvOptions.applyAudioPassthrough()
        //   只对 null 短路（ifnull），空串照样会下发 `audio-spdif=`（applyPreInit + applyAppOwned 各一次）。
        //
        // 不调用 ⇒ Set=false ⇒ MpvPlayerOptionDefaults 完全不碰 requestedPassthroughCodecs，
        // 保持 Builder 默认的 null ⇒ MpvAudioCapabilities.getSupportedPassthroughCodecs(ctx, null)
        // 原样返回 null ⇒ applyAudioPassthrough 短路 ⇒ **完全不写 audio-spdif 选项**，
        // 与上游「从未设置过该开关」的语义完全等价。
        //
        // 边界：开启时的行为一字未改；关闭时对 mpv 而言同样是「无直通编码」，
        // 预期用户可见行为不变，差别只是不再下发那个空值选项。
        if (DecodeSetting.isAudioPassThrough()) builder.setAudioPassthroughEnabled(true);
        builder.setVulkanEnabled(isVulkanSupported() && PlayerSetting.isMpvVulkan());
        builder.setGpuNextEnabled(PlayerSetting.isMpvGpuNext());
        return builder.build();
    }

    private static void setPreferredTextLanguages(MpvPlayer player) {
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setPreferredTextLanguages(LangUtil.getPreferredTextLanguages()).build());
    }

    private static void addPreloadOptions(MpvPlayerConfig.Builder builder) {
        if (!PreloadSetting.isEnabled()) return;
        builder.addDiskCacheOptions(Path.mpvCache(), PreloadSetting.getTimeSeconds());
    }

    private static MpvSubtitleOptions buildSubtitleOptions() {
        MpvSubtitleOptions.Builder builder = new MpvSubtitleOptions.Builder();
        if (SubtitleSetting.isPositionSet()) builder.setPosition(getSubtitlePosition());
        if (SubtitleSetting.isScaleApplied()) builder.setScale(SubtitleSetting.getAppliedScale());
        // 原来这里还有副字幕的两行（setSecondarySubtitlePosition / setSecondaryAssStyleOverride）
        // 和字体的 setFontFamily / setFontsDirectory —— 本分支的 MPV 引擎不支持副字幕，
        // 字体改走 buildConfig() 的 addFontOptions()。详见 MpvPlayerEngine 顶部说明。
        if (SubtitleSetting.isCustomStyle()) builder.setCustomStyle(SubtitleSetting.getTextColor(), SubtitleSetting.getBackgroundColor(), SubtitleSetting.getEdgeType(), SubtitleSetting.getEdgeColor(), SubtitleSetting.getEdgeWidth(), SubtitleSetting.getShadow());
        else if (SubtitleSetting.isSystemStyle()) builder.setSystemCaptionStyle();
        return builder.build();
    }

    private static double getSubtitlePosition() {
        return Util.constrainValue(DEFAULT_SUB_POS - SubtitleSetting.getPosition(), MIN_SUB_POS, MAX_SUB_POS);
    }
}
