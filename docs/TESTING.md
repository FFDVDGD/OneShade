# OneShade 验证与故障排查

CI 只能检查构建和 lint。运行时依赖厂商私有 API，通知命中、空白手势和动画必须在兼容设备上验证。本文件中的坐标、序列号和路径都应按自己的设备填写，不要复制其他人的真实设备标识。

## 1. 测试前保护设备

1. 只选择目标 OPD2413，保持已授权 USB ADB 和可用的 Root 恢复路径。
2. 记录当前固件、SystemUI、LSPosed、屏幕方向、分离模式和相关模块；不要为测试关闭其他模块。
3. 保存正在操作的内容。重启 SystemUI 可能回到锁屏，必须正常解锁，不绕过认证。
4. 不清空用户通知、不点击用户消息。删除测试通知只使用模块自己的 fixture。
5. 若临时修改屏幕方向或 USB 常亮，记录原值，结束时恢复；不要记录解锁凭据。

```sh
adb devices -l
SERIAL='<目标 OPD2413 的 ADB 序列号>'
adb -s "$SERIAL" shell getprop ro.product.model
adb -s "$SERIAL" shell getprop ro.build.version.sdk
adb -s "$SERIAL" shell dumpsys package com.android.systemui
adb -s "$SERIAL" shell pidof com.android.systemui
```

所有 ADB 命令都带 `-s "$SERIAL"`，避免操作另一个手机。安装后的作用域只勾选 `com.android.systemui`。

## 2. debug 通知 fixture

`VerificationActivity` 仅在 debug 变体存在，没有桌面入口。它由 `android.permission.DUMP` 保护，不向普通第三方应用开放。release APK 没有该 Activity 或通知发布权限。

下列操作适用于安装了 debug APK 的开发设备。部分 Root 模块会过滤 shell 权限，需要 Root 才能启动 fixture：

```sh
adb -s "$SERIAL" shell 'su -c "pm grant io.github.opd2413.ctrlcenter android.permission.POST_NOTIFICATIONS"'
adb -s "$SERIAL" shell 'su -c "am start -S -n io.github.opd2413.ctrlcenter/.VerificationActivity --ez post true --ei count 12"'
```

fixture 发布编号 1…12 的低重要性通知。点击日志为 `DualShadeVerification: Clicked notification N`，点击后的 Activity 显示编号，通知设置了自动删除。左右划除应只移除对应编号。ColorOS 可能把多个测试通知自动合并；先按当前截图展开组，再定位实际卡片，不复用过时坐标。

```sh
adb -s "$SERIAL" shell logcat -d -v brief -s 'DualShadeVerification:*'
```

结束时只取消本模块的通知：

```sh
adb -s "$SERIAL" shell 'su -c "am start -S -n io.github.opd2413.ctrlcenter/.VerificationActivity --ez cleanup true"'
```

该 Activity 内的 `cancelAll()` 作用于模块自己的 NotificationManager，不是系统的“清空所有通知”。换用独立 debug 签名可能无法覆盖官方 release 签名；不要为跑 fixture 强制卸载正在使用的正式模块，优先使用专门的开发设备。

## 3. 实机测试矩阵

| 输入／状态 | 预期结果 | 容易遗漏的错误 |
| --- | --- | --- |
| 解锁横屏，顶部左侧下拉 | 原生通知和 QS 同时出现 | 只出现通知，或互斥策略立刻收起另一页 |
| 同状态，顶部右侧下拉 | 仍走通知初始入口，两栏出现 | 原生 QS 入口造成明显延迟 |
| 左、右空白分别轻点 | 两栏回到原页面，无单边残留 | 满屏通知根视图吞掉空白点击 |
| 左、右空白分别上滑 | 拖动阶段开始联动原生收起 | 通知等到松手才开始收起 |
| 返回键关闭 | 两栏都结束，无幽灵根视图 | 最后触摸栏之外的关闭意图未联动 |
| 测试通知点击 | 打开对应编号，通知自动删除 | 移动容器后的坐标未换算 |
| 测试通知单条左右划除 | 仅目标测试通知消失 | 被误当空白关闭或错误命中相邻行 |
| 展开通知组、列表上下滚动 | 显示较低编号，QS 保持可见 | 把全高 stack 当成所有空白都可操作 |
| QS 设置按钮及其他可操作控件 | 打开相应原生页面／执行原生动作 | 通知展开导致 QS 拦截每个按钮 |
| 竖屏／锁屏／关闭分离模式 | 不启用双栏，保留系统隐私策略 | 模块坐标或共存状态残留 |
| 普通关闭末帧与再次打开 | 通知静态居中位置不恢复右移 | 使用含缩放的屏幕坐标计算布局 |
| 快速下拉、连续收起再拉、途中反向 | 记录实际结果，不假设与慢手势一致 | 两个原生生命周期和新触摸交错 |

