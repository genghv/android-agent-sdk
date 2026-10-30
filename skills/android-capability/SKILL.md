---
name: android-capability
description: 在安卓设备上用「类 Linux 命令」完成三类事——跑命令、读写文件、调用手机硬件（手电筒/电池/剪贴板/震动/通知）。当需要和手机交互、操作设备、读取或写入文件、执行 shell 时使用。
---

# Android 能力（device shell）

agent 只需输出命令，命令在设备上的「拟 Linux」沙箱里执行，结果回传。
命令分两层：**基础层**（现成的标准 Linux 命令）和 **硬件层**（`termux-*` 风格，由宿主 App 桥接到安卓 API）。

> 环境细节（你在哪儿、工作目录、垃圾怎么清、定时怎么设、调宿主接口怎么调）
> 见 [agent-workspace](../agent-workspace/SKILL.md)。

## 一、基础层：标准 Linux 命令（现成，无需自己造）

设备自带 Android toybox，**不需要装任何东西**。

### 完整清单（按用途）

> 下面这份是**常见全集**（Android 8～16 的 toybox 都有）。**这台设备上实际有哪些，
> 以系统提示词里那份为准** —— 那是运行时探测出来的，老系统上少几个是正常的。

| 用途 | 命令 |
|---|---|
| 文件/目录读写 | `ls` `pwd` `cp` `mv` `rm` `mkdir` `rmdir` `touch` `ln` `chmod` `find` `xargs` `du` `df` `stat` `readlink` `realpath` `basename` `dirname` `mktemp` |
| 查找/过滤/加工 | `cat` `echo` `printf` `head` `tail` `wc` `cut` `tr` `sort` `uniq` `grep` `egrep` `fgrep` `sed` `awk` `diff` `patch` `nl` `paste` `split` `comm` `tee` `cmp` `file` |
| 打包/压缩/编码 | `tar` `gzip` `gunzip` `unzip` `zip` `cpio` `base64` `xxd` `od` `dd` `cksum` `sha1sum` `md5sum` `sha256sum` `sha512sum` |
| 网络 | `curl` `nc` `ping` `wget` |
| 系统信息/流程控制 | `sh` `env` `printenv` `id` `whoami` `uname` `hostname` `getprop` `date` `sleep` `seq` `expr` `true` `false` `test` `timeout` `which` `clear` `more` `dos2unix` `unix2dos` |

> ⚠️ **系统提示词里只列了「高频核心」那一批**（几十个，常用的那些），
> 完整清单在这里 —— **需要冷门命令时查这张表**。
> 两份都是**白名单 ∩ 本机实存**，所以「查得到但这台机器没有」的情况不会发生。

> ⚠️ **别用 `ls /system/bin` 去列目录** —— Android 上它是 `drwxr-x--x`，
> 普通 App **没有读权限**，列不出来（`Permission denied`）。
> 所以「这台设备到底有什么命令」只能靠系统提示词/本表，不能靠现场 `ls`。

**路径约定**
- `$HOME` = 你的工作目录（相对路径都落这儿），`$PREFIX` = 拟 Linux 根（可执行文件在 `$PREFIX/bin`）。
- 不需要 `sudo`；当前就是 App 身份。

**示例**
```
ls -la .
cat note.txt
echo "hello" > x.txt
find . -name "*.jpg" | head -20
```

⚠️ **跑脚本 ≠ 跑二进制**
- 跑 **shell 脚本**（`sh run.sh`）可以 —— 解释器在 `/system/bin`；
- 跑 **ELF 二进制**（自己编译的程序）**不行** ——
  Android 10 起 targetSdk ≥ 29 的 App 私有目录是 `noexec` 的，退出码 126（`Permission denied`）。
- ⇒ **本环境只有 shell 这一个解释器**（别的语言运行时一律没有）。脚本用 shell 写：`sh 脚本.sh`。
  多行文件用 heredoc：`cat > f.txt << 'EOF' … EOF`。

## 二、硬件层：安卓能力命令（termux-* 风格，宿主 App 实现）

命名沿用 Termux:API 规范（命令 + 参数 + 输出 JSON 到 stdout），实现走「命令 → 桥 → 宿主 App → 安卓 API」。

### 本 SDK **已实现**的 6 个

| 能力 | 命令 | 典型用法 |
|---|---|---|
| **手电筒** | `termux-torch on\|off` | `termux-torch on` / `termux-torch off` |
| 震动 | `termux-vibrate -d 1000` | `termux-vibrate -d 1000` |
| 通知 | `termux-notification` | `termux-notification -t "标题" -c "正文"` |
| 写剪贴板 | `termux-clipboard-set` | `termux-clipboard-set "文本"` |
| 读剪贴板 | `termux-clipboard-get` | `termux-clipboard-get` |
| 电池 | `termux-battery-status` | 返回容量/温度/充电状态 JSON |

### 本 SDK **没有实现**的（别写，写了会落空）

相机、传感器、定位、WiFi 信息、短信、打开文件/URL、存储授权准备 ——
**按设计不做**：这些要么需要特权权限，要么需要额外适配。
模型如果写了这些命令，只会得到「命令找不到」，白白浪费一轮还留下垃圾。

> 想扩展就实现 `DeviceCapability` 接口并注册进 `DeviceCapabilityRegistry`；
> 命令名**必须**与 Termux:API 逐字一致，模型才认。

### SDK 自制的命令（`agent-*`）

这批不在 Termux:API 里，是这套环境特有的：
`agent-workspace` / `agent-cleanup` / `agent-quota` / `agent-schedule` / `agent-http-*`
/ `agent-memory` / `agent-text` / `agent-cred`。
详见 [agent-workspace](../agent-workspace/SKILL.md)、[agent-data-api](../agent-data-api/SKILL.md)、
[agent-scheduling](../agent-scheduling/SKILL.md)。

## 三、使用规则

1. **优先基础层**：能用标准命令解决就别用硬件命令。
2. **一条命令一件事**：便于定位失败；需要串联用 `&&` / `|`。
3. **硬件命令输出是 JSON**：需要取值时用 `grep` / `sed`（无 jq 时）解析。
4. **失败要能看出来**：能力失败会返回 `{"ok":false,"error":"…"}` 并给非零退出码。
   **「如实报错」是合格，「谎报成功」才是不合格** —— 所以看到 `ok:false` 就按 `error` 改，别原样重试。
5. **参数含空格必须加引号**：`termux-notification -t "我的标题" -c "正文内容"`。
6. **路径用环境变量拼**：`$HOME`（工作目录）、`$AGENT_ROOT`（工作区根）、`$PREFIX`（可执行文件）；
   shell 的 cwd 就是 `$HOME`，所以相对路径也落在这儿 —— 别猜目录。

## 四、常见坑

- **跑脚本 ≠ 跑二进制**：跑 shell 脚本可以；跑 ELF 二进制不行（noexec，退出码 126）。
- **读剪贴板要 App 在前台有焦点**（Android 10+ 限制）；读不到时能力会明确报错，不谎报空。
- **明文 HTTP 被系统禁**：宿主接口是 http:// 时，宿主要配 `networkSecurityConfig`。
- **手电筒是开关**：开了要记得关，别把用户的手机留在亮着灯的状态。
- 手机公共存储访问需要 `termux-setup-storage`（本 SDK 未实现该能力，别写）。
