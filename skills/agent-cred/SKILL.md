---
name: agent-cred
description: 设备端 API 凭据的**只读目录**——有哪些凭据、各自绑在哪个 host、现在能不能解密。当需要确认「密钥是不是配好了」「绑在哪个服务上」「为什么用不了」时使用。⚠️ 这条命令**永远不返回明文**，也**没有任何取值子命令**。
---

# 凭据（agent-cred）

## 一、这条命令只回答「有没有」，不回答「是什么」

```bash
agent-cred list           # 有哪些凭据、各自绑在哪个 host（**没有明文**）
agent-cred status <name>  # 这一条的状态（**没有明文**）
```

输出是 JSON：
- `list` ⇒ `ok` / `credentials[]`（每项 `name` + `host`）/ `note`；
- `status` ⇒ `ok` / `name` / `host` / `state` / `decryptable` / `note`。

失败时 `ok:false` 且带 `error`，退出码非 0（能力失败一律是「如实报错」）。

## 二、⚠️ 为什么没有 `get`

命令的参数会进**模型上下文**（命令回显、转录、工具历史）。
若这条命令能取出明文，agent 一句 `agent-cred get deepseek` 就把用户的密钥读进上下文
⇒ **泄露给上游模型服务商、会话历史、用量日志** —— 等于把要防的东西又送回去。

⇒ 所以**写入与读取明文都不走命令面**，只走宿主 App 的 API（见第三节）。

## 三、宿主怎么写入（明文只走这一条）

```kotlin
val cred = AndroidAgentSdk.credentialCapabilities(context)

cred.setSecret("deepseek", "api.deepseek.com", userTypedKey)  // 写入
cred.secretFor("deepseek")        // 取明文（**仅宿主用**：不要写进日志/提示词/命令参数）
cred.deleteSecret("deepseek")     // 删
cred.listSecrets()                // 名单
```

- 每个凭据登记了它**只允许发给哪个 host**；比不出来就不放行（fail-closed）。
- 凭据落在**宿主 App 的私有目录**里，**不在** agent 的沙箱根下面 ⇒ agent 连密文文件都读不到。

## 四、`decryptable: false` 是什么意思

那表示**凭据加密实现没有注入** ⇒ 这条凭据现在**解不开**。
宿主必须先注册实现（加密核心是**闭源产物**，由宿主单独集成）：

```kotlin
CredentialCipherLoader.register("com.example.KeystoreCredentialCipher")  // 用类名，零编译期依赖
```

⇒ **没有实现就安全失败**（抛异常），**绝不退回明文** ——
「以为加密了、其实没加密」比明确报错危险得多。

> ⚠️ 别把「解不开」与「读不出来」混为一谈：
> **解不开** = 实现没注入 / 密文坏了；**读不出来** = 文件或名字有问题。

## 五、几条规矩

1. **别把密钥当命令参数传** —— 参数会进上下文。
2. **要改密钥**走宿主 App 的设置入口（`setSecret` / `deleteSecret`），别让 agent 改。
3. **`agent-cred status` 说 `decryptable:false`** 时，那是「解不开」，不是「没有这个凭据」。
4. **命令面永远拿不到明文** —— 这不是缺陷，是设计。
