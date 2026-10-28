# android-agent-sdk

给 agent（LLM）提供**执行 Linux 命令 / 操作 Android 设备**能力的最小 SDK。
agent 只要「输出一条命令」，SDK 就负责把它跑起来并把结果回填给模型。

> 定位：**SDK，不是 App，不搞生态。** 需要配合宿主 App 使用。
> 不提供交互式终端（无 PTY）。

> **要把这个 SDK 装进自己的 App？** 看 [`接入指南.md`](接入指南.md) ——
> 三种接入方式（mavenLocal / 拷 AAR / 复合构建）、权限怎么算、真机怎么验、已知坑。
> 本文讲的是**它是什么、为什么这么设计**。

## 开源说明

- **许可**：Apache-2.0（见 [`LICENSE`](LICENSE)）
- **第三方组件与许可**：见 [`NOTICE`](NOTICE)
- **不依赖任何后端**：拿到源码 + 一个 DeepSeek API key 就能跑；
  系统提示词由 SDK **在设备上自己生成**（完整自带，不靠服务端注入）。
- **`bootstrap/` 不在主包内**：那是 **Termux 的 GPLv3 二进制**，
  打进主包会让整个发行包受 copyleft 约束；而且它在 Android 10+ 上因 noexec **根本跑不起来**
  （真机实测退出码 126）。它作为**独立压缩包**单独分发，用不用由你决定
  （见《接入指南》「私有目录不能执行二进制」一节）。

## 三分钟跑起来

```kotlin
// ① 依赖（先在 SDK 工程里 gradle publishToMavenLocal）
implementation("ai.agentkernel:sdk-android:1.0.0")

// ② 建 SDK —— 一行，地址与鉴权都用 DeepSeek 官方
val sdk = AndroidAgentSdk.deepSeek(
    context = context,
    apiKey = BuildConfig.DEEPSEEK_KEY,   // 只在宿主 App 里存，绝不打包进 SDK
)

// ③ 跑一轮：模型会自己下发命令、SDK 在这台手机上执行、结果回喂给模型
val result = sdk.runTurn(listOf(UserMessage("看下当前目录有什么文件")))
println(result.finalText)
```

- **系统提示词不用你写** —— SDK 默认生成（环境 / 命令清单 / 能力边界 / 工作区规则）。
- **命令清单是这台设备上实测存在的**（白名单 ∩ 实际 `stat`），不写死。
- **上游的 `finish_reason` 会如实带回来**（`AssistantMessage.finishReason`）——
  被 `max_tokens` 截断时是 `length`，不会假装答完了。

## 模块划分

| 模块 | 类型 | 内容 | 是否含 Android 依赖 |
|------|------|------|----------------------|
| `sdk-core` | Kotlin/JVM | 内核：协议模型、轮次循环、LLM 客户端、工具调度、设备能力契约与路由 | 否（可独立单测） |
| `sdk-android` | Android library | 宿主层：命令执行器、设备能力实现、Shizuku 特权桥、门面 | 是 |

**内核与 Android 解耦**：`sdk-core` 不 import 任何 `android.*`，因此内核可在 JVM 上直接跑测试。
「设备能力」只通过一个接口 `CommandExecutor.execute(cmd)` 注入。

## 仓库结构

```
android-agent-sdk/          ← 仓库根（README / LICENSE / NOTICE 都在这一层）
├── sdk-core/               内核（纯 JVM，不依赖 android.*）
├── sdk-android/            宿主层（Android library）
├── skills/                 agent 技能文档（命令速查 / 工作区 / 数据接口 / 定时）
├── agent-sdk-demo/         宿主示例 App（Kotlin + Compose），可独立构建
├── bootstrap/README.md     可选的 Termux 运行时说明（载荷是 Release 附件，不进 git）
├── 接入指南.md              宿主 App 怎么把这个 SDK 装进去用
└── 打包说明.md              交付包是怎么打的
```

**从源码构建**（需要本机有 Android SDK）：

```bash
# ① 指到你的 Android SDK：建 local.properties 写 sdk.dir=<路径>，或者设 ANDROID_HOME
./gradlew build                 # 全量：编译 + AAR + 单测 + lint
./gradlew publishToMavenLocal   # 发到 ~/.m2，供下面的 demo 按坐标依赖

cd agent-sdk-demo
./gradlew assembleDebug         # 出 demo APK（依赖上一步发到 ~/.m2 的坐标）
```

> `agent-sdk-demo` 走的是**外置接入**（按坐标 `ai.agentkernel:sdk-android` 拉），
> 和《接入指南》推荐的用法一模一样 —— 所以它同时也是一个「怎么用这个 SDK」的活样例。

## 内核边界（明确写死）

**属于内核（`sdk-core`）：**
- LLM 协议线格式：`messages` / `tool_calls` / `tool_call_id` 的构造与解析；
- 协议级 JSON 解析：`tool_calls[].function.arguments` 是原始 JSON 字符串，由内核 `parse` 取参数；
- 流式分片聚合（`BlockAssembler`：按 `index` 拼 `arguments` 碎片）；
- 单轮循环：发请求 → 读 tool_calls → 提取 command → 执行 → 回填 → 循环至收尾。

**不属于内核（宿主负责）：**
- 跨轮历史拼接 / 压缩 / system prompt 组装 → 宿主拼好 `messages` 再传进来；
- 业务数据加工 → 由工具或宿主自己做；
- 交互终端 / UI / 前台保活 → 宿主 App 的事。

对应接口：`AgentKernel.runTurn(history) -> TurnResult(messages, stopReason, finalText)`，
返回的 `messages = 传入 history + 本轮新增`，下一轮是否续接、怎么持久化，全由宿主决定。

## 一次工具调用的完整链路

