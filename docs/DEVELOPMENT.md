# OneShade 开发文档

本项目是针对 OPD2413 指定固件的传统 Xposed / LSPosed 模块。本文以 `app/src/main/java/io/github/opd2413/ctrlcenter/DualShadeHook.java` 为实现依据；实机测试步骤见 [TESTING.md](TESTING.md)。

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
| `app/src/main/assets/xposed_init` | LSPosed 入口类声明 |
| `app/src/main/AndroidManifest.xml` | 应用名称、Xposed 元数据 |
| `app/src/main/res/values/arrays.xml` | 建议作用域，仅 `com.android.systemui` |
| `app/src/debug/` | debug 专用通知测试 Activity 与权限声明 |
| `scripts/recover-device.sh` | 指定设备卸载本模块并重启 SystemUI |
| `.github/workflows/build.yml` | debug / release 构建及 lint，不签名、不部署 |

构建配置：Gradle 8.13、Android Gradle Plugin 8.13.2、Java 源码级别 17、compile / min / target SDK 36。推荐使用 JDK 21；本次实测使用 JDK 21。Xposed API 82 是 `compileOnly` 依赖，不打包进 APK，运行时由框架提供。

```sh
export ANDROID_HOME='/你的/Android/SDK'
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/36.0.0:$PATH"
sdkmanager 'platforms;android-36' 'build-tools;36.0.0'
./gradlew :app:assembleDebug :app:assembleRelease :app:lintDebug :app:lintRelease --console=plain
```

首次构建需要访问 Google Maven、Maven Central、Xposed Maven 及 Gradle 分发服务。Wrapper 固定版本并检查分发包 SHA-256；CI 另校验 Wrapper JAR。不要把 SDK、JDK、Gradle 缓存提交到仓库。

| 产物 | 用途 |
| --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | 本地 debug 签名，包含测试 Activity |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | 无测试组件、未签名，不能直接安装 |
| GitHub Release 的 `OneShade-v*.apk` | 维护者正式签名，可安装 |
| `app/build/reports/lint-results-*.html` | 两个变体的静态检查报告 |

CI 不连接平板，不能证明私有方法、原生动画或真实触摸行为兼容。源码构建使用自己的签名，不会自动获得维护者签名。

## 3. 加载与兼容边界

`handleLoadPackage` 先检查包名 `com.android.systemui`、`Build.MODEL == OPD2413` 和 API 36。通过后注册 Hook，在页控制器的 `onInit` **完成之后**读取视图和控制器，再检查 SystemUI versionCode 是否为 `169912`。

初始化前 `shade` 为 `null`，注册的联动 Hook 不启动双栏。SystemUI 版本不匹配时不创建 `DualShade`。缺少类、方法或视图会记录错误；这不是可以无视的适配差异。

`DualShade.active()` 动态要求：

1. 当前实例没有被错误处理禁用。
2. 当前资源配置是横屏。
3. `isKeyguardVisible$1()` 返回 false。
4. `getEnableSeparateNotificationAndQS().getValue()` 返回 true。

型号、API 和 SystemUI 版本是代码保护条件；固件版本是实测记录，代码没有按完整固件字符串比较。相同 SystemUI versionCode 也不能替代其他固件的实机验证。

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
| pager `onInit` | after | 捕获完成初始化的对象，检查 SystemUI 版本 |
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

通知 `expand(true)` 在全局布局前会发出零高度更新，`mInstantExpanding` 对应的通知零进度不会被当成普通关闭处理。两个原生进度都不大于 0 时才清除共存和 closing 标记。这些字段是协调标记，不是证明所有快速重入手势安全的持久状态机。

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

`fail(error)` 写 Android Log 和 Xposed Log，禁用当前实例、恢复可恢复的布局属性并清空 `shade`。它不卸载 Hook；后续 Hook 因实例为空而停止联动。这不能保证所有系统故障都可恢复，Root / ADB 恢复路径仍有必要。

适配新固件时：

