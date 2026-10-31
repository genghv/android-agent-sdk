# app/libs —— 凭据加密产物放置目录

## 目录用途

宿主要启用设备端凭据加密时，把加密实现产物 `.aar` 放到本目录：

```kotlin
// app/build.gradle.kts
implementation(fileTree("libs") { include("*.aar") })
```

使用 `fileTree` 而非 `files("libs/x.aar")`：**文件不存在时依赖为空、构建不失败**，
运行期读写凭据会**安全失败**（抛 `CredentialException`），而不是在构建期中断。
仓库中不包含该产物（二进制不进版本库）。

## 产物来源与校验

| 项 | 值 |
|---|---|
| 文件名 | `android-agent-sdk-cipher-v1.0.0-20261029.aar` |
| 来源 | GitHub Release 资产（独立分发，不进入 SDK 主包） |
| 大小 | 12,514 字节 |
| sha256 | `f098744c71d660a4684d7138c437330ef34f19f19ab98c8f7cabd7c1d3644731` |

```powershell
Get-FileHash -LiteralPath .\app\libs\android-agent-sdk-cipher-v1.0.0-20261029.aar -Algorithm SHA256
# 期望：f098744c71d660a4684d7138c437330ef34f19f19ab98c8f7cabd7c1d3644731
```

下载、校验指纹并跑通 12 条公开契约测试的一键脚本：`scripts/verify-cipher-contract.ps1`。

## 注册与可用性

```kotlin
CredentialCipherLoader.register("com.agentsdk.cipher.KeystoreCredentialCipher")
```

**注册成功不等于可用**：未放入 `.aar` 时注册仍会成功，但解析实现会失败，
读写凭据随即安全失败。「连不上」与「解不开」是两种不同的失败，排查时需区分。

## 真机验证

```bash
# 第一轮：写入（文件保留）
adb shell am start -S --activity-clear-task -n com.agentkernel.demo/.MainActivity --ez autorun_cred true
# 重启 App 后第二轮：只读，验证密钥持久性
adb shell am start -S --activity-clear-task -n com.agentkernel.demo/.MainActivity --ez autorun_cred true

adb logcat -d -s AgentSdkDemo | Select-String 'CRED\|'
```

自检覆盖四项：`cipher-impl`（实现是否注册且可解析）、`cred-write`（写入一条测试用假 key）、
`cred-no-plaintext`（扫描 `filesDir` 全树，假 key 不应出现在任何文件中）、
`cred-roundtrip`（读回与写入一致，且不打印明文）。