```
模型返回:  assistant.tool_calls = [{ id:"call_1", function:{ name:"bash",
                                                          arguments:"{\"command\":\"ls -la\"}" } }]
   │
   ├─ 内核: ToolCall.argsOrEmpty()  ── parse(arguments) ──▶ { "command": "ls -la" }
   │
   ├─ 内核: tools["bash"].execute(args)  ──▶ ShellTool 取出 command
   │
   ├─ 设备: CommandExecutor.execute("ls -la")  ──▶ ExecResult(exitCode, stdout, stderr)
   │
   └─ 内核: 回填 ToolMessage(toolCallId="call_1", content="exitCode: 0\n--- stdout ---\n...")
        └─ 追加进 transcript，继续下一轮请求
```

## 关键文件

```
sdk-core/src/main/kotlin/ai/agentkernel/
├── protocol/
│   ├── Messages.kt          # SystemMessage / UserMessage / AssistantMessage / ToolMessage
│   ├── ToolCall.kt          # arguments 原始字符串 + argsOrEmpty() 执行前才 parse
│   └── ChatRequest.kt
├── kernel/
│   ├── AgentKernel.kt       # 单轮循环（本仓库的核心）
│   └── TurnResult.kt        # TurnStop / TurnResult / KernelEvent
├── llm/
│   ├── LlmClient.kt         # 内核眼中的模型客户端
│   ├── BlockAssembler.kt    # 流式 tool_calls 按 index 聚合（坑点）
│   └── openai/
│       ├── ChatCompletionsClient.kt  # 请求组装 + 响应解析 + 流式聚合
│       ├── SseParser.kt
│       ├── Transport.kt              # HTTP 抽象
│       └── JdkHttpTransport.kt       # JVM 用
└── tools/
    ├── Tool.kt              # Tool 契约 + ToolRegistry
    └── exec/
        ├── CommandExecutor.kt  # execute(cmd) —— 内核↔设备唯一契约
        └── ShellTool.kt        # 把 tool_call 接到执行器的那根线

sdk-android/src/main/kotlin/ai/agentkernel/android/
├── AndroidCommandExecutor.kt  # Runtime.exec，无 PTY（对应 Termux AppShell 路径）
├── OkHttpTransport.kt         # Android 的 HTTP 实现
├── device/                    # 设备能力（手电筒/震动/通知/剪贴板/电池）
│   └── AndroidDeviceCapabilities.kt
├── shizuku/ShizukuBridge.kt   # 特权通道**预留**：su / Shizuku / rish 三通道
└── AndroidAgentSdk.kt         # 门面：组装 + 一行跑一轮

sdk-core/src/main/kotlin/ai/agentkernel/device/   # 能力契约与路由（不依赖 Android）
├── DeviceCapability.kt        # 能力接口 / CapabilityFailure / 注册表
└── DeviceCommandParser.kt     # 从一行 shell 里认出能力调用
```

## 本地验证（不需要设备、不需要模拟器）

自测是 **AGP 的 JVM unit test**，跑在宿主 JVM 上：

```bash
gradle :sdk-android:testDebugUnitTest     # 或 ./gradlew（已带 wrapper）
gradle :sdk-android:assembleRelease        # 出 AAR
gradle build                               # 全量：编译 + AAR + 测试 + lint
```

覆盖三层：

| 文件 | 覆盖什么 |
|------|---------|
| `sdk-android/src/test/kotlin/ai/agentkernel/CoreSelfTest.kt` | 内核循环、流式分片聚合、协议线格式、未知工具容错、maxTurns、真实进程执行、bootstrap 安装器、prefix 环境变量（**34 条断言**） |
| `sdk-android/src/test/kotlin/ai/agentkernel/EndToEndTest.kt` | **端到端**：起本地 OpenAI 兼容 mock 服务 → 真发 HTTP → 真按 OpenAI 线格式解析 `tool_calls` → 真执行命令 → 真把结果回填进下一次请求 → 收尾；外加流式 SSE 分片、命令失败、超时截断 |
| `sdk-android/src/test/kotlin/ai/agentkernel/WireFormatTest.kt` | **线格式契约**：对齐生产链路那份「拿 live 流校验过」的形状（见下） |
| `sdk-android/src/test/kotlin/ai/agentkernel/MockLlmServer.kt` | 那个 mock 服务端（裸 `ServerSocket` 写的，**故意不用 `com.sun.net.httpserver`** —— 后者在 Android 上不存在，这套 mock 要能原样搬到真机 instrumented test 里跑） |
| `sdk-core/src/test/kotlin/ai/agentkernel/device/DeviceCapabilityTest.kt` | **能力路由**（纯 JVM，16 条）：认命令、拆参数、复合命令改写、失败转非零退出码、命令名逐字对齐 Termux |
| `sdk-android/src/test/kotlin/ai/agentkernel/DeviceCapabilityRoutingTest.kt` | **能力接进执行器**：整条命令进程内执行（不起子进程）、失败退出码、复合命令结果回 stdout、能力清单进工具描述 |

> 交付包原本的自测是一份手工 `main()`（`selftest/SelfTest.kt`），需要 661MB 的 `.toolchain/`
> 才能跑，且**模型端是 `FakeLlm`** —— 也就是说「真发 HTTP / 真解析 tool_calls /
> 真把结果回填进下一次请求」这三件事**一次都没被验证过**。
> 现在的分层就是为了补上这个洞。

### 线格式契约：以官方线格式为基准

`WireFormatTest` 的基准不是我们自己想的形状，而是 DeepSeek 官方线格式那份
**标注了 "cross-checked against live streams"** 的实现
（harness 仓库 `packages/llm/llm-deepseek/src/types.ts` 与同目录 `serialize.ts`）。

