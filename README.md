# android-agent-sdk

面向 Android 的 **Agent 基础设施 SDK**：让大模型在设备上**执行命令、读写工作区、管理记忆**，
并把结果回填给模型，形成可闭环的智能体循环。

SDK 只做执行与协议，**不含界面、不含业务、不附带任何后端服务**。
宿主 App 决定「用哪个模型服务、存什么数据、界面怎么画」。

---

## 一、特性概览

| 能力 | 说明 |
|---|---|
| **命令执行** | 模型下发一条 shell 命令，SDK 在设备上执行并回填结构化结果（退出码 / stdout / stderr） |
| **真实流式** | 走 SSE 增量返回，正文与思维链分批到达；收到 `data: [DONE]` 立即结束，不等连接关闭 |
| **工具调用** | 单一 `bash` 工具；工具定义、参数解析、分片聚合、结果回填全部内置 |
| **设备能力** | 手电筒 / 震动 / 通知 / 剪贴板 / 电池等 15 条能力命令，进程内执行 |
| **工作区管理** | 拟 Linux 根目录、配额、清理、定时任务 |
| **长期记忆** | 上下文超限自动压缩；闲置归档；记忆条目可增删改查、按行打补丁 |
| **会话归档** | 被压缩掉的对话原文落盘存档，前端可分段翻回 |
| **凭据加密** | 第三方模型密钥加密落盘、绑定目标主机、解不开即失败 |
| **两种线格式** | 直连厂商官方（DeepSeek / OpenAI 系）与自建后端两套思考强度线格式，按地址自动选择 |

最低支持 **Android 7.0（API 24）**。

---

## 二、两条接入路径

本 SDK 的模型服务由**你**提供，两条路径并列，按需选一：

| 路径 | 服务地址 | 鉴权 | 思考强度线格式 |
|---|---|---|---|
| **A. 直连厂商官方** | `https://api.deepseek.com/v1` 或任意 OpenAI 兼容服务 | 厂商 API Key | 按厂商官方格式（`thinking` / `reasoning_effort`） |
| **B. 自建后端** | 你自己的服务地址 | 你的会话 token / 自定义头 | `reasoning_strength`（整数编码，由你的后端解释） |

> 两种形状对应 `ReasoningWire` 的取值；`AndroidAgentSdk.deepSeek(...)` 会按地址**自动选择**：
> 官方域名走官方形状，其它地址走自建后端形状。也可以显式指定，避免误判。

**A 与 B 的区别只在「谁解释思考强度」**，协议本体（OpenAI Chat Completions）完全一致，
切换不需要改任何业务代码。

---

## 三、快速开始

```kotlin
// ① 依赖（先在 SDK 工程里执行 ./gradlew publishToMavenLocal）
implementation("ai.agentkernel:sdk-android:1.0.0")

// ② 直连厂商官方：一行装配
val sdk = AndroidAgentSdk.deepSeek(
    context = context,
    apiKey = BuildConfig.DEEPSEEK_KEY,   // 只在宿主内保存，不写入源码
)

// ③ 跑一轮：模型下发命令 → SDK 在设备上执行 → 结果回喂 → 模型作答
val result = sdk.runTurn(listOf(UserMessage("查看当前目录下的文件")))
println(result.finalText)
```

接自建后端时，只改客户端装配：

```kotlin
val client = ChatCompletionsClient(
    baseUrl = "https://your-backend/v1",
    transport = OkHttpTransport(),
    auth = LlmAuth.bearerDynamic { sessionToken() },   // 每次请求现取，适配过期
    reasoningWire = ReasoningWire.GATEWAY,             // 自建后端形状
)

val sdk = AndroidAgentSdk(
    llm = client,
    model = "deepseek-chat",
    executor = AndroidCommandExecutor.forContext(context),
)
```

完整接入步骤、权限申请、真机验证与已知边界见 **[接入指南.md](接入指南.md)**。

---

## 四、模块与仓库结构

| 模块 | 类型 | 内容 |
|---|---|---|
| `sdk-core` | Kotlin/JVM | 协议模型、轮次循环、LLM 客户端、工具调度、能力契约、记忆与归档 |
| `sdk-android` | Android library | 命令执行器、设备能力实现、HTTP 传输、宿对面门 |

`sdk-core` 不依赖 `android.*`，可在 JVM 上直接跑测试；设备侧只通过
`CommandExecutor.execute(cmd)` 一个接口注入。

```
android-agent-sdk/
├── sdk-core/                 内核（纯 JVM）
├── sdk-android/              宿主层（Android library）
├── skills/                   Agent 技能文档（7 份，随包分发）
├── cipher-contract-test/     凭据加密核心的契约测试（源码公开，默认不参与构建）
├── agent-sdk-demo/           宿主示例 App（Kotlin + Compose）
├── bootstrap/README.md       可选的 Termux 运行时说明（载荷为独立资产，不进本仓库）
├── 功能清单.md                能力与边界的逐条清单（接入前评估用）
├── 接入指南.md
├── 凭据加密模块规格.md
└── 打包说明.md
```

