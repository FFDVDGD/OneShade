# OneShade 开发文档

本文介绍构建、Hook、布局、动画和触摸路由。测试步骤见 [TESTING.md](TESTING.md)，签名与发布见 [LSPOSED.md](LSPOSED.md)。运行时实现位于 `app/src/main/java/io/github/ffdvdgd/oneshade/DualShadeHook.java`。

## 1. 目标与不做的事情

目标是在解锁、横屏、通知与 QS 分离模式下，让两个现有原生面板同时出现：左侧是真实通知，右侧是原生控制中心。QS 指 Quick Settings，即快捷设置。

- 不绘制替代通知、不复制通知数据、不使用悬浮窗或通知监听服务。
- 不修改系统 APK、数据库、锁屏认证、通知隐私设置或其他模块配置。
- 不创建常驻服务；模块代码由 LSPosed 加载到 SystemUI 进程。
- 不提供通用 OnePlus / ColorOS 适配层，不保证未验证设备或固件。
- 不强制所有动画逐帧相同，不修改原生弹簧参数。

## 2. 工程与构建

| 路径 | 职责 |
| --- | --- |
| `app/src/main/java/.../DualShadeHook.java` | 全部运行时 Hook、布局及触摸路由 |
| `app/src/main/resources/META-INF/xposed/java_init.list` | 现代 API 入口类声明 |
| `app/src/main/resources/META-INF/xposed/module.prop` | 最低／目标 API 102 与 `staticScope=true` |
| `app/src/main/resources/META-INF/xposed/scope.list` | 静态作用域，仅 `com.android.systemui` |
| `app/src/main/AndroidManifest.xml` / `res/values/strings.xml` | 应用名称与模块描述，不再声明旧 API 元数据 |
| `app/src/debug/` | debug 专用通知测试 Activity 与权限声明 |
| `scripts/recover-device.sh` | 指定设备卸载本模块并重启 SystemUI |
| `.github/workflows/build.yml` | debug / release 构建及 lint，不签名、不部署 |

构建配置：Gradle 8.13、Android Gradle Plugin 8.13.2、Java 源码级别 17、compile / min / target SDK 36。推荐使用 JDK 21。`io.github.libxposed:api:102.0.0` 使用 `compileOnly`，运行时由框架提供。

```sh
export ANDROID_HOME='/你的/Android/SDK'
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/36.0.0:$PATH"
sdkmanager 'platforms;android-36' 'build-tools;36.0.0'
./gradlew :app:assembleDebug :app:assembleRelease :app:lintDebug :app:lintRelease --console=plain
```

Windows 使用 `gradlew.bat`。首次构建需要访问 Google Maven、Maven Central 及 Gradle 分发服务；CI 同时校验 Wrapper、构建两个变体并运行 lint。

| 产物 | 用途 |
| --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | 本地 debug 签名，包含测试 Activity |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | 无测试组件、未签名，不能直接安装 |
| GitHub Release 的 `OneShade-v*.apk` | 维护者正式签名，可安装 |
| `app/build/reports/lint-results-*.html` | 两个变体的静态检查报告 |

安装调试包后需要重载 SystemUI；模块不支持热替换。debug 与正式包签名可能不同，不能直接覆盖安装。CI 只做构建和静态检查，不替代实机测试。

## 3. 加载与兼容边界

入口继承 `XposedModule`，在 `onPackageReady` 使用最终应用 ClassLoader，检查包名 `com.android.systemui` 与 `Build.MODEL == OPD2413`，不检查固件或 SystemUI 的精确版本。注册 Hook 后，在页控制器的 `onInit` **完成之后**读取视图和控制器，创建 `DualShade` 并记录实际 SystemUI 版本。

初始化前 `shade` 为 `null`，联动 Hook 不启动双栏。按名称枚举声明的方法并注册现代 interceptor；找不到必需 Hook 方法时立即报错，撤销已注册的本模块 Hook，避免留下部分联动。字段和调用方法使用缓存的 Java 反射，不引入旧 API 兼容桥。

`module.prop` 声明最低／目标 API 102 和 `staticScope=true`，`scope.list` 仅包含 `com.android.systemui`。框架不允许扩展作用域，用户仍需启用模块和系统界面。

`DualShade.active()` 动态要求：

1. 当前实例没有被错误处理禁用。
2. 当前资源配置是横屏。
3. `isKeyguardVisible$1()` 返回 false。
4. `getEnableSeparateNotificationAndQS().getValue()` 返回 true。

