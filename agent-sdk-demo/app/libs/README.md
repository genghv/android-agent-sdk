# demo 的闭源加密核心放这儿（**仓库里不放那个文件**）

## 这个目录干什么

宿主（demo / 你的 App）把**闭源加密核心**的 `.aar` 放进这个目录，
demo 的构建脚本就会把它打进 APK：

```kotlin
implementation(fileTree("libs") { include("*.aar") })
```

⚠️ 用 `fileTree` 而不是 `files("libs/x.aar")` ⇒ **文件不在时构建不报错** ——
没有加密实现时 SDK 侧**安全失败**（抛异常，**绝不退回明文**），而不是构建期崩掉。

## 文件从哪来

| 项 | 值 |
|---|---|
| 文件名 | `android-agent-sdk-cipher-v1.0.0-20261029.aar` |
| 来源 | SDK 的 GitHub release **资产**（不来自 git —— 闭源字节不进仓库） |
| 期望 sha256 | `f098744c71d660a4684d7138c437330ef34f19f19ab98c8f7cabd7c1d3644731` |
| 大小 | 12,514 字节 |

取法（PowerShell）：

```powershell
# 1) 从 release 资产下载（文件名就是上面那个）
# 2) 核对指纹 —— 这一步不能省：防止「拿了本地旧构建当发布物」
Get-FileHash -LiteralPath .\app\libs\android-agent-sdk-cipher-v1.0.0-20261029.aar -Algorithm SHA256
# 期望：f098744c71d660a4684d7138c437330ef34f19f19ab98c8f7cabd7c1d3644731
```

一键复现脚本（下载 + 自算指纹 + 跑 12 条公开契约测试）：
`scripts/verify-cipher-contract.ps1`。

## 装上之后 demo 会做什么

demo 在启动时按**类名**注册实现（逐字，不能改）：

```kotlin
CredentialCipherLoader.register("com.agentsdk.cipher.KeystoreCredentialCipher")
```

⇒ **注册成功 ≠ 可用**：没放那个 `.aar` 时，注册照样成功，但**解析**会失败
⇒ 读写凭据**安全失败**（抛异常）。
⚠️ 那是「**连不上**」，与「**解不开**」是两种失败 —— 排查时别混。

## 真机验一遍

```bash
# 第一次：写入（并留着文件）
adb shell am start -S --activity-clear-task -n com.agentkernel.demo/.MainActivity --ez autorun_cred true
# 重启 App 后第二次：只读 ⇒ 验「Keystore 密钥持久性」
adb shell am start -S --activity-clear-task -n com.agentkernel.demo/.MainActivity --ez autorun_cred true

adb logcat -d -s AgentSdkDemo | Select-String 'CRED\|'
```

自检覆盖四项：`cipher-impl`（注册了没有＋能不能解析）、
`cred-write`（写入一条**假** key）、`cred-no-plaintext`（扫 `filesDir` 全树，
那串假 key 不该出现在任何文件里）、`cred-roundtrip`（读回与写入一致，**不打印明文**）。
