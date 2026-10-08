package com.hidebar.lsp;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Locale;

/**
 * 状态栏背景颜色 · 自定义调色板。
 * <p>
 * 交互跟 MIUI「色彩风格」的色温盘一致：一个色相/饱和度圆盘 + 一条深浅，
 * 改动即时下发（走广播请 system_server 里的模块写 Settings.Global），
 * 不用重启，下拉状态栏就能看到真实效果。
 * <p>
 * 【重要】和 MainActivity 一样，本类绝不能引用 HookEntry（Xposed 是 compileOnly，
 * 普通 App 进程里没有那些类，一引用就 NoClassDefFoundError 闪退）。
 */
public class ColorPickerActivity extends Activity {

    private ColorWheelView wheel;
    /** v2.1.3：预览分两套——顶部状态栏条 / 底部三键条，按当前调的是哪条栏切换 */
    private View previewStatus;
    private View previewNav;
    private View previewBar;
    private TextView tvDepth;
    private TextView tvPreviewColor;
    private SeekBar sbDepth;
    private Switch swAlways;
    private View rowAlways;

    /**
     * v2.1.2：这次调的是哪条栏。
     * false = 状态栏（{@link Config#K_BARCOLOR}），true = 导航栏（{@link Config#K_NAVCOLOR}）。
     */
    private boolean forNav = false;
    private String colorKey = Config.K_BARCOLOR;