不要为了验证开关而改变网络、飞行模式等非必要设置；优先使用设置按钮或可恢复的控件。若需要改变设置，先记录并恢复原值。

基准开合：左右交替 10 次，展开后等一秒、收起后等一秒，每次截图检查两栏，记录通知进度及 SystemUI PID。还要单独测试半秒或更短间隔；基准通过不能证明快速连续手势通过。

```sh
adb -s "$SERIAL" shell dumpsys activity service com.android.systemui/.SystemUIService
adb -s "$SERIAL" shell pidof com.android.systemui
```

Dump 中的 `mExpandedFraction` 属于通知控制器，不能单独证明 QS 已显示／收起。需要同时检查画面和 native 状态；PID 不变也不能替代交互验证。

## 4. 高帧率录屏与回弹检查

低帧率 GIF 无法可靠显示回弹。优先保存原始 MP4，检查动态阶段帧时间，而不是只读取标称帧率。Android screenrecord 在静止时可能少产帧，整个视频平均 FPS 不代表动画 FPS。

```sh
adb -s "$SERIAL" shell screenrecord --size 1696x1200 --bit-rate 24000000 \
  --time-limit 12 /data/local/tmp/oneshade-review.mp4
adb -s "$SERIAL" pull /data/local/tmp/oneshade-review.mp4 ./oneshade-review.mp4
ffprobe -v error -select_streams v:0 \
  -show_entries frame=best_effort_timestamp_time -of csv=p=0 oneshade-review.mp4
```

录屏运行期间在设备上操作，或由另一个终端发送适合当前画面的手势。尺寸示例适用于本次横屏设备，可降低分辨率换取记录性能。若 shell 录屏被设备上其他模块限制，可以使用 Root screenrecord 并在拉取前设置文件可读权限；不要因此修改那些模块的配置。

检查窗口包括：拉下后松手、原生回弹与停稳、上滑开始、松手后的收起、返回键／点击引发关闭、末帧和下一次拉下。识别横向布局恢复跳动与原生卡片缩放的区别。同步启动不意味着最后一帧时刻一致。

脱敏重新编码时，应指定编码时间基准并保留帧时间。例如原片 time_base 为 1/90000：

```sh
ffmpeg -i oneshade-review.mp4 -vf '<不透明脱敏遮罩>' \
  -c:v libx264 -crf 18 -enc_time_base 1:90000 -fps_mode passthrough \
  -an -movflags +faststart review-redacted.mp4
ffmpeg -i review-redacted.mp4 -vf 'setpts=4*PTS' \
  -c:v libx264 -crf 18 -enc_time_base 1:90000 -fps_mode passthrough \
  -an -movflags +faststart review-quarter-speed.mp4
```

遮罩必须按实际画面制作，不能直接运行占位符。只指定 passthrough 而保留默认编码 time_base，仍可能把 60 FPS 动态帧量化成更低频率。导出后逐帧比对数量和 PTS；慢放只延长时间，不插帧。检查实际导出文件而不只是原片，确认运动过程中也没有露出隐私。

需遮挡通知消息、联系人／群名称、Wi-Fi SSID、设备主机名和其他识别信息。审核后删除设备与本地原始捕获；不要把原始视频、厂商 APK 或个人数据提交到 Git 或 GitHub Release。

## 5. 已执行验证与未覆盖项

