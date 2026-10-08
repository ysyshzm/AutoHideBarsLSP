# AutoHideBars LSP

**在系统框架层（`system_server`）接管状态栏 / 导航栏的显隐，以及 App 的内容边界。**

全局生效，不需要给每个 App 单独勾选作用域。当前版本 **v2.4.0（versionCode 37）**。

> 本模块的所有行为都来自对真机 `/system/framework/services.jar` 的反汇编验证，
> 而不是照抄教程。Android 16 上大量流传的"沉浸方案"（`policy_control`、`BarController`）早就被删除或废弃，
> 照抄必然无效 —— 详见文末「实现原理」。

---

## 一、环境要求

| 项目 | 要求 |
|---|---|
| Root | 必需 |
| 框架 | LSPosed（兼容 Xposed API 82）及其追随者 |
| Android | 14 / 15 / 16；**主要开发与验证环境为 Android 16** |
| 作用域 | 只需勾选 **「系统框架」（system_server，包名 `android`，进程 `system`）** |

**验证机型**：小米澎湃 HyperOS OS3.0（V816，Android 16），1116×2480，density 480（努比亚 NX721J）。

> ⚠️ 兼容性提示：本模块有多个挂点是针对 **MIUI/HyperOS 定制过的 `WindowState`** 写的
> （例如它优先取 `mFrozenInsetsState`，而 AOSP 取 `getMergedInsetsState()`）。
> 其他 ROM 若行为不同，需要重新定位挂点，不是换个开关就能用。

---

## 二、安装

### 三步

1. 安装 APK：`releases/AutoHideBarsLSP-v2.4.0-debug.apk`
2. 打开 LSPosed 管理器 → 启用本模块 → **作用域勾选「系统框架」**；
   不需要给其他 App 勾选（App 端只负责写配置，真正干活的是 system_server 里那份 dex）。
3. **重启手机**。模块只在 `system_server` 启动时加载一次。

### 改完 App 界面不必重启，改了 HookEntry 才需要

- 只动 App 端 UI / 写配置 → 打开界面立刻生效；
- 动了 `HookEntry.java`（即 framework 侧逻辑）→ 必须重启。

主界面顶部的版本行会直接告诉你：内存里跑的模块版本与 APK 版本是否一致。
App 版本号每次编译都会变，所以**只改 UI 也会显示为"不一致"** —— 这种情况下忽略即可，
看这个更有意义：**关键是改动是否触及 `HookEntry.java`**。

### 重装 APK 后"模块似乎没生效"

LSPosed 把模块 APK 路径记在 `/data/adb/lspd/config/modules_config.db`（SQLite，`modules.apk_path`）。
Android 每次覆盖安装都会换一个 `/data/app/~~xxxx==/...` 目录，路径对不上就加载不到，
表现为：管理器里显示"已启用"，但一条日志都没有、完全没效果。

```bash
# 1) 实际路径
adb shell pm path com.hidebar.lsp

# 2) LSPosed 记录的路径
adb shell su -c "strings /data/adb/lspd/config/modules_config.db | grep hidebar"
```

处理办法（任选其一）：

- 在 LSPosed 管理器里把模块取消勾选再勾上（多数版本会重写 `apk_path`）；
- 或改完 SQLite 后回写两个库（注意 SELinux 与权限）：
  ```bash
  adb shell su -c "setenforce 0"
  adb shell su -c "cp <改好的 db> /data/adb/lspd/config/modules_config.db"
  adb shell su -c "cp <改好的 db> /data/adb/lspd/modules_config.db"
  adb shell su -c "setenforce 1"
  adb reboot
  ```

---

## 三、界面使用说明

打开 App 就是全部设置。主界面是一张开关卡 + 两个可折叠的扩展区。

### 主开关行

| 项目 | 说明 |
|---|---|
| 隐藏状态栏 | 总开关。关掉后立即恢复系统原生状态栏 |
| 隐藏导航栏 | 总开关。**开启后三键虚拟导航也会隐藏**，依赖三键的话请关掉它 |
| 隐藏时不响应触摸 | 隐藏态清空栏窗口的触摸区域，点击穿透给 App（见下文「顶部点不到」） |
| 硬隐藏 | 连边缘滑出的瞬态栏一并禁止。开启后系统栏将完全无法唤出，谨慎使用 |