    /** 当前不透明度 0..255；0 = 关闭 */
    private int alpha255 = 255;
    /** 上次下发时间，用来给连续拖动节流 */
    private long lastSendAt = 0L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_color);

        // v2.1.2：同一个调色板复用于状态栏 / 导航栏，靠 extra 区分改哪个键
        forNav = Config.TARGET_NAV.equals(getIntent().getStringExtra(Config.EXTRA_TARGET));
        colorKey = forNav ? Config.K_NAVCOLOR : Config.K_BARCOLOR;
        TextView title = findViewById(R.id.tvPickerTitle);
        if (title != null) {
            title.setText(forNav ? R.string.picker_title_nav : R.string.picker_title);
        }
        // 「始终显示」只对状态栏有意义：常显会让颜色一直盖住 App 底部。
        // 调导航栏时整张卡（含说明文字）一起收掉，免得留一段讲顶部的说明在下面。
        rowAlways = findViewById(R.id.rowAlways);
        View cardAlways = findViewById(R.id.cardAlways);
        if (forNav) {
            if (rowAlways != null) rowAlways.setVisibility(View.GONE);
            if (cardAlways != null) cardAlways.setVisibility(View.GONE);
        }

        // 预览跟着目标换：调导航栏时看底部三键那条，不再是顶部时间那条
        previewStatus = findViewById(R.id.previewBarStatus);
        previewNav = findViewById(R.id.previewBarNav);
        TextView previewLabel = findViewById(R.id.tvPreviewLabel);
        if (previewStatus != null) previewStatus.setVisibility(forNav ? View.GONE : View.VISIBLE);
        if (previewNav != null) previewNav.setVisibility(forNav ? View.VISIBLE : View.GONE);
        previewBar = forNav ? previewNav : previewStatus;
        if (previewLabel != null) {
            previewLabel.setText(forNav ? R.string.picker_preview_nav : R.string.picker_preview_status);
        }

        wheel = findViewById(R.id.wheel);
        tvDepth = findViewById(R.id.tvDepth);
        tvPreviewColor = findViewById(R.id.tvPreviewColor);
        sbDepth = findViewById(R.id.sbDepth);

        loadCurrent();

        if (wheel != null) {
            wheel.setOnPickListener(new ColorWheelView.OnPickListener() {
                @Override
                public void onPick(float hue, float sat, boolean fromUser) {
                    refreshUi();
                    // 只有用户真的在色轮上拖过才落盘。
                    // 否则 loadCurrent() 里的 setHsv() 初始化就会触发一次回写，
                    // 用户只是点进来看一眼，颜色就被莫名其妙打开了。
                    if (fromUser) sendThrottled();
                }
            });
        }

        if (sbDepth != null) {
            sbDepth.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    alpha255 = Math.round(progress * 255f / 100f);
                    if (alpha255 < 0) alpha255 = 0;
                    if (alpha255 > 255) alpha255 = 255;
                    if (wheel != null) wheel.setDepth(alpha255 / 255f);
                    refreshUi();
                }

                @Override
                public void onStartTrackingTouch(SeekBar bar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar bar) {
                    sendNow();
                }
            });
        }

        swAlways = findViewById(R.id.swAlways);
        if (swAlways != null) {
            swAlways.setChecked(alwaysOn());
            swAlways.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton b, boolean on) {
                    sendAlways(on);
                }
            });
        }

        // v2.1.3：预设只剩三个。关键是把明度（V）也传给色轮——
        // 纯黑 = V 0，纯白 = V 1；以前 V 恒为 1，纯黑按下去算出来是白色，等于没生效。
        preset(R.id.btnPreNone, 0f, 0f, 1f, 0);      // 关闭：整层透明
        preset(R.id.btnPreBlack, 0f, 0f, 0f, 255);   // 纯黑
        preset(R.id.btnPreWhite, 0f, 0f, 1f, 255);   // 纯白

        View back = findViewById(R.id.btnBack);
        if (back != null) back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        Button done = findViewById(R.id.btnDone);
        if (done != null) done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendNow();
                finish();
            }
        });
    }

    /** @param v 明度 0..1（0 = 纯黑）；@param a 不透明度 0..255（0 = 关闭） */
    private void preset(int id, final float h, final float s, final float v, final int a) {
        View view = findViewById(id);
        if (view == null) return;
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View vv) {
                if (wheel != null) {
                    wheel.setHsv(h, s, v);
                    wheel.setDepth(a / 255f);
                }
                alpha255 = a;
                if (sbDepth != null) sbDepth.setProgress(Math.round(a * 100f / 255f));
                refreshUi();
                sendNow();
            }
        });
    }

    /** 读当前配置：0 表示关闭。 */
    private void loadCurrent() {
        int cur = 0;
        try {
            String s = Settings.Global.getString(getContentResolver(), colorKey);
            if (s != null && s.trim().length() > 0) cur = (int) Long.parseLong(s.trim());
        } catch (Throwable ignored) {
        }
        if (cur == 0) {
            // 关闭：色轮停在"中性偏冷"的起始位置，深浅 0%
            if (wheel != null) {
                wheel.setHsv(210f, 0f);
                wheel.setDepth(0f);
            }
            alpha255 = 0;
            if (sbDepth != null) sbDepth.setProgress(0);
        } else {
            float[] hsv = new float[3];
            Color.colorToHSV(cur, hsv);
            alpha255 = Color.alpha(cur);
            if (wheel != null) {
                // hsv[2] 是明度：纯黑读回来是 0，不能丢，否则一进页面黑色就变白了
                wheel.setHsv(hsv[0], hsv[1], hsv[2]);
                wheel.setDepth(alpha255 / 255f);
            }
            if (sbDepth != null) sbDepth.setProgress(Math.round(alpha255 * 100f / 255f));
        }
        refreshUi();
    }

    /** 组装成 ARGB；深浅为 0 时等同于关闭。 */
    private int argb() {
        if (alpha255 <= 0) return 0;
        if (wheel == null) return 0;
        return wheel.color(alpha255);
    }

    private void refreshUi() {
        final int c = argb();
        if (previewBar != null) previewBar.setBackgroundColor(c);
        if (tvDepth != null) {
            tvDepth.setText(String.format(Locale.US, "%d%%", Math.round(alpha255 * 100f / 255f)));
        }
        if (tvPreviewColor != null) {
            if (c == 0) {
                tvPreviewColor.setText("关闭（完全透明）");
            } else {
                tvPreviewColor.setText(String.format(Locale.US, "#%08X", c)
                        + String.format(Locale.US, "   不透明度 %d%%",
                        Math.round(Color.alpha(c) * 100f / 255f)));
            }
        }
        paintSwatch(findViewById(R.id.colorSwatch), c);
    }

    /** 主界面那个小色块也在这里刷（本页没有，留着复用）。 */
    private void paintSwatch(View v, int c) {
        if (v == null) return;
        try {
            if (v.getBackground() instanceof GradientDrawable) {
                GradientDrawable g = (GradientDrawable) v.getBackground().mutate();
                // 关闭时给个浅灰，表示"没有颜色"
                g.setColor(c == 0 ? 0xFFDDDDDD : c);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 连续拖动时每 150ms 最多发一次，避免刷爆广播。 */
    private void sendThrottled() {
        final long now = android.os.SystemClock.uptimeMillis();
        if (now - lastSendAt < 150L) return;
        sendNow();
    }

    private void sendNow() {
        lastSendAt = android.os.SystemClock.uptimeMillis();
        put(colorKey, String.valueOf(argb()));
    }

    /** v2.0.3：颜色图层常显开关。 */
    private void sendAlways(boolean on) {
        put(Config.K_COLORALWAYS, on ? "1" : "0");
    }

    private boolean alwaysOn() {
        try {
            String s = Settings.Global.getString(getContentResolver(), Config.K_COLORALWAYS);
            return s != null && "1".equals(s.trim());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void put(String key, String value) {
        try {
            Intent i = new Intent(Config.ACTION_SET);
            i.putExtra(Config.EXTRA_KEY, key);
            i.putExtra(Config.EXTRA_VALUE, value);
            sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }
}
