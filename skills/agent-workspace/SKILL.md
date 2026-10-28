---
name: agent-workspace
description: 在安卓手机的「拟 Linux」沙箱里工作时的环境约定与工作区维护规范——你在哪儿、工作目录是哪儿、哪些命令能用、哪些事做不了、空间满了怎么办、垃圾谁负责清。当需要在手机上读写文件、跑命令、或被告知「工作区告警」时使用。
---

# 拟 Linux 工作区（环境约定 + 维护规范）

你在一台 Android 手机上跑在一个宿主 App 提供的「拟 Linux」沙箱里。
这份文档讲清三件事：**你在哪儿**、**你能干什么**、**你怎么维护自己的地盘**。

## 一、你在哪儿

| 变量 | 含义 |
|---|---|
| `HOME` | 你的工作目录，shell 的默认 cwd。相对路径都落在这儿。 |
| `PREFIX` | 拟 Linux 根（`bin/`、`lib/`、`share/`）。可执行文件放这儿。 |
| `TMPDIR` | 临时目录。**必须存在**，否则 heredoc 之类的写法会失败。 |
| `AGENT_ROOT` | 你的**整个工作区**根目录。 |
| `AGENT_TRASH` | 回收站。你删掉的东西先进这儿，不是直接抹掉。 |
| `PATH` | `$PREFIX/bin` + `/system/bin` + `/system/xbin` |

你的工作区在宿主 App 的私有目录里，**与宿主 App 自己的文件是分开的**——
你不会误伤宿主的数据。但注意：你和宿主 App 是**同一个用户**，
所以你仍然**能**读到宿主的其它文件；越界与否靠约定，不靠权限。

## 二、你能用什么命令

### 1) 标准 shell（Android 自带 toybox，不用装任何东西）

`ls` `cat` `echo` `grep` `sed` `awk` `cut` `sort` `head` `tail` `wc` `find` `du` `df`
`chmod` `rm` `cp` `mv` `mkdir` `touch` `ln` `stat` `tar` `unzip` `gzip` `xxd` `base64`
`dd` `sleep` `timeout` `tee` `tr` `curl` `nc` `ping`

> 上面这份是**常见子集**，不是保证。
> **系统提示词里那份清单才是准的** —— 它是运行时在这台设备上探测出来的
> （白名单 ∩ 真实存在），老系统上少几个命令是正常的。
> 不在提示词清单里的命令，别去试。

写文件 / 读文件 / 跑脚本：

```bash
echo "内容" > 文件名                      # 写
cat > 文件名 << 'EOF'                      # 写多行
第一行
第二行
EOF
cat 文件名                                 # 读
echo 'echo hi' > run.sh && sh run.sh       # 跑脚本（**必须显式给解释器**）
rm -f 文件名                               # 删
```

⚠️ **跑脚本 ≠ 跑二进制**：
- 跑 **shell 脚本**（`sh x.sh`）可以 —— 解释器在 `/system/bin`；
- 跑 **ELF 二进制**（自己编译的程序、任何预装的解释器）**不行** ——
  Android 10 起，targetSdk ≥ 29 的 App 私有目录是 `noexec` 的，系统会拒绝执行，
  报 `Permission denied`（退出码 126）。
  ⇒ 想跑二进制，只能宿主把 targetSdk 降到 28，或把二进制放进 `nativeLibraryDir`（要按 ABI 拆包）。

### 2) 设备能力（`termux-*`，与 Termux:API 逐字对齐）

模型是按 Termux 的习惯写命令的，所以命令名**一字不差**：

| 能力 | 命令 |
|---|---|
| 手电筒 | `termux-torch on` / `termux-torch off` |
| 震动 | `termux-vibrate -d 300` |
| 通知 | `termux-notification -t "标题" -c "正文"` |
| 读剪贴板 | `termux-clipboard-get` |
| 写剪贴板 | `termux-clipboard-set "文本"` |
| 电池状态 | `termux-battery-status` |

⚠️ **参数里有空格必须加引号**，否则会被拆成多个词，能力会**如实报错**告诉你加引号。

⚠️ **手电筒是开关**：开了要记得关，别把用户的手机留在亮着灯的状态。

⚠️ **读剪贴板需要 App 在前台有焦点**（Android 10+ 的系统限制）。
读不到时能力会明确报错，**不会谎报「剪贴板是空的」** —— 那是两种不同的失败。

### 3) 我们内部拟 Linux 自制的命令（`agent-*`）

这批**不在 Termux:API 里**，别去别处找：

| 命令 | 干什么 |
|---|---|
| `agent-workspace` | 工作区体检：占用、配额、各目录分布 |
| `agent-cleanup --dry-run` | 列出可清的垃圾（**默认，不删**） |
| `agent-cleanup --apply` | 真的清理（**先进回收站**） |
| `agent-cleanup --purge-trash` | 清掉回收站里超龄的条目 |
| `agent-quota show` | 看当前生效的配额 |
| `agent-quota set 200MB` | 改配额（写 `200MB`/`1GB`/`不限`） |
| `agent-quota reset` | 改回宿主的默认值 |
| `agent-schedule list/add/remove/due` | 定时任务（见 `agent-scheduling` 技能） |
| `agent-http-get` / `agent-http-post` | 调内部接口取数据（见 `agent-data-api` 技能） |

## 三、你不能做的事

- **装应用、点屏幕、改系统设置** —— 这些是特权操作，本环境**不提供**，别去试。
  （不是「暂时没实现」，是**按设计不做**。）
- 你没有 root，也没有别的 App 的数据。

## 四、工作区维护规范（这条会直接影响你的行为）

### 配额不是写死的

配额有三种来源：
- 宿主注入的默认值（默认是**设备剩余空间的一个百分比**，跟着实况走）；
- 你自己用 `agent-quota set` 改过（会存下来，下次启动仍生效）；
- `agent-quota reset` 改回默认。

用 `agent-quota show` 看当前生效的是哪一个。

### 垃圾你自己清

- **你产生的东西你自己负责清理**，别指望有人替你收拾。
- 占用接近或超过配额时，你会收到一条 `[工作区告警]`，里面直接给了命令，照做即可：
  1. `agent-cleanup --dry-run` —— 先看能清多少、为什么清它；
  2. `agent-cleanup --apply` —— 确认后执行；
  3. `agent-cleanup --purge-trash` —— 回收站里的也清掉（不然只是搬了个家）。
- 确实需要更多空间时，可以 `agent-quota set 300MB` 调整 —— 配额是活的，不是死的坎。

### 什么是「垃圾」，什么是「有用」

默认**保守**：只有明确命中垃圾规则的才算垃圾，其余一律保留。

| 保留（有用） | 垃圾（可清） |
|---|---|
| `*.py` `*.sh` `*.js` `*.json` `*.csv` `*.md` `*.txt` `*.sql` `*.yaml` 等源码与数据 | `*.tmp` `*.log` `*.part` `*.bak` `*.pyc` `nohup.out` `core.*` `__pycache__` 等 |

想保护某个目录：在里面放一个空文件 **`.agent-keep`**，整个目录就不会被动。

删除**默认进回收站**（`AGENT_TRASH`），不是直接抹掉 —— 删错了还能找回来。

## 五、失败怎么读

能力命令失败时返回 `{"ok":false,"error":"…"}` 并给非零退出码。
**「如实报错」是合格，「谎报成功」才是不合格** —— 比如读不到剪贴板时明确报错，
而不是回一个空字符串骗你说剪贴板是空的。

所以：看到 `ok:false` 就按 `error` 里说的改，别重试同一条命令。
