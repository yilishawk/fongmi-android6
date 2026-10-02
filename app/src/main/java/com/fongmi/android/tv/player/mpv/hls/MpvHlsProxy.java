package com.fongmi.android.tv.player.mpv.hls;

import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import fi.iki.elonen.NanoHTTPD;

/**
 * MPV 用的最小 HLS 代理：**playlist 重写 + 分片剥壳**。
 *
 * <p>为什么需要它：mpv 直接向源站取分片时，App 没有任何机会检查/改写响应体，
 * 而「假图片头」bug 恰恰要在**分片响应体**上做（见 {@link MpvHlsSegmentContentPolicy}）。
 * ⇒ 必须让分片请求经过本机。</p>
 *
 * <p>设计（刻意做小）：</p>
 * <ul>
 *   <li><b>一个端点</b>：{@code /p/<sid>[?u=<base64url>]}。
 *       不带 {@code u} ⇒ 取 session 里记的入口 m3u8；带 {@code u} ⇒ 取该上游 URL。</li>
 *   <li><b>响应自判</b>：Content-Type 含 {@code mpegurl}，或 body 以 {@code #EXTM3U} 开头
 *       ⇒ 当作 playlist，重写 URI 后返回；否则当作分片，走剥壳探测。</li>
 *   <li><b>流式</b>：分片只缓冲开头 {@link MpvHlsSegmentContentPolicy#IMAGE_PREFIX_SCAN_LIMIT} 字节用于判断，
 *       其余用 {@link SequenceInputStream} 直接接上，不整片读进内存。</li>
 *   <li><b>找不到壳就原样透传</b>（含 Range），不制造新故障面。</li>
 * </ul>
 *
 * <p>⚠ 与 a6 的差异：a6 的 {@code MpvHlsProxy} 有 2440 行（多码率变体、byterange、广告解析、统计）。
 * 本类只做最小可用：<b>不支持对带 Range 的请求剥壳</b> —— 那类请求一律原样透传
 * （剥壳会打乱字节偏移，强行做会把正确性搞坏）。</p>
 *
 * <p>⚠ 回滚：删掉本类与 {@link MpvHlsSegmentContentPolicy}，并去掉
 * {@code MpvPlayerEngine.startInternal()} 里的代理挂载那一行（见该处注释）。</p>
 */
public final class MpvHlsProxy extends NanoHTTPD {

    private static final String TAG = "MpvHlsProxy";
    private static final String PATH_PREFIX = "/p/";
    private static final String PLAYLIST_MIME = "application/vnd.apple.mpegurl";
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 12000;
    private static final int PLAYLIST_MAX_BYTES = 1024 * 1024;

    private static volatile MpvHlsProxy instance;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private MpvHlsProxy() {
        super("127.0.0.1", 0);
    }

    /**
     * 建一个会话并返回可供 mpv 播放的代理 URL。
     *
     * @param url     原始 m3u8 地址
     * @param headers 原始请求头（UA / Referer / Cookie…），会透传给上游
     * @return 代理 URL；任何异常都返回 {@code null}（调用方据此走直连，绝不影响播放）
     */
    public static String proxy(String url, Map<String, String> headers) {
        if (TextUtils.isEmpty(url)) return null;
        try {
            MpvHlsProxy server = instance();
            String id = UUID.randomUUID().toString().replace("-", "");
            server.sessions.put(id, new Session(url, headers == null ? new HashMap<>() : new HashMap<>(headers)));
            String proxyUrl = server.baseUrl() + PATH_PREFIX + id;
            Log.i(TAG, "session opened id=" + id + " url=" + url);
            return proxyUrl;
        } catch (Throwable e) {
            Log.w(TAG, "proxy unavailable, fallback to direct url", e);
            return null;
        }
    }

    /** 会话结束（播放器释放时调用）。 */
    public static void release(String sessionId) {
        MpvHlsProxy server = instance;
        if (server != null && sessionId != null) server.sessions.remove(sessionId);
    }

    /** 从代理 URL 里取回 session id（给调用方做释放用）。 */
    public static String sessionIdOf(String proxyUrl) {
        if (proxyUrl == null) return null;
        int index = proxyUrl.indexOf(PATH_PREFIX);
        if (index < 0) return null;
        return proxyUrl.substring(index + PATH_PREFIX.length());
    }

    /** 这个 URL 是不是本代理发出的（防止二次挂载套娃）。 */
    public static boolean isProxyUrl(String url) {
        return url != null && url.startsWith("http://127.0.0.1:") && url.indexOf(PATH_PREFIX) > 0;
    }

