# 签名与发布维护

面向维护者。构建步骤见[开发文档](DEVELOPMENT.md)，设备检查见[测试文档](TESTING.md)。

## 版本与仓库

应用 ID 为 `io.github.ffdvdgd.oneshade`。发布新 APK 时，在 `app/build.gradle` 修改 `versionName` 并递增 `versionCode`；仅修改文档无需重新打包或升版。

| 仓库 | 内容 | Release 标签 | 标题 |
| --- | --- | --- | --- |
| [FFDVDGD/OneShade](https://github.com/FFDVDGD/OneShade) | 源码、文档、正式包 | `v<versionName>` | 版本名 |
| [官方分发仓库](https://github.com/Xposed-Modules-Repo/io.github.ffdvdgd.oneshade) | 模块介绍、正式包 | `<versionCode>-<versionName>` | 版本名 |

例如，0.5.0 的 versionCode 为 6，两处标签分别是 `v0.5.0` 和 `6-0.5.0`。两个仓库使用**同一个签名 APK 和校验文件**，不分别构建。

## 正式签名

后续更新复用现有正式密钥。0.5.0 起的证书 SHA-256：

```text
5b58ecefb5b9dfada438432e90ff6b3df957124e1f3818affd2ed7e659d4f96d
```

以下示例在已配置 Android build-tools 的 Bash 中运行。密钥和密码文件放在仓库外，不写入 Git、CI 或 Release，并单独备份。

```sh
VERSION='0.5.0'
CODE='6'
APK="OneShade-v$VERSION.apk"
KEYSTORE='/私有目录/release.p12'
PASSWORD_FILE='/私有目录/password.txt'

apksigner sign --ks "$KEYSTORE" --ks-key-alias oneshade \
  --ks-pass "file:$PASSWORD_FILE" \
  --v1-signing-enabled false --v2-signing-enabled false \
  --v3-signing-enabled true --v4-signing-enabled false \
  --debuggable-apk-permitted false --out "$APK" \
  app/build/outputs/apk/release/app-release-unsigned.apk
apksigner verify --verbose --print-certs "$APK"
zipalign -c -P 16 -v 4 "$APK"
sha256sum "$APK" >SHA256SUMS
```

自行构建使用自己的密钥，不能覆盖安装维护者签名的包。旧版 0.4.1 的证书轮换仅适用于旧包名，不用于跨包名升级。

## 发布检查

1. debug／release 构建、lint 和 CI 通过；完成适用的实机测试，记录未覆盖项。
2. 核对 APK 包名和版本；确认含现代 Xposed 入口、API 102 元数据及仅 SystemUI 的静态作用域，不含框架 API 实现类。
3. 确认 release 不含 `debuggable` 标记、测试 Activity 或 `POST_NOTIFICATIONS` 权限。
4. 验证正式签名、zipalign 和 SHA-256；发布包须与实际测试安装包一致。
5. 更新 [CHANGELOG](../CHANGELOG.md) 和必要文档，发布说明写用户可见变化、迁移提示与已知问题。
6. 检查附件与仓库无密钥、设备原始日志、个人通知或厂商 APK。

使用上面的版本变量，先创建含附件的草稿，再核对并公开：

```sh
gh release create "v$VERSION" "$APK" SHA256SUMS \
  --repo FFDVDGD/OneShade --target main --title "$VERSION" \
  --notes-file release-notes.md --draft
gh release create "$CODE-$VERSION" "$APK" SHA256SUMS \
  --repo Xposed-Modules-Repo/io.github.ffdvdgd.oneshade \
  --target main --title "$VERSION" --notes-file release-notes.md --draft

# 确认两处草稿的版本、说明和附件完整后执行：
gh release edit "v$VERSION" --repo FFDVDGD/OneShade --draft=false --latest
gh release edit "$CODE-$VERSION" \
  --repo Xposed-Modules-Repo/io.github.ffdvdgd.oneshade --draft=false --latest
```

发布后从两处回下载并比较 SHA-256。不要覆盖同一已发布标签下内容不同的 APK，应递增版本后重新发布。

## 官方分发仓库

当前手动同步。文档更新时按下表复制，不推送整个源码仓库：

| 分发仓库文件 | 来源 |
| --- | --- |
| `README.md` | [docs/lsposed/README.md](lsposed/README.md)，链接须为绝对 URL |
| `SUMMARY` | 源码仓库根目录 `SUMMARY` |
| `LICENSE` | 源码仓库根目录 `LICENSE` |

仓库 About 的 Description 为 `OneShade`，Website 为 `https://github.com/FFDVDGD/OneShade`。按[官方规则](https://github.com/Xposed-Modules-Repo/submission#readme)在 GitHub 网页维护这些字段。

索引通常在更新后 5–60 分钟刷新，可在[模块目录](https://modules.lsposed.org/module/io.github.ffdvdgd.oneshade)检查介绍、版本和下载。Release 必须使用正确标签并在公开前带上 APK；仅替换已发布的 APK 附件可能不会触发索引。
