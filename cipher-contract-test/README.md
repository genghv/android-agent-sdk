# 凭据加密核心 · 契约测试

本目录**不含加密实现**，只有一份针对加密产物的**契约测试**：
它把密文包的格式与 AEAD 语义写成可执行断言，用已发布的 `.aar` 即可运行。

- 契约本身见 [`../凭据加密模块规格.md`](../凭据加密模块规格.md)；
- 被测对象是 `android-agent-sdk-cipher-<版本>-<日期>.aar`（独立资产，不进入主包）。

---

## 一、运行方式

### 方式一：一键脚本（推荐）

```powershell
# 在仓库根目录执行
powershell -ExecutionPolicy Bypass -File scripts/verify-cipher-contract.ps1
```

脚本做三件事：

1. 通过 `api.github.com` 读取该资产的 `digest` 字段（`sha256:…`）；
2. 下载 `.aar` 字节，本地独立计算 sha256，与 `digest` 比对；
3. 解出 `classes.jar` 放入 `cipher-contract-test/libs/`，执行
   `gradlew -PwithCipherContract=true :cipher-contract-test:test`，
   并只统计**本次运行**产生的结果文件。

下载按两条通道顺序尝试（部分网络中 GitHub CDN 不可达）：

| 通道 | 地址 | 说明 |
|---|---|---|
| ① CDN | `github.com/…/releases/download/…` | 官方通道 |
| ② API | `api.github.com/…/releases/assets/<id>` | 回退通道（`Accept: application/octet-stream`） |

脚本会打印实际使用的通道。

> `-PwithCipherContract=true` 是必需参数：没有该开关时 `settings.gradle.kts` 不会 include 本模块，
> 默认构建不受影响、也不需要加密产物。

### 方式二：手动

```powershell
# 1) 从 .aar（本质是 zip）中取出 classes.jar 放到 libs/
# 2) 运行
.\gradlew.bat -PwithCipherContract=true :cipher-contract-test:test
```

---

## 二、覆盖内容（12 条）

| 组 | 断言 |
|---|---|
| 正向 | 密封后能原样解开 |
| 包格式 | magic `ASDC`、版本号、nonce 长度字段的位置与取值；nonce 段与密文体的字节偏移；密文体长度 = 明文长度 + GCM 标签 |
| AAD 绑定 | 换目标 host 解不开；换凭据名解不开 |
| 完整性 | 密文改一个字节、nonce 段被改、截断、截到刚好少一个标签，均解不开 |
| 其它 | 同一明文两次密封的 nonce 必须不同且差异落在 nonce 段；密文包内搜不到明文；非本模块的包要报错；未知版本要报错 |

**用例有效性**：每条「解不开」类用例都先执行一次正向往返作为基线。
否则包格式整体损坏时，这批负向用例会全部通过——「因为该抛而抛」与「因为整体坏掉而抛」无法区分。

### 条数口径

| 口径 | 值 |
|---|---|
| 契约条数（去重后的用例名） | 12 |
| 执行次数 | 24（debug / release 两个变体各执行一遍） |
| failures / errors / skipped | 0 / 0 / 0 |

脚本分别打印条数与执行次数，并断言条数为 12、跳过为 0。

---

## 三、构造被测实例的方式

加密产物对外只暴露无参构造（走真实 Android Keystore），在 JVM 上无法直接实例化。
测试通过反射注入一把内存密钥（`InMemoryKeyOps`）：

- 该口子在字节码层面是 `public`（Kotlin `internal` 的编译结果），
  但 Kotlin 编译器会拒绝跨模块访问，直接书写会编译失败；
- 反射只用于**构造实例**，被验证的 `seal` / `open` 走的仍是公开接口 `CredentialCipher`；
- 反射查找构造器时参数类型需精确匹配：传 `KeyOps` 接口，而非实现类。

---

## 四、有效性验证

「当前是绿的」不等于「用例有效」。验证方式：把历史上修掉的一个真实缺陷重新注入 —— 
密文包头长度字段被误用为 nonce 起始偏移（偏移 12 字节，GCM 使用错误 IV） —— 然后重跑：

| 阶段 | 结果 |
|---|---|
| 注入缺陷 | 构建失败；24 次执行中 **9 条失败**，其中「正向：密封后能原样解开」首先失败 |
| 恢复实现 | 构建成功；24 次执行，0 失败 |

---

## 五、不覆盖的内容

- **不验证真实 Keystore**：测试使用内存中的固定 AES 密钥，验证的是格式与 AEAD 语义。
  「密钥在 Keystore 内生成、不可导出」属于**设备端验收**，需要在真机上完成。
- **不验证密钥管理策略**：别名规则、每设备一把、账号维度隔离等属于实现内部约束，
  由《凭据加密模块规格.md》第四节写成条款，在宿主集成后按第七节清单验收。

---

## 六、版本对应

| SDK 版本 | 加密产物 | 说明 |
|---|---|---|
| v1.1.6 | — | 接口与加载器进入开源仓 |
| v1.1.15 起 | `android-agent-sdk-cipher-v1.0.0-20261029.aar` | 加密实现首次以独立资产发布 |
| v1.1.18 起 | 同上 | 契约测试源码公开（本目录） |
