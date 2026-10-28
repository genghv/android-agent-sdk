---
name: agent-text
description: 按行改沙箱里的文本文件（只改目标几行，不重写整个文件）。当你要修改一个已经存在的文本文件、而且只想动其中几行时使用 —— 比如改一段配置、修正文档里的某句话、给脚本加一行。整文件重写容易把没关系的部分也改坏。
---

# 改文本文件（按行，不整篇重写）

## 为什么别整篇重写

要改一个文件时，最省事的写法是「读出来 → 改 → 整篇写回去」。
但整篇重写有两个真实的代价：

- **措辞会漂**：你每次重新生成一遍全文，没打算改的句子也可能变了意思；
- **token 成本**：长文件每次全量读 + 全量写，一个来回就够呛。

`agent-text patch` 只让你输出**改动的那部分**，其余原样保留。

## 怎么用

```bash
agent-text read notes.md                       # 读一个文件（先看现状）
agent-text patch notes.md "replace-lines 2 2 改过的第二行"   # ★ 只改第 2 行
agent-text patch notes.md "replace-lines 2 3 新的一行\n再一行"  # 改 2-3 行
agent-text patch notes.md "insert-after 1 新增的一行"          # 在第 1 行后插入
agent-text patch notes.md "delete-line 4"                    # 删掉第 4 行
agent-text patch notes.md "replace-block|旧的标题|新的标题"     # 按内容定位（要加引号）
```

输出是 JSON：`ok` / `path` / `chars` / `diff`（改动了哪几行）。
失败时 `ok:false` 且带 `error`，退出码非 0。

## 规格怎么写

| 写法 | 含义 |
|---|---|
| `replace-lines <起> <止> <新内容>` | 替换第「起」到第「止」行（行号从 1 开始，含两端） |
| `insert-after <行号> <新内容>` | 在第「行号」行**之后**插入；行号 0 = 插到最前面 |
| `delete-line <行号>` | 删掉那一行 |
| `replace-block <锚点>\|<新内容>` | 按**内容**定位：把包含锚点的那块整块换掉 |

- 规格里的 `\n` 表示真换行（shell 的引号里传不了真换行）。
- `replace-block` 的锚点用 `|` 分隔。**整段规格要加引号** —— `|` 在 shell 里是管道符。
- 锚点**命中多处会报错**，并把候选行号列出来 —— 那说明锚点不够具体，多带几行上下文。

## 边界（很重要）

- **只能改你自己私有根（`$AGENT_ROOT`）下面的文件**，用相对路径。
  `..`、绝对路径、指向根外面的路径一律拒绝 —— 这是防止你把不该改的东西改了。
- 文件不存在时，`patch` 会把它**当空文件新建**（`replace-lines 1 1 内容`）。
- 单个文件有大小上限（约两万字符），超了会拒绝，不会写盘。

## 和 `agent-memory patch` 的区别

`agent-memory patch` 改的是**记忆条目**（SDK 管的），`agent-text patch` 改的是**任意文本文件**。
改记忆用前者（它会同步索引）；改自己的笔记、配置、脚本用后者。
