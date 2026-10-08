package com.hidebar.lsp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.WindowManager;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * AutoHideBars —— 全局沉浸 LSPosed 模块（v6，Android 16 / 澎湃 OS3 适配）
 * <p>
 * ─────────────────────────────────────────────────────────────────────────
 * 作用域只需勾选「系统框架」（system_server，包名 android / 进程名 system）。
 * 本模块运行在 system_server 内，hook 服务端系统栏可见性决策链，对全部 App 生效。
 * <p>
 * ─────────────────────────────────────────────────────────────────────────
 * 【v6 修复的关键 Bug】
 * <p>
 * v5 已经能让 insets 层正确隐藏（dumpsys 里 InsetsSource visible=false），
 * 但屏幕上状态栏/导航栏依旧画着。反汇编 + 真机验证后定位到根因：
 * <p>
 * 服务端真正通知 SystemUI「隐藏栏窗口」的是：
 * <pre>
 *   InsetsPolicy$BarWindow.setVisible(boolean v)
 *     → int state = v ? WINDOW_STATE_SHOWING(0) : WINDOW_STATE_HIDDEN(2)
 *     → if (mState != state) { mState = state;
 *           DisplayPolicy.getStatusBarManagerInternal()
 *               .setWindowState(displayId, mId, state); }   // mId: 1=状态栏 2=导航栏
 * </pre>
 * SystemUI 侧由 StatusBarWindowStateController / NavigationBar.setWindowState 消费。
 * <p>
 * 问题：这一句有 <b>「状态没变就不发」</b> 的短路。开机时本模块在极早期就把
 * mState 从 0 置为 2，那一刻 SystemUI 的 IStatusBar 还没注册，binder 调用被丢弃，
 * 于是 mState 永远停在 2，之后再也不会重发 → SystemUI 始终认为栏是可见的。
 * （真机证据：dumpsys window displays 显示 InsetsPolicy status/nav 都是
 *  WINDOW_STATE_HIDDEN，但 dumpsys 的 visible windows 里 StatusBar /
 *  NavigationBar0 依然在列，截图也确认栏还在。）
 * <p>
 * v6 的两道修复：
 *   A. hook BarWindow.setVisible：强制参数为 false；若发现 mState 已经是 HIDDEN
 *      （说明本次通知会被短路丢弃），就把 mState 挪到 HIDING，逼它真的重发一次。
 *   B. 周期性「补发」：每 10s 对每个 InsetsPolicy 直接调
 *      StatusBarManagerInternal.setWindowState(displayId, id, WINDOW_STATE_HIDDEN)，
 *      这样即使 SystemUI 中途重启（重新注册 IStatusBar），也能立刻重新隐藏。
 * <p>
 * ─────────────────────────────────────────────────────────────────────────
 * 【Android 16 上已死的三条老路】（真机反汇编 + A/B 截图验证）
 * <p>
 * 1) SYSTEM_UI_FLAG_* —— WindowState 上已无 getSystemUiVisibility()，服务端不再读取。
 * 2) policy_control —— services.jar 中 PolicyControl 类已删除，无任何消费者。
 * 3) BarController —— 类已不存在，bar 显隐改由 Insets 管线负责。
 * <p>
 * ─────────────────────────────────────────────────────────────────────────
 * 【完整生效链路】
 * <pre>
 * 挂点1  WindowState.setRequestedVisibleTypes(int)   清掉状态栏/导航栏 bit
 *          → InsetsPolicy.updateSystemBars → updateBarControlTarget
 * 挂点2  WindowState.isRequestedVisible(int)         兜底返回 false
 * 挂点3  InsetsPolicy.areTypesForciblyShowing(int)   兜底返回 false
 * 挂点4  InsetsSourceProvider.updateClientVisibility → mClientVisible=false
 *          → updateVisibility(): visible = serverVisible && clientVisible = false
 *          → mSource.setVisible(false)   ⇒ App 侧 insets 归零，内容真正铺满全屏
 * 挂点5  InsetsPolicy$BarWindow.setVisible(boolean)  ← v6 新增，真正隐藏栏窗口
 * 补发   StatusBarManagerInternal.setWindowState(displayId,id,HIDDEN) ← v6 新增
 * </pre>
 * <p>
 * ─────────────────────────────────────────────────────────────────────────
 * 【配置】改动即时生效（最长 1s 缓存 / 最长 10s 补发周期），无需重启：
 * <p>
 * A. Settings.Global（推荐）
 *      autohide_enable  1/0   总开关          （默认 1）
 *      autohide_status  1/0   隐藏状态栏      （默认 1）
 *      autohide_nav     1/0   隐藏导航栏      （默认 1）
 *      autohide_hard    1/0   硬隐藏：连顶部下拉手势也禁止（默认 0）。
 *                          软隐藏(=0) 时状态栏隐藏但仍保留顶部下拉（桌面与所有 App 可用）。
 *      autohide_blk     字符串 黑名单包名，逗号分隔（默认空）
 *      autohide_direct_panel 1/0 顶部下拉是否"一步到位"直接展开通知面板（默认 0）
 *                          0 = 标准沉浸节奏：第 1 次下拉只唤出状态栏（约 3s 无操作自动收起），
 *                              第 2 次下拉才展开通知面板。
 *                          1 = 一次下拉直接展开通知面板。
 *      autohide_touch_gate 1/0 隐藏态是否让状态栏窗口"不可触摸"（默认 1，v1.9.10）。
 *                          1 = 第一次下滑触摸穿透、面板不会被拖出；由模块自己检测顶边下滑，
 *                              只把状态栏整条显示并停留 REVEAL_MS；窗口内再下滑才展开面板。
 *                          0 = 关闭门控（原始 MIUI 行为：触摸打到状态栏窗口，短拖回弹 peek、
 *                              稍长直接展开面板）。
 * <p>
 * B. 系统属性（root/setprop，作为 A 的兜底）
 *      persist.autohide.enable / persist.autohide.statusbar / persist.autohide.navbar
 *      persist.autohide.hard / persist.autohide.blk / persist.autohide.direct_panel
 * <p>
 * 注意：Settings.Global 的值优先于系统属性；两者都没写时用默认值。
 * <p>
 * 调试：logcat -s LSPosed-Bridge，或看 /data/adb/lspd/log/modules_*.log
 */
public class HookEntry implements IXposedHookLoadPackage {

    public static final String TAG = "AutoHideLSP";

    /* ── android.view.WindowInsets.Type 常量（值稳定，内联以免依赖） ── */
    /** WindowInsets.Type.statusBars() */
    private static final int TYPE_STATUS_BARS = 1;
    /** WindowInsets.Type.navigationBars() */
    private static final int TYPE_NAVIGATION_BARS = 2;

    /* ── InsetsPolicy$BarWindow 的 mId（构造时写死） ── */
    private static final int BAR_ID_STATUS = 1;
    private static final int BAR_ID_NAV = 2;

    /* ── android.app.StatusBarManager 的窗口状态常量 ── */
    private static final int WINDOW_STATUS_BAR = 1;
    private static final int WINDOW_NAV_BAR = 2;
    private static final int ST_SHOWING = 0;
    private static final int ST_HIDING = 1;
    private static final int ST_HIDDEN = 2;

    /* ── 系统栏窗口的 WindowManager.LayoutParams.type（真机实测值） ──
     * TYPE_STATUS_BAR            = 2000  (窗口名 StatusBar)
     * TYPE_NAVIGATION_BAR        = 2019  (窗口名 NavigationBar0)
     * TYPE_STATUS_BAR_ADDITIONAL = 2041  (窗口名 StatusBar1，澎湃多出来的一层)
     * 注意：TYPE_NOTIFICATION_SHADE = 2040 是独立窗口，不在本列表内，
     *       所以隐藏这三个表面不会连带干掉下拉通知面板。
     */
    private static final int WT_STATUS_BAR = 2000;
    private static final int WT_NAV_BAR = 2019;
    private static final int WT_STATUS_BAR_ADDITIONAL = 2041;
    /** TYPE_NOTIFICATION_SHADE，下拉通知面板本体（v1.9.9：用它判断"第一次下滑已开始"） */
    private static final int WT_NOTIFICATION_SHADE = 2040;

    /* ── 配置键（唯一来源在 Config，避免两处维护；常量会被 javac 内联） ── */
    public static final String K_ENABLE = Config.K_ENABLE;
    public static final String K_STATUS = Config.K_STATUS;
    public static final String K_NAV = Config.K_NAV;
    public static final String K_HARD = Config.K_HARD;
    public static final String K_BLK = Config.K_BLK;
    public static final String K_BARCOLOR = Config.K_BARCOLOR;
    public static final String K_FULL = Config.K_FULL;
    public static final String K_FULLMODE = Config.K_FULLMODE;
    public static final String K_COLORMODE = Config.K_COLORMODE;
    public static final String K_COLORALWAYS = Config.K_COLORALWAYS;
    public static final String K_NAVCOLOR = Config.K_NAVCOLOR;
    public static final String K_FSMODE = Config.K_FSMODE;
    public static final String K_NAVSOFT = Config.K_NAVSOFT;
    public static final String K_FULLNAV = Config.K_FULLNAV;
    public static final String K_NAVSQ = Config.K_NAVSQ;
    public static final String K_TOUCHBLOCK = Config.K_TOUCHBLOCK;
    public static final String K_ZEROEDGE = Config.K_ZEROEDGE;

    public static final String P_ENABLE = Config.P_ENABLE;
    public static final String P_STATUS = Config.P_STATUS;
    public static final String P_NAV = Config.P_NAV;
    public static final String P_HARD = Config.P_HARD;
    public static final String P_BLK = Config.P_BLK;
    public static final String P_BARCOLOR = Config.P_BARCOLOR;
    public static final String P_FULL = Config.P_FULL;
    public static final String P_FULLMODE = Config.P_FULLMODE;
    public static final String P_COLORMODE = Config.P_COLORMODE;
    public static final String P_COLORALWAYS = Config.P_COLORALWAYS;
    public static final String P_NAVCOLOR = Config.P_NAVCOLOR;
    public static final String P_FSMODE = Config.P_FSMODE;
    public static final String P_NAVSOFT = Config.P_NAVSOFT;
    public static final String P_FULLNAV = Config.P_FULLNAV;
    public static final String P_NAVSQ = Config.P_NAVSQ;
    public static final String P_TOUCHBLOCK = Config.P_TOUCHBLOCK;
    public static final String P_ZEROEDGE = Config.P_ZEROEDGE;

    private static final String PKG_SYSTEMUI = "com.android.systemui";
    /** v1.9.12：调 IStatusBarService.disable() 时用的调用方包名（system_server 自身）。 */
    private static final String PKG_ANDROID = "android";

    /** 补发周期 */
    private static final long RECONFIRM_MS = 10_000L;
    /** 同一个 BarWindow 强制重发的最小间隔 */
    private static final long RENOTIFY_MIN_MS = 2_000L;

    /* ── 全局状态 ── */
    private static volatile ClassLoader sCL;
    private static volatile Context sCtx;

    private static volatile long sCfgAt = 0L;
    private static volatile int sHiddenMask = 0;
    private static volatile boolean sHard = false;
    /**
     * v1.9.5：是否「一次下拉直接展开通知面板」。
     * 默认 false = 标准全屏沉浸节奏：
     *   第 1 次下拉 → 只唤出状态栏（无操作约 3s 自动收起）
     *   第 2 次下拉 → 展开通知面板
     * 需要"一步到位"时把它打开（`settings put global autohide_direct_panel 1`）。
     */
    private static volatile boolean sDirectPanel = false;
    private static volatile String sBlk = "";

    /**
     * 状态栏 InsetsSource 当前是否「可见」= 系统正在把状态栏显示出来
     * （下拉临时显示 / transient / 锁屏等）。软隐藏时据此决定要不要继续
     * 把 Surface 压成透明——被系统主动显示时就放行，否则下拉根本看不到。
     */
    private static volatile boolean sStatusRevealed = false;
    /** v2.1：导航栏 InsetsSource 当前是否「可见」（系统主动显示，如键盘弹起） */
    private static volatile boolean sNavSourceRevealed = false;

    /**
     * v1.9.7：「揭示窗口」截止时间（uptimeMillis）。系统瞬态唤出状态栏时被刷新。
     * 在窗口内 = 状态栏窗口可触摸（第二次下滑能展开面板）；
     * 窗口外 = 状态栏窗口加 NOT_TOUCHABLE，触摸穿透，只走系统瞬态路径。
     */
    private static volatile long sRevealUntil = 0L;

    /**
     * v1.9.7：是否启用「隐藏态状态栏窗口不可触摸」的门控（默认开）。
     * 可用 `settings put global autohide_touch_gate 0` 关闭以便对比排查。
     */
    private static volatile boolean sTouchGate = true;

    /**
     * 软隐藏时给状态栏 Surface 用的「几乎不可见但非 0」的 alpha。
     * <p>
     * 根因（v1.9.2）：alpha=0 会被输入系统判为 NOT_VISIBLE，
     * 该窗口即使 touchableRegion=[0,0][1116,104] 也不收触摸，
     * 顶部下拉被投递给下层 App ⇒ App 内无法下拉。
     * 取一个极小非零值：肉眼不可见，但保住输入窗口的「可见」属性。
     */
    private static final float GHOST_ALPHA = 0.01f;

    /**
     * v1.9.12：隐藏态 alpha 运行时可调（`autohide_ghost_alpha`，单位千分之一，默认 10 = 0.01）。
     * <p>
     * 真机结论（v1.9.12 实测，见 dbg/t5..t10）：本 ROM 的 InputDispatcher **按图层 alpha
     * 决定顶边 DOWN 的归属**，而 v1.9.7 起的 `NOT_TOUCHABLE` 输入门控其实是个 no-op：
     *   隐藏态 alpha=0.0100021 ⇒ 顶边下滑被判给下层 App（刷短视频被刷新，用户反馈的问题）
     *   揭示态 alpha=1         ⇒ 顶边下滑被判给 StatusBar1（SystemUI 吃掉，能开面板）
     * 两态的 `inputConfig` 与 `touchableRegion` **完全相同**，唯一变量就是 alpha。
     * ⇒ 想"吸收"顶边手势，必须把隐藏态 alpha 抬到阈值之上；这个开关用于实测最小可用值。
     */
    private static volatile float sGhostAlpha = GHOST_ALPHA;

    /**
     * v1.9.12：是否启用「吸收 + 压制」（默认 0 = 关）。
     * 吸收 = 顶边手势由 SystemUI 吃掉，App 收不到 ⇒ 短视频不再被误刷新；
     * 压制 = 隐藏态下发 `DISABLE_EXPAND`，让 SystemUI 吃到了也展不开面板，
     *        只由我们自己的 topPull 把状态栏画出来。
     * 依赖 sGhostAlpha 抬到阈值之上才成立。
     */
    private static volatile boolean sAbsorb = false;

    /** android.view.WindowManager / StatusBarManager.DISABLE_EXPAND（值稳定，内联）。 */
    private static final int DISABLE_EXPAND = 0x00010000;

    /** IStatusBarService 缓存 + 我们自己的 disable token */
    private static volatile Object sSbSvc = null;
    private static volatile boolean sSbSvcTried = false;
    private static volatile IBinder sDisableToken = null;
    /** 已下发的 disable 值：-1 未初始化 */
    private static volatile int sDisableApplied = -1;
    private static final AtomicInteger C_DISABLE = new AtomicInteger();

    /**
     * android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE（值稳定，内联）。
     * v1.9.7：隐藏态给状态栏窗口加这个 flag，让顶部下滑触摸"穿透"到下层，
     * 只触发系统手势的瞬态状态栏，而不会把状态栏窗口直接拖成通知面板。
     */
    private static final int FLAG_NOT_TOUCHABLE = 0x00000010;

    /**
     * v1.9.7：「揭示窗口」时长。状态栏被系统瞬时唤出后的这段时间内，
     * 状态栏窗口恢复可触摸 —— 这是"第二次下滑才展开通知面板"的依据。
     * 取值与系统瞬态状态栏自动收起时间（约 3s）接近。
     * <p>
     * v1.9.13：改成可在 App 二级菜单里调（{@code autohide_reveal_ms}，500~10000ms），
     * 这个常量只作默认值。
     */
    private static final long REVEAL_MS = 3500L;

    /** v1.9.13：揭示窗口时长（显示时间）运行时值，ms */
    private static volatile long sRevealMs = REVEAL_MS;

    /* ══════════════════════════════════════════════════════════════
     * v2.0 新增状态
     * ══════════════════════════════════════════════════════════════ */

    /**
     * v2.0：状态栏背景色（ARGB 十进制），0 = 关闭。
     * <p>
     * 状态栏本身是 SystemUI 画的透明窗口，改不了它的内容，所以做法是在状态栏
     * 图层**正下方**插一张我们自己的纯色图层（SurfaceControl color layer），
     * 只在「揭示窗口」内显示，给白色时间/图标垫一个底。
     */
    private static volatile int sBarColor = 0;
    /**
     * v2.0：颜色图层几何方式。0 = 只设 bufferSize；1 = bufferSize + crop。
     * <p>
     * SurfaceFlinger 的纯色图层没有 buffer，"尺寸认哪个字段"各版本有别，
     * 留成运行时可改的开关，省掉一轮"改代码+重启"。
     */
    private static volatile int sColorMode = 1;
    /** v2.0.3：颜色图层常显（揭示窗口之外也铺着）。 */
    private static volatile boolean sColorAlways = false;
    /**
     * v2.0：「应用占满全屏」。
     * <p>
     * 真机实测（NX721J / Android 16）：应用窗口的 frame **本来就是**
     * [0,0][1116,2480] 全屏，真正吃掉顶部那 104px 的是 App 自己按
     * InsetsState 里 STATUS_BARS 源加的内边距。所以只要把下发给**应用窗口**的
     * InsetsState 里那个源清掉，内容就会从 y=0 开始画。
     * <p>
     * 揭示状态栏时不还原 inset —— 状态栏直接「浮层」盖在内容上，内容不重排、不抖。
     */
    private static volatile boolean sFullscreen = true;
    /**
     * v2.2：顶部「占满全屏」的模式 —— 0 = 关闭，1 = 浮层式，2 = 挤压式（详见 {@link Config#K_FULLMODE}）。
     * <p>挤压式跟导航栏 {@link #K_NAVSQ} 是同一套：平时剥掉顶部 inset 让内容顶满，
     * 揭示期间把 inset 还回去 ⇒ App 重新留出状态栏的位置、内容被顶下来，
     * 状态栏以实体条出现，顶部内容不会被浮层盖住（代价是揭示时重排一下）。
     */
    private static volatile int sFullMode = 1;
    /** 顶部占满的「浮层式」：内容始终顶满，揭示也不还 inset。 */
    private static final int FM_OFF = 0;
    private static final int FM_OVERLAY = 1;
    private static final int FM_SQUEEZE = 2;
    /**
     * v2.0：「占满全屏」挂点位掩码 —— bit0=getter 组，bit1=fillInsetsState，
     * bit2=updateAboveInsetsState 就地剥，bit3=v2.3 允许就近剥 mFrozenInsetsState。
     * 默认 11 = getter + fill + frozen。见 {@link Config#K_FSMODE}。
     */
    private static volatile int sFsMode = 11;
    /**
     * v2.3：剥离时顺带清掉「别的贴边条带源」（tappableElement / mandatorySystemGestures …）。
     * <p>
     * 真机证据（QQ 顶部仍有 104px 白边，dumpsys 抓的同一时刻）：
     * <pre>
     *   type=statusBars              frame=[0,0][0,0]      visible=false   ← 我们剥过了
     *   type=tappableElement         frame=[0,0][1116,104] visible=true    ← 没剥，QQ 读的就是它
     *   type=mandatorySystemGestures frame=[0,0][1116,104] visible=true    ← 同上
     * </pre>
     * 所以只认 STATUS_BARS 一种源是不够的；判据改用几何（全宽 + 贴边）。
     */
    private static volatile boolean sZeroEdge = true;

