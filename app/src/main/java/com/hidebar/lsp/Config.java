package com.hidebar.lsp;

/**
 * 配置键与读取工具。
 * <p>
 * 【重要】本类刻意不引用任何 Xposed / LSPosed 类。
 * <p>
 * 原因：Xposed API 在 build.gradle 里是 {@code compileOnly}，不会打进 APK。
 * 普通 App 进程（比如打开模块说明页的 MainActivity）里没有
 * {@code de.robv.android.xposed.*}，一旦某个类被加载时牵出 HookEntry
 * （它 implements IXposedHookLoadPackage），就会抛
 * {@code NoClassDefFoundError: de.robv.android.xposed.IXposedHookLoadPackage}
 * 导致 Activity 启动即闪退。
 * <p>
 * 所以：MainActivity 只能通过 Config 拿配置，绝不能直接摸 HookEntry。
 */
public final class Config {

    private Config() {
    }

    /* ── Settings.Global 键名（首选，改动即时生效） ── */
    public static final String K_ENABLE = "autohide_enable";
    public static final String K_STATUS = "autohide_status";
    public static final String K_NAV = "autohide_nav";
    public static final String K_HARD = "autohide_hard";
    public static final String K_BLK = "autohide_blk";
    /** 状态栏显示强度（隐藏态图层 alpha，千分之一单位），默认 10 */
    public static final String K_GHOST = "autohide_ghost_alpha";
    /** 顶边手势「吸收 + 压制展开」，默认 0（关） */
    public static final String K_ABSORB = "autohide_top_absorb";
    /** 状态栏「显示时间」——第 1 次下滑唤出后停留多久自动收起，ms，默认 3500 */
    public static final String K_TIME = "autohide_reveal_ms";
    /**
     * v2.0：状态栏背景颜色，ARGB 整数（十进制字符串），默认 "0" = 关闭。
     * <p>非 0 时模块会在状态栏图层正下方插一张纯色图层，只在「揭示窗口」内出现，
     * 让白色时间/图标压在浅色 App 上时也能看清（v2.0 起配合「占满全屏」用）。
     */
    public static final String K_BARCOLOR = "autohide_bar_color";
    /**
     * v2.0.3：颜色图层**常显**。默认 "0" = 只在「揭示窗口」内出现。
     * <p>置 1 后，无论有没有揭示，颜色图层都铺在状态栏区域。
     * 这是给酷安这类「自己按 status_bar_height 留白、根本不理 insets」的 App 兜底的：
     * 顶部那条不再是一片空白，而是自定义颜色。
     * <p>代价：对已经顶到 y=0 的 App，会盖住最上面 104px 的内容，所以默认关。
     */
    public static final String K_COLORALWAYS = "autohide_bar_color_always";
    /**
     * v2.1.2：导航栏背景色（和状态栏同一套调色板），默认 "0" = 关闭。
     * <p>导航栏呼出时它是「浮」在 App 内容上的，背景完全透明 ——
     * 遇到纯白/浅色界面，三键图标就糊在背景里看不清。
     * 设一个背景色之后，三键底下有底色衬着，任何界面都能看清。
     * <p>取值同 {@link #K_BARCOLOR}：ARGB 十进制字符串（0 = 关闭）。
     * 显示时机固定跟随导航栏的「揭示」状态（不受 K_COLORALWAYS 影响，
     * 免得常显一直盖住 App 底部）。
     */
    public static final String K_NAVCOLOR = "autohide_nav_color";
    /**
     * v2.0：状态栏隐藏时把顶部那块还给 App（App 内容从 y=0 开始画），默认 "1"。
     * <p>做法是把下发给应用窗口的 InsetsState 里 STATUS_BARS 源清掉，
     * App 不再给状态栏留内边距；状态栏揭示时改为「浮层」盖在内容上，内容不重排。
     */
    public static final String K_FULL = "autohide_fullscreen";
    /**
     * v2.2：「应用占满全屏」的**模式**，取代原来的单一开关，默认 "1"（浮层）。
     * <p>取值：<br>
     * · "0" = 关闭（顶部留白，跟系统默认一样）；<br>
     * · "1" = 浮层式（v2.0 的老行为）：把顶部 inset 一直剥掉，App 从 y=0 开始画；
     *   状态栏下拉揭示时是"盖"在内容上的浮层，内容不重排、不抖动 ——
     *   代价是揭示期间顶部那一小条内容会被盖住。<br>
     * · "2" = 挤压式（和导航栏 {@link #K_NAVSQ} 同一套思路）：平时照样占满，
     *   但**揭示期间把顶部 inset 还回去**，App 内容被顶下来 104px、重新留出状态栏的位置，
     *   状态栏是"挤"出来的实体条而不是浮层 —— 顶部内容不会被盖，
     *   缺点就是揭示/收起时内容会重排（上下动一下）。<br>
     * 没设置过本键时回落到老键 {@link #K_FULL}（1 → 模式 1，0 → 模式 0），老用户无感。
     */
    public static final String K_FULLMODE = "autohide_fullscreen_mode";
    /**
     * v2.0 调试：颜色图层几何方式。0 = 只设 bufferSize；1 = bufferSize + crop（默认）。
     * <p>SurfaceFlinger 的纯色图层没有 buffer，尺寸到底认哪个字段随版本有别，
     * 留这个开关是为了**不重新编译就能现场试**，免得又走一轮"改代码+重启"。
     */
    public static final String K_COLORMODE = "autohide_bar_color_mode";
    /**
     * v2.0 调试：「占满全屏」的挂点**位掩码**。
     * bit0(1) = InsetsState getter 组（getInsetsState / getMergedInsetsState /
     * getCompatInsetsState / getInsetsStateWithVisibilityOverride，一律换副本返回）；
     * bit1(2) = fillInsetsState(out, boolean) 就地剥；
     * bit2(4) = updateAboveInsetsState 就地剥（会改 WMS 内部 mAboveInsetsState，
     *          覆盖面最广但风险也最大，默认不开）。
     * 默认 3 = 前两条。0 = 整个功能关掉。7 = 三条全开。
     * <p>Android 16 把 computeFrameLw 拿掉了，insets 的下发路径只能实测确认，
     * 留这个开关是为了失败时能当场二分，而不是再来一轮编译重启。
     * <p>已用 dexdump 核实：InsetsState 的 mSources 是 **按 id 索引** 的
     * SparseArray（removeSource/peekSource 吃 id，sourceIdAt 返回 keyAt），
     * 而 InsetsSource 有 createId(owner,index,type) 与静态 getType(id)，
     * 即 id ≠ type ⇒ 必须遍历 + getType() 判类型，不能拿 type 直接当 key。
     */
    public static final String K_FSMODE = "autohide_fs_mode";
    /**
     * v2.1：导航栏也用「软隐藏」，默认 "1"。
     * <p>之前导航栏是硬隐藏（isOnSurface=false + Surface 真移除），
     * 结果就是用户说的「上滑根本出不来」——输入窗口没了，底边手势没人接。
     * 置 1 后照搬状态栏那套：窗口保持 SHOWN、只把图层 alpha 压到极小值，
     * 底边上滑由我们自己检测并打开揭示窗口，栏就真的浮出来。
     * <p>置 0 退回旧行为（硬隐藏）。
     */
    public static final String K_NAVSOFT = "autohide_nav_soft";
    /**
     * v2.1：「占满全屏」是否连**底部**一起还给 App，默认 "1"。
     * <p>置 1 时除了 STATUS_BARS，还会把下发给应用窗口的 InsetsState 里的
     * NAVIGATION_BARS 源一起清掉 —— App 内容一路画到屏幕最底，
     * 导航栏揭示时以「浮层」方式盖在内容上，不触发重排（和顶部同一套逻辑）。
     * <p>用三键导航且希望底部留白时置 0。
     */
    public static final String K_FULLNAV = "autohide_fullscreen_nav";
    /**
     * v2.1.1：导航栏**呼出时把内容顶开**（挤压式），默认 "1"。
     * <p>纯浮层式的毛病：导航栏浮出来后会盖住 App 最底部那一条，
     * 正好压在 App 自己的底部按钮/输入框上，导致按不到。
     * 置 1 后，揭示窗口内暂停剥离底部 inset —— App 内容让出 142px 被"顶上去"，
     * 三键不再压住任何可点区域；收起后再回到占满全屏。
     * <p>置 0 = 始终浮层（内容不重排，但底部会被盖住）。
     */
    public static final String K_NAVSQ = "autohide_nav_squeeze";
    /**
     * v2.1.1：隐藏态下**屏蔽系统栏的触摸**，默认 "1"。
     * <p>软隐藏只把图层 alpha 压到 ~0，窗口还在、touchableRegion 还在，
     * 所以"看不见但按得到" —— 盲按三键位置照样返回桌面/切后台。
     * 本开关在 InputMonitor.populateInputWindowHandle 里把栏窗口的
     * touchableRegion 清空，让隐藏态的点击穿透给下层 App。
     * <p>取值：0 = 关（保持原样）；1 = 只屏蔽导航栏；2 = 状态栏 + 导航栏都屏蔽。
     */
    public static final String K_TOUCHBLOCK = "autohide_touch_block";
    /**
     * v2.3：剥离时**顺带清掉其它"贴边的系统条带源"**，默认 "1"。
     * <p>起因：手机 QQ 这类 App 即使 STATUS_BARS 已经清成 0，顶部still 留一条
     * 104px 白边。真机 dumpsys 抓到的原因是：**同一侧还有别的源顶着 104** ——
     * <pre>
     *   InsetsSource type=statusBars              frame=[0,0][0,0]        visible=false
     *   InsetsSource type=tappableElement         frame=[0,0][1116,104]   visible=true
     *   InsetsSource type=mandatorySystemGestures frame=[0,0][1116,104]   visible=true
     * </pre>
     * 腾讯系 App 普遍用 {@code WindowInsets.Type.tappableElement()} /
     * {@code mandatorySystemGestures()} 算"顶部要避让多少"，只剥 statusBars 对它无效。
     * <p>置 1 后：凡是「全宽、贴顶（frame.top==0）」的源，在顶部隐藏期间一起清掉；
     * 「全宽、贴底」的同理（跟着 {@link #K_FULLNAV} 走）。
     * <p>判据只用几何（全宽 + 贴边 + 厚度 ≤ 400px），不认识具体类型常量 ——
     * 免得再赌 ROM 里 IType 的数值。
     */
    public static final String K_ZEROEDGE = "autohide_zero_edge";