这几条在本地 mock 上**全都看不出来**（mock 照单全收），但对着真服务时每条都是当场 400：

| 规则 | 官方线格式怎么做 | SDK 之前 | 现在 |
|------|---------------|-----------|------|
| assistant 的 `content` | **永远给字符串**，给 null 有些服务直接拒；「只有思考、既无正文又无 tool_calls」的回合 live API 会 400（`content or tool_calls must be set`），而消息在会话日志里，一坏就带崩后面每一轮 | 给 `null` | 给 `""` |
| `reasoning_content` | 带 tool_calls 的回合**必须回传**（否则思考链断掉）；纯文本回合不带（省 token） | 完全没解析、没透传 | 解析 + 聚合 + 按规则回传 |
| tool 消息的 content | 空输出兜底成 `'(no output)'` | 空串 | 兜底 |
| 流式 | 官方链路**全程 `stream: true`** + `accept: text/event-stream` | 内核只走非流式 | 内核走流式；服务端若忽略 `stream:true` 仍回整块 JSON，则自动按普通 JSON 解析 |

### 用真实 LLM 跑一遍（最后一档，会真实计费）

```bash
set LLM_BASE_URL=https://your-gateway/v1
set LLM_API_KEY=sk-xxx
set LLM_MODEL=deepseek-chat
gradle :sdk-android:realLlmCheck
```

退出码 0 = 真链路跑通：模型真的下发了命令 → 命令真的执行了 → 结果真的回喂给了模型 → 模型答对了。
这一档验的是「和真网关的线格式对不对得上」，是本地 mock 验不了的那一段。



## 设备能力（`termux-*` 命令）

模型除了跑普通 shell 命令，还能直接下发**设备能力命令**：手电筒、震动、通知、剪贴板、电池。

### 为什么是「进程内路由」而不是往 PATH 塞二进制

塞二进制要付三笔账：

1. **noexec** —— 宿主 `targetSdk ≥ 29` 时私有目录里的可执行文件直接 EACCES 起不来
   （唯一豁免是 `nativeLibraryDir`，那就要按 ABI 拆包）；
2. **shebang 改写** —— bootstrap 里的脚本写死了 `/data/data/com.termux/...`；
3. **包名路径** —— `bin/am` 之类还写死了 `com.termux`。

走能力路由，这三笔账**一笔都不用付**：命令由宿主在进程内执行，不经过「文件能不能被执行」这一关。

### 命令名逐字对齐 Termux:API

| 命令 | 作用 | 权限 |
|------|------|------|
| `termux-torch on\|off` | 手电筒 | `CAMERA` |
| `termux-vibrate [-d 毫秒]` | 震动 | `VIBRATE` |
| `termux-notification -t 标题 -c 内容` | 发通知 | `POST_NOTIFICATIONS`（API 33+） |
| `termux-clipboard-get` | 读剪贴板 | 无 |
| `termux-clipboard-set <文本>` | 写剪贴板 | 无 |
| `termux-battery-status` | 电池状态 | 无 |

**名字必须一字不差**：模型是按 Termux:API 的习惯写命令的。我们要是把剪贴板合并成一个
`clipboard` 能力，模型写 `termux-clipboard-set` 就会落空 —— 它按 shell 去找这个二进制，
找不到，得到 `command not found`，而不是我们准备好的 JSON。
名字对齐 = 模型不用学新方言。（`DeviceCapabilityTest` 里有一条回归护栏盯着这件事。）

输出**统一是 JSON**（`{"ok":true,...}` / `{"ok":false,"error":...}`）。
唯一与 Termux:API 不同的地方是 `termux-clipboard-get`：那边打印纯文本，这里给 JSON ——
因为纯文本会被模型当成普通输出混过去，JSON 才能区分「剪贴板是空的」和「取剪贴板失败」。

### 失败怎么表达

**能力失败一律抛 `CapabilityFailure`**，由注册表统一转成 `{"ok":false,...}` + **非零退出码**。

不靠解析能力返回的 JSON 判成败：那样注册表就得回头解析内容，一旦能力自己改了格式，
退出码就会和内容对不上（内容说失败、退出码说成功）。

### 复合命令：结果经环境变量交给 shell

`termux-torch on && echo done` 这种组合是支持的。做法是**能力先跑**，结果放进环境变量，
命令里的能力调用换成 shell **内建**命令：

```
printf '%s' "$AGENT_CAP_0_0" && echo done
```

- 成功 → 打印结果，退出码 0；
- 失败 → `printf '%s' "$AGENT_CAP_0_0"; false` —— **照样把失败原因打印出来**，
  但整段退出码是 1，于是 `&&` 链短路、`||` 链走兜底分支，与真实命令语义一致。

**为什么不落盘 + `cat` 文件**：那条路依赖宿主 shell 还能找到 `cat`，而 SDK 恰恰会把 PATH
整个换成「拟 Linux 的 bin + `/system/bin`」——换个宿主 `cat` 就不在了
（本机 Git Bash 实测 `cat: command not found`，退出码 127）。
环境变量方案不依赖任何外部程序：`printf` 是 POSIX shell 内建命令（bash / dash / mksh / ash 都有），
不用建临时文件，也就没有路径转义和残留清理的问题。

### 边界：高级权限不给手机碰

这批能力**只用 app 自身的普通权限**。装应用 / 点屏 / 改系统设置那类**不实现**，
`ShizukuBridge` 保持 `Noop`；相机 / 定位 / 短信那类要运行时授权或太敏感的，这一批也先不做。

### 真机实测：剪贴板**读**要前台焦点（Android 10+ 的系统限制）

在真机（Android 16 / 小米）上实测到：

```
E/ClipboardService: Denying clipboard access to <pkg>, application is not in focus
```

