package com.huoyu.livesub;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 本场字幕导出（对应网页版 💾「保存字幕文件（本场全部）」）。
 *
 * 数据来源：GET {字幕服务器}/api/record?k=<口令>&kind=lines
 * 返回结构：
 * <pre>
 * {"meta":{"start":1792599044.88,"room":22748536},
 *  "n_lines":759,"n_dms":31509,
 *  "lines":[{"id":1,"ja":"...","zh":"...","tc":1792599119.0,"t":"00:11:59","ts":1792599119.328,"el":93.328}, ...]}
 * </pre>
 * 该接口是**服务器留底**的本场全量记录（从开播到现在），不受何时打开 App 影响；
 * 换台 / 重新开播才会重置。
 *
 * 零第三方依赖：HttpURLConnection + org.json。
 */
public final class RecordExporter {

    // ================= 导出格式 =================

    public static final int FMT_TXT = 0;        // 中日对照（默认）
    public static final int FMT_ZH = 1;         // 纯中文
    public static final int FMT_ZHT = 2;        // 中文 + 时间
    public static final int FMT_SRT = 3;        // 双语 SRT
    public static final int FMT_CSV = 4;        // CSV（Excel 可直接打开）
    public static final int FMT_MD = 5;         // Markdown 表格
    public static final int FMT_JSON = 6;       // 原始 JSON

    public static final String[] FMT_LABELS = {
            "TXT · 中日对照（推荐）",
            "TXT · 纯中文",
            "TXT · 中文 + 时间",
            "SRT · 双语字幕（可外挂播放器）",
            "CSV · 表格（Excel 可开）",
            "Markdown · 表格",
            "JSON · 原始数据",
    };

    private static final String[] FMT_EXT = {".txt", ".txt", ".txt", ".srt", ".csv", ".md", ".json"};
    private static final String[] FMT_MIME = {
            "text/plain", "text/plain", "text/plain", "application/x-subrip",
            "text/csv", "text/markdown", "application/json"};

    private static final int CONNECT_TIMEOUT = 8000;
    private static final int READ_TIMEOUT = 20000;

    /** 接口路径（相对字幕服务器根） */
    private static final String API_RECORD = "api/record";

    private RecordExporter() {
    }

    /** 一次导出产物 */
    public static final class Export {
        public final String name;   // 文件名（含扩展名）
        public final String mime;
        public final String text;
        public final int count;     // 句数

        Export(String name, String mime, String text, int count) {
            this.name = name;
            this.mime = mime;
            this.text = text;
            this.count = count;
        }

        /** 内容字节数（UTF-8） */
        public int bytes() {
            try {
                return text.getBytes("UTF-8").length;
            } catch (Throwable t) {
                return text.length();
            }
        }
    }

    /** 服务端返回的本场记录 */
    public static final class Session {
        public final List<JSONObject> lines;
        public final int nDms;      // 弹幕条数（本功能不导出，仅用于提示）
        public final long room;     // 房间号（0=未知）

        Session(List<JSONObject> lines, int nDms, long room) {
            this.lines = lines;
            this.nDms = nDms;
            this.room = room;
        }
    }

    public interface Callback {
        void onSuccess(Session s);

        void onFailure(String msg);
    }

    // ================= 网络 =================

    /**
     * 后台线程拉取本场全量字幕，回调运行在**非 UI 线程**。
     *
     * @param base  当前字幕服务器地址，如 http://123.60.104.22:6006/
     * @param token 口令 k
     */
    public static void fetchAsync(final String base, final String token, final Callback cb) {
        Thread th = new Thread(() -> {
            try {
                cb.onSuccess(fetch(base, token));
            } catch (Throwable e) {
                cb.onFailure(describe(e));
            }
        }, "hy-record");
        th.setDaemon(true);
        th.start();
    }

    private static Session fetch(String base, String token) throws Exception {
        String root = Prefs.normalize(base);
        if (root.isEmpty()) throw new IllegalStateException("字幕服务器地址为空");
        String t = token == null ? "" : token;
        String urlStr = root + API_RECORD + "?k=" + URLEncoder.encode(t, "UTF-8") + "&kind=lines";

        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setRequestMethod("GET");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Cache-Control", "no-cache");
            c.setConnectTimeout(CONNECT_TIMEOUT);
            c.setReadTimeout(READ_TIMEOUT);
            c.setInstanceFollowRedirects(true);
            c.setDoInput(true);

            int code = c.getResponseCode();
            String body = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());

            if (code == 403) {
                throw new IllegalStateException(pickDetail(body, "口令不正确（网址后加 ?k=口令）"));
            }
            if (code != 200) {
                throw new IllegalStateException("服务器返回 " + code + (body.isEmpty() ? "" : "：" + brief(body)));
            }
            if (body.isEmpty() || body.charAt(0) != '{') {
                throw new IllegalStateException("返回内容不是 JSON（可能被网关/登录页拦截）");
            }

            JSONObject o = new JSONObject(body);
            JSONArray arr = o.optJSONArray("lines");
            if (arr == null) throw new IllegalStateException("返回里没有 lines 字段（服务端版本可能过旧）");

