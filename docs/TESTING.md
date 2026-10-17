# 测试与故障排查

本文包含实机测试步骤、已有验证范围和恢复方法。构建与 Hook 实现见[开发文档](DEVELOPMENT.md)；CI 不替代设备上的交互与动画检查。

## 测试准备

1. 使用已授权 USB ADB、可用 Root 的 OPD2413，保存当前操作。
2. 记录固件、SystemUI、LSPosed 版本及分离模式、屏幕方向和相关模块状态。
3. 重启 SystemUI 后正常解锁；不要操作私人通知、关闭其他模块或清空系统通知。
4. 记录临时修改的旋转／常亮设置，测试结束后恢复。

```sh
adb devices -l
SERIAL='<目标 OPD2413 的 ADB 序列号>'
adb -s "$SERIAL" shell getprop ro.product.model
adb -s "$SERIAL" shell getprop ro.build.version.sdk
adb -s "$SERIAL" shell dumpsys package com.android.systemui
adb -s "$SERIAL" shell pidof com.android.systemui
```

所有 ADB 命令指定目标序列号。安装后检查 LSPosed 识别 API 102，静态作用域仅含已启用的 `com.android.systemui`；旧包 `io.github.opd2413.ctrlcenter` 不应同时启用。

## 测试通知

`VerificationActivity` 是仅存在于 debug 变体的测试工具，由 `android.permission.DUMP` 保护，没有桌面入口。正式包不包含此 Activity 或通知发布权限。

在已安装 debug APK 的开发设备发布 12 条测试通知：

```sh
adb -s "$SERIAL" shell 'su -c "pm grant io.github.ffdvdgd.oneshade android.permission.POST_NOTIFICATIONS"'
adb -s "$SERIAL" shell 'su -c "am start -S -n io.github.ffdvdgd.oneshade/.VerificationActivity --ez post true --ei count 12"'
```

点击通知后应显示对应编号、自动移除该通知，并记录 `Clicked notification N`。ColorOS 可能自动合并通知或为侧滑显示操作菜单，应根据当前画面定位目标。

```sh
adb -s "$SERIAL" shell logcat -d -v brief -s 'DualShadeVerification:*'
```

结束时只取消本模块的通知：

```sh
adb -s "$SERIAL" shell 'su -c "am start -S -n io.github.ffdvdgd.oneshade/.VerificationActivity --ez cleanup true"'
```

清理只影响本模块的测试通知。debug 与正式包签名可能不同，优先使用专门的开发设备，不为运行测试工具强制卸载正在使用的正式模块。

## 测试矩阵

| 输入／状态 | 预期结果 |
| --- | --- |
| 解锁横屏，分别从顶部左／右侧下拉 | 两栏同时出现，无明显单侧延迟 |
| 分别点击左／右空白 | 两栏关闭，无单边残留 |
| 分别上滑左／右空白 | 拖动阶段开始联动收起，不等松手 |
| 返回键关闭 | 两栏关闭，原页面可操作 |
| 点击测试通知 | 打开对应编号，通知自动移除 |
| 单条通知左／右滑 | 按系统行为显示菜单或移除目标，不影响其他通知 |
| 展开通知组、上下滚动列表 | 通知正常展开与滚动，控制中心保持可见 |
| 控制中心设置按钮、滑块 | 执行原生操作，不被通知区域拦截 |
| 竖屏／锁屏／关闭分离模式 | 恢复原生界面和隐私行为 |
| 收起末帧、再次展开 | 通知不出现额外横向跳动 |
| 快速重拉、动画途中反向 | 记录成功率、残留画面和异常日志 |

不要为了验证开关而改变网络、飞行模式等非必要设置；优先使用设置按钮或可恢复的控件。若需要改变设置，先记录并恢复原值。

基准开合：左右交替 10 次，展开后等一秒、收起后等一秒，逐次检查画面并记录 SystemUI PID。另测半秒及更短间隔，不能用慢手势结果代替。

```sh
adb -s "$SERIAL" shell dumpsys activity service com.android.systemui/.SystemUIService
adb -s "$SERIAL" shell pidof com.android.systemui
```

`mExpandedFraction` 只反映通知进度，需要结合两栏画面和原生状态判断；PID 不变也不能代替交互检查。

## 动画录屏

低帧率 GIF 无法可靠显示回弹。优先保存原始 MP4，检查动态阶段帧时间，而不是只读取标称帧率。Android screenrecord 在静止时可能少产帧，整个视频平均 FPS 不代表动画 FPS。