系统规定**只有当前有焦点的 App（以及默认输入法）能读剪贴板**。
此时 `getPrimaryClip()` 返回 **null** —— 与「剪贴板确实是空的」**无法区分**。

所以 `termux-clipboard-get` **不把 null 当成空剪贴板**（那样等于骗模型「剪贴板是空的」），
而是明确报错，并把两种可能的原因都告诉模型：

```json
{"ok":false,"error":"读不到剪贴板：可能是剪贴板为空，也可能是 App 当前没有前台焦点（Android 10+ 的系统限制；logcat 里会有「ClipboardService: Denying clipboard access」）。"}
```

**写入不受此限**（实测后台进程写进去，系统剪贴板确实收到了）。

⇒ 对 agent 的实际影响：如果宿主 App 在后台跑 agent，`termux-clipboard-get` 常常会失败。
这是**系统限制，不是 SDK 的 bug**，但宿主要清楚。

### 真机实测：哪些能力不需要权限

`termux-battery-status` / `termux-vibrate` / `termux-clipboard-set` **无运行时权限**，
`termux-torch` 需要 `CAMERA`、`termux-notification` 需要 `POST_NOTIFICATIONS`（API 33+）——
但这两个权限被拒时能力是**明确失败**（`{"ok":false,...}`），不会静默。

### 怎么接上

```kotlin
val caps = DeviceCapabilityRegistry(defaultDeviceCapabilities(context))
val sdk = AndroidAgentSdk(
    llm = ChatCompletionsClient(baseUrl, apiKey, OkHttpTransport()),
    model = "deepseek-chat",
    executor = AndroidCommandExecutor.forContext(context, caps),
)
// 能力清单会自动进 bash 工具的描述 —— 模型看得见才会用
```

> **「看得见」是硬要求**：模型只看得见工具的 `description`。能力实现了但不写进描述，
> 等于没实现 —— 模型不会凭空猜出 `termux-torch` 这个词，它只会写 `ls`、`cat`、`echo`。
> `AndroidAgentSdk` 默认从 executor 自动取同一份注册表，保证「能执行」和「模型看得见」不会对不上。


## 接模型服务：线格式一个字不动

SDK 适配的是 **OpenAI Chat Completions 线格式**（请求体、流式 SSE、`tool_calls`、`reasoning_content`
全部按 OpenAI 那套），所以**DeepSeek 官方、以及任何 OpenAI 兼容的服务，SDK 都不用改一行协议代码**。

### 最省事：直接用 DeepSeek 官方

```kotlin
val sdk = AndroidAgentSdk.deepSeek(
    context = context,
    apiKey = BuildConfig.DEEPSEEK_KEY,   // 只在宿主 App 里存，绝不打包进 SDK
)
val result = sdk.runTurn(listOf(UserMessage("看下当前目录有什么文件")))
println(result.finalText)
```

一行就够了 —— 地址用 DeepSeek 官方（`https://api.deepseek.com/v1`）、鉴权走
`Authorization: Bearer <key>`、执行器按宿主 Context 装配、**系统提示词由 SDK 自己生成**
（完整自带，见下）。

> ⚠️ **`apiKey` 更好的做法**：让宿主的服务端代理 LLM 请求，手机只跟自己的服务端说话。
> 这样密钥永远不进 APK，也顺带拿到模型池、兜底与审计。
> 直接把 key 打进 APK 等于把它发给每一个装了这个 App 的人。
> （个人自用 / 内部工具另说。）

### 换成别的服务：只改鉴权与地址

```kotlin
val client = ChatCompletionsClient(
    baseUrl = "https://你们的网关/v1",      // 服务地址
    transport = OkHttpTransport(),
    auth = LlmAuth.bearer(token),          // 鉴权
)
```

`LlmAuth` 支持四种形状：

| 写法 | 实际请求头 | 什么时候用 |
|------|-----------|-----------|
| `LlmAuth.bearer(token)` | `Authorization: Bearer <token>` | 大多数兼容服务 |
| `LlmAuth.bearerDynamic { ... }` | 同上，但**每次请求现取 token** | token 会过期（不用重建客户端） |
| `LlmAuth.header("X-Token", token)` | `X-Token: <token>` | 自建网关用自己的头名 |
| `LlmAuth.none()` | 不带鉴权 | 内网免鉴权 |

路径不是 `/chat/completions` 时用 `chatPath` 覆盖（baseUrl 写 `.../v1`，路径写 `/internal/turn`）。

官方写法仍然兼容：`ChatCompletionsClient(baseUrl, apiKey, transport)` = `LlmAuth.bearer(apiKey)`。

### 能调的参数

| 参数 | 在哪 | 说明 |
|------|------|------|
| `baseUrl` / `apiKey` | `ChatCompletionsClient` | 服务地址与鉴权 |
| `model` | `AndroidAgentSdk` | 模型名。DeepSeek 官方：`deepseek-chat`（不思考、快）/ `deepseek-reasoner`（会推理） |
| `maxTokens` | `AndroidAgentSdk` | 回答的 token 上限（`max_tokens`）。**不填就不带这个字段**，由服务端用默认值 |
| `reasoningStrength` | `AndroidAgentSdk` | 思考强度的**强度编码**（2 的幂：1=关闭思考 / 2=low / 4=medium / 8=high / 16=max）。**不填就不带**，由服务端用默认档；它是**上限**（服务端只向下取）。合法档位用 `EffortCatalogClient` 问服务端要，别自己抄 |
| `temperature` | `AndroidAgentSdk` | 采样温度 |
| `extraRequest` | `AndroidAgentSdk` | 透传任意 JSON 字段（厂商扩展参数） |
| `maxTurns` | `AndroidAgentSdk` | 一轮最多几次模型往返，默认 32 |
| `commandDetail` | `AndroidAgentSdk` | 提示词里的命令清单写多少（见下） |
| `extraNotes` | `AndroidAgentSdk` | 宿主自己的补充说明，拼进系统提示词 |

