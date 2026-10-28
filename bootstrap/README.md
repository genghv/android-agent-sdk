# bootstrap —— 可选的 Termux 运行时（**独立分发，不属于主发行包**）

这个目录**不随主包 `android-agent-sdk-package-*.zip` 一起分发**。
它是单独打的一个包 `android-agent-sdk-bootstrap-*.zip`。

## 这里面是什么

| 文件 | 说明 |
|---|---|
| `bootstrap-aarch64-lean.zip` | 精简版 Termux 运行时（bash / coreutils / curl 等，aarch64） |

原始全量版本（约 31.5MB）：<https://github.com/termux/termux-packages/releases>
本包是精简版（删掉了 `include/`、文档、测试二进制；**运行时程序件一个没动**）。

## 许可

Termux 是 **GPLv3**（copyleft）。本归档**自带完整的许可包**，随二进制一起分发：

- `share/LICENSES/GPL-3.0.txt`（以及 AGPL-3.0 / LGPL-2.x / LGPL-3.0 / Apache-2.0 / MIT 等 25 份）
- `share/doc/termux-licenses/copyright`
- `var/lib/dpkg/info/termux-licenses.list` / `.md5sums`（逐文件校验清单）

这些文件在 `bootstrap-aarch64-lean.zip` **内部**，解压后位于 `share/LICENSES/`。
如果你二次分发这个归档，**必须把上述许可文本一并带上**（GPLv3 §4），
别只把二进制拿走 —— 那是不合规的。

主包 `android-agent-sdk` 自身是 **Apache-2.0**，与这个 GPLv3 运行时是**两个独立作品**，
所以刻意拆开分发。

## ⚠️ 重要：它在现代 Android 上跑不起来

真机实测（Android 10+ / `targetSdk ≥ 29`）：把二进制解压到 App 私有目录后执行，
系统报 `/system/bin/sh: ./t: Permission denied`，**退出码 126**。

原因是 App 私有目录（`/data/data/<pkg>/files/`）默认挂在 **noexec** 挂载点上。

⇒ **这个运行时目前只能当作「可选参考」，不是可用路径。**

SDK 的设备能力层**不依赖它** —— 那 6 个 `termux-*` 能力命令调用的是系统自带的
`/system/bin` 里的程序（逐个 `stat` 探测，见《接入指南》「目录列不出来」一节），
在真机上已全部跑通。

要真正用上 Termux 运行时，可选方案（按推荐顺序）：

1. **不装** —— 直接用系统命令，能力已覆盖。
2. **改用 Termux 官方 App** 装在同一台设备上，通过 `am` / intent 调它执行。
3. 把私有目录的 noexec 绕开 —— 需要 root 或改挂载点，属于超出普通权限范围的操作。

要不要用、用哪种，见《接入指南》的「私有目录不能执行二进制」一节。
