package com.huoyu.livesub;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * 版本检测：请求 {字幕服务器}/api/version（备选 /app/version），
 * 解析出最新 versionCode 等信息，交由调用方与本机已安装版本比较。
 *
 * 约定（与服务端接口规范一致）：
 *   必填 versionCode / versionName，其余字段可缺省。
 *   接口不存在、超时、返回非 JSON 一律视为失败，由调用方决定是否提示。
 * 零第三方依赖：HttpURLConnection + org.json。
 */
public class UpdateChecker {

    /** 服务端返回的版本信息 */
    public static class Info {
        public int versionCode;
        public String versionName = "";
        public int minVersionCode;
        public String downloadUrl = "";
        public boolean force;
        public String title = "";
        public String notes = "";
        public long sizeBytes = -1;
        public String sha256 = "";
        public String publishedAt = "";

        /** 展示用的版本号文案 */
        public String label() {
            if (versionName != null && !versionName.trim().isEmpty()) return versionName.trim();
            return String.valueOf(versionCode);
        }
    }

    public interface Callback {
        void onSuccess(Info info);

        void onFailure(String msg);
    }

    /** 依次尝试的接口路径（相对字幕服务器根） */
    private static final String[] PATHS = {"api/version", "app/version"};

    private static final int CONNECT_TIMEOUT = 5000;
    private static final int READ_TIMEOUT = 5000;

    private UpdateChecker() {
    }

    /**
     * 后台线程发起检测，回调可能运行在非 UI 线程。
     *
     * @param base  当前字幕服务器地址，如 http://123.60.104.22:6006/
     * @param token 口令 k（可空）
     */
    public static void check(final String base, final String token, final Callback cb) {
        final String b = Prefs.normalize(base);
        final String t = token == null ? "" : token;
        Thread th = new Thread(() -> {
            String lastErr = "接口不可用";
            for (String path : PATHS) {
                try {
                    String url = b + path + "?k=" + URLEncoder.encode(t, "UTF-8");
                    Info info = fetch(url);
                    if (info != null) {
                        cb.onSuccess(info);
                        return;
                    }
                    lastErr = "未获取到有效版本信息";
                } catch (Throwable e) {
                    String m = e.getMessage();
                    lastErr = e.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : (": " + m));
                }
            }
            cb.onFailure(lastErr);
        }, "hy-update");
        th.setDaemon(true);
        th.start();
    }

    /** 请求单个 URL；成功且含有效版本号时返回 Info，否则返回 null */
    private static Info fetch(String urlStr) throws Exception {
        HttpURLConnection c = null;
        try {
            URL u = new URL(urlStr);
            c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("GET");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Cache-Control", "no-cache");
            c.setConnectTimeout(CONNECT_TIMEOUT);
            c.setReadTimeout(READ_TIMEOUT);
            c.setInstanceFollowRedirects(true);
            c.setDoInput(true);

            int code = c.getResponseCode();
            if (code != 200) return null;

            InputStream in = c.getInputStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            try {
                r.close();
            } catch (Throwable ignored) {
            }

            String body = sb.toString().trim();
            if (body.isEmpty() || body.charAt(0) != '{') return null;   // 不是 JSON（可能被网关/登录页拦截）

            JSONObject o = new JSONObject(body);
            Info info = new Info();
            info.versionCode = o.optInt("versionCode", 0);
            info.versionName = o.optString("versionName", "");
            info.minVersionCode = o.optInt("minVersionCode", 0);
            info.downloadUrl = o.optString("downloadUrl", "");
            info.force = o.optBoolean("force", false);
            info.title = o.optString("title", "");
            info.notes = o.optString("notes", "");
            info.sizeBytes = o.optLong("sizeBytes", -1);
            info.sha256 = o.optString("sha256", "");
            info.publishedAt = o.optString("publishedAt", "");

            if (info.versionCode <= 0) return null;    // 没有有效版本号 = 接口尚未就绪
            return info;
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
