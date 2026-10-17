# LSPosed 官方模块仓库发布与维护

官方分发仓库已建立并发布 0.5.0。**仓库发布不等于目录已展示**：About 字段尚待维护者在网页登录后设置，目录展示仍待确认。核对日期：2026-10-17。

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
| 官方分发仓库 | [Xposed-Modules-Repo/io.github.ffdvdgd.oneshade](https://github.com/Xposed-Modules-Repo/io.github.ffdvdgd.oneshade) |
| 官方 Release | [0.5.0 / `6-0.5.0`](https://github.com/Xposed-Modules-Repo/io.github.ffdvdgd.oneshade/releases/tag/6-0.5.0) |
| About／目录展示 | About 待网页设置；[目录页面](https://modules.lsposed.org/module/io.github.ffdvdgd.oneshade)尚待确认 |

包名已按维护者 GitHub 用户名迁移为 `io.github.ffdvdgd.oneshade`，不再申请旧包名例外。官方要求反向域名属于申请人；无自有域名时可使用 `io.github.<GitHub 用户名或组织名>`，使用组织名需先公开组织成员身份。

**迁移提示：0.5.0 改了 applicationId，即使使用同一签名也不能覆盖旧包；先停用并卸载 `io.github.opd2413.ctrlcenter`，再安装和启用新模块。** 这句话同时放入 Release 更新说明，避免同时启用两份模块。

0.5.0 新包已完成构建、签名及实机验证。本次官方分发复用源码仓库已发布的原始 APK，没有重新构建或进行新的实机验证。不能把旧包 APK 改名冒充新包。静态作用域来自现代 API 元数据，不是旧 API 的推荐作用域列表。

## 2. 发布产物与权限

不再需要为该包重复提交申请，也不要使用 `[transfer]` 转移源码仓库。官方仓库只用于介绍和正式包分发，源码保留在 `FFDVDGD/OneShade`。

0.5.0 在两个仓库中的 APK SHA-256 一致：

```text
b3fbef5b9ddd4821a80734448cbbf425e3e1c3e05fdad37974983f0e914f3767
```

已从官方 Release 回下载 APK 与 `SHA256SUMS` 并验证，APK 的 v3 签名及正式证书符合[开发文档](DEVELOPMENT.md#10-签名版本与-github-发布)。设备测试范围沿用 [0.5.0 验证记录](TESTING.md)，不把仓库发布当作新增设备验证。

目前未配置跨仓库自动同步工作流或 `MODULE_REPO_TOKEN`。后续发布仍须同步分发文档并上传正式包；内置 `GITHUB_TOKEN` 不能写另一个仓库。若另行启用自动同步，应使用专用、最小权限且有期限的凭据，不把日常 CLI 登录凭据写入工作流或仓库。

## 3. 分发仓库结构与 About 设置

官方仓库根目录仅含以下三个文件，不包含 Android 源码、构建脚本或签名材料。About 的 Description 和 Home Page 尚待设置：在仓库首页 About 右侧齿轮中填写下表值，不通过 API 绕过官方限制。

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

## 4. 本次发布状态与后续检查

- [x] 包名归属已解决，官方仓库名与最终 APK applicationId 一致。
- [x] MIT 许可证、模块介绍及 `SUMMARY` 已推送到官方分发仓库。
- [x] 正式 APK 的 applicationId、versionCode、versionName 与 `6-0.5.0` 标签一致。
- [x] 官方附件回下载后 SHA-256 与原包一致，正式 v3 签名验证通过。
- [x] 复用已有版本验证记录，没有重新构建、签名或声称新增实机验证。
- [x] 发布时先上传 APK 与校验文件至草稿，再公开 Release。
- [ ] 在 GitHub 网页填写 About 的 Description 和 Home Page。
- [ ] 等待官方索引更新（官方说明通常为 5–60 分钟），再检查网页介绍、版本和下载；未显示时检查仓库是否完整，而不是直接宣称已收录。

后续版本应先完成适用的构建、签名、实机检查和隐私审查，再同步文档与原始正式包。不要覆盖同一已发布标签中内容不同的 APK；应递增版本并创建新 Release。仅替换 APK 附件可能不会触发官方索引，必须带 APK 一起发布正确标签的 Release。
