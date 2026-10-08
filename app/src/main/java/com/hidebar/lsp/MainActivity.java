package com.hidebar.lsp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Locale;

/**
 * 模块控制面板（v8）。
 * <p>
 * 三个开关：隐藏状态栏 / 隐藏导航栏 / 硬隐藏，互相独立。
 * <p>
 * 【重要】本类绝不能引用 HookEntry —— 那会牵出 Xposed 的 compileOnly 类，
 * 在普通 App 进程里抛 NoClassDefFoundError 直接闪退。配置相关一律走 Config。
 */
public class MainActivity extends Activity {

    private Switch swStatus;
    private Switch swNav;
    private Switch swHard;
    private Switch swAbsorb;
    /**
     * v2.2：「应用占满全屏」从单一开关改成三段模式（关闭 / 浮层 / 挤压）。
     * 折叠起来只占一行，展开才是三个按钮 + 当前模式的说明。
     */
    private View sbFullRow;
    private View sbFullArrow;
    private View sbFullBody;
    private TextView tvFullMode;
    private TextView tvFullNote;
    private Button btnFullOff;
    private Button btnFullOverlay;
    private Button btnFullSq;
    /** 当前模式：0 = 关闭，1 = 浮层式，2 = 挤压式 */
    private int fullMode = 1;
    /** v2.1：「底部也占满」+「导航栏软隐藏」 */
    private Switch swFullNav;
    /** v2.3：顺带清掉其它贴边条带（tappableElement / 系统手势区）—— 治 QQ 那类"留白靠手势区算"的 App */
    private Switch swZeroEdge;
    private Switch swNavSoft;
    /** v2.1.1：「呼出时顶开内容」+「隐藏时不响应触摸」 */
    private Switch swNavSq;
    private Switch swTouchBlock;
    /** 「状态栏背景颜色」入口行 + 小色块 + 取值文本 */
    private View rowColor;
    private View colorSwatch;
    private TextView tvColorVal;
    /** v2.1.2：导航栏背景颜色入口行 */
    private View rowNavColor;
    private View navColorSwatch;
    private TextView tvNavColorVal;
    private TextView tvState;
    private TextView tvPolicy;
    private TextView tvConfig;
    private TextView tvVersion;
    private TextView tvModVer;

    /* ── 「隐藏状态栏」下方的二级菜单 ── */
    private View sbCard;
    private View sbArrow;
    private View sbBody;
    /* ── v2.3.1：「隐藏导航栏」下方的二级菜单（背景颜色 / 软隐藏 / 呼出挤压） ── */
    private View navArrow;
    private View navBody;
    /** 状态栏显示时间（揭示窗口时长），0.5 ~ 10 秒 */
    private SeekBar sbTime;
    private TextView tvTimeVal;
    /** 进阶：隐藏细致参数 */
    private View advHeader;
    private View advArrow;
    private View advBody;
    private SeekBar sbAlpha;
    private TextView tvAlphaVal;

    /**
     * 时间滑块量程：0.5s ~ 10s，步长 0.25s（用 0..38 的整数格表示）。
     * 默认落到 3.5s。
     */
    private static final int T_MAX_STEP = 38;
    private static final int T_DEF_MS = 3500;
    private static final int T_MIN_MS = 500;
    private static final int T_MAX_MS = 10000;

    /** 透明度滑块：1‰ ~ 100‰，默认 10‰ */
    private static final int A_MIN = 1;
    private static final int A_MAX = 100;
    private static final int A_DEF = 10;

    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean moduleAlive = false;
    private long sentAt = 0L;