> ⚠️ **`maxTokens` 与思考型模型**：推理 token 也算在这个额度里。额度给小了，
> 正文就只剩几个 token，答案会是半句。**要限制就给个够正文写完的数**（2048 / 4096），别给几十。
> SDK 会把上游的 `finish_reason` 如实带回来 —— 被截断时是 `length`（`AssistantMessage.finishReason`），
> 不会假装答完了。

### 接自建网关前，确认这三件事

1. **透传 `tools`** —— 不透传，模型就看不见工具，永远吐不出 `tool_calls`，第一轮就卡死；
2. **system 段别截断** —— 提示词是模型的「环境说明书」，砍掉就等于没写
   （实测有服务硬截断到 2000 字符，见「硬约束」一节）；
3. **模型名怎么定** —— 有的服务忽略客户端传的 model，直接走服务端配置的。

## 系统提示词：**SDK 自己带全，不依赖任何后端**

模型看不见这台手机 —— 它不知道自己在哪儿、工作目录是哪儿、哪些命令能用、哪些事做不了。
所以 SDK **默认会生成一段系统提示词**（`AgentSystemPrompt`），一次讲清楚：

- 拟 Linux 环境（`HOME` / `PREFIX` / `TMPDIR` / `AGENT_TRASH` / `PATH`）；
- 这台设备上**实测存在**的命令清单（白名单 ∩ 实际 stat，不写死）；
- 设备能力清单（`termux-*`）与内部命令（`agent-*`）；
- 能力边界（哪些事做不了）；
- 工作区维护规则（配额不写死、超限自己清）。

**这一段完全由 SDK 在设备上生成，不依赖任何服务端提示词注入** —— 所以你不需要有自己的提示词库。
宿主想加自己的说明，走 `extraNotes`。

不接模型也能拿到这份提示词（用于展示或调试）：

```kotlin
val prompt = AndroidAgentSdk.buildSystemPrompt(executor)   // 默认完整清单
```

命令清单写多少由 `commandDetail` 决定：

| 档位 | 什么时候用 |
|------|-----------|
| `FULL`（**默认**） | 直连 DeepSeek 官方等**没有 system 段字数上限**的服务 —— 列全更有用 |
| `CORE` | 你的服务对客户端 system 段有字数上限（实测有硬截断到 2000 字符的）—— 瘦身后装得下，完整清单仍在 `skills/android-capability` 里按需查 |


## 上下文与缓存命中：**前缀稳定**才是省 token 的关键

上游（DeepSeek Context Caching on Disk）默认开启，客户端**不声明任何断点** ——
但只有**逐字相同**的前缀才命中。所以 SDK 把消息分成三段，顺序不能换：

```
prefix（稳定）    +  history（只增不改）   +  suffix（易变）
系统提示词         +  对话历史              +  本轮临时内容
工作区告警
```

两条硬规矩（踩过的坑）：

1. **稳定前缀里不许有每轮变的数字。** 工作区告警以前写「占用 12.3MB（37%）」——
   数字每轮变 → 前缀每轮断 → **命中率从第 3 轮起归零**。现在是常量文本。
2. **注入的内容不进返回的会话轨**（`TurnResult.messages` 只有「历史 + 本轮新增」）——
   否则每轮多拼一次系统提示词，前缀从此对不齐。

SDK 给你三样观测手段：

```kotlin
result.usage      // Usage?：上游报的命中/未命中 token（不是本地估的）
result.context    // ContextReport?：前缀有没有被动过、断点在下标几
sdk.systemBudget()// 客户端 system 段会不会被网关截掉
```

**上下文检测**靠 `ContextReport`：`prefixPreserved=false` 就说明**我们的布局坏了**
（`firstChangedIndex` 指出被改的那条）；前缀没坏却没命中 = 缓存还没建起来，等着就行。

> ⚠️ 体检器要**跨轮复用同一个实例**（`AndroidAgentSdk(inspector = …)`）——
> 每轮新建的话它永远以为「这是第一次请求」，该报的漂移一个都发现不了。

实测（真机 + 真实上游）：短问答 6 轮稳定前缀 1408 tokens → 命中率 84~91%；
长前缀 4000 tokens → **95.3%**；官方明说 best-effort、**不保证 100%**。
详细的规则、实测表与「怎么再往上抬」见《接入指南》11.11。


## 演示 App

`agent-sdk-demo/` 是一个**能点的演示台**（Kotlin + Compose），它按坐标依赖本 SDK
（`mavenLocal()` + `ai.agentkernel:sdk-android:1.0.0`）——所以它同时是**外置接入的活样例**。

三个页签：

| 页签 | 做什么 |
|------|-------|
| **能力台** | 每个能力一个图标，点一下就通过 SDK 在这台手机上真的执行，下方显示模型会看到的原始 JSON |
| **自检** | 「一键自检」自动跑完全部能力 + 普通 shell 命令 + 权限现状，逐项给 ✅/⚠️/❌ 与耗时；**页底直接摆着「模型看到的系统提示词」原文** |
| **对话** | 填服务地址 + token（或 DeepSeek 官方 key），让模型通过 bash 工具下发 `termux-*` 命令去驱动设备 |

> 自检当前 **34 项**（Android 16 / 小米 / arm64-v8a 实测 **✅ 32 · ⚠️ 2 · ❌ 0**，
> 两个 ⚠️ 是如实报告的系统限制：`CAMERA` 未授权、私有目录不能执行二进制）。
>
> 自检本身也有三条规矩：**阈值不写死**（先量占用再压到一半）、
> **空结果不算通过**（探测坏了 ≠ 没有）、**自检不留垃圾**（加的定时任务自己删掉）。

