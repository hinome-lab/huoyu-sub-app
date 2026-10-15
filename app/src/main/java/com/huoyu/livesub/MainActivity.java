package com.huoyu.livesub;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import android.net.http.SslError;

/**
 * App 主体：
 *  - 全屏 WebView 加载字幕页（保留视频小窗、翻译、TTS、发弹幕等全部网页功能）
 *  - 顶部极简工具条：开关悬浮字幕 / 重载 / 设置
 *  - 悬浮字幕由 OverlayService（前台服务）提供，可浮在其它 App 之上
 */
public class MainActivity extends Activity {

    private static final int REQ_OVERLAY = 1001;
    private static final int REQ_NOTI = 1002;

    private WebView wv;
    private TextView btnOverlay, btnReload, btnCfg, status;
    private SharedPreferences sp;
    private final Handler h = new Handler();

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshOverlayBtn();
            h.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences(Prefs.NAME, MODE_PRIVATE);

        try {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            getWindow().setStatusBarColor(0xFF101014);
            getWindow().setNavigationBarColor(0xFF15151A);
        } catch (Throwable ignored) {
        }

        LinearLayout rootV = new LinearLayout(this);
        rootV.setOrientation(LinearLayout.VERTICAL);
        rootV.setBackgroundColor(0xFF0B0B0E);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF15151A);
        int pad = dp(6);
        bar.setPadding(pad, pad, pad, pad);

        btnOverlay = mkBtn("开启悬浮字幕", v -> toggleOverlay());
        btnOverlay.setTextColor(0xFFFFFFFF);
        bar.addView(btnOverlay, lpBtn());

        btnReload = mkBtn("重载", v -> {
            if (wv != null) wv.reload();
        });
        bar.addView(btnReload, lpBtn());

        btnCfg = mkBtn("设置", v -> showSettings());
        bar.addView(btnCfg, lpBtn());

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        status.setTextColor(0xFF8B8B95);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(dp(6), 0, 0, 0);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(status, slp);

        rootV.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        wv = new WebView(this);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        String ua = s.getUserAgentString();
        s.setUserAgentString(ua + " HuoYuSub/1.0");

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
                if (scheme.startsWith("http")) return false;      // http/https 全部留在应用内
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Throwable ignored) {
                }
                return true;
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // 仅对本应用配置的服务器放行证书问题，其它站点照常拦截
                String host = error.getUrl() == null ? "" : Uri.parse(error.getUrl()).getHost();
                String baseHost = Uri.parse(base()).getHost();
                if (host != null && host.equals(baseHost)) handler.proceed();
                else super.onReceivedSslError(view, handler, error);
            }
        });

        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                try {
                    request.grant(request.getResources());
                } catch (Throwable ignored) {
                }
            }
        });

        rootV.addView(wv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(rootV);

        wv.loadUrl(pageUrl());

        if (Build.VERSION.SDK_INT >= 33) {
            try {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTI);
            } catch (Throwable ignored) {
            }
        }
        refreshOverlayBtn();
    }

    // ================= 悬浮字幕开关 =================

    private void toggleOverlay() {
        if (OverlayService.RUNNING) {
            startService(new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_STOP));
            Toast.makeText(this, "悬浮字幕已关闭", Toast.LENGTH_SHORT).show();
            h.postDelayed(this::refreshOverlayBtn, 300);
            return;
        }
        if (!canOverlay()) {
            Toast.makeText(this, "请先允许「显示在其他应用上层」", Toast.LENGTH_LONG).show();
            try {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())), REQ_OVERLAY);
            } catch (Throwable t) {
                try {
                    startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION), REQ_OVERLAY);
                } catch (Throwable ignored) {
                    Toast.makeText(this, "无法打开权限页面，请在系统设置中手动授予悬浮窗权限", Toast.LENGTH_LONG).show();
                }
            }
            return;
        }
        startOverlay();
    }

    private void startOverlay() {
        Intent i = new Intent(this, OverlayService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
            else startService(i);
            Toast.makeText(this, "悬浮字幕已开启：可浮在其它应用之上", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "启动失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
        h.postDelayed(this::refreshOverlayBtn, 400);
    }

    private boolean canOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        return true;
    }

    private void refreshOverlayBtn() {
        if (btnOverlay == null) return;
        boolean on = OverlayService.RUNNING;
        btnOverlay.setText(on ? "关闭悬浮字幕" : "开启悬浮字幕");
        GradientDrawable d = new GradientDrawable();
        d.setColor(on ? 0xFF3D5AFE : 0xFF26262E);
        d.setCornerRadius(dp(7));
        d.setStroke(dp(1), on ? 0xFF3D5AFE : 0xFF3A3A44);
        btnOverlay.setBackground(d);
        if (status != null) {
            status.setText(on ? OverlayService.STATE : (canOverlay() ? "未开启" : "缺少悬浮窗权限"));
            status.setTextColor(on ? 0xFF7FE0A6 : 0xFF8B8B95);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_OVERLAY) {
            h.postDelayed(() -> {
                if (canOverlay()) startOverlay();
                else Toast.makeText(this, "仍未获得悬浮窗权限", Toast.LENGTH_LONG).show();
                refreshOverlayBtn();
            }, 400);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (wv != null) wv.onResume();
        h.removeCallbacks(ticker);
        h.post(ticker);
    }

    @Override
    protected void onPause() {
        h.removeCallbacks(ticker);
        if (wv != null) wv.onPause();
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (wv != null && wv.canGoBack()) {
            wv.goBack();
        } else {
            moveTaskToBack(true);   // 退到后台，悬浮字幕继续运行
        }
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (wv != null) {
            try {
                wv.stopLoading();
                wv.destroy();
            } catch (Throwable ignored) {
            }
            wv = null;
        }
        super.onDestroy();
    }

    // ================= 设置 =================

    private String base() {
        String b = sp.getString(Prefs.KEY_BASE, Prefs.DEF_BASE);
        if (b == null || b.trim().isEmpty()) b = Prefs.DEF_BASE;
        return b;
    }

    private String token() {
        String t = sp.getString(Prefs.KEY_TOKEN, Prefs.DEF_TOKEN);
        return t == null ? "" : t;
    }

    private String pageUrl() {
        String b = base().trim();
        if (!b.endsWith("/")) b = b + "/";
        return b + "?k=" + Uri.encode(token());
    }

    private void showSettings() {
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        box.setPadding(p, p, p, p);
        sv.addView(box);

        EditText eBase = addField(box, "字幕服务器地址", base(), InputType.TYPE_TEXT_VARIATION_URI);
        EditText eToken = addField(box, "密钥 k（直播间标识）", token(), InputType.TYPE_CLASS_TEXT);

        EditText eFs = addField(box, "字幕字号 " + Prefs.MIN_FS + "-" + Prefs.MAX_FS,
                String.valueOf(sp.getInt(Prefs.KEY_FS, Prefs.DEF_FS)), InputType.TYPE_CLASS_NUMBER);
        EditText eLines = addField(box, "显示行数 1-4",
                String.valueOf(sp.getInt(Prefs.KEY_LINES, Prefs.DEF_LINES)), InputType.TYPE_CLASS_NUMBER);
        EditText eAlpha = addField(box, "背景不透明度 0-255",
                String.valueOf(sp.getInt(Prefs.KEY_ALPHA, Prefs.DEF_ALPHA)), InputType.TYPE_CLASS_NUMBER);

        CheckBox cbJa = new CheckBox(this);
        cbJa.setText("显示日文原文对照");
        cbJa.setTextColor(0xFFEAEAEA);
        cbJa.setChecked(sp.getBoolean(Prefs.KEY_JA, true));
        box.addView(cbJa);

        TextView tip = new TextView(this);
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tip.setTextColor(0xFF8B8B95);
        tip.setPadding(0, dp(12), 0, 0);
        tip.setText("用法：\n"
                + "1. 点「开启悬浮字幕」并允许「显示在其他应用上层」权限。\n"
                + "2. 之后切到任意应用（含全屏播放器）都能看到火羽的实时翻译字幕。\n"
                + "3. 拖动字幕可移动；轻点一下出现控制条（字号 / 中日 / 行数 / 锁定 / 关闭）。\n"
                + "4. 锁定后字幕区域点击穿透，不会误触；再点通知栏「解锁」恢复。\n"
                + "5. 通知栏常驻按钮：锁定、仅中文、停止。");
        box.addView(tip);

        new AlertDialog.Builder(this)
                .setTitle("设置")
                .setView(sv)
                .setPositiveButton("保存", (d, w) -> {
                    SharedPreferences.Editor e = sp.edit();
                    e.putString(Prefs.KEY_BASE, safe(eBase.getText().toString(), Prefs.DEF_BASE));
                    e.putString(Prefs.KEY_TOKEN, eToken.getText().toString().trim());
                    e.putInt(Prefs.KEY_FS, clamp(intOf(eFs.getText().toString(), Prefs.DEF_FS), Prefs.MIN_FS, Prefs.MAX_FS));
                    e.putInt(Prefs.KEY_LINES, clamp(intOf(eLines.getText().toString(), Prefs.DEF_LINES), 1, 4));
                    e.putInt(Prefs.KEY_ALPHA, clamp(intOf(eAlpha.getText().toString(), Prefs.DEF_ALPHA), 0, 255));
                    e.putBoolean(Prefs.KEY_JA, cbJa.isChecked());
                    e.apply();

                    if (wv != null) wv.loadUrl(pageUrl());
                    if (OverlayService.RUNNING) {
                        try {
                            startService(new Intent(this, OverlayService.class)
                                    .setAction(OverlayService.ACTION_APPLY));
                        } catch (Throwable ignored) {
                        }
                    }
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
                    refreshOverlayBtn();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private EditText addField(LinearLayout box, String label, String value, int inputType) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTextColor(0xFF8B8B95);
        t.setPadding(0, dp(10), 0, dp(2));
        box.addView(t);

        EditText e = new EditText(this);
        e.setText(value);
        e.setInputType(inputType);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        e.setSingleLine(true);
        box.addView(e);
        return e;
    }

    private String safe(String v, String def) {
        if (v == null || v.trim().isEmpty()) return def;
        return v.trim();
    }

    private int intOf(String v, int def) {
        try {
            return Integer.parseInt(v.trim());
        } catch (Throwable ignored) {
            return def;
        }
    }

    private int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ================= 小工具 =================

    private TextView mkBtn(String text, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setTextColor(0xFFDDDDDD);
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xFF26262E);
        d.setCornerRadius(dp(7));
        d.setStroke(dp(1), 0xFF3A3A44);
        b.setBackground(d);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams lpBtn() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.rightMargin = dp(6);
        return p;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
