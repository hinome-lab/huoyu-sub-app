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
import android.net.Uri;
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
    /** 开启悬浮字幕：把悬浮窗位置重置回初始位置 */
    public static final String ACTION_START  = "com.huoyu.livesub.action.START";
    /** 仅重置悬浮窗位置到初始位置（不重启服务） */
    public static final String ACTION_RESET_POS = "com.huoyu.livesub.action.RESET_POS";
    public static final String ACTION_LOCK   = "com.huoyu.livesub.action.LOCK";
    public static final String ACTION_JA     = "com.huoyu.livesub.action.JA";
    public static final String ACTION_RESIZE = "com.huoyu.livesub.action.RESIZE";
    public static final String ACTION_APPLY  = "com.huoyu.livesub.action.APPLY";
    /** 只重载样式（颜色 / 字体 / 字号等）并重绘，不重连推送流 */
    public static final String ACTION_STYLE  = "com.huoyu.livesub.action.STYLE";
    /** 运行时切换字幕服务器地址（网页地址变化触发） */
    public static final String ACTION_SWITCH = "com.huoyu.livesub.action.SWITCH";
    public static final String EXTRA_BASE    = "extra_base";
    public static final String EXTRA_TOKEN   = "extra_token";

    /** 供界面查询状态 */
    public static volatile boolean RUNNING = false;
    public static volatile String STATE = "未启动";

    private static final String CH_ID = "hy_overlay";
    private static final int NID = 0x5241;
    private static final int MAXSLOT = 4;
    private static final int MAXBUF = 8;

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
    // ---- 样式（颜色 / 字体）----
    private int fgColor = Prefs.DEF_FG_COLOR;
    private int jaColor = Prefs.DEF_JA_COLOR;
    private String fontFamily = Prefs.DEF_FONT;
    private String fontPath = "";
    private boolean bold = Prefs.DEF_BOLD;
    private int px = -1, py = -1;
    private int maxWidthPx = 900;
    /** 上一次已知的屏幕尺寸，用于横竖屏切换时按比例换算位置 */
    private int lastScreenW = 0, lastScreenH = 0;

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
        if (ACTION_START.equals(a) || ACTION_RESET_POS.equals(a)) {
            // 开启悬浮字幕时，位置重置回初始位置
            resetPosition();
            return START_STICKY;
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
        if (ACTION_STYLE.equals(a)) {
            // 只改样式：不重连、不清字幕，直接重绘
            loadConfig();
            applyConfig();
            return START_STICKY;
        }
        if (ACTION_SWITCH.equals(a)) {
            switchServer(intent.getStringExtra(EXTRA_BASE), intent.getStringExtra(EXTRA_TOKEN));
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
        int newW = (int) screenW();
        int newH = (int) screenH();
        maxWidthPx = (int) (newW * 0.92) - dp(24);
        applyConfig();
        // 按比例把窗口位置映射到新的屏幕尺寸，并约束回屏幕内，避免转到屏幕外
        repositionForScreen(lastScreenW, lastScreenH, newW, newH);
        lastScreenW = newW;
        lastScreenH = newH;
    }

    // ================= 位置换算 / 边界约束 =================

    /** 把 x 约束到 [0, 屏宽-窗宽] */
    private int clampX(int x, int winW, int screenW) {
        int max = Math.max(0, screenW - winW);
        return Math.max(0, Math.min(max, x));
    }

    /** 把 y 约束到 [0, 屏高-窗高] */
    private int clampY(int y, int winH, int screenH) {
        int max = Math.max(0, screenH - winH);
        return Math.max(0, Math.min(max, y));
    }

    /**
     * 横竖屏切换后重新定位：
     * 以窗口中心为基准，把相对位置按屏幕尺寸等比换算，再整体约束进屏幕，
     * 这样窗口既保持原来的相对位置（视觉上仍在屏幕中间区域），又绝不会跑到屏幕外。
     */
    private void repositionForScreen(int oldW, int oldH, int newW, int newH) {
        if (lp == null || wm == null || card == null) return;

        int cw = card.getWidth();
        int chh = card.getHeight();
        if (cw <= 0) cw = Math.max(dp(120), maxWidthPx / 2);   // 尚未测量时的兜底
        if (chh <= 0) chh = dp(64);

        int nx = lp.x, ny = lp.y;
        if (oldW > 0 && oldH > 0 && (oldW != newW || oldH != newH)) {
            float cx = (lp.x + cw / 2f) / oldW * newW;
            float cy = (lp.y + chh / 2f) / oldH * newH;
            nx = Math.round(cx - cw / 2f);
            ny = Math.round(cy - chh / 2f);
        }

        nx = clampX(nx, cw, newW);
        ny = clampY(ny, chh, newH);

        lp.x = nx;
        lp.y = ny;
        px = nx;
        py = ny;
        try {
            wm.updateViewLayout(card, lp);
        } catch (Throwable ignored) {
        }
        saveConfig();   // 记住换算后的位置，服务被重启时也落在屏幕内
        // 方向切换会导致字幕重新折行、窗口尺寸变化，布局稳定后再兜底约束一次
        if (card != null) card.post(this::clampIntoView);
    }

    /** 窗口尺寸已知后，把它整体约束回屏幕内（处理保存的位置来自另一方向的情况） */
    private void clampIntoView() {
        if (lp == null || wm == null || card == null) return;
        int w = card.getWidth();
        int hh = card.getHeight();
        if (w <= 0 || hh <= 0) return;

        int nx = clampX(lp.x, w, (int) screenW());
        int ny = clampY(lp.y, hh, (int) screenH());
        if (nx == lp.x && ny == lp.y) return;

        lp.x = nx;
        lp.y = ny;
        px = nx;
        py = ny;
        try {
            wm.updateViewLayout(card, lp);
        } catch (Throwable ignored) {
        }
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
        fgColor = sp.getInt(Prefs.KEY_FG_COLOR, Prefs.DEF_FG_COLOR) | 0xFF000000;
        jaColor = sp.getInt(Prefs.KEY_JA_COLOR, Prefs.DEF_JA_COLOR) | 0xFF000000;
        fontFamily = sp.getString(Prefs.KEY_FONT, Prefs.DEF_FONT);
        fontPath = sp.getString(Prefs.KEY_FONT_PATH, "");
        bold = sp.getBoolean(Prefs.KEY_BOLD, Prefs.DEF_BOLD);
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

    /** 初始位置：左边距 12dp */
    private int defaultX() {
        return dp(12);
    }

    /** 初始位置：屏幕高度的 60% 处 */
    private int defaultY() {
        return (int) (screenH() * 0.60);
    }

    /**
     * 把悬浮窗位置重置回初始位置，并清掉记住的坐标，
     * 这样下次重启服务（熄屏被杀后恢复等）也从初始位置开始。
     */
    private void resetPosition() {
        px = -1;
        py = -1;
        sp.edit()
                .putInt(Prefs.KEY_X, -1)
                .putInt(Prefs.KEY_Y, -1)
                .apply();

        if (lp == null || wm == null || card == null) return;
        lp.x = defaultX();
        lp.y = defaultY();
        px = lp.x;
        py = lp.y;
        try {
            wm.updateViewLayout(card, lp);
        } catch (Throwable ignored) {
        }
    }

    private void applyConfig() {
        if (card == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(Math.max(0, Math.min(255, bgAlpha)), 0, 0, 0));
        bg.setCornerRadius(dp(10));
        card.setBackground(bg);

        int mw = Math.max(dp(80), maxWidthPx);
        // 字体 / 颜色实时生效
        Typeface tfZh = Prefs.typeface(fontFamily, fontPath, bold);
        Typeface tfJa = Prefs.typeface(fontFamily, fontPath, false);
        for (int i = 0; i < MAXSLOT; i++) {
            zhs[i].setMaxWidth(mw);
            jas[i].setMaxWidth(mw);
            try {
                zhs[i].setTypeface(tfZh);
                jas[i].setTypeface(tfJa);
            } catch (Throwable ignored) {
            }
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
            z.setTextColor(fgColor);
            z.setTypeface(Typeface.DEFAULT_BOLD);
            z.setShadowLayer(5f, 0f, 1f, Color.BLACK);
            z.setLineSpacing(0f, 1.12f);

            TextView j = new TextView(this);
            j.setTextColor(jaColor);
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

        lp.x = px < 0 ? defaultX() : px;
        lp.y = py < 0 ? defaultY() : py;
        px = lp.x;
        py = lp.y;

        wm.addView(card, lp);

        lastScreenW = (int) screenW();
        lastScreenH = (int) screenH();
        // 窗口尺寸测量完成后，把位置约束回屏幕内（防止保存的位置来自另一方向）
        card.post(this::clampIntoView);

        GradientDrawable bgo = new GradientDrawable();
        bgo.setColor(Color.argb(Math.max(0, Math.min(255, bgAlpha)), 0, 0, 0));
        bgo.setCornerRadius(dp(10));
        card.setBackground(bgo);

        int mw = Math.max(dp(80), maxWidthPx);
        Typeface tfZh = Prefs.typeface(fontFamily, fontPath, bold);
        Typeface tfJa = Prefs.typeface(fontFamily, fontPath, false);
        for (int i = 0; i < MAXSLOT; i++) {
            zhs[i].setMaxWidth(mw);
            jas[i].setMaxWidth(mw);
            try {
                zhs[i].setTypeface(tfZh);
                jas[i].setTypeface(tfJa);
            } catch (Throwable ignored) {
            }
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
                z.setTextColor(fgColor);
                j.setTextColor(jaColor);
                z.setAlpha(1f);
                j.setAlpha(1f);
            } else {
                z.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(10f, fs - 3f));
                j.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(9f, fs * 0.58f));
                z.setTextColor(fgColor);
                j.setTextColor(jaColor);
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

    /**
     * 运行时切换字幕服务器（网页地址跳到别的白名单服务器时由 MainActivity 触发）。
     * 若地址真的变了：清掉旧服务器的字幕缓存、持久化新地址、状态栏提示并立刻重连。
     */
    private void switchServer(String newBase, String newToken) {
        if (newBase == null || newBase.trim().isEmpty()) return;
        String nb = Prefs.normalize(newBase);
        String nt = (newToken == null || newToken.trim().isEmpty()) ? token : newToken.trim();

        if (nb.equals(Prefs.normalize(base)) && nt.equals(token)) return;   // 没变化

        base = nb;
        token = nt;
        sp.edit()
                .putString(Prefs.KEY_BASE, base)
                .putString(Prefs.KEY_TOKEN, token)
                .apply();

        buf.clear();                       // 旧服务器的字幕不再沿用
        STATE = "已切换服务器 · " + hostPort(base);
        render();
        updateNoti();
        try {
            Toast.makeText(this, "字幕服务器已自动切换：" + hostPort(base), Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
        restartSse();
    }

    /** http://host:port/ -> host:port */
    private String hostPort(String b) {
        try {
            Uri u = Uri.parse(b);
            String host = u.getHost();
            return u.getPort() > 0 ? (host + ":" + u.getPort()) : String.valueOf(host);
        } catch (Throwable t) {
            return b;
        }
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
