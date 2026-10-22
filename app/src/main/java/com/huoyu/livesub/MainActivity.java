package com.huoyu.livesub;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
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
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import android.net.http.SslError;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;

/**
 * App 主体：
 *  - 全屏 WebView 加载字幕页（保留视频小窗、翻译、TTS、发弹幕等全部网页功能）
 *  - 顶部极简工具条：开关悬浮字幕 / 重载 / 设置
 *  - 悬浮字幕由 OverlayService（前台服务）提供，可浮在其它 App 之上
 */
public class MainActivity extends Activity {

    private static final int REQ_OVERLAY = 1001;
    private static final int REQ_NOTI = 1002;
    private static final int REQ_WRITE = 1003;
    /** 选自定义字体文件（.ttf/.otf） */
    private static final int REQ_FONT = 1004;

    /** 自动检查更新的最小间隔（回到前台时若距上次超过该间隔才再查一次） */
    private static final long AUTO_CHECK_INTERVAL = 10 * 60 * 1000L;

    private WebView wv;
    private TextView btnOverlay, btnSave, btnReload, btnCfg, status;
    private SharedPreferences sp;
    private final Handler h = new Handler();

    /** 等存储权限期间暂存的导出格式（API < 29 才需要） */
    private int pendingSaveFmt = RecordExporter.FMT_TXT;

    /** 等用户选完自定义字体文件后要回填的界面元素 */
    private TextView pendingFontRow, pendingFontPreview;

    /** 升级弹窗是否正在显示（避免重复弹栈） */
    private boolean updateDialogShowing = false;
    /** 上次自动检查更新的时间戳 */
    private long lastAutoCheckAt = 0L;

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