> 需要逐条核对「能做什么、不能做什么」时看 [功能清单.md](功能清单.md)；
> 需要按步骤接入时看 [接入指南.md](接入指南.md)。

---

## 五、设备能力

除普通 shell 命令外，模型可直接下发下列能力命令。命令名与 Termux:API 保持一致，
模型无需学习新语法。

| 命令 | 作用 | 运行时权限 |
|---|---|---|
| `termux-torch on\|off` | 手电筒 | `CAMERA` |
| `termux-vibrate [-d 毫秒]` | 震动 | `VIBRATE`（静态） |
| `termux-notification -t 标题 -c 内容` | 发送通知 | `POST_NOTIFICATIONS`（Android 13+） |
| `termux-clipboard-get` | 读剪贴板 | 需 App 处于前台焦点 |
| `termux-clipboard-set <文本>` | 写剪贴板 | — |
| `termux-battery-status` | 电池状态 | — |
| `agent-workspace` / `agent-cleanup` / `agent-quota` | 工作区体检、清理、配额 | — |
| `agent-schedule list\|add\|remove\|due` | 定时任务 | — |
| `agent-http-get` / `agent-http-post` | 调宿主接口取数 | — |
| `agent-memory` | 长期记忆增删改查 | — |
| `agent-text` | 文本按行打补丁 | — |
| `agent-cred` | 凭据目录只读查询（不含取值） | — |

输出统一为 JSON；失败返回 `{"ok":false,"error":"…"}` 并带非零退出码。
能力不静默降级：权限不足、系统限制都会明确报错。

完整的命令参数与用法见 `skills/` 下 7 份技能文档。

---

## 六、系统提示词与预算

系统提示词由 SDK 在设备上生成，内容包括：拟 Linux 环境变量、**本机实际存在的命令清单**
（白名单 ∩ 运行时探测）、能力清单、能力边界、工作区规则。

```kotlin
val prompt = AndroidAgentSdk.buildSystemPrompt(executor)   // 不接模型也能取到，便于展示与调试
```

命令清单详略由 `commandDetail` 控制：

| 档位 | 提示词长度 | 适用 |
|---|---|---|
| `FULL` | 约 2.4 K 字符 | 服务端对 system 段没有严格上限时 |
| `CORE` | 约 2.0 K 字符 | 服务端 system 段上限可能被调小时（保险档） |

宿主可追加自己的说明（`extraNotes`），或改用自备提示词文件（`promptFile`）。

**关于 length caps**：提示词超长不会报错，只会被服务端静默截断。SDK 在发送前主动协调：
可压缩的只有长期记忆摘要（可用 `agent-memory search` 取回），
提示词正文、会话摘要、工作区告警一律不截断；确实装不下时显式报错，不静默。

---

## 七、上下文压缩、归档与长期记忆

上下文管理全部集中在 `ContextPolicy`，传入即启用，不传则历史只增不减。

```kotlin
val sdk = AndroidAgentSdk.deepSeek(
    context = context,
    apiKey = key,
    memoryPolicy = ContextPolicy(),   // 默认值即推荐值
)
```

| 场景 | 行为 |
|---|---|
| 单次请求超容量 | 将较早历史压缩为摘要，摘要进入稳定前缀，近期原文保留 |
| 话题闲置超时 | 归档为长期记忆并开启新会话 |

主要参数：`maxContextChars`（默认 24 000）、`keepRecentChars`（6 000）、
`summaryTargetChars`（1 200）、`idleArchiveMillis`（30 分钟）、
`memoryDigestChars`（1 500）、`maxMemoryEntries`（100）等。

**目录布局**（`<filesDir>` 为宿主私有目录）：

```
<filesDir>/agent/
├── usr/      拟 Linux 根（bin / lib / share）
├── home/     工作目录（shell 默认 cwd）
├── trash/    删除先入回收站
└── var/
    ├── memory/     长期记忆（index.json + entries/*.md）
    ├── archive/    会话归档（index.json + segments/*.json）
    └── schedule.json
```

记忆与归档都是**普通文件**，Agent 用已有终端能力即可读取，无需新增工具。
容量上限为硬线，超限按「删最旧」规则收敛；每日摘要槽与最新归档段永不删除。

---

## 八、凭据与加密

Agent 具备读文件的能力，因此**模型密钥不能明文落盘**：一次 `cat` 就可能把密钥带进上下文。
本 SDK 提供设备端凭据层：