### ⌄「隐藏状态栏」的扩展菜单

点标题右侧的 `⌄` 展开：

| 项目 | 说明 |
|---|---|
| 状态栏显示时间 | 顶边下滑唤出后停留多久自动收起（0.5s ~ 10s，默认 3.5s），下面有 2s / 3.5s / 5s / 8s 快捷按钮 |
| 应用占满全屏 | **关闭 / 浮层 / 挤压** 三选一，详见下一节 |
| 顺带清掉其它顶/底内边距 | 连 `tappableElement`、系统手势区这类"贴边条带"一起剥，对付按手势区算避让的 App |
| 状态栏背景颜色 | 在状态栏后面垫一层纯色图层；可选**始终显示**，用来盖住某些 App 顶部自绘的白条 |
| 进阶：隐藏细致参数 | 再折一层，含「隐藏透明度」（隐藏态 alpha，1‰~100‰，默认 10‰）与「顶边吸收 + 压制展开」 |

### ⌄「隐藏导航栏」的扩展菜单

| 项目 | 说明 |
|---|---|
| 导航栏背景颜色 | 同上，但只在导航栏被唤出时跟着显示（纯白界面下三键看不清时很有用） |
| 导航栏软隐藏（可上滑唤出） | 隐藏时只压 alpha、不移除窗口，因此可以从底边滑出来 |
| 呼出时把内容顶开（挤压式） | 唤出期间把底部高度还给 App，内容被顶上去，三键不会盖住按钮 |
| 底部也占满 | 把导航栏占的那条也还给 App，内容一路画到屏幕最底 |

---

## 四、「应用占满全屏」三种模式

| 模式 | 隐藏时的表现 | 唤出时的表现 | 代价 |
|---|---|---|---|
| **关闭** | 顶部留白（系统原生行为） | — | 没有沉浸效果 |
| **浮层** | 内容从 y=0 起画 | 状态栏**盖**在内容上，内容不重排 | 唤出那几秒顶部一条被压住 |
| **挤压** | 内容从 y=0 起画 | 顶部 104px 还给 App，内容被**顶下来**，状态栏是"挤"出来的实体条 | 唤出/收起时内容上下动一下 |

选哪个取决于你在不在意那一下重排：**看书、刷信息流用浮层**（不想抖），
**要点到顶部按钮就用挤压**（内容既看得见也点得到）。

---

## 五、所有配置键

优先读 `Settings.Global`，读不到时回退到系统属性。**全部实时生效，不需要重启**
（除非该改动由 `HookEntry` 首次引入）。

| Settings.Global | 系统属性（兜底） | 默认 | 说明 |
|---|---|---|---|
| `autohide_enable` | `persist.autohide.enable` | 1 | 总开关 |
| `autohide_status` | `persist.autohide.statusbar` | 1 | 隐藏状态栏 |
| `autohide_nav` | `persist.autohide.navbar` | 1 | 隐藏导航栏 |
| `autohide_hard` | `persist.autohide.hard` | 0 | 硬隐藏，真移除 Surface、连瞬态也禁止 |
| `autohide_blk` | `persist.autohide.blk` | 空 | 黑名单包名，逗号分隔（如桌面不沉浸） |
| `autohide_reveal_ms` | `persist.autohide.reveal_ms` | 3500 | 唤出后停留时长，夹在 500~10000 |
| `autohide_ghost_alpha` | `persist.autohide.ghost_alpha` | 10 | 隐藏态 alpha，千分之一单位 |
| `autohide_top_absorb` | `persist.autohide.top_absorb` | 0 | 顶边吸收 + 压制展开 |
| `autohide_fullscreen_mode` | `persist.autohide.fullscreen_mode` | 2 | 占满全屏：0 关 / 1 浮层 / 2 挤压 |
| `autohide_fullscreen` | `persist.autohide.fullscreen` | 1 | 老键，仅在未写过新键时折算 |
| `autohide_fullscreen_nav` | `persist.autohide.fullscreen_nav` | 1 | 底部也占满 |
| `autohide_nav_soft` | `persist.autohide.nav_soft` | 1 | 导航栏软隐藏（可上滑唤出） |
| `autohide_nav_squeeze` | `persist.autohide.nav_squeeze` | 1 | 唤出时把内容顶开 |
| `autohide_touch_block` | `persist.autohide.touch_block` | 2 | 清空隐藏态栏的触摸区：0 关 / 1 只导航栏 / 2 含状态栏 |
| `autohide_zero_edge` | `persist.autohide.zero_edge` | 1 | 连贴边条带一起剥 |
| `autohide_bar_color` | `persist.autohide.bar_color` | 0 | 状态栏背景色，ARGB 十进制；alpha=0 即关闭 |
| `autohide_bar_color_always` | `persist.autohide.bar_color_always` | 0 | 状态栏颜色始终显示（不只唤出时） |
| `autohide_nav_color` | `persist.autohide.nav_color` | 0 | 导航栏背景色，同上格式 |

