# OneShade · 平板双栏控制中心

LSPosed 模块：在解锁、横屏、通知与控制中心分离模式下，左侧显示真实通知，右侧保留原生 ColorOS 控制中心。保留通知点击、划除、展开、滚动与控制中心按钮交互，使用两栏各自的原生动画。

面向 OPD2413、Android 16 及以上的 ColorOS 分离式通知／控制中心，需要支持现代 Xposed API 102 的 LSPosed。以下是实测记录，不是固件白名单；其他设备不保证适配。

包名为 `io.github.ffdvdgd.oneshade`。**0.5.0 改包名，旧版不能覆盖升级；请先停用并卸载 `io.github.opd2413.ctrlcenter`，再安装、启用新包。**

| 项目 | 实测值 |
| --- | --- |
| 设备 | OPD2413 |
| 系统 | Android 16 / API 36，ColorOS 16.1 |
| 固件 | `OPD2413_16.0.9.400(CN01)` |
| SystemUI | `16.99.12` / versionCode `169912` |
| 框架 | LSPosed IT `2.2.1-it (7920)`，API 102（0.5.0 实测） |
| Root | KernelSU |

![原生双栏效果，个人信息已遮挡](https://raw.githubusercontent.com/FFDVDGD/OneShade/main/docs/images/dual-shade.png)

截图来自 0.4 的原生动画验证；新版保留相同布局和原生动画逻辑。

## 安装与使用

1. 下载并安装正式签名 APK；源码仓库的 [Releases](https://github.com/FFDVDGD/OneShade/releases/latest) 同时提供 `SHA256SUMS`。
2. 在 LSPosed 启用“平板双栏控制中心”；`staticScope=true` 仅允许系统界面 `com.android.systemui`，确认它已启用。
3. 保存当前操作后重载 SystemUI；若回到锁屏，正常解锁。
4. 横屏并开启系统的通知／控制中心分离模式，从顶部任一侧下拉。

模块没有桌面入口，不需要通知读取权限、悬浮窗或常驻服务。竖屏、锁屏及关闭分离模式时不启用双栏。代码保留型号条件，不锁定 Android API 或 SystemUI 的精确版本；新固件会尝试使用现有厂商私有 API，缺失时记录错误并撤销本模块 Hook。遇到兼容问题请反馈版本信息与复现步骤。

## 风险与恢复

修改 SystemUI 私有行为可能导致界面异常，**安装前保持已授权 USB ADB 和可用 Root 恢复路径**。

- 极快速收起后立即重拉，可能被原生关闭阶段忽略，留在桌面。
- 两栏同步启动意图，不保证动画曲线及结束时刻相同。
- 未覆盖其他固件、全部通知类型、所有连续手势、其他模块组合及整机重启后的长期稳定性。
- 优先在 LSPosed 关闭本模块，再重载 SystemUI。无法操作界面时，参照源码仓库的 [恢复步骤](https://github.com/FFDVDGD/OneShade#恢复原状)；恢复依赖已有 ADB 授权和可用 Root，不保证在所有故障中可达。

更新请使用相同正式签名；自行编译的 APK 可能无法覆盖安装，不要绕过签名验证。

## 源码、反馈与许可

- [源码与完整说明](https://github.com/FFDVDGD/OneShade)
- [问题反馈](https://github.com/FFDVDGD/OneShade/issues)：请提供设备、固件、SystemUI 与框架版本、模块版本及复现步骤；上传日志前去除个人信息。
- [更新记录](https://github.com/FFDVDGD/OneShade/blob/main/CHANGELOG.md)
- [实机验证范围](https://github.com/FFDVDGD/OneShade/blob/main/docs/TESTING.md)
- [MIT License](https://github.com/FFDVDGD/OneShade/blob/main/LICENSE)。第三方组件保留原有许可，不分发厂商 APK、反编译源码或个人通知资料。
