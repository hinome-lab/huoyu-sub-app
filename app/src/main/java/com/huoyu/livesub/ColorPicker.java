package com.huoyu.livesub;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

/**
 * 零依赖取色器：预设色板 + RGB 三条滑杆 + 实时示例预览。
 * 只取不透明颜色（alpha 固定 255），保证字幕压在视频上仍可读。
 */
public final class ColorPicker {

    public interface OnPick {
        void onPick(int color);
    }

    /** 预设色板：2 行 × 6 列（白色 / 灰白最常用，放最前） */
    private static final int[] PRESETS = {
            0xFFFFFFFF, 0xFFE6E6EC, 0xFFFFE86E, 0xFFFFD93D, 0xFFFFB36B, 0xFFFF9EC4,
            0xFF7FE0A6, 0xFF7FE0E8, 0xFF8FD8FF, 0xFFC9B6FF, 0xFFFF8A8A, 0xFF101014,
    };

    private ColorPicker() {
    }

    /**
     * 弹出取色对话框。
     *
     * @param title   标题，如「中文颜色」
     * @param initial 当前颜色（ARGB，alpha 会被忽略）
     * @param def     点「恢复默认」时使用的颜色
     * @param cb      点「确定」/「恢复默认」后的回调
     */
    public static void show(final Context ctx, String title, int initial, final int def, final OnPick cb) {
        final int[] rgb = {Color.red(initial), Color.green(initial), Color.blue(initial)};

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(ctx, 18), dp(ctx, 8), dp(ctx, 18), 0);

        // ---- 示例预览 ----
        final TextView preview = new TextView(ctx);
        preview.setText("示例字幕 · サンプル");
        preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        preview.setGravity(Gravity.CENTER);
        preview.setPadding(dp(ctx, 10), dp(ctx, 12), dp(ctx, 10), dp(ctx, 12));
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(0xFF101014);
        pbg.setCornerRadius(dp(ctx, 9));
        preview.setBackground(pbg);
        box.addView(preview);

        // ---- 预设色板 ----
        final TextView[] cells = new TextView[PRESETS.length];
        LinearLayout grid = new LinearLayout(ctx);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        glp.topMargin = dp(ctx, 10);
        grid.setLayoutParams(glp);
        box.addView(grid);

        for (int row = 0; row < 2; row++) {
            LinearLayout line = new LinearLayout(ctx);
            line.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (row > 0) llp.topMargin = dp(ctx, 8);
            line.setLayoutParams(llp);
            for (int col = 0; col < 6; col++) {
                final int idx = row * 6 + col;
                TextView c = new TextView(ctx);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, dp(ctx, 34), 1f);
                if (col > 0) clp.leftMargin = dp(ctx, 8);
                c.setLayoutParams(clp);
                cells[idx] = c;
                line.addView(c);
            }
            grid.addView(line);
        }

        // ---- 十六进制提示 ----
        final TextView hex = new TextView(ctx);
        hex.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hex.setTextColor(0xFF8B8B95);
        hex.setPadding(0, dp(ctx, 12), 0, 0);
        box.addView(hex);

        // ---- RGB 滑杆 ----
        final SeekBar[] bars = new SeekBar[3];
        final String[] names = {"红", "绿", "蓝"};
        for (int i = 0; i < 3; i++) {
            LinearLayout rowBox = new LinearLayout(ctx);
            rowBox.setOrientation(LinearLayout.HORIZONTAL);
            rowBox.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(ctx, 4);
            rowBox.setLayoutParams(rlp);

            TextView lbl = new TextView(ctx);
            lbl.setText(names[i]);
            lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            lbl.setWidth(dp(ctx, 28));
            rowBox.addView(lbl);

            SeekBar sb = new SeekBar(ctx);
            sb.setMax(255);
            rowBox.addView(sb, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            bars[i] = sb;
            box.addView(rowBox);
        }

        // 统一刷新：颜色 → 预览 / 十六进制 / 色板高亮
        final Runnable refresh = new Runnable() {
            @Override
            public void run() {
                int c = Color.rgb(rgb[0], rgb[1], rgb[2]);
                preview.setTextColor(c);
                hex.setText(String.format(Locale.US, "#%02X%02X%02X　红 %d 绿 %d 蓝 %d",
                        rgb[0], rgb[1], rgb[2], rgb[0], rgb[1], rgb[2]));
                for (int i = 0; i < cells.length; i++) {
                    boolean on = Color.red(c) == Color.red(PRESETS[i])
                            && Color.green(c) == Color.green(PRESETS[i])
                            && Color.blue(c) == Color.blue(PRESETS[i]);
                    cells[i].setBackground(swatch(ctx, PRESETS[i], on ? 0xFFFFFFFF : 0));
                }
            }
        };

        for (int i = 0; i < 3; i++) {
            final int ch = i;
            bars[i].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    rgb[ch] = progress;
                    refresh.run();
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
        }

        for (int i = 0; i < cells.length; i++) {
            final int idx = i;
            cells[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    rgb[0] = Color.red(PRESETS[idx]);
                    rgb[1] = Color.green(PRESETS[idx]);
                    rgb[2] = Color.blue(PRESETS[idx]);
                    refresh.run();
                }
            });
        }

        // 初始值（触发一次 refresh，同时把滑杆位置对齐）
        for (int i = 0; i < 3; i++) bars[i].setProgress(rgb[i]);
        refresh.run();

        new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("确定", (d, w) -> {
                    if (cb != null) cb.onPick(Color.rgb(rgb[0], rgb[1], rgb[2]));
                })
                .setNeutralButton("恢复默认", (d, w) -> {
                    if (cb != null) cb.onPick(def);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 色块背景：圆角方块 + 描边（选中白边，未选中细灰边） */
    private static GradientDrawable swatch(Context ctx, int color, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color | 0xFF000000);
        d.setCornerRadius(dp(ctx, 7));
        d.setStroke(dp(ctx, strokeColor != 0 ? 2 : 1), strokeColor != 0 ? strokeColor : 0xFF3A3A44);
        return d;
    }

    private static int dp(Context ctx, float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