「什么情况算合格」在自检页顶部写死了：

- ✅ **合格** —— 退出码 0 且返回 `{"ok":true,...}`；
- ⚠️ **受系统限制** —— 如实报错（`ok:false` + 非零退出码 + 说清原因）；
- ❌ **不合格** —— 返回了不该出现的错误，**或者谎报成功**。

> 「如实报错」和「假装成功」是两回事：前者合格，后者不合格。
> 比如剪贴板读不到（Android 10+ 要前台焦点），如实报错 = 合格；
> 如果回 `{"ok":true,"text":""}` 骗人说剪贴板是空的 = 不合格。

自检也能从命令行触发（不用盯着屏幕，结果写 logcat）：

```bash
adb shell am start -n com.agentkernel.demo/.MainActivity --ez autorun_selfcheck true
adb logcat -d -s AgentSdkDemo
```

手电筒是**开关**（点一下开、再点一下关），单独有一条自检入口，
走的是按钮点的**同一个函数**，三刀：基线关 → 开 → 关：

```bash
adb shell am start -n com.agentkernel.demo/.MainActivity --ez autorun_torch true
adb logcat -d -s AgentSdkDemo
```

### 文件基础操作（写 / 读 / 删 / 跑脚本）

不需要新能力，走普通 shell（toybox 自带 `echo`/`cat`/`rm`/`ls`/`sh`）即可；
前提是执行器的**工作目录 = HOME**（`files/agent/home`），相对路径落在 App 私有目录里。

| 能力 | 命令 | 真机 |
|------|------|------|
| 写文件 | `echo "hello" > demo.txt` | ✅ |
| 读文件 | `cat demo.txt` | ✅ |
| 写多行 | `cat > notes.txt << 'EOF' … EOF` | ✅ |
| 读多行 | `cat notes.txt` | ✅ |
| 跑脚本 | `echo 'echo ok' > run.sh && sh run.sh` | ✅ |
| 删文件 | `rm -f demo.txt notes.txt run.sh` | ✅ |
| 能力输出存文件 | `termux-battery-status > battery.txt` | ✅ |

⚠️ **能跑脚本 ≠ 能跑二进制**：跑 shell 脚本可以（显式给解释器）；
跑 **ELF 二进制不行** —— `targetSdk ≥ 29` 时私有目录里的可执行文件直接 EACCES
（唯一豁免是 `nativeLibraryDir`，那要按 ABI 拆包）。

## 工作区、内部命令与技能目录

agent 不是「跑完一条命令就完了」——它在一个**隔离工作区**里长期干活，
自己产生的垃圾自己清，超配额时会被提醒。详见《接入指南》第十一节。

### 隔离工作区

```
<filesDir>/agent/     ← agent 私有根
  usr/  home/  var/  trash/  usr-staging/
```

收进一棵子树，是为了不和宿主 App 自己的文件平级（平级的话 agent 一个 `rm -rf` 就误伤）。
⚠️ 这是**工作区隔离，不是权限隔离**（同一个 UID）。

### 配额不写死 + 垃圾自己清

| 命令 | 干什么 |
|---|---|
| `agent-workspace` | 占用、配额、各目录分布 |
| `agent-cleanup --dry-run` / `--apply` / `--purge-trash` | 干跑 / 清理 / 清回收站 |
| `agent-quota show` / `set 200MB` / `reset` | 配额可由**宿主注入**，也可由**agent 自己改**（持久化） |

- 配额默认是「**设备剩余空间的 5%**」—— 跟着实况走，不是写死的坎；
- 只有**明确命中**垃圾规则的才算垃圾（`*.tmp` `*.log` `*.part` `*.pyc` `core.*` …），其余一律保留；
- 目录里放一个空文件 `.agent-keep` → 整个目录不动；
- 超限（到 85% 就开始提醒）时，宿主把一条**可执行**的告警插进对话 → **agent 自己清理**。

### 内部命令（`agent-` 前缀，不在 Termux:API 里）

`agent-workspace` / `agent-cleanup` / `agent-quota` / `agent-schedule` / `agent-http-get` / `agent-http-post`。

用**另一个前缀**是为了不让模型把内部命令误当成 Termux 命令到处找；
系统提示词里也会**分开介绍**这两类。

### 宿主自己的数据 API

`agent-http-get` / `agent-http-post`：**鉴权由宿主注入**，agent 只写相对路径，
**token 不出现在命令里**；响应体有上限；非 2xx 明确报错并翻译成模型能照做的话。

### 系统提示词写死

`AndroidAgentSdk` 默认补一段提示词：环境路径 / 可用命令 / **能力边界**（装应用、点屏、改系统设置不做）/
工作区规则。**结构固定，内容随环境填** —— 不会出现「提示词里写的命令其实没注册」。

**命令清单是运行时探测出来的，不是写死的。**
提示词里的每个命令都取「**白名单 ∩ 这台设备上真实存在**」——
toybox 的子命令集合跟系统版本走（`awk`/`xxd`/`stat`/`timeout` 都是后来才进 toybox 的），
写死一份清单，在老机型上就是在骗模型：它会一次次去试，每次失败还要在工作区里留垃圾。

- 白名单**故意不收** `pm` / `am` / `dumpsys` / `su` —— 它们在 `/system/bin` 里确实存在，
  但要么要特权、要么会引出「装应用 / 点屏」这种我们故意不做的事；模型看见 `pm install` 就会去试。
- 也**不探** `PREFIX/bin`（拟 Linux 根）：它在宿主私有目录里，而 Android 对私有目录是 noexec 的，
  那里的 ELF 执行不了（真机实测 exit=126）—— 列进去同样是骗模型。
