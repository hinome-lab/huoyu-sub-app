package com.huoyu.livesub;

import android.net.Uri;

import java.util.Locale;

/** 全局配置键与默认值 */
public final class Prefs {

    public static final String NAME = "cfg";

    public static final String DEF_BASE = "http://123.60.104.22:6006/";
    public static final String DEF_TOKEN = "hy";

    /**
     * 可被自动识别并切换的字幕服务器白名单。
     * 匹配规则：只看「协议://主机:端口」，路径与查询串（?k=xxx）不参与比较。
     */
    public static final String[] KNOWN_SERVERS = {
            "http://106.15.35.175:6006/",
            "http://123.60.104.22:6006/",
            "http://123.60.104.22:6007/",
            "http://8.133.231.186:6006/",
    };

    public static final int DEF_FS = 17;
    public static final int MIN_FS = 11;
    public static final int MAX_FS = 40;

    public static final int DEF_LINES = 2;
    public static final int DEF_ALPHA = 175;

    public static final String KEY_BASE = "base";
    public static final String KEY_TOKEN = "token";
    public static final String KEY_FS = "fs";
    public static final String KEY_LINES = "lines";
    public static final String KEY_ALPHA = "alpha";
    public static final String KEY_JA = "ja";
    public static final String KEY_LOCKED = "locked";
    public static final String KEY_X = "x";
    public static final String KEY_Y = "y";
    /** 网址切到白名单服务器时，是否自动把字幕服务器地址跟着切过去 */
    public static final String KEY_AUTO_SWITCH = "auto_switch";
    /** 上次选择的字幕导出格式（RecordExporter.FMT_*） */
    public static final String KEY_SAVE_FMT = "save_fmt";

    private Prefs() {
    }

    // ================= 地址工具 =================

    /** 归一化服务器地址：去空白，末尾补 '/' */
    public static String normalize(String base) {
        if (base == null) return "";
        String b = base.trim();
        if (b.isEmpty()) return "";
        if (!b.endsWith("/")) b = b + "/";
        return b;
    }

    /**
     * 取 URL 的「协议://主机:端口/」归一化形式；非法或非 http(s) 返回 null。
     * 例：http://123.60.104.22:6006/?k=hy  ->  http://123.60.104.22:6006/
     */
    public static String originOf(String url) {
        if (url == null) return null;
        Uri u;
        try {
            u = Uri.parse(url.trim());
        } catch (Throwable t) {
            return null;
        }
        String scheme = u.getScheme();
        String host = u.getHost();
        if (scheme == null || host == null || host.isEmpty()) return null;
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return null;
        StringBuilder sb = new StringBuilder();
        sb.append(scheme).append("://").append(host.toLowerCase(Locale.ROOT));
        int port = u.getPort();
        if (port > 0) sb.append(':').append(port);
        sb.append('/');
        return sb.toString();
    }

    /** 若 url 命中白名单，返回该服务器的规范地址（含末尾 '/'）；否则返回 null */
    public static String matchServer(String url) {
        String o = originOf(url);
        if (o == null) return null;
        for (String s : KNOWN_SERVERS) {
            String os = originOf(s);
            if (os != null && os.equals(o)) return normalize(s);
        }
        return null;
    }
}
