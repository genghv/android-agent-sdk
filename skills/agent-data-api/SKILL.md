---
name: agent-data-api
description: 在安卓设备上调用内部数据接口取数的约定——用 agent-http-get/agent-http-post 发请求、鉴权由宿主注入所以命令里不出现 token、相对路径怎么拼、非 2xx 和明文 HTTP 怎么处理。当需要从内部 API 拿数据（而不是让用户手动打开 App）时使用。
---

# 宿主数据 API 调用（agent-http-*）

设备上的 agent 要自己取数据时，用 `agent-http-get` / `agent-http-post`，
**不要**去拼 `curl` 命令行。

## 一、为什么不用 curl

设备自带 `/system/bin/curl`，但用它有三个问题：

1. **鉴权 header 得写进命令行** —— token 会出现在转录、日志、shell 历史里；
2. **输出不可控** —— 一次拉 5MB 的 JSON 直接塞进模型上下文；
3. **失败不好归类** —— curl 的退出码分不清「连不上」和「对方返回 500」。

`agent-http-*` 把这三件事都收好了：鉴权由宿主注入、响应体有上限、错误说清原因。

## 二、命令

```
agent-http-get  <url 或相对路径> [--header "K: V"] [--timeout 15000] [--max-bytes 200000]
agent-http-post <url 或相对路径> [--header "K: V"] [--data "{...}"] [--timeout 15000]
```

### 相对路径怎么用

宿主配了基地址（内部 API 的根，比如 `https://internal.corp/api`）时，
你写相对路径就行：

```bash
agent-http-get /api/weather
agent-http-post /api/query --data "{\"q\":\"最近三天的降雨量\"}"
```

**基地址和鉴权 header 是宿主注入的，你不用知道、也不该在命令里写 token。**
（命令里写出来的 token 会进模型上下文和日志。）

没有配基地址时，相对路径会明确报错，让你给完整 URL。

### 返回什么

```json
{"ok":true,"status":200,"content_type":"application/json","bytes":1234,
 "truncated":false,"elapsed_ms":110,"body":"…","headers":[…]}
```

- `body` 是响应正文（字符串）。JSON 接口的话它自己是一段 JSON 文本。
- `truncated:true` 表示响应体超了上限被截断了 —— **这时别当完整数据用**，
  先想办法缩小查询范围。
- 超过 2xx 视为**失败**（`ok:false`），错误里带状态码和响应片段
  —— 那是「取数据失败了」，不是「命令写错了」。

## 三、常见错误怎么读

| 错误里的话 | 什么意思 | 你该做什么 |
|---|---|---|
| `明文 HTTP 被 Android 的网络安全策略拒绝` | 宿主没给这个域名配 `networkSecurityConfig`，或该接口没有 https | 这是**宿主配置问题**，不是命令写错了。换 https 地址，或告诉宿主加配置。 |
| `解析不了域名` | DNS 不通 | 检查设备网络；确认这台设备能不能访问该地址（手机在 4G 上就访问不了内网）。 |
| `请求超时` | 连不上 | 同上 —— 设备可能不在能访问该地址的网络里。 |
| `header 的值含非 ASCII 字符` | HTTP 头按 RFC 只能放可打印 ASCII | 中文内容放到请求体（`--data`）里；确实要放中文头值，自己写百分号编码（如 `%E5%8D%8E%` 形式）。 |

## 四、几条规矩

1. **一条命令一件事** —— 先 GET 探一下接口通不通，再做复杂查询。
2. **带 token 的地方不要写进命令** —— 用相对路径，让宿主注入鉴权。
3. **别把整个响应直接倒进上下文** —— 需要什么取什么；响应体大就先用
   `--max-bytes` 限制，或者在服务端就分好页。
4. **命令里有空格/引号要加引号** —— `--data "{\"k\":\"v\"}"` 这种写法，
   里面的双引号要用 `\"` 转义。
5. **接口返回非 2xx 就是没拿到数据** —— 读错误里的状态码和响应片段，
   判断是参数错、没鉴权，还是服务端的问题，然后改命令再来。
