# LSPosed 官方模块仓库收录准备

本文件是维护者的准备清单与申请草稿，**不表示已经提交、审核通过或收录**。核对日期：2026-10-10。

官方依据：

- [申请入口](https://modules.lsposed.org/submission)
- [submission 仓库的规则](https://github.com/Xposed-Modules-Repo/submission#readme)

## 1. 当前状态与包名迁移

| 项目 | 当前值 |
| --- | --- |
| 源码仓库／维护者 | `FFDVDGD/OneShade` / `FFDVDGD` |
| 应用名称 | 平板双栏控制中心（OneShade） |
| APK applicationId | `io.github.ffdvdgd.oneshade` |
| 当前版本 | versionName `0.5.0` / versionCode `6` |
| 作用域 | 现代 API 102，`staticScope=true`，仅 `com.android.systemui` |
| 许可证 | MIT；第三方组件保持原有许可 |
| 源码仓库发布格式 | `v0.5.0`，附件 `OneShade-v0.5.0.apk` 与 `SHA256SUMS` |
| 官方模块仓库收录 | 尚未提交 |

包名已按维护者 GitHub 用户名迁移为 `io.github.ffdvdgd.oneshade`，不再申请旧包名例外。官方要求反向域名属于申请人；无自有域名时可使用 `io.github.<GitHub 用户名或组织名>`，使用组织名需先公开组织成员身份。

**迁移提示：0.5.0 改了 applicationId，即使使用同一签名也不能覆盖旧包；先停用并卸载 `io.github.opd2413.ctrlcenter`，再安装和启用新模块。** 这句话同时放入 Release 更新说明，避免同时启用两份模块。

新包需要重新构建、签名及实机验证，不能把旧 APK 直接上传成新包的发布附件。静态作用域来自现代 API 元数据，不是旧 API 的推荐作用域列表。

## 2. 申请草稿

在 [官方申请入口](https://modules.lsposed.org/submission) 选择提交新模块，使用最终 APK 的 applicationId。待新版正式 APK 发布且下面的检查清单通过后使用此草稿。

标题：

```text
[submission] io.github.ffdvdgd.oneshade
```

正文：

```text
Module name: OneShade / 平板双栏控制中心
Application ID: io.github.ffdvdgd.oneshade
Source: https://github.com/FFDVDGD/OneShade
Releases: https://github.com/FFDVDGD/OneShade/releases
Support: https://github.com/FFDVDGD/OneShade/issues
License: MIT

OneShade is an Xposed / LSPosed module that displays the native notification
panel and ColorOS quick settings side by side in unlocked landscape mode,
with the system's separate notification / quick settings mode enabled.
It preserves native notification interactions and each panel's own animations.

Tested only on OPD2413, Android 16 (API 36), ColorOS 16.1,
firmware OPD2413_16.0.9.400(CN01), SystemUI 16.99.12 (169912),
and KernelSU. Version 0.5.0 was tested with LSPosed IT 2.2.1-it (7920), API 102.
Historical 0.4/0.4.1 testing used LSPosed IT 2.1.1-it (7789).
The new module requires modern Xposed API 102 or later.
Static scope: com.android.systemui only. No launcher activity.
This is not a universal ColorOS / OnePlus module.

The new package is 0.5.0 (versionCode 6), with a signed release APK
and SHA256SUMS. It retains the OPD2413 device condition, but does not gate
activation on an exact Android API or SystemUI version code. New firmware
is attempted using the required OEM APIs; compatibility issues are tracked
and fixed. Untested firmware is not claimed to be verified.
SystemUI modifications may still cause failures; recovery requires previously
authorized ADB and working root access.
```

由 `FFDVDGD` 账号提交，不要使用 `[transfer]` 转移源码仓库：官方模块仓库不需要源码。

## 3. 获准后填充模块仓库

官方机器人创建仓库后，接受维护者邀请，再填充独立的分发仓库。不要把整个 OneShade 源码仓库推过去。

| 官方仓库内容／设置 | 准备材料 |
| --- | --- |
| 仓库名 | 与获准 APK applicationId 完全相同 |
| Description | `平板双栏控制中心（OneShade）`；按官方说明在浏览器中设置 |
| Home Page | `https://github.com/FFDVDGD/OneShade/issues` |
| 根目录 `SUMMARY` | 复制源码仓库根目录的 `SUMMARY`，不要改为 `SUMMARY.md` |
| 根目录 `README.md` | 复制 [模块介绍](lsposed/README.md)，使用绝对链接，不依赖源码目录 |
| 根目录 `LICENSE` | 复制源码仓库根目录的 MIT 许可证 |
| Release Title | `0.5.0` |
| Release Tag | `6-0.5.0`，格式为 `<versionCode>-<versionName>` |
| Release Content | 使用该版本的更新记录，保留兼容性、风险和验证边界 |
| Release 附件 | 经验证的正式签名 APK 与对应 `SHA256SUMS` |

源码仓库使用 `v0.5.0` 标签；官方分发仓库使用 `6-0.5.0`。两者用途不同，无需重写已有源码标签。不能仅放源码仓库下载链接代替官方 Release 的 APK 附件。

包名、版本、标签、静态作用域与校验和必须以新产物为准。正式包不含 debug 通知 fixture 或通知发布权限，框架 API 只用于编译。

## 4. 提交与发布检查清单

- [ ] 包名归属已解决，申请账号、标题与最终 APK applicationId 一致。
- [ ] MIT 许可证及申请引用的介绍材料已推送到公开源码仓库，链接可访问。
- [ ] 正式 APK 的 applicationId、versionCode、versionName 与申请／标签一致。
- [ ] 使用 `apksigner verify --verbose --print-certs` 验证签名；证书符合 [签名发布说明](DEVELOPMENT.md#10-签名版本与-github-发布)。
- [ ] SHA-256 与正式发布文件一致，release 不含 debug 测试组件，含 Xposed 入口与 SystemUI 作用域。
- [ ] 无源码行为改动时复用已有版本的验证记录，不把文档准备声称为新的实机验证；改包名或重新构建后重新执行适用的构建、lint、安装与实机检查。
- [ ] 源码、图片和日志不含密钥、密码、厂商 APK、反编译源码或个人通知资料。
- [ ] 提交申请并接受邀请后，完成 `SUMMARY`、`README.md`、仓库设置和正式 Release。
- [ ] 发布时同时附带 APK 并采用正确标签；不要只替换已有 Release 的 APK 附件，官方机器人可能不会检测到纯附件变更。
- [ ] 等待官方索引更新（官方说明通常为 5–60 分钟），再检查网页介绍、版本和下载；未显示时检查仓库是否完整，而不是直接宣称已收录。

创建外部 issue、推送源码或分发仓库、发布 Release 均需维护者明确授权。此清单不执行这些操作。
