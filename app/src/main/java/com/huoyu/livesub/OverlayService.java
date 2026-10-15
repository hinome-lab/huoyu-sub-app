package com.huoyu.livesub;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 悬浮字幕前台服务：
 *  - 通过 SseClient 订阅字幕服务器的 /events 流（拿到「火羽」直播的实时翻译字幕）
 *  - 用 TYPE_APPLICATION_OVERLAY 悬浮窗绘制中日对照字幕，可浮在任何应用上层
 *  - 支持拖动、字号、行数、中日/仅中文、锁定穿透、通知栏快捷开关
 */
public class OverlayService extends Service {

    public static final String ACTION_STOP   = "com.huoyu.livesub.action.STOP";
    public static final String ACTION_LOCK   = "com.huoyu.livesub.action.LOCK";
    public static final String ACTION_JA     = "com.huoyu.livesub.action.JA";
    public static final String ACTION_RESIZE = "com.huoyu.livesub.action.RESIZE";
    public static final String ACTION_APPLY  = "com.huoyu.livesub.action.APPLY";

    /** 供界面查询状态 */
    public static volatile boolean RUNNING = false;
    public static volatile String STATE = "未启动";

    private static final String CH_ID = "hy_overlay";
    private static final int NID = 0x5241;
    private static final int MAXSLOT = 4;
    private static final int MAXBUF = 8;

    private static final int C_ZH = 0xFFFFFFFF;
    private static final int C_JA = 0xFF9A9AA5;
    private static final int C_HINT = 0xFF8A8A96;

    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private LinearLayout card;
    private LinearLayout panel;
    private TextView btnJa, btnLines;
    private final LinearLayout[] groups = new LinearLayout[MAXSLOT];
    private final TextView[] zhs = new TextView[MAXSLOT];
    private final TextView[] jas = new TextView[MAXSLOT];

    private final List<Line> buf = new ArrayList<>();
    private SseClient sse;
    private SharedPreferences sp;
    private final Handler h = new Handler();

    // ---- 配置 ----
    private String base = Prefs.DEF_BASE;
    private String token = Prefs.DEF_TOKEN;
    private int fs = Prefs.DEF_FS;
    private int shown = Prefs.DEF_LINES;
    private int bgAlpha = Prefs.DEF_ALPHA;
    private boolean showJa = true;
    private boolean locked = false;
    private int px = -1, py = -1;
    private int maxWidthPx = 900;

    static class Line {
        long id;
        String ja;
        String zh;
        String t;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        RUNNING = true;
        STATE = "启动中…";
        sp = getSharedPreferences(Prefs.NAME, MODE_PRIVATE);
        loadConfig();
        createChannel();
        startForegroundCompat();

        try {
            buildOverlay();
        } catch (Throwable t) {
            STATE = "悬浮窗创建失败：" + t.getMessage();
            Toast.makeText(this, "悬浮窗创建失败，请确认已授予「显示在其他应用上层」权限", Toast.LENGTH_LONG).show();
            stopSelf();
            return;
        }
        render();
        startSse();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(a)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_LOCK.equals(a)) {
            setLocked(!locked);
            return START_STICKY;
        }
        if (ACTION_JA.equals(a)) {
            showJa = !showJa;
            saveConfig();
            render();
            refreshPanel();
            updateNoti();
            return START_STICKY;
        }
        if (ACTION_RESIZE.equals(a)) {
            changeFs(2);
            return START_STICKY;
        }
        if (ACTION_APPLY.equals(a)) {
            loadConfig();
            applyConfig();
            restartSse();
            return START_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        RUNNING = false;
        STATE = "已停止";
        if (sse != null) {
            sse.stop();
            sse = null;
        }
        h.removeCallbacksAndMessages(null);
        if (wm != null && card != null) {
            try {
                wm.removeViewImmediate(card);
            } catch (Throwable ignored) {
            }
        }
        card = null;
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 旋转后重新限制宽度，避免字幕超出屏幕
        maxWidthPx = (int) (screenW() * 0.92) - dp(24);
        applyConfig();
    }

    // ================= 配置 =================

    private void loadConfig() {
        base = sp.getString(Prefs.KEY_BASE, Prefs.DEF_BASE);
        token = sp.getString(Prefs.KEY_TOKEN, Prefs.DEF_TOKEN);
        fs = sp.getInt(Prefs.KEY_FS, Prefs.DEF_FS);
        shown = sp.getInt(Prefs.KEY_LINES, Prefs.DEF_LINES);
        bgAlpha = sp.getInt(Prefs.KEY_ALPHA, Prefs.DEF_ALPHA);
        showJa = sp.getBoolean(Prefs.KEY_JA, true);
        locked = sp.getBoolean(Prefs.KEY_LOCKED, false);
        px = sp.getInt(Prefs.KEY_X, -1);
        py = sp.getInt(Prefs.KEY_Y, -1);
    }