```sh
adb -s "$SERIAL" shell screenrecord --size 1696x1200 --bit-rate 24000000 \
  --time-limit 12 /data/local/tmp/oneshade-review.mp4
adb -s "$SERIAL" pull /data/local/tmp/oneshade-review.mp4 ./oneshade-review.mp4
ffprobe -v error -select_streams v:0 \
  -show_entries frame=best_effort_timestamp_time -of csv=p=0 oneshade-review.mp4
```

录屏期间操作面板，尺寸按设备调整，必要时降低分辨率。shell 录屏受限时可使用 Root screenrecord，不修改其他模块配置。

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

公开前遮挡通知内容、联系人、Wi-Fi 名称和设备标识，检查运动过程中是否露出信息。原始录屏不提交到 Git 或 Release。

## 验证记录

### 实测环境

OPD2413，Android 16 / ColorOS 16.1，固件 `OPD2413_16.0.9.400(CN01)`，SystemUI `16.99.12`（169912），KernelSU。

### 0.5.0

框架：LSPosed IT `2.2.1-it (7920)`，API 102。

- debug／release 构建和 lint 通过：0 错误，分别有 5／4 项警告，涉及私有 API 反射、动态资源查找、备份属性和 debug 测试文字。
- 新包识别及 API 102 静态作用域检查通过，旧包已卸载；SystemUI 日志确认 Hook 加载。
- debug 包左右交替 10 次均显示双栏，收起后进度归零，SystemUI PID 不变。
- 测试通知组展开、点击命中及自动删除通过；左滑显示原生菜单，右滑删除目标，其他通知与控制中心保持显示。
- 正式包通过 v3 签名及 16 KiB zipalign 检查，无测试组件或通知发布权限；设备安装包与发布文件 SHA-256 一致。
- 正式包重载后复查左右入口、两侧空白轻点／上滑收起及竖屏回退。测试通知已清理，临时设备设置已恢复。

未完整复测锁屏、分离模式切换、全部通知模板／列表滚动及极短间隔反向手势，也未重录高帧率视频。

### 历史基准

0.4／0.4.1 使用 LSPosed IT `2.1.1-it (7789)`，以下记录不代表新版已重新覆盖这些场景。

- **0.4：** 10 次交替开合、空白手势、返回、通知点击／自动删除／划除／滚动及设置按钮通过。检查过原速和四分之一速视频；动态帧间隔中位数 16.68 ms，原片与脱敏版均为 418 帧，原速 PTS 一致。
- **0.4.1：** 未修改控制中心逻辑；构建、lint、正式签名轮换覆盖安装及重载后的双栏展开通过。

### 已知边界

极快速收起后立即重拉可能被原生关闭阶段忽略；两栏各自播放原生动画，不保证曲线和结束时刻一致。其他固件、全部通知模板／回复操作、长期稳定性及模块组合尚未完整覆盖。

## 故障排查与恢复

| 现象 | 首先检查 |
| --- | --- |
| 模块无效 | LSPosed 是否支持现代 API 102、是否识别并启用新包、SystemUI 静态作用域是否启用、是否重载进程、型号是否匹配 |
| 新固件无双栏、找不到类／字段／方法 | 记录实际版本和模块日志，按[固件适配步骤](DEVELOPMENT.md#9-故障处理与适配新固件)检查私有 API 变化 |
| 只出现一栏、空白手势或控件异常 | 检查是否解锁横屏并开启分离模式，分别复现左右入口与触摸位置 |
| 通知横向跳动、动画拖尾或回弹异常 | 记录原速视频及帧时间，区分设备画面与录屏导出问题 |
| 极速重拉停在桌面 | 记录操作间隔与日志，对照已知边界 |

```sh
adb -s "$SERIAL" shell logcat -d -v brief -s 'OPD2413DualShade:*'
```

日志被过滤时可查看 LSPosed 模块日志。反馈时提供版本、步骤和脱敏日志，不提交私人通知、解锁凭据或设备标识。

优先在 LSPosed 中关闭 OneShade，重载 SystemUI。界面无法使用而 ADB / Root 仍可用时：

```sh
./scripts/recover-device.sh "$SERIAL"
```

脚本先确认设备型号，再卸载**本项目包**，仅在卸载成功后重启 SystemUI。没有指定序列号或型号不是 OPD2413 时会退出，不执行卸载；不会停用框架、删除其他模块或清空用户通知。

模块卸载会删除本模块数据。脚本需要已有 ADB 授权和可用 Root，无法保证在所有系统故障或尚未解锁的启动阶段可执行；不要把它当作刷机／开机故障恢复工具。