```kotlin
// ① 注册加密实现（按类名加载，编译期零依赖）
CredentialCipherLoader.register("com.agentsdk.cipher.KeystoreCredentialCipher")

// ② 打开凭据仓库
val store = AndroidAgentSdk.credentialStore(context)

// ③ 写入（只经宿主 API，不进命令面）
store.setSecret("deepseek", "api.deepseek.com", userInput)

// ④ 取用：host 不匹配当场拒绝
val auth = store.authFor("deepseek", "https://api.deepseek.com/v1")
```

四条不变量：

- **磁盘上只有密文**：明文仅在写入瞬间与生成请求头瞬间存在；
- **解不开即失败**：绝不回退明文、绝不返回空串；
- **密钥绑定目标主机**：换主机即拒绝，在建客户端时判定；
- **命令面永不吐明文**：`agent-cred` 只提供 `list` / `status`，没有取值子命令。

### 加密核心的分发方式

凭据加密核心（Android Keystore 包裹 + AES-256-GCM）以**预编译产物**分发：
独立资产 `android-agent-sdk-cipher-v1.0.0-*.aar`，**不随主包发布**。

**接口与契约完全公开**：`CredentialCipher`、`CredentialCipherLoader`、
以及 12 条契约测试的源码都在本仓库内。实现本身不随源码发布，原因有两点：

1. **凭据实现应当只有一份**：多份实现意味着多套密钥处理逻辑，审计面与出错面同时放大；
2. **减少被复刻与误用的机会**：加密能力一旦被拆改嵌入不可信场景，风险会外溢到使用者。

因此采取「**契约公开、实现以产物交付**」的方式：任何人都可以取到已发布的 `.aar`，
用仓库内的一键脚本自行复核字节指纹与契约测试结果，**结论可独立验证，不必采信文档**：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/verify-cipher-contract.ps1
```

接口契约、实现方必须遵守的七条纪律、宿主侧验收清单见
**[凭据加密模块规格.md](凭据加密模块规格.md)**。

---

## 九、权限

| 权限 | 类型 | 由谁申请 |
|---|---|---|
| `INTERNET` | 静态 | SDK 清单已声明 |
| `VIBRATE` | 静态 | SDK 清单已声明 |
| `POST_NOTIFICATIONS` | 静态 + 运行时 | 宿主运行时申请（Android 13+） |
| `CAMERA` | 运行时 | 宿主运行时申请（仅手电筒） |

SDK 不主动弹权限框：申请时机由宿主界面决定。未授权时相关能力明确失败并说明原因。

---

## 十、构建与测试

```bash
# 指定 Android SDK：写 local.properties 的 sdk.dir，或设 ANDROID_HOME
./gradlew build                  # 编译 + AAR + 单元测试 + lint
./gradlew publishToMavenLocal    # 发布到本地 Maven，供示例 App 按坐标依赖

cd agent-sdk-demo
./gradlew assembleDebug          # 示例 App（按坐标拉取 SDK）
```

测试分三层，全部可离线运行：

| 层 | 内容 |
|---|---|
| 单元测试 | 协议线格式、流式聚合、能力路由、记忆与归档、凭据存储、传输取消 |
| 端到端（本地 mock） | 起本地 OpenAI 兼容服务，走真实 HTTP，校验 `tool_calls` 解析与命令回填 |
| 真机仪器化 | 设备能力（手电筒、通知、剪贴板、电池）与 Keystore 凭据往返 |

接真实模型服务的验证步骤见《接入指南》「真机验证」一节。

---

## 十一、已知边界

1. **私有目录不可执行**：`targetSdk ≥ 29` 时应用私有目录挂载为 `noexec`，执行自带 ELF 二进制会得到退出码 126；
   跑 shell 脚本不受影响。设备能力层为进程内执行，不受此限。
2. **不提供特权能力**：安装应用、模拟点击、修改系统设置等按设计不实现；`ShizukuBridge` 保持空实现。
3. **读剪贴板需前台焦点**：Android 10+ 系统限制，后台运行时该能力会明确失败。
4. **命令输出无体量上限**：可能产生大量输出的命令，建议在宿主侧加截断。
5. **明文 HTTP 被系统默认禁止**：需要时由宿主配置 `networkSecurityConfig`。
6. **`max_tokens` 含推理 token**：思考型模型给过小额度会导致正文被截断，建议 2048 以上。

---

## 十二、许可与第三方组件

- 本项目：**Apache-2.0**，见 [LICENSE](LICENSE)。
- 第三方组件与许可清单：见 [NOTICE](NOTICE)。
- `bootstrap` 载荷为 **Termux 运行时（GPLv3）**，作为**独立资产**分发，不进入主包：
  两者是两个独立作品，分开分发以避免许可相互约束；该载荷在 Android 10+ 上受
  `noexec` 限制，仅供有相应条件的场景参考，**不是使用本 SDK 的前提**。

---

*文档随版本更新；接口以源码与 `凭据加密模块规格.md` 为准。*