    private void saveConfig() {
        sp.edit()
                .putInt(Prefs.KEY_FS, fs)
                .putInt(Prefs.KEY_LINES, shown)
                .putInt(Prefs.KEY_ALPHA, bgAlpha)
                .putBoolean(Prefs.KEY_JA, showJa)
                .putBoolean(Prefs.KEY_LOCKED, locked)
                .putInt(Prefs.KEY_X, px)
                .putInt(Prefs.KEY_Y, py)
                .apply();
    }

    private void applyConfig() {
        if (card == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(Math.max(0, Math.min(255, bgAlpha)), 0, 0, 0));
        bg.setCornerRadius(dp(10));
        card.setBackground(bg);

        int mw = Math.max(dp(80), maxWidthPx);
        for (int i = 0; i < MAXSLOT; i++) {
            zhs[i].setMaxWidth(mw);
            jas[i].setMaxWidth(mw);
        }
        if (lp != null) {
            lp.flags = baseFlags() | (locked ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0);
            try {
                wm.updateViewLayout(card, lp);
            } catch (Throwable ignored) {
            }
        }
        refreshPanel();
        render();
    }

    // ================= 悬浮窗 =================

    private int baseFlags() {
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
    }

    private void buildOverlay() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        maxWidthPx = (int) (screenW() * 0.92) - dp(24);

        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(7), dp(12), dp(7));

        for (int i = 0; i < MAXSLOT; i++) {
            LinearLayout g = new LinearLayout(this);
            g.setOrientation(LinearLayout.VERTICAL);

            TextView z = new TextView(this);
            z.setTextColor(C_ZH);
            z.setTypeface(Typeface.DEFAULT_BOLD);
            z.setShadowLayer(5f, 0f, 1f, Color.BLACK);
            z.setLineSpacing(0f, 1.12f);

            TextView j = new TextView(this);
            j.setTextColor(C_JA);
            j.setShadowLayer(4f, 0f, 1f, Color.BLACK);
            j.setLineSpacing(0f, 1.12f);

            g.addView(z);
            g.addView(j);
            g.setVisibility(View.GONE);

            groups[i] = g;
            zhs[i] = z;
            jas[i] = j;
            card.addView(g);
        }

        panel = buildPanel();
        card.addView(panel);

        card.setOnTouchListener(new DragTouch());

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                baseFlags() | (locked ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0),
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;

        int defX = dp(12);
        int defY = (int) (screenH() * 0.60);
        lp.x = px < 0 ? defX : px;
        lp.y = py < 0 ? defY : py;
        px = lp.x;
        py = lp.y;

        wm.addView(card, lp);

        GradientDrawable bgo = new GradientDrawable();
        bgo.setColor(Color.argb(Math.max(0, Math.min(255, bgAlpha)), 0, 0, 0));
        bgo.setCornerRadius(dp(10));
        card.setBackground(bgo);

        int mw = Math.max(dp(80), maxWidthPx);
        for (int i = 0; i < MAXSLOT; i++) {
            zhs[i].setMaxWidth(mw);
            jas[i].setMaxWidth(mw);
        }
        refreshPanel();
    }

    private LinearLayout buildPanel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setPadding(0, dp(6), 0, 0);
        p.setVisibility(View.GONE);
        p.setTag("panel");

        p.addView(mkBtn("A−", v -> changeFs(-2)));
        p.addView(mkBtn("A+", v -> changeFs(2)));
        btnJa = mkBtn("中日", v -> {
            showJa = !showJa;
            saveConfig();
            render();
            refreshPanel();
            updateNoti();
        });
        p.addView(btnJa);
        btnLines = mkBtn("2行", v -> {
            shown = shown >= 4 ? 1 : shown + 1;
            saveConfig();
            render();
            refreshPanel();
            updateNoti();
        });
        p.addView(btnLines);
        p.addView(mkBtn("锁定", v -> setLocked(true)));
        p.addView(mkBtn("✕", v -> stopSelf()));
        return p;
    }

    private TextView mkBtn(String text, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setTextColor(0xFFEEEEEE);
        b.setPadding(dp(8), dp(3), dp(8), dp(3));
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xCC26262E);
        d.setCornerRadius(dp(6));
        d.setStroke(dp(1), 0xFF3A3A44);
        b.setBackground(d);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.rightMargin = dp(5);
        b.setLayoutParams(p);
        b.setOnClickListener(l);
        return b;
    }

    private void refreshPanel() {
        if (btnJa != null) btnJa.setText(showJa ? "中日" : "仅中文");
        if (btnLines != null) btnLines.setText(shown + "行");
        if (panel != null && (locked || buf.isEmpty())) panel.setVisibility(View.GONE);
    }

    private void togglePanel() {
        if (panel == null || locked) return;
        panel.setVisibility(panel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        if (panel.getVisibility() == View.VISIBLE) h.postDelayed(() -> {
            if (panel != null) panel.setVisibility(View.GONE);
        }, 6000);
    }

    private void setLocked(boolean v) {
        locked = v;
        saveConfig();
        if (panel != null && locked) panel.setVisibility(View.GONE);
        if (lp != null && wm != null && card != null) {
            lp.flags = baseFlags() | (locked ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0);
            try {
                wm.updateViewLayout(card, lp);
            } catch (Throwable ignored) {
            }
        }
        Toast.makeText(this, locked ? "悬浮字幕已锁定：点击穿透，不会误碰" : "悬浮字幕已解锁：可拖动/调字号",
                Toast.LENGTH_SHORT).show();
        updateNoti();
    }

    private void changeFs(int delta) {
        fs = Math.max(Prefs.MIN_FS, Math.min(Prefs.MAX_FS, fs + delta));
        saveConfig();
        render();
        refreshPanel();
    }

    private class DragTouch implements View.OnTouchListener {
        private float downRawX, downRawY;
        private int startX, startY;
        private long downTime;
        private boolean moved;
        private boolean panelWasVisible;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = e.getRawX();
                    downRawY = e.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                    downTime = System.currentTimeMillis();
                    moved = false;
                    panelWasVisible = panel != null && panel.getVisibility() == View.VISIBLE;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int nx = startX + (int) (e.getRawX() - downRawX);
                    int ny = startY + (int) (e.getRawY() - downRawY);
                    if (!moved && (Math.abs(e.getRawX() - downRawX) > dp(6) || Math.abs(e.getRawY() - downRawY) > dp(6))) {
                        moved = true;
                    }
                    if (!moved) return true;
                    // 边界限制
                    int w = card.getWidth(), hh = card.getHeight();
                    int sw = (int) screenW(), sh = (int) screenH();
                    if (w > 0) {
                        nx = Math.max(-w / 3, Math.min(sw - w / 3, nx));
                    }
                    if (hh > 0) {
                        ny = Math.max(0, Math.min(sh - hh / 2, ny));
                    }
                    lp.x = nx;
                    lp.y = ny;
                    try {
                        wm.updateViewLayout(card, lp);
                    } catch (Throwable ignored) {
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (moved) {
                        px = lp.x;
                        py = lp.y;
                        saveConfig();
                    } else if (System.currentTimeMillis() - downTime < 500 && !panelWasVisible) {
                        togglePanel();
                    }
                    return true;
            }
            return false;
        }
    }

    // ================= 渲染 =================

    private void render() {
        if (card == null) return;
        int n = Math.min(shown, buf.size());

        if (n == 0) {
            groups[0].setVisibility(View.VISIBLE);
            zhs[0].setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(12, fs - 3));
            zhs[0].setTextColor(C_HINT);
            zhs[0].setText("等待字幕…");
            jas[0].setVisibility(View.GONE);
            for (int i = 1; i < MAXSLOT; i++) groups[i].setVisibility(View.GONE);
            refreshPanel();
            return;
        }

        for (int slot = 0; slot < n; slot++) {
            Line L = buf.get(buf.size() - n + slot);
            boolean newest = slot == n - 1;
            LinearLayout g = groups[slot];
            TextView z = zhs[slot];
            TextView j = jas[slot];
            g.setVisibility(View.VISIBLE);

            if (newest) {
                z.setTextSize(TypedValue.COMPLEX_UNIT_SP, fs);
                j.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(10f, fs * 0.64f));
                z.setTextColor(C_ZH);
                j.setTextColor(C_JA);
                z.setAlpha(1f);
                j.setAlpha(1f);
            } else {
                z.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(10f, fs - 3f));
                j.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(9f, fs * 0.58f));
                z.setTextColor(C_ZH);
                j.setTextColor(C_JA);
                z.setAlpha(0.62f);
                j.setAlpha(0.62f);
            }

            String zh = (L.zh == null || L.zh.trim().isEmpty()) ? "…翻译中" : L.zh;
            z.setText(zh);

            boolean showJ = showJa && L.ja != null && !L.ja.trim().isEmpty() && !L.ja.equals(L.zh);
            if (showJ) {
                j.setVisibility(View.VISIBLE);
                j.setText(L.ja);
            } else {
                j.setVisibility(View.GONE);
            }
        }
        for (int i = n; i < MAXSLOT; i++) groups[i].setVisibility(View.GONE);
        refreshPanel();
    }

    // ================= 数据 =================

    private void startSse() {
        if (sse != null) sse.stop();
        STATE = "连接中…";
        updateNoti();
        sse = new SseClient(base, token, new SseClient.Listener() {
            @Override
            public void onEvent(JSONObject data) {
                final JSONObject d = data;
                h.post(() -> handleEvent(d));
            }

            @Override
            public void onConn(boolean connected, String msg) {
                h.post(() -> {
                    STATE = msg;
                    updateNoti();
                });
            }
        });
        sse.start();
    }

    private void restartSse() {
        startSse();
    }

    private void handleEvent(JSONObject d) {
        try {
            String type = d.optString("type", "");
            if ("snapshot".equals(type)) {
                buf.clear();
                JSONArray arr = d.optJSONArray("lines");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o != null) push(parseLine(o));
                    }
                }
                render();
                updateNoti();
            } else if ("line".equals(type)) {
                JSONObject o = d.optJSONObject("line");
                if (o != null) {
                    push(parseLine(o));
                }
                render();
                updateNoti();
            } else if ("line_upd".equals(type)) {
                long id = d.optLong("id", -1);
                String zh = d.isNull("zh") ? null : d.optString("zh", null);
                for (Line L : buf) {
                    if (L.id == id) {
                        L.zh = zh;
                        break;
                    }
                }
                render();
                updateNoti();
            } else if ("reset".equals(type)) {
                buf.clear();
                render();
                updateNoti();
            } else if ("status".equals(type)) {
                boolean connected = d.optBoolean("connected", true);
                int viewers = d.optInt("viewers", -1);
                String msg = d.optString("msg", "");
                if (!connected) {
                    STATE = msg.isEmpty() ? "音频断开，重连中…" : msg;
                } else {
                    STATE = viewers >= 0 ? ("已连接 · " + viewers + " 人在看") : "已连接";
                }
                updateNoti();
            }
            // move / dm / dm_upd / guard / tts 与悬浮字幕无关，忽略
        } catch (Throwable ignored) {
        }
    }

    private void push(Line L) {
        if (L.id != 0) {
            for (int i = 0; i < buf.size(); i++) {
                if (buf.get(i).id == L.id) {
                    buf.set(i, L);
                    return;
                }
            }
        }
        buf.add(L);
        while (buf.size() > MAXBUF) buf.remove(0);
    }

    private Line parseLine(JSONObject o) {
        Line L = new Line();
        L.id = o.optLong("id", 0);
        L.ja = o.isNull("ja") ? null : o.optString("ja", null);
        L.zh = o.isNull("zh") ? null : o.optString("zh", null);
        L.t = o.isNull("t") ? null : o.optString("t", null);
        return L;
    }

    // ================= 通知 =================

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CH_ID) == null) {
                NotificationChannel ch = new NotificationChannel(CH_ID,
                        getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
                ch.setDescription(getString(R.string.channel_desc));
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    private void startForegroundCompat() {
        Notification n = buildNoti();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NID, n);
        }
    }

    private PendingIntent svc(String action, int req) {
        Intent i = new Intent(this, OverlayService.class).setAction(action);
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) f |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getService(this, req, i, f);
    }

    private Notification buildNoti() {
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) f |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent piOpen = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK), f);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CH_ID)
                : new Notification.Builder(this);

        String text = latestText();
        b.setSmallIcon(R.drawable.ic_stat_sub)
                .setContentTitle(getString(R.string.app_name) + " · " + STATE)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(Notification.PRIORITY_LOW)
                .setContentIntent(piOpen);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Icon ic = Icon.createWithResource(this, R.drawable.ic_stat_sub);
            b.addAction(new Notification.Action.Builder(ic, locked ? "解锁" : "锁定", svc(ACTION_LOCK, 11)).build());
            b.addAction(new Notification.Action.Builder(ic, showJa ? "仅中文" : "中日", svc(ACTION_JA, 12)).build());
            b.addAction(new Notification.Action.Builder(ic, "停止", svc(ACTION_STOP, 13)).build());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b.setStyle(new Notification.BigTextStyle().bigText(text));
        }
        return b.build();
    }

    private String latestText() {
        for (int i = buf.size() - 1; i >= 0; i--) {
            Line L = buf.get(i);
            if (L.zh != null && !L.zh.trim().isEmpty()) return L.zh;
            if (L.ja != null && !L.ja.trim().isEmpty()) return L.ja;
        }
        return "等待字幕…（悬浮窗可拖动，点一下出控制条）";
    }

    private void updateNoti() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && RUNNING) nm.notify(NID, buildNoti());
        } catch (Throwable ignored) {
        }
    }

    // ================= 工具 =================

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private float screenW() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    private float screenH() {
        return getResources().getDisplayMetrics().heightPixels;
    }
}
