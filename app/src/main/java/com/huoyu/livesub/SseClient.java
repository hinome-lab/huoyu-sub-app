package com.huoyu.livesub;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * 极简 SSE（Server-Sent Events）客户端。
 * 订阅 字幕服务器的 /events?k=xxx，逐行解析 data: 负载并回调。
 * 自带断线重连 + 指数退避，不依赖任何第三方库（HttpURLConnection + org.json）。
 */
public class SseClient {

    public interface Listener {
        /** 每收到一条 SSE 事件时回调（后台线程） */
        void onEvent(JSONObject data);

        /** 连接状态变化（后台线程） */
        void onConn(boolean connected, String msg);
    }

    private final String baseUrl;
    private final String token;
    private final Listener listener;

    private volatile boolean running = false;
    private volatile HttpURLConnection conn = null;
    private Thread thread;

    public SseClient(String baseUrl, String token, Listener listener) {
        this.baseUrl = baseUrl;
        this.token = token;
        this.listener = listener;
    }

    public void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "hy-sse");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        HttpURLConnection c = conn;
        if (c != null) {
            try {
                c.disconnect();
            } catch (Throwable ignored) {
            }
        }
        if (thread != null) {
            try {
                thread.interrupt();
            } catch (Throwable ignored) {
            }
            thread = null;
        }
    }

    private String eventsUrl() throws Exception {
        String b = baseUrl == null ? "" : baseUrl.trim();
        if (b.isEmpty()) b = "http://123.60.104.22:6006/";
        if (!b.endsWith("/")) b = b + "/";
        return b + "events?k=" + URLEncoder.encode(token == null ? "" : token, "UTF-8");
    }

    private void loop() {
        int backoff = 1000;
        while (running) {
            HttpURLConnection c = null;
            try {
                URL u = new URL(eventsUrl());
                c = (HttpURLConnection) u.openConnection();
                conn = c;
                c.setRequestMethod("GET");
                c.setRequestProperty("Accept", "text/event-stream");
                c.setRequestProperty("Cache-Control", "no-cache");
                c.setRequestProperty("Connection", "keep-alive");
                c.setConnectTimeout(15000);
                c.setReadTimeout(75000);
                c.setInstanceFollowRedirects(true);
                c.setDoInput(true);

                int code = c.getResponseCode();
                if (code != 200) throw new java.io.IOException("HTTP " + code);

                safeConn(true, "已连接");
                backoff = 1000;

                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                StringBuilder data = new StringBuilder();
                String line;
                while (running && (line = r.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (data.length() > 0) {
                            dispatch(data.toString());
                            data.setLength(0);
                        }
                        continue;
                    }
                    if (line.charAt(0) == ':') continue;            // 注释/心跳
                    if (line.startsWith("data:")) {
                        String p = line.substring(5);
                        if (p.startsWith(" ")) p = p.substring(1);
                        if (data.length() > 0) data.append('\n');
                        data.append(p);
                    }
                    // retry: / event: / id: 暂不处理
                }
                // 服务端正常结束 -> 走重连
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            } catch (Throwable t) {
                if (running) safeConn(false, "重连中…");
            } finally {
                conn = null;
                if (c != null) {
                    try {
                        c.disconnect();
                    } catch (Throwable ignored) {
                    }
                }
            }

            if (!running) break;
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                break;
            }
            backoff = Math.min(backoff * 2, 12000);
        }
    }

    private void dispatch(String payload) {
        try {
            JSONObject o = new JSONObject(payload);
            listener.onEvent(o);
        } catch (Throwable ignored) {
            // 非 JSON 负载（如服务端注释）直接忽略
        }
    }

    private void safeConn(boolean ok, String msg) {
        try {
            listener.onConn(ok, msg);
        } catch (Throwable ignored) {
        }
    }
}