            List<JSONObject> list = new ArrayList<>(arr.length());
            Set<Integer> seen = new HashSet<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject l = arr.optJSONObject(i);
                if (l == null) continue;
                int id = l.optInt("id", 0);
                if (id > 0 && !seen.add(id)) continue;        // 同 id 只留一条
                list.add(l);
            }
            // 按 id 升序（= 播出顺序）
            Collections.sort(list, new Comparator<JSONObject>() {
                @Override
                public int compare(JSONObject a, JSONObject b) {
                    return a.optInt("id", 0) - b.optInt("id", 0);
                }
            });

            JSONObject meta = o.optJSONObject("meta");
            long room = meta == null ? 0 : meta.optLong("room", 0);
            int nDms = o.optInt("n_dms", 0);
            return new Session(list, nDms, room);
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder(8192);
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        try {
            r.close();
        } catch (Throwable ignored) {
        }
        return sb.toString().trim();
    }

    /** 从错误体里取 detail 字段，取不到就用兜底文案 */
    private static String pickDetail(String body, String fallback) {
        try {
            if (body != null && body.startsWith("{")) {
                String d = new JSONObject(body).optString("detail", "");
                if (d != null && !d.trim().isEmpty()) return d.trim();
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static String brief(String s) {
        String x = s.replace('\n', ' ').trim();
        return x.length() > 120 ? x.substring(0, 120) + "…" : x;
    }

    private static String describe(Throwable e) {
        String m = e.getMessage();
        String name = e.getClass().getSimpleName();
        if (e instanceof java.net.SocketTimeoutException) return "连接超时，请检查网络或换服务器";
        if (e instanceof java.net.UnknownHostException) return "域名解析失败，请检查网络";
        if (e instanceof java.net.ConnectException) return "无法连接服务器：" + (m == null ? "" : m);
        return (m == null || m.trim().isEmpty()) ? name : m;
    }

    // ================= 文本生成 =================

    private static String nowStr() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(new Date());
    }

    private static String p2(int n) {
        return (n < 10 ? "0" : "") + n;
    }

    private static String ms3(double x) {
        int v = (int) Math.round((x - Math.floor(x)) * 1000.0);
        if (v > 999) v = 999;
        if (v < 0) v = 0;
        String s = "00" + v;
        return s.substring(s.length() - 3);
    }

    private static boolean hasNum(JSONObject o, String key) {
        return o != null && o.has(key) && !o.isNull(key);
    }

    /** 已播时长（秒）→ mm:ss.SSS（超过 1 小时带小时）；没有 el 就退回服务器给的 t */
    static String playT(JSONObject l) {
        if (l == null) return "";
        if (hasNum(l, "el")) {
            double s = l.optDouble("el", Double.NaN);
            if (!Double.isNaN(s) && s >= 0) {
                int h = (int) (s / 3600);
                int m = (int) (s % 3600 / 60);
                int sec = (int) (s % 60);
                return (h > 0 ? (h + ":") : "") + p2(m) + ":" + p2(sec) + "." + ms3(s);
            }
        }
        return l.optString("t", "");
    }

    /** 绝对时间（本地时区）yyyy-MM-dd HH:mm:ss.SSS */
    static String absT(JSONObject l) {
        if (!hasNum(l, "ts")) return "";
        double ts = l.optDouble("ts", 0);
        if (ts <= 0) return "";
        Date d = new Date((long) (ts * 1000.0));
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(d);
    }

    private static String timeCell(JSONObject l) {
        String p = playT(l), a = absT(l);
        return a.isEmpty() ? ("[" + p + "]") : ("[" + p + " · " + a + "]");
    }

    private static String srtTC(double s) {
        if (s < 0 || Double.isNaN(s)) s = 0;
        return p2((int) (s / 3600)) + ":" + p2((int) (s % 3600 / 60)) + ":" + p2((int) (s % 60))
                + "," + ms3(s);
    }

    private static String srtFrom(JSONObject l, int fallbackSec) {
        if (hasNum(l, "el")) return srtTC(l.optDouble("el", 0));
        String t = l == null ? "" : l.optString("t", "").trim();
        if (t.matches("^\\d\\d:\\d\\d:\\d\\d$")) return t + ",000";
        return "00:00:" + p2(Math.max(0, fallbackSec)) + ",000";
    }

    private static String srtEnd(JSONObject l, JSONObject next) {
        if (hasNum(next, "el")) return srtTC(next.optDouble("el", 0));
        if (hasNum(l, "el")) return srtTC(l.optDouble("el", 0) + 5);
        String t = l == null ? "" : l.optString("t", "").trim();
        if (t.matches("^\\d\\d:\\d\\d:\\d\\d$")) {
            try {
                int sec = Integer.parseInt(t.substring(0, 2)) * 3600
                        + Integer.parseInt(t.substring(3, 5)) * 60
                        + Integer.parseInt(t.substring(6, 8)) + 5;
                return p2((sec / 3600) % 24) + ":" + p2(sec % 3600 / 60) + ":" + p2(sec % 60) + ",000";
            } catch (Throwable ignored) {
            }
        }
        return "00:00:05,000";
    }

    private static String csvq(String s) {
        return "\"" + (s == null ? "" : s.replace("\"", "\"\"")) + "\"";
    }

    private static String mdesc(String s) {
        return (s == null ? "" : s.replace("|", "\\|").replace("\n", " "));
    }

    private static String zhOf(JSONObject l) {
        return l == null ? "" : l.optString("zh", "");
    }

    private static String jaOf(JSONObject l) {
        return l == null ? "" : l.optString("ja", "");
    }

    /**
     * 生成导出文件。
     *
     * @param fmt      格式常量（FMT_*）
     * @param s        拉取到的本场记录
     * @param roomNote 服务器标识（如 123.60.104.22:6006），写进文件头；可传 null
     */
    public static Export build(int fmt, Session s, String roomNote) {
        List<JSONObject> L = s == null ? new ArrayList<JSONObject>() : s.lines;
        int n = L.size();
        String name = "火羽字幕-" + stamp();
        String ext = (fmt >= 0 && fmt < FMT_EXT.length) ? FMT_EXT[fmt] : ".txt";
        String mime = (fmt >= 0 && fmt < FMT_MIME.length) ? FMT_MIME[fmt] : "text/plain";

        String title = "火羽实时字幕 · 直播翻译记录"
                + (roomNote == null || roomNote.trim().isEmpty() ? "" : (" · " + roomNote.trim()));
        String head = title + "\n"
                + "导出：" + nowStr()
                + "　共 " + n + " 句"
                + (n > 0 && !absT(L.get(0)).isEmpty() ? ("　起录：" + absT(L.get(0))) : "")
                + (n > 0 && !absT(L.get(n - 1)).isEmpty() ? ("　末句：" + absT(L.get(n - 1))) : "");

        StringBuilder sb = new StringBuilder(Math.max(1024, n * 120));

        if (fmt == FMT_JSON) {
            JSONArray arr = new JSONArray();
            for (JSONObject l : L) arr.put(l);
            String json;
            try {
                json = arr.toString(1);      // 带缩进，方便人看
            } catch (Throwable t) {
                json = arr.toString();
            }
            sb.append(json).append('\n');
            return new Export(name + ext, mime, sb.toString(), n);
        }

        if (fmt == FMT_CSV) {
            sb.append("\uFEFF");
            sb.append("已播,时间,中文,日语\r\n");
            for (JSONObject l : L) {
                sb.append(csvq(playT(l))).append(',')
                        .append(csvq(absT(l))).append(',')
                        .append(csvq(zhOf(l))).append(',')
                        .append(csvq(jaOf(l))).append("\r\n");
            }
            return new Export(name + ext, mime, sb.toString(), n);
        }

        if (fmt == FMT_MD) {
            sb.append("# ").append(title).append("\n\n");
            sb.append("_导出：").append(nowStr()).append("　共 ").append(n).append(" 句_");
            if (!absT(L.isEmpty() ? null : L.get(0)).isEmpty()) {
                sb.append("　起录：").append(absT(L.get(0)));
            }
            sb.append("\n\n| 已播 | 时间 | 中文 | 日语 |\n|---|---|---|---|\n");
            for (JSONObject l : L) {
                sb.append("| ").append(mdesc(playT(l)))
                        .append(" | ").append(mdesc(absT(l)))
                        .append(" | ").append(mdesc(zhOf(l)))
                        .append(" | ").append(mdesc(jaOf(l)))
                        .append(" |\n");
            }
            return new Export(name + ext, mime, sb.toString(), n);
        }

        if (fmt == FMT_SRT) {
            for (int i = 0; i < n; i++) {
                JSONObject l = L.get(i);
                JSONObject next = i + 1 < n ? L.get(i + 1) : null;
                sb.append(i + 1).append('\n')
                        .append(srtFrom(l, i + 1)).append(" --> ").append(srtEnd(l, next)).append('\n')
                        .append(zhOf(l)).append('\n')
                        .append(jaOf(l)).append("\n\n");
            }
            return new Export(name + ext, mime, sb.toString(), n);
        }

        if (fmt == FMT_ZH) {
            for (JSONObject l : L) sb.append(zhOf(l)).append('\n');
            return new Export(name + ext, mime, sb.toString(), n);
        }

        if (fmt == FMT_ZHT) {
            sb.append(head).append("\n\n");
            for (JSONObject l : L) {
                sb.append('[').append(playT(l)).append("] ").append(zhOf(l)).append('\n');
            }
            return new Export(name + ext, mime, sb.toString(), n);
        }

        // 默认：中日对照
        sb.append(head).append("\n\n");
        for (int i = 0; i < n; i++) {
            JSONObject l = L.get(i);
            sb.append(timeCell(l)).append(' ').append(zhOf(l));
            String ja = jaOf(l);
            if (ja != null && !ja.trim().isEmpty()) sb.append('\n').append("        ").append(ja);
            if (i + 1 < n) sb.append("\n\n");
        }
        sb.append('\n');
        return new Export(name + ext, mime, sb.toString(), n);
    }
}