命令行改法（立即生效）：

```bash
adb shell settings put global autohide_fullscreen_mode 1    # 切成浮层
adb shell settings put global autohide_reveal_ms 5000       # 停留 5 秒
adb shell settings put global autohide_touch_block 0        # 关闭触摸屏蔽
adb shell settings get global autohide_fullscreen_mode      # 读回当前值
```

获取全部当前值：

```bash
adb shell settings list global | grep autohide
```

---

## 六、调试与排错

### 日志

模块 tag 是 `LSPosedFramework`，内容形如
`(system)[com.hidebar.lsp,XposedBridge] AutoHideLSP: ...`。

```bash
adb logcat | grep AutoHideLSP
# 只看 hook 是否挂上
adb logcat | grep AutoHideLSP | grep "hook "
```

如果一条都看不到 → 模块没被加载（回看「重装后没生效」那一节）。

### 顶部内容点不到（v2.4.0 已修复）

症状：开了「应用占满全屏」后，App 内容顶到 y=0，但顶部那一条（搜索框等）**点不动**。

根因：输入按下 **z 序** 找命中窗口，`StatusBar` 排在 App 之上，
它的 `touchableRegion=[0,0][1116,104]` 完整覆盖屏幕顶部 ⇒ 落在 y<104 的点击全部派给 SystemUI。
**注意：alpha 只影响渲染，不影响落点归属** —— 状态栏压到 alpha=0.001 也照样吃掉点击。

确认方法：

```bash
adb shell dumpsys input | grep "^\s*[0-9]*: name="
```

正常情况下隐藏态的 `StatusBar1` 应该是：

```
inputConfig=NOT_VISIBLE | NOT_FOCUSABLE | NOT_TOUCHABLE | TRUSTED_OVERLAY
touchableRegion=<empty>
```

v2.4.0 起：**只要此刻顶部确实被剥给了 App，就无条件让出状态栏的触摸区**，
不再依赖 `autohide_touch_block` 的具体数值；唤出时自动恢复可触摸。
要把这条路完全交还系统，把「应用占满全屏」设为「关闭」即可。

### 某些 App 顶部留一条白边（例如手机 QQ）

这类 App **不看系统给的 insets**，而是自己按一个固定高度（多半是
`Resources.getDimensionPixelSize(status_bar_height)`）画留白。
证据方式：无论用浮层、挤压、还是干脆把状态栏整个恢复显示，
同一页面的顶部内容位置**完全一致**。

遇到这种 App：

- 用「状态栏背景颜色 + 始终显示」把那片白染成和 App 一致的颜色（视觉上不突兀，开销最小）；
- 或给该 App 单独写 hook（作用域勾选它，改它读到的状态栏高度）——本次未实现。

### 看不到任何效果（其它检查项）