    /**
     * v2.1.2：导航栏背景色（ARGB 十进制），0 = 关闭。
     * <p>和状态栏同一套做法：在导航栏图层**正下方**插一张纯色图层。
     * 导航栏呼出时是浮在 App 内容上的，背景全透明，纯白界面上三键就糊掉了；
     * 垫一层底色后任何界面都看得清。
     */
    private static volatile int sNavColor = 0;

    /* ── 颜色图层槽位 ──────────────────────────────────────────────
     * v2.1.2：状态栏 / 导航栏各一条，几何与创建逻辑完全一样，
     * 用槽位对象把三件套（图层 / 父层 / 是否在显示）收在一起，避免代码复制一份。
     */
    private static final class ColorLayer {
        final String name;
        Object sc;
        Object parentSc;
        boolean shown;

        ColorLayer(String name) {
            this.name = name;
        }
    }

    private static final ColorLayer L_STATUS = new ColorLayer("AutoHideBarColor");
    private static final ColorLayer L_NAV = new ColorLayer("AutoHideNavColor");

    private static volatile long sLastColorLogAt = 0L;
    private static final AtomicInteger C_COLOR = new AtomicInteger();
    /** Android 16 实测命中次数，用来确认 fillInsetsState 这条挂点选对了 */
    private static final AtomicInteger C_FULLSCREEN = new AtomicInteger();
    private static volatile long sLastFsLogAt = 0L;

    /** android.view.InsetsState.ITYPE_STATUS_BAR（值稳定，内联） */
    private static final int ITYPE_STATUS_BAR = 1;
    /** android.view.InsetsState.ITYPE_NAVIGATION_BAR（值稳定，内联） */
    private static final int ITYPE_NAV_BAR = 2;
    /** android.view.WindowManager.LayoutParams.FIRST_/LAST_APPLICATION_WINDOW */
    private static final int WT_FIRST_APP = 1;
    private static final int WT_LAST_APP = 99;

    /** v1.9.10：自定义"顶边下滑"检测——起手点必须在屏幕顶部这个高度内 */
    private static final float TOP_ZONE_Y = 120f;
    /** v1.9.10：纵向位移超过这个值才算"下滑"（避免点击误触） */
    private static final float TOP_TRIG_Y = 40f;

    /* ── v2.1：自定义"底边上滑"检测（唤出导航栏），与顶边那套对称 ── */
    /** 起手点必须落在屏幕底部这个高度内（px，物理像素） */
    private static final float BOTTOM_ZONE_H = 160f;
    /** 向上位移超过这个值才算"上滑"（避免点击误触） */
    private static final float BOTTOM_TRIG_Y = 40f;

    private static final AtomicInteger C_SETTER = new AtomicInteger();
    private static final AtomicInteger C_QUERY = new AtomicInteger();
    private static final AtomicInteger C_FORCE = new AtomicInteger();
    private static final AtomicInteger C_SERVER = new AtomicInteger();
    private static final AtomicInteger C_BAR = new AtomicInteger();
    private static final AtomicInteger C_NUDGE = new AtomicInteger();
    private static final AtomicInteger C_SURF = new AtomicInteger();
    private static final AtomicInteger C_TRAV = new AtomicInteger();
    private static final AtomicInteger C_VIS = new AtomicInteger();
    private static final AtomicInteger C_HIDE = new AtomicInteger();
    private static final AtomicInteger C_EXPAND = new AtomicInteger();
    /** v1.9.7：输入配置被加 NOT_TOUCHABLE 的次数 */
    private static final AtomicInteger C_GATE = new AtomicInteger();
    /** v1.9.7：「揭示窗口」被打开的次数 */
    private static final AtomicInteger C_REVEAL = new AtomicInteger();
    /** v1.9.7：打印输入门控挂钩签名的次数（只打前几次） */
    private static final AtomicInteger C_GATELOG = new AtomicInteger();
    /** v1.9.7：手势探针计数 */
    private static final AtomicInteger C_GPROBE = new AtomicInteger();
    private static final AtomicInteger C_GSWIPE = new AtomicInteger();
    /** v1.9.10：自定义顶边下滑检测命中次数 */
    private static final AtomicInteger C_TPULL = new AtomicInteger();

    /** v1.9.10：自定义顶边下滑检测的滑动窗口状态 */
    private static volatile boolean sDownInTop = false;
    private static volatile boolean sPullFired = false;
    private static volatile float sDownY = 0f;

    /** v1.9.10：KeyguardManager.isKeyguardLocked() 的缓存（避免频繁 IPC） */
    private static volatile boolean sKgVal = false;
    private static volatile long sKgAt = 0L;

    /** v1.9.7 调试：强制「已揭示」（验证渲染路径），config autohide_debug_reveal=1 开启 */
    private static volatile boolean sDebugReveal = false;

    /* ── v1.9.11：揭示渲染「执行器」 ──
     * 真机结论：软隐藏下状态栏图层完全靠 setAlpha 控制显隐，而 setAlpha 只在
     * WindowStateAnimator.prepareSurfaceLocked() 被调用时才下发。揭示窗口的开始/
     * 结束并不会让系统重跑一次（栏窗口没有动画、没有内容变化、mLastHidden 又被我们
     * 钉成 false）⇒ 日志里 reveal 在涨，屏幕上却什么都不出现，表现为"顶部下拉没反应"。
     * 这里把状态栏窗口的 SurfaceControl 存下来，由 200ms 看门狗在 revealed() 翻转的
     * 瞬间直接下发 alpha（再补一次 WMS 遍历兜底），不再依赖系统是否重绘。
     */
    private static volatile Object sStatusSc = null;
    private static volatile Object sStatusWs = null;
    /** v2.1：导航栏窗口的 SurfaceControl / WindowState（软隐藏渲染用） */
    private static volatile Object sNavSc = null;
    private static volatile Object sNavWs = null;
    private static volatile Class<?> sScTxnClass = null;
    private static volatile boolean sLastAppliedReveal = false;
    private static volatile boolean sLastAppliedNavReveal = false;
    private static volatile long sLastAlphaLogAt = 0L;
    private static volatile long sLastSurfLogAt = 0L;
    private static final AtomicInteger C_ALPHA = new AtomicInteger();

    /**
     * v2.1：导航栏「揭示窗口」截止时间。和状态栏分开记，因为底边上滑要能单独唤出导航栏，
     * 而状态栏揭示（下拉）时导航栏也要跟着一起出来（用户要"一块隐藏和显示"）。
     */
    private static volatile long sNavRevealUntil = 0L;
    /** v2.1：导航栏软隐藏（默认开）。关掉就退回旧的"真移除 Surface" */
    private static volatile boolean sNavSoft = true;
    /** v2.1：「占满全屏」连底部一起还给 App（默认开） */
    private static volatile boolean sFullNav = true;
    /** v2.1.1：导航栏呼出时把内容顶开（挤压式），默认开 */
    private static volatile boolean sNavSqueeze = true;
    /**
     * v2.1.1：隐藏态屏蔽系统栏触摸。0=关，1=只屏蔽导航栏（默认），2=状态栏+导航栏都屏蔽。
     * <p>软隐藏只压 alpha，窗口和 touchableRegion 都还在，于是"看不见但按得到" ——
     * 盲按三键位置照样返回桌面/切后台。这里在生成输入窗口信息时把区域清空，
     * 让隐藏态的点击穿透给下层 App。
     */
    private static volatile int sTouchBlock = 1;
    /** InputMonitor 实例（用来请求刷新输入窗口信息） */
    private static volatile Object sInputMonitor = null;
    /** NOT_TOUCHABLE 对应的 InputConfig 位，0 = 尚未探测 */
    private static volatile int sNotTouchableCfg = 0;
    /** v2.1：缓存的屏幕高度（物理 px），用于底边手势判定 */
    private static volatile int sScreenH = 0;
    /** v2.1：自定义底边上滑检测的滑动窗口状态 */
    private static volatile boolean sDownInBottom = false;
    private static volatile boolean sBottomPullFired = false;
    /** v2.1：底边上滑命中次数 */
    private static final AtomicInteger C_BPULL = new AtomicInteger();
    /** v2.1.1：主动通知 insets 变化的次数 / 屏蔽触摸的次数 */
    private static final AtomicInteger C_INSETS = new AtomicInteger();
    private static final AtomicInteger C_TOUCHBLOCK = new AtomicInteger();

    /** v1.9.11：本次揭示窗口内是否已出现「第二次下滑」的手势（决定是否该折叠面板） */
    private static volatile boolean sSecondPullSeen = false;
    /** 最近一次顶边下滑（markReveal）的时间 */
    private static volatile long sLastTopPullAt = 0L;

    /**
     * v1.9.9：peek 机制开关（默认开）。
     * 本 ROM 上顶边下滑唯一的路径是"触摸打到状态栏窗口 → SystemUI 拖动面板"，
     * 所以没法靠系统瞬态；改为在面板"刚冒头"的瞬间把它折回去，
     * 只让状态栏整条停留 REVEAL_MS；这段窗口内再下滑才放行面板。
     */
    private static volatile boolean sPeek = true;
    /** 上一次观察到的 NotificationShade 窗口可见性（用于检测"刚开始下滑"） */
    private static volatile boolean sShadeVisible = false;
    private static final AtomicInteger C_PEEK = new AtomicInteger();
    private static final AtomicInteger C_COLLAPSE = new AtomicInteger();
    /** 上次"下拉即展开面板"的时间（防抖，避免一次手势触发多次） */
    private static volatile long sLastExpandAt = 0L;
    private static volatile long sLogAt = 0L;

    /** 记录 int[3]：下标 1/2 分别对应状态栏/导航栏 BarWindow 上次强制重发时间 */
    private static final long[] sLastReNotify = new long[3];

    /** 收集到的 InsetsPolicy 实例（每个 DisplayContent 一个） */
    private static final Set<Object> sPolicies =
            Collections.synchronizedSet(new HashSet<Object>());

    /** WindowManagerService 实例（用于配置变化时强制请求重新布局） */
    private static volatile Object sWms;
    /** 上一次已经「落地」的 mask；变化时触发一次 WMS 遍历 */
    private static volatile int sAppliedMask = -1;
    /** 还没成功的遍历请求（sWms 尚未抓到时） */
    private static volatile boolean sPendingTraversal = false;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 只处理系统框架（system_server）
        if (!"android".equals(lpparam.packageName)) return;
        sCL = lpparam.classLoader;
        log("v6 loaded in system_server (Android " + android.os.Build.VERSION.SDK_INT
                + " / " + android.os.Build.VERSION.RELEASE
                + ", HyperOS " + getPropRaw("ro.miui.ui.version.name", "?") + ")");