### 0.4 的实机基准

环境：OPD2413，固件 `OPD2413_16.0.9.400(CN01)`，ColorOS 16.1，Android 16，SystemUI `16.99.12` / `169912`，LSPosed IT `2.1.1-it (7789)`，KernelSU。

- debug 构建、lint 通过：0 错误，4 项既有警告。
- 左右交替 10 次基准开合全部显示两栏，收起后通知进度回到 0，SystemUI PID 未变化。
- 左右空白轻点／上滑、返回键、测试通知点击与自动删除、单条划除、列表滚动、设置按钮通过。
- 原速及四分之一速录屏已检查。动画阶段帧间隔中位数 16.68 ms，原片与脱敏版各 418 帧，原速 PTS 一致。
- 早期 0.3 在每阶段等半秒的 12 次开合中有 5 次未展开；0.4 没有宣称修复全部快速连续手势。
- 竖屏和锁屏回退曾在早期实现验证；0.4 动画修订没有重新覆盖全部此类状态。

### 0.4.1 发布检查

- 控制中心 Java 源码未改；新版 debug `classes.dex` 与此前 0.4 debug 产物一致。
- debug / release 构建及 lint 均通过；release 为 0 错误、3 项既有警告，debug 为 0 错误、4 项。
- 正式 APK 通过签名及 zipalign 验证，release manifest 无测试 Activity、通知发布权限或 debuggable 标记。
- 正式签名轮换 APK 在目标设备覆盖安装成功，包 appId 保持不变。
- 应用信息页显示“平板双栏控制中心”和 `0.4.1(5)`；SystemUI 重载后检查了原生双栏展开。

lint 警告涉及工具版本提示、动态资源查找、备份属性声明，以及只在 debug 出现的测试文字拼接。本次没有抑制警告或为发布顺便修改不相关功能。

尚未完整验证其他固件、其他设备、全部通知模板／回复操作、所有反向和极短间隔手势、整机重启后的长期稳定性及与所有 Root 模块的组合。去掉名称标记不改变这些限制。

## 6. 排查与恢复

| 现象 | 首先检查 |
| --- | --- |
| 模块无效 | LSPosed 是否启用、作用域是否仅 SystemUI、是否重载进程、型号/API/版本保护是否匹配 |
| `Unsupported SystemUI version` | 固件的实际 versionCode；不要直接删保护 |
| 找不到类／字段／方法 | 对照设备私有 API 与插件类加载器，查看 Xposed 日志 |
| 左右只出现一栏 | 两处结束事件互斥保护、两栏原生状态、是否处于不适用状态 |
| 空白不关闭或按钮不响应 | 通知实际行命中、整屏根视图抢事件、QS 临时拦截豁免 |
| 通知关闭时横向偏移 | 是否用动画屏幕坐标算布局，是否普通关闭时恢复了原 X |
| 动画拖尾／奇怪回弹 | 是否重加进度复制、跳过原生弹簧，或录屏导出丢失原 PTS |
| 极速重拉停在桌面 | 记录事件与原生关闭阶段；这是已知边界，不用伪造展开状态掩盖 |

```sh
adb -s "$SERIAL" shell logcat -d -v brief -s 'OPD2413DualShade:*'
```

如果日志被其他模块过滤，可查看 LSPosed 模块日志。提交问题时提供版本、触发步骤、必要且脱敏的日志；不要提交完整通知 dump、解锁密码、USB 序列号或私人录屏。

优先在 LSPosed 中关闭 OneShade，重载 SystemUI。界面无法使用而 ADB / Root 仍可用时：

```sh
./scripts/recover-device.sh "$SERIAL"
```

脚本先确认设备型号，再卸载**本项目包**，仅在卸载成功后重启 SystemUI。没有指定序列号或型号不是 OPD2413 时会退出，不执行卸载；不会停用框架、删除其他模块或清空用户通知。

模块卸载会删除本模块数据。脚本需要已有 ADB 授权和可用 Root，无法保证在所有系统故障或尚未解锁的启动阶段可执行；不要把它当作刷机／开机故障恢复工具。