- 作用域是不是勾了「系统框架」；
- 有没有重启（改过 HookEntry 的话）；
- App 是不是在黑名单 `autohide_blk` 里；
- 主界面点「重新读取当前状态」，看最下面的「当前生效配置」卡反读到的值。

---

## 七、实现原理（主要挂点）

这些结论全部来自对目标机型 `services.jar` 的反汇编验证。

1. **`WindowState.setRequestedVisibleTypes` / `isRequestedVisible`**
   —— 清掉栏位 bit，而不是硬压 `setServerVisible(false)`。
   后者会把系统的瞬态显示一起干掉，导致三键用户被彻底锁死。

2. **`InsetsState` getter 组（`getMergedInsetsState` / `getInsetsStateWithVisibilityOverride`）**
   —— 返回**副本**并把 `statusBars` / `navigationBars` 源剥掉，App 侧 insets 归零 ⇒ 内容铺满。
   > `getMergedInsetsState()` 返回的是内部 `mAboveInsetsState` 本体，**必须复制后再改**，就地改会污染 WMS。
   > `InsetsState.removeSource/peekSource` 按 **id** 索引而非 type，要用 `InsetsSource.getType(id)` 判类型。
   > MIUI 的 `getInsetsState()` 优先取 `mFrozenInsetsState`，所以还额外挂了 `freezeInsetsState()` 在冻结快照上补一刀。

3. **`WindowState.prepareSurfaceLocked`**
   —— 软隐藏：窗口保持存在，只把图层 alpha 压到极低值；每帧显式下发，并有 200ms 看门狗补发。

4. **`InputMonitor.populateInputWindowHandle`**
   —— 隐藏态对该 bar 窗口 `setInputConfigMasked(NOT_TOUCHABLE)` + `clearTouchableRegion()`，
   让点击穿透给下层 App。**改 `LayoutParams.flags` 那条老路在本 ROM 上无效**，别走回头路。

5. **`SystemGesturesPointerEventListener.onPointerEvent`**
   —— 自己实现顶边下滑（唤出状态栏）/ 底边上滑（唤出导航栏）检测。
   本 ROM 的 `DisplayPolicy` 三个 swipe 回调都 hook 不到，所以必须自己兜。

### 那些已经失效、不要再试的方案

| 老方案 | 结论 |
|---|---|
| `LayoutParams.systemUiVisibility` / `SYSTEM_UI_FLAG_*` | Android 16 上 `WindowState` 已无该 getter，服务端不再读 |
| `Settings.Global.policy_control = immersive.full=*` | services.jar 中已无 `PolicyControl` 类 |
| Hook `BarController.setBarShowingLw` | 类已被删除，显隐改由 Insets 管线负责 |

---

## 八、自行编译

```bash
# Windows / PowerShell
$env:JAVA_HOME = "C:\Users\<你>\java"          # 注意必须指向 JDK 根目录
gradle.bat assembleDebug                        # 或用 Android Studio 直接 Run
```

- 需要 JDK 17（`sourceCompatibility = JavaVersion.VERSION_17`），`compileSdk 34`。
- Xposed API 是 `compileOnly` 引入的（`de.robv.android.xposed:api:82`），运行时由框架提供。
- 版本号在 `app/build.gradle`（`versionCode` / `versionName`），模块会用 `BuildConfig.VERSION_NAME`
  回传给 App 做"是否要重启"的判断，**改完记得同步这两个值**。
- 产物：`app/build/outputs/apk/debug/app-debug.apk`。

---

## 九、已知限制

- 自绘状态栏高度的 App（如手机 QQ）不会受益，见上文。
- 目标 ROM 的 `WindowState` 是 MIUI 定制版，换 ROM 需要重新核对挂点。
- 硬隐藏模式下系统栏无法唤出，误开后可用命令救回来：
  `adb shell settings put global autohide_hard 0`。

---

## 十、风险提示

- 本模块工作在 system_server，改动会全局影响窗口与输入分发。
- 请确保有可用的恢复手段（TWRP / Magisk 安全模式等）再折腾。
- 建议先看懂 `HookEntry.java` 里的挂点再改动，不要盲改。