    private static synchronized MpvHlsProxy instance() throws IOException {
        if (instance == null) {
            MpvHlsProxy server = new MpvHlsProxy();
            server.start(SOCKET_READ_TIMEOUT, false);
            instance = server;
            Log.i(TAG, "started on port " + server.getListeningPort());
        }
        return instance;
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + getListeningPort();
    }

    // ------------------------------------------------------------------ serve

    @Override
    public Response serve(IHTTPSession httpSession) {
        String uri = httpSession.getUri();
        if (uri == null || !uri.startsWith(PATH_PREFIX)) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found");
        }
        String id = uri.substring(PATH_PREFIX.length());
        Session session = sessions.get(id);
        if (session == null) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "expired session");
        }
        String target = decodeTarget(httpSession.getParms().get("u"));
        if (target == null) target = session.url;
        if (TextUtils.isEmpty(target)) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "no target");
        }
        try {
            return handle(id, session, target, httpSession.getHeaders());
        } catch (Throwable e) {
            Log.w(TAG, "serve failed url=" + target, e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "proxy error");
        }
    }

    private Response handle(String id, Session session, String target, Map<String, String> clientHeaders) throws IOException {
        String range = clientHeaders == null ? null : clientHeaders.get("range");
        boolean ranged = !TextUtils.isEmpty(range);
        HttpURLConnection connection = open(target, session.headers, range);
        int code = connection.getResponseCode();
        if (code >= 400) {
            Log.w(TAG, "upstream " + code + " url=" + target);
            return newFixedLengthResponse(toStatus(code), MIME_PLAINTEXT, "upstream " + code);
        }
        String contentType = connection.getContentType();
        // ⚠ 不能用 URLConnection.getContentLengthLong()：它是 **API 24** 新增
        // （api-versions.xml: java/net/URLConnection.getContentLengthLong()J since 24），
        // minSdk 23 上会 NoSuchMethodError。getHeaderFieldInt 是 API 1。
        // 分片不可能超过 2 GiB，int 足够。
        long contentLength = connection.getHeaderFieldInt("Content-Length", -1);
        InputStream stream = connection.getInputStream();

        // ---- playlist：Content-Type 优先 ----
        if (isPlaylistMime(contentType)) {
            String text = readAll(stream, PLAYLIST_MAX_BYTES);
            return playlistResponse(target, text, id);
        }

        // ---- 读开头：既用于 playlist 兜底判断，也用于剥壳探测 ----
        byte[] head = readPrefix(stream, MpvHlsSegmentContentPolicy.IMAGE_PREFIX_SCAN_LIMIT);
        String headText = new String(head, StandardCharsets.UTF_8);

        // ---- playlist 兜底：上游 Content-Type 报错时靠内容认 ----
        if (looksLikePlaylist(headText)) {
            return playlistResponse(target, headText + readAll(stream, PLAYLIST_MAX_BYTES), id);
        }

        // ---- 带 Range 的分片：原样透传（剥壳会打乱偏移） ----
        if (ranged) {
            return newChunkedResponse(toStatus(code), mimeOf(contentType),
                    new SequenceInputStream(new ByteArrayInputStream(head), stream));
        }

        // ---- 分片：探测假图片头 ----
        int stripOffset = MpvHlsSegmentContentPolicy.findTransportStreamOffset(head, head.length);
        if (stripOffset > 0 && stripOffset < head.length) {
            // ⭐ 验收日志（清单 §五）：strip image prefix offset=54 prefixBytes=54 kind=bmp
            Log.i(TAG, String.format(Locale.US,
                    "strip image prefix offset=%d prefixBytes=%d kind=%s url=%s",
                    stripOffset, head.length, MpvHlsSegmentContentPolicy.imagePrefixKind(head, head.length), target));
            long stripped = MpvHlsSegmentContentPolicy.strippedContentLength(contentLength, stripOffset);
            InputStream body = new SequenceInputStream(new ByteArrayInputStream(head, stripOffset, head.length - stripOffset), stream);
            if (stripped > 0) return newFixedLengthResponse(Response.Status.OK, mimeOf(contentType), body, stripped);
            return newChunkedResponse(Response.Status.OK, mimeOf(contentType), body);
        }

        // 没找到壳（或本来就是干净 TS）⇒ 原样透传
        InputStream body = new SequenceInputStream(new ByteArrayInputStream(head), stream);
        if (contentLength > 0) return newFixedLengthResponse(toStatus(code), mimeOf(contentType), body, contentLength);
        return newChunkedResponse(toStatus(code), mimeOf(contentType), body);
    }

    private Response playlistResponse(String playlistUrl, String text, String id) {
        String rewritten = rewritePlaylist(playlistUrl, text, id);
        byte[] bytes = rewritten.getBytes(StandardCharsets.UTF_8);
        Log.i(TAG, "playlist rewritten bytes=" + text.length() + " -> " + bytes.length + " url=" + playlistUrl);
        return newFixedLengthResponse(Response.Status.OK, PLAYLIST_MIME, new ByteArrayInputStream(bytes), bytes.length);
    }

    // ------------------------------------------------------------------ upstream

    private HttpURLConnection open(String target, Map<String, String> headers, String range) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(target).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        // ⚠ 不要 gzip：解压后长度对不上，剥壳的 Content-Length 修正会算错
        connection.setRequestProperty("Accept-Encoding", "identity");
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey();
            if (key == null || entry.getValue() == null) continue;
            // 逐请求头 / 由本方法自己管的，不能照搬
            if (key.equalsIgnoreCase("Range") || key.equalsIgnoreCase("Host")
                    || key.equalsIgnoreCase("Accept-Encoding") || key.equalsIgnoreCase("Connection")
                    || key.equalsIgnoreCase("Content-Length")) continue;
            connection.setRequestProperty(key, entry.getValue());
        }
        if (!TextUtils.isEmpty(range)) connection.setRequestProperty("Range", range);
        return connection;
    }

    private static boolean isPlaylistMime(String contentType) {
        if (contentType == null) return false;
        String mime = contentType.toLowerCase(Locale.US);
        return mime.contains("mpegurl") || mime.contains("m3u8");
    }

    private static boolean looksLikePlaylist(String text) {
        return text != null && text.startsWith("#EXTM3U");
    }

    // ------------------------------------------------------------------ rewrite

    private String rewritePlaylist(String playlistUrl, String text, String id) {
        if (!looksLikePlaylist(text)) return text;
        StringBuilder out = new StringBuilder(text.length() + 256);
        for (String line : text.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                out.append(line).append('\n');
            } else if (trimmed.startsWith("#")) {
                out.append(rewriteTag(playlistUrl, line, id)).append('\n');
            } else {
                out.append(itemUrl(id, resolve(playlistUrl, trimmed))).append('\n');
            }
        }
        return out.toString();
    }

    /** 处理标签里的 {@code URI="..."}（EXT-X-KEY / EXT-X-MAP / EXT-X-MEDIA / EXT-X-PART…）。 */
    private String rewriteTag(String playlistUrl, String line, String id) {
        int index = line.indexOf("URI=\"");
        if (index < 0) return line;
        int start = index + 5;
        int end = line.indexOf('"', start);
        if (end < 0) return line;
        String resolved = resolve(playlistUrl, line.substring(start, end));
        return line.substring(0, start) + itemUrl(id, resolved) + line.substring(end);
    }

    private String itemUrl(String id, String url) {
        String encoded = Base64.encodeToString(url.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        return baseUrl() + PATH_PREFIX + id + "?u=" + encoded;
    }

    private static String decodeTarget(String encoded) {
        if (TextUtils.isEmpty(encoded)) return null;
        try {
            byte[] raw = Base64.decode(encoded, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            return new String(raw, StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return null;
        }
    }

    private static String resolve(String base, String uri) {
        if (TextUtils.isEmpty(uri)) return uri;
        String lower = uri.toLowerCase(Locale.US);
        if (lower.startsWith("http://") || lower.startsWith("https://")) return uri;
        try {
            return URI.create(base).resolve(uri).toString();
        } catch (Throwable e) {
            return uri;
        }
    }

    // ------------------------------------------------------------------ io

    private static byte[] readPrefix(InputStream stream, int limit) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] chunk = new byte[4096];
        while (buffer.size() < limit) {
            int want = Math.min(chunk.length, limit - buffer.size());
            int read = stream.read(chunk, 0, want);
            if (read <= 0) break;
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static String readAll(InputStream stream, int limit) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(8192);
        byte[] chunk = new byte[4096];
        while (buffer.size() < limit) {
            int read = stream.read(chunk, 0, Math.min(chunk.length, limit - buffer.size()));
            if (read <= 0) break;
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String mimeOf(String contentType) {
        if (TextUtils.isEmpty(contentType)) return "application/octet-stream";
        int index = contentType.indexOf(';');
        return index < 0 ? contentType : contentType.substring(0, index).trim();
    }

    private static Response.Status toStatus(int code) {
        Response.Status status = Response.Status.lookup(code);
        return status == null ? Response.Status.OK : status;
    }

    private static final class Session {

        final String url;
        final Map<String, String> headers;

        Session(String url, Map<String, String> headers) {
            this.url = url;
            this.headers = headers;
        }
    }
}