- 宿主可以**不接模型**就拿到这份提示词（展示 / 调试 / 核对）：
  ```kotlin
  val prompt = AndroidAgentSdk.buildSystemPrompt(executor, policy = guard.effectivePolicy().first)
  ```

**默认是「完整清单」（`CommandDetail.FULL`）—— 直连官方模型服务时没有 system 段字数上限。**
但如果你的服务会把客户端 system 段**硬截断到 2000 字符**（实测很多中转服务会），
砍掉的正好是提示词**尾部** —— 也就是「能力清单」和「工作区维护规则」这两段最要命的内容。
这时就显式传 `commandDetail = AgentSystemPrompt.CommandDetail.CORE`：
提示词只写**核心命令**（仍然是白名单 ∩ 实存），**完整清单在 `skills/android-capability`** 里按需查。

实测（真机等价输入，`DevicePromptBudgetTest` 复刻并交叉校验过）：**核心模式 1809 字符**，
装得进 2000 预算（余量 191）；**完整模式 2209 字符**。
环境段也一起压了：只写一次绝对路径（工作区根），子路径用 `$AGENT_ROOT/…` `$PREFIX/…` 拼 ——
这些变量在 shell 里是真设了的，模型写出来直接能跑。

| 模式 | 提示词内容 | 真机长度 |
|---|---|---|
| `FULL`（默认） | 全部探测到的命令（82 条）+ 能力清单 + 边界 + 工作区规则 | 2209 字符 |
| `CORE` | 核心命令（28 条）+ 能力清单 + 边界 + 工作区规则 | 1809 字符 |

⚠️ `CORE` 模式**故意不教原生 `curl -H`** —— 免得模型为了调内部接口就写
`curl -H 'Authorization: Bearer …'`，那会把凭证写进命令历史和转录；
正确做法是走 `agent-http-get` / `agent-http-post`（鉴权由宿主注入，命令里没有 token）。

> ⚠️ **这份清单只管「告诉模型什么」，不管「拦不让你跑」。**
> 真机实测：`/system/bin` 有 **434** 个可执行文件，提示词只介绍了 **82** 个 ——
> 剩下 **352** 个（`vmstat`/`strace`/`top`/`dumpsys`…）**照样能跑**。
> 被我们接管的是**注册过的能力命令**（13 个），其它一律原样丢给 `/system/bin/sh`。
> 详见《接入指南》11.8。

### 定时任务

`agent-schedule add "*/5 * * * *" "…"` —— SDK 定义任务并判定到期，
**到点唤醒由宿主负责**（`WorkManager` / `AlarmManager`）。

### 技能目录

`skills/` 下：`agent-workspace`（环境与维护规范）、`agent-data-api`（内部接口调用）、
`agent-scheduling`（定时任务）、`android-capability`（硬件能力速查）。

## 五条必须知道的硬约束

1. **私有目录不能执行 ELF**（targetSdk ≥ 29）⇒ **已决定不预装 Python、不装 Termux 运行时**；
   跑 shell 脚本可以（`sh x.sh`），多行文件用 heredoc。
   将来若真要跑二进制：宿主 targetSdk 降到 28，或 ELF 放 `nativeLibraryDir`。
   ⚠️ **别用 `adb shell run-as` 测这个** —— 它跑在更宽松的 SELinux 域，会给假阳性。
2. **明文 HTTP 被系统禁**（Android 9+）⇒ 宿主要配 `networkSecurityConfig` 或用 https。
3. **`/system/bin` 列不出来**（`drwxr-x--x`）⇒ 探测系统命令要**逐个 stat**，不能 `list()`。
4. **服务可能截断客户端 system 段**（实测有服务硬截断到 2000 字符）⇒ 提示词要放得下；
   本 SDK 默认**列完整命令清单**（`FULL`），遇到有字数上限的服务时显式改用 `CORE`，
   完整清单在 `skills/android-capability` 里按需查。
5. **agent 循环要跑在进程级 scope**，不能挂 composition scope（`LaunchedEffect`）——
   activity 一被重建，循环就**中途夭折**，而且是**静默失败**（只抛一句取消异常，
   看起来像「模型没说话」）。`viewModelScope` 也不够（activity 被 finish 时 ViewModel 一样被 clear）。
   ⇒ 用 **Application scope / WorkManager**，详见《接入指南》12.5。

## 设计取舍的出处

只抄「内核」思路，不抄架构：

| 本 SDK | 对应的通用做法 |
|--------|----------|
| `AgentKernel.runTurn` | agent-loop 的 kick→turn→step |
| `ChatRequest` / `ChatCompletionsClient` | prepare → build → stream |
| `BlockAssembler` | 流式 tool_calls 的按 index 聚合器 |
| `ToolMessage` 回填 | 工具结果回填成一条 tool 消息 |
| `Tool.schema()` | 函数式 tool 的 schema 投影 |
| `CommandExecutor` / `ShellTool` | bash 工具 + 本地子进程 |

一条原则贯穿：**「model-visible ⟺ logged」** ——
凡回填给模型的 `ToolMessage`，就是会话音轨的一条记录（`TurnResult.messages` 里能看到）。

## 快速上手

**最短的路径**（DeepSeek 官方，填个 key 就能跑）：

```kotlin
val sdk = AndroidAgentSdk.deepSeek(
    context = context,
    apiKey = BuildConfig.DEEPSEEK_KEY,   // 只在宿主 App 里存，绝不打包进 SDK
)
val result = sdk.runTurn(listOf(UserMessage("看下当前目录有什么文件")))
println(result.finalText)
```

**要更多控制时**（自定义服务 / 挂设备能力 / 限制 token 预算）：

