package com.huoyu.livesub;

import android.graphics.Typeface;
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

    // ---- 悬浮字幕样式默认值 ----
    /** 中文行默认颜色（纯白） */
    public static final int DEF_FG_COLOR = 0xFFFFFFFF;
    /** 日文行默认颜色（灰白，弱化处理） */
    public static final int DEF_JA_COLOR = 0xFF9A9AA5;
    /** 中文行默认加粗（压在视频上也清晰） */
    public static final boolean DEF_BOLD = true;

    /**
     * 可选字体族（系统内置，无需打包字体文件）：
     * 显示名 → Typeface 家族名。系统不认时自动回退到默认字体。
     */
    public static final String[] FONT_LABELS = {
            "系统默认（黑体）",
            "细体",
            "中黑",
            "特黑",
            "宋体（衬线）",
            "等宽",
            "窄体",
    };

    public static final String[] FONT_FAMILIES = {
            "sans-serif",
            "sans-serif-light",
            "sans-serif-medium",
            "sans-serif-black",
            "serif",
            "monospace",
            "sans-serif-condensed",
    };

    public static final String DEF_FONT = "sans-serif";

    /** KEY_FONT 取该值表示「使用导入的自定义字体文件」 */
    public static final String FONT_CUSTOM = "__custom__";

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
    /** 悬浮字幕：中文行颜色（ARGB int） */
    public static final String KEY_FG_COLOR = "fg_color";
    /** 悬浮字幕：日文行颜色（ARGB int） */
    public static final String KEY_JA_COLOR = "ja_color";
    /** 悬浮字幕：字体家族名（见 FONT_FAMILIES），或 FONT_CUSTOM 表示自定义字体 */
    public static final String KEY_FONT = "font";
    /** 悬浮字幕：自定义字体文件路径（从手机导入后拷贝到应用私有目录） */
    public static final String KEY_FONT_PATH = "font_path";
    /** 悬浮字幕：中文行是否加粗 */
    public static final String KEY_BOLD = "bold";

    private Prefs() {
    }

    // ================= 字体 =================

    /** 字体家族名 → 可选列表下标（不认识的一律当 0） */
    public static int fontIndex(String family) {
        if (family != null) {
            for (int i = 0; i < FONT_FAMILIES.length; i++) {
                if (FONT_FAMILIES[i].equals(family)) return i;
            }
        }
        return 0;
    }

    /** 下标 → 字体家族名（越界回退默认） */
    public static String fontFamily(int index) {
        if (index < 0 || index >= FONT_FAMILIES.length) return DEF_FONT;
        return FONT_FAMILIES[index];
    }

    /** 取字体；家族名不被系统支持时回退到默认字体，绝不抛异常 */
    public static Typeface typeface(String family, boolean bold) {
        int style = bold ? Typeface.BOLD : Typeface.NORMAL;
        try {
            Typeface t = Typeface.create(family, style);
            if (t != null) return t;
        } catch (Throwable ignored) {
        }
        return bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT;
    }

    /**
     * 取字体（含自定义字体文件）。
     *
     * @param family     字体家族名；等于 {@link #FONT_CUSTOM} 时用 customPath
     * @param customPath 导入的 .ttf/.otf 绝对路径，可空
     * @param bold       是否加粗（自定义字体走合成粗体）
     */
    public static Typeface typeface(String family, String customPath, boolean bold) {
        int style = bold ? Typeface.BOLD : Typeface.NORMAL;
        if (FONT_CUSTOM.equals(family) && customPath != null && !customPath.trim().isEmpty()) {
            try {
                Typeface base = Typeface.createFromFile(customPath.trim());
                if (base != null) return Typeface.create(base, style);
            } catch (Throwable ignored) {
                // 文件损坏 / 不是字体 → 回退系统默认
            }
        }
        return typeface(family, bold);
    }

    /** 读当前配置对应的字体 */
    public static Typeface currentTypeface(android.content.SharedPreferences sp, boolean bold) {
        String fam = sp.getString(KEY_FONT, DEF_FONT);
        String path = sp.getString(KEY_FONT_PATH, "");
        return typeface(fam, path, bold);
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
