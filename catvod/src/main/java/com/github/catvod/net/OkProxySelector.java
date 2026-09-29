package com.github.catvod.net;

import com.github.catvod.bean.Proxy;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Util;

import java.io.IOException;
import java.net.Authenticator;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class OkProxySelector extends ProxySelector {

    private final List<Proxy> proxy;
    private final ProxySelector system;
    private boolean authSet;

    public OkProxySelector() {
        proxy = new CopyOnWriteArrayList<>();
        system = ProxySelector.getDefault();
        Authenticator.setDefault(new ProxyAuthenticator(this));
    }

    public synchronized void addAll(List<Proxy> items) {
        if (items.isEmpty()) return;
        items.forEach(Proxy::init);
        proxy.addAll(items);
        proxy.sort(null);
    }

    // 壳代理（从 webhtv 移植）：按规则名整体摘掉一批规则。
    // ProxySetting.apply() 每次先 remove("app") 再 addAll()，这样规则改动是幂等的，
    // 不会因为反复 apply 而把同一批规则叠加进去。
    public synchronized void remove(String name) {
        int before = proxy.size();
        proxy.removeIf(item -> item.getName().equals(name));
        int removed = before - proxy.size();
        if (removed > 0) SpiderDebug.log("proxy", "selector remove name=%s removed=%s total=%s", name, removed, proxy.size());
    }

    public synchronized void clear() {
        Authenticator.setDefault(null);
        proxy.clear();
        SpiderDebug.log("proxy", "selector clear");
    }

    public List<Proxy> getProxy() {
        return proxy;
    }

    private List<java.net.Proxy> fallback(URI uri) {
        return system != null ? system.select(uri) : List.of(java.net.Proxy.NO_PROXY);
    }

    @Override
    public List<java.net.Proxy> select(URI uri) {
        if (proxy.isEmpty() || uri.getHost() == null || "127.0.0.1".equals(uri.getHost())) return fallback(uri);
        for (Proxy item : proxy) for (String host : item.getHosts()) if (Util.containOrMatch(uri.getHost(), host)) return !item.getProxies().isEmpty() ? item.getProxies() : fallback(uri);
        return fallback(uri);
    }

    @Override
    public void connectFailed(URI uri, SocketAddress socketAddress, IOException e) {
        if (system != null) system.connectFailed(uri, socketAddress, e);
    }
}
