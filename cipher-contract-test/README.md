# 闭源加密核心的**契约测试**（公开版）

**实现是闭源的，契约是公开的。**

这个目录里**没有加密实现**，只有一份**契约测试**：它把密文包的格式与 AEAD 语义
写成可执行的断言。任何人拿到**已发布的 `.aar`** 就能跑它 ⇒
「契约测试全绿」这句话**可以被第三方独立复现**，不必只相信我们的说法。

- 契约本身见《凭据加密模块规格.md》（开源仓根目录）；
- 被测对象是闭源产物 `android-agent-sdk-cipher-v1.0.0-20261029.aar`
  （**独立 release asset**，不进主包）。

## 怎么跑（两条路）

### 路 ①：一键脚本（推荐）

```powershell
# 在仓库根目录执行
powershell -ExecutionPolicy Bypass -File scripts/verify-cipher-contract.ps1
```

脚本做三件事：

1. 从 `api.github.com` 取该 release 资产的 **`digest` 字段**（`sha256:…`）；
2. 取 `.aar` 的字节，**自己算**一遍 sha256，与 `digest` **逐字比对**
   ⇒ 指纹与字节是两条不同的数据，互相印证；
3. 解出 `classes.jar` 放进 `cipher-contract-test/libs/`，再跑
   `gradlew -PwithCipherContract=true :cipher-contract-test:test`，
   并**只统计本次运行产生的**结果文件（旧的陈旧 XML 不算数）。

> `-PwithCipherContract=true` 是**必须的**：没有这个开关时 `settings.gradle.kts`
> **不会 include** 本模块 ⇒ 默认构建完全不受影响（也不需要闭源产物）。

下载走**两条通道**，按顺序试（有的网络里 `github.com/.../download` 不通，
`api.github.com` 通）：

| 通道 | 地址 | 说明 |
|---|---|---|
| ① CDN | `github.com/…/releases/download/…` | 官方通道；本机实测**不通**（连接被重置） |
| ② API | `api.github.com/…/releases/assets/<id>` | 回退通道（`Accept: application/octet-stream`） |

脚本会打印**实际走了哪条通道** —— 走 ② 时仍是「API 元数据 vs API 内容端点」两条不同的数据，
但同源；走 ① 时是「CDN 字节 vs API 元数据」，互证最强。

### 路 ②：手动

```powershell
# 1. 把 .aar 里的 classes.jar 解到 libs/
#    （.aar 就是 zip：把 classes.jar 那个条目取出来即可）
# 2. 跑测试
.\gradlew.bat -PwithCipherContract=true :cipher-contract-test:test
```

## 它验了什么（12 条）

| 组 | 断言 |
|---|---|
| 正向 | 密封后能原样解开 |
| 格式 | magic `ASDC` / 版本号 / nonce 长度字段的位置与值；nonce 段与密文体的**字节偏移**钉死；密文体长度 = 明文 + GCM 标签 |
| AAD 绑定 | 换目标 host 解不开；换凭据名解不开 |
| 完整性 | 密文改一个字节解不开；nonce 段被改解不开；截断解不开；截断到刚好少一个认证标签也解不开 |
| 其它 | 同一明文两次密封 nonce 必须不同（且差别**落在 nonce 段**）；密文包里搜不到明文；不是本模块的包要报错；版本不认识要报错 |

★ **每条「解不开」类用例都先自证基线是好的**（先跑一遍正向往返）——
否则包格式整个坏掉时，这一堆负向用例会**照样全绿**（「因为该抛而抛」和「因为全坏了而抛」分不出来）。

### 数字口径

| 口径 | 值 |
|---|---|
| 契约条数（去重后的用例名） | **12** |
| 执行次数 | **24**（`testDebugUnitTest` + `testReleaseUnitTest` 两个变体各跑一遍） |
| failures / errors / skipped | **0 / 0 / 0** |

脚本会**分别**打印「契约条数」与「执行次数」，并断言条数 == 12、跳过 == 0
—— 数字必须与**实际在跑的那一份**一致。

## ★ 为什么用反射造实例（一条容易踩的坑）

闭源产物对外**只有无参构造**（走真 Android Keystore）⇒ 在 JVM 上造不出实例。
而「把密钥换成内存里的那把」这个口子（`InMemoryKeyOps`）在闭源产物里是 **`internal`**：

- `javap` 看字节码是 `public`（Kotlin 的 `internal` 编译进字节码**就是** `public`），
- 但 **Kotlin 编译器拒绝外部模块访问** ⇒ 直接写
  `KeystoreCredentialCipher(InMemoryKeyOps())` 会**编译不过**：
  `Cannot access ...: it is internal in 'com/agentsdk/cipher/KeystoreCredentialCipher'`。

⇒ 所以这里用反射造实例。

★ **被验的 `seal` / `open` 走的是公开接口 `CredentialCipher`** ——
反射只用来「造一个不依赖 Keystore 的实例」，**不参与被验行为**。

⚠️ 反射找构造器时，参数类型必须**精确匹配**：
要传 `KeyOps` **接口**，不能拿实现类 `InMemoryKeyOps` 去顶 ⇒ 否则 `NoSuchMethodException`。

## 这份门禁**咬得住**吗（反向验证）

「现在是绿的」说明不了「门禁有效」。⇒ 把 2026-10-29 修掉的那个**真实**缺陷
（`HEADER_LEN` 写成「含 nonce 的 18」，却被当作 nonce 的**起始偏移**用 ⇒ 偏 12 字节
⇒ GCM 用错 IV）**注回去**，再跑：

| 阶段 | 结果 |
|---|---|
| 注入缺陷 | gradle **非零退出**；24 次执行 / **9 条红**；**正向那条「密封后能原样解开」先红** |
| 恢复 | gradle 零退出；24 次执行 / **0 红** |

⇒ 门禁真的咬得住。

## 它**不**验什么

- **不验真 Keystore**：测试用内存里的一把固定 AES 密钥。
  真 Keystore 的行为**只能在设备上验** ⇒ 「密钥在 Keystore 里不可导出」这条
  **属于设备端验收**，不属于这份契约测试。别把两者混为一谈。
- **不验密钥管理**：别名的生成规则、每设备一把密钥、账号维度隔离 —— 那些是**实现内部**的事，
  由《凭据加密模块规格.md》第四条写成可验收条款，在**宿主集成后**按那份清单验。

## 版本对应

| SDK 版本 | 闭源产物 | 说明 |
|---|---|---|
| v1.1.15 起 | `android-agent-sdk-cipher-v1.0.0-20261029.aar` | 首次**发布**闭源实现（接口与加载器自 v1.1.6 起就在开源仓里，见《凭据加密模块规格.md》§九） |
| v1.1.18 起 | 同上 | **契约测试公开**（本目录）⇒ 第三方可独立复现「12 条全绿」 |