```kotlin
val sdk = AndroidAgentSdk(
    llm = ChatCompletionsClient.deepSeek(
        apiKey = BuildConfig.DEEPSEEK_KEY,
        transport = OkHttpTransport(),
    ),
    model = "deepseek-chat",
    // 拟 Linux 根必须跟着宿主 App 走，所以 executor 是必填的：
    executor = AndroidCommandExecutor.forContext(context),
    // 想让模型能开手电筒/发通知，就挂上设备能力（可选，见「设备能力」一节）：
    // capabilities = DeviceCapabilityRegistry(defaultDeviceCapabilities(context)),
    // 想限制单次回答的 token 预算（可选）：
    // maxTokens = 2048,
)
```

> **系统提示词不用你写** —— SDK 默认生成（环境 / 命令清单 / 能力边界 / 工作区规则）。
> 手写的版本会跟实际注册的能力对不上。
> 确实要自己控制才传 `systemPrompt`（`null` = 用生成的，`""` = 不加）。



## 注意事项 / 待办

- `targetSdk`：若宿主 App 内置自己的 Linux 用户态二进制，需保持 **28**（≥29 私有目录 noexec，二进制会 EACCES 起不来）。宿主坚持高 targetSdk 时，只能把二进制放进 APK 的 `lib/`（`nativeLibraryDir`，noexec 的唯一豁免）—— 但那样 bootstrap 得按 ABI 拆包。
- 特权能力（装应用 / 点屏 / 改设置）需实现 `ShizukuBridge`；未接入时特权调用返回明确失败，不会静默。
- `AndroidCommandExecutor` 未做输出体积上限；如命令可能产生海量输出，请在 `ShellTool` 侧加截断。
- `AndroidCommandExecutor` 的 PATH 是「拟 Linux 的 bin + `/system/bin` + `/system/xbin`」（分隔符跟平台走：Android `:`、Windows `;`）。在桌面机上只有 shell **内建**命令可用（`echo` / `printf` / `read` …）——设计如此，不是 bug；真机上 `/system/bin` 里有 toybox。设备能力路由也只依赖 `printf` 这类内建命令，所以在任何宿主上都成立。

### bootstrap 的三个坑（把 Termux 运行时装进宿主之前必须处理）

1. **shebang 写死了 `/data/data/com.termux/files/usr/bin/sh`** —— 换包名后所有脚本都会 "bad interpreter"。Termux 自己有 `termux-fix-shebang` 干这件事，本包的 `BootstrapInstaller` **没有调用**。
2. **脚本里的包名路径**同样写死 `com.termux`（例如 `bin/am` 里的 `AM_APK_PATH`）。
3. **`bin/am` 是 Termux 私有实现**，靠 `libexec/termux-am/am.apk` + app 进程内的 socket server；系统自带的 `/system/bin/am` 会被 prefix 遮住。

### 这一版修掉的问题（原先编译不过 / lint 不过）

- `Process.waitFor(timeout)` 与 `destroyForcibly()` 都是 **API 26** 才有的，而模块声明 `minSdk 24` —— Android 7.0/7.1 上走超时分支会 `NoSuchMethodError`。改成「独立等待线程 + `Thread.join(timeout)`」（`Thread.join` 是 API 1，行为跨版本一致），强杀再按 `SDK_INT` 分流。
- 写死的 `DEFAULT_FILES_DIR = "/data/data/ai.agentkernel/files"`：宿主包名根本不是 `ai.agentkernel`，多用户下真实路径是 `/data/user/<userId>/<pkg>`。写错的代价是**静默**的 —— 命令照样跑得起来（PATH 里还有 `/system/bin`），但 HOME / PREFIX / TMPDIR 指到别人的目录，写文件直接失败。现在 `PrefixLayout` 必填，另给 `AndroidCommandExecutor.forContext(context)` 一行写法。
- `kotlinx-serialization-json` / `kotlinx-coroutines-core` 原先声明成 `implementation`，但 SDK 的公开 API 直接暴露 `JsonObject`（`Tool.parameters`、`ToolCall.argsOrEmpty()`）—— 宿主只要自己实现一个 Tool 就会编译不过。已改成 `api`。
- `AndroidAgentSdk` 的 `executor` 改为必填：命令执行器要知道「拟 Linux 根」在宿主 App 的哪个目录，这只能由宿主的 `Context` 给出。
- `Vibrator.vibrate(VibrationEffect)` 是 **API 26** 才有的，而模块 `minSdk 24` —— Android 7.0/7.1 上会 `NoSuchMethodError`。改成按 `SDK_INT` 分流（老版本用 `vibrate(long)`）。
- PATH 的分隔符原先写死 `:`（Unix 口径）。在非 Unix 平台上整条 PATH 会塌成一个不存在的目录名，shell 内建命令还活着，但 `cat` / `ls` 这类外部程序一律 `command not found`（退出码 127）。改用 `File.pathSeparator`。
- 复合命令里的能力调用原先改写成 `cat '<临时文件>'`，**依赖宿主 shell 还能找到 `cat`**——而 SDK 恰恰把 PATH 整个换掉了。改成「能力先跑、结果经环境变量交给 shell 内建的 `printf` 打印」，不再依赖任何外部程序，也不再产生临时文件。
- **分词器不认 `>`** → 能力命令的输出重定向被吞掉，而且是**静默**的：
  `termux-battery-status > b.txt` 会被解析成「能力 `battery-status`，参数 `[>, b.txt]`」，
  于是能力照跑、回 `{"ok":true,...}`，**但文件根本没生成** —— 按本项目的合格标准，这就是「谎报成功」。
  现在 `>` / `>>` 也算操作符，该命令改写成 `printf '%s' "$AGENT_CAP_0_0" > b.txt`，由 shell 真的写文件。