    /* ── 系统属性（兜底，root/setprop 写入） ── */
    public static final String P_ENABLE = "persist.autohide.enable";
    public static final String P_STATUS = "persist.autohide.statusbar";
    public static final String P_NAV = "persist.autohide.navbar";
    public static final String P_HARD = "persist.autohide.hard";
    public static final String P_BLK = "persist.autohide.blk";
    public static final String P_GHOST = "persist.autohide.ghost_alpha";
    public static final String P_ABSORB = "persist.autohide.top_absorb";
    public static final String P_TIME = "persist.autohide.reveal_ms";
    public static final String P_BARCOLOR = "persist.autohide.bar_color";
    public static final String P_FULL = "persist.autohide.fullscreen";
    public static final String P_FULLMODE = "persist.autohide.fullscreen_mode";
    public static final String P_COLORMODE = "persist.autohide.bar_color_mode";
    public static final String P_COLORALWAYS = "persist.autohide.bar_color_always";
    public static final String P_NAVCOLOR = "persist.autohide.nav_color";
    public static final String P_FSMODE = "persist.autohide.fs_mode";
    public static final String P_NAVSOFT = "persist.autohide.nav_soft";
    public static final String P_FULLNAV = "persist.autohide.fullscreen_nav";
    public static final String P_NAVSQ = "persist.autohide.nav_squeeze";
    public static final String P_TOUCHBLOCK = "persist.autohide.touch_block";
    public static final String P_ZEROEDGE = "persist.autohide.zero_edge";