1. 记录型号、API、完整固件、SystemUI 版本和 LSPosed 版本，保留 USB 与恢复入口。
2. 从自己设备提取并在本地分析 SystemUI 及其 QS 插件；不要上传厂商 APK 或反编译源码。
3. 逐项核对类、字段、资源、方法签名和每个布尔参数的语义，包含两处手势结束互斥策略。
4. 检查原生动画入口、布局回调、页共存策略、触摸坐标和锁屏边界；不能仅修改 versionCode 保护。
5. 用 [测试矩阵](TESTING.md#3-实机测试矩阵) 覆盖左右入口、关闭路径、通知交互和不适用状态。
6. 记录未验证项与失败项，更新兼容表后再发布。

不要为消除视觉延迟，将“已经插值的进度”喂给另一条原生弹簧；这会形成二次滤波。不要用全局禁用动画、强制卡片可见或整体 alpha 来掩盖不同动画阶段。

## 10. 签名、版本与 GitHub 发布

应用 ID 保持 `io.github.opd2413.ctrlcenter`。修改 `app/build.gradle` 的 versionName，并递增 versionCode；标签采用 `v<versionName>`。仅名称／文档改动也需要新 versionCode 才能可靠覆盖安装。

维护者的正式证书 SHA-256：

```text
cd12320344cc8eed8a640de2486b4b3a4e423a21a7a421c3ef38bd706b31909e
```

`v0.4.1` 带有 Android v3 签名证书轮换证明，由此前本地开发签名授权正式证书接管。在目标 Android 16 平板上已覆盖安装；未卸载模块，应用 UID 保持不变。其他开发者自己的 debug 签名不在这条证明链中，不能保证覆盖安装。

首次创建自己的正式密钥应交互输入密码：

```sh
keytool -genkeypair -keystore /私有目录/release.p12 -storetype PKCS12 \
  -alias oneshade -keyalg RSA -keysize 3072 -validity 10000 \
  -dname 'CN=OneShade, O=YourName'
```

维护者后续发布使用受保护的密钥、密码文件和保存的轮换链；变量是私有文件路径，不是密码值：

```sh
APK='OneShade-v0.4.1.apk'
KEYSTORE='/私有目录/release.p12'
PASSWORD_FILE='/私有目录/password.txt'
LINEAGE='/私有目录/lineage.bin'
apksigner sign --ks "$KEYSTORE" --ks-key-alias oneshade \
  --ks-pass "file:$PASSWORD_FILE" --lineage "$LINEAGE" \
  --v1-signing-enabled false --v2-signing-enabled false \
  --v3-signing-enabled true --v4-signing-enabled false \
  --debuggable-apk-permitted false --out "$APK" \
  app/build/outputs/apk/release/app-release-unsigned.apk
apksigner verify --verbose --print-certs "$APK"
zipalign -c -P 16 -v 4 "$APK"
sha256sum "$APK" >SHA256SUMS
```

自己首次签名且不做轮换时省略 `--lineage`。需要从旧密钥轮换时，先使用 SDK 的 `apksigner rotate --old-signer ... --new-signer ...` 生成证明链；保留 installed-data 能力以允许覆盖安装，不打开 rollback 能力来绕过签名保护。参考工具自身 `rotate --help` / `sign --help`。

发布前必须检查：

- 两个变体构建、lint、恢复脚本检查，以及适用的实机测试。
- release manifest 无 `android:debuggable=true`、测试 Activity 或 `POST_NOTIFICATIONS` 权限。
- APK 含 `assets/xposed_init`，作用域只声明 SystemUI；签名验证和 zipalign 通过。
- 发布 APK 与实际测试安装包一致，校验和对应发布文件。
- Git 只包含源码、Wrapper、文档及已脱敏图片，不含密钥、密码、设备日志、系统 APK 或个人通知。
- 等待 GitHub CI 结果；记录 CI 无法覆盖的设备验证边界。
- 用 `gh release create v0.4.1 <APK> SHA256SUMS --target main --notes-file <发布说明>` 发布，再从 GitHub 下载校验。

私钥、密码和轮换链需要由维护者自行安全备份；私钥及密码不进入 Git、CI 或 Release。丢失签名密钥后无法凭源码生成可覆盖更新的官方 APK。

## 11. 来源与许可边界

调研过 LuckyTool 和 [Oxygen Customizer](https://github.com/DHD2280/Oxygen-Customizer)，后者的 Oplus Hook 方式提供参考。本项目以设备实际私有 API 为准，没有直接移植其完整模块；本仓库不分发厂商实现代码。

Gradle Wrapper 的脚本与 JAR 保留原有 Apache-2.0 声明。Xposed API 只用于编译。仓库自身尚未指定开源许可证，公开托管不等于授予额外使用／再分发许可；如要采用许可证，应由仓库所有者明确选择。