Android 16 是安装下限。新固件的私有 API 变化通过适配解决，不增加固件白名单；已测环境见[验证记录](TESTING.md#验证记录)。

## 4. 控制器、视图与调用关系

```diagram
┌────────────────────────────┐
│ Oplus pager / touch / fling│
└─────────────┬──────────────┘
              ▼
┌────────────────────────────┐
│ DualShadeHook / DualShade  │
│ conditions, routing, start │
└───────┬───────────┬────────┘
        ▼           ▼
┌──────────────┐ ┌───────────────────────┐
│ Notification │ │ OplusSeparateQSManager│
│ controller   │ │ native plugin / QS    │
└───────┬──────┘ └───────────┬───────────┘
        ▼                   ▼
┌──────────────┐ ┌───────────────────────┐
│ Native rows /│ │ Native tiles / sliders│
│ list springs │ │ and native springs    │
└──────────────┘ └───────────────────────┘
```

源类及核心对象：

- `com.oplus.systemui.separate.OplusPanelViewPagerController`：拥有两页根视图、通知控制器、QS manager、分离模式状态及触摸处理器。
- `com.android.systemui.shade.NotificationPanelViewController`：通知展开、收起、fling 和可见性生命周期。
- `com.oplus.systemui.separate.OplusSeparateQSManager`：控制原生 QS 插件的展开、收起和触摸分发。
- `com.oplus.systemui.plugins.qs.animator.QSPanelAnimatorManager`：接收 QS 原始触摸目标及 fling，不由模块重写弹簧输出。
- `notification_container_parent`：需要移动的通知内容容器。
- `notification_stack_scroller`：原生通知列表，用于通知行命中。
- `personal_tiles_container`：右栏静态起点，决定左侧可用空间。
- `simple_qs_footer/quick_qs_status_icons`：延迟加载的重复左侧图标组。

## 5. Hook 表

下列名称是该设备私有 API，尤其 `$1`、`$9` 等后缀可能随编译改变。

| 所属类／方法 | 阶段 | 用途 |
| --- | --- | --- |
| pager `onInit` | after | 捕获完成初始化的对象，记录实际 SystemUI 版本 |
| pager `updateQSExpandFraction` / `updateNotifyExpandFraction` | before + after | 在原始回调内抑制互斥收起，之后保存两个原生进度并联动 |
| notification `fling` | before | 第 4 个参数是 expand；在原生收起开始时联动另一栏 |
| QS animator `onFling` | before | 第 2 个参数是 expand；联动通知原生动画 |
| QS animator `onTouchMove` | before | 仅在模块分发 QS 触摸时读取原始目标，提前联动上滑收起 |
| pager `notifySeparateQSEndMotionEvent` / `notifyNTEndMotionEvent` | before + after | 在判定为展开的结束事件中抑制另一套互斥收起策略 |
| notification `collapseNotifPanelWithoutAnimate` / QS manager `collapseQSPanel` | before | 仅在上述回调深度大于 0 时阻止互斥收起 |
| pager `getDownOnQsArea` | before | 适用状态下初次左右下拉均走原生通知入口 |
| QS `SeparateQSHost.getNtIsFullCollapsed` | before | 分发 QS 触摸期间临时解除通知展开导致的按钮拦截 |
| notification `updateVisibility$9` | after | 刷新共存布局或恢复原布局 |
| pager `updateNotificationPanelAlpha` | before | 双栏期间避免页控制器隐藏通知页 |
| pager `TouchHandler.onInterceptTouchEvent` | before | 已展开后的新手势由模块选择实际命中栏 |
| pager `TouchHandler.onTouchEvent` | before | 按选定栏分发整个手势流 |

“before / after”指 interceptor 中 `chain.proceed()` 前后的逻辑。展开回调深度使用 `try/finally` 成对增减；跳过原方法直接返回，修改参数则复制参数数组后 `chain.proceed(args)`。

## 6. 状态与原生动画

以下字段只存于 SystemUI 内存，不写磁盘：

| 字段 | 含义 |
| --- | --- |
| `notifyFraction` / `qsFraction` | 两栏独立的原生展开进度，不用于逐帧驱动另一栏 |
| `dualVisible` | 当前需要双栏共存 |
| `closing` | 已启动联动收起，避免反复启动同一关闭动画 |
| `syncing` | 调用另一栏原生入口时的重入保护 |
| `expansionCallbackDepth` | 只在原始展开／展开方向手势结束回调内屏蔽互斥收起 |
| `leader` | 最近联动或触摸的栏；退出适用状态时决定关闭哪一栏，不是帧进度来源 |
| `routingTouch` | 在 ACTION_DOWN 决定是否接管此次手势，避免中途截断初始展开 |
| `touchOnQs` / `dispatchingQs` | 固定此次手势的目标及限定 QS 按钮拦截豁免范围 |

展开的调用顺序：

1. 顶部任一侧下拉走原生通知入口。
2. 首个正展开进度，或原生 expand fling，触发 `onFling(source, true)`。
3. 模块设置共存标记，并用 `syncing` 防止另一栏的同步回调递归联动。
4. 通知发起时调用 `expandQSPanel(true)`；QS 发起时调用通知 `expand(true)`。
5. 两套原生动画各自更新，模块只处理共存、位置、图标和必要的触摸路由。

关闭时，通知 `fling` 或 QS `onFling` 给出 expand=false。模块调用另一栏的通知 `collapse(false, 1f, cause)` 或 `collapseQSPanel(true, true, false, null)`。前者的 false 是“不延迟”，不是“禁用动画”；后者第一个 true 是使用动画。

空白上滑还要提前处理：QS 已在拖动阶段收起，若等 ACTION_UP 才联动通知，就会明显滞后。因此只在 `dispatchingQs` 期间读取 `onTouchMove` 的**输入目标**；开放手势条件下目标低于 `getDefaultSettingEngine()` 时启动一次通知原生收起。这里没有取 QS 动画输出作为通知动画输入。

通知 `expand(true)` 在全局布局前会发出零高度更新，`mInstantExpanding` 对应的通知零进度不会被当成普通关闭处理。两个原生进度都不大于 0 时才清除共存和 closing 标记。

**同步的是启动意图，不是相同曲线、时长或逐帧进度。** 不使用自定义 `ValueAnimator`，不手动调用弹簧 `skipToEnd`、`trySetCurrValue`，不强制卡片／开关始终可见。顶部页之间的横向切换位置和页透明度仍受共存 Hook 约束；各栏内部的原生位移、缩放、透明度、错峰显示和回弹由厂商代码负责。

## 7. 布局与重复 UI

`layoutLeft(view)` 将静态 `getLeft()` 与祖先滚动偏移累加到 pager 坐标，不使用含动画变换的屏幕坐标。右栏边界是 `personal_tiles_container` 的布局起点加左 padding。

实际布局计算只使用边界、原生容器宽度和静态布局起点：

```java
float targetLeft = (rightColumnLeft() - content.getWidth()) * 0.5f;
content.setTranslationX(targetLeft - layoutLeft(content));
```

容器宽度、通知数据和垂直排列不变。普通关闭仍保留居中位置，避免末帧恢复原 X 带来横向跳动；只有退出适用状态或错误禁用时调用 `restore()`。

通知侧简单标题区在 `onInit` 后才膨胀，因此重复图标延迟查找，保存原 alpha 后隐藏。只隐藏指定图标组，不隐藏时钟、通知设置按钮或右侧状态图标。`restore()` 恢复通知容器原 X 及图标原 alpha。

## 8. 触摸命中与生命周期

两个页根视图覆盖整屏，即使空白也会抢事件。初次展开保持原生手势；双栏已经出现后的新 ACTION_DOWN 才启用模块路由。

- 右栏或两侧空白：交给 QS manager 的原生 `dispatchTouchEvent`，复用轻点、上滑关闭和原生开关处理。
- 实际通知行：由原生 stack 的 `getChildAtPosition` 判断，保留点击、划除和滚动。
- 可操作的通知标题／页脚：递归检查可见、启用、点击或长按能力及全局可见矩形。
- 通知触摸：复制 MotionEvent，将屏幕坐标减去内容容器的实际屏幕位置，分发给通知容器，最后 recycle 副本。
- 同一个手势流固定目标；ACTION_UP / ACTION_CANCEL 清理接管标记。

这也绕过通知页原有的居中 QS 命中区，而不是修改通知行内部监听器。`getNtIsFullCollapsed` 豁免只覆盖 QS 分发调用栈，不全局改变通知状态。

## 9. 故障处理与适配新固件

`fail(error)` 写 Android Log 和框架日志，清空 `shade`，撤销所有本模块 Hook，禁用当前实例并恢复可恢复的布局属性。现代 Hook 使用 protective 异常模式，不把模块的反射错误当作正常原生执行结果。这不能保证所有系统故障都可恢复，Root / ADB 恢复路径仍有必要。

适配新固件时：

1. 记录型号、API、完整固件、SystemUI 版本和 LSPosed 版本，保留 USB 与恢复入口。
2. 从自己设备提取并在本地分析 SystemUI 及其 QS 插件；不要上传厂商 APK 或反编译源码。
3. 逐项核对类、字段、资源、方法签名和每个布尔参数的语义，包含两处手势结束互斥策略。
4. 检查原生动画入口、布局回调、页共存策略、触摸坐标和锁屏边界；通过实际 API 兼容性适配，而不是新增固件白名单。
5. 用[测试矩阵](TESTING.md#测试矩阵)覆盖左右入口、关闭路径、通知交互和不适用状态。
6. 记录未验证项与失败项，更新兼容表后再发布。

不要为消除视觉延迟，将“已经插值的进度”喂给另一条原生弹簧；这会形成二次滤波。不要用全局禁用动画、强制卡片可见或整体 alpha 来掩盖不同动画阶段。

## 来源与许可

调研过 LuckyTool 和 [Oxygen Customizer](https://github.com/DHD2280/Oxygen-Customizer)，后者的 Oplus Hook 方式提供参考。本项目以设备实际私有 API 为准，没有直接移植其完整模块；本仓库不分发厂商实现代码。

本项目采用 [MIT License](../LICENSE)，Copyright (c) 2026 FFDVDGD；使用或再分发时须保留许可证要求的版权与许可声明。

Gradle Wrapper 的脚本与 JAR 保留原有 Apache-2.0 声明，libxposed API 仅为 `compileOnly` 依赖、不打包进 APK。MIT 不替代第三方组件的原有许可，也不授予厂商 APK 或反编译实现代码的分发权。