    /* ══════════════════════════════════════════════════════════════
     * App 开关 ↔ system_server 模块的通信
     *
     * 难点：普通 App 既写不了 Settings.Global（要 WRITE_SECURE_SETTINGS），
     *      也写不了 persist 属性（要 root），所以没法直接改配置。
     *
     * 解法：App 发广播 → 运行在 system_server 里的模块收到后，用它自己的
     *      system 权限写 Settings.Global → 配置立刻生效。
     *
     * 安全：模块注册 receiver 时要求发送者持有下面的 signature 级权限，
     *      只有同签名的本 App 能触发，第三方 App 发不出来。
     * ══════════════════════════════════════════════════════════════ */
    public static final String PERM_SET = "com.hidebar.lsp.permission.SET_CONFIG";

    /** App → 模块：把某个配置键设成 "1"/"0" */
    public static final String ACTION_SET = "com.hidebar.lsp.action.SET_CONFIG";
    /** App → 模块：问一声在不在、当前值是多少 */
    public static final String ACTION_QUERY = "com.hidebar.lsp.action.QUERY_CONFIG";
    /** 模块 → App：回执（带上当前实际生效的值） */
    public static final String ACTION_STATE = "com.hidebar.lsp.action.CONFIG_STATE";

    public static final String EXTRA_KEY = "key";
    public static final String EXTRA_VALUE = "value";
    public static final String EXTRA_OK = "ok";
    /** 回执里：各键当前生效值（"1"/"0"） */
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_NAV = "nav";
    public static final String EXTRA_ENABLE = "enable";
    public static final String EXTRA_HARD = "hard";
    /**
     * MainActivity → ColorPickerActivity：这次调的是哪条栏的背景色。
     * <p>取 {@link #TARGET_NAV} 时改的是 {@link #K_NAVCOLOR}，其余（含缺省）改 {@link #K_BARCOLOR}。
     */
    public static final String EXTRA_TARGET = "target";
    public static final String TARGET_NAV = "nav";
    /**
     * 回执里：模块（system_server 里真正在跑的那份代码）的版本，格式 "versionName|versionCode"。
     * <p>
     * 用来发现"App 装了新的、但模块还是旧的"这种最常见的"改了不生效"场景 ——
     * 模块只在 system_server 启动时加载一次，装新 APK 不重启是不会生效的。
     */
    public static final String EXTRA_MODVER = "modver";

    /** 本 App 的包名（模块回执时用它做显式投递） */
    public static final String PKG = "com.hidebar.lsp";

    /**
     * 读系统属性。用反射而不是 {@code android.os.SystemProperties} 直接调用，
     * 因为后者是 hidden API，compileSdk 34 下编译期不可见。
     */
    public static String getPropRaw(String key, String def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class, String.class).invoke(null, key, def);
            return v instanceof String ? (String) v : def;
        } catch (Throwable t) {
            return def;
        }
    }
}