        btnSave = mkBtn("存字幕", v -> showSavePicker());
        btnSave.setTextColor(0xFFC9B6FF);
        bar.addView(btnSave, lpBtn());

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
        s.setUserAgentString(ua + " HuoYuSub/1.5");

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
                if (scheme.startsWith("http")) {
                    // http(s) 全部留在应用内，同时兜底检测一次地址变化
                    watchUrl(u.toString());
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Throwable ignored) {
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                watchUrl(url);
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                // 单页应用用 history.pushState 换地址时也会走到这里
                watchUrl(url);
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
        checkUpdate(false);      // 每次打开 App 检测一次新版本
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
        // 每次开启都把悬浮字幕窗口位置重置回初始位置
        sp.edit()
                .putInt(Prefs.KEY_X, -1)
                .putInt(Prefs.KEY_Y, -1)
                .apply();

        Intent i = new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_START);
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
        if (req == REQ_FONT) {
            Uri u = (data == null) ? null : data.getData();
            if (res == RESULT_OK && u != null) {
                try {
                    String path = importFont(u);
                    sp.edit()
                            .putString(Prefs.KEY_FONT, Prefs.FONT_CUSTOM)
                            .putString(Prefs.KEY_FONT_PATH, path)
                            .apply();
                    if (pendingFontRow != null) pendingFontRow.setText(fontRowText());
                    refreshStylePreview(pendingFontPreview);
                    applyStyleNow();
                    Toast.makeText(this, "已切换为自定义字体", Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    String m = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                    Toast.makeText(this, "字体导入失败：" + m, Toast.LENGTH_LONG).show();
                }
            }
            pendingFontRow = null;
            pendingFontPreview = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (wv != null) wv.onResume();
        h.removeCallbacks(ticker);
        h.post(ticker);
        // 从后台回到前台也顺带查一次（限流，避免频繁打扰）
        if (System.currentTimeMillis() - lastAutoCheckAt > AUTO_CHECK_INTERVAL) {
            checkUpdate(false);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // launchMode=singleTask：再次点图标回到本界面时走这里，同样视为「打开 App」
        if (System.currentTimeMillis() - lastAutoCheckAt > AUTO_CHECK_INTERVAL) {
            checkUpdate(false);
        }
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

    // ================= 网址变化 → 自动切换字幕服务器 =================

    /**
     * 网页地址发生变化时调用：
     * 若新地址命中白名单（106.15.35.175:6006 / 123.60.104.22:6006 / 123.60.104.22:6007 / 8.133.231.186:6006）
     * 且与当前字幕服务器不是同一个，就把字幕服务器地址自动切换过去，
     * 并让正在运行的悬浮字幕服务立刻重新订阅新服务器。
     */
    private void watchUrl(String url) {
        if (url == null || url.trim().isEmpty()) return;
        if (!sp.getBoolean(Prefs.KEY_AUTO_SWITCH, true)) return;

        String hit = Prefs.matchServer(url);
        if (hit == null) return;                       // 不在白名单内，不动

        String curOrigin = Prefs.originOf(base());
        String newOrigin = Prefs.originOf(hit);
        if (newOrigin == null || newOrigin.equals(curOrigin)) return;   // 已经是同一个，无需切换

        String newToken = tokenOf(url);
        if (newToken == null || newToken.isEmpty()) newToken = token();

        SharedPreferences.Editor e = sp.edit();
        e.putString(Prefs.KEY_BASE, hit);
        e.putString(Prefs.KEY_TOKEN, newToken);
        e.apply();

        String shown = shortAddr(hit);
        Toast.makeText(this, "网址已变化，字幕服务器自动切换到 " + shown, Toast.LENGTH_SHORT).show();
        if (status != null) {
            status.setText("已切换：" + shown);
            status.setTextColor(0xFF7FE0A6);
        }
        notifyOverlaySwitch(hit, newToken);
    }

    /** 从页面地址里取 ?k= 的值（没带就返回 null） */
    private String tokenOf(String url) {
        try {
            String k = Uri.parse(url).getQueryParameter("k");
            return k == null ? null : k.trim();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 通知正在运行的悬浮字幕服务：换服务器并重连 */
    private void notifyOverlaySwitch(String newBase, String newToken) {
        if (!OverlayService.RUNNING) return;            // 没在跑就不用通知，下次启动自会读新配置
        try {
            startService(new Intent(this, OverlayService.class)
                    .setAction(OverlayService.ACTION_SWITCH)
                    .putExtra(OverlayService.EXTRA_BASE, newBase)
                    .putExtra(OverlayService.EXTRA_TOKEN, newToken));
        } catch (Throwable ignored) {
        }
    }

    /** http://host:port/ -> host:port，用于界面提示 */
    private String shortAddr(String b) {
        try {
            Uri u = Uri.parse(b);
            String h = u.getHost();
            return u.getPort() > 0 ? (h + ":" + u.getPort()) : String.valueOf(h);
        } catch (Throwable t) {
            return b;
        }
    }

    // ================= 保存本场字幕（翻译 + 原文） =================

    /**
     * 保存本场字幕。数据来自服务器留底（GET /api/record?kind=lines），
     * 所以**从开播到现在的全部字幕**都能拿到，不受「什么时候打开 App」影响。
     */
    private void showSavePicker() {
        int last = sp.getInt(Prefs.KEY_SAVE_FMT, RecordExporter.FMT_TXT);
        if (last < 0 || last >= RecordExporter.FMT_LABELS.length) last = RecordExporter.FMT_TXT;
        final int[] choice = {last};

        new AlertDialog.Builder(this)
                .setTitle("保存本场字幕")
                .setSingleChoiceItems(RecordExporter.FMT_LABELS, last,
                        (d, which) -> choice[0] = which)
                .setPositiveButton("保存", (d, w) -> {
                    sp.edit().putInt(Prefs.KEY_SAVE_FMT, choice[0]).apply();
                    startSave(choice[0]);
                })
                .setNeutralButton("说明", (d, w) -> showSaveHelp())
                .setNegativeButton("取消", null)
                .show();
    }

    private void showSaveHelp() {
        new AlertDialog.Builder(this)
                .setTitle("关于保存本场字幕")
                .setMessage("· 保存的是服务器留底的本场全部字幕（从开播到现在），"
                        + "什么时候打开 App 都不影响。\n"
                        + "· 换台或重新开播后，记录会从头重置。\n"
                        + "· 文件存到「下载/火羽字幕/」，可在文件管理器的「下载」里找到。\n"
                        + "· 「中日对照」= 中文翻译 + 日文原文；SRT 可直接挂到播放器当外挂字幕。\n"
                        + "· 字幕由服务端 ASR + 翻译推送，个别句子可能在译文到达前就已被记录，"
                        + "若发现空行可稍后重存一次。")
                .setPositiveButton("知道了", null)
                .show();
    }

    /** API < 29 需要写外部存储权限；Q+ 走 MediaStore 不需要任何权限 */
    private boolean storageReady() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true;
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startSave(final int fmt) {
        if (!storageReady()) {
            pendingSaveFmt = fmt;
            try {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
            } catch (Throwable t) {
                Toast.makeText(this, "无法申请存储权限，保存可能失败", Toast.LENGTH_LONG).show();
            }
            return;
        }

        final AlertDialog wait = new AlertDialog.Builder(this)
                .setMessage("正在从服务器获取本场字幕…")
                .setCancelable(false)
                .create();
        try {
            wait.show();
        } catch (Throwable ignored) {
        }

        final String b = base();
        final String t = token();
        RecordExporter.fetchAsync(b, t, new RecordExporter.Callback() {
            @Override
            public void onSuccess(final RecordExporter.Session s) {
                if (s == null || s.lines.isEmpty()) {
                    runOnUiThread(() -> {
                        dismissQuietly(wait);
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("没有可保存的字幕")
                                .setMessage("服务器当前没有本场字幕记录。\n\n"
                                        + "可能原因：本场还没开播；刚换台 / 重新开播（记录已重置）；"
                                        + "或当前这条线路不留底字幕 —— 可在设置里换回主服务器再试一次。")
                                .setPositiveButton("好", null)
                                .show();
                    });
                    return;
                }

                final RecordExporter.Export ex;
                final String path;
                try {
                    ex = RecordExporter.build(fmt, s, shortAddr(b));
                    path = FileStore.save(MainActivity.this, ex.name, ex.mime, ex.text);
                } catch (final Throwable e) {
                    runOnUiThread(() -> {
                        dismissQuietly(wait);
                        showSaveFailed(fmt, e);
                    });
                    return;
                }

                final String finalPath = path;
                runOnUiThread(() -> {
                    dismissQuietly(wait);
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("已保存 " + ex.count + " 句字幕")
                            .setMessage("文件：" + finalPath
                                    + "\n大小：" + fmtSize(ex.bytes())
                                    + "\n\n可在「文件管理 → 下载 → " + FileStore.DIR_NAME
                                    + "」里找到；SRT 可直接作为播放器外挂字幕。")
                            .setPositiveButton("好", null)
                            .setNeutralButton("再存一份", (d, w) -> showSavePicker())
                            .show();
                });
            }

            @Override
            public void onFailure(final String msg) {
                runOnUiThread(() -> {
                    dismissQuietly(wait);
                    showSaveFailed(fmt, new IllegalStateException(msg));
                });
            }
        });
    }

    private void showSaveFailed(final int fmt, Throwable e) {
        if (isFinishing()) return;
        String m = e == null ? "未知错误" : (e.getMessage() == null
                ? e.getClass().getSimpleName() : e.getMessage());
        new AlertDialog.Builder(this)
                .setTitle("保存失败")
                .setMessage(m + "\n\n检查一下：服务器地址 / 密钥 k 是否正确、"
                        + "手机网络能否访问该服务器、服务端是否支持 /api/record 接口。")
                .setPositiveButton("好", null)
                .setNeutralButton("重试", (d, w) -> startSave(fmt))
                .show();
    }

    private void dismissQuietly(AlertDialog d) {
        if (d == null) return;
        try {
            if (d.isShowing()) d.dismiss();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_WRITE) {
            if (res != null && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
                startSave(pendingSaveFmt);
            } else {
                Toast.makeText(this, "未授予存储权限，无法写入「下载」目录", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ================= 版本检测 / 升级提醒 =================

    /**
     * 检测是否有新版本。
     *
     * @param manual true=用户手动点「检查更新」（失败与「已是最新」都会给出提示）；
     *               false=打开 App 时自动检测（失败静默，只有确实有新版本才弹窗）
     */
    private void checkUpdate(final boolean manual) {
        if (!manual) lastAutoCheckAt = System.currentTimeMillis();
        UpdateChecker.check(base(), token(), new UpdateChecker.Callback() {
            @Override
            public void onSuccess(final UpdateChecker.Info info) {
                runOnUiThread(() -> handleUpdateInfo(info, manual));
            }

            @Override
            public void onFailure(final String msg) {
                runOnUiThread(() -> {
                    if (manual) {
                        Toast.makeText(MainActivity.this, "检查更新失败：" + msg, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void handleUpdateInfo(UpdateChecker.Info info, boolean manual) {
        if (isFinishing() || info == null) return;
        int cur = installedVersionCode();
        if (info.versionCode <= cur) {
            if (manual) {
                Toast.makeText(this, "当前已是最新版本 " + installedVersionName(), Toast.LENGTH_SHORT).show();
            }
            return;
        }
        showUpdateDialog(info, cur);
    }

    /** 弹升级提醒：可点「立即升级」打开下载页，或点「稍后更新」关闭 */
    private void showUpdateDialog(final UpdateChecker.Info info, int cur) {
        if (updateDialogShowing || isFinishing()) return;
        updateDialogShowing = true;

        final boolean force = info.force || (info.minVersionCode > 0 && cur < info.minVersionCode);
        final String ver = info.label();
        final String title = (info.title == null || info.title.trim().isEmpty())
                ? ("发现新版本 " + ver) : info.title.trim();

        TextView tv = new TextView(this);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setLineSpacing(0f, 1.3f);

        StringBuilder sb = new StringBuilder();
        sb.append("当前版本：").append(installedVersionName()).append("（").append(cur).append("）\n");
        sb.append("最新版本：").append(ver).append("（").append(info.versionCode).append("）\n");
        if (info.sizeBytes > 0) {
            sb.append("安装包大小：约 ").append(fmtSize(info.sizeBytes)).append("\n");
        }
        sb.append('\n');
        sb.append(info.notes == null || info.notes.trim().isEmpty()
                ? "建议升级到最新版本，获得更好的体验。" : info.notes.trim());
        if (force) {
            sb.append("\n\n当前版本已不再受支持，请升级后继续使用。");
        }
        tv.setText(sb.toString());

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(tv);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        // 升级地址：服务端未给就用 {当前字幕服务器}/app
        final String url = (info.downloadUrl == null || info.downloadUrl.trim().isEmpty())
                ? (Prefs.normalize(base()) + "app") : info.downloadUrl.trim();

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton("立即升级", (d, w) -> openDownload(url));

        if (force) {
            b.setCancelable(false);
        } else {
            b.setNegativeButton("稍后更新", null);       // 点一下即关闭
        }

        AlertDialog dlg = b.create();
        dlg.setCanceledOnTouchOutside(!force);
        dlg.setOnDismissListener(d -> updateDialogShowing = false);
        dlg.show();
    }

    /** 用系统浏览器 / 下载器打开升级地址（默认 http://<当前服务器>/app） */
    private void openDownload(String url) {
        if (url == null || url.trim().isEmpty()) {
            Toast.makeText(this, "下载地址为空", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url.trim()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开下载地址：" + url, Toast.LENGTH_LONG).show();
        }
    }

    /** 本机已安装的 versionCode */
    @SuppressWarnings("deprecation")
    private int installedVersionCode() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= 28) return (int) pi.getLongVersionCode();
            return pi.versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 本机已安装的 versionName */
    @SuppressWarnings("deprecation")
    private String installedVersionName() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName == null ? "" : pi.versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    private String fmtSize(long n) {
        if (n < 1024) return n + " B";
        if (n < 1048576) return String.format(Locale.US, "%.0f KB", n / 1024.0);
        return String.format(Locale.US, "%.1f MB", n / 1048576.0);
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

        // ===== 悬浮字幕样式（颜色 / 字体，改动立即生效）=====
        addSection(box, "悬浮字幕样式 · 改动立即生效");

        final TextView stylePreview = mkStylePreview();
        box.addView(stylePreview);

        final TextView rowZh = addActionRow(box, colorRowText("中文（翻译）颜色", fgColor()));
        rowZh.setOnClickListener(v -> ColorPicker.show(this, "中文（翻译）颜色", fgColor(),
                Prefs.DEF_FG_COLOR, c -> {
                    sp.edit().putInt(Prefs.KEY_FG_COLOR, c).apply();
                    rowZh.setText(colorRowText("中文（翻译）颜色", c));
                    refreshStylePreview(stylePreview);
                    applyStyleNow();
                }));

        final TextView rowJa = addActionRow(box, colorRowText("日文（原文）颜色", jaColor()));
        rowJa.setOnClickListener(v -> ColorPicker.show(this, "日文（原文）颜色", jaColor(),
                Prefs.DEF_JA_COLOR, c -> {
                    sp.edit().putInt(Prefs.KEY_JA_COLOR, c).apply();
                    rowJa.setText(colorRowText("日文（原文）颜色", c));
                    refreshStylePreview(stylePreview);
                    applyStyleNow();
                }));

        final TextView rowFont = addActionRow(box, fontRowText());
        rowFont.setOnClickListener(v -> pickFont(rowFont, stylePreview));

        final CheckBox cbBold = new CheckBox(this);
        cbBold.setText("中文加粗（压在视频上更清楚）");
        cbBold.setTextColor(0xFFEAEAEA);
        cbBold.setChecked(sp.getBoolean(Prefs.KEY_BOLD, Prefs.DEF_BOLD));
        cbBold.setOnCheckedChangeListener((b, checked) -> {
            sp.edit().putBoolean(Prefs.KEY_BOLD, checked).apply();
            refreshStylePreview(stylePreview);
            applyStyleNow();
        });
        box.addView(cbBold);

        refreshStylePreview(stylePreview);

        CheckBox cbJa = new CheckBox(this);
        cbJa.setText("显示日文原文对照");
        cbJa.setTextColor(0xFFEAEAEA);
        cbJa.setChecked(sp.getBoolean(Prefs.KEY_JA, true));
        box.addView(cbJa);

        CheckBox cbAuto = new CheckBox(this);
        cbAuto.setText("网址变化时自动切换字幕服务器");
        cbAuto.setTextColor(0xFFEAEAEA);
        cbAuto.setChecked(sp.getBoolean(Prefs.KEY_AUTO_SWITCH, true));
        box.addView(cbAuto);

        TextView tip = new TextView(this);
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tip.setTextColor(0xFF8B8B95);
        tip.setPadding(0, dp(12), 0, 0);
        tip.setText("用法：\n"
                + "1. 点「开启悬浮字幕」并允许「显示在其他应用上层」权限。\n"
                + "2. 之后切到任意应用（含全屏播放器）都能看到火羽的实时翻译字幕。\n"
                + "3. 拖动字幕可移动；轻点一下出现控制条（字号 / 中日 / 行数 / 锁定 / 关闭）。\n"
                + "4. 锁定后字幕区域点击穿透，不会误触；再点通知栏「解锁」恢复。\n"
                + "5. 通知栏常驻按钮：锁定、仅中文、停止。\n"
                + "6. 自动切换：网页地址跳到白名单服务器（106.15.35.175:6006 / "
                + "123.60.104.22:6006 / 123.60.104.22:6007 / 8.133.231.186:6006）时，"
                + "字幕服务器地址会自动跟着切过去，悬浮字幕立即重连。\n"
                + "7. 版本检测：每次打开 App 会向当前服务器查询最新版本，"
                + "有新版会弹窗提醒，点「立即升级」进入下载页。\n"
                + "8. 存字幕：点工具条「存字幕」可把本场全部字幕（中文翻译 + 日文原文）"
                + "导出成 TXT / SRT / CSV / Markdown / JSON，文件存到「下载/火羽字幕」。\n"
                + "9. 样式：本页可改中文字幕颜色、日文字幕颜色、字体（7 种系统字体，"
                + "也可导入手机里的 .ttf / .otf）和是否加粗，改完立即生效。");
        box.addView(tip);

        TextView verInfo = new TextView(this);
        verInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        verInfo.setTextColor(0xFF8B8B95);
        verInfo.setPadding(0, dp(12), 0, 0);
        verInfo.setText("当前版本：" + installedVersionName() + "（" + installedVersionCode() + "）");
        box.addView(verInfo);

        final AlertDialog settingsDlg = new AlertDialog.Builder(this)
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
                    e.putBoolean(Prefs.KEY_AUTO_SWITCH, cbAuto.isChecked());
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
                .create();

        Button btnCheck = new Button(this);
        btnCheck.setText("立即检查更新");
        btnCheck.setOnClickListener(v -> {
            settingsDlg.dismiss();
            checkUpdate(true);
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(12);
        btnCheck.setLayoutParams(blp);
        box.addView(btnCheck);

        Button btnDump = new Button(this);
        btnDump.setText("保存本场字幕（翻译 + 原文）");
        btnDump.setOnClickListener(v -> {
            settingsDlg.dismiss();
            showSavePicker();
        });
        LinearLayout.LayoutParams blp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp2.topMargin = dp(8);
        btnDump.setLayoutParams(blp2);
        box.addView(btnDump);

        settingsDlg.show();
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

    // ================= 悬浮字幕样式（颜色 / 字体） =================

    private int fgColor() {
        return sp.getInt(Prefs.KEY_FG_COLOR, Prefs.DEF_FG_COLOR) | 0xFF000000;
    }

    private int jaColor() {
        return sp.getInt(Prefs.KEY_JA_COLOR, Prefs.DEF_JA_COLOR) | 0xFF000000;
    }

    private String colorRowText(String label, int color) {
        return label + "    " + String.format(Locale.US, "#%06X", color & 0xFFFFFF);
    }

    private String fontRowText() {
        String fam = sp.getString(Prefs.KEY_FONT, Prefs.DEF_FONT);
        String path = sp.getString(Prefs.KEY_FONT_PATH, "");
        if (Prefs.FONT_CUSTOM.equals(fam) && !path.isEmpty()) {
            return "字体    自定义：" + new File(path).getName();
        }
        return "字体    " + Prefs.FONT_LABELS[Prefs.fontIndex(fam)];
    }

    private void addSection(LinearLayout box, String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTextColor(0xFF7FE0A6);
        t.setPadding(0, dp(16), 0, dp(4));
        box.addView(t);
    }

    private TextView addActionRow(LinearLayout box, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(0xFFEAEAEA);
        t.setPadding(dp(10), dp(9), dp(10), dp(9));
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xFF26262E);
        d.setCornerRadius(dp(7));
        d.setStroke(dp(1), 0xFF3A3A44);
        t.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        t.setLayoutParams(lp);
        box.addView(t);
        return t;
    }

    private TextView mkStylePreview() {
        TextView p = new TextView(this);
        p.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xFF101014);
        d.setCornerRadius(dp(9));
        d.setStroke(dp(1), 0xFF3A3A44);
        p.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        p.setLayoutParams(lp);
        return p;
    }

    /** 预览块：中文用中文色、日文用日文色，字体/加粗同步当前配置 */
    private void refreshStylePreview(TextView p) {
        if (p == null) return;
        try {
            p.setTypeface(Prefs.currentTypeface(sp, sp.getBoolean(Prefs.KEY_BOLD, Prefs.DEF_BOLD)));
            p.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            String s = "示例字幕 サンプル";
            SpannableString ss = new SpannableString(s);
            ss.setSpan(new ForegroundColorSpan(fgColor()), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            ss.setSpan(new ForegroundColorSpan(jaColor()), 5, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            p.setText(ss);
        } catch (Throwable ignored) {
        }
    }

    /** 样式改动后让正在运行的悬浮字幕立刻重绘（不重连推送流） */
    private void applyStyleNow() {
        if (!OverlayService.RUNNING) return;
        try {
            startService(new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_STYLE));
        } catch (Throwable ignored) {
        }
    }

    private void pickFont(final TextView rowFont, final TextView preview) {
        final int customIdx = Prefs.FONT_LABELS.length;
        String path = sp.getString(Prefs.KEY_FONT_PATH, "");
        String[] items = new String[customIdx + 1];
        System.arraycopy(Prefs.FONT_LABELS, 0, items, 0, customIdx);
        items[customIdx] = path.isEmpty()
                ? "自定义字体文件（.ttf / .otf）…"
                : ("自定义字体（" + new File(path).getName() + "）");

        String fam = sp.getString(Prefs.KEY_FONT, Prefs.DEF_FONT);
        int cur = Prefs.FONT_CUSTOM.equals(fam) ? customIdx : Prefs.fontIndex(fam);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("悬浮字幕字体")
                .setSingleChoiceItems(items, cur, (d, which) -> {
                    d.dismiss();
                    onFontChosen(which, rowFont, preview);
                })
                .setNegativeButton("取消", null);
        if (!path.isEmpty()) {
            b.setNeutralButton("移除自定义字体", (d, w) -> {
                try {
                    new File(path).delete();
                } catch (Throwable ignored) {
                }
                sp.edit().putString(Prefs.KEY_FONT, Prefs.DEF_FONT)
                        .putString(Prefs.KEY_FONT_PATH, "").apply();
                rowFont.setText(fontRowText());
                refreshStylePreview(preview);
                applyStyleNow();
                Toast.makeText(this, "已移除自定义字体", Toast.LENGTH_SHORT).show();
            });
        }
        b.show();
    }

    private void onFontChosen(int which, TextView rowFont, TextView preview) {
        int customIdx = Prefs.FONT_LABELS.length;
        if (which == customIdx) {
            String path = sp.getString(Prefs.KEY_FONT_PATH, "");
            if (path.isEmpty() || !new File(path).exists()) {
                pendingFontRow = rowFont;
                pendingFontPreview = preview;
                pickFontFile();
                return;
            }
            sp.edit().putString(Prefs.KEY_FONT, Prefs.FONT_CUSTOM).apply();
        } else {
            sp.edit().putString(Prefs.KEY_FONT, Prefs.fontFamily(which)).apply();
        }
        rowFont.setText(fontRowText());
        refreshStylePreview(preview);
        applyStyleNow();
    }

    /** 打开系统文件选择器挑一个字体文件 */
    private void pickFontFile() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_FONT);
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开文件选择器：" + t.getMessage(), Toast.LENGTH_LONG).show();
            pendingFontRow = null;
            pendingFontPreview = null;
        }
    }

    /** 把选中的字体文件拷进应用私有目录，并校验能否加载 */
    private String importFont(Uri uri) throws Exception {
        File dir = new File(getFilesDir(), "fonts");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建字体目录");

        File f = new File(dir, "custom_" + System.currentTimeMillis() + ".ttf");
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = getContentResolver().openInputStream(uri);
            if (in == null) throw new Exception("无法读取所选文件");
            out = new FileOutputStream(f);
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            out.flush();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }

        // 校验：加载不了就当不是字体文件
        try {
            Typeface tf = Typeface.createFromFile(f);
            if (tf == null) throw new Exception("不是有效的字体文件");
        } catch (Throwable t) {
            try {
                f.delete();
            } catch (Throwable ignored) {
            }
            throw new Exception("不是有效的字体文件（只支持 .ttf / .otf）");
        }

        // 删掉上一个自定义字体，避免越攒越多
        String old = sp.getString(Prefs.KEY_FONT_PATH, "");
        if (!old.isEmpty() && !old.equals(f.getAbsolutePath())) {
            try {
                new File(old).delete();
            } catch (Throwable ignored) {
            }
        }
        return f.getAbsolutePath();
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