        hookRequestedVisibleTypes(lpparam);
        hookAreTypesForciblyShowing(lpparam);
        hookUpdateSystemBars(lpparam);
        hookBarWindow(lpparam);
        hookHardHide(lpparam);
        hookBarSurface(lpparam);      // v7：把栏窗口的 Surface 真的藏掉
        hookPrepareSurface(lpparam);  // v7 兜底 + v1.9.7 软隐藏渲染
        // v1.9.6 起不再注册 hookSurfaceHide：拦截 hide() 会让状态栏图层一直"可拖"，
        // 破坏"第一次下拉只出状态栏"的节奏（v1.9.7 改由输入门控解决）。
        hookTransientExpand(lpparam);  // 软隐藏下：记录"揭示窗口"；direct_panel=1 时顺带直接开面板
        hookBarInputVisible(lpparam); // v1.9.1：恢复状态栏窗口输入区（修复 App 内下拉失效）
        hookSourceVisibility(lpparam); // v1.9.2：跟踪状态栏是否正被系统临时显示（决定要不要压 alpha）
        hookInputTouchGate(lpparam);   // v1.9.7：隐藏态让状态栏窗口不可触摸（默认关，见下）
        hookInputTouchBlock(lpparam);  // v2.1.1：隐藏态清空栏窗口触摸区（直接改 InputWindowHandle）
        hookShadeWatch(lpparam);       // v1.9.9：peek —— 第一次下滑只出状态栏并停留，第二次才出面板
        hookGestureProbe(lpparam);     // v1.9.7 诊断：确认系统顶边手势回调是否触发
        hookFullscreen(lpparam);       // v2.0：把应用窗口的「状态栏内边距」清掉，内容占满全屏
        hookDiagnostics(lpparam);
        hookSystemReady(lpparam);
        log("all hooks installed. props(snapshot): " + snapshot());
    }

    /* ══════════════════════════════════════════════════════════════
     * 挂点 1：WindowState.setRequestedVisibleTypes(int)
     *   把「窗口请求可见的类型」里的状态栏/导航栏 bit 清掉。
     *   这是整个方案的根：InsetsPolicy 最终会把焦点窗口当作系统栏的
     *   InsetsControlTarget，从而 isRequestedVisible() 返回 false。
     * ══════════════════════════════════════════════════════════════ */
    private void hookRequestedVisibleTypes(final XC_LoadPackage.LoadPackageParam lp) {
        Class<?> ws;
        try {
            ws = XposedHelpers.findClass("com.android.server.wm.WindowState", lp.classLoader);
        } catch (Throwable t) {
            log("!! WindowState not found: " + t);
            return;
        }
        XC_MethodHook before = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    refreshConfig();
                    final int mask = sHiddenMask;
                    if (mask == 0 || param.args.length == 0) return;
                    Object a0 = param.args[0];
                    if (!(a0 instanceof Integer)) return;
                    int types = (Integer) a0;
                    if ((types & mask) == 0) return;
                    if (isExcluded(param.thisObject)) return;
                    param.args[0] = types & ~mask;
                    C_SETTER.incrementAndGet();
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            Set<?> unhooks = XposedBridge.hookAllMethods(ws, "setRequestedVisibleTypes", before);
            log("hook setRequestedVisibleTypes -> " + unhooks.size() + " overload(s)");
        } catch (Throwable t) {
            log("!! setRequestedVisibleTypes hook failed: " + t);
        }
    }

    /* ══════════════════════════════════════════════════════════════
     * 挂点 2：WindowState.isRequestedVisible(int)
     *   查询侧兜底。WindowState.<init> 里字段是直接 iput 初始化的（不走 setter），
     *   所以必须在“查询侧”再补一刀。
     * ══════════════════════════════════════════════════════════════ */
    private void hookIsRequestedVisible(final XC_LoadPackage.LoadPackageParam lp) {
        Class<?> ws;
        try {
            ws = XposedHelpers.findClass("com.android.server.wm.WindowState", lp.classLoader);
        } catch (Throwable t) {
            log("!! WindowState not found for isRequestedVisible: " + t);
            return;
        }
        try {
            Set<?> u = XposedBridge.hookAllMethods(ws, "isRequestedVisible", mQueryHook);
            log("hook isRequestedVisible -> " + u.size());
        } catch (Throwable t) {
            log("!! isRequestedVisible hook failed: " + t);
        }
    }

    /** 查询侧兜底（挂点 2）。 */
    private final XC_MethodHook mQueryHook = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                refreshConfig();
                final int mask = sHiddenMask;
                if (mask == 0 || param.args.length == 0) return;
                Object a0 = param.args[0];
                if (!(a0 instanceof Integer)) return;
                if (((Integer) a0 & mask) == 0) return;
                if (isExcluded(param.thisObject)) return;
                if (Boolean.TRUE.equals(param.getResult())) {
                    param.setResult(Boolean.FALSE);
                    C_QUERY.incrementAndGet();
                }
            } catch (Throwable ignored) {
            }
        }
    };

    /* ══════════════════════════════════════════════════════════════
     * 挂点 3：InsetsPolicy.areTypesForciblyShowing(int)
     *   系统可能因“强制显示导航栏 / HOME 桌面 / 遥控模式”等把栏钉住，
     *   这里让被隐藏的栏类型不再算“被强制显示”。
     * ══════════════════════════════════════════════════════════════ */
    private void hookAreTypesForciblyShowing(final XC_LoadPackage.LoadPackageParam lp) {
        Class<?> ip;
        try {
            ip = XposedHelpers.findClass("com.android.server.wm.InsetsPolicy", lp.classLoader);
        } catch (Throwable t) {
            log("!! InsetsPolicy not found: " + t);
            return;
        }
        XC_MethodHook after = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    refreshConfig();
                    final int mask = sHiddenMask;
                    if (mask == 0 || param.args.length == 0) return;
                    Object a0 = param.args[0];
                    if (!(a0 instanceof Integer)) return;
                    if (((Integer) a0 & mask) == 0) return;
                    if (Boolean.TRUE.equals(param.getResult())) {
                        param.setResult(Boolean.FALSE);
                        C_FORCE.incrementAndGet();
                    }
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            Set<?> u = XposedBridge.hookAllMethods(ip, "areTypesForciblyShowing", after);
            log("hook areTypesForciblyShowing -> " + u.size());
        } catch (Throwable t) {
            log("!! areTypesForciblyShowing hook failed: " + t);
        }
        hookIsRequestedVisible(lp);
    }

    /* ══════════════════════════════════════════════════════════════
     * 挂点 4：InsetsPolicy.updateSystemBars(WindowState, boolean, boolean)
     *   顺带把 InsetsPolicy 实例收进来，供周期性补发使用。
     * ══════════════════════════════════════════════════════════════ */
    private void hookUpdateSystemBars(final XC_LoadPackage.LoadPackageParam lp) {
        Class<?> ip;
        try {
            ip = XposedHelpers.findClass("com.android.server.wm.InsetsPolicy", lp.classLoader);
        } catch (Throwable t) {
            log("!! InsetsPolicy not found for updateSystemBars: " + t);
            return;
        }
        XC_MethodHook before = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    refreshConfig();
                    if (sHiddenMask == 0) return;
                    sPolicies.add(param.thisObject);
                    // 本 ROM 的 updateSystemBars 里 arg1(forceShowSystemBars) 并未被使用，
                    // 保留这刀只为兼容其他 ROM / 后续版本。
                    if (param.args.length >= 2 && param.args[1] instanceof Boolean
                            && Boolean.TRUE.equals(param.args[1])) {
                        param.args[1] = Boolean.FALSE;
                    }
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            Set<?> u = XposedBridge.hookAllMethods(ip, "updateSystemBars", before);
            log("hook updateSystemBars -> " + u.size());
        } catch (Throwable t) {
            log("!! updateSystemBars hook failed: " + t);
        }
    }

    /* ══════════════════════════════════════════════════════════════
     * 挂点 5（v6 核心）：InsetsPolicy$BarWindow.setVisible(boolean)
     *
     *   void setVisible(boolean visible) {
     *       int state = visible ? WINDOW_STATE_SHOWING : WINDOW_STATE_HIDDEN;
     *       if (mState != state) {                    // ← 短路在这里
     *           mState = state;
     *           getStatusBarManagerInternal().setWindowState(displayId, mId, state);
     *       }
     *   }
     *
     *   这里做两件事：
     *     1. 恒置 visible=false（我们就是要隐藏）；
     *     2. 如果 mState 已经是 HIDDEN，说明本次调用会被短路丢弃，把 mState
     *        临时挪到 HIDING，强迫它真的重发一次 setWindowState。
     * ══════════════════════════════════════════════════════════════ */
    private void hookBarWindow(final XC_LoadPackage.LoadPackageParam lp) {
        Class<?> bw;
        try {
            bw = XposedHelpers.findClass("com.android.server.wm.InsetsPolicy$BarWindow",
                    lp.classLoader);
        } catch (Throwable t) {
            log("!! InsetsPolicy$BarWindow not found: " + t);
            return;
        }
        XC_MethodHook before = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    refreshConfig();
                    final int mask = sHiddenMask;
                    if (mask == 0) return;
                    final int id = XposedHelpers.getIntField(param.thisObject, "mId");
                    final int bit = (id == BAR_ID_STATUS) ? TYPE_STATUS_BARS
                            : (id == BAR_ID_NAV) ? TYPE_NAVIGATION_BARS : 0;
                    if (bit == 0 || (mask & bit) == 0) return;
                    // 软隐藏状态栏：不要逼 SystemUI 把栏设为 HIDDEN，否则它停用手势/下拉。
                    // 放行系统自身逻辑（保持 SHOWING，透明由 Surface alpha 负责），
                    // 这样顶部下拉手势在桌面与所有 App 中都可用。
                    // v2.1：导航栏软隐藏时同状态栏处理 —— 保持 SHOWING。
                    // 之前这里对导航栏一律 FALSE，InsetsPolicy/SystemUI 认为它 HIDDEN，
                    // 于是底边手势没人接，「上滑出不来」。
                    if (!sHard && (bit == TYPE_STATUS_BARS
                            || (bit == TYPE_NAVIGATION_BARS && sNavSoft))) {
                        // 软隐藏：保持栏窗口 SHOWN，只靠 Surface alpha=0 做视觉隐藏，
                        // 这样窗口仍保留输入区，下拉/上滑手势在桌面与所有 App 都可用。
                        param.args[0] = Boolean.TRUE;
                        if (XposedHelpers.getIntField(param.thisObject, "mState") != ST_SHOWING) {
                            XposedHelpers.setIntField(param.thisObject, "mState", ST_SHOWING);
                        }
                        C_BAR.incrementAndGet();
                        return;
                    }
                    param.args[0] = Boolean.FALSE;
                    C_BAR.incrementAndGet();

                    if (XposedHelpers.getIntField(param.thisObject, "mState") == ST_HIDDEN) {
                        final long now = SystemClock.uptimeMillis();
                        if (now - sLastReNotify[id] >= RENOTIFY_MIN_MS) {
                            sLastReNotify[id] = now;
                            // 挪开状态，逼内部逻辑真的走一次 setWindowState
                            XposedHelpers.setIntField(param.thisObject, "mState", ST_HIDING);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            Set<?> u = XposedBridge.hookAllMethods(bw, "setVisible", before);
            log("hook InsetsPolicy$BarWindow.setVisible -> " + u.size());
        } catch (Throwable t) {
            log("!! BarWindow.setVisible hook failed: " + t);
        }
    }

    /* ══════════════════════════════════════════════════════════════
     * 挂点 7（v7 核心）：WindowState.isOnScreen()
     *
     *   v6 之后真机实测：dumpsys 里三个栏窗口都已经是 isVisible=false
     *   （InsetsSource 也 visible=false，App 确实拿到零 insets），
     *   但屏幕上栏依然画着——因为：
     *
     *       Surface: shown=true    mDrawState=HAS_DRAWN    mLastHidden=false
     *
     *   也就是「逻辑不可见、Surface 却还在被合成」。追到
     *   WindowStateAnimator.prepareSurfaceLocked(SurfaceControl.Transaction)：
     *
     *       if (!hasSurface()) return;
     *       if (!mWin.isOnScreen()) { hide(t, "prepareSurfaceLocked"); return; }
     *       // 只有 mLastHidden 时才 showRobustly(t)
     *
     *   ⇒ 表面显隐的唯一开关就是 WindowState.isOnScreen()。
     *     只要让栏窗口的 isOnScreen() 返回 false，系统自己就会走 hide()，
     *     并且正确维护 mLastHidden，关掉开关时还能自己恢复（showRobustly）。
     * ══════════════════════════════════════════════════════════════ */
    private void hookBarSurface(final XC_LoadPackage.LoadPackageParam lp) {
        Class<?> ws;
        try {
            ws = XposedHelpers.findClass("com.android.server.wm.WindowState", lp.classLoader);
        } catch (Throwable t) {
            log("!! WindowState not found for isOnScreen: " + t);
            return;
        }
        XC_MethodHook before = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    captureWms(param.thisObject);
                    refreshConfig();
                    final int mask = sHiddenMask;
                    if (mask == 0) return;
                    final int type = windowType(param.thisObject);
                    if (type == 0) return;
                    boolean isStatus = (type == WT_STATUS_BAR || type == WT_STATUS_BAR_ADDITIONAL);
                    boolean isNav = (type == WT_NAV_BAR);
                    if (!isStatus && !isNav) return;
                    if (isStatus && (mask & TYPE_STATUS_BARS) == 0) return;
                    if (isNav && (mask & TYPE_NAVIGATION_BARS) == 0) return;
                    // 软隐藏状态栏：保留栏窗口 on-screen（Surface 不移除 → 输入照常派发），
                    // 真正的“看不见”交给 prepareSurface 的 alpha=0，从而顶部下拉手势不失效。
                    // 仅硬隐藏(sHard)才强制 isOnScreen=false 真正移除 Surface。
                    if (isStatus && !sHard) return;
                    // v2.1：导航栏同样改为软隐藏。之前硬隐藏会真的移除 Surface，
                    // 底边上滑手势因此没人接 —— 这正是「上滑根本出不来」的根因。
                    if (isNav && sNavSoft && !sHard) return;
                    param.setResult(Boolean.FALSE);
                    C_SURF.incrementAndGet();
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            Set<?> u = XposedBridge.hookAllMethods(ws, "isOnScreen", before);
            log("hook WindowState.isOnScreen -> " + u.size());
        } catch (Throwable t) {
            log("!! isOnScreen hook failed: " + t);
        }
    }

    /* ══════════════════════════════════════════════════════════════
     * 挂点 8（v7 兜底）：WindowStateAnimator.prepareSurfaceLocked
     *   万一某次编译把 isOnScreen() 内联了，上面那刀就打空。
     *   这里在原始逻辑跑完之后，对栏窗口直接 t.hide(mSurfaceControl)，
     *   并同步把 mLastHidden 置 true（这样关掉开关时系统会自己 showRobustly 恢复）。
     * ══════════════════════════════════════════════════════════════ */
    private void hookPrepareSurface(final XC_LoadPackage.LoadPackageParam lp) {
        Class<?> wsa;
        try {
            wsa = XposedHelpers.findClass("com.android.server.wm.WindowStateAnimator",
                    lp.classLoader);
        } catch (Throwable t) {
            log("!! WindowStateAnimator not found: " + t);
            return;
        }
        XC_MethodHook after = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    refreshConfig();
                    final int mask = sHiddenMask;
                    if (mask == 0) return;
                    Object win = XposedHelpers.getObjectField(param.thisObject, "mWin");
                    if (win == null) return;
                    final int type = windowType(win);
                    if (type == 0) return;
                    boolean isStatus = (type == WT_STATUS_BAR || type == WT_STATUS_BAR_ADDITIONAL);
                    boolean isNav = (type == WT_NAV_BAR);
                    if (!isStatus && !isNav) return;
                    if (isStatus && (mask & TYPE_STATUS_BARS) == 0) return;
                    if (isNav && (mask & TYPE_NAVIGATION_BARS) == 0) return;
                    Object sc = XposedHelpers.getObjectField(param.thisObject, "mSurfaceControl");
                    Object t = param.args.length > 0 ? param.args[0] : null;
                    if (sc == null || t == null) return;
                    if (isNav && sNavSoft && !sHard) {
                        // v2.1：导航栏软隐藏 —— 与状态栏同一套：图层保持 SHOWN，
                        // 每帧显式 setAlpha（revealedNav → 1 真的浮出来，否则 ghost）。
                        XposedHelpers.callMethod(t, "show", sc);
                        XposedHelpers.setBooleanField(param.thisObject, "mLastHidden", false);
                        sNavSc = sc;
                        sNavWs = win;
                        if (sScTxnClass == null) sScTxnClass = t.getClass();
                        final boolean rn = revealedNav();
                        final float na = rn ? 1.0f : sGhostAlpha;
                        XposedHelpers.callMethod(t, "setAlpha", sc, Float.valueOf(na));
                        // v2.1.2：导航栏背景色图层（和状态栏同一笔 Transaction）
                        applyNavColor(t, sc, win);
                        final long now = SystemClock.uptimeMillis();
                        if (now - sLastSurfLogAt > 900L) {
                            sLastSurfLogAt = now;
                            log("surf bar type=" + type + " (nav soft) revealedNav=" + rn
                                    + " alpha=" + na + " sinceNavReveal=" + (sNavRevealUntil - now));
                        }
                    } else if (isStatus && !sHard) {
                        // 软隐藏状态栏（v1.9.7 定稿）：
                        //  真机对比结论：
                        //   · v1.9.3 额外做 t.show(sc) / show(动画 leash) / 拦截 hide()，图层一直"可拖"，
                        //     一次长下拉就被 SystemUI 直接拖成通知面板 —— 但那不是 show 的错，
                        //     是"窗口一直可触摸"的错（v1.9.7 改用输入门控解决，见 hookInputTouchGate）。
                        //   · v1.9.6 把 show / mLastHidden=false 全删掉、只留 alpha，结果系统认为
                        //     mLastAlpha==mShownAlpha 不再下发 setAlpha，图层永远停在我们上帧压的
                        //     GHOST alpha 上 ⇒ **瞬态状态栏也看不见了**（短扫无反应）。
                        //  正确做法：图层保持 shown（这样瞬态时能画出来）+ 每帧显式设 alpha：
                        //     · 系统正在揭示（sStatusRevealed）或刚被下拉揭示（sRevealUntil 内）→ alpha=1，真的画出来
                        //     · 否则 → alpha=GHOST_ALPHA，肉眼不可见
                        //  每帧都显式 setAlpha 是关键：不依赖系统的"值没变就不发"短路。
                        XposedHelpers.callMethod(t, "show", sc);
                        XposedHelpers.setBooleanField(param.thisObject, "mLastHidden", false);
                        // v1.9.11：把状态栏图层与 Transaction 类记下来，供看门狗直接下发 alpha
                        sStatusSc = sc;
                        sStatusWs = win;
                        if (sScTxnClass == null) sScTxnClass = t.getClass();
                        final boolean rv = revealed();
                        final float alpha = rv ? 1.0f : sGhostAlpha;
                        XposedHelpers.callMethod(t, "setAlpha", sc, Float.valueOf(alpha));
                        // v2.0：背景色图层（同一笔 Transaction，和 alpha 一起落地）
                        applyBarColor(t, sc, win);
                        final long now = SystemClock.uptimeMillis();
                        if (now - sLastSurfLogAt > 900L) {
                            sLastSurfLogAt = now;
                            log("surf bar type=" + type + " revealed=" + rv + " alpha=" + alpha
                                    + " sinceReveal=" + (sRevealUntil - now));
                        }
                    } else {
                        // 硬隐藏、或软隐藏被关掉的导航栏：真正移除 Surface
                        XposedHelpers.callMethod(t, "hide", sc);
                        XposedHelpers.setBooleanField(param.thisObject, "mLastHidden", true);
                    }
                    C_SURF.incrementAndGet();
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            Set<?> u = XposedBridge.hookAllMethods(wsa, "prepareSurfaceLocked", after);
            log("hook WindowStateAnimator.prepareSurfaceLocked -> " + u.size());
        } catch (Throwable t) {
            log("!! prepareSurfaceLocked hook failed: " + t);
        }
    }

    /* ══════════════════════════════════════════════════════════════
     * 挂点 11（v1.9.3 引入，★v1.9.6 起已停用★）：拦截「把状态栏图层隐藏」的调用
     *
     *   停用原因：本挂点会让状态栏图层始终处于"可被拖动"的可见态，
     *   导致一次长下拉就被 SystemUI 直接展开成通知面板，
     *   破坏「第 1 次下拉只出状态栏 → 自动收起 → 第 2 次下拉出面板」的沉浸节奏。
     *   真机复现：`input swipe 558 1 558 400 200` → focus 直接变 NotificationShade。
     *   保留代码仅供排查，不再注册。
     * ══════════════════════════════════════════════════════════════ */
    private void hookSurfaceHide(final XC_LoadPackage.LoadPackageParam lp) {
        final XC_MethodHook block = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    refreshConfig();
                    final int mask = sHiddenMask;
                    if (mask == 0 || sHard) return;
                    if ((mask & TYPE_STATUS_BARS) == 0) return;
                    Object sc = (param.args.length > 0 && param.args[0] instanceof android.view.SurfaceControl)
                            ? param.args[0] : (param.args.length == 0 ? param.thisObject : null);
                    if (sc == null) return;
                    Object n = XposedHelpers.callMethod(sc, "getName");
                    if (!(n instanceof String)) return;
                    if (((String) n).indexOf("StatusBar") < 0) return;
                    final int c = C_HIDE.incrementAndGet();
                    if (c <= 20) log("blocked hide() on statusbar surface: " + n);
                    // Transaction.hide 返回 this（链式），SurfaceControl.hide 返回 void
                    if (param.args.length > 0) param.setResult(param.thisObject);
                    else param.setResult(null);
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            Class<?> txn = XposedHelpers.findClass("android.view.SurfaceControl$Transaction",
                    lp.classLoader);
            Set<?> a = XposedBridge.hookAllMethods(txn, "hide", block);
            log("hook SurfaceControl$Transaction.hide -> " + a.size());
        } catch (Throwable t) {
            log("!! Transaction.hide hook failed: " + t);
        }
        try {
            Class<?> sc = XposedHelpers.findClass("android.view.SurfaceControl", lp.classLoader);
            Set<?> b = XposedBridge.hookAllMethods(sc, "hide", block);
            log("hook SurfaceControl.hide -> " + b.size());
        } catch (Throwable t) {
            log("!! SurfaceControl.hide hook failed: " + t);
        }
    }

    /* ══════════════════════════════════════════════════════════════
     * 挂点 12（v1.9.4 核心）：软隐藏下「首次顶部下拉直接展开通知面板」
     *
     *   现象：状态栏隐藏后，从顶边下拉只会触系统的「瞬态状态栏」——
     *   状态栏闪现 3 秒，必须再滑一次才展开通知面板。原因是隐藏状态下
     *   状态栏图层被 SF 判为 invisible，输入窗口被打上 NOT_VISIBLE，
     *   触摸穿透到 App，SystemUI 的状态栏窗口收不到这次拖动。
     *
     *   修复：直接挂在系统的「请求瞬态状态栏」入口上——
     *      · InsetsPolicy.showTransient(...)      （真正执行瞬态显示）
     *      · DisplayPolicy.requestTransientBars() （顶边手势的上游入口，兜底）
     *   命中状态栏类型时，紧接着调用状态栏服务 expandNotificationsPanel()，
     *   于是"一次下拉 = 通知面板直接展开"，与桌面行为一致。
     *
     *   仅软隐藏状态栏时生效；硬隐藏/关闭模块不介入；900ms 防抖。
     * ══════════════════════════════════════════════════════════════ */
    private void hookTransientExpand(final XC_LoadPackage.LoadPackageParam lp) {
        // ① InsetsPolicy.showTransient(<statusBars bit>) —— 真正执行瞬态显示
        final XC_MethodHook byTypes = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    boolean hit = false;
                    for (Object a : param.args) {
                        if (a instanceof Integer && (((Integer) a) & TYPE_STATUS_BARS) != 0) hit = true;
                    }
                    if (!hit) return;
                    // v1.9.7：系统正在瞬态唤出状态栏 —— 打开"揭示窗口"，
                    // 期间状态栏窗口恢复可触摸（第二次下滑才展开面板）。
                    markReveal("showTransient");
                    if (!expandEnabled()) return;
                    fireExpand("showTransient");
                } catch (Throwable ignored) {
                }
            }
        };
        // ② DisplayPolicy.requestTransientBars(WindowState swipeTarget) —— 顶边手势上游，兜底
        final XC_MethodHook byGesture = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (param.args.length != 1 || param.args[0] == null) return;
                    final int type = windowType(param.args[0]);
                    // 只认主状态栏窗（顶部下拉）；导航栏手势不处理
                    if (type != WT_STATUS_BAR && type != WT_STATUS_BAR_ADDITIONAL) return;
                    markReveal("requestTransientBars");
                    if (!expandEnabled()) return;
                    fireExpand("requestTransientBars");
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            Class<?> ip = XposedHelpers.findClass("com.android.server.wm.InsetsPolicy", lp.classLoader);
            Set<?> u = XposedBridge.hookAllMethods(ip, "showTransient", byTypes);
            log("hook InsetsPolicy.showTransient -> " + u.size());
        } catch (Throwable t) {
            log("!! showTransient hook failed: " + t);
        }
        try {
            Class<?> dp = XposedHelpers.findClass("com.android.server.wm.DisplayPolicy", lp.classLoader);
            Set<?> u = XposedBridge.hookAllMethods(dp, "requestTransientBars", byGesture);
            log("hook DisplayPolicy.requestTransientBars -> " + u.size());
        } catch (Throwable t) {
            log("!! requestTransientBars hook failed: " + t);
        }
    }

    /** 软隐藏状态栏、且显式开启了"一步到位"时才启用"下拉即展开面板"（默认关闭）。 */
    private static boolean expandEnabled() {
        refreshConfig();
        if (!sDirectPanel) return false;
        return sHiddenMask != 0 && !sHard && (sHiddenMask & TYPE_STATUS_BARS) != 0;
    }

    /* ══════════════════════════════════════════════════════════════
     * v1.9.7 核心：揭示状态
     *
     *   revealed() = 「状态栏此刻应当可见/可交互」
     *     · sStatusRevealed     —— 系统 InsetsSource 正在显示状态栏（下拉瞬态 / 面板展开 / 锁屏）
     *     · sRevealUntil 未过期 —— 刚刚被顶边手势唤出（兜底，避免 sStatusRevealed 更新延迟）
     *
     *   这个值同时决定两件事：
     *     1) 状态栏 Surface 的 alpha：revealed → 1（真的画出来），否则 → GHOST（不可见）
     *     2) 状态栏窗口是否可触摸（见 hookInputTouchGate）
     *        —— 隐藏态不可触摸 ⇒ 第一次下滑触摸穿透，只触发系统瞬态状态栏；
     *        —— 揭示态可触摸 ⇒ 第二次下滑才把面板拖出来。
     * ══════════════════════════════════════════════════════════════ */
    private static boolean revealed() {
        if (sDebugReveal) return true;
        if (sStatusRevealed) return true;
        return SystemClock.uptimeMillis() < sRevealUntil;
    }

    /**
     * v2.1：导航栏此刻是否该显示。
     * <p>跟状态栏"一块隐藏和显示"：状态栏被揭示（下拉/系统瞬态）时导航栏一起出来；
     * 另外底边上滑会开一段**只属于导航栏**的揭示窗口，可以单独唤出它。
     */
    private static boolean revealedNav() {
        if (sDebugReveal) return true;
        if ((sHiddenMask & TYPE_NAVIGATION_BARS) == 0) return true;   // 根本没隐藏 → 交给系统
        if (sNavSourceRevealed) return true;                          // 系统自己显示它
        if (revealed()) return true;                                  // 跟随状态栏
        return SystemClock.uptimeMillis() < sNavRevealUntil;
    }

    /** 导航栏此刻是否处于「软隐藏」状态（既开着软隐藏、又该被藏起来）。 */
    private static boolean navSoftHidden() {
        return sNavSoft && !sHard && (sHiddenMask & TYPE_NAVIGATION_BARS) != 0
                && !revealedNav();
    }

    /** 顶边手势/系统瞬态唤出状态栏时，打开一段"揭示窗口"。 */
    private static void markReveal(String from) {
        sRevealUntil = SystemClock.uptimeMillis() + sRevealMs;
        // v2.1：导航栏跟随一起显示（用户要的"一块隐藏和显示"）
        sNavRevealUntil = sRevealUntil;
        sLastTopPullAt = SystemClock.uptimeMillis();
        sSecondPullSeen = false;
        final int n = C_REVEAL.incrementAndGet();
        if (n <= 30) log("reveal window opened via " + from + " #" + n);
        // v1.9.11：立刻下发 alpha —— 不等系统重绘（这是"揭示了却什么都没出现"的根因）
        applyRevealAlpha("reveal:" + from);
        // v1.9.12：揭示后要解除压制，否则窗口期内第二次下滑也展不开面板
        syncDisable();
    }

    /**
     * v2.1：底边上滑 —— 单独给导航栏开一段揭示窗口。
     * <p>不动 sRevealUntil，所以不会连带把状态栏也拉出来（用户是冲着底部来的）。
     */
    private static void markRevealNav(String from) {
        sNavRevealUntil = SystemClock.uptimeMillis() + sRevealMs;
        final int n = C_BPULL.incrementAndGet();
        if (n <= 20) log("nav reveal window opened via " + from + " #" + n
                + " for " + sRevealMs + "ms");
        applyRevealAlpha("navReveal:" + from);
    }

    /**
     * v1.9.11：把「当前该用的 alpha」立刻下发到状态栏图层。
     * <p>
     * 软隐藏下状态栏显隐 100% 由图层 alpha 决定（栏窗口被刻意保持 SHOWN）。
     * 而 alpha 的下发时机原本只挂在 WindowStateAnimator.prepareSurfaceLocked 上，
     * 揭示窗口的开始/结束不会让系统重跑准备流程（窗口无动画、无内容变化），
     * 于是"揭示"只存在于日志里。这里由看门狗/markReveal 主动下发，闭环。
     */
    private static void applyRevealAlpha(String tag) {
        if (sHard) return;
        final Object sc = sStatusSc;
        final Object nsc = sNavSc;
        final Class<?> txnCls = sScTxnClass;
        if (txnCls == null) return;
        if (sc == null && nsc == null) return;
        final boolean r = revealed();
        final float a = r ? 1.0f : sGhostAlpha;
        // v2.1：导航栏同批下发（软隐藏时），揭示态浮出来、隐藏态压成 ghost
        final boolean rn = revealedNav();
        final float na = rn ? 1.0f : sGhostAlpha;
        try {
            Object txn = XposedHelpers.newInstance(txnCls);
            if (sc != null) {
                XposedHelpers.callMethod(txn, "setAlpha", sc, Float.valueOf(a));
                // v2.0：背景色图层跟着揭示状态一起翻，共用同一笔 Transaction
                applyBarColor(txn, sc, sStatusWs);
            }
            if (nsc != null) {
                XposedHelpers.callMethod(txn, "setAlpha", nsc, Float.valueOf(na));
                XposedHelpers.callMethod(txn, "show", nsc);
                // v2.1.2：导航栏背景色跟着揭示状态一起翻，共用同一笔 Transaction
                applyNavColor(txn, nsc, sNavWs);
            }
            XposedHelpers.callMethod(txn, "apply");
            final boolean flipStatus = (r != sLastAppliedReveal);
            final boolean flipNav = (rn != sLastAppliedNavReveal);
            sLastAppliedReveal = r;
            sLastAppliedNavReveal = rn;
            C_ALPHA.incrementAndGet();
            // v2.1.1：翻转的这两件事必须跟着做，否则"挤压"只存在于 insets 里
            if (flipNav) {
                pushInsetsChanged();   // 让 App 重算 insets，内容真的被顶开/放回
                requestInputWindowUpdate(); // 让导航栏重新可点（隐藏态被屏蔽了触摸）
            }
            if (flipStatus) {
                // v2.2：状态栏选「挤压式」时同理 —— 揭示一翻，顶部那 104px 该还回去/收回来，
                // 必须主动通知一次，否则"顶开"只活在 insets 数据里，屏幕上看不到重排。
                if (sFullMode == FM_SQUEEZE) pushInsetsChanged();
                requestInputWindowUpdate();
            }
            final long now = SystemClock.uptimeMillis();
            if (now - sLastAlphaLogAt > 900L) {
                sLastAlphaLogAt = now;
                String extra = "";
                try {
                    Object ws = sStatusWs;
                    if (ws != null) {
                        boolean lh = XposedHelpers.getBooleanField(ws, "mLastHidden");
                        Object sun = XposedHelpers.getObjectField(ws, "mSurfaceAnimator");
                        Object leash = sun == null ? null
                                : XposedHelpers.getObjectField(sun, "mLeash");
                        extra = " lastHidden=" + lh + " leash=" + (leash != null)
                                + " onScreen=" + XposedHelpers.callMethod(ws, "isOnScreen");
                    }
                } catch (Throwable ignored) {
                }
                log("alpha pushed=" + a + " revealed=" + r + " via " + tag + extra
                        + (nsc != null ? (" | nav=" + na + " revealedNav=" + rn) : ""));
            }
        } catch (Throwable t) {
            log("!! applyRevealAlpha failed: " + t);
        }
    }

    /* ══════════════════════════════════════════════════════════════
     * v2.0 · 状态栏背景色图层
     *
     * 状态栏是 SystemUI 画的透明窗口，我们改不了它的内容；所以在状态栏图层
     * **正下方**（setRelativeLayer z = -1）插一张自己的纯色图层：
     *   · 父层直接取 WindowState.getParentSurfaceControl() —— 和状态栏同父，
     *     SurfaceFlinger 要求相对层级的两层共父，否则相对 z 解析不出来
     *   · 只在「揭示窗口」内 show，隐藏态直接 hide，不影响沉浸
     *   · setColorLayer 声明成纯色层，没有 buffer，靠 bufferSize/crop 定尺寸
     * ══════════════════════════════════════════════════════════════ */

    /** Settings.Global 里存的是十进制字符串；用 Long 解析再窄化，负数写法也能吃下。 */
    private static int parseArgb(String s) {
        if (s == null) return 0;
        try {
            return (int) Long.parseLong(s.trim());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * 把背景色图层同步到「当前状态」。挂在两处：
     * ① prepareSurfaceLocked（状态栏每帧准备时，用 WMS 那笔 Transaction）
     * ② applyRevealAlpha（看门狗/markReveal 主动下发时）
     */
    /** 状态栏背景色图层（v2.0）：默认只在揭示窗口内出现，常显开关打开后一直铺着。 */
    private static void applyBarColor(Object txn, Object sbSc, Object win) {
        final boolean want = !sHard && sBarColor != 0 && (sColorAlways || revealed());
        applyColorLayer(txn, sbSc, win, L_STATUS, sBarColor, want);
    }

    /**
     * v2.1.2：导航栏背景色图层。
     * <p>显示时机固定跟随导航栏自己的揭示状态 —— 不套用 K_COLORALWAYS，
     * 否则常显会在隐藏态也一直盖住 App 底部那一条。
     */
    private static void applyNavColor(Object txn, Object navSc, Object win) {
        final boolean want = !sHard && sNavColor != 0 && revealedNav();
        applyColorLayer(txn, navSc, win, L_NAV, sNavColor, want);
    }

    /** 把一条纯色图层铺到指定栏窗口下面。状态栏 / 导航栏共用同一套几何逻辑。 */
    private static void applyColorLayer(Object txn, Object barSc, Object win,
                                        ColorLayer L, int argb, boolean want) {
        if (txn == null || barSc == null || win == null) return;
        if (!want) {
            if (L.sc != null && L.shown) {
                try {
                    XposedHelpers.callMethod(txn, "hide", L.sc);
                    L.shown = false;
                } catch (Throwable ignored) {
                }
            }
            return;
        }
        try {
            Object parent = XposedHelpers.callMethod(win, "getParentSurfaceControl");
            if (parent == null) {
                logColorThrottled("color: no parent surface (win has no parent SC)");
                return;
            }
            if (L.sc == null) {
                Class<?> bCls = XposedHelpers.findClass(
                        "android.view.SurfaceControl$Builder", sCL);
                Object b = XposedHelpers.newInstance(bCls);
                XposedHelpers.callMethod(b, "setName", L.name);
                XposedHelpers.callMethod(b, "setCallsite", "AutoHideLSP#barColor");
                XposedHelpers.callMethod(b, "setColorLayer");
                XposedHelpers.callMethod(b, "setParent", parent);
                L.sc = XposedHelpers.callMethod(b, "build");
                L.parentSc = parent;
                log("color layer created [" + L.name + "]: " + L.sc);
            } else if (L.parentSc != parent) {
                // 父层换了（状态栏被重新挂到别的 DisplayArea）→ 跟着重挂
                XposedHelpers.callMethod(txn, "reparent", L.sc, parent);
                L.parentSc = parent;
            }

            Object frameObj = XposedHelpers.callMethod(win, "getFrame");
            int left = 0, top = 0, cw = 0, ch = 0;
            if (frameObj instanceof android.graphics.Rect) {
                android.graphics.Rect f = (android.graphics.Rect) frameObj;
                left = f.left;
                top = f.top;
                cw = f.width();
                ch = f.height();
            }
            if (cw <= 0 || ch <= 0) {
                logColorThrottled("color: bad frame " + cw + "x" + ch);
                return;
            }

            final float[] rgb = new float[]{
                    ((argb >> 16) & 0xFF) / 255f,
                    ((argb >> 8) & 0xFF) / 255f,
                    (argb & 0xFF) / 255f};
            XposedHelpers.callMethod(txn, "setColor", L.sc, rgb);
            XposedHelpers.callMethod(txn, "setAlpha", L.sc,
                    Float.valueOf(((argb >>> 24) & 0xFF) / 255f));
            XposedHelpers.callMethod(txn, "setPosition", L.sc,
                    Float.valueOf(left), Float.valueOf(top));
            XposedHelpers.callMethod(txn, "setBufferSize", L.sc,
                    Integer.valueOf(cw), Integer.valueOf(ch));
            if (sColorMode >= 1) {
                XposedHelpers.callMethod(txn, "setCrop", L.sc,
                        Float.valueOf(0f), Float.valueOf(0f),
                        Float.valueOf((float) cw), Float.valueOf((float) ch));
            }
            // 紧贴栏窗口下面：图标/三键画在我们上面
            XposedHelpers.callMethod(txn, "setRelativeLayer", L.sc, barSc,
                    Integer.valueOf(-1));
            try {
                XposedHelpers.callMethod(txn, "setTrustedOverlay", L.sc, Boolean.TRUE);
            } catch (Throwable ignored) {
                // 有些版本这刀要权限，失败不影响绘制
            }
            XposedHelpers.callMethod(txn, "show", L.sc);
            L.shown = true;

            final int n = C_COLOR.incrementAndGet();
            final long now = SystemClock.uptimeMillis();
            if (n <= 5 || now - sLastColorLogAt > 2000L) {
                sLastColorLogAt = now;
                log("color applied [" + L.name + "] #" + n + " #" + Integer.toHexString(argb)
                        + " at " + left + "," + top + " " + cw + "x" + ch
                        + " mode=" + sColorMode);
            }
        } catch (Throwable t) {
            logColorThrottled("!! applyColorLayer failed [" + L.name + "]: " + t);
        }
    }

    private static void logColorThrottled(String s) {
        final long now = SystemClock.uptimeMillis();
        if (now - sLastColorLogAt > 3000L) {
            sLastColorLogAt = now;
            log(s);
        }
    }

    /* ══════════════════════════════════════════════════════════════
     * v2.0 · 「应用占满全屏」
     *
     * 真机实测（NX721J / Android 16，dumpsys window）：
     *   所有 BASE_APPLICATION 窗口的 frame 本来就是 [0,0][1116,2480] —— 全屏。
     *   真正吃掉顶部那 104px 的是 **App 自己**：它从 InsetsState 里读到
     *   STATUS_BARS 源（frame 高 104、visible），于是给内容加了等高的内边距。
     *   ⇒ 把下发给应用窗口的 InsetsState 里这个源清掉，内容就从 y=0 开始画。
     *
     * 揭示时**不还原** inset —— 状态栏改为「浮层」盖在内容上：
     * 内容不重排、不抖动，代价是揭示的那几秒顶部 104px 被盖住（用户主动触发，可接受）。
     * ══════════════════════════════════════════════════════════════ */

    /** 把 InsetsState 里的「状态栏」源清掉。返回是否动过。
     *
     * 坑（Android 16 实测，dexdump 验证）：InsetsState 内部是
     * `SparseArray<InsetsSource> mSources`，**键是 id 不是 type**：
     *   removeSource(I) -> mSources.delete(id)
     *   peekSource(I)   -> mSources.get(id)
     *   sourceIdAt(I)   -> mSources.keyAt(index)
     * 而 InsetsSource 里同时有 `createId(owner,index,type)` 和静态 `getType(id)`，
     * 说明 id = f(index,type) ≠ type。早期版本直接 peekSource(ITYPE_STATUS_BAR=1)
     * 是碰运气，这里改成**遍历 + 用实例 getType() 判类型**，稳。 */
    private static boolean stripStatusBarInset(Object state) {
        return stripInsets(state, true, true);
    }

    /**
     * v2.1.1：顶部 inset 此刻是否该被剥掉。
     * <p>抽成方法的意义：剥离判定、副本缓存校验、UI 显示必须共用同一套结论，
     * 否则缓存会在策略翻转后继续返回旧副本。
     */
    private static boolean wantStripStatus() {
        if ((sHiddenMask & TYPE_STATUS_BARS) == 0) return false;
        if (sFullMode == FM_OVERLAY) return true;
        // 挤压式：揭示期间把顶部那 104px 还给 App —— 内容被顶下来，
        // 状态栏以实体条出现而不是浮层盖在内容上。收起后立刻回到占满。
        if (sFullMode == FM_SQUEEZE) return !revealed();
        return false;
    }

    /**
     * v2.1.1：底部 inset 此刻是否该被剥掉。
     * <p>挤压模式（{@link Config#K_NAVSQ}）下，导航栏被唤出期间返回 false ——
     * 也就是**把底部那 142px 还给 App**，让它重新留出空间、内容被顶上去。
     * 这样三键是"挤"出来的而不是"盖"上去的，App 底部原有的按钮不会被压住。
     * <p>注意：只在导航栏真的处于隐藏策略中时才谈挤压；没隐藏就完全不干预。
     */
    private static boolean wantStripNav() {
        if (!sFullNav) return false;
        if ((sHiddenMask & TYPE_NAVIGATION_BARS) == 0) return false;
        return !(sNavSqueeze && revealedNav());
    }

    /** 当前剥离策略戳：顶部/底部两个开关 + v2.3 的贴边条带开关，用于让副本缓存失效。 */
    private static int stripStamp() {
        return (wantStripStatus() ? 1 : 0)
                | (wantStripNav() ? 2 : 0)
                | (sZeroEdge ? 4 : 0)
                | (sFsMode & 0x8);
    }

    /**
     * v2.1：把 InsetsState 里指定类型的源清掉。
     *
     * @param doStatus 清 STATUS_BARS（受 autohide_fullscreen 控制）
     * @param doNav    清 NAVIGATION_BARS（受 autohide_fullscreen_nav 控制）
     * <p>默认（挤压关闭）是纯浮层：揭示时也不还原 inset，导航栏盖在内容上，内容不重排。
     * 三键导航的用户如果嫌底部被盖，把 autohide_fullscreen_nav 置 0 即可；
     * 既想占满又想呼出时能用按钮，就把 autohide_nav_squeeze 置 1（默认）。
     */
    private static boolean stripInsets(Object state, boolean doStatus, boolean doNav) {
        if (state == null) return false;
        final boolean wantStatus = doStatus && wantStripStatus();
        final boolean wantNav = doNav && wantStripNav();
        if (!wantStatus && !wantNav) return false;
        try {
            final int n = (Integer) XposedHelpers.callMethod(state, "sourceSize");
            boolean touched = false;
            // v2.3：几何判据需要屏幕尺寸；拿不到就退化成"只看贴顶"
            int dw = 0, dh = 0;
            if (sZeroEdge) {
                try {
                    Object df = XposedHelpers.callMethod(state, "getDisplayFrame");
                    if (df instanceof Rect) {
                        dw = ((Rect) df).width();
                        dh = ((Rect) df).height();
                    }
                } catch (Throwable ignored) {
                }
            }
            // 倒着走：removeSource 会让后面的下标前移，倒序不受影响
            for (int i = n - 1; i >= 0; i--) {
                final int id = (Integer) XposedHelpers.callMethod(state, "sourceIdAt",
                        Integer.valueOf(i));
                Object src = XposedHelpers.callMethod(state, "peekSource", Integer.valueOf(id));
                if (src == null) continue;
                final int ty = typeOfSource(src, id);
                boolean hit = (ty == ITYPE_STATUS_BAR && wantStatus)
                        || (ty == ITYPE_NAV_BAR && wantNav);
                // v2.3：QQ 这类 App 看的是 tappableElement / mandatorySystemGestures，
                // 它们同样顶着 104px。按几何把"别的贴边条带"一起清掉。
                if (!hit && sZeroEdge) {
                    hit = edgeStripHit(src, dw, dh, wantStatus, wantNav);
                    if (hit) logEdgeOnce(ty);
                }
                if (!hit) continue;
                try {
                    // 先归零 + 标不可见，再把整源抽掉：
                    // 这样 getInsets() 和 getInsetsIgnoringVisibility() 两条路都吃不到那 104px
                    XposedHelpers.callMethod(src, "setFrame", Integer.valueOf(0),
                            Integer.valueOf(0), Integer.valueOf(0), Integer.valueOf(0));
                    XposedHelpers.callMethod(src, "setVisible", Boolean.FALSE);
                } catch (Throwable ignored) {
                }
                XposedHelpers.callMethod(state, "removeSource", Integer.valueOf(id));
                touched = true;
            }
            return touched;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 取 InsetsSource 的类型：优先实例 getType()，失败退回静态 getType(id)。 */
    private static int typeOfSource(Object src, int id) {
        try {
            return (Integer) XposedHelpers.callMethod(src, "getType");
        } catch (Throwable ignored) {
        }
        try {
            Class<?> c = src.getClass();
            while (c != null) {
                if ("android.view.InsetsSource".equals(c.getName())) break;
                c = c.getSuperclass();
            }
            if (c != null) {
                return (Integer) XposedHelpers.callStaticMethod(c, "getType", Integer.valueOf(id));
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /* ══════════════════════════════════════════════════════════════
     * v2.3：除了 STATUS_BARS / NAVIGATION_BARS，还要清掉「别的贴边条带」
     *
     * 真机（NX721J / Android 16 / 澎湃 OS3）在 QQ 前台时 dumpsys 到的同一份
     * InsetsState 里，顶部是这样一组：
     *   type=statusBars              frame=[0,0][0,0]       visible=false  ← 早被我们剥了
     *   type=tappableElement         frame=[0,0][1116,104]  visible=true   ← 还在
     *   type=mandatorySystemGestures frame=[0,0][1116,104]  visible=true   ← 还在
     * 手机 QQ 用的是后者来算顶部避让（腾讯系普遍如此），所以只剥 statusBars
     * 对它是无效的 —— 现象就是"别家 App 都全屏了，QQ 顶部还留一条 104px 白边"。
     *
     * 判据刻意只用**几何**（全宽 + 贴边 + 厚度有限），不认识类型常量：
     * 前几轮已经吃过"赌 ROM 里 IType 数值"的亏，几何是跨版本稳的。
     * IME 顶在屏幕中下部，天然不会命中；顶部/底部分别受各自开关控制，不会互相误伤。
     * ══════════════════════════════════════════════════════════════ */

    /** 「贴边条带」的最大厚度（px）。状态栏 104、导航栏 142，420 足够宽松又不会碰到 IME。 */
    private static final int EDGE_MAX = 420;

    private static boolean edgeStripHit(Object src, int dw, int dh,
                                        boolean wantStatus, boolean wantNav) {
        try {
            Object fo = XposedHelpers.callMethod(src, "getFrame");
            if (!(fo instanceof Rect)) return false;
            final Rect f = (Rect) fo;
            if (f.width() <= 0 || f.height() <= 0) return false;
            if (f.height() > EDGE_MAX) return false;
            if (dw > 0 && f.width() < dw) return false;            // 必须全宽
            if (wantStatus && f.top <= 0 && f.bottom > 0) return true;   // 贴顶条带
            if (wantNav && dh > 0 && f.bottom >= dh && f.top >= dh - EDGE_MAX) return true; // 贴底条带
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 每种被"顺带清掉"的类型只打一行日志，方便事后一眼看出清了些什么。 */
    private static final java.util.Set<Integer> sEdgeSeen =
            java.util.Collections.synchronizedSet(new java.util.HashSet<Integer>());

    private static void logEdgeOnce(int type) {
        try {
            if (sEdgeSeen.add(Integer.valueOf(type))) {
                log("zero-edge: also stripped inset source type=" + type);
            }
        } catch (Throwable ignored) {
        }
    }

    /* ── 剥好的副本缓存 ──────────────────────────────────────────────
     * getInsetsState() 这类方法 WMS 调得非常勤（relayout、insets 动画每帧），
     * 每次 new 一个 InsetsState 会白白制造 GC 压力。
     * 按「原始对象身份 + seq」缓存：稳定期（seq 不变）完全零分配；
     * 命中缓存时再扫一遍确认状态栏源确实不在（成本极低），
     * 防止上一手调用方把副本改回去了。
     */
    private static volatile Object sStripSrc;
    private static volatile Object sStripCopy;
    private static volatile int sStripSeq = Integer.MIN_VALUE;
    /**
     * v2.1.1：缓存副本当时用的剥离策略。
     * 挤压模式下「导航栏是否揭示」会翻转，翻转前后该剥的东西不一样；
     * 只看 (对象身份, seq) 会在翻转后继续复用旧副本，导致挤压永远不生效。
     */
    private static volatile int sStripStamp = Integer.MIN_VALUE;

    /** 返回剥掉状态栏源的副本；本来就没有状态栏源则返回 null（不替换结果）。 */
    private static Object strippedCopy(Object state) {
        try {
            int seq = (Integer) XposedHelpers.callMethod(state, "getSeq");
            final int stamp = stripStamp();
            if (state == sStripSrc && seq == sStripSeq && stamp == sStripStamp
                    && sStripCopy != null && !hasStrippableSource(sStripCopy)) {
                return sStripCopy;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> stCls = XposedHelpers.findClass("android.view.InsetsState", sCL);
            Object copy = XposedHelpers.newInstance(stCls, state);
            if (!stripStatusBarInset(copy)) return null;
            int seq;
            try {
                seq = (Integer) XposedHelpers.callMethod(state, "getSeq");
            } catch (Throwable t) {
                seq = Integer.MIN_VALUE;
            }
            sStripSrc = state;
            sStripSeq = seq;
            sStripStamp = stripStamp();
            sStripCopy = copy;
            return copy;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 只读扫描：state 里还有没有状态栏源。 */
    private static boolean hasStatusBarSource(Object state) {
        return hasSourceOfType(state, ITYPE_STATUS_BAR);
    }

    /** v2.1：只读扫描 —— 还有没有「本该被剥掉」的源（状态栏 + 按开关的底部 + v2.3 的贴边条带）。 */
    private static boolean hasStrippableSource(Object state) {
        final boolean ws = wantStripStatus();
        final boolean wn = wantStripNav();
        if (ws && hasSourceOfType(state, ITYPE_STATUS_BAR)) return true;
        if (wn && hasSourceOfType(state, ITYPE_NAV_BAR)) return true;
        // v2.3：顺带清的那几类也要一起校验，否则缓存会以为副本"已经剥干净"了
        if (!sZeroEdge) return false;
        try {
            int dw = 0, dh = 0;
            Object df = XposedHelpers.callMethod(state, "getDisplayFrame");
            if (df instanceof Rect) {
                dw = ((Rect) df).width();
                dh = ((Rect) df).height();
            }
            final int n = (Integer) XposedHelpers.callMethod(state, "sourceSize");
            for (int i = n - 1; i >= 0; i--) {
                final int id = (Integer) XposedHelpers.callMethod(state, "sourceIdAt",
                        Integer.valueOf(i));
                Object src = XposedHelpers.callMethod(state, "peekSource", Integer.valueOf(id));
                if (src != null && edgeStripHit(src, dw, dh, ws, wn)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean hasSourceOfType(Object state, int want) {
        try {
            final int n = (Integer) XposedHelpers.callMethod(state, "sourceSize");
            for (int i = n - 1; i >= 0; i--) {
                final int id = (Integer) XposedHelpers.callMethod(state, "sourceIdAt",
                        Integer.valueOf(i));
                Object src = XposedHelpers.callMethod(state, "peekSource", Integer.valueOf(id));
                if (src == null) continue;
                if (typeOfSource(src, id) == want) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 这些方法返回的是 WMS 内部 state 对象，直接改会污染布局，只能换副本。 */
    private static final String[] INSETS_GETTERS = {
            "getInsetsState",                        // () / (Z)，内部会调 getMergedInsetsState
            "getMergedInsetsState",                  // Android 16 新增，直接返回 mAboveInsetsState
            "getCompatInsetsState",                  // fillInsetsState 取的就是它
            "getInsetsStateWithVisibilityOverride",
    };

    private void hookFullscreen(final XC_LoadPackage.LoadPackageParam lp) {
        Class<?> wsCls;
        try {
            wsCls = XposedHelpers.findClass("com.android.server.wm.WindowState", lp.classLoader);
        } catch (Throwable t) {
            log("!! WindowState not found for fullscreen: " + t);
            return;
        }

        /* 挂点 A 组：返回型 getter —— 一律换成「剥掉状态栏源」的副本再返回。 */
        for (final String name : INSETS_GETTERS) {
            XC_MethodHook get = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object win = param.thisObject;
                    try {
                        final boolean bit = (sFsMode & 0x1) != 0;
                        final boolean allow = allowFullscreen(win);
                        Object r = param.getResult();
                        fsProbe(name, win, r, allow, bit);
                        Object copy = null;
                        if (bit && allow && r != null) {
                            copy = strippedCopy(r);
                            if (copy != null) {
                                param.setResult(copy);
                                countFullscreen(name);
                            }
                        }
                        // 每个包名每个结论只打一行：拿来直接看"哪个 App 没被剥到"
                        fsPkgOnce(win, name, bit && allow, copy != null);
                    } catch (Throwable t) {
                        logFsThrottled("!! " + name + " threw: " + t);
                    }
                }
            };
            try {
                Set<?> u = XposedBridge.hookAllMethods(wsCls, name, get);
                log("hook WindowState." + name + " -> " + u.size());
            } catch (Throwable t) {
                log("!! " + name + " hook failed: " + t);
            }
        }

        /* 挂点 B：WindowState.fillInsetsState(InsetsState out, boolean)
         *   把 insets 填进 out 参数交给 App。
         *   注意：如果 out 正好是窗口自己的内部字段（mAboveInsetsState/
         *   mLastReportedInsetsState/mFrozenInsetsState），就地剥会污染 WMS 状态，
         *   这种情况直接放过（交给上面的 getter 挂点处理）。 */
        XC_MethodHook fill = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object win = param.thisObject;
                try {
                    final boolean bit = (sFsMode & 0x2) != 0;
                    final boolean allow = allowFullscreen(win);
                    Object out = (param.args.length >= 1) ? param.args[0] : null;
                    fsProbe("fillInsetsState", win, out, allow, bit);
                    if (!bit || !allow || out == null) return;
                    if (isInternalInsetsField(win, out)) {
                        logFsThrottled("fillInsetsState: out is an internal field, skipped");
                        return;
                    }
                    if (stripStatusBarInset(out)) countFullscreen("fillInsetsState");
                } catch (Throwable t) {
                    logFsThrottled("!! fillInsetsState threw: " + t);
                }
            }
        };
        try {
            Set<?> u = XposedBridge.hookAllMethods(wsCls, "fillInsetsState", fill);
            log("hook WindowState.fillInsetsState -> " + u.size());
        } catch (Throwable t) {
            log("!! fillInsetsState hook failed: " + t);
        }

        /* 挂点 C：WindowState.updateAboveInsetsState(InsetsState, SparseArray, ArraySet)
         *   这是 mAboveInsetsState 被写入的入口。前两组挂点都是「返回副本」，
         *   窗口内部那份 mAboveInsetsState 仍然是**带状态栏**的 ——
         *   任何直接读这个字段、或者从它派生的代码路径都吃不到剥除效果
         *   （怀疑这就是酷安这类 App 没生效的原因）。
         *   这里直接就地剥：覆盖面最广，但会改变 WMS 内部状态，
         *   所以默认不开，用 fs_mode 的 bit2(4) 现场开关。 */
        XC_MethodHook upd = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object win = param.thisObject;
                try {
                    if ((sFsMode & 0x4) == 0) return;
                    if (!allowFullscreen(win)) return;
                    if (param.args.length < 1) return;
                    Object st = param.args[0];
                    if (st == null) return;
                    boolean did = stripStatusBarInset(st);
                    fsPkgOnce(win, "updateAboveInsetsState", true, did);
                    if (did) countFullscreen("updateAboveInsetsState");
                } catch (Throwable t) {
                    logFsThrottled("!! updateAboveInsetsState threw: " + t);
                }
            }
        };
        try {
            Set<?> u = XposedBridge.hookAllMethods(wsCls, "updateAboveInsetsState", upd);
            log("hook WindowState.updateAboveInsetsState -> " + u.size());
        } catch (Throwable t) {
            log("!! updateAboveInsetsState hook failed: " + t);
        }
    }

    /** out 参数是不是窗口自己的内部 InsetsState 字段（是就不能就地改）。 */
    private static boolean isInternalInsetsField(Object win, Object out) {
        // v2.3：bit3(8) 打开时，连「冻结/最后上报给 App 的那两份副本」也允许就地剥 ——
        // 这台机器的 WindowState.getInsetsState(Z) 是 MIUI 定制版，反汇编显示它优先取
        // mFrozenInsetsState（见 dbg/svcdex 里 classes3.dex 的 WindowState.getInsetsState），
        // 而这两份字段原来是白名单里"绝不碰"的，等于给 QQ 留了一条绕过剥离的路。
        // mAboveInsetsState 永远跳过：它是 WMS 内部派生源，改它会污染别的窗口。
        final String[] f = ((sFsMode & 0x8) != 0)
                ? new String[]{"mAboveInsetsState"}
                : new String[]{"mAboveInsetsState", "mLastReportedInsetsState", "mFrozenInsetsState"};
        for (String n : f) {
            try {
                if (XposedHelpers.getObjectField(win, n) == out) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /** 该窗口是否要享受「占满全屏」：只针对普通应用窗口，别碰系统栏/输入法/面板。 */
    private static boolean allowFullscreen(Object win) {
        refreshConfig();
        if (sHard) return false;
        if (sFsMode == 0) return false;
        // v2.1：顶部和底部各自独立判断 —— 只隐藏导航栏、没隐藏状态栏时也要剥底部。
        // v2.2：直接用 wantStripXxx()，挤压模式揭示期间这里会返回 false，
        // 于是这段时间走原始 insets（顶部/底部还回去，内容被顶开），正是挤压想要的效果。
        final boolean wantStatus = wantStripStatus();
        final boolean wantNav = wantStripNav();
        if (!wantStatus && !wantNav) return false;
        final int type = windowType(win);
        return type >= WT_FIRST_APP && type <= WT_LAST_APP;
    }

    /* ── 一次性诊断探针 ──────────────────────────────────────────────
     * v2.0.1 首次开机：6 个挂点全部注册成功、但 stripped 一次没命中。
     * 之前的 catch 把异常吞了，等于瞎猜。这里把每个门的取值 + 实际 insets 内容
     * 打出来：到底是被门挡住、还是压根没被调用、还是剥的时候抛了异常。
     */
    private static final AtomicInteger C_FSPROBE = new AtomicInteger();
    private static volatile long sLastFsProbeAt = 0L;
    private static volatile long sLastFsErrAt = 0L;

    private static void fsProbe(String name, Object win, Object state,
                                boolean allow, boolean bit) {
        final int n = C_FSPROBE.incrementAndGet();
        final long now = SystemClock.uptimeMillis();
        // 前 40 次全打，之后每 4 秒一条，免得刷屏
        if (n > 40 && now - sLastFsProbeAt < 4000L) return;
        sLastFsProbeAt = now;
        int srcN = -1;
        boolean has = false;
        if (state != null) {
            try {
                srcN = (Integer) XposedHelpers.callMethod(state, "sourceSize");
            } catch (Throwable ignored) {
            }
            has = hasStatusBarSource(state);
        }
        log("fs probe #" + n + " " + name
                + " type=" + windowType(win)
                + " allow=" + allow + " bit=" + bit
                + " full=" + sFullscreen + " hard=" + sHard
                + " fsMode=" + sFsMode + " mask=" + sHiddenMask
                + " srcN=" + srcN + " hasStatus=" + has);
    }

    private static void logFsThrottled(String s) {
        // 用独立的时间戳，别和 fsProbe 互相压制
        final long now = SystemClock.uptimeMillis();
        if (now - sLastFsErrAt > 2000L) {
            sLastFsErrAt = now;
            log(s);
        }
    }

    /** 按「包名 + 结论」去重：每个 App 只留一行，方便直接对比哪些没被剥到。 */
    private static final java.util.Set<String> sFsSeen =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private static void fsPkgOnce(Object win, String name, boolean attempted, boolean stripped) {
        try {
            final String key = pkgOf(win) + "|" + name + "|" + attempted + "|" + stripped;
            if (!sFsSeen.add(key)) return;
            log("fs app=" + pkgOf(win) + " hook=" + name
                    + " type=" + windowType(win)
                    + " attempted=" + attempted + " stripped=" + stripped
                    + " full=" + sFullscreen + " hard=" + sHard
                    + " fsMode=" + sFsMode + " mask=" + sHiddenMask);
        } catch (Throwable ignored) {
        }
    }

    private static String pkgOf(Object windowState) {
        try {
            Object attrs = XposedHelpers.getObjectField(windowState, "mAttrs");
            Object p = XposedHelpers.getObjectField(attrs, "packageName");
            return p == null ? "?" : String.valueOf(p);
        } catch (Throwable t) {
            return "?";
        }
    }

    private static void countFullscreen(String tag) {
        final int n = C_FULLSCREEN.incrementAndGet();
        final long now = SystemClock.uptimeMillis();
        if (n <= 5 || now - sLastFsLogAt > 3000L) {
            sLastFsLogAt = now;
            log("fullscreen: stripped statusBars inset via " + tag + " #" + n);
        }
    }

    /* ══════════════════════════════════════════════════════════════
     * v1.9.7/1.9.10 挂点：手势监视器 —— 既做诊断，也做"顶边下滑"检测
     *
     *   真机疑问：本 ROM 上顶边下滑唤出状态栏时，
     *   `InsetsPolicy.showTransient` 与 `DisplayPolicy.requestTransientBars`
     *   的命中计数始终为 0（reveal=0），且
     *   `hook DisplayPolicy swipe callbacks -> 0/0/0`（这三个回调在本 ROM 上不存在）
     *   ⇒ MIUI 没走 AOSP 那套手势回调。
     *
     *   但 `SystemGesturesPointerEventListener.onPointerEvent` **有**在收事件
     *   （它是 display 的 gesture monitor，任何触摸都收）。
     *   所以 v1.9.10 在这里**自己实现**顶边下滑检测：
     *     DOWN 落在顶部 TOP_ZONE_Y 内 → 武装；
     *     随后 MOVE 纵向位移 ≥ TOP_TRIG_Y → 判定为"顶边下滑"，markReveal()。
     *   配合已打开的输入门控（隐藏态状态栏窗口 NOT_TOUCHABLE），
     *   第一次下滑的触摸会穿透、面板根本没有机会被拖出来，
     *   屏幕上只会出现"整条状态栏 + 停留 REVEAL_MS"，正是要的节奏。
     * ══════════════════════════════════════════════════════════════ */
    private void hookGestureProbe(final XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> sg = XposedHelpers.findClass(
                    "com.android.server.wm.SystemGesturesPointerEventListener", lp.classLoader);
            XC_MethodHook h = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object ev = param.args.length > 0 ? param.args[0] : null;
                    if (!(ev instanceof MotionEvent)) return;
                    MotionEvent me = (MotionEvent) ev;
                    try {
                        if (C_GPROBE.get() < 40) {
                            final int a = me.getActionMasked();
                            C_GPROBE.incrementAndGet();
                            log("gprobe action=" + a + " x=" + me.getX() + " y=" + me.getY());
                        }
                    } catch (Throwable ignored) {
                    }
                    try {
                        detectTopPull(me);
                    } catch (Throwable ignored) {
                    }
                }
            };
            Set<?> u = XposedBridge.hookAllMethods(sg, "onPointerEvent", h);
            log("hook SystemGesturesPointerEventListener.onPointerEvent -> " + u.size());
        } catch (Throwable t) {
            log("!! gesture probe (listener) failed: " + t);
        }
        try {
            Class<?> dp = XposedHelpers.findClass("com.android.server.wm.DisplayPolicy", lp.classLoader);
            XC_MethodHook h2 = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (C_GSWIPE.get() >= 40) return;
                        C_GSWIPE.incrementAndGet();
                        log("gprobe DisplayPolicy." + param.method.getName() + "() fired");
                    } catch (Throwable ignored) {
                    }
                }
            };
            Set<?> a = XposedBridge.hookAllMethods(dp, "onSwipeFromTop", h2);
            Set<?> b = XposedBridge.hookAllMethods(dp, "onSwipeFromBottom", h2);
            Set<?> c = XposedBridge.hookAllMethods(dp, "onSwipeFromNavBar", h2);
            log("hook DisplayPolicy swipe callbacks -> " + a.size() + "/" + b.size() + "/" + c.size());
        } catch (Throwable t) {
            log("!! gesture probe (policy) failed: " + t);
        }
    }

    /** v1.9.10：自己检测"顶边下滑"。命中则打开揭示窗口（只唤出状态栏，不开面板）。 */
    private static void detectTopPull(MotionEvent me) {
        final int a = me.getActionMasked();
        final float y = me.getY();
        if (a == MotionEvent.ACTION_DOWN) {
            // v1.9.11：揭示窗口内任何一次新的按下都视为"用户在继续操作"（第二次下滑），
            // 用于让 peek 机制放行面板、不要把它折回去。
            if (revealed() && !sSecondPullSeen) sSecondPullSeen = true;
            sDownY = y;
            sDownInTop = y <= TOP_ZONE_Y;
            // v2.1：同一次按下也顺便判断是不是落在底边（上滑唤导航栏）
            final int h = screenHeight();
            sDownInBottom = h > 0 && y >= (h - BOTTOM_ZONE_H);
            sPullFired = false;
            sBottomPullFired = false;
            return;
        }
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            sDownInTop = false;
            sDownInBottom = false;
            sPullFired = false;
            sBottomPullFired = false;
            return;
        }
        if (a != MotionEvent.ACTION_MOVE) return;
        if (sDownInTop && !sPullFired) {
            // 只认单指（多点触控时保持原判）
            if ((y - sDownY) < TOP_TRIG_Y) return;
            sPullFired = true;
            onTopPull();
            return;
        }
        // v2.1：底边上滑 → 单独唤出导航栏
        if (sDownInBottom && !sBottomPullFired) {
            if ((sDownY - y) < BOTTOM_TRIG_Y) return;
            sBottomPullFired = true;
            onBottomPull();
        }
    }

    /** v2.1：屏幕物理高度（只算一次，之后缓存）。 */
    private static int screenHeight() {
        if (sScreenH > 0) return sScreenH;
        try {
            Object dm = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.content.res.Resources", sCL),
                    "getSystem");
            Object met = XposedHelpers.callMethod(dm, "getDisplayMetrics");
            Object h = XposedHelpers.getObjectField(met, "heightPixels");
            if (h instanceof Integer) sScreenH = (Integer) h;
        } catch (Throwable ignored) {
        }
        return sScreenH;
    }

    /** v2.1：判定"隐藏态的底边上滑"→ 唤出导航栏并停留。 */
    private static void onBottomPull() {
        refreshConfig();
        if (sHard || !sNavSoft) return;
        if (sHiddenMask == 0 || (sHiddenMask & TYPE_NAVIGATION_BARS) == 0) return;
        if (revealedNav()) return;        // 已经出来了 → 交给系统，别反复续
        markRevealNav("bottomPull");
    }

    /** 判定"这是隐藏态的第一次下滑"→ 只唤出状态栏并停留。 */
    private static void onTopPull() {
        refreshConfig();
        if (!sPeek || sHard) return;
        if (sHiddenMask == 0 || (sHiddenMask & TYPE_STATUS_BARS) == 0) return;
        if (revealed()) return;          // 揭示窗口内 → 第二次下滑，交给系统开面板
        markReveal("topPull");
        final int n = C_TPULL.incrementAndGet();
        if (n <= 20) log("topPull: first pull detected -> reveal status bar only #" + n);
    }

    /* ══════════════════════════════════════════════════════════════
     * v1.9.9 挂点：peek —— "第一次下滑只出状态栏，停留 ~3.5s；第二次才出面板"
     *
     *   真机结论（v1.9.8 探针）：
     *     · SystemGesturesPointerEventListener.onPointerEvent 有在收事件（gp 计数在涨）
     *     · 但 DisplayPolicy.onSwipeFromTop/FromBottom/FromNavBar 在本 ROM 上
     *       **根本不存在**（hook 返回 0/0/0）⇒ MIUI 换了手势实现
     *     · 因此 InsetsPolicy.showTransient / DisplayPolicy.requestTransientBars
     *       永不触发（reveal=0），"系统瞬态状态栏"这条路在本 ROM 上是死的
     *   ⇒ 顶边下滑唯一的路径就是"触摸打到状态栏窗口（StatusBar1）→ SystemUI 拖面板"。
     *     短拖是原生回弹 peek，稍快/稍长就一路拖成面板（用户实测现象）。
     *
     *   本挂点做法：
     *     监视 NotificationShade(type=2040) 的可见性。它从"不可见→可见"即代表
     *     "用户刚开始下滑"。若此时状态栏还处于隐藏态（不在揭示窗口内），就
     *      ① markReveal()：打开 REVEAL_MS 的揭示窗口 → 状态栏整条 alpha=1 画出来
     *      ② collapsePanels()：把刚冒头的面板折回去，不让它直接展开
     *     揭示窗口内再下滑 → revealed()=true → 直接放行，面板正常展开（保留 MIUI 左右分栏）。
     *
     *   安全阀：锁屏时不介入（锁屏 UI 本身跑在 NotificationShade 窗口里，避免误折叠）。
     * ══════════════════════════════════════════════════════════════ */
    private void hookShadeWatch(final XC_LoadPackage.LoadPackageParam lp) {
        Class<?> ws;
        try {
            ws = XposedHelpers.findClass("com.android.server.wm.WindowState", lp.classLoader);
        } catch (Throwable t) {
            log("!! WindowState not found for shade watch: " + t);
            return;
        }
        XC_MethodHook h = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (windowType(param.thisObject) != WT_NOTIFICATION_SHADE) return;
                    final boolean vis = Boolean.TRUE.equals(param.getResult());
                    if (vis == sShadeVisible) return;
                    sShadeVisible = vis;
                    if (vis) onShadeAppeared();
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            Set<?> u = XposedBridge.hookAllMethods(ws, "isVisible", h);
            log("hook WindowState.isVisible(shade watch) -> " + u.size());
        } catch (Throwable t) {
            log("!! shade watch hook failed: " + t);
        }
    }

    /** NotificationShade 刚变可见 = 用户刚开始下滑。 */
    private static void onShadeAppeared() {
        refreshConfig();
        if (!sPeek || sHard) return;
        if (sHiddenMask == 0 || (sHiddenMask & TYPE_STATUS_BARS) == 0) return;
        if (revealed()) return;                 // 揭示窗口内 → 第二次下拉，正常放行
        // v1.9.11：本次揭示窗口内已经出现过新的按下（=用户第二下滑）→ 用户就是想开面板，
        // 不能再把它折回去，否则"第二次下滑出面板"会被自己打断。
        if (sSecondPullSeen) return;
        if (isKeyguardLocked()) return;         // 锁屏不介入
        markReveal("firstPull");
        collapseShade();
        final int n = C_PEEK.incrementAndGet();
        if (n <= 20) log("peek: shade just appeared -> reveal bar & collapse #" + n);
    }

    /** 折叠通知面板（走 DisplayPolicy.getStatusBarManagerInternal().collapsePanels()）。 */
    private static void collapseShade() {
        Object[] arr;
        synchronized (sPolicies) {
            arr = sPolicies.toArray();
        }
        for (Object policy : arr) {
            try {
                Object dp = XposedHelpers.getObjectField(policy, "mPolicy");
                if (dp == null) continue;
                Object sbmi = XposedHelpers.callMethod(dp, "getStatusBarManagerInternal");
                if (sbmi == null) continue;
                XposedHelpers.callMethod(sbmi, "collapsePanels");
                C_COLLAPSE.incrementAndGet();
                return;
            } catch (Throwable t) {
                log("!! collapseShade path failed: " + t);
            }
        }
        log("!! collapseShade: no usable path");
    }

    /* ══════════════════════════════════════════════════════════════
     * v1.9.12：「压制」—— IStatusBarService.disable(DISABLE_EXPAND)
     *
     *   吸收（把隐藏态 alpha 抬高）让顶边手势落到 StatusBar1 之后，SystemUI 就会
     *   照常把面板拖出来 —— 那只是把"刷新视频"换成了"误开面板"。
     *   所以还要压制：隐藏态下发 DISABLE_EXPAND，让 SystemUI 吃到了手势也展不开。
     *
     *   静态可行性（dexdump 本机 dex）：
     *     · com.android.server.statusbar.StatusBarManagerService 完整保留
     *       disable / disableForUser / disableLocked / manageDisableListLocked / getDisableFlags
     *     · SystemUI 侧 CentralSurfacesCommandQueueCallbacks 有 disable() + mDisabled1/mDisabled2
     *   真机可行性（v1.9.12，dbg/t3、t4）：
     *     · cmd statusbar send-disable-flag statusbar-expansion ⇒ dumpsys statusbar mDisabled1=0x10000
     *     · 顶边下滑后 mCurrentFocus 仍为 Launcher ⇒ **手指拖拽确实被挡住了**
     *     · 解除后第二次下滑 ⇒ mCurrentFocus=NotificationShade ⇒ 面板正常打开
     * ══════════════════════════════════════════════════════════════ */

    /** 拿 IStatusBarService（走 ServiceManager + Stub.asInterface）。 */
    private static Object statusBarService() {
        if (sSbSvc != null) return sSbSvc;
        if (sSbSvcTried) return null;
        sSbSvcTried = true;
        try {
            Class<?> sm = XposedHelpers.findClass("android.os.ServiceManager", sCL);
            Object b = XposedHelpers.callStaticMethod(sm, "getService", "statusbar");
            if (b == null) {
                log("!! statusbar service: getService returned null");
                return null;
            }
            Class<?> stub = XposedHelpers.findClass(
                    "com.android.internal.statusbar.IStatusBarService$Stub", sCL);
            sSbSvc = XposedHelpers.callStaticMethod(stub, "asInterface", b);
            log("statusbar service acquired: " + sSbSvc);
        } catch (Throwable t) {
            log("!! statusbar service failed: " + t);
        }
        return sSbSvc;
    }

    /** 下发/解除 disable 标志（0 = 全部恢复）。失败只记日志，不影响主流程。 */
    private static void applyDisable(int what) {
        final Object svc = statusBarService();
        if (svc == null) return;
        try {
            if (sDisableToken == null) sDisableToken = new Binder();
            try {
                XposedHelpers.callMethod(svc, "disable",
                        what, sDisableToken, PKG_ANDROID);
            } catch (Throwable t1) {
                // 某些版本只有 disableForUser
                XposedHelpers.callMethod(svc, "disableForUser",
                        what, sDisableToken, PKG_ANDROID, 0);
            }
            final int n = C_DISABLE.incrementAndGet();
            if (n <= 20) log("disable applied what1=0x" + Integer.toHexString(what) + " #" + n);
        } catch (Throwable t) {
            log("!! applyDisable(0x" + Integer.toHexString(what) + ") failed: " + t);
        }
    }

    /**
     * 让 disable 标志与当前状态保持一致：
     *   隐藏态（未揭示、未锁屏、状态栏软隐藏、面板没开着） ⇒ DISABLE_EXPAND
     *   其余（已揭示 / 锁屏 / 面板开着 / 未启用吸收 / 硬隐藏）   ⇒ 0
     * 由看门狗（200ms）与 markReveal 调用，幂等。
     */
    private static void syncDisable() {
        try {
            int want = 0;
            if (sAbsorb && !sHard
                    && (sHiddenMask & TYPE_STATUS_BARS) != 0
                    && !revealed()
                    && !sShadeVisible
                    && !isKeyguardLockedCached()) {
                want = DISABLE_EXPAND;
            }
            if (want == sDisableApplied) return;
            applyDisable(want);
            sDisableApplied = want;
        } catch (Throwable ignored) {
        }
    }

    /** 是否处于锁屏（锁屏 UI 也跑在 NotificationShade 里，不能误折叠/误门控）。 */
    private static boolean isKeyguardLocked() {
        try {
            Context c = sCtx;
            if (c == null) return false;
            Object svc = XposedHelpers.callMethod(c, "getSystemService", Context.KEYGUARD_SERVICE);
            if (svc == null) return false;
            Object r = XposedHelpers.callMethod(svc, "isKeyguardLocked");
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 带 2s 缓存的版本（门控挂钩调用极频繁，不能每次都走 IPC）。 */
    private static boolean isKeyguardLockedCached() {
        final long now = SystemClock.uptimeMillis();
        if (now - sKgAt < 2000L) return sKgVal;
        sKgAt = now;
        sKgVal = isKeyguardLocked();
        return sKgVal;
    }

    /* ══════════════════════════════════════════════════════════════
     * v1.9.7 挂点：输入门控 —— 隐藏态让状态栏窗口不可触摸
     *
     *   真机证据（dumpsys input，App 前台、状态栏已软隐藏）：
     *      16: name=... StatusBar   inputConfig=NOT_VISIBLE | NOT_FOCUSABLE | TRUSTED_OVERLAY   ← 不可触摸
     *       8: name=... StatusBar1  inputConfig=NOT_FOCUSABLE | TRUSTED_OVERLAY                 ← **可触摸**
     *          touchableRegion=[0,0][1116,104]
     *   ⇒ 顶部下滑的触摸实际落在 StatusBar1 上，被 SystemUI 直接拖成通知面板
     *     （合成 `input swipe 558 1 558 400 1500`，中途截图 1.43MB、focus=NotificationShade）。
     *
     *   修复：在服务端计算输入窗口配置的入口 `InputConfigAdapter.getInputConfigFromWindowParams`
     *   上，对状态栏窗口类型（2000 / 2041）把 WindowManager 的 FLAG_NOT_TOUCHABLE
     *   或进 flags 参数。该 flag 的映射在这个方法内部完成，所以拿到的输入配置会带上
     *   NOT_TOUCHABLE ⇒ 触摸穿透到下层；系统手势监视器（mandatorySystemGestures）
     *   仍然独立收到这次下滑，照常触发"瞬态状态栏"。
     *
     *   注意：本 ROM（Android 16）类名是 com.android.server.wm.InputConfigAdapter，
     *   不是 AOSP 14 文档里的 android.view.InputConfigAdapter（已移除）。
     * ══════════════════════════════════════════════════════════════ */
    private void hookInputTouchGate(final XC_LoadPackage.LoadPackageParam lp) {
        Class<?> ica;
        try {
            ica = XposedHelpers.findClass("com.android.server.wm.InputConfigAdapter", lp.classLoader);
        } catch (Throwable t) {
            log("!! InputConfigAdapter not found (touch gate disabled): " + t);
            return;
        }
        XC_MethodHook before = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (C_GATELOG.get() < 4) {
                        C_GATELOG.incrementAndGet();
                        StringBuilder sb = new StringBuilder();
                        for (Object a : param.args) sb.append(a).append(", ");
                        log("gate sig: " + param.method.getName() + "(" + sb + ")");
                    }
                    refreshConfig();
                    // v1.9.12：吸收模式下状态栏窗口必须保持可触摸（否则手势又穿透给 App）。
                    // 而且实测这个门控本来就是 no-op —— 真正决定归属的是图层 alpha（见 sGhostAlpha 注释）。
                    if (!sTouchGate || sAbsorb || sHard) return;
                    if ((sHiddenMask & TYPE_STATUS_BARS) == 0) return;
                    if (isKeyguardLockedCached()) return;            // 锁屏不门控（锁屏 UI 需要触摸）
                    if (param.args.length < 2) return;
                    Object a0 = param.args[0];
                    Object a1 = param.args[1];
                    if (!(a0 instanceof Integer) || !(a1 instanceof Integer)) return;
                    final int type = (Integer) a0;
                    if (type != WT_STATUS_BAR && type != WT_STATUS_BAR_ADDITIONAL) return;
                    final int flags = (Integer) a1;
                    if ((flags & FLAG_NOT_TOUCHABLE) != 0) return;   // 本来就不能摸
                    if (revealed()) return;                          // 已揭示 → 保持可触摸
                    param.args[1] = flags | FLAG_NOT_TOUCHABLE;
                    C_GATE.incrementAndGet();
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            Set<?> u = XposedBridge.hookAllMethods(ica, "getInputConfigFromWindowParams", before);
            log("hook InputConfigAdapter.getInputConfigFromWindowParams -> " + u.size());
        } catch (Throwable t) {
            log("!! getInputConfigFromWindowParams hook failed: " + t);
        }
    }

    /* ══════════════════════════════════════════════════════════════
     * v2.1.1 挂点：InputMonitor.populateInputWindowHandle
     *
     *   软隐藏只把图层 alpha 压到 ~0，窗口还在、touchableRegion 也还在，
     *   于是"看不见但按得到" —— 盲按三键位置照样返回桌面 / 切后台。
     *   这里在系统填完输入窗口信息之后，把隐藏态栏窗口的触摸区域清掉，
     *   点击就穿透给下层 App。
     *
     *   与 v1.9.7 那个 hookInputTouchGate 的区别：
     *     那个改的是 LayoutParams.flags，要等下一次 relayout 才生效（实测等于没生效）；
     *     这里直接改 InputWindowHandle，下一次 updateInputWindows 就生效。
     * ══════════════════════════════════════════════════════════════ */
    private void hookInputTouchBlock(final XC_LoadPackage.LoadPackageParam lp) {
        Class<?> imCls;
        try {
            imCls = XposedHelpers.findClass("com.android.server.wm.InputMonitor", lp.classLoader);
        } catch (Throwable t) {
            log("!! InputMonitor not found (touch block disabled): " + t);
            return;
        }
        XC_MethodHook after = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (param.args.length < 2) return;
                    Object wrapper = param.args[0];
                    Object win = param.args[1];
                    if (wrapper == null || win == null) return;
                    if (sInputMonitor == null) sInputMonitor = param.thisObject;
                    final int type = windowType(win);
                    if (type == 0) return;
                    final boolean isStatus = (type == WT_STATUS_BAR
                            || type == WT_STATUS_BAR_ADDITIONAL);
                    final boolean isNav = (type == WT_NAV_BAR);
                    if (!isStatus && !isNav) return;
                    refreshConfig();
                    /*
                     * v2.4：顶部到底"该不该让给 App"，不能只看开关数字。
                     *
                     *   输入按下 z 序遍历窗口，StatusBar 排在 App 之上且带
                     *   touchableRegion=[0,W][0,104]，只要它可触摸，App 顶部的点击就永远
                     *   到不了 —— 哪怕状态栏 alpha 已经压到 0.001（alpha 只影响渲染，
                     *   不影响落点归属）。于是「应用占满全屏」一开，App 内容顶到 y=0，
                     *   顶部搜索框/标题栏却点不动，功能自相矛盾。
                     *
                     *   所以只要此刻顶部确实被剥掉了（wantStripStatus()，含
                     *   「模式=关闭」与「揭示窗口中」两种不该剥的情况），
                     *   就无条件把状态栏的触摸区让出来，不再依赖 sTouchBlock ≥ 2。
                     *   揭示窗口内 wantStripStatus() 会返回 false ⇒
                     *   状态栏恢复可触摸，下拉/点通知照常。
                     */
                    final boolean fbStatus = sTouchBlock >= 2 || wantStripStatus();
                    if (isNav && sTouchBlock < 1) return;
                    if (isStatus && !fbStatus) return;
                    // 锁屏不门控：锁屏界面自己要用这些触摸
                    if (isKeyguardLockedCached()) return;
                    final boolean block;
                    if (isNav) {
                        block = navSoftHidden();
                    } else {
                        block = !sHard && (sHiddenMask & TYPE_STATUS_BARS) != 0 && !revealed();
                    }
                    if (!block) return;
                    final int cfg = notTouchableConfig(param.thisObject);
                    if (cfg == 0) return;
                    XposedHelpers.callMethod(wrapper, "setInputConfigMasked",
                            Integer.valueOf(cfg), Integer.valueOf(cfg));
                    XposedHelpers.callMethod(wrapper, "clearTouchableRegion");
                    final int n = C_TOUCHBLOCK.incrementAndGet();
                    if (n <= 5) log("touch blocked on bar type=" + type + " #" + n);
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            Set<?> u = XposedBridge.hookAllMethods(imCls, "populateInputWindowHandle", after);
            log("hook InputMonitor.populateInputWindowHandle -> " + u.size());
        } catch (Throwable t) {
            log("!! populateInputWindowHandle hook failed: " + t);
        }
    }

    /** 防抖 + 计数 + 真正展开。 */
    private static void fireExpand(String from) {
        final long now = SystemClock.uptimeMillis();
        if (now - sLastExpandAt < 900L) return;
        sLastExpandAt = now;
        final int n = C_EXPAND.incrementAndGet();
        if (n <= 20) log("transient(status) via " + from + " -> expandNotificationsPanel #" + n);
        expandShade();
    }

    /** 让系统把通知面板（下拉通知栏）展开。 */
    private static void expandShade() {
        // 路径 A：直接拿 statusbar 服务（StatusBarManagerService / IStatusBarService）
        try {
            Class<?> sm = XposedHelpers.findClass("android.os.ServiceManager", sCL);
            Object bar = XposedHelpers.callStaticMethod(sm, "getService", "statusbar");
            if (bar != null) {
                try {
                    XposedHelpers.callMethod(bar, "expandNotificationsPanel");
                    return;
                } catch (Throwable ignored) {
                }
                try {
                    XposedHelpers.callMethod(bar, "expandNotificationsPanel", Integer.valueOf(0));
                    return;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        // 路径 B：InsetsPolicy.mPolicy（= DisplayPolicy）→ getStatusBarManagerInternal()
        try {
            Object[] arr;
            synchronized (sPolicies) {
                arr = sPolicies.toArray();
            }
            for (Object policy : arr) {
                Object dp = XposedHelpers.getObjectField(policy, "mPolicy");
                if (dp == null) continue;
                Object sbmi = XposedHelpers.callMethod(dp, "getStatusBarManagerInternal");
                if (sbmi == null) continue;
                try {
                    XposedHelpers.callMethod(sbmi, "expandNotificationsPanel");
                    return;
                } catch (Throwable ignored) {
                }
                try {
                    XposedHelpers.callMethod(sbmi, "expandNotificationsPanel", Integer.valueOf(0));
                    return;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        log("expandShade: no usable path");
    }

    /* ══════════════════════════════════════════════════════════════
     * 挂点 9（v1.9.1 核心）：恢复状态栏窗口的「输入区」
     *
     *   真机现象：软隐藏后，桌面能从顶部下拉，进入 App 后下拉失效。
     *   逐字段对比「桌面 vs App」下的 StatusBar(type=2000) 窗口，发现
     *   仅两处不同（其余字段完全一致）：
     *       桌面 : isVisible=true   isReadyForDisplay()=true
     *       App   : isVisible=false  isReadyForDisplay()=false
     *   ⇒ App 内窗口没有输入区，顶部下拉手势被投递给下层 App 而非 SystemUI。
     *
     *   修复：软隐藏状态下，对该窗口强制 isVisible / isReadyForDisplay
     *   返回 true（复制桌面的可用态）；「看不见」仍由 prepareSurface 的
     *   alpha=0 负责，所以强制可见不会让状态栏真的画出来。
     *   仅作用 type=2000 主状态栏；硬隐藏(sHard)不介入。
     * ══════════════════════════════════════════════════════════════ */
    private void hookBarInputVisible(final XC_LoadPackage.LoadPackageParam lp) {
        Class<?> ws;
        try {
            ws = XposedHelpers.findClass("com.android.server.wm.WindowState", lp.classLoader);
        } catch (Throwable t) {
            log("!! WindowState not found for input-visible: " + t);
            return;
        }
        XC_MethodHook force = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    refreshConfig();
                    final int mask = sHiddenMask;
                    if (mask == 0 || sHard) return;
                    final int type = windowType(param.thisObject);
                    final boolean isStatus = (type == WT_STATUS_BAR
                            || type == WT_STATUS_BAR_ADDITIONAL);
                    final boolean isNav = (type == WT_NAV_BAR);
                    if (!isStatus && !isNav) return;
                    if (isStatus) {
                        if ((mask & TYPE_STATUS_BARS) == 0) return;
                    } else {
                        // v2.1：软隐藏的导航栏也要保持"可见/可显示"，
                        // 否则系统不给它派 Surface，揭示的那几秒画出来是空的。
                        if ((mask & TYPE_NAVIGATION_BARS) == 0 || !sNavSoft) return;
                    }
                    if (!Boolean.TRUE.equals(param.getResult())) {
                        param.setResult(Boolean.TRUE);
                        C_VIS.incrementAndGet();
                    }
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            Set<?> a = XposedBridge.hookAllMethods(ws, "isVisible", force);
            log("hook WindowState.isVisible -> " + a.size());
        } catch (Throwable t) {
            log("!! isVisible hook failed: " + t);
        }
        try {
            Set<?> b = XposedBridge.hookAllMethods(ws, "isReadyForDisplay", force);
            log("hook WindowState.isReadyForDisplay -> " + b.size());
        } catch (Throwable t) {
            log("!! isReadyForDisplay hook failed: " + t);
        }
    }

    /* ══════════════════════════════════════════════════════════════
     * 挂点 10（v1.9.2）：跟踪状态栏 InsetsSource 的「可见」状态
     *
     *   每次 InsetsSourceProvider 计算可见性后，若类型是 statusBars，
     *   就把 source.isVisible() 记到 sStatusRevealed。
     *   下拉/transient 触发时它会变 true —— 此时软隐藏应放行、不再压 alpha，
     *   否则用户下拉后状态栏依旧是透明的，看着像"没反应"。
     * ══════════════════════════════════════════════════════════════ */
    private void hookSourceVisibility(final XC_LoadPackage.LoadPackageParam lp) {
        Class<?> sp;
        try {
            sp = XposedHelpers.findClass("com.android.server.wm.InsetsSourceProvider",
                    lp.classLoader);
        } catch (Throwable t) {
            log("!! InsetsSourceProvider not found for reveal: " + t);
            return;
        }
        XC_MethodHook after = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object src = XposedHelpers.getObjectField(param.thisObject, "mSource");
                    if (src == null) return;
                    Object tObj = XposedHelpers.callMethod(src, "getType");
                    if (!(tObj instanceof Integer)) return;
                    final int ty = (Integer) tObj;
                    Object vis = XposedHelpers.callMethod(src, "isVisible");
                    boolean now = Boolean.TRUE.equals(vis);
                    if (ty == TYPE_STATUS_BARS) {
                        if (now != sStatusRevealed) {
                            sStatusRevealed = now;
                        }
                    } else if (ty == TYPE_NAVIGATION_BARS) {
                        // v2.1：系统自己把导航栏显示出来时（键盘弹起、transient 等）
                        // 也别再压 alpha，否则看着像"上滑没反应"。
                        if (now != sNavSourceRevealed) {
                            sNavSourceRevealed = now;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            Set<?> u = XposedBridge.hookAllMethods(sp, "updateVisibility", after);
            log("hook updateVisibility(reveal) -> " + u.size());
        } catch (Throwable t) {
            log("!! updateVisibility(reveal) hook failed: " + t);
        }
    }

    /** 读 WindowState.mAttrs.type；读不到返回 0。 */
    private static int windowType(Object windowState) {
        try {
            Object attrs = XposedHelpers.getObjectField(windowState, "mAttrs");
            if (attrs == null) return 0;
            return XposedHelpers.getIntField(attrs, "type");
        } catch (Throwable t) {
            return 0;
        }
    }

    /* ══════════════════════════════════════════════════════════════
     * 挂点 6（可选，autohide_hard=1）：InsetsSourceProvider.setServerVisible
     *   直接压死 insets 源的 server 可见性，栏永不可见（连瞬态滑出也没有）。
     * ══════════════════════════════════════════════════════════════ */
    private void hookHardHide(final XC_LoadPackage.LoadPackageParam lp) {
        String[] classes = {
                "com.android.server.wm.InsetsSourceProvider",
                "com.android.server.wm.ImeInsetsSourceProvider"
        };
        XC_MethodHook before = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    refreshConfig();
                    if (!sHard || sHiddenMask == 0) return;
                    Object src = XposedHelpers.getObjectField(param.thisObject, "mSource");
                    if (src == null) return;
                    Object tObj = XposedHelpers.callMethod(src, "getType");
                    if (!(tObj instanceof Integer)) return;
                    if (((Integer) tObj & sHiddenMask) == 0) return;
                    param.args[0] = Boolean.FALSE;
                    C_SERVER.incrementAndGet();
                } catch (Throwable ignored) {
                }
            }
        };
        for (String cn : classes) {
            try {
                Class<?> c = XposedHelpers.findClass(cn, lp.classLoader);
                Set<?> u = XposedBridge.hookAllMethods(c, "setServerVisible", before);
                log("hook " + cn + ".setServerVisible -> " + u.size());
            } catch (Throwable t) {
                log("hook " + cn + " failed: " + t);
            }
        }
    }

    /* ══════════════════════════════════════════════════════════════
     * 诊断：打印 insets 源真实可见性 + 各挂点命中计数
     * ══════════════════════════════════════════════════════════════ */
    private void hookDiagnostics(final XC_LoadPackage.LoadPackageParam lp) {
        Class<?> sp;
        try {
            sp = XposedHelpers.findClass("com.android.server.wm.InsetsSourceProvider",
                    lp.classLoader);
        } catch (Throwable t) {
            log("!! InsetsSourceProvider not found: " + t);
            return;
        }
        XC_MethodHook after = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    long now = SystemClock.uptimeMillis();
                    if (now - sLogAt < 5000L) return;
                    Object src = XposedHelpers.getObjectField(param.thisObject, "mSource");
                    if (src == null) return;
                    Object tObj = XposedHelpers.callMethod(src, "getType");
                    if (!(tObj instanceof Integer)) return;
                    int type = (Integer) tObj;
                    if (type != TYPE_STATUS_BARS && type != TYPE_NAVIGATION_BARS) return;
                    sLogAt = now;
                    boolean sv = XposedHelpers.getBooleanField(param.thisObject, "mServerVisible");
                    boolean cv = XposedHelpers.getBooleanField(param.thisObject, "mClientVisible");
                    Object vis = XposedHelpers.callMethod(src, "isVisible");
                    log("bar type=" + (type == TYPE_STATUS_BARS ? "status" : "nav")
                            + " serverVisible=" + sv + " clientVisible=" + cv
                            + " sourceVisible=" + vis
                            + " | mask=" + sHiddenMask + " hard=" + sHard
                            + " | hits setter=" + C_SETTER.get() + " query=" + C_QUERY.get()
                            + " force=" + C_FORCE.get() + " server=" + C_SERVER.get()
                            + " bar=" + C_BAR.get() + " nudge=" + C_NUDGE.get()
                            + " surf=" + C_SURF.get() + " trav=" + C_TRAV.get());
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            Set<?> u = XposedBridge.hookAllMethods(sp, "updateVisibility", after);
            log("hook updateVisibility(diag) -> " + u.size());
        } catch (Throwable t) {
            log("!! updateVisibility hook failed: " + t);
        }
    }

    /* ══════════════════════════════════════════════════════════════
     * 启动就绪：拿 Context（读 Settings.Global）
     *   + 心跳日志（60s）
     *   + 周期性补发 setWindowState（10s，v6 关键）
     * ══════════════════════════════════════════════════════════════ */
    private void hookSystemReady(final XC_LoadPackage.LoadPackageParam lp) {
        Class<?> ams;
        try {
            ams = XposedHelpers.findClass("com.android.server.am.ActivityManagerService",
                    lp.classLoader);
        } catch (Throwable t) {
            log("!! ActivityManagerService not found: " + t);
            return;
        }
        XposedBridge.hookAllMethods(ams, "systemReady", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object ctx = XposedHelpers.getObjectField(param.thisObject, "mContext");
                    if (ctx instanceof Context) {
                        sCtx = (Context) ctx;
                        log("got system context from AMS.mContext");
                    } else {
                        // ActivityThread 是 hidden API，编译期不可见 → 反射调用
                        Class<?> atClass = XposedHelpers.findClass("android.app.ActivityThread", sCL);
                        Object at = XposedHelpers.callStaticMethod(atClass, "systemMain");
                        if (at == null) {
                            at = XposedHelpers.callStaticMethod(atClass, "currentActivityThread");
                        }
                        Object c2 = at == null ? null : XposedHelpers.callMethod(at, "getSystemContext");
                        if (c2 instanceof Context) {
                            sCtx = (Context) c2;
                            log("got system context from ActivityThread.systemMain()");
                        }
                    }
                } catch (Throwable t) {
                    log("get context failed: " + t);
                }
                sCfgAt = 0L;
                refreshConfig();
                log("systemReady -> applying, " + snapshot());
                registerConfigReceiver();
                startLoops();
            }
        });
        log("hook systemReady");
    }

    /* ══════════════════════════════════════════════════════════════
     * App 开关通道（v8）
     *
     * 模块跑在 system_server 里，天然持有 WRITE_SECURE_SETTINGS，
     * 所以由它来落盘 Settings.Global；App 只负责发广播。
     *
     * 注册时带上 signature 权限 com.hidebar.lsp.permission.SET_CONFIG，
     * 第三方 App 拿不到这个权限，发的广播会被 AMS 直接拦掉。
     * ══════════════════════════════════════════════════════════════ */
    private void registerConfigReceiver() {
        final Context ctx = sCtx;
        if (ctx == null) {
            log("!! no context, config receiver NOT registered");
            return;
        }
        IntentFilter f = new IntentFilter();
        f.addAction(Config.ACTION_SET);
        f.addAction(Config.ACTION_QUERY);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(mConfigReceiver, f, Config.PERM_SET, null,
                        Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(mConfigReceiver, f, Config.PERM_SET, null);
            }
            log("config receiver registered (requires " + Config.PERM_SET + ")");
        } catch (Throwable t) {
            log("!! register receiver failed: " + t);
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    ctx.registerReceiver(mConfigReceiver, f, Context.RECEIVER_EXPORTED);
                } else {
                    ctx.registerReceiver(mConfigReceiver, f);
                }
                log("config receiver registered (no-permission fallback)");
            } catch (Throwable t2) {
                log("!! fallback register failed too: " + t2);
            }
        }
    }

    private final BroadcastReceiver mConfigReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            try {
                if (i == null) return;
                final String action = i.getAction();
                boolean ok = true;
                if (Config.ACTION_SET.equals(action)) {
                    final String key = i.getStringExtra(Config.EXTRA_KEY);
                    final String val = i.getStringExtra(Config.EXTRA_VALUE);
                    if (key == null || val == null) {
                        ok = false;
                    } else {
                        try {
                            Settings.Global.putString(c.getContentResolver(), key, val);
                            log("set " + key + "=" + val);
                        } catch (Throwable t) {
                            ok = false;
                            log("!! putString " + key + " failed: " + t);
                        }
                    }
                } else if (!Config.ACTION_QUERY.equals(action)) {
                    return;
                }
                // 立刻重读配置（mask 变化时 refreshConfig 内部会请求一次遍历）
                sCfgAt = 0L;
                refreshConfig();
                requestTraversal();
                reply(c, ok);
            } catch (Throwable t) {
                log("!! config receiver error: " + t);
            }
        }
    };

    /** 回执给 App：告诉它模块活着，以及当前真正生效的值。 */
    private void reply(Context c, boolean ok) {
        try {
            Intent r = new Intent(Config.ACTION_STATE);
            r.putExtra(Config.EXTRA_OK, ok);
            r.putExtra(Config.EXTRA_ENABLE, String.valueOf(getCfgInt(K_ENABLE, P_ENABLE, 1)));
            r.putExtra(Config.EXTRA_STATUS, String.valueOf(getCfgInt(K_STATUS, P_STATUS, 1)));
            r.putExtra(Config.EXTRA_NAV, String.valueOf(getCfgInt(K_NAV, P_NAV, 1)));
            r.putExtra(Config.EXTRA_HARD, String.valueOf(getCfgInt(K_HARD, P_HARD, 0)));
            r.putExtra(Config.EXTRA_MODVER, moduleVersion());
            r.setPackage(Config.PKG);
            c.sendBroadcast(r);
        } catch (Throwable t) {
            log("!! reply failed: " + t);
        }
    }

    /**
     * 本模块回执里带的版本号取自 {@link BuildConfig} —— 这是**编译期常量**，
     * 会被 javac 内联进 dex。
     * <p>
     * 这一点是关键：它反映的是"当前 system_server 内存里跑的这份 dex 是哪次编译的"，
     * 而不是"磁盘上装了哪个 APK"。装了新包但没重启时这两者不一致，
     * App 侧一比就知道要不要提示重启。
     * <p>
     * 千万别改成用 PackageManager 查 —— 那查到的是磁盘版本，永远跟 App 自己读的一样，比不出差异。
     */
    private static String moduleVersion() {
        return BuildConfig.VERSION_NAME + "|" + BuildConfig.VERSION_CODE;
    }

    private void startLoops() {
        try {
            final Handler h = new Handler(Looper.getMainLooper());
            // 周期性补发：保证 SystemUI 一定收到「隐藏栏窗口」（也能覆盖 SystemUI 重启）
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        sCfgAt = 0L;
                        refreshConfig();
                        if (sPendingTraversal) requestTraversal();
                        reConfirm();
                    } catch (Throwable ignored) {
                    }
                    h.postDelayed(this, RECONFIRM_MS);
                }
            }, 3000L);
            // 心跳日志
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        sCfgAt = 0L;
                        refreshConfig();
                        log("heartbeat " + snapshot()
                                + " | policies=" + sPolicies.size()
                                + " hits setter=" + C_SETTER.get()
                                + " query=" + C_QUERY.get()
                                + " force=" + C_FORCE.get()
                                + " server=" + C_SERVER.get()
                                + " bar=" + C_BAR.get()
                                + " nudge=" + C_NUDGE.get()
                                + " surf=" + C_SURF.get() + " trav=" + C_TRAV.get()
                                + " vis=" + C_VIS.get() + " hide=" + C_HIDE.get()
                                + " expand=" + C_EXPAND.get()
                                + " direct=" + (sDirectPanel ? 1 : 0)
                                + " gate=" + C_GATE.get()
                                + " reveal=" + C_REVEAL.get()
                                + " revealed=" + revealed()
                                + " alpha=" + C_ALPHA.get() + " sc=" + (sStatusSc != null ? 1 : 0)
                                + " gateOn=" + (sTouchGate ? 1 : 0)
                                + " peekOn=" + (sPeek ? 1 : 0)
                                + " peek=" + C_PEEK.get() + " collapse=" + C_COLLAPSE.get()
                                + " tpull=" + C_TPULL.get()
                                + " shadeVis=" + (sShadeVisible ? 1 : 0)
                                + " gp=" + C_GPROBE.get() + " gsw=" + C_GSWIPE.get());
                    } catch (Throwable ignored) {
                    }
                    h.postDelayed(this, 60000L);
                }
            }, 60000L);

            // v1.9.11：揭示渲染看门狗 —— revealed() 一翻转就立刻重下发状态栏 alpha，
            // 并在揭示窗口内每 500ms 续一次，保证"下拉 → 整条状态栏出现"真的看得见。
            // 不再依赖系统是否重绘（这正是"揭示了却什么都没出现"的根因）。
            Thread wd = new Thread(new Runnable() {
                @Override
                public void run() {
                    long sustainAt = 0L;
                    while (true) {
                        try {
                            final boolean r = revealed();
                            // v2.1：导航栏的揭示状态也纳入翻转判定，否则底边上滑唤出后
                            // 只靠 prepareSurface 补帧的话，静态画面下根本不会重绘 → 看不见。
                            final boolean rn = revealedNav();
                            final long now = SystemClock.uptimeMillis();
                            if (r != sLastAppliedReveal || rn != sLastAppliedNavReveal
                                    || ((r || rn) && now - sustainAt > 500L)) {
                                sustainAt = now;
                                applyRevealAlpha("watchdog");
                                requestTraversal();
                            }
                            // v1.9.12：让 DISABLE_EXPAND 与当前状态保持一致（揭示时解除、过期后重压）
                            syncDisable();
                        } catch (Throwable ignored) {
                        }
                        try {
                            Thread.sleep(200L);
                        } catch (InterruptedException ie) {
                            return;
                        }
                    }
                }
            }, "AutoHideRevealWatchdog");
            wd.setDaemon(true);
            wd.start();
            log("reveal watchdog started");
        } catch (Throwable t) {
            log("loops failed: " + t);
        }
    }

    /**
     * 直接给 SystemUI 补发「栏窗口状态 = HIDDEN」。
     * <p>
     * 走的是 InsetsPolicy$BarWindow.setVisible 内部用的同一条 API：
     * StatusBarManagerInternal.setWindowState(displayId, windowId, state)。
     */
    private void reConfirm() {
        final int mask = sHiddenMask;
        if (mask == 0) return;
        Object[] arr;
        synchronized (sPolicies) {
            arr = sPolicies.toArray();
        }
        if (arr.length == 0) return;
        for (Object policy : arr) {
            try {
                Object dp = XposedHelpers.getObjectField(policy, "mPolicy");
                if (dp == null) continue;
                Object sbmi = XposedHelpers.callMethod(dp, "getStatusBarManagerInternal");
                if (sbmi == null) continue;
                Object dc = XposedHelpers.getObjectField(policy, "mDisplayContent");
                if (dc == null) continue;
                Object idObj = XposedHelpers.callMethod(dc, "getDisplayId");
                if (!(idObj instanceof Integer)) continue;
                int displayId = (Integer) idObj;
                if ((mask & TYPE_STATUS_BARS) != 0) {
                    // 软隐藏：强制状态栏窗口 SHOWN（保留下拉手势）；硬隐藏才真正通知 HIDDEN。
                    int st = sHard ? ST_HIDDEN : ST_SHOWING;
                    XposedHelpers.callMethod(sbmi, "setWindowState",
                            displayId, WINDOW_STATUS_BAR, st);
                }
                if ((mask & TYPE_NAVIGATION_BARS) != 0) {
                    // v2.1：软隐藏时告诉 SystemUI 导航栏窗口仍 SHOWN，
                    // 否则它把视图自己藏了，底边上滑唤出来也是一片空白。
                    int st = (sNavSoft && !sHard) ? ST_SHOWING : ST_HIDDEN;
                    XposedHelpers.callMethod(sbmi, "setWindowState",
                            displayId, WINDOW_NAV_BAR, st);
                }
                C_NUDGE.incrementAndGet();
            } catch (Throwable ignored) {
            }
        }
    }

    /* ══════════════════════════════════════════════════════════════
     * 配置读取
     * ══════════════════════════════════════════════════════════════ */
    private static void refreshConfig() {
        long now = SystemClock.uptimeMillis();
        if (now - sCfgAt < 1000L) return;
        sCfgAt = now;
        int enable = getCfgInt(K_ENABLE, P_ENABLE, 1);
        int mask = 0;
        if (enable == 1) {
            if (getCfgInt(K_STATUS, P_STATUS, 1) == 1) mask |= TYPE_STATUS_BARS;
            if (getCfgInt(K_NAV, P_NAV, 1) == 1) mask |= TYPE_NAVIGATION_BARS;
        }
        sHiddenMask = mask;
        sHard = getCfgInt(K_HARD, P_HARD, 0) == 1;
        sDirectPanel = getCfgInt("autohide_direct_panel", "persist.autohide.direct_panel", 0) == 1;
        // v1.9.10：输入门控恢复默认开启 —— 配合自定义顶边下滑检测（hookGestureProbe/detectTopPull）：
        // 隐藏态状态栏窗口 NOT_TOUCHABLE ⇒ 第一次下滑触摸穿透、面板没机会被拖出，
        // 屏幕上只出现"整条状态栏 + 停留 REVEAL_MS"；揭示窗口内再下滑才放行面板。
        sTouchGate = getCfgInt("autohide_touch_gate", "persist.autohide.touch_gate", 1) == 1;
        sPeek = getCfgInt("autohide_peek", "persist.autohide.peek", 1) == 1;
        // v1.9.12：隐藏态 alpha 千分之一单位，默认 10 (=0.01)。用于实测"能被 InputDispatcher
        // 判给 StatusBar1"的最小 alpha。取值 1..1000；越大越容易被 SystemUI 吸收，但越显形。
        int ga = getCfgInt("autohide_ghost_alpha", "persist.autohide.ghost_alpha", 10);
        if (ga < 1) ga = 1;
        if (ga > 1000) ga = 1000;
        sGhostAlpha = ga / 1000f;
        // v1.9.12：吸收 + 压制（默认关）
        sAbsorb = getCfgInt("autohide_top_absorb", "persist.autohide.top_absorb", 0) == 1;
        // v1.9.13：状态栏「显示时间」——第 1 次下滑后状态栏整条停留多久再自动收起。
        // 同时决定"第二次下滑展开面板"的机会窗口。范围 500..10000ms，默认 3500。
        int rv = getCfgInt("autohide_reveal_ms", "persist.autohide.reveal_ms", 3500);
        if (rv < 500) rv = 500;
        if (rv > 10000) rv = 10000;
        sRevealMs = rv;
        // v2.2：顶部「占满全屏」按模式读 —— 0 关 / 1 浮层 / 2 挤压。
        // 老键 autohide_fullscreen 仍作兜底：没写过新键的用户 → 1 映射浮层、0 映射关闭。
        int fmode = getCfgInt(K_FULLMODE, P_FULLMODE, -1);
        if (fmode < 0) fmode = getCfgInt(K_FULL, P_FULL, 1) == 1 ? FM_OVERLAY : FM_OFF;
        if (fmode > FM_SQUEEZE) fmode = FM_SQUEEZE;
        sFullMode = fmode;
        // 旧字段继续维护成"占满是否开着"，老代码路径（日志、其它判断）不用改
        sFullscreen = sFullMode != FM_OFF;
        // v2.1：连底部 NAVIGATION_BARS 源一起清掉（默认开）
        sFullNav = getCfgInt(K_FULLNAV, P_FULLNAV, 1) == 1;
        // v2.1：导航栏软隐藏（默认开）——保留 Surface，只压 alpha，底边上滑才唤出
        sNavSoft = getCfgInt(K_NAVSOFT, P_NAVSOFT, 1) == 1;
        // v2.1.1：呼出时把内容顶开（默认开），否则三键会压住 App 底部的可点区域
        sNavSqueeze = getCfgInt(K_NAVSQ, P_NAVSQ, 1) == 1;
        final int tb = getCfgInt(K_TOUCHBLOCK, P_TOUCHBLOCK, 1);
        sTouchBlock = (tb < 0) ? 0 : (tb > 2 ? 2 : tb);
        // v2.0：状态栏背景色（ARGB 十进制）。用 Long 解析再窄化，负数/无符号写法都能吃下
        sBarColor = parseArgb(getCfgStr(K_BARCOLOR, P_BARCOLOR, "0"));
        int cm = getCfgInt(K_COLORMODE, P_COLORMODE, 1);
        sColorMode = (cm < 0) ? 0 : (cm > 2 ? 2 : cm);
        sColorAlways = getCfgInt(K_COLORALWAYS, P_COLORALWAYS, 0) == 1;
        // v2.1.2：导航栏背景色（和状态栏同格式：ARGB 十进制字符串，0 = 关闭）
        sNavColor = parseArgb(getCfgStr(K_NAVCOLOR, P_NAVCOLOR, "0"));
        // 位掩码：bit0=InsetsState getter 组，bit1=fillInsetsState，bit2=updateAboveInsetsState 就地剥，
        // bit3=允许就地剥内部字段（mFrozenInsetsState / mLastReportedInsetsState，MIUI 走这条路）。默认 3。
        int fm = getCfgInt(K_FSMODE, P_FSMODE, 3);
        sFsMode = (fm < 0) ? 0 : (fm > 15 ? 15 : fm);
        // v2.3：顺带清掉其它贴边条带源（tappableElement / mandatorySystemGestures…），默认开
        sZeroEdge = getCfgInt(K_ZEROEDGE, P_ZEROEDGE, 1) == 1;
        sDebugReveal = getCfgInt("autohide_debug_reveal", "persist.autohide.debug_reveal", 0) == 1;
        sBlk = getCfgStr(K_BLK, P_BLK, "");
        if (mask != sAppliedMask) {
            sAppliedMask = mask;
            // 关键：翻转开关本身不会触发 WMS 重新布局，栏的 Surface 会一直停在
            // 上一次的状态（开着就永远不显示、关了就永远不消失）。这里主动请求
            // 一次遍历，让 prepareSurfaceLocked 重新跑一遍，栏才能立刻跟上。
            sPendingTraversal = true;
            requestTraversal();
            log("mask changed -> " + Integer.toHexString(mask) + ", traversal requested");
        }
    }

    /** 抓住 WindowManagerService（WindowContainer 上有 mWmService 字段）。 */
    private static void captureWms(Object windowOrContainer) {
        if (sWms != null || windowOrContainer == null) return;
        try {
            Object wms = XposedHelpers.getObjectField(windowOrContainer, "mWmService");
            if (wms != null) {
                sWms = wms;
                log("captured WindowManagerService");
                if (sPendingTraversal) requestTraversal();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 请求一次 WMS 表面布局遍历（异步，不阻塞当前调用）。 */
    private static void requestTraversal() {
        try {
            Object wms = sWms;
            if (wms == null) return;
            Object placer = XposedHelpers.getObjectField(wms, "mWindowPlacerLocked");
            if (placer == null) return;
            XposedHelpers.callMethod(placer, "requestTraversal");
            sPendingTraversal = false;
            C_TRAV.incrementAndGet();
        } catch (Throwable ignored) {
        }
    }

    /**
     * v2.1.1：让所有窗口重算 insets。
     * <p>挤压模式下，导航栏揭示状态一翻转，App 拿到的底部 inset 就变了；
     * 但 insets 只在 relayout / 显式通知时才下发给 App ——
     * 光改图层 alpha 不会触发任何重排，屏幕上是看不到"顶开"的。
     */
    private static void pushInsetsChanged() {
        try {
            Object win = (sNavWs != null) ? sNavWs : sStatusWs;
            if (win == null) return;
            Object dc = XposedHelpers.callMethod(win, "getDisplayContent");
            if (dc == null) return;
            Object isc = XposedHelpers.getObjectField(dc, "mInsetsStateController");
            if (isc != null) {
                XposedHelpers.callMethod(isc, "notifyInsetsChanged");
                C_INSETS.incrementAndGet();
                return;
            }
            // 兜底：逐个窗口通知
            XposedHelpers.callMethod(dc, "forAllWindows",
                    new java.util.function.Consumer<Object>() {
                        @Override
                        public void accept(Object w) {
                            try {
                                XposedHelpers.callMethod(w, "notifyInsetsChanged");
                            } catch (Throwable ignored) {
                            }
                        }
                    }, Boolean.TRUE);
            C_INSETS.incrementAndGet();
        } catch (Throwable ignored) {
        }
    }

    /**
     * v2.1.1：请求刷新输入窗口信息。
     * <p>隐藏态我们把栏窗口的 touchableRegion 清空了（见 hookInputTouchBlock），
     * 揭示时必须让 InputMonitor 重算一遍，否则栏虽画出来了却点不动。
     */
    private static void requestInputWindowUpdate() {
        try {
            Object im = sInputMonitor;
            if (im == null) {
                Object win = (sNavWs != null) ? sNavWs : sStatusWs;
                if (win == null) return;
                Object dc = XposedHelpers.callMethod(win, "getDisplayContent");
                im = (dc == null) ? null
                        : XposedHelpers.getObjectField(dc, "mInputMonitor");
                if (im != null) sInputMonitor = im;
            }
            if (im == null) return;
            XposedHelpers.callMethod(im, "scheduleUpdateInputWindows");
        } catch (Throwable ignored) {
        }
    }

    /**
     * v2.1.1：算出本 ROM 上「不可触摸」对应的 InputConfig 位。
     * <p>不硬编码：直接问系统自己的映射表 ——
     * {@code InputConfigAdapter.getInputConfigFromWindowParams(type, flags, inputFeatures)}
     * 传 FLAG_NOT_TOUCHABLE(0x10) 进去，返回的就是那一位。
     * 兜底 8（本 ROM 反汇编实测值：LAYOUT_PARAM_FLAG_TO_CONFIG_MAP 里 0x10 → 8）。
     */
    private static int notTouchableConfig(Object inputMonitor) {
        if (sNotTouchableCfg != 0) return sNotTouchableCfg;
        int v = 8;
        try {
            Class<?> ica = XposedHelpers.findClass("com.android.server.wm.InputConfigAdapter",
                    inputMonitor.getClass().getClassLoader());
            Object r = XposedHelpers.callStaticMethod(ica, "getInputConfigFromWindowParams",
                    Integer.valueOf(0), Integer.valueOf(0x10), Integer.valueOf(0));
            int got = ((Integer) r).intValue();
            if (got != 0) v = got;
        } catch (Throwable ignored) {
        }
        sNotTouchableCfg = v;
        log("touch block: NOT_TOUCHABLE config bit = " + v);
        return v;
    }

    private static String snapshot() {
        return "enable/mask=" + Integer.toHexString(sHiddenMask)
                + " hard=" + sHard + " blk=[" + sBlk + "]";
    }

    private static int getCfgInt(String key, String prop, int def) {
        Context c = sCtx;
        if (c != null) {
            try {
                String s = Settings.Global.getString(c.getContentResolver(), key);
                if (s != null && s.length() > 0) return Integer.parseInt(s.trim());
            } catch (Throwable ignored) {
            }
        }
        try {
            String s = getPropRaw(prop, null);
            if (s != null && s.length() > 0) return Integer.parseInt(s.trim());
        } catch (Throwable ignored) {
        }
        return def;
    }

    private static String getCfgStr(String key, String prop, String def) {
        Context c = sCtx;
        if (c != null) {
            try {
                String s = Settings.Global.getString(c.getContentResolver(), key);
                if (s != null && s.length() > 0) return s.trim();
            } catch (Throwable ignored) {
            }
        }
        String s = getPropRaw(prop, null);
        return (s != null && s.length() > 0) ? s.trim() : def;
    }

    /** 读系统属性（实现见 Config，那儿不依赖任何 Xposed 类，App 进程也能用）。 */
    static String getPropRaw(String key, String def) {
        return Config.getPropRaw(key, def);
    }

    /* ══════════════════════════════════════════════════════════════
     * 过滤：不碰 SystemUI 自己的窗口（栏本体/通知栏），也不碰黑名单包。
     * ══════════════════════════════════════════════════════════════ */
    private static boolean isExcluded(Object windowState) {
        try {
            Object attrs = XposedHelpers.getObjectField(windowState, "mAttrs");
            if (!(attrs instanceof WindowManager.LayoutParams)) return false;
            String pkg = ((WindowManager.LayoutParams) attrs).packageName;
            if (pkg == null) return true;                 // 无包名：系统内部窗口，跳过
            if (PKG_SYSTEMUI.equals(pkg)) return true;    // SystemUI 本体不动
            String blk = sBlk;
            if (blk != null && blk.length() > 0) {
                for (String p : blk.split(",")) {
                    p = p.trim();
                    if (p.length() > 0 && pkg.equals(p)) return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /* 保留：便于将来定位 SparseArray/Rect 相关字段 */
    @SuppressWarnings("unused")
    private static void unusedRefs(SparseArray<?> a, Rect r) {
    }

    private static void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }
}
