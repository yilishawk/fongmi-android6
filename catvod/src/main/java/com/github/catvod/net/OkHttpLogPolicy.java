package com.github.catvod.net;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

import okhttp3.Headers;
import okhttp3.HttpUrl;

public final class OkHttpLogPolicy {

    private OkHttpLogPolicy() {
    }

    public static String redactUrl(HttpUrl url) {
        if (url == null) return "<none>";
        String host = redactHost(url.host());
        int defaultPort = url.isHttps() ? 443 : 80;
        String port = portLabel(url.port(), defaultPort, isLoopback(url.host()));
        return url.scheme() + "://" + host + port + redactedPath(url.encodedPath());
    }

    public static String redactUri(URI uri) {
        if (uri == null) return "<none>";
        String scheme = uri.getScheme() == null ? "other" : uri.getScheme().toLowerCase(Locale.US);
        String host = redactHost(uri.getHost());
        int portValue = uri.getPort();
        boolean defaultPort = portValue < 0 || ("https".equals(scheme) && portValue == 443) || ("http".equals(scheme) && portValue == 80);
        String port = defaultPort ? "" : portLabel(portValue, -1, isLoopback(uri.getHost()));
        return scheme + "://" + host + port + redactedPath(uri.getRawPath());
    }

    public static String redactHost(String host) {
        // Android 6 (API 23) 分支：上游用 host.isBlank()，而 String.isBlank() 是 API 33 才有的
        // 平台方法；它在 java.lang.String 上，desugar_jdk_libs 不会遮蔽 java.lang.*，
        // 在 API 23 上会 NoSuchMethodError。用 trim().isEmpty() 等价替代。
        if (host == null || host.trim().isEmpty()) return "unknown-host";
        String normalized = host.toLowerCase(Locale.US);
        return isLoopback(normalized) ? "loopback" : "remote-" + shortHash(normalized);
    }

    public static String requestMetadata(Headers headers) {
        if (headers == null) return "";
        String range = headers.get("Range");
        // 同上：String.isBlank() 是 API 33，改成 trim().isEmpty()。
        return range == null || range.trim().isEmpty() ? "" : ", range=" + sanitizeRange(range);
    }

    public static String errorChain(Throwable error) {
        StringBuilder builder = new StringBuilder();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 8) {
            if (builder.length() > 0) builder.append(" <- ");
            builder.append(current.getClass().getSimpleName());
            current = current.getCause();
        }
        return builder.toString();
    }

    private static String sanitizeRange(String value) {
        String range = value.trim();
        return range.matches("bytes=\\d*-\\d*(,\\d*-\\d*)*") ? range : "<redacted>";
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "0:0:0:0:0:0:0:1".equals(host);
    }

    private static String portLabel(int port, int defaultPort, boolean loopback) {
        if (port < 0 || port == defaultPort) return "";
        return loopback ? ":local" : ":custom";
    }

    private static String redactedPath(String path) {
        // 同上：String.isBlank() 是 API 33，改成 trim().isEmpty()。
        if (path == null || path.trim().isEmpty()) return "/<redacted>";
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot <= slash || path.length() - dot > 10) return "/<redacted>";
        String extension = path.substring(dot).toLowerCase(Locale.US);
        return extension.matches("\\.[a-z0-9]{1,8}") ? "/<redacted>" + extension : "/<redacted>";
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(8);
            for (int i = 0; i < 4; i++) builder.append(String.format(Locale.US, "%02x", digest[i]));
            return builder.toString();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }
}