    /** 模块回执：拿到当前真正生效的值，同时说明模块活着。 */
    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (i == null || !Config.ACTION_STATE.equals(i.getAction())) return;
            moduleAlive = true;
            final boolean ok = i.getBooleanExtra(Config.EXTRA_OK, true);
            final String st = i.getStringExtra(Config.EXTRA_STATUS);
            final String nv = i.getStringExtra(Config.EXTRA_NAV);
            final String hd = i.getStringExtra(Config.EXTRA_HARD);
            final String en = i.getStringExtra(Config.EXTRA_ENABLE);
            final String mv = i.getStringExtra(Config.EXTRA_MODVER);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    applying = true;
                    if (st != null) swStatus.setChecked(!"0".equals(st));
                    if (nv != null) swNav.setChecked(!"0".equals(nv));
                    if (hd != null) swHard.setChecked("1".equals(hd));
                    applying = false;
                    if (!ok) {
                        setState("模块收到了，但写入设置失败", false);
                    } else if ("0".equals(en)) {
                        setState("已保存。注意：总开关 autohide_enable=0，整体功能当前是关闭的", false);
                    } else {
                        setState("已生效（模块已连接）", true);
                    }
                    showModuleVersion(mv);
                    refreshPolicy();
                }
            });
        }
    };

    /** 防止 setChecked 触发 onCheckedChanged 造成回环 */
    private boolean applying = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        swStatus = findViewById(R.id.swStatus);
        swNav = findViewById(R.id.swNav);
        swHard = findViewById(R.id.swHard);
        swAbsorb = findViewById(R.id.swAbsorb);
        sbFullRow = findViewById(R.id.sbFullRow);
        sbFullArrow = findViewById(R.id.sbFullArrow);
        sbFullBody = findViewById(R.id.sbFullBody);
        tvFullMode = findViewById(R.id.tvFullMode);
        tvFullNote = findViewById(R.id.tvFullNote);
        btnFullOff = findViewById(R.id.btnFullOff);
        btnFullOverlay = findViewById(R.id.btnFullOverlay);
        btnFullSq = findViewById(R.id.btnFullSq);
        swFullNav = findViewById(R.id.swFullNav);
        swZeroEdge = findViewById(R.id.swZeroEdge);
        swNavSoft = findViewById(R.id.swNavSoft);
        swNavSq = findViewById(R.id.swNavSq);
        swTouchBlock = findViewById(R.id.swTouchBlock);
        rowColor = findViewById(R.id.rowColor);
        colorSwatch = findViewById(R.id.colorSwatch);
        tvColorVal = findViewById(R.id.tvColorVal);
        rowNavColor = findViewById(R.id.rowNavColor);
        navColorSwatch = findViewById(R.id.navColorSwatch);
        tvNavColorVal = findViewById(R.id.tvNavColorVal);
        tvState = findViewById(R.id.tvState);
        tvPolicy = findViewById(R.id.tvPolicy);
        tvConfig = findViewById(R.id.tvConfig);
        tvVersion = findViewById(R.id.tvVersion);
        tvModVer = findViewById(R.id.tvModVer);
        if (tvModVer != null) tvModVer.setVisibility(View.GONE);
        sbCard = findViewById(R.id.sbCard);
        sbArrow = findViewById(R.id.sbArrow);
        sbBody = findViewById(R.id.sbBody);
        navArrow = findViewById(R.id.navArrow);
        navBody = findViewById(R.id.navBody);
        sbTime = findViewById(R.id.sbTime);
        tvTimeVal = findViewById(R.id.tvTimeVal);
        advHeader = findViewById(R.id.advHeader);
        advArrow = findViewById(R.id.advArrow);
        advBody = findViewById(R.id.advBody);
        sbAlpha = findViewById(R.id.sbAlpha);
        tvAlphaVal = findViewById(R.id.tvAlphaVal);
        Button btn = findViewById(R.id.btnRefresh);

        tvVersion.setText(versionText());

        setupAbout();
        setupStatusSub();
        setupNavSub();

        swStatus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (applying) return;
                send(Config.K_STATUS, swStatus.isChecked() ? "1" : "0");
            }
        });
        swNav.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (applying) return;
                send(Config.K_NAV, swNav.isChecked() ? "1" : "0");
            }
        });
        swHard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (applying) return;
                send(Config.K_HARD, swHard.isChecked() ? "1" : "0");
            }
        });
        if (swAbsorb != null) {
            swAbsorb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (applying) return;
                    send(Config.K_ABSORB, swAbsorb.isChecked() ? "1" : "0");
                }
            });
        }
        if (swFullNav != null) {
            swFullNav.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (applying) return;
                    send(Config.K_FULLNAV, swFullNav.isChecked() ? "1" : "0");
                }
            });
        }
        if (swZeroEdge != null) {
            swZeroEdge.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (applying) return;
                    send(Config.K_ZEROEDGE, swZeroEdge.isChecked() ? "1" : "0");
                }
            });
        }
        if (swNavSoft != null) {
            swNavSoft.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (applying) return;
                    send(Config.K_NAVSOFT, swNavSoft.isChecked() ? "1" : "0");
                }
            });
        }
        if (swNavSq != null) {
            swNavSq.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (applying) return;
                    send(Config.K_NAVSQ, swNavSq.isChecked() ? "1" : "0");
                }
            });
        }
        if (swTouchBlock != null) {
            swTouchBlock.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (applying) return;
                    // v2.4：写 2（导航栏 + 状态栏一起）。写 1 的话只挡导航栏，
                    // 顶部那条永远够不到 —— 这正是"顶部内容点不到"的根源之一。
                    send(Config.K_TOUCHBLOCK, swTouchBlock.isChecked() ? "2" : "0");
                }
            });
        }
        if (rowColor != null) {
            rowColor.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        startActivity(new Intent(MainActivity.this, ColorPickerActivity.class));
                    } catch (Throwable ignored) {
                    }
                }
            });
        }
        // v2.1.2：导航栏背景颜色 —— 复用同一个调色板，靠 EXTRA_TARGET 区分改哪个键
        if (rowNavColor != null) {
            rowNavColor.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        Intent i = new Intent(MainActivity.this, ColorPickerActivity.class);
                        i.putExtra(Config.EXTRA_TARGET, Config.TARGET_NAV);
                        startActivity(i);
                    } catch (Throwable ignored) {
                    }
                }
            });
        }
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                query();
            }
        });

        refreshPolicy();

        tvConfig.setText("root 命令行同样可用（优先级：Settings.Global > 属性 > 默认）：\n"
                + "  su -c 'setprop persist.autohide.statusbar 0'\n"
                + "  su -c 'setprop persist.autohide.navbar 0'\n"
                + "注意：普通 adb shell 的 setprop 会静默失败，必须 su。");
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 先用轮询方式把开关摆到当前值（settings 读不到就退回属性/默认）
        applying = true;
        swStatus.setChecked(!"0".equals(current(Config.K_STATUS, Config.P_STATUS, "1")));
        swNav.setChecked(!"0".equals(current(Config.K_NAV, Config.P_NAV, "1")));
        swHard.setChecked("1".equals(current(Config.K_HARD, Config.P_HARD, "0")));
        if (swAbsorb != null) {
            swAbsorb.setChecked("1".equals(current(Config.K_ABSORB, Config.P_ABSORB, "0")));
        }
        if (sbAlpha != null) {
            sbAlpha.setProgress(alphaCurrent());
        }
        if (sbTime != null) {
            sbTime.setProgress(timeToStep(timeCurrentMs()));
        }
        refreshFullModeUi();
        if (swFullNav != null) {
            swFullNav.setChecked(!"0".equals(current(Config.K_FULLNAV, Config.P_FULLNAV, "1")));
        }
        if (swZeroEdge != null) {
            swZeroEdge.setChecked(!"0".equals(current(Config.K_ZEROEDGE, Config.P_ZEROEDGE, "1")));
        }
        if (swNavSoft != null) {
            swNavSoft.setChecked(!"0".equals(current(Config.K_NAVSOFT, Config.P_NAVSOFT, "1")));
        }
        if (swNavSq != null) {
            swNavSq.setChecked(!"0".equals(current(Config.K_NAVSQ, Config.P_NAVSQ, "1")));
        }
        if (swTouchBlock != null) {
            swTouchBlock.setChecked(!"0".equals(current(Config.K_TOUCHBLOCK, Config.P_TOUCHBLOCK, "1")));
        }
        applying = false;
        refreshColorRow();
        refreshNavColorRow();
        refreshPolicy();
        registerRx();
        query();
        probeInsets();
    }

    /* ── v2.0 诊断探针 ────────────────────────────────────────────────
     * 「应用占满全屏」到底生效没有，靠截图很难判（还要解锁、还要换算坐标）。
     * 这里把**这个 App 自己**看到的顶部内边距打到 logcat，一行数字就能 A/B：
     *   模块生效前 statusTop=104  →  生效后 statusTop=0
     * 抓法：adb logcat -s AutoHideUI
     * ──────────────────────────────────────────────────────────────── */
    private void probeInsets() {
        final View deco = getWindow().getDecorView();
        deco.post(new Runnable() {
            @Override
            public void run() {
                dumpInsets("now");
            }
        });
        // 首帧常常还是旧的，等一次 relayout 落定再报一次
        deco.postDelayed(new Runnable() {
            @Override
            public void run() {
                dumpInsets("settle");
            }
        }, 1200L);
    }

    @SuppressLint("NewApi")
    private void dumpInsets(String when) {
        try {
            View deco = getWindow().getDecorView();
            WindowInsets wi = deco.getRootWindowInsets();
            if (wi == null) {
                Log.d("AutoHideUI", "probe[" + when + "] rootWindowInsets=null");
                return;
            }
            Rect f = new Rect();
            deco.getWindowVisibleDisplayFrame(f);
            int sysTop = wi.getSystemWindowInsetTop();      // 老 API，24+ 都能用
            int stTop = -1, stIgnTop = -1, navBot = -1;
            if (Build.VERSION.SDK_INT >= 30) {
                stTop = wi.getInsets(WindowInsets.Type.statusBars()).top;
                stIgnTop = wi.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top;
                navBot = wi.getInsets(WindowInsets.Type.navigationBars()).bottom;
            }
            Log.d("AutoHideUI", "probe[" + when + "] full=" + fullCurrent()
                    + " sysTop=" + sysTop + " statusTop=" + stTop
                    + " statusIgn=" + stIgnTop + " navBot=" + navBot
                    + " visFrame=" + f.toShortString());
        } catch (Throwable t) {
            Log.d("AutoHideUI", "probe[" + when + "] err " + t);
        }
    }

    private boolean fullCurrent() {
        return fullModeCurrent() != 0;
    }

    @Override
    protected void onPause() {
        try {
            unregisterReceiver(rx);
        } catch (Throwable ignored) {
        }
        super.onPause();
    }

    private void registerRx() {
        try {
            IntentFilter f = new IntentFilter(Config.ACTION_STATE);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(rx, f);
            }
        } catch (Throwable ignored) {
        }
    }

    private void query() {
        sentAt = android.os.SystemClock.uptimeMillis();
        moduleAlive = false;
        setState("正在询问模块…", true);
        Intent i = new Intent(Config.ACTION_QUERY);
        try {
            sendBroadcast(i);
        } catch (Throwable t) {
            setState("发送广播失败：" + t, false);
            return;
        }
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!moduleAlive) {
                    setState("没收到模块回执 —— 请确认：LSPosed 里已启用本模块、"
                            + "作用域勾选了「系统框架」、并且重启过手机", false);
                }
            }
        }, 2000L);
    }

    private void send(String key, String value) {
        sentAt = android.os.SystemClock.uptimeMillis();
        moduleAlive = false;
        setState("正在应用…", true);
        Intent i = new Intent(Config.ACTION_SET);
        i.putExtra(Config.EXTRA_KEY, key);
        i.putExtra(Config.EXTRA_VALUE, value);
        try {
            sendBroadcast(i);
        } catch (Throwable t) {
            setState("发送失败：" + t, false);
            return;
        }
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!moduleAlive) {
                    setState("没收到模块回执 —— 改动可能没生效。"
                            + "确认模块已启用且重启过（或用 root 命令行改）", false);
                }
            }
        }, 2000L);
    }

    /**
     * 「软件关于」二级折叠菜单：默认只露出标题四个字，点击展开/收起。
     * <p>
     * 单独抽出来是因为状态栏隐藏下主界面本来就长，
     * 一大段说明常年铺在底部会挤掉真正要用的开关和状态。
     */
    private void setupAbout() {
        final View aboutHeader = findViewById(R.id.aboutHeader);
        final View aboutArrow = findViewById(R.id.aboutArrow);
        final View aboutBody = findViewById(R.id.aboutBody);
        final View aboutCard = findViewById(R.id.aboutCard);
        final ScrollView scroll = findViewById(R.id.scroll);
        if (aboutHeader == null || aboutBody == null) return;
        aboutHeader.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final boolean open = aboutBody.getVisibility() != View.VISIBLE;
                aboutBody.setVisibility(open ? View.VISIBLE : View.GONE);
                arrow(aboutArrow, open);
                if (open && scroll != null && aboutCard != null) {
                    scroll.post(new Runnable() {
                        @Override
                        public void run() {
                            scroll.smoothScrollTo(0, aboutCard.getBottom());
                        }
                    });
                }
            }
        });
    }

    /**
     * 「隐藏状态栏」右侧的 › 扩展键 —— 点开是二级菜单。
     * <p>
     * 主项是「状态栏显示时间」（第 1 次下滑唤出后停留多久）；
     * 更细的透明度 / 顶边吸收再折一层「进阶」，默认不摊开。
     */
    private void setupStatusSub() {
        if (sbArrow == null || sbBody == null) return;
        sbArrow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final boolean open = sbBody.getVisibility() != View.VISIBLE;
                sbBody.setVisibility(open ? View.VISIBLE : View.GONE);
                arrow(sbArrow, open);
                // 每次展开都重读一遍真机值，避免滑块停在旧位置
                if (open) {
                    if (sbTime != null) sbTime.setProgress(timeToStep(timeCurrentMs()));
                    if (sbAlpha != null) sbAlpha.setProgress(alphaCurrent());
                }
            }
        });

        /* ── 状态栏显示时间 ── */
        if (sbTime != null) {
            sbTime.setMax(T_MAX_STEP);
            sbTime.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    if (tvTimeVal != null) tvTimeVal.setText(timeText(stepToMs(progress)));
                }

                @Override
                public void onStartTrackingTouch(SeekBar bar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar bar) {
                    timeApply(stepToMs(bar.getProgress()));
                }
            });
            if (tvTimeVal != null) tvTimeVal.setText(timeText(stepToMs(sbTime.getProgress())));
        }

        hookPresetMs(R.id.btnT1, 2000);
        hookPresetMs(R.id.btnT2, 3500);
        hookPresetMs(R.id.btnT3, 5000);
        hookPresetMs(R.id.btnT4, 8000);

        /* ── 进阶折叠 ── */
        if (advHeader != null && advBody != null) {
            advHeader.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final boolean open = advBody.getVisibility() != View.VISIBLE;
                    advBody.setVisibility(open ? View.VISIBLE : View.GONE);
                    arrow(advArrow, open);
                }
            });
        }

        if (sbAlpha != null) {
            sbAlpha.setMax(A_MAX - A_MIN);
            sbAlpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    if (tvAlphaVal != null) tvAlphaVal.setText(alphaText(bar.getProgress() + A_MIN));
                }

                @Override
                public void onStartTrackingTouch(SeekBar bar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar bar) {
                    alphaApply(bar.getProgress() + A_MIN);
                }
            });
            if (tvAlphaVal != null) tvAlphaVal.setText(alphaText(sbAlpha.getProgress() + A_MIN));
        }

        // v2.2：「应用占满全屏」也折一层，展开才是 关闭 / 浮层 / 挤压 三选一
        setupFullMode();
    }

    /**
     * v2.3.1：「隐藏导航栏」右侧的 ⌄ 扩展键 —— 与「隐藏状态栏」那套保持同一套观感。
     * <p>
     * 三件事本来散在主界面上（背景颜色 / 软隐藏 / 呼出挤压），其实都只跟导航栏有关，
     * 折进这一层之后主界面短了一截，也更像"主开关 → 细节"的层级。
     */
    private void setupNavSub() {
        if (navArrow == null || navBody == null) return;
        navArrow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final boolean open = navBody.getVisibility() != View.VISIBLE;
                navBody.setVisibility(open ? View.VISIBLE : View.GONE);
                arrow(navArrow, open);
                // 展开时重读一遍真机值，免得开关停在旧状态、色块还是旧颜色
                if (open) {
                    if (swNavSoft != null) {
                        swNavSoft.setChecked(!"0".equals(current(Config.K_NAVSOFT, Config.P_NAVSOFT, "1")));
                    }
                    if (swNavSq != null) {
                        swNavSq.setChecked(!"0".equals(current(Config.K_NAVSQ, Config.P_NAVSQ, "1")));
                    }
                    if (swFullNav != null) {
                        swFullNav.setChecked(!"0".equals(current(Config.K_FULLNAV, Config.P_FULLNAV, "1")));
                    }
                    refreshNavColorRow();
                }
            }
        });
    }

    /* ══════════ 应用占满全屏：模式选择（v2.2） ══════════ */

    /**
     * 「应用占满全屏」的折叠 + 三选一。
     * <p>折起来只占一行，右边直接写着当前模式（关闭 / 浮层 / 挤压），
     * 点标题或箭头才摊开三个按钮 —— 主界面本来就长，能折的都折起来。
     */
    private void setupFullMode() {
        if (sbFullRow == null || sbFullBody == null) return;
        View.OnClickListener toggle = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final boolean open = sbFullBody.getVisibility() != View.VISIBLE;
                sbFullBody.setVisibility(open ? View.VISIBLE : View.GONE);
                arrow(sbFullArrow, open);
            }
        };
        sbFullRow.setOnClickListener(toggle);
        if (sbFullArrow != null) sbFullArrow.setOnClickListener(toggle);

        hookFullModeBtn(btnFullOff, 0);
        hookFullModeBtn(btnFullOverlay, 1);
        hookFullModeBtn(btnFullSq, 2);
    }

    private void hookFullModeBtn(Button b, final int mode) {
        if (b == null) return;
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fullMode = mode;
                send(Config.K_FULLMODE, String.valueOf(mode));
                // 老键 autohide_fullscreen 只在值不一致时同步一次：
                // 既照顾回退/命令行读老键的场景，又不至于每次都发两遍广播
                final String want = (mode == 0) ? "0" : "1";
                String old = current(Config.K_FULL, Config.P_FULL, "1");
                if (old == null || !want.equals(old.trim())) send(Config.K_FULL, want);
                paintFullMode();
            }
        });
    }

    /** 当前模式：优先读新键；没写过（老用户）就按老键 autohide_fullscreen 折算。 */
    private int fullModeCurrent() {
        String s = current(Config.K_FULLMODE, Config.P_FULLMODE, "");
        if (s != null && s.trim().length() > 0) {
            try {
                int v = (int) Long.parseLong(s.trim());
                if (v < 0) v = 0;
                if (v > 2) v = 2;
                return v;
            } catch (Throwable ignored) {
            }
        }
        return "0".equals(current(Config.K_FULL, Config.P_FULL, "1")) ? 0 : 1;
    }

    private void refreshFullModeUi() {
        fullMode = fullModeCurrent();
        paintFullMode();
    }

    private void paintFullMode() {
        if (tvFullMode != null) tvFullMode.setText(modeText(fullMode));
        if (tvFullNote != null) tvFullNote.setText(modeNote(fullMode));
        paintModeBtn(btnFullOff, fullMode == 0);
        paintModeBtn(btnFullOverlay, fullMode == 1);
        paintModeBtn(btnFullSq, fullMode == 2);
    }

    private String modeText(int m) {
        if (m == 0) return getString(R.string.full_mode_off);
        if (m == 2) return getString(R.string.full_mode_sq);
        return getString(R.string.full_mode_overlay);
    }

    private String modeNote(int m) {
        if (m == 0) return getString(R.string.full_off_note);
        if (m == 2) return getString(R.string.full_sq_note);
        return getString(R.string.full_overlay_note);
    }

    /** 选中的按钮用深蓝底白字，未选中的浅底蓝字。 */
    private void paintModeBtn(Button b, boolean on) {
        if (b == null) return;
        b.setBackgroundResource(on ? R.drawable.pill_on : R.drawable.pill_off);
        b.setTextColor(on ? 0xFFFFFFFF : 0xFF1565C0);
    }

    /* ══════════ 状态栏显示时间 ══════════ */

    /** 滑块格 → 毫秒：每格 250ms，0.5s 起步，最多 10s。 */
    private static int stepToMs(int step) {
        int ms = T_MIN_MS + step * 250;
        if (ms < T_MIN_MS) ms = T_MIN_MS;
        if (ms > T_MAX_MS) ms = T_MAX_MS;
        return ms;
    }

    private static int timeToStep(int ms) {
        int step = Math.round((ms - T_MIN_MS) / 250f);
        if (step < 0) step = 0;
        if (step > T_MAX_STEP) step = T_MAX_STEP;
        return step;
    }

    private static String timeText(int ms) {
        return String.format(Locale.US, "%.2f 秒", ms / 1000f);
    }

    private int timeCurrentMs() {
        int v = T_DEF_MS;
        try {
            v = Integer.parseInt(current(Config.K_TIME, Config.P_TIME, String.valueOf(T_DEF_MS)).trim());
        } catch (Throwable ignored) {
            v = T_DEF_MS;
        }
        if (v < T_MIN_MS) v = T_MIN_MS;
        if (v > T_MAX_MS) v = T_MAX_MS;
        return v;
    }

    private void timeApply(int ms) {
        int v = ms;
        if (v < T_MIN_MS) v = T_MIN_MS;
        if (v > T_MAX_MS) v = T_MAX_MS;
        if (sbTime != null) {
            int s = timeToStep(v);
            if (sbTime.getProgress() != s) sbTime.setProgress(s);
        }
        if (tvTimeVal != null) tvTimeVal.setText(timeText(v));
        send(Config.K_TIME, String.valueOf(v));
    }

    private void hookPresetMs(int id, final int ms) {
        View v = findViewById(id);
        if (v == null) return;
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View vv) {
                timeApply(ms);
            }
        });
    }

    /* ══════════ 进阶：隐藏透明度 ══════════ */

    /** 滑块当前该停在哪：读 Settings.Global / 属性 / 默认，并夹到量程内。 */
    private int alphaCurrent() {
        int v = A_DEF;
        try {
            v = Integer.parseInt(current(Config.K_GHOST, Config.P_GHOST, String.valueOf(A_DEF)).trim());
        } catch (Throwable ignored) {
            v = A_DEF;
        }
        if (v < A_MIN) v = A_MIN;
        if (v > A_MAX) v = A_MAX;
        return v;
    }

    /** 滑块停在 0 也没有意义（0 = 完全透明，等同于硬隐藏），所以下限抬到 A_MIN。 */
    private void alphaApply(int value) {
        int v = value;
        if (v < A_MIN) v = A_MIN;
        if (v > A_MAX) v = A_MAX;
        if (sbAlpha != null) {
            int s = v - A_MIN;
            if (sbAlpha.getProgress() != s) sbAlpha.setProgress(s);
        }
        if (tvAlphaVal != null) tvAlphaVal.setText(alphaText(v));
        send(Config.K_GHOST, String.valueOf(v));
    }

    private static String alphaText(int v) {
        return v + "\u2030";
    }

    /**
     * 折叠箭头：收起时是向下的 {@code ⌄}，展开时换成向右的 {@code ›}。
     * <p>
     * 不用 rotation 动画 —— 上一版用 {@code ›} 配合 rotate(-90)、展开时再 rotate(90)，
     * 属性动画会老老实实从 -90° 一路转到 +90°（绕过一整圈），箭头会乱转。
     * 直接换字符最直观，也省掉一次属性对象分配。
     */
    private static void arrow(View v, boolean open) {
        if (!(v instanceof TextView)) return;
        ((TextView) v).setText(open ? "\u203A" : "\u2304");
    }

    /**
     * 显示「模块实际在跑的版本」，跟 App 版本不一致时红字警示。
     * <p>
     * 这是"改了设置却不生效"的头号原因：模块只在 system_server 启动时加载一次，
     * 装了新 APK 不重启的话，内存里跑的还是旧代码 —— 旧代码压根不认识新加的配置键，
     * 于是界面上值改了、行为纹丝不动，用户完全无从判断。
     * <p>
     * 这里把版本差挑明，省掉一轮"是不是没保存 / 是不是坏了"的无效排查。
     */
    private void showModuleVersion(String modver) {
        if (tvModVer == null) return;
        if (modver == null || modver.length() == 0) {
            // 能走到这里说明**收到了回执**（没回执时这个方法压根不会被调用），
            // 但回执里没有版本字段 ⇒ 内存里跑的是 1.9.14 之前的旧代码。
            // 旧代码不认识新加的配置键，改动必然不生效 —— 这正是要提示的场景。
            tvModVer.setVisibility(View.VISIBLE);
            tvModVer.setTextColor(0xFFE65100);
            tvModVer.setText("模块在跑的是旧版本（不回传版本号）\n"
                    + "→ 装了新包后没重启，改动不会生效，请重启手机。");
            return;
        }
        final int bar = modver.indexOf('|');
        if (bar < 0) {
            tvModVer.setVisibility(View.GONE);
            return;
        }
        final String name = modver.substring(0, bar);
        int code = -1;
        try {
            code = Integer.parseInt(modver.substring(bar + 1).trim());
        } catch (Throwable ignored) {
        }

        final int myCode = appVersionCode();
        if (code >= 0 && myCode >= 0 && code != myCode) {
            tvModVer.setVisibility(View.VISIBLE);
            tvModVer.setTextColor(0xFFC62828);
            tvModVer.setText("模块在跑 " + name + "（build " + code + "），"
                    + "但 App 是 " + appVersionName() + "（build " + myCode + "）\n"
                    + "→ 改动不会生效，请重启手机让模块加载新代码。");
        } else {
            tvModVer.setVisibility(View.VISIBLE);
            tvModVer.setTextColor(0xFF2E7D32);
            tvModVer.setText("模块已加载 " + name + "（build " + code + "），与 App 版本一致");
        }
    }

    private int appVersionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private String appVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
            return "?";
        }
    }

    /**
     * 「状态栏背景颜色」那一行右侧的小色块 + 取值文字。
     * <p>
     * 调色板页是独立 Activity，回来时 onResume 会重跑，所以这里不需要额外回调。
     */
    private void refreshColorRow() {
        final int c = barColorCurrent();
        if (tvColorVal != null) {
            if (c == 0) {
                tvColorVal.setText("关闭");
            } else {
                tvColorVal.setText(String.format(Locale.US, "#%08X", c));
            }
        }
        if (colorSwatch != null) {
            try {
                if (colorSwatch.getBackground() instanceof GradientDrawable) {
                    GradientDrawable g = (GradientDrawable) colorSwatch.getBackground().mutate();
                    // 关闭时用浅灰表示"没有颜色"，免得白色和"没设置"分不清
                    g.setColor(c == 0 ? 0xFFDDDDDD : c);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 当前背景色（ARGB 十进制），0 = 关闭。存成十进制是为了能塞进 Settings.Global 的字符串。 */
    private int barColorCurrent() {
        try {
            String s = current(Config.K_BARCOLOR, Config.P_BARCOLOR, "0").trim();
            return (int) Long.parseLong(s);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** v2.1.2：刷新「导航栏背景颜色」那一行的色块与取值。 */
    private void refreshNavColorRow() {
        final int c = navColorCurrent();
        if (tvNavColorVal != null) {
            tvNavColorVal.setText(c == 0 ? "关闭" : String.format(Locale.US, "#%08X", c));
        }
        if (navColorSwatch != null) {
            try {
                if (navColorSwatch.getBackground() instanceof GradientDrawable) {
                    GradientDrawable g =
                            (GradientDrawable) navColorSwatch.getBackground().mutate();
                    g.setColor(c == 0 ? 0xFFDDDDDD : c);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** v2.1.2：当前导航栏背景色（ARGB 十进制），0 = 关闭。 */
    private int navColorCurrent() {
        try {
            String s = current(Config.K_NAVCOLOR, Config.P_NAVCOLOR, "0").trim();
            return (int) Long.parseLong(s);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 「当前生效配置」卡片：每次回执/回前台都重读一遍，保持和真机一致。 */
    private void refreshPolicy() {
        if (tvPolicy == null) return;
        tvPolicy.setText(line(Config.K_ENABLE, Config.P_ENABLE, "1", "总开关")
                + "\n  " + line(Config.K_STATUS, Config.P_STATUS, "1", "隐藏状态栏")
                + "\n  " + line(Config.K_NAV, Config.P_NAV, "1", "隐藏导航栏")
                + "\n  " + line(Config.K_HARD, Config.P_HARD, "0", "硬隐藏")
                + "\n  " + line(Config.K_TIME, Config.P_TIME, "3500", "状态栏显示时间ms")
                + "\n  " + line(Config.K_FULLMODE, Config.P_FULLMODE, "1", "顶部占满模式")
                + "\n  " + line(Config.K_FULL, Config.P_FULL, "1", "应用占满全屏(旧键)")
                + "\n  " + line(Config.K_FULLNAV, Config.P_FULLNAV, "1", "底部也占满")
                + "\n  " + line(Config.K_NAVSOFT, Config.P_NAVSOFT, "1", "导航栏软隐藏")
                + "\n  " + line(Config.K_NAVSQ, Config.P_NAVSQ, "1", "呼出时顶开内容")
                + "\n  " + line(Config.K_TOUCHBLOCK, Config.P_TOUCHBLOCK, "1", "隐藏时屏蔽触摸")
                + "\n  " + line(Config.K_ZEROEDGE, Config.P_ZEROEDGE, "1", "清其它顶/底内边距")
                + "\n  " + line(Config.K_FSMODE, Config.P_FSMODE, "11", "剥离挂点位掩码")
                + "\n  " + line(Config.K_BARCOLOR, Config.P_BARCOLOR, "0", "状态栏背景色")
                + "\n  " + line(Config.K_NAVCOLOR, Config.P_NAVCOLOR, "0", "导航栏背景色")
                + "\n  " + line(Config.K_COLORALWAYS, Config.P_COLORALWAYS, "0", "背景色常显")
                + "\n  " + line(Config.K_GHOST, Config.P_GHOST, "10", "隐藏透明度‰")
                + "\n  " + line(Config.K_ABSORB, Config.P_ABSORB, "0", "顶边吸收")
                + "\n  " + line(Config.K_BLK, Config.P_BLK, "", "黑名单"));
    }

    /** 关于卡片顶部的版本行 / build 号（直接从 PackageManager 读，不再写死）。 */
    private String versionText() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return "版本 " + pi.versionName + "  ·  build " + pi.versionCode;
        } catch (Throwable ignored) {
            return "版本未知";
        }
    }

    private void setState(String s, boolean good) {
        tvState.setText(s);
        tvState.setTextColor(good ? 0xFF2E7D32 : 0xFFC62828);
    }

    private String line(String key, String prop, String def, String desc) {
        String v = read(key);
        String src = "settings";
        if (v == null) {
            v = readProp(prop);
            if (v != null) src = "setprop";
        }
        if (v == null) {
            v = def;
            src = "默认";
        }
        return key + " = " + v + "  (" + desc + ", 来源:" + src + ")";
    }

    /** 当前生效值：Settings.Global 优先，其次系统属性，最后默认 */
    private String current(String key, String prop, String def) {
        String v = read(key);
        if (v == null) v = readProp(prop);
        return v == null ? def : v;
    }

    private String read(String key) {
        try {
            String s = Settings.Global.getString(getContentResolver(), key);
            return (s == null || s.length() == 0) ? null : s;
        } catch (Throwable t) {
            return null;
        }
    }

    private String readProp(String key) {
        String s = Config.getPropRaw(key, null);
        return (s == null || s.length() == 0) ? null : s;
    }
}
