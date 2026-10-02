package com.fongmi.android.tv.player.mpv.hls;

import java.util.Locale;

/**
 * HLS 分片「假图片头」剥壳的纯算法层。
 *
 * <p>照搬自 `webhtv-android6`（a6）的 `androidx.media3.mpvplayer.MpvHlsSegmentContentPolicy`，
 * 只改了可见性（package-private → public，便于本地单测）。算法逐字节一致。</p>
 *
 * <p>背景（a6 交接文档 `MPV假图片头剥壳-交接其他项目AI.md`）：源站把 TS 分片伪装成图片
 * （`BMP/PNG/JPEG 头 + 真 MPEG-TS`），而剥壳逻辑只认 PNG ⇒ 其它格式原样透传给播放器
 * ⇒ ffmpeg 的 mpegts demuxer 只在 188 的整数倍位置找同步字节 `0x47`，真 TS 从偏移 54 开始
 * ⇒ 一个都对不上 ⇒ 探测打 0 分 ⇒ `avformat_find_stream_info` 拿不到 SPS/PPS（extradata 为空）
 * ⇒ 硬解/软解**全部** init 失败 ⇒ `vid=no` ⇒ 只有声音没画面。</p>
 *
 * <p>本类只做「算偏移」，不碰 IO。找不到壳一律返回 -1（原样透传），不制造新故障面。</p>
 */
public final class MpvHlsSegmentContentPolicy {

    private static final byte[] PNG_SIGNATURE = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] PNG_IEND = new byte[]{0x49, 0x45, 0x4E, 0x44, (byte) 0xAE, 0x42, 0x60, (byte) 0x82};
    public static final int TS_PACKET_BYTES = 188;

    /** 假图片头的长度上限。实测只有 54 字节（BMP），留足余量但不无限缓冲。 */
    public static final int IMAGE_PREFIX_SCAN_LIMIT = 8 * 1024;

    private MpvHlsSegmentContentPolicy() {
    }

    /**
     * 「要不要试剥壳」的入口判断。
     *
     * <p>⚠ 这是 a6 修复的**三处之一**：原来是 `if (contentType.startsWith("image/png"))`，
     * 其它一律透传 ⇒ BMP 壳的分片永远不剥。现在：PNG 一律试；**其它 `image/*` 且是媒体分片时也试**。</p>
     */
    public static boolean shouldProbeImagePrefix(String contentType, boolean mediaSegment) {
        String mime = contentType == null ? "" : contentType.trim().toLowerCase(Locale.US);
        if (mime.startsWith("image/png")) return true;
        return mediaSegment && mime.startsWith("image/");
    }

    /**
     * 通用剥壳：返回第一个「188 字节对齐的 MPEG-TS 起始偏移」。
     *
     * <p>⛔ 只在**确实找到**连续 5 个 188 对齐的 `0x47` 时才剥；偏移 0 表示本来就没壳（返回 -1）。
     * 随机数据出现这种巧合的概率约 1e-12，可以忽略。找不到就原样透传。</p>
     */
    public static int findTransportStreamOffset(byte[] data, int length) {
        int safeLength = Math.max(0, Math.min(length, data == null ? 0 : data.length));
        if (safeLength < TS_PACKET_BYTES * 5) return -1;
        int png = findPngWrappedTransportStreamOffset(data, safeLength);
        if (png > 0) return png;
        if (startsAtTransportStreamPacket(data, safeLength)) return -1;
        for (int offset = 1; offset + TS_PACKET_BYTES * 4 < safeLength; offset++) {
            if (data[offset] != 0x47) continue;
            boolean aligned = true;
            for (int k = 1; k <= 4; k++) {
                if (data[offset + TS_PACKET_BYTES * k] != 0x47) {
                    aligned = false;
                    break;
                }
            }
            if (aligned) return offset;
        }
        return -1;
    }

    /** 数据是否**从第 0 字节起**就是对齐的 TS（= 没有假图片头，不需要剥）。 */
    public static boolean startsAtTransportStreamPacket(byte[] data, int length) {
        int safeLength = Math.max(0, Math.min(length, data == null ? 0 : data.length));
        if (safeLength < TS_PACKET_BYTES * 5) return false;
        for (int k = 0; k <= 4; k++) {
            if (data[TS_PACKET_BYTES * k] != 0x47) return false;
        }
        return true;
    }

    public static int findPngWrappedTransportStreamOffset(byte[] data, int length) {
        int safeLength = Math.max(0, Math.min(length, data == null ? 0 : data.length));
        if (!startsWithPngSignature(data, safeLength)) return -1;
        int iend = indexOf(data, safeLength, PNG_IEND);
        if (iend < 0) return -1;
        int start = iend + PNG_IEND.length;
        for (int offset = start; offset + TS_PACKET_BYTES < safeLength; offset++) {
            if (data[offset] != 0x47 || data[offset + TS_PACKET_BYTES] != 0x47) continue;
            if (offset + TS_PACKET_BYTES * 2 < safeLength
                    && data[offset + TS_PACKET_BYTES * 2] != 0x47) continue;
            return offset;
        }
        return -1;
    }

    public static boolean startsWithPngSignature(byte[] data, int length) {
        int safeLength = Math.max(0, Math.min(length, data == null ? 0 : data.length));
        return startsWith(data, safeLength, PNG_SIGNATURE);
    }

    /**
     * 剥壳后要相应减掉 `Content-Length`，否则播放器会一直等永远不来的字节。
     *
     * @return 新的长度；`-1` 表示「不报长度」（让下游按 chunked 处理）
     */
    public static long strippedContentLength(long contentLength, int strippedPrefixBytes) {
        if (contentLength <= 0
                || strippedPrefixBytes <= 0
                || strippedPrefixBytes >= contentLength) return -1;
        return contentLength - strippedPrefixBytes;
    }

    /**
     * 壳类型，**仅用于日志**（验收凭据 `strip image prefix ... kind=bmp`）。
     *
     * <p>⚠ 别用这个方法的返回值去决定「要不要剥」—— 剥不剥只由
     * {@link #findTransportStreamOffset} 的 188 对齐探测决定。</p>
     */
    public static String imagePrefixKind(byte[] data, int length) {
        int safeLength = Math.max(0, Math.min(length, data == null ? 0 : data.length));
        if (safeLength >= 8 && startsWith(data, safeLength, PNG_SIGNATURE)) return "png";
        if (safeLength >= 2 && data[0] == 'B' && data[1] == 'M') return "bmp";
        if (safeLength >= 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8
                && (data[2] & 0xFF) == 0xFF) return "jpeg";
        return "unknown";
    }

    private static boolean startsWith(byte[] data, int length, byte[] prefix) {
        if (data == null || length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (data[i] != prefix[i]) return false;
        return true;
    }

    private static int indexOf(byte[] data, int length, byte[] needle) {
        int end = length - needle.length;
        for (int i = 0; i <= end; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) return i;
        }
        return -1;
    }
}
